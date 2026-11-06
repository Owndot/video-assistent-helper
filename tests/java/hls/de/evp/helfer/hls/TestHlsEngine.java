package de.evp.helfer.hls;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.net.LocalTestClients;
import de.evp.helfer.net.SafeHttpClient;

/**
 * P4b-3/5: HLS-Koordinator gegen den echten SafeHttpClient und einen lokalen Server mit den synthetischen Fixtures.
 * Der Remux ist hier eine Nachbildung (Verkettung + Längenprüfung): die echte Media3-Umsetzung prüfen die
 * Emulatortests. Geprüft werden Ablauf, Grenzen, Journal, Absturz an jeder Schreib-/Commit-Grenze, Wiederaufnahme
 * nur mit starkem ETag, persistenter Abbruchvorrang, Speicherquote und dass nie eine Adresse/ein Token im Journal,
 * in Antworten oder in Rückmeldungen steht.
 */
public final class TestHlsEngine {
    static int passed, failed;
    static File fx, base;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    // ------------------------------------------------------------------ lokaler Server

    /** Steuerbarer Fixture-Server: /dyn/<fixture>/… schreibt ein Token (?t=) in jede Ressourcenzeile und prüft es. */
    static final class Server {
        final HttpServer srv;
        final int port;
        volatile String token = "T1";
        // strong | weak (Server vergleicht If-Range fälschlich auch schwach) | none | bogus200 (200 mit Content-Range)
        // | lenlie (206 mit falscher Gesamtlänge bei gleichem starken ETag)
        volatile String etagMode = "strong";
        volatile int delayMs = 0;
        volatile boolean chunked = false;          // Segmente ohne Content-Length
        volatile String changedPath = null;        // diese Ressource liefert anderen Inhalt (anderer ETag)
        volatile String masterExtra = "";          // wird an Master angehängt (Playlist-Änderung)
        volatile String bandwidth = null;          // P4c-3: BANDWIDTH im Master ersetzen (z. B. massiv unterschätzte Bitrate)
        // P4c: so viele volle Segmentabrufe beantwortet der Server mit 503 (vorübergehend überlastet)
        final AtomicInteger fail503 = new AtomicInteger(), answered503 = new AtomicInteger(), answered403 = new AtomicInteger(), requests = new AtomicInteger();
        volatile Runnable on503 = null;            // z. B. Netz abschalten (Flugmodus) beim ersten 503
        final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        final Map<String, AtomicInteger> fullGets = new ConcurrentHashMap<>(), rangeGets = new ConcurrentHashMap<>(), rangeMisses = new ConcurrentHashMap<>(),
            rangeBroken = new ConcurrentHashMap<>();
        final List<String> leaks = new CopyOnWriteArrayList<>();

        Server() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = srv.getAddress().getPort();
            srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            srv.createContext("/", this::handle);
            srv.start();
        }

        String url(String fixture, String file) { return "http://127.0.0.1:" + port + "/dyn/" + fixture + "/" + file + "?t=" + token; }

        void reset() { fullGets.clear(); rangeGets.clear(); rangeMisses.clear(); rangeBroken.clear(); maxInFlight.set(0); delayMs = 0; chunked = false; changedPath = null; masterExtra = ""; bandwidth = null; etagMode = "strong"; token = "T1";
            fail503.set(0); answered503.set(0); answered403.set(0); on503 = null; }

        int full(String suffix) { int n = 0; for (Map.Entry<String, AtomicInteger> e : fullGets.entrySet()) if (e.getKey().endsWith(suffix)) n += e.getValue().get(); return n; }
        int fullSegments() { int n = 0; for (Map.Entry<String, AtomicInteger> e : fullGets.entrySet()) if (!e.getKey().endsWith(".m3u8")) n += e.getValue().get(); return n; }
        int ranges() { int n = 0; for (AtomicInteger a : rangeGets.values()) n += a.get(); return n; }

        void handle(HttpExchange x) throws IOException {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            requests.incrementAndGet();
            try {
                for (String h : new String[] {"Cookie", "Authorization", "Referer", "Origin"})
                    if (x.getRequestHeaders().containsKey(h)) leaks.add(h);
                String path = x.getRequestURI().getRawPath(), q = x.getRequestURI().getRawQuery();
                if (!path.startsWith("/dyn/")) { x.sendResponseHeaders(404, -1); return; }
                String rel = path.substring(5);
                File f = new File(fx, rel);
                if (!f.isFile() || !f.getCanonicalPath().startsWith(fx.getCanonicalPath())) { x.sendResponseHeaders(404, -1); return; }
                if (q == null || !q.equals("t=" + token)) { answered403.incrementAndGet(); x.sendResponseHeaders(403, -1); return; }
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] body = Files.readAllBytes(f.toPath());
                if (rel.endsWith(".m3u8")) {
                    body = tokenize(new String(body, StandardCharsets.UTF_8), rel.endsWith("master.m3u8")).getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                    count(fullGets, rel);
                    x.sendResponseHeaders(200, body.length);
                    try (OutputStream o = x.getResponseBody()) { o.write(body); }
                    return;
                }
                if (x.getRequestHeaders().getFirst("Range") == null && fail503.getAndDecrement() > 0) {
                    answered503.incrementAndGet();
                    Runnable r = on503; if (r != null) r.run();
                    x.sendResponseHeaders(503, -1);
                    return;
                }
                fail503.accumulateAndGet(0, Math::max);
                if (rel.equals(changedPath)) { body = body.clone(); body[body.length - 1] ^= 1; }
                String etag = etagMode.equals("none") ? null : (etagMode.equals("weak") ? "W/" : "") + "\"" + sha(body).substring(0, 16) + "\"";
                if (etag != null) x.getResponseHeaders().set("ETag", etag);
                String range = x.getRequestHeaders().getFirst("Range"), ifRange = x.getRequestHeaders().getFirst("If-Range");
                if (range != null && ifRange != null && etagMode.equals("bogus200")) {
                    count(rangeBroken, rel);
                    x.getResponseHeaders().set("Content-Range", "bytes 0-0/" + body.length);
                    x.sendResponseHeaders(200, 1);
                    try (OutputStream o = x.getResponseBody()) { o.write(body, 0, 1); }
                    return;
                }
                if (range != null && ifRange != null && !(etag != null && ifRange.equals(etag))) {
                    // If-Range passt nicht: Server liefert die ganze (geänderte) Darstellung; der Client liest sie nicht
                    count(rangeMisses, rel);
                    x.sendResponseHeaders(200, body.length);
                    try (OutputStream o = x.getResponseBody()) { o.write(body); }
                    return;
                }
                if (range != null && range.equals("bytes=0-0") && ifRange != null) {
                    count(etagMode.equals("lenlie") ? rangeBroken : rangeGets, rel);
                    x.getResponseHeaders().set("Content-Range", "bytes 0-0/" + (body.length + (etagMode.equals("lenlie") ? 1 : 0)));
                    x.sendResponseHeaders(206, 1);
                    try (OutputStream o = x.getResponseBody()) { o.write(body, 0, 1); }
                    return;
                }
                count(fullGets, rel);
                x.sendResponseHeaders(200, chunked ? 0 : body.length);
                try (OutputStream o = x.getResponseBody()) { o.write(body); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client hat abgebrochen
            } finally {
                inFlight.decrementAndGet();
                x.close();
            }
        }

