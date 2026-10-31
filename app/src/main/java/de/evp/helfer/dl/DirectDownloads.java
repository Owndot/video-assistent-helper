package de.evp.helfer.dl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.ControlCommands;
import de.evp.helfer.net.NetBlockedException;
import de.evp.helfer.net.SafeHttpClient;
import de.evp.helfer.net.SafeResponse;

/**
 * Direkte Downloads öffentlich erreichbarer MP4-Dateien (Phase C2). Einziger Netzwerkweg: {@link SafeHttpClient};
 * eine abgelehnte Sicherheitsprüfung beendet den Auftrag (kein anderer Abrufweg existiert in dieser Klasse).
 *
 * <ul>
 *   <li>Auftragskennung vom Browser (32 Hex): ein Start je Kennung, auch über Neustarts (Journal). Ein wiederholter
 *       oder verspäteter dlstart liefert den bekannten Zustand und startet nichts.</li>
 *   <li>Höchstens {@link #MAX_PARALLEL} gleichzeitig; darüber "busy" (nicht vermerkt, später erneut möglich).</li>
 *   <li>Prüft Status 200, Content-Length (Pflicht, ≤ Grenze), Content-Type, MP4-Signatur, freien Speicher vor und
 *       während des Downloads und die empfangene Menge (genau Content-Length).</li>
 *   <li>Unterbrechung: Fortsetzen nur mit starkem ETag (If-Range, starker Vergleich), Status 206 und exakt passendem
 *       Content-Range; höchstens {@link #MAX_RESUMES}-mal; sonst Fehler und Entfernen des Unfertigen.</li>
 *   <li>Abbruch jederzeit (auch während Verbindungsaufbau); jedes Ende ohne Erfolg entfernt das Unfertige.</li>
 *   <li>Antworten, Journal und Rückmeldungen enthalten nie die URL.</li>
 * </ul>
 */