        String tokenize(String m3u8, boolean master) {
            StringBuilder b = new StringBuilder();
            for (String line : m3u8.split("\n")) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("#")) line = line + "?t=" + token;
                else line = line.replaceAll("URI=\"([^\"]+)\"", "URI=\"$1?t=" + token + "\"");
                if (master && bandwidth != null && line.startsWith("#")) line = line.replaceAll("BANDWIDTH=\\d+", "BANDWIDTH=" + bandwidth);
                b.append(line).append('\n');
            }
            if (master) b.append(masterExtra);
            return b.toString();
        }

        static void count(Map<String, AtomicInteger> m, String k) { m.computeIfAbsent(k, z -> new AtomicInteger()).incrementAndGet(); }

        void stop() { srv.stop(0); }

        /** Wartet (begrenzt), bis der Server keine Anfrage mehr bearbeitet. */
        void quiesce() throws InterruptedException {
            long end = System.nanoTime() + 5_000_000_000L;
            while (inFlight.get() > 0 && System.nanoTime() < end) Thread.sleep(5);
        }
    }

    // ------------------------------------------------------------------ Ablage, Remux, Rückmeldungen

    static final class DirStore implements DownloadStore {
        final File dir;
        volatile long free = Long.MAX_VALUE / 4;
        volatile long freeAfterSegments = -1, freeLater = 0;
        final AtomicInteger committed = new AtomicInteger(), aborted = new AtomicInteger();
        volatile boolean writable = true;
        DirStore(File dir) { this.dir = dir; dir.mkdirs(); }
        @Override public long freeBytes() { return free; }
        @Override public boolean writable() { return writable; }
        @Override public boolean isPublished(String ref, long total) {
            File f = new File(ref);
            return dir.equals(f.getParentFile()) && f.isFile() && f.length() == total;
        }
        @Override public Target create(String displayName, String mime) throws IOException {
            File part = File.createTempFile(".part-", ".tmp", dir);
            File fin = new File(dir, displayName);
            OutputStream fos = new FileOutputStream(part);
            return new Target() {
                boolean done;
                @Override public OutputStream out() { return fos; }
                @Override public String ref() { return fin.getPath(); }
                @Override public synchronized Object commit() throws IOException {
                    fos.close();
                    if (fin.exists() || !part.renameTo(fin)) throw new IOException("rename");
                    done = true;
                    committed.incrementAndGet();
                    return fin;
                }
                @Override public synchronized void abort() {
                    if (done) return;
                    try { fos.close(); } catch (IOException ignored) { }
                    part.delete();
                    aborted.incrementAndGet();
                }
            };
        }
        @Override public byte[] loadJournal() { return null; }
        @Override public void saveJournal(byte[] data) { }
        String[] files() { String[] l = dir.list(); Arrays.sort(l); return l; }
        /** Entfernt unveröffentlichte Teil-Dateien (wie Downloads.initialize nach einem Prozessende). */
        void processStartCleanup() { for (String n : files()) if (n.startsWith(".part-")) new File(dir, n).delete(); }
    }

    /** Remux-Nachbildung: Video- und Tondateien hintereinander; Prüfung über Länge und Anzahl. */
    static final class FakeRemux implements HlsRemux {
        volatile String failWith;                // HlsException-Grund
        volatile boolean oom;
        volatile long blockMs;
        volatile String verifyFail;
        volatile int pad;                        // P4c-3: Ausgabe größer als die geladenen Bytes (wie echte MP4-Kopfdaten)
        final AtomicInteger concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger(), runs = new AtomicInteger();
        @Override public Result remux(Input in, File out, BooleanSupplier cancelled) throws IOException, HlsException {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            runs.incrementAndGet();
            try {
                long end = System.nanoTime() + blockMs * 1_000_000L;
                while (System.nanoTime() < end) {
                    if (cancelled.getAsBoolean()) { out.delete(); throw new HlsException("cancelled"); }
                    try { Thread.sleep(10); } catch (InterruptedException e) { throw new HlsException("cancelled"); }
                }
                if (oom) throw new OutOfMemoryError("simulated");
                if (failWith != null) { out.delete(); throw new HlsException(failWith); }
                try (OutputStream o = new FileOutputStream(out)) {
                    for (File f : in.video) o.write(Files.readAllBytes(f.toPath()));
                    for (File f : in.audio) o.write(Files.readAllBytes(f.toPath()));
                    if (pad > 0) o.write(new byte[pad]);
                }
                return new Result(in.video.size(), in.audio.size(), 0, 0);
            } finally {
                concurrent.decrementAndGet();
            }
        }
        @Override public void verify(File out, Result expected, double v, double a, double tol, BooleanSupplier cancelled) throws HlsException {
            if (verifyFail != null) throw new HlsException(verifyFail);
            if (!out.isFile() || out.length() == 0) throw new HlsException("verify-missing");
        }
    }

    static final class Events implements DirectDownloads.Listener {
        final Map<String, String> finished = new ConcurrentHashMap<>();
        final List<String> all = new CopyOnWriteArrayList<>();
        final List<de.evp.helfer.dl.Progress> shown = new CopyOnWriteArrayList<>();   // P4c-3: was die Anzeige bekommt
        final Map<String, Long> sizes = new ConcurrentHashMap<>();
        @Override public void progress(String id, String title, long bytes, long total) { all.add("progress " + id + " " + title + " " + bytes); }
        @Override public void progress(String id, String title, de.evp.helfer.dl.Progress p) {
            shown.add(p);
            DirectDownloads.Listener.super.progress(id, title, p);
        }
        @Override public void finished(String id, String title, String state, String reason, Object saved) {
            all.add("finished " + id + " " + title + " " + state + " " + reason);
            finished.put(id, state + (reason.isEmpty() ? "" : ":" + reason));
        }
        @Override public void finished(String id, String title, String state, String reason, Object saved, long size) {
            sizes.put(id, size);
            finished(id, title, state, reason, saved);
        }
    }

    /** Absturz-Nachbildung: ab dem Treffer wirft jede Grenze (Prozess "tot"); Error, damit nichts ihn auffängt. */
    static final class Crash extends Error { Crash(String p) { super("crash at " + p, null, false, false); } }

    static class CrashHooks implements HlsJournal.Hooks {
        volatile String point;
        volatile int hit;           // beim n-ten Erreichen (1 = erstes)
        final AtomicInteger seen = new AtomicInteger();
        final AtomicBoolean dead = new AtomicBoolean();
        final List<String> trace = new CopyOnWriteArrayList<>();
        @Override public void at(String p) {
            if (dead.get()) throw new Crash(p);
            trace.add(p);
            if (p.equals(point) && seen.incrementAndGet() == hit) { dead.set(true); throw new Crash(p); }
        }
    }

    static final class Rig {
        final File root, out;
        final DirStore store;
        final FakeRemux remux = new FakeRemux();
        final Events events = new Events();
        final JobSlots slots;
        final AtomicLong now = new AtomicLong(1_000_000);
        HlsEngine engine;
        CrashHooks hooks = new CrashHooks();
        final SafeHttpClient http;
        // P4c: Wartezeiten 1000-fach verkürzt; Netz steuerbar
        final java.util.concurrent.atomic.AtomicBoolean online = new java.util.concurrent.atomic.AtomicBoolean(true);
        de.evp.helfer.dl.Retry retry = new de.evp.helfer.dl.Retry(online::get, de.evp.helfer.dl.Retry.Clock.SYSTEM, 1000);
        void net(boolean on) { online.set(on); retry.networkChanged(); }
        Rig(String name, Server s) throws Exception { this(name, s, new JobSlots(2, 4)); }
        Rig(String name, Server s, JobSlots slots) throws Exception {
            root = new File(base, name + "/jobs");
            out = new File(base, name + "/out");
            store = new DirStore(out);
            this.slots = slots;
            http = LocalTestClients.loopback(s.port);
            restart();
        }
        /** Neuer "Prozess": neue Engine auf demselben Ordner, Teil-Dateien der Ablage wie beim Prozessstart entfernt. */
        void restart() throws Exception {
            waitForWorkers();
            store.processStartCleanup();
            hooks = new CrashHooks();
            engine = new HlsEngine(http, store, events, root, remux, slots, false, 1_000_000, hooks, now::get);
            engine.setRetry(retry);
        }
        File dir(String id) { return new File(root, id); }
    }

    static void waitForWorkers() throws InterruptedException {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) {
            boolean any = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().startsWith("evp-hls")) any = true;
            if (!any) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("HLS worker threads still alive");
    }

    static final byte[] PEER = "peer-session-0001".getBytes(StandardCharsets.US_ASCII);
    static final String BIND = "p1|3|7";
    static int idSeq;
    static String newId() { return String.format("%032x", ++idSeq + 0xabc000L); }

    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":(\"([^\"]*)\"|(-?[0-9.]+)|(true|false))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4)) : null;
    }

    static String firstOption(String plan) {
        Matcher m = Pattern.compile("\\{\"id\":\"([0-9a-f]{24})\"").matcher(plan);
        return m.find() ? m.group(1) : null;
    }

    static String await(HlsEngine e, String id, String... states) throws InterruptedException {
        long end = System.nanoTime() + 30_000_000_000L;
        String st = null;
        while (System.nanoTime() < end) {
            st = e.status(id);
            for (String s : states) if (s.equals(field(st, "state"))) return st;
            Thread.sleep(10);
        }
        return st;
    }

    static String run(Rig r, Server s, String fixture, String id) throws Exception {
        String plan = r.engine.plan(PEER, BIND, s.url(fixture, "master.m3u8"));
        String st = r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "Clip " + fixture);
        if (!"true".equals(field(st, "ok"))) return st;
        return await(r.engine, id, "done", "failed", "cancelled", "interrupted");
    }

    /** Erwarteter Inhalt der fertigen Datei (Nachbildung): Init+Segmente der Variante, dann der Tonspur. */
    static byte[] expected(String fixture, String video, String audio) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (String list : audio == null ? new String[] {video} : new String[] {video, audio}) {
            for (String line : Files.readAllLines(new File(fx, fixture + "/" + list).toPath())) {
                String n = line.startsWith("#EXT-X-MAP:URI=\"") ? line.substring(16, line.indexOf('"', 16)) : line.startsWith("#") || line.isEmpty() ? null : line;
                if (n != null) b.write(Files.readAllBytes(new File(fx, fixture + "/" + n).toPath()));
            }
        }
        return b.toByteArray();
    }

    static String sha(byte[] b) {
        try { return HlsPlan.hex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Kein Ort darf eine Adresse oder ein Token enthalten. */
    static void noAddresses(String what, Rig r, String... texts) throws IOException {
        StringBuilder all = new StringBuilder();
        for (String t : texts) all.append(t).append('\n');
        for (String e : r.events.all) all.append(e).append('\n');
        File[] dirs = r.root.listFiles();
        if (dirs != null) for (File d : dirs) for (String n : new String[] {"journal", "segments.log"}) {
            File f = new File(d, n);
            if (f.isFile()) all.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1));
        }
        String s = all.toString();
        check(what + ": weder Adresse noch Token in Journal, Antworten oder Rückmeldungen",
            !s.contains("127.0.0.1") && !s.contains("http") && !s.contains("t=T") && !s.contains("m3u8") && !s.contains(".ts") && !s.contains("init.mp4"),
            s.length() > 400 ? s.substring(0, 400) : s);
    }

    static String[] list(File d) { String[] l = d.list(); if (l == null) return new String[0]; Arrays.sort(l); return l; }

    // ------------------------------------------------------------------ Tests

    public static void main(String[] args) throws Exception {
        fx = new File(args[0]);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { if (!(e instanceof Crash)) e.printStackTrace(); });
        base = Files.createTempDirectory("hls-engine").toFile();
        Server s = new Server();
        try {
            fullRuns(s);
            progressDisplay(s);
            vault(s);
            plans(s);
            resume(s);
            autoResume(s);
            crashes(s);
            cancelPriority(s);
            storageAndFailures(s);
            limits(s);
            journalFormat();
        } finally {
            s.stop();
            HlsEngine.deleteTree(base);
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void fullRuns(Server s) throws Exception {
        String[][] cases = {{"ts_sep", "v0.m3u8", "v1.m3u8"}, {"ts_muxed", "v0.m3u8", null}, {"fmp4_sep", "v0.m3u8", "v1.m3u8"}, {"fmp4_muxed", "v0.m3u8", null}};
        for (String[] c : cases) {
            s.reset();
            Rig r = new Rig("full-" + c[0], s);
            String id = newId();
            String st = run(r, s, c[0], id);
            check("Vollständig " + c[0] + ": done", "done".equals(field(st, "state")), st + " " + r.engine.diagnostics(id));
            String[] out = r.store.files();
            byte[] want = expected(c[0], c[1], c[2]);
            boolean same = out.length == 1 && sha(Files.readAllBytes(new File(r.out, out[0]).toPath())).equals(sha(want));
            check("Vollständig " + c[0] + ": genau eine veröffentlichte Datei, Inhalt = alle Ressourcen in Reihenfolge", same, Arrays.toString(out));
            check("Vollständig " + c[0] + ": jede Ressource genau einmal geladen", s.fullSegments() == countResources(c[0], c[1], c[2]), s.fullGets);
            check("Vollständig " + c[0] + ": Auftragsordner enthält nur noch das Journal", Arrays.equals(list(r.dir(id)), new String[] {"journal"}), Arrays.toString(list(r.dir(id))));
            check("Vollständig " + c[0] + ": kein Cookie/Authorization/Referer/Origin", s.leaks.isEmpty(), s.leaks);
            noAddresses("Vollständig " + c[0], r, st, r.engine.status(id));
            String again = r.engine.start(PEER, BIND, id, "0".repeat(32), "0".repeat(24), "x");
            check("Vollständig " + c[0] + ": erneuter Start derselben Kennung liefert nur den Zustand", "done".equals(field(again, "state")) && r.store.files().length == 1, again);
            r.restart();
            check("Vollständig " + c[0] + ": nach Neustart weiterhin done", "done".equals(field(r.engine.status(id), "state")), r.engine.status(id));
        }
        // ohne Content-Length (chunked): Länge aus dem Strom, Ergebnis gleich
        s.reset();
        s.chunked = true;
        Rig r = new Rig("chunked", s);
        String id = newId();
        String st = run(r, s, "ts_sep", id);
        check("Ohne Content-Length: done, Inhalt vollständig", "done".equals(field(st, "state")) && r.store.files().length == 1
            && new File(r.out, r.store.files()[0]).length() == expected("ts_sep", "v0.m3u8", "v1.m3u8").length, st);
    }

    /**
     * P4c-3 (P1): Anzeige nie aus der Schätzung. Massiv unterschätzte Bitrate (Samsung: „über 300 %“, „5 MB von 2 MB“),
     * getrennte Tonspur, unterschiedliche Segmentgrößen, Ausgabe größer als die geladenen Bytes, Fortsetzen mit
     * wiederverwendeten Teilen: Zähler ≤ Nenner, kein Prozent ohne exakte Größe, Phasen sichtbar, Endgröße exakt.
     */
    static void progressDisplay(Server s) throws Exception {
        int parts = countResources("ts_sep", "v0.m3u8", "v1.m3u8");
        long media = expected("ts_sep", "v0.m3u8", "v1.m3u8").length;
        for (String bw : new String[] {"1000", null}) {     // 1000 bit/s: Schätzung ≈ 1/100 der echten Größe; null: wie im Master
            s.reset();
            s.bandwidth = bw;
            s.delayMs = 15;
            Rig r = new Rig("progress-" + bw, s);
            r.remux.pad = 4096;
            String id = newId();
            String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "Fortschritt");
            String bad = null;
            String st;
            long end = System.nanoTime() + 30_000_000_000L;
            do {
                st = r.engine.status(id);
                if ("running".equals(field(st, "state"))) {
                    long total = Long.parseLong(field(st, "total"));
                    int done = Integer.parseInt(field(st, "done")), of = Integer.parseInt(field(st, "of"));
                    if (total != -1 || (of > 0 && done > of) || (of != 0 && of != parts)) bad = st;
                }
                Thread.sleep(3);
            } while (!"done".equals(field(st, "state")) && !"failed".equals(field(st, "state")) && System.nanoTime() < end);
            String tag = "Fortschritt (BANDWIDTH=" + (bw == null ? "Master" : bw) + "): ";
            check(tag + "done", "done".equals(field(st, "state")), st + " " + r.engine.diagnostics(id));
            check(tag + "Status während des Ladens: total=-1 (keine Schätzung), done ≤ of, of = Video+Ton", bad == null, bad);
            long file = new File(r.out, r.store.files()[0]).length();
            check(tag + "Ausgabe wirklich größer als die geladenen Bytes (Testvoraussetzung)", file == media + 4096, file + " vs " + media);
            check(tag + "Status bei done: bytes = total = exakte Größe der veröffentlichten Datei",
                Long.parseLong(field(st, "bytes")) == file && Long.parseLong(field(st, "total")) == file, st);
            check(tag + "Benachrichtigung bei done: exakte Größe", Long.valueOf(file).equals(r.events.sizes.get(id)), r.events.sizes.get(id));
            r.restart();                                     // neuer Prozess: Größe aus dem Journal, nicht aus dem Zähler
            String after = r.engine.status(id);
            check(tag + "nach Neustart: done mit exakter Größe (bytes = total)", "done".equals(field(after, "state"))
                && Long.parseLong(field(after, "bytes")) == file && Long.parseLong(field(after, "total")) == file, after);
            boolean ok = !r.events.shown.isEmpty();
            String why = "";
            for (de.evp.helfer.dl.Progress p : r.events.shown) {
                String de = p.text(true), en = p.text(false);
                boolean fine = p.total == -1 && p.percent() == -1 && !de.contains("%") && !de.contains(" von ") && !en.contains(" of ")
                    && p.done <= p.of && p.of == parts && p.bytes <= media;
                if (!fine) { ok = false; why = p + " → " + de; break; }
            }
            check(tag + "Anzeige: nie Prozent/„von“, Teile ≤ alle Teile, Bytes ≤ geladene Medien", ok, why);
            java.util.Set<de.evp.helfer.dl.Progress.Phase> phases = java.util.EnumSet.noneOf(de.evp.helfer.dl.Progress.Phase.class);
            for (de.evp.helfer.dl.Progress p : r.events.shown) phases.add(p.phase);
            check(tag + "Anzeige: Phasen zusammensetzen, prüfen, speichern gemeldet", phases.containsAll(java.util.EnumSet.of(
                de.evp.helfer.dl.Progress.Phase.MUX, de.evp.helfer.dl.Progress.Phase.VERIFY, de.evp.helfer.dl.Progress.Phase.SAVE)), phases);
        }
        // Fenster „Journal schon done, Arbeiter noch nicht fertig“: weiterhin running, noch keine Größe (deterministisch)
        {
            s.reset();
            Rig rw = new Rig("progress-window", s);
            CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
            rw.hooks = new CrashHooks() {
                @Override public void at(String p) {
                    if (p.equals("done-journaled")) { reached.countDown(); try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                }
            };
            rw.engine = new HlsEngine(rw.http, rw.store, rw.events, rw.root, rw.remux, rw.slots, false, 1_000_000, rw.hooks, rw.now::get);   // restart() setzt die Haken zurück
            rw.engine.setRetry(rw.retry);
            String idw = newId();
            String pw = rw.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            rw.engine.start(PEER, BIND, idw, field(pw, "planId"), firstOption(pw), "Fenster");
            boolean in = reached.await(30, TimeUnit.SECONDS);
            String mid = rw.engine.status(idw);
            release.countDown();
            String fin = await(rw.engine, idw, "done", "failed", "interrupted", "cancelled");
            check("Fortschritt: Journal fertig, Arbeiter noch nicht → running ohne Größe (total=-1)", in && "running".equals(field(mid, "state"))
                && "-1".equals(field(mid, "total")), mid);
            check("Fortschritt: danach done mit exakter Größe", "done".equals(field(fin, "state")) && !"-1".equals(field(fin, "total")), fin);
        }
        // Fortsetzen: wiederverwendete Teile zählen genau einmal (kein Aufblähen über alle Teile bzw. alle Bytes)
        s.reset();
        s.delayMs = 40;
        Rig r = new Rig("progress-resume", s);
        String id = newId();
        String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
        long end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(5);
        s.token = "T2";
        String st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
        check("Fortschritt (Fortsetzen): erst unterbrochen", "interrupted".equals(field(st, "state")), st);
        r.restart();
        s.quiesce();
        s.delayMs = 0;
        r.events.shown.clear();
        String p2 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
        st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
        check("Fortschritt (Fortsetzen): done", "done".equals(field(st, "state")), st);
        int maxDone = 0; long maxBytes = 0;
        for (de.evp.helfer.dl.Progress p : r.events.shown) { maxDone = Math.max(maxDone, p.done); maxBytes = Math.max(maxBytes, p.bytes); }
        check("Fortschritt (Fortsetzen): höchstens alle Teile und alle Bytes – wiederverwendete Teile nicht doppelt",
            maxDone <= parts && maxBytes <= media, "maxDone=" + maxDone + "/" + parts + " maxBytes=" + maxBytes + "/" + media);
    }

    /** P2: Software-AES-GCM für die JVM-Tests (App: nicht exportierbarer Android-Keystore-Schlüssel). */
    static final class SoftSealer implements de.evp.helfer.dl.SourceVault.Sealer {
        final javax.crypto.SecretKey key;
        SoftSealer() throws Exception { javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance("AES"); g.init(256); key = g.generateKey(); }
        @Override public byte[] seal(byte[] aad, byte[] plain) throws java.security.GeneralSecurityException {
            byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plain), out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        }
        @Override public byte[] open(byte[] aad, byte[] sealed) throws java.security.GeneralSecurityException {
            if (sealed.length < 28) throw new java.security.GeneralSecurityException("short");
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12));
            c.updateAAD(aad);
            return c.doFinal(sealed, 12, sealed.length - 12);
        }
    }

    /** Lauf bis unterbrochen (Dienstende mitten im Abruf), dann „Prozessende“ (neue Engine, Quelle nicht mehr im Speicher). */
    static String interruptThenRestart(Rig r, Server s, de.evp.helfer.dl.SourceVault v) throws Exception {
        String id = newId();
        s.delayMs = 40;
        String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "Tresor");
        long end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(5);
        r.engine.stopAll();
        await(r.engine, id, "interrupted", "done", "failed", "cancelled");
        r.restart();
        r.engine.setVault(v);
        s.delayMs = 0;
        return id;
    }

    static void vault(Server s) throws Exception {
        s.reset();
        Rig r = new Rig("vault", s);
        SoftSealer key = new SoftSealer();
        File vdir = new File(base, "vault/src");
        de.evp.helfer.dl.SourceVault v = new de.evp.helfer.dl.SourceVault(vdir, key, r.now::get);
        r.engine.setVault(v);
        String id = interruptThenRestart(r, s, v);
        File rec = new File(vdir, id + ".src");
        String raw = rec.isFile() ? new String(Files.readAllBytes(rec.toPath()), StandardCharsets.ISO_8859_1) : "";
        check("Tresor: Eintrag vorhanden, ohne Klartext-Adresse, Host, Pfad oder Token", rec.isFile() && !raw.contains("127.0.0.1") && !raw.contains("dyn")
            && !raw.contains("master") && !raw.contains("t=T1") && !raw.contains("http"), raw);
        File journal = new File(r.dir(id), "journal");
        check("Tresor: Journal weiterhin ohne Adresse", !new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8).contains("127.0.0.1"), "");
        check("Tresor: nach Prozessende kein Fortsetzen aus dem Arbeitsspeicher, aber aus dem Tresor", !r.engine.canResumeLocal(id) && r.engine.canResumeStored(id), "");
        int before = s.fullSegments();
        check("Tresor: genau ein Auftrag fortgesetzt", r.engine.resumeStored() == 1, "");
        String st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
        check("Tresor: ohne Webseite fertig, Inhalt exakt", "done".equals(field(st, "state")) && sameOutput(r), st + " " + r.engine.diagnostics(id));
        check("Tresor: schon geprüfte Teile nicht neu geladen", s.fullSegments() - before < countResources("ts_sep", "v0.m3u8", "v1.m3u8"), s.fullSegments() - before);
        check("Tresor: nach Erfolg sofort gelöscht", !rec.exists(), "");

        // abgelaufen (6 h): kein Fortsetzen, Eintrag weg
        s.reset();
        Rig r2 = new Rig("vault-exp", s);
        de.evp.helfer.dl.SourceVault v2 = new de.evp.helfer.dl.SourceVault(new File(base, "vault-exp/src"), key, r2.now::get);
        r2.engine.setVault(v2);
        String id2 = interruptThenRestart(r2, s, v2);
        r2.now.addAndGet(de.evp.helfer.dl.SourceVault.MAX_AGE_MS);
        int req = s.requests.get();
        check("Tresor: nach 6 h kein Fortsetzen, keine Anfrage, Eintrag gelöscht", r2.engine.resumeStored() == 0 && s.requests.get() == req
            && !new File(base, "vault-exp/src/" + id2 + ".src").exists() && "interrupted".equals(field(r2.engine.status(id2), "state")), r2.engine.status(id2));

        // Abbruch durch den Nutzer: Eintrag sofort weg, nie automatisch fortgesetzt
        s.reset();
        Rig r3 = new Rig("vault-cancel", s);
        File d3 = new File(base, "vault-cancel/src");
        de.evp.helfer.dl.SourceVault v3 = new de.evp.helfer.dl.SourceVault(d3, key, r3.now::get);
        r3.engine.setVault(v3);
        String id3 = interruptThenRestart(r3, s, v3);
        check("Tresor (Abbruch): vorher vorhanden", new File(d3, id3 + ".src").isFile(), "");
        r3.engine.cancel(id3);
        check("Tresor (Abbruch): sofort gelöscht, kein Fortsetzen", !new File(d3, id3 + ".src").exists() && r3.engine.resumeStored() == 0
            && "cancelled".equals(field(r3.engine.status(id3), "state")), r3.engine.status(id3));

        // beschädigt, vertauscht, falscher Schlüssel: kein Fortsetzen, kein Absturz, Eintrag weg
        for (String mode : new String[] {"corrupt", "swapped", "wrongkey", "extended"}) {
            s.reset();
            Rig rm = new Rig("vault-" + mode, s);
            File dm = new File(base, "vault-" + mode + "/src");
            de.evp.helfer.dl.SourceVault vm = new de.evp.helfer.dl.SourceVault(dm, key, rm.now::get);
            rm.engine.setVault(vm);
            String a = interruptThenRestart(rm, s, vm);
            File fa = new File(dm, a + ".src");
            String text = new String(Files.readAllBytes(fa.toPath()), StandardCharsets.US_ASCII);
            de.evp.helfer.dl.SourceVault use = vm;
            if (mode.equals("corrupt")) { byte[] b = Files.readAllBytes(fa.toPath()); b[b.length - 6] ^= 0x01; Files.write(fa.toPath(), b); }
            if (mode.equals("swapped")) {   // Eintrag eines anderen Auftrags unter dieser Kennung (AAD bindet die Kennung)
                String other = newId();
                vm.put(other, "hls", "http://127.0.0.1:" + s.port + "/dyn/ts_sep/master.m3u8?t=T1", "x|0|-1|-");
                Files.copy(new File(dm, other + ".src").toPath(), fa.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (mode.equals("wrongkey")) { use = new de.evp.helfer.dl.SourceVault(dm, new SoftSealer(), rm.now::get); rm.engine.setVault(use); }
            if (mode.equals("extended")) {   // Ablaufzeit im Klartext-Kopf verlängert
                String[] l = text.split("\n", -1);
                Files.write(fa.toPath(), (l[0] + "\n" + l[1] + "\n" + l[2] + "\n" + (Long.parseLong(l[3]) + 3_600_000L) + "\n" + l[4] + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            int rq = s.requests.get();
            int n = rm.engine.resumeStored();
            check("Tresor (" + mode + "): kein Fortsetzen, keine Anfrage, Eintrag gelöscht, Auftrag bleibt unterbrochen", n == 0 && s.requests.get() == rq
                && !fa.exists() && "interrupted".equals(field(rm.engine.status(a), "state")), n + " " + rm.engine.status(a));
        }

        // Liste geändert: fehlgeschlagen (plan_changed), Eintrag weg
        s.reset();
        Rig r5 = new Rig("vault-changed", s);
        File d5 = new File(base, "vault-changed/src");
        de.evp.helfer.dl.SourceVault v5 = new de.evp.helfer.dl.SourceVault(d5, key, r5.now::get);
        r5.engine.setVault(v5);
        String id5 = interruptThenRestart(r5, s, v5);
        s.masterExtra = "#EXT-X-INDEPENDENT-SEGMENTS\n";
        check("Tresor (Liste geändert): fortgesetzt", r5.engine.resumeStored() == 1, "");
        String st5 = await(r5.engine, id5, "done", "failed", "interrupted", "cancelled");
        check("Tresor (Liste geändert): failed:plan_changed, nichts veröffentlicht, Eintrag gelöscht", "failed".equals(field(st5, "state"))
            && "plan_changed".equals(field(st5, "reason")) && r5.store.files().length == 0 && !new File(d5, id5 + ".src").exists(), st5);
    }

    static int countResources(String fixture, String v, String a) throws IOException {
        int n = 0;
        for (String list : a == null ? new String[] {v} : new String[] {v, a})
            for (String line : Files.readAllLines(new File(fx, fixture + "/" + list).toPath()))
                if (line.startsWith("#EXT-X-MAP:") || (!line.startsWith("#") && !line.isEmpty())) n++;
        return n;
    }

    static void plans(Server s) throws Exception {
        s.reset();
        Rig r = new Rig("plans", s);
        String plan = r.engine.plan(PEER, BIND, s.url("ts_multi", "master.m3u8"));
        check("Plan: ok mit Optionen, Dauer und Ablaufzeit", "true".equals(field(plan, "ok")) && firstOption(plan) != null && field(plan, "duration") != null
            && "600".equals(field(plan, "expiresSec")), plan);
        noAddresses("Plan", r, plan);
        String pid = field(plan, "planId"), opt = firstOption(plan);
        check("Plan: andere Kanal-Sitzung → plan_stale", "plan_stale".equals(field(r.engine.start("other-session-xx".getBytes(StandardCharsets.US_ASCII), BIND, newId(), pid, opt, "x"), "reason")), "");
        check("Plan: andere Bindung (Player/Generation/Revision) → plan_stale", "plan_stale".equals(field(r.engine.start(PEER, "p1|3|8", newId(), pid, opt, "x"), "reason")), "");
        check("Plan: unbekannte Option → option_unknown", "option_unknown".equals(field(r.engine.start(PEER, BIND, newId(), pid, "f".repeat(24), "x"), "reason")), "");
        r.now.addAndGet(HlsEngine.PLAN_TTL_MS + 1);
        check("Plan: nach Ablauf → plan_stale", "plan_stale".equals(field(r.engine.start(PEER, BIND, newId(), pid, opt, "x"), "reason")), "");
        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) ids[i] = field(r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8")), "planId");
        check("Plan: höchstens 4 je Sitzung – der älteste fällt heraus", "plan_stale".equals(field(r.engine.start(PEER, BIND, newId(), ids[0], "0".repeat(24), "x"), "reason")), "");
        String p2 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        String id = newId();
        r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
        await(r.engine, id, "done", "failed");
        check("Plan: ein Plan startet höchstens einen Auftrag", "plan_stale".equals(field(r.engine.start(PEER, BIND, newId(), field(p2, "planId"), firstOption(p2), "x"), "reason")), "");
        // Playlist-Änderung zwischen Plan und Start
        String p3 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        s.masterExtra = "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.4d401e,mp4a.40.2\"\nv0.m3u8?t=T1\n";
        String id3 = newId();
        String st3 = r.engine.start(PEER, BIND, id3, field(p3, "planId"), firstOption(p3), "x");
        st3 = await(r.engine, id3, "done", "failed", "interrupted");
        check("Plan: geänderte Liste zwischen Plan und Start → failed:plan_changed, nichts geladen",
            "failed".equals(field(st3, "state")) && "plan_changed".equals(field(st3, "reason")), st3);
        s.masterExtra = "";
        // Private Ziele / strenge Regeln
        Rig strict = new Rig("plans-strict", s);
        HlsEngine se = new HlsEngine(LocalTestClients.strict(), strict.store, strict.events, new File(base, "strict-root"), strict.remux, new JobSlots(2, 4), false, 1_000_000);
        String sp = se.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        check("Plan: Produktionsregeln sperren Loopback (privates Ziel) → blocked_*", "false".equals(field(sp, "ok")) && String.valueOf(field(sp, "reason")).startsWith("blocked_"), sp);
        String bad = r.engine.plan(PEER, BIND, "http://127.0.0.1:" + s.port + "/dyn/ts_sep/missing.m3u8?t=T1");
        check("Plan: fehlende Liste → neutraler Grund ohne Adresse", "false".equals(field(bad, "ok")) && !bad.contains("127.0.0.1"), bad);
    }


    // ------------------------------------------------------------------ P4c: automatische Wiederaufnahme

    static void startJob(Rig r, Server s, String id) {
        String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
    }
    static boolean sameOutput(Rig r) throws IOException {
        return r.store.files().length == 1 && sha(Files.readAllBytes(new File(r.out, r.store.files()[0]).toPath())).equals(sha(expected("ts_sep", "v0.m3u8", "v1.m3u8")));
    }
    static String waitField(HlsEngine e, String id, String key, String value) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        String st = e.status(id);
        while (!value.equals(field(st, key)) && System.nanoTime() < end) { Thread.sleep(5); st = e.status(id); }
        return st;
    }

    static void autoResume(Server s) throws Exception {
        int total = countResources("ts_sep", "v0.m3u8", "v1.m3u8");
        // 1) Server kurz überlastet (3× 503) mitten im Abruf: ohne Zutun fertig, kein Segment doppelt geladen
        s.reset();
        s.delayMs = 20;
        Rig r = new Rig("auto-503", s);
        String id = newId();
        startJob(r, s, id);
        long end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(2);
        s.fail503.set(3);
        String st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
        check("Auto (503): ohne Nutzeraktion fertig, Inhalt korrekt", "done".equals(field(st, "state")) && sameOutput(r), st + " " + r.engine.diagnostics(id));
        check("Auto (503): der Server meldete wirklich 503", s.answered503.get() >= 1, s.answered503.get());
        // Verifizierte Segmente werden nur per starkem ETag bestätigt (Range 0-0), nie neu geladen; neu geladen werden darf
        // höchstens, was im Moment der Störung noch unterwegs (also unverifiziert) war: je weiterem Abrufer eines.
        check("Auto (503): verifizierte Segmente nicht neu geladen (höchstens die gerade laufenden), Rest bestätigt",
            s.fullSegments() >= total && s.fullSegments() <= total + HlsEngine.FETCH_PER_JOB - 1 && s.ranges() >= 3,
            "full=" + s.fullSegments() + " total=" + total + " ranges=" + s.ranges());

        // 2) Flugmodus: kein Netz → keine Anfrage, Status „wartet auf Verbindung“; Netz zurück → fertig
        s.reset();
        s.delayMs = 20;
        Rig r2 = new Rig("auto-offline", s);
        String id2 = newId();
        s.on503 = () -> r2.net(false);
        startJob(r2, s, id2);
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(2);
        s.fail503.set(1);
        st = waitField(r2.engine, id2, "wait", "offline");
        check("Offline: läuft weiter (running) mit wait=offline", "running".equals(field(st, "state")) && "offline".equals(field(st, "wait")), st);
        s.quiesce();
        int req = s.requests.get();
        Thread.sleep(1_200);                     // länger als alle verkürzten Pausen zusammen
        check("Offline: keine einzige Anfrage ohne Netz", s.requests.get() == req, (s.requests.get() - req) + " requests");
        r2.net(true);
        st = await(r2.engine, id2, "done", "failed", "interrupted", "cancelled");
        check("Offline: Netz zurück → selbstständig fertig, Inhalt korrekt", "done".equals(field(st, "state")) && sameOutput(r2), st);
        check("Offline: verifizierte Segmente nicht neu geladen (höchstens die gerade laufenden)", s.fullSegments() >= total
            && s.fullSegments() <= total + HlsEngine.FETCH_PER_JOB - 1 && s.ranges() >= 3, s.fullSegments() + "/" + total + " ranges=" + s.ranges());

        // 3) Abbruch während des Wartens: sofort cancelled, Daten weg, auch nach Netz zurück kein weiterer Versuch
        s.reset();
        s.delayMs = 20;
        Rig r3 = new Rig("auto-cancel", s);
        String id3 = newId();
        s.on503 = () -> r3.net(false);
        startJob(r3, s, id3);
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 3 && System.nanoTime() < end) Thread.sleep(2);
        s.fail503.set(1);
        waitField(r3.engine, id3, "wait", "offline");
        long t0 = System.nanoTime();
        r3.engine.cancel(id3);
        st = await(r3.engine, id3, "cancelled", "done", "failed", "interrupted");
        check("Abbruch im Warten: cancelled in < 1 s", "cancelled".equals(field(st, "state")) && (System.nanoTime() - t0) / 1_000_000 < 1_000, st);
        int req3 = s.requests.get();
        r3.net(true);
        Thread.sleep(800);
        check("Abbruch im Warten: nie wieder aufgenommen (keine Anfrage, nur Journal)", s.requests.get() == req3
            && Arrays.equals(list(r3.dir(id3)), new String[] {"journal"}) && r3.store.files().length == 0, (s.requests.get() - req3) + " " + Arrays.toString(list(r3.dir(id3))));
        r3.restart();
        check("Abbruch im Warten: nach Neustart weiterhin cancelled", "cancelled".equals(field(r3.engine.status(id3), "state")), r3.engine.status(id3));

        // 4) Dienstende während des Wartens: interrupted:stopped, Segmente bleiben
        s.reset();
        s.delayMs = 20;
        Rig r4 = new Rig("auto-stop", s);
        String id4 = newId();
        s.on503 = () -> r4.net(false);
        startJob(r4, s, id4);
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 3 && System.nanoTime() < end) Thread.sleep(2);
        s.fail503.set(1);
        waitField(r4.engine, id4, "wait", "offline");
        r4.engine.stopAll();
        st = await(r4.engine, id4, "interrupted", "cancelled", "done", "failed");
        check("Dienstende im Warten: interrupted:stopped", "interrupted".equals(field(st, "state")) && "stopped".equals(field(st, "reason")), st);
        r4.net(true);

        // 5) Dauerhaft 503: begrenzt (keine Endlosschleife), danach unterbrochen; „Fortsetzen“ ohne Kadri (Quelle im
        //    Arbeitsspeicher) lädt den Rest; nach einem Prozessneustart ist das nicht mehr möglich
        s.reset();
        Rig r5 = new Rig("auto-limit", s);
        String id5 = newId();
        s.fail503.set(1_000_000);
        startJob(r5, s, id5);
        st = await(r5.engine, id5, "interrupted", "done", "failed", "cancelled");
        int n503 = s.answered503.get();
        check("Dauerfehler: endet als interrupted:http_503", "interrupted".equals(field(st, "state")) && "http_503".equals(field(st, "reason")), st);
        check("Dauerfehler: Anfragen begrenzt (≤ 2 je Versuch, " + (1 + de.evp.helfer.dl.Retry.MAX_ATTEMPTS) + " Versuche)",
            n503 <= HlsEngine.FETCH_PER_JOB * (1 + de.evp.helfer.dl.Retry.MAX_ATTEMPTS), n503);
        Thread.sleep(300);
        check("Dauerfehler: danach keine weiteren Anfragen", s.answered503.get() == n503, s.answered503.get() - n503);
        s.fail503.set(0);
        check("Fortsetzen ohne Kadri möglich (Quelle im Arbeitsspeicher)", r5.engine.canResumeLocal(id5), "");
        check("Fortsetzen ohne Kadri: gestartet", r5.engine.resumeLocal(id5), "");
        check("Fortsetzen ohne Kadri: kein zweiter Lauf parallel", !r5.engine.resumeLocal(id5), "");
        st = await(r5.engine, id5, "done", "failed", "interrupted", "cancelled");
        check("Fortsetzen ohne Kadri: fertig, Inhalt korrekt", "done".equals(field(st, "state")) && sameOutput(r5), st);
        check("Fortsetzen ohne Kadri: nach done nicht mehr fortsetzbar (Quelle vergessen)", !r5.engine.canResumeLocal(id5), "");
        s.reset();
        Rig r6 = new Rig("auto-restart", s);
        String id6 = newId();
        s.fail503.set(1_000_000);
        startJob(r6, s, id6);
        await(r6.engine, id6, "interrupted", "done", "failed", "cancelled");
        r6.restart();
        check("Nach Prozessneustart: keine Quelle mehr – kein Fortsetzen ohne Kadri (keine Adresse gespeichert)",
            !r6.engine.canResumeLocal(id6) && !r6.engine.resumeLocal(id6) && "interrupted".equals(field(r6.engine.status(id6), "state")), r6.engine.status(id6));
        check("Nach Prozessneustart: Journal ohne Adresse", !new String(Files.readAllBytes(new File(r6.dir(id6), "journal").toPath()), StandardCharsets.UTF_8).contains("127.0.0.1"), "");
        s.fail503.set(0);
        // 6) 403 (abgelaufene Signatur/fehlende Berechtigung) wird nie automatisch wiederholt: ein einziger Versuch
        s.reset();
        s.delayMs = 20;
        Rig r7 = new Rig("auto-403", s);
        String id7 = newId();
        startJob(r7, s, id7);
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 3 && System.nanoTime() < end) Thread.sleep(2);
        s.token = "T2";
        st = await(r7.engine, id7, "interrupted", "done", "failed", "cancelled");
        int afterFail = s.requests.get();
        Thread.sleep(500);
        check("403: interrupted ohne automatischen Versuch (keine weitere Anfrage)", "interrupted".equals(field(st, "state")) && "http_403".equals(field(st, "reason"))
            && s.requests.get() == afterFail, st + " +" + (s.requests.get() - afterFail));
        // ein einziger Versuch: höchstens je Abrufer eine 403-Antwort (ein Versuch je Pause gäbe deutlich mehr)
        check("403: genau ein Versuch (höchstens " + HlsEngine.FETCH_PER_JOB + " Antworten 403)", s.answered403.get() >= 1 && s.answered403.get() <= HlsEngine.FETCH_PER_JOB, s.answered403.get());
    }

    static void resume(Server s) throws Exception {
        // Abgelaufene Quelle mitten im Abruf: unterbrochen; neuer Plan mit neuem Token → Fortsetzen ohne erneutes Laden
        for (String mode : new String[] {"strong", "weak", "none", "bogus200", "lenlie"}) {
            s.reset();
            s.etagMode = mode;
            s.delayMs = 40;
            Rig r = new Rig("resume-" + mode, s);
            String id = newId();
            String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
            long end = System.nanoTime() + 10_000_000_000L;
            while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(5);
            s.token = "T2";                                  // alte Adressen ungültig (403)
            String st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
            check("Ablauf (" + mode + "): Auftrag unterbrochen, nicht fehlgeschlagen", "interrupted".equals(field(st, "state")) && "http_403".equals(field(st, "reason")), st);
            int fetched = Integer.parseInt(field(st, "done"));
            check("Ablauf (" + mode + "): bereits verifizierte Ressourcen bleiben erhalten", fetched >= 3, st);
            r.restart();                                     // auch über einen Prozessneustart hinweg
            String afterRestart = r.engine.status(id);
            check("Ablauf (" + mode + "): nach Neustart weiterhin unterbrochen mit erhaltenen Ressourcen", "interrupted".equals(field(afterRestart, "state"))
                && Integer.parseInt(field(afterRestart, "done")) == fetched, afterRestart);
            s.quiesce();
            int before = s.fullSegments();
            s.delayMs = 0;
            String p2 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
            st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
            int refetched = s.fullSegments() - before;
            int total = countResources("ts_sep", "v0.m3u8", "v1.m3u8");
            check("Fortsetzen (" + mode + "): done mit korrektem Inhalt", "done".equals(field(st, "state")) && r.store.files().length == 1
                && sha(Files.readAllBytes(new File(r.out, r.store.files()[0]).toPath())).equals(sha(expected("ts_sep", "v0.m3u8", "v1.m3u8"))), st);
            if (mode.equals("strong")) {
                check("Fortsetzen (stark): bestätigte Ressourcen per If-Range/206 nicht neu geladen", refetched == total - fetched && s.ranges() == fetched,
                    "refetched=" + refetched + " total=" + total + " fetched=" + fetched + " ranges=" + s.ranges());
            } else {
                boolean broken = mode.equals("bogus200") || mode.equals("lenlie");
                check("Fortsetzen (" + mode + "): " + (broken ? "fehlerhafte Bestätigung des Servers wird nicht akzeptiert" : "ohne starken ETag")
                    + " – alles neu geladen (keine Wiederverwendung)", refetched == total && s.ranges() == 0 && (!broken || s.rangeBroken.size() > 0),
                    "refetched=" + refetched + " ranges=" + s.ranges() + " broken=" + s.rangeBroken);
            }
        }
        // Geänderter Inhalt hinter derselben Adresse (anderer starker ETag) und beschädigte Datei auf der Platte
        for (String damage : new String[] {"changed", "corrupt"}) {
            s.reset();
            s.delayMs = 40;
            Rig r = new Rig("resume-" + damage, s);
            String id = newId();
            String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
            long end = System.nanoTime() + 10_000_000_000L;
            while (!new File(r.dir(id), "v0.seg").isFile() && System.nanoTime() < end) Thread.sleep(5);
            while (s.fullSegments() < 4 && System.nanoTime() < end) Thread.sleep(5);
            r.engine.stopAll();
            String st = await(r.engine, id, "interrupted", "done", "failed");
            check("Dienstende (" + damage + "): unterbrochen mit Grund stopped", "interrupted".equals(field(st, "state")) && "stopped".equals(field(st, "reason")), st);
            r.restart();
            if (damage.equals("changed")) s.changedPath = "ts_sep/s0_000.ts";
            else { try (RandomAccessFile f = new RandomAccessFile(new File(r.dir(id), "v0.seg"), "rw")) { f.seek(10); f.write(0x55 ^ f.read()); } }
            s.quiesce();
            int before = s.full("s0_000.ts");
            s.delayMs = 0;
            String p2 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
            r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
            st = await(r.engine, id, "done", "failed", "interrupted");
            byte[] got = r.store.files().length == 1 ? Files.readAllBytes(new File(r.out, r.store.files()[0]).toPath()) : new byte[0];
            check("Fortsetzen (" + damage + "): die betroffene Ressource wurde neu geladen", s.full("s0_000.ts") == before + 1
                && s.rangeMisses.size() == (damage.equals("changed") ? 1 : 0), s.fullGets + " misses=" + s.rangeMisses);
            byte[] want = expected("ts_sep", "v0.m3u8", "v1.m3u8");
            if (damage.equals("changed")) {
                // Server liefert jetzt den geänderten Inhalt: die Datei enthält genau ihn, nie die alte Fassung gemischt
                File seg0 = new File(fx, "ts_sep/s0_000.ts");
                int len0 = (int) seg0.length();
                check("Fortsetzen (changed): Ergebnis enthält die neue Fassung", "done".equals(field(st, "state")) && got.length == want.length
                    && got[len0 - 1] == (byte) (want[len0 - 1] ^ 1), st);
            } else {
                check("Fortsetzen (corrupt): Ergebnis exakt wie Quelle", "done".equals(field(st, "state")) && sha(got).equals(sha(want)), st);
            }
        }
    }

    static final String[] POINTS = {"journal-written", "journal-synced", "journal-renamed", "log-written", "log-synced", "segment-fetched",
        "segment-renamed", "segment-journaled", "remux-start", "verified", "publish-journaled", "publish-copied", "published"};

    static void crashes(Server s) throws Exception {
        byte[] want = expected("fmp4_sep", "v0.m3u8", "v1.m3u8");
        for (String point : POINTS) {
            for (int hit : point.startsWith("journal") || point.startsWith("log") || point.startsWith("segment") ? new int[] {1, 3} : new int[] {1}) {
                s.reset();
                Rig r = new Rig("crash-" + point + "-" + hit, s);
                r.hooks.point = point;
                r.hooks.hit = hit;
                String id = newId();
                String plan = r.engine.plan(PEER, BIND, s.url("fmp4_sep", "master.m3u8"));
                String st0;
                try { st0 = r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x"); }
                catch (Crash c) { st0 = "crash in start"; }   // erste Journal-Ersetzung geschieht im Aufrufer
                long end = System.nanoTime() + 20_000_000_000L;
                while (!r.hooks.dead.get() && System.nanoTime() < end && !"done".equals(field(r.engine.status(id), "state"))) Thread.sleep(5);
                String tag = "Absturz bei " + point + " #" + hit;
                if (!r.hooks.dead.get()) { check(tag + ": Grenze erreicht", false, st0 + " " + r.hooks.trace); continue; }
                r.restart();
                String st = r.engine.status(id);
                String state = field(st, "state");
                boolean publishedBefore = point.equals("published");
                // Unmittelbar nach dem Neustart: keine Teil-Dateien, jede vermerkte Ressource vorhanden und korrekt
                boolean clean = true;
                StringBuilder why = new StringBuilder();
                File d = r.dir(id);
                if (d.isDirectory()) {
                    HlsJournal j = HlsJournal.load(d);
                    for (String n : list(d)) {
                        if (n.endsWith(".part") || n.endsWith(".tmp")) { clean = false; why.append(" part:").append(n); }
                        if (n.endsWith(".seg") && !j.resources.containsKey(n.substring(0, n.length() - 4))) { clean = false; why.append(" unjournaled:").append(n); }
                    }
                    for (HlsJournal.Res res : j.resources.values()) {
                        File f = new File(d, res.key() + ".seg");
                        if (!f.isFile() || !HlsEngine.fileSha(f).equals(res.sha256)) { clean = false; why.append(" bad:").append(res.key()); }
                    }
                }
                check(tag + ": nach Neustart keine Teil-/fremden Dateien, jede vermerkte Ressource SHA-geprüft", clean, why);
                if (publishedBefore) {
                    check(tag + ": veröffentlicht vor dem Absturz → nach Neustart done, genau eine Datei", "done".equals(state) && r.store.files().length == 1, st + " " + Arrays.toString(r.store.files()));
                    continue;
                }
                // Vor dem ersten dauerhaften Journal gilt der Auftrag als nie gestartet (unbekannt, keine Reste);
                // danach als unterbrochen. Nie veröffentlicht.
                boolean okState = "interrupted".equals(state) || ("unknown".equals(state) && !d.exists());
                check(tag + ": nach Neustart unterbrochen (oder nie gestartet ohne Reste), nichts veröffentlicht", okState && r.store.files().length == 0,
                    st + " " + Arrays.toString(r.store.files()));
                String p2 = r.engine.plan(PEER, BIND, s.url("fmp4_sep", "master.m3u8"));
                r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
                st = await(r.engine, id, "done", "failed", "interrupted", "cancelled");
                boolean ok = "done".equals(field(st, "state")) && r.store.files().length == 1
                    && sha(Files.readAllBytes(new File(r.out, r.store.files()[0]).toPath())).equals(sha(want));
                check(tag + ": Wiederaufnahme → done, genau eine Datei, Inhalt exakt", ok, st + " " + Arrays.toString(r.store.files()) + " " + r.engine.diagnostics(id));
            }
        }
        // Abgerissene letzte Logzeile: wird verworfen und abgeschnitten, Rest gilt
        s.reset();
        Rig r = new Rig("torn", s);
        r.hooks.point = "segment-journaled";
        r.hooks.hit = 3;
        String id = newId();
        String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
        long end = System.nanoTime() + 10_000_000_000L;
        while (!r.hooks.dead.get() && System.nanoTime() < end) Thread.sleep(5);
        waitForWorkers();
        File log = new File(r.dir(id), "segments.log");
        long len = log.length();
        try (FileOutputStream o = new FileOutputStream(log, true)) { o.write("r v 9 123 abc".getBytes(StandardCharsets.US_ASCII)); }
        r.restart();
        check("Abgerissene Logzeile: verworfen und abgeschnitten, Auftrag wiederaufnehmbar", log.length() == len && "interrupted".equals(field(r.engine.status(id), "state")), log.length() + " vs " + len);
        // Beschädigte Logzeile in der Mitte: Auftrag wird vollständig verworfen (nie teilweise verwendet)
        // Formal gültige Zeile mit verändertem SHA-256 (nur die Prüfsumme verrät es)
        String[] lines = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).split("\n", -1);
        String[] f0 = lines[0].split(" ");
        char c0 = f0[4].charAt(0);
        f0[4] = (c0 == '0' ? '1' : '0') + f0[4].substring(1);
        lines[0] = String.join(" ", f0);
        Files.write(log.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        r.restart();
        check("Beschädigtes Log: Auftrag verworfen (unbekannt), Ordner entfernt", "unknown".equals(field(r.engine.status(id), "state")) && !r.dir(id).exists(), r.engine.status(id));
    }

    static void cancelPriority(Server s) throws Exception {
        s.reset();
        s.delayMs = 60;
        Rig r = new Rig("cancel", s);
        String id = newId();
        String plan = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "x");
        long end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 2 && System.nanoTime() < end) Thread.sleep(5);
        long t0 = System.nanoTime();
        r.engine.cancel(id);
        String st = await(r.engine, id, "cancelled", "done", "failed", "interrupted");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("Abbruch: laufender Auftrag endet als cancelled", "cancelled".equals(field(st, "state")), st);
        check("Abbruch: innerhalb von 2 s", ms < 2000, ms + " ms");
        check("Abbruch: nur das Journal bleibt, nichts veröffentlicht", Arrays.equals(list(r.dir(id)), new String[] {"journal"}) && r.store.files().length == 0,
            Arrays.toString(list(r.dir(id))));
        r.restart();
        s.quiesce();                       // abgebrochene Anfragen, die der Server noch beantwortet, nicht mitzählen
        int before = s.fullSegments() + s.full(".m3u8");
        String p2 = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        String again = r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "x");
        check("Abbruch: nach Neustart bleibt cancelled; ein neuer Start derselben Kennung lädt nichts", "cancelled".equals(field(again, "state"))
            && s.fullSegments() + s.full(".m3u8") == before + 3, again);   // +3: nur Master + Video-/Tonliste des neuen Plans
        // Unterbrochener (nicht laufender) Auftrag: Abbruch entfernt sofort alles
        s.reset();
        s.delayMs = 40;
        Rig r2 = new Rig("cancel-idle", s);
        String id2 = newId();
        String p3 = r2.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r2.engine.start(PEER, BIND, id2, field(p3, "planId"), firstOption(p3), "x");
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 3 && System.nanoTime() < end) Thread.sleep(5);
        r2.engine.stopAll();
        await(r2.engine, id2, "interrupted");
        r2.restart();
        String c = r2.engine.cancel(id2);
        check("Abbruch eines unterbrochenen Auftrags: sofort cancelled, Daten entfernt", "cancelled".equals(field(c, "state"))
            && Arrays.equals(list(r2.dir(id2)), new String[] {"journal"}), c + " " + Arrays.toString(list(r2.dir(id2))));
        // Absturz direkt nach dem dauerhaften Abbruchwunsch: der Neustart verwirft alles, kein Fortsetzen
        s.reset();
        s.delayMs = 40;
        Rig r3 = new Rig("cancel-crash", s);
        String id3 = newId();
        String p4 = r3.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r3.engine.start(PEER, BIND, id3, field(p4, "planId"), firstOption(p4), "x");
        end = System.nanoTime() + 10_000_000_000L;
        while (s.fullSegments() < 3 && System.nanoTime() < end) Thread.sleep(5);
        r3.hooks.point = "journal-renamed";      // die nächste Journal-Ersetzung ist die des Abbruchwunsches
        r3.hooks.hit = 1;
        try { r3.engine.cancel(id3); } catch (Crash expected) { /* Prozess "stirbt" direkt nach dem Umbenennen */ }
        r3.restart();
        String st3 = r3.engine.status(id3);
        check("Abbruchwunsch vor Absturz: nach Neustart cancelled, Daten entfernt", "cancelled".equals(field(st3, "state"))
            && Arrays.equals(list(r3.dir(id3)), new String[] {"journal"}), st3 + " " + Arrays.toString(list(r3.dir(id3))));
        String p5 = r3.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        check("Abbruchwunsch vor Absturz: Wiederaufnahme abgelehnt", "cancelled".equals(field(r3.engine.start(PEER, BIND, id3, field(p5, "planId"), firstOption(p5), "x"), "state")), "");
    }

    static void storageAndFailures(Server s) throws Exception {
        // Speicherquote vor dem Start (Schätzung aus BANDWIDTH × Dauer)
        s.reset();
        Rig r = new Rig("space", s);
        r.store.free = 100L << 20;
        String id = newId();
        String st = run(r, s, "ts_sep", id);
        check("Voller Speicher vor dem Start: failed:no_space, kein Segment geladen", "failed".equals(field(st, "state")) && "no_space".equals(field(st, "reason")) && s.fullSegments() == 0, st);
        // Speicher läuft während des Abrufs voll
        s.reset();
        Rig r2 = new Rig("space-mid", s);
        r2.store.free = 300L << 20;      // reicht für die Startquote (256 MiB + 2 × Schätzung), nicht für "Rest + Ausgabe + Reserve/2"
        String id2 = newId();
        String plan = r2.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        r2.engine.start(PEER, BIND, id2, field(plan, "planId"), firstOption(plan), "x");
        long end = System.nanoTime() + 5_000_000_000L;
        while (s.fullSegments() < 2 && System.nanoTime() < end) Thread.sleep(2);
        r2.store.free = 10L << 20;
        st = await(r2.engine, id2, "done", "failed", "interrupted");
        check("Speicher läuft voll: Abbruch während des Abrufs (nicht erst vor dem Remux)", s.fullSegments() < countResources("ts_sep", "v0.m3u8", "v1.m3u8")
            && r2.remux.runs.get() == 0, s.fullSegments());
        check("Speicher läuft voll: failed:no_space, Daten entfernt, nichts veröffentlicht", "failed".equals(field(st, "state")) && "no_space".equals(field(st, "reason"))
            && Arrays.equals(list(r2.dir(id2)), new String[] {"journal"}) && r2.store.files().length == 0, st);
        // Ablage nicht beschreibbar (Android 8–9 ohne Berechtigung): vor jedem Segmentabruf
        s.reset();
        Rig r3 = new Rig("perm", s);
        r3.store.writable = false;
        st = run(r3, s, "ts_sep", newId());
        check("Ohne Speicherberechtigung: failed:storage_permission, kein Segment geladen", "storage_permission".equals(field(st, "reason")) && s.fullSegments() == 0, st);
        // Remux-/Prüffehler: nichts veröffentlicht, alles entfernt, kein stiller Ausweichweg
        for (String kind : new String[] {"no-audio", "verify-duration", "oom"}) {
            s.reset();
            Rig rx = new Rig("remux-" + kind, s);
            if (kind.equals("oom")) rx.remux.oom = true; else if (kind.startsWith("verify")) rx.remux.verifyFail = kind; else rx.remux.failWith = kind;
            String idx = newId();
            st = run(rx, s, "ts_sep", idx);
            String want = kind.equals("oom") ? "memory" : kind.replace('-', '_');
            check("Remux/Prüfung " + kind + ": failed:" + want + ", nichts veröffentlicht, Daten entfernt", "failed".equals(field(st, "state")) && want.equals(field(st, "reason"))
                && rx.store.files().length == 0 && Arrays.equals(list(rx.dir(idx)), new String[] {"journal"}), st + " " + Arrays.toString(rx.store.files()));
        }
        // Dienstende während des Remux: unterbrochen mit Grund "stopped" (nicht der Folgefehler des Remuxers)
        s.reset();
        Rig rs = new Rig("stop-mux", s);
        rs.remux.blockMs = 10_000;
        String ids = newId();
        String ps = rs.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        rs.engine.start(PEER, BIND, ids, field(ps, "planId"), firstOption(ps), "x");
        end = System.nanoTime() + 10_000_000_000L;
        while (!"mux".equals(field(rs.engine.status(ids), "phase")) && System.nanoTime() < end) Thread.sleep(5);
        rs.engine.stopAll();
        st = await(rs.engine, ids, "interrupted", "failed", "done", "cancelled");
        check("Dienstende im Remux: interrupted:stopped, Segmente bleiben, keine Ausgabe", "interrupted".equals(field(st, "state")) && "stopped".equals(field(st, "reason"))
            && rs.store.files().length == 0 && new File(rs.dir(ids), "v0.seg").isFile() && !new File(rs.dir(ids), "out.mp4.part").exists(), st);
        // Abbruch während des Remux: < 2 s, keine Ausgabe
        s.reset();
        Rig rc = new Rig("cancel-mux", s);
        rc.remux.blockMs = 10_000;
        String idc = newId();
        String pc = rc.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8"));
        rc.engine.start(PEER, BIND, idc, field(pc, "planId"), firstOption(pc), "x");
        end = System.nanoTime() + 10_000_000_000L;
        while (!"mux".equals(field(rc.engine.status(idc), "phase")) && System.nanoTime() < end) Thread.sleep(5);
        long t0 = System.nanoTime();
        rc.engine.cancel(idc);
        st = await(rc.engine, idc, "cancelled", "done", "failed");
        check("Abbruch im Remux: cancelled in < 2 s, keine Ausgabe", "cancelled".equals(field(st, "state")) && (System.nanoTime() - t0) / 1_000_000 < 2000
            && rc.store.files().length == 0, st);
    }

    static void limits(Server s) throws Exception {
        // Zwei HLS-Aufträge gleichzeitig, ein dritter "busy"; HTTP global ≤ 4, Remuxer genau 1
        s.reset();
        s.delayMs = 30;
        JobSlots slots = new JobSlots(2, 4);
        Rig r = new Rig("limits", s, slots);
        r.remux.blockMs = 300;
        String a = newId(), b = newId(), c = newId();
        String pa = r.engine.plan(PEER, BIND, s.url("ts_sep", "master.m3u8")), pb = r.engine.plan(PEER, BIND, s.url("fmp4_sep", "master.m3u8")),
            pc = r.engine.plan(PEER, BIND, s.url("ts_muxed", "master.m3u8"));
        r.engine.start(PEER, BIND, a, field(pa, "planId"), firstOption(pa), "A");
        r.engine.start(PEER, BIND, b, field(pb, "planId"), firstOption(pb), "B");
        String third = r.engine.start(PEER, BIND, c, field(pc, "planId"), firstOption(pc), "C");
        check("Grenzen: dritter gleichzeitiger Auftrag → busy (nichts vermerkt)", "busy".equals(field(third, "state")) && !r.engine.knows(c), third);
        await(r.engine, a, "done", "failed");
        await(r.engine, b, "done", "failed");
        check("Grenzen: beide Aufträge done", "done".equals(field(r.engine.status(a), "state")) && "done".equals(field(r.engine.status(b), "state")), r.engine.status(a) + r.engine.status(b));
        check("Grenzen: höchstens 4 HTTP-Anfragen gleichzeitig (gemessen am Server)", s.maxInFlight.get() <= 4 && s.maxInFlight.get() >= 3, s.maxInFlight.get());
        check("Grenzen: genau ein Remuxer gleichzeitig", r.remux.maxConcurrent.get() == 1 && r.remux.runs.get() == 2, r.remux.maxConcurrent.get());
        check("Grenzen: Plätze und Verbindungen vollständig zurückgegeben", slots.inUse() == 0 && slots.httpInUse() == 0, slots.inUse() + "/" + slots.httpInUse());
        // Ein Auftrag: höchstens 2 Abrufe gleichzeitig
        s.reset();
        s.delayMs = 30;
        Rig r1 = new Rig("limits-one", s);
        run(r1, s, "ts_sep", newId());
        check("Grenzen: je HLS-Auftrag höchstens 2 Abrufe gleichzeitig", s.maxInFlight.get() == 2, s.maxInFlight.get());
        // Gemeinsam mit MP4: ein laufender MP4-Auftrag belegt Platz und Verbindung
        JobSlots shared = new JobSlots(2, 4);
        check("Gemeinsame Grenze: MP4 belegt Platz + Verbindung", shared.tryAcquireWithConnection() && shared.httpInUse() == 1, "");
        check("Gemeinsame Grenze: zweiter Platz frei", shared.tryAcquire(), "");
        check("Gemeinsame Grenze: kein dritter Platz (MP4 oder HLS)", !shared.tryAcquire() && !shared.tryAcquireWithConnection(), "");
        shared.release();
        shared.releaseWithConnection();
        check("Gemeinsame Grenze: freigegeben", shared.inUse() == 0 && shared.httpInUse() == 0, "");
        boolean threw = false;
        try { shared.release(); } catch (IllegalStateException e) { threw = true; }
        check("Gemeinsame Grenze: Freigabe ohne Belegung wird erkannt", threw, "");
    }

    static void journalFormat() throws Exception {
        HlsJournal j = new HlsJournal();
        j.id = "0123456789abcdef0123456789abcdef";
        j.salt = "a".repeat(32);
        j.shape = "b".repeat(64);
        j.videoCount = 3;
        j.audioCount = 2;
        byte[] raw = j.serialize();
        check("Journal: Kopf rundläuft", new String(HlsJournal.parse(raw).serialize(), StandardCharsets.UTF_8).equals(new String(raw, StandardCharsets.UTF_8)), "");
        String[] bad = {
            new String(raw, StandardCharsets.UTF_8).replace("hls-job-v1", "hls-job-v2"),
            new String(raw, StandardCharsets.UTF_8).replace("state RUNNING", "state BOGUS"),
            new String(raw, StandardCharsets.UTF_8).replace("cancel 0", "cancel 2"),
            new String(raw, StandardCharsets.UTF_8).replace("counts 3 2", "counts 0 2"),
            new String(raw, StandardCharsets.UTF_8).replace("end\n", ""),
            new String(raw, StandardCharsets.UTF_8) + "extra\n",
            new String(raw, StandardCharsets.UTF_8).replace("salt " + "a".repeat(32), "salt xyz"),
        };
        int rejected = 0;
        for (String b : bad) { try { HlsJournal.parse(b.getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { rejected++; } }
        check("Journal: jede Abweichung im Kopf abgelehnt (" + bad.length + ")", rejected == bad.length, rejected);
        File d = Files.createTempDirectory(base.toPath(), "jr").toFile();
        j.save(d, HlsJournal.Hooks.NONE);
        j.append(d, new HlsJournal.Res('v', 0, 10, "c".repeat(64), "\"e1\""), HlsJournal.Hooks.NONE);
        j.append(d, new HlsJournal.Res('a', 1, 20, "d".repeat(64), null), HlsJournal.Hooks.NONE);
        j.drop(d, "v0", HlsJournal.Hooks.NONE);
        HlsJournal back = HlsJournal.load(d);
        check("Journal: Log mit Anhängen und Verwerfen wird exakt wiederhergestellt", back.resources.size() == 1 && back.resources.containsKey("a1")
            && back.resources.get("a1").length == 20 && back.resources.get("a1").etag == null, back.resources.keySet());
        String[] badLog = {"r v 3 10 " + "c".repeat(64) + " -", "r q 0 10 " + "c".repeat(64) + " -", "r v 0 0 " + "c".repeat(64) + " -",
            "r v 0 10 xyz -", "x v 0", "r v 0 10 " + "c".repeat(64) + " " + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("W/\"weak\"".getBytes(StandardCharsets.UTF_8))};
        int logRejected = 0;
        for (String line : badLog) {
            File dd = Files.createTempDirectory(base.toPath(), "jl").toFile();
            j.resources.clear();
            j.save(dd, HlsJournal.Hooks.NONE);
            Files.write(new File(dd, "segments.log").toPath(), (line + " " + HlsJournal.crc(line) + "\n").getBytes(StandardCharsets.UTF_8));
            try { HlsJournal.load(dd); } catch (IOException e) { logRejected++; }
        }
        check("Journal: ungültige Logeinträge (Index, Spur, Länge, SHA, Verwerfen ohne Eintrag, schwacher ETag) abgelehnt", logRejected == badLog.length, logRejected);
    }
}