public final class DirectDownloads implements ControlCommands.DownloadControl {
    public static final int MAX_PARALLEL = 2;
    public static final int MAX_RESUMES = 3;
    public static final long MAX_FILE_BYTES = 16L << 30;
    public static final long SPACE_RESERVE_BYTES = 64L << 20;
    static final int JOURNAL_MAX = 256;
    static final long SPACE_RECHECK_BYTES = 8L << 20;
    static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d{1,19})-(\\d{1,19})/(\\d{1,19})");

    /** Rückmeldungen für Benachrichtigungen: nie mit URL. */
    public interface Listener {
        void progress(String id, String title, long bytes, long total);
        /** {@code saved}: Verweis der Ablage bei "done", sonst null. */
        void finished(String id, String title, String state, String reason, Object saved);
    }

    enum State { RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }

    static final class Job {
        final String id;
        final String title;
        volatile State state = State.RUNNING;
        volatile long bytes, total = -1;
        volatile String reason = "";
        volatile String ref;      // Verweis der Ablage (Journal), nie die URL
        final SafeHttpClient.Cancellation cancel = new SafeHttpClient.Cancellation();
        Job(String id, String title) { this.id = id; this.title = title; }
    }

    /** Endgültiger Ausgang eines Auftrags (für den Abbruch der Schleife). */
    static final class Fail extends Exception {
        final String reason;
        Fail(String reason) { super(reason, null, false, false); this.reason = reason; }
    }

    private final SafeHttpClient http;
    private final DownloadStore store;
    private final Listener listener;
    private final long callTimeoutMs;
    private final ExecutorService workers;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private boolean stopped;

    public DirectDownloads(SafeHttpClient http, DownloadStore store, Listener listener) throws IOException {
        this(http, store, listener, SafeHttpClient.MAX_CALL_TIMEOUT_MS);
    }

    DirectDownloads(SafeHttpClient http, DownloadStore store, Listener listener, long callTimeoutMs) throws IOException {
        this.http = http;
        this.store = store;
        this.listener = listener;
        this.callTimeoutMs = callTimeoutMs;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(MAX_PARALLEL, MAX_PARALLEL, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "evp-mp4");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);     // ohne Auftrag keine wartenden Threads
        this.workers = pool;
        loadJournal();
    }

    // ------------------------------------------------------------------ Befehle (DownloadControl)

    @Override public String start(String id, String url, String name) {
        Job job;
        synchronized (this) {
            Job known = jobs.get(id);
            if (known != null) return json(known);           // doppelter oder verspäteter Start: nichts Neues
            if (stopped) return busy(id);
            if (running.get() >= MAX_PARALLEL) return busy(id);
            job = new Job(id, displayTitle(name));
            jobs.put(id, job);
            running.incrementAndGet();
            try {
                saveJournal();                                    // erst vermerkt, dann gestartet (at most once)
            } catch (IOException e) {
                jobs.remove(id);
                running.decrementAndGet();
                return busy(id);
            }
        }
        final Job j = job;
        try {
            workers.execute(() -> run(j, url));
        } catch (RuntimeException rejected) {
            finish(j, State.FAILED, "stopped", null);
        }
        return json(j);
    }

    @Override public synchronized String status(String id) {
        Job j = jobs.get(id);
        return j == null ? unknown(id) : json(j);
    }

    @Override public String cancel(String id) {
        Job j;
        synchronized (this) { j = jobs.get(id); }
        if (j == null) return unknown(id);
        j.cancel.cancel();
        return json(j);
    }

    /**
     * Dienstende: keine neuen Starts, alle laufenden Aufträge abbrechen (ihre Arbeiter räumen selbst auf). Die Instanz
     * bleibt je Prozess bestehen, damit ein Dienst-Neustart dasselbe Journal weiterführt.
     */
    public void stopAll() {
        List<Job> snapshot;
        synchronized (this) { stopped = true; snapshot = new ArrayList<>(jobs.values()); }
        for (Job j : snapshot) if (j.state == State.RUNNING) j.cancel.cancel();
    }

    /** Dienststart: Starts wieder annehmen. */
    public synchronized void serviceStarted() { stopped = false; }

    // ------------------------------------------------------------------ Ablauf eines Auftrags

    private void run(Job job, String url) {
        DownloadStore.Target target = null;
        SafeResponse response = null;
        State state = State.FAILED;
        String reason = "network";
        Object saved = null;
        try {
            // Ablage zuerst: ohne Schreibrecht (Android 8–9: Speicherberechtigung) kein Netzwerkzugriff.
            if (!store.writable()) throw new Fail("storage_permission");
            // Neue Verbindung je Auftrag: eine ruhende, vom Server schon geschlossene Pool-Verbindung wäre ohne
            // automatische Wiederholung ein Fehlschlag (siehe SafeHttpClient.closeIdleConnections).
            http.closeIdleConnections();
            response = http.get(url, -1, -1, MAX_FILE_BYTES, callTimeoutMs, job.cancel);
            checkCancelled(job);
            if (response.code() != 200) throw new Fail("http_" + response.code());
            long total = response.contentLength();
            if (total <= 0) throw new Fail("no_length");
            if (total > MAX_FILE_BYTES) throw new Fail("too_large");
            if (!acceptedType(response.contentType())) throw new Fail("type");
            if (!identityEncoding(response.contentEncoding())) throw new Fail("encoding");
            job.total = total;
            // Fortsetzen nur mit starkem ETag (RFC 9110 8.8.1/13.1.5); Last-Modified und schwache ETags genügen nicht.
            String etag = strongEtag(response.etag());
            String finalUrl = response.url();
            if (freeBytes() < total + SPACE_RESERVE_BYTES) throw new Fail("no_space");
            InputStream in = response.body();
            byte[] head = new byte[8];
            int h = readFully(in, head);
            if (h < 8 || !mp4Signature(head)) throw new Fail("not_mp4");
            try { target = store.create(fileName(job.title), "video/mp4"); } catch (IOException e) { throw new Fail("storage"); }
            OutputStream out;
            try { out = target.out(); } catch (IOException e) { throw new Fail("storage"); }
            // Verweis vermerken, bevor Daten geschrieben werden: nach einem Absturz lässt sich so feststellen, ob die
            // Datei schon veröffentlicht war (dann "done") oder nicht (dann "interrupted", Reste räumt die Ablage ab).
            job.ref = target.ref();
            synchronized (this) {
                try { saveJournal(); } catch (IOException e) { throw new Fail("storage"); }
            }
            write(out, head, 8);
            job.bytes = 8;
            byte[] buf = new byte[64 << 10];
            long nextSpaceCheck = SPACE_RECHECK_BYTES, lastNote = 0;
            int resumes = 0;
            while (job.bytes < total) {
                checkCancelled(job);
                int n;
                try {
                    n = in.read(buf, 0, (int) Math.min(buf.length, total - job.bytes));
                    if (n < 0) throw new IOException("short body");
                } catch (NetBlockedException blocked) {
                    throw blocked;
                } catch (IOException interrupted) {
                    checkCancelled(job);
                    if (resumes >= MAX_RESUMES || etag == null) throw new Fail("network");
                    resumes++;
                    response.close();
                    // If-Range: der Server liefert 206 nur für dieselbe Darstellung, sonst 200 (dann Fehler, nie Mischen).
                    // Grenze = Gesamtlänge: eine falsche Antwort (200 statt 206) wird so von checkResume benannt.
                    response = http.get(finalUrl, job.bytes, total - 1, etag, total, callTimeoutMs, job.cancel);
                    checkCancelled(job);
                    checkResume(response, job.bytes, total, etag);
                    in = response.body();
                    continue;
                }
                write(out, buf, n);
                job.bytes += n;
                if (job.bytes >= nextSpaceCheck) {
                    nextSpaceCheck = job.bytes + SPACE_RECHECK_BYTES;
                    // Rest muss weiter passen (halbe Reserve als Puffer für andere Apps); sonst früh und sauber abbrechen.
                    if (freeBytes() < total - job.bytes + SPACE_RESERVE_BYTES / 2) throw new Fail("no_space");
                }
                long now = System.nanoTime();
                if (now - lastNote > 500_000_000L) { lastNote = now; listener.progress(job.id, job.title, job.bytes, total); }
            }
            if (in.read() != -1) throw new Fail("long_body");
            checkCancelled(job);
            try { out.close(); saved = target.commit(); } catch (IOException e) { throw new Fail("storage"); }
            job.ref = target.ref();   // nach dem Veröffentlichen (Android 8–9: endgültiger Dateiname)
            target = null;
            state = State.DONE;
            reason = "";
        } catch (Fail f) {
            reason = f.reason;
        } catch (NetBlockedException blocked) {
            // Sicherheitsprüfung abgelehnt: Ende des Auftrags. Kein zweiter Versuch, kein anderer Abrufweg.
            reason = blocked.reason == NetBlockedException.Reason.BODY_TOO_LARGE ? "too_large" : "blocked_" + blocked.reason.name().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            reason = "network";
        } finally {
            if (response != null) response.close();
            if (target != null) target.abort();
        }
        if (state != State.DONE && job.cancel.isCancelled()) { state = State.CANCELLED; reason = ""; }
        finish(job, state, reason, saved);    // erst nach dem Aufräumen: "beendet" heißt, nichts Unfertiges bleibt
    }

    private long freeBytes() throws Fail {
        try { return store.freeBytes(); } catch (IOException e) { throw new Fail("storage"); }
    }

    /** Schreibfehler (z. B. Speicher voll) sind Ablagefehler, keine Netzwerkunterbrechung: nie Fortsetzen. */
    private static void write(OutputStream out, byte[] b, int n) throws Fail {
        try { out.write(b, 0, n); } catch (IOException e) { throw new Fail("storage"); }
    }

    private static void checkCancelled(Job job) throws Fail {
        if (job.cancel.isCancelled()) throw new Fail("cancelled");
    }

    static void checkResume(SafeResponse r, long start, long total, String etag) throws Fail {
        // 200 auf eine Anfrage mit If-Range: Darstellung geändert oder Range nicht unterstützt – beides kein Fortsetzen.
        if (r.code() != 206) throw new Fail(r.code() == 200 ? "range_ignored" : "range_http_" + r.code());
        if (!identityEncoding(r.contentEncoding())) throw new Fail("encoding");
        String type = r.contentType();
        if (type != null && type.trim().toLowerCase(Locale.ROOT).startsWith("multipart/")) throw new Fail("range_multipart");
        String cr = r.contentRange();
        Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr.trim());
        if (m == null || !m.matches()) throw new Fail("range_header");
        long s, e, t;
        try { s = Long.parseLong(m.group(1)); e = Long.parseLong(m.group(2)); t = Long.parseLong(m.group(3)); }
        catch (NumberFormatException nfe) { throw new Fail("range_header"); }
        if (s != start || e != total - 1 || t != total) throw new Fail("range_mismatch");
        if (r.contentLength() != total - start) throw new Fail("range_length");
        // Starker Vergleich (RFC 9110 8.8.3.2): beide stark und zeichengleich; fehlender oder schwacher ETag = geändert.
        if (etag == null || !etag.equals(strongEtag(r.etag()))) throw new Fail("changed");
    }

    private void finish(Job job, State state, String reason, Object saved) {
        synchronized (this) {
            job.state = state;
            job.reason = reason;
            running.decrementAndGet();
            try { saveJournal(); } catch (IOException ignored) { /* Zustand bleibt im Speicher; nächster Start: interrupted */ }
        }
        listener.finished(job.id, job.title, state.name().toLowerCase(Locale.ROOT), reason, saved);
    }

    // ------------------------------------------------------------------ Prüfungen

    /** Der ETag, wenn er stark und syntaktisch gültig ist; sonst null (kein Fortsetzen). */
    static String strongEtag(String etag) {
        return SafeHttpClient.isStrongEtag(etag) ? etag : null;
    }

    /** Nur unkodierte Rümpfe (Accept-Encoding: identity); gzip o. Ä. würde Längen und Bereiche verfälschen. */
    static boolean identityEncoding(String contentEncoding) {
        return contentEncoding == null || contentEncoding.trim().isEmpty() || contentEncoding.trim().equalsIgnoreCase("identity");
    }

    static boolean acceptedType(String contentType) {
        if (contentType == null) return true;
        String t = contentType.toLowerCase(Locale.ROOT);
        int semi = t.indexOf(';');
        if (semi >= 0) t = t.substring(0, semi);
        t = t.trim();
        return t.equals("video/mp4") || t.equals("video/quicktime") || t.equals("video/x-m4v") || t.equals("application/mp4")
            || t.equals("application/octet-stream") || t.equals("binary/octet-stream");
    }

    /** ISO-BMFF: die ersten 8 Byte sind Boxgröße und Boxtyp; erlaubt sind die üblichen ersten Boxen einer MP4-Datei. */
    static boolean mp4Signature(byte[] head) {
        String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
        return type.equals("ftyp") || type.equals("moov") || type.equals("mdat") || type.equals("free") || type.equals("skip")
            || type.equals("wide") || type.equals("pnot") || type.equals("uuid");
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    /** Anzeigetitel (Benachrichtigung): nie aus der URL; nur sichere Zeichen. */
    static String displayTitle(String name) {
        if (name == null) return "Video";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 80; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || " -_.,()[]!+&'".indexOf(c) >= 0) b.append(c);
        }
        String t = b.toString().trim();
        return t.isEmpty() ? "Video" : t;
    }

    static String fileName(String title) {
        String t = title.replace('.', ' ').replace('/', ' ').trim();
        return (t.isEmpty() ? "Video" : t) + ".mp4";
    }

    // ------------------------------------------------------------------ Antworten (ohne URL)

    private static String json(Job j) {
        return "{\"ok\":true,\"id\":\"" + j.id + "\",\"state\":\"" + j.state.name().toLowerCase(Locale.ROOT) + "\",\"bytes\":" + j.bytes
            + ",\"total\":" + j.total + ",\"reason\":\"" + j.reason + "\"}";
    }

    private static String busy(String id) { return "{\"ok\":false,\"id\":\"" + id + "\",\"state\":\"busy\"}"; }

    private static String unknown(String id) { return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"unknown\"}"; }

    // ------------------------------------------------------------------ Journal

    private void loadJournal() throws IOException {
        byte[] raw = store.loadJournal();
        if (raw == null) return;
        String[] lines = new String(raw, StandardCharsets.US_ASCII).split("\n");
        int fields = lines.length == 0 ? -1 : lines[0].equals("mp4-jobs-v2") ? 6 : lines[0].equals("mp4-jobs-v1") ? 5 : -1;
        if (fields < 0) throw new IOException("corrupt download journal");
        boolean changed = false;
        try {
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split(" ", -1);
                if (f.length != fields || !f[0].matches("[0-9a-f]{32}")) throw new IOException("corrupt download journal");
                Job j = new Job(f[0], "Video");
                State st = State.valueOf(f[1]);
                j.bytes = Long.parseLong(f[2]);
                j.total = Long.parseLong(f[3]);
                j.reason = f[4].matches("[a-z0-9_]{0,40}") ? f[4] : "";
                j.ref = fields == 6 && !f[5].equals("-") ? new String(Base64.getUrlDecoder().decode(f[5]), StandardCharsets.UTF_8) : null;
                if (st == State.RUNNING) {
                    // Neustart während des Downloads: war die Datei schon veröffentlicht (Absturz zwischen Veröffentlichen
                    // und Journal), gilt der Auftrag als fertig; sonst als unterbrochen (Reste räumt die Ablage ab).
                    st = j.ref != null && published(j.ref, j.total) ? State.DONE : State.INTERRUPTED;
                    if (st == State.DONE) { j.bytes = j.total; j.reason = ""; }
                    changed = true;
                }
                j.state = st;
                jobs.put(j.id, j);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("corrupt download journal", e);
        }
        if (changed) saveJournal();
    }

    private boolean published(String ref, long total) {
        try { return total > 0 && store.isPublished(ref, total); } catch (IOException | RuntimeException e) { return false; }
    }

    /** Mit Sperre auf this aufrufen. Älteste beendete Einträge fallen über {@link #JOURNAL_MAX} heraus. */
    private void saveJournal() throws IOException {
        while (jobs.size() > JOURNAL_MAX) {
            String drop = null;
            for (Job j : jobs.values()) if (j.state != State.RUNNING) { drop = j.id; break; }
            if (drop == null) break;
            jobs.remove(drop);
        }
        StringBuilder b = new StringBuilder("mp4-jobs-v2");
        for (Job j : jobs.values()) {
            b.append('\n').append(j.id).append(' ').append(j.state.name()).append(' ').append(j.bytes).append(' ').append(j.total).append(' ').append(j.reason)
                .append(' ').append(j.ref == null ? "-" : Base64.getUrlEncoder().withoutPadding().encodeToString(j.ref.getBytes(StandardCharsets.UTF_8)));
        }
        store.saveJournal(b.toString().getBytes(StandardCharsets.US_ASCII));
    }
}
