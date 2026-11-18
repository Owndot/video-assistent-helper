package de.evp.helfer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.Progress;
import de.evp.helfer.dl.Retry;
import de.evp.helfer.net.DeviceTestClients;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NUR DIAGNOSE, NUR Wegwerf-Emulator (Option B nach dem API-34-Befund auf 0f989ca). Liegt bewusst unter tests/bootscan/
 * und wird ausschließlich vom Diagnose-Workflow vor dem Bau in die Test-APK kopiert; im normalen Gerätelauf existiert
 * diese Klasse nicht. Aufgerufen nur von tools/run-bootscan-probe.sh.
 *
 * Frage: Was geschieht mit einer gerade veröffentlichten MediaStore-Zeile, wenn der erste Volume-Scan nach dem Booten
 * ("Scanned external_primary due to REASON_MOUNTED") gleichzeitig läuft – verloren, ersetzt, Besitzer geändert, nur
 * vorübergehend unsichtbar? Vergleich: Veröffentlichungen WÄHREND des Scans (phase=during) gegen kontrollierte nach
 * seinem Abschluss (phase=after); phase=recheck ist die abschließende Nachprüfung in einem neuen Prozess.
 * A/B-Paketdiagnose (tools/run-pkgremoval-probe.sh): phase=pre veröffentlicht wenige Dateien mit der ersten Installation
 * (nur Prüfungen +0/+250 ms, damit die Deinstallation noch während des Boot-Scans folgen kann), phase=post nach der
 * Neuinstallation bzw. Aktualisierung gedrosselt bis kurz nach dem Mount-Scan; bootscanMax begrenzt die Anzahl.
 *
 * Der Test macht KEINE Produkt-Assertion und bewertet nichts: er protokolliert nur Rohbeobachtungen (Logcat-Tag
 * BOOTSCAN), die tools/bootscan_verdict.py einordnet. Er schreibt nur synthetische Dateien "BootScanProbe-<run>-<seq>.mp4"
 * über die echte Engine und die echte App-Ablage, liest den MediaStore (eigene Sicht der App) und liest über zwei vom
 * Host abgelegte Shell-Skripte (nur lesend: "content query", find/stat/sha256sum) die privilegierte Sicht. Kein Schreiben
 * in die MediaProvider-Datenbank, kein Löschen, kein Aufräumen (der Emulator wird verworfen).
 */
@RunWith(AndroidJUnit4.class)
public class MediaStoreBootScanProbeTest {
    static final String TAG = "BOOTSCAN";
    static final int SIZE = 256 << 10;
    static final String QUERY_SH = "/data/local/tmp/bootscan-q.sh";
    static final String FILES_SH = "/data/local/tmp/bootscan-f.sh";
    static final long SNAPSHOT_MS = 500, DISK_MS = 2_000, SETTLE_MS = 12_000;
    static final long[] URI_OFFSETS = {0, 250, 2_000, 10_000};
    static final long[] URI_OFFSETS_PRE = {0, 250};
    static final Pattern SCAN_LINE = Pattern.compile("Scanned external_primary due to REASON_MOUNTED");
    static final Pattern ROW = Pattern.compile("^Row: \\d+ _id=(\\d+), owner_package_name=([^,]*), is_pending=(\\d+), is_trashed=(\\d+), "
        + "_data=([^,]*), _display_name=([^,]*), _size=([^,]*)$");
    static final Pattern FILE = Pattern.compile("^([0-9a-f]{64}) (\\d+) (/storage/\\S+)$");

    String run, phase, token, pkg;
    Context ctx;
    ContentResolver cr;

    static long now() { return System.currentTimeMillis(); }

    static void log(String s) { Log.i(TAG, "BOOTSCAN " + s); }

    /** Shell-Befehl (UiAutomation, Shell-Kontext) – nur die vom Host abgelegten, lesenden Skripte. */
    static List<String> shell(String cmd) throws IOException {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        }
        return out;
    }

    static String clean(String v) { return v == null ? "NULL" : v.replace(' ', '_').replace('|', '_'); }

    /** Eindeutiger synthetischer Inhalt je Lauf-Nummer; die ersten 8 Byte bleiben ein gültiger MP4-Anfang (ftyp). */
    static byte[] content(int seq) {
        byte[] b = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) b[i] = (byte) Mp4DownloadsInstrumentedTest.at(i);
        byte x = (byte) (seq * 31 + 7);
        for (int i = 8; i < SIZE; i++) b[i] ^= x;
        return b;
    }

    static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte d : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", d));
        return s.toString();
    }

    // ------------------------------------------------------------ Beobachtung (nur lesend)

    /** Eigene Sicht der App: alle Zeilen dieses Laufs in Downloads (auch ausstehende). null = Abfrage nicht verlässlich. */
    Map<String, String> appRows(String[] status) {
        Map<String, String> out = new TreeMap<>();
        Uri all = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        try (Cursor c = cr.query(all, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[] {"%" + token + "%"}, MediaStore.MediaColumns._ID)) {
            if (c == null) { status[0] = "null-cursor"; return null; }
            while (c.moveToNext()) out.put(String.valueOf(c.getLong(0)), clean(c.getString(1)) + "|" + c.getInt(2) + "|" + clean(c.getString(3)));
        } catch (RuntimeException e) {
            status[0] = "error:" + e.getClass().getSimpleName();
            return null;
        }
        status[0] = "ok";
        return out;
    }

    /**
     * Privilegierte Sicht (Shell, alle Besitzer, auch ausstehende/gelöschte Markierung): nur gültig mit Q-BEGIN, jeder Zeile
     * im erwarteten Format bzw. "No result found." und "Q-END rc=0". Alles andere ist KEIN Beleg (status bad:…).
     */
    Map<String, String> privRows(String[] status) {
        List<String> lines;
        try { lines = shell("sh " + QUERY_SH + " " + token); }
        catch (IOException | RuntimeException e) { status[0] = "bad:exec-" + e.getClass().getSimpleName(); return null; }
        if (lines.size() < 2 || !lines.get(0).equals("Q-BEGIN") || !lines.get(lines.size() - 1).equals("Q-END rc=0")) {
            status[0] = "bad:frame-" + (lines.isEmpty() ? "empty" : clean(lines.get(lines.size() - 1)).replace('=', '-'));
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        boolean none = false;
        for (String l : lines.subList(1, lines.size() - 1)) {
            if (l.equals("No result found.")) { none = true; continue; }
            Matcher m = ROW.matcher(l);
            if (!m.matches()) { status[0] = "bad:line"; return null; }
            out.put(m.group(1), clean(m.group(2)) + "|" + m.group(3) + "|" + m.group(4) + "|" + clean(m.group(5)));
        }
        if (none && !out.isEmpty()) { status[0] = "bad:mixed"; return null; }
        // leere Antwort nur, wenn der Provider ausdrücklich "No result found." meldete (sonst unvollständige Ausgabe)
        if (!none && out.isEmpty()) { status[0] = "bad:empty"; return null; }
        status[0] = "ok";
        return out;
    }

    /** Dateien dieses Laufs auf der Platte (Shell): Pfad -> Größe|SHA-256. null = Aufnahme nicht verlässlich. */
    Map<String, String> diskFiles(String[] status) {
        List<String> lines;
        try { lines = shell("sh " + FILES_SH + " " + token); }
        catch (IOException | RuntimeException e) { status[0] = "bad:exec-" + e.getClass().getSimpleName(); return null; }
        if (lines.size() < 2 || !lines.get(0).equals("F-BEGIN") || !lines.get(lines.size() - 1).equals("F-END rc=0")) {
            status[0] = "bad:frame";
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        for (String l : lines.subList(1, lines.size() - 1)) {
            Matcher m = FILE.matcher(l);
            if (!m.matches()) { status[0] = "bad:line"; return null; }
            out.put(m.group(3), m.group(2) + "|" + m.group(1));
        }
        status[0] = "ok";
        return out;
    }

    /**
     * Protokolliert nur Änderungen gegenüber der vorigen gültigen Aufnahme (+ neu/geändert, - verschwunden). Gedrosselt
     * (nach je 25 Zeilen 2 ms Pause): in Lauf 44281951152 fehlten bei 877 Zeilen in ~15 ms ohne Meldung Zeilen im Strom.
     * Vollständigkeit belegt die Prüfsumme der ganzen Aufnahme in der SNAP-/DSNAP-Zeile (digest), nicht die Anzahl.
     */
    static void diff(String kind, int n, Map<String, String> prev, Map<String, String> cur) {
        int lines = 0;
        for (Map.Entry<String, String> e : cur.entrySet())
            if (!e.getValue().equals(prev.get(e.getKey()))) { log(kind + " run=" + RUN + " n=" + n + " +" + e.getKey() + "|" + e.getValue()); pace(++lines); }
        for (String k : prev.keySet()) if (!cur.containsKey(k)) { log(kind + " run=" + RUN + " n=" + n + " -" + k); pace(++lines); }
    }

    static void pace(int lines) {
        if (lines % 25 == 0) { try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    /** SHA-256 (erste 16 Hex-Zeichen) über "schlüssel|wert" je Zeile, nach Schlüssel sortiert, mit "\n" verbunden. */
    static String digest(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(m).entrySet()) { if (b.length() > 0) b.append('\n'); b.append(e.getKey()).append('|').append(e.getValue()); }
        try { return sha(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16); } catch (Exception e) { return "-"; }
    }

    static volatile String RUN = "-";

    final class Sampler implements Runnable {
        final Map<String, String> app = new TreeMap<>(), priv = new TreeMap<>(), disk = new TreeMap<>();
        int n, invalid;
        long lastDisk;

        @Override public void run() { try { sample(false); } catch (Throwable t) { invalid++; log("SNAPERR run=" + run + " error=" + t.getClass().getSimpleName()); } }

        /** last=true: Schlussaufnahme – mit Dateiaufnahme erzwungen und als final=1 gekennzeichnet. */
        synchronized void sample(boolean last) {
            n++;
            long t = now();
            String[] as = {"?"}, ps = {"?"};
            Map<String, String> a = appRows(as);
            Map<String, String> p = privRows(ps);
            String cross = "na";
            if (a != null && p != null) {
                // Vollständigkeit der privilegierten Abfrage: jede Zeile, die die App selbst sieht, muss auch dort stehen
                List<String> missing = new ArrayList<>();
                for (String id : a.keySet()) if (!p.containsKey(id)) missing.add(id);
                cross = missing.isEmpty() ? "ok" : "missing:" + String.join(",", missing);
            }
            // Nur gültige Aufnahmen fortschreiben: App-Sicht bei erfolgreicher Abfrage, privilegierte Sicht nur, wenn sie
            // vollständig ist (enthält alles, was die App selbst sieht); sonst bleibt der letzte gültige Stand stehen.
            if (a != null) { diff("APP", n, app, a); app.clear(); app.putAll(a); }
            if (a != null && p != null && cross.equals("ok")) { diff("PRIV", n, priv, p); priv.clear(); priv.putAll(p); }
            if (a == null || p == null || !cross.equals("ok")) invalid++;
            log("SNAP run=" + run + " n=" + n + " t=" + t + " tEnd=" + now() + " app=" + as[0] + " appN=" + (a == null ? -1 : a.size())
                + " appH=" + (a == null ? "-" : digest(a)) + " priv=" + ps[0] + " privN=" + (p == null ? -1 : p.size())
                + " privH=" + (p == null ? "-" : digest(p)) + " cross=" + cross + " final=" + (last ? 1 : 0));
            if (last || t - lastDisk >= DISK_MS) {
                lastDisk = t;
                String[] ds = {"?"};
                Map<String, String> d = diskFiles(ds);
                if (d != null) { diff("DISK", n, disk, d); disk.clear(); disk.putAll(d); }
                log("DSNAP run=" + run + " n=" + n + " t=" + now() + " disk=" + ds[0] + " diskN=" + (d == null ? -1 : d.size())
                    + " diskH=" + (d == null ? "-" : digest(d)) + " final=" + (last ? 1 : 0));
            }
        }
    }

    /** Eigene Abfrage über genau die erhaltene URI (wie im fehlgeschlagenen Gerätetest), zu festen Zeitpunkten nach "done". */
    void uriQuery(int seq, long off, Uri uri) {
        String res;
        try (Cursor c = cr.query(uri, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c == null) res = "null-cursor";
            else if (!c.moveToFirst()) res = "none";
            else res = "found id=" + c.getLong(0) + " owner=" + clean(c.getString(1)) + " pending=" + c.getInt(2) + " data=" + clean(c.getString(3));
        } catch (RuntimeException e) {
            res = "error:" + e.getClass().getSimpleName();
        }
        log("URIQ run=" + run + " seq=" + seq + " off=" + off + " t=" + now() + " res=" + res);
    }

    // ------------------------------------------------------------ Zeitmessung der echten Ablage (reine Durchleitung)

    static final class Timing { volatile long insertStart = -1, insertEnd = -1, commitStart = -1, commitEnd = -1; volatile String commit = "-"; }

    static final class TimedStore implements DownloadStore {
        final Mp4Downloads real;
        final Map<String, Timing> byName = new ConcurrentHashMap<>();
        TimedStore(Mp4Downloads real) { this.real = real; }
        @Override public long freeBytes() throws IOException { return real.freeBytes(); }
        @Override public boolean writable() { return real.writable(); }
        @Override public boolean isPublished(String ref, long total) throws IOException { return real.isPublished(ref, total); }
        @Override public byte[] loadJournal() throws IOException { return real.loadJournal(); }
        @Override public void saveJournal(byte[] data) throws IOException { real.saveJournal(data); }
        @Override public Target create(String name, String mime) throws IOException {
            Timing tm = new Timing();
            byName.put(name, tm);
            tm.insertStart = now();
            Target t = real.create(name, mime);
            tm.insertEnd = now();
            return new Target() {
                @Override public OutputStream out() throws IOException { return t.out(); }
                @Override public String ref() { return t.ref(); }
                @Override public Object commit() throws IOException {
                    tm.commitStart = now();
                    try {
                        Object o = t.commit();
                        tm.commit = "ok";
                        return o;
                    } catch (IOException | RuntimeException e) {
                        tm.commit = "ex:" + e.getClass().getSimpleName();
                        throw e;
                    } finally {
                        tm.commitEnd = now();
                    }
                }
                @Override public void abort() { t.abort(); }
            };
        }
    }

    static final class Done { final CountDownLatch latch = new CountDownLatch(1); volatile long at = -1; volatile String state = "-", reason = "-"; volatile Object saved; }

    // ------------------------------------------------------------ Ablauf

    @Test public void probe() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        run = args.getString("bootscanRun");
        phase = args.getString("bootscanPhase");
        assertNotNull("only run by tools/run-bootscan-probe.sh", run);
        assertTrue("run id", run.matches("[0-9a-f]{12}"));
        assertTrue("phase", java.util.Arrays.asList("during", "after", "recheck", "pre", "post").contains(phase));
        assertTrue("API 34 only (is_trashed, owner columns)", Build.VERSION.SDK_INT >= 30);
        RUN = run;
        token = "BootScanProbe-" + run + "-";
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cr = ctx.getContentResolver();
        pkg = ctx.getPackageName();
        log("START run=" + run + " phase=" + phase + " sdk=" + Build.VERSION.SDK_INT + " pkg=" + pkg + " appUid=" + ctx.getApplicationInfo().uid
            + " pid=" + android.os.Process.myPid() + " t=" + now());
        Sampler sampler = new Sampler();
        ScheduledExecutorService ex = Executors.newScheduledThreadPool(3);
        int downloads = 0;
        try {
            ex.scheduleWithFixedDelay(sampler, 0, SNAPSHOT_MS, TimeUnit.MILLISECONDS);
            if ("recheck".equals(phase)) {
                // Nachprüfung in einem neuen Prozess: Abfrage über jede ursprünglich erhaltene URI (Host übergibt die IDs
                // und die Basis), danach weitere Aufnahmen
                String base = args.getString("bootscanBase");
                String ids = args.getString("bootscanIds");
                assertNotNull("recheck needs bootscanBase", base);
                assertNotNull("recheck needs bootscanIds", ids);
                for (String part : ids.split(",")) {
                    if (!part.matches("\\d+:\\d+")) continue;   // "-" = keine Downloads
                    String[] kv = part.split(":");
                    uriQuery(Integer.parseInt(kv[0]), -1, Uri.parse(base + kv[1]));
                }
                Thread.sleep(6_000);
            } else {
                downloads = publish(args, ex);
            }
        } finally {
            ex.shutdown();
            ex.awaitTermination(30, TimeUnit.SECONDS);
            sampler.sample(true);   // Schlussaufnahme nach allen geplanten Abfragen (immer mit Dateiaufnahme)
            log("END run=" + run + " phase=" + phase + " downloads=" + downloads + " snapshots=" + sampler.n + " invalid=" + sampler.invalid
                + " final=" + sampler.n + " t=" + now());
        }
    }

    boolean scanSeen() {
        try {
            for (String l : shell("logcat -d -s MediaProvider:I")) if (SCAN_LINE.matcher(l).find()) return true;
        } catch (IOException ignored) { }
        return false;
    }

    /** Veröffentlicht nacheinander je eine synthetische Datei über die echte Engine; höchstens zwei gleichzeitig. */
    int publish(Bundle args, ScheduledExecutorService ex) throws Exception {
        long seconds = Long.parseLong(args.getString("bootscanSeconds", "40"));
        int max = Integer.parseInt(args.getString("bootscanMax", String.valueOf(Integer.MAX_VALUE)));
        long pace = Long.parseLong(args.getString("bootscanPaceMs", "0"));
        long afterScan = Long.parseLong(args.getString("bootscanAfterScanMs", "5000"));
        boolean during = "during".equals(phase) || "post".equals(phase);   // bis kurz nach dem Mount-Scan
        boolean pre = "pre".equals(phase);
        int seq0 = "during".equals(phase) ? 0 : "after".equals(phase) ? 10_000 : pre ? 20_000 : 30_000;
        int parallel = pace > 0 ? 1 : 2;
        long end = now() + seconds * 1000;
        long scanAt = -1;
        if (during && scanSeen()) { scanAt = now(); log("SCANSEEN run=" + run + " t=" + scanAt + " already=1"); }
        Map<String, byte[]> bodies = new ConcurrentHashMap<>();
        Map<String, Done> done = new ConcurrentHashMap<>();
        try (Mp4DownloadsInstrumentedTest.Server srv = new Mp4DownloadsInstrumentedTest.Server((line, h, out, i) -> {
            String path = line.split(" ")[1];
            byte[] body = bodies.get(path.substring(1, path.indexOf('.')));
            Mp4DownloadsInstrumentedTest.head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + SIZE, "ETag: \"bs-" + path.hashCode() + "\"");
            out.write(body);
        })) {
            Mp4Downloads store = new Mp4Downloads(ctx, "bootscan-" + run + "-" + phase, DeviceTestClients.loopback(srv.ss.getLocalPort()),
                () -> Mp4Downloads.legacyWritePermitted(ctx));
            TimedStore timed = new TimedStore(store);
            DirectDownloads engine = new DirectDownloads(DeviceTestClients.loopback(srv.ss.getLocalPort()), timed, new DirectDownloads.Listener() {
                @Override public void progress(String id, String title, long bytes, long total) { store.progress(id, title, bytes, total); }
                @Override public void progress(String id, String title, Progress p) { store.progress(id, title, p); }
                @Override public void waiting(String id, String title, Retry.Wait w) { store.waiting(id, title, w); }
                @Override public void finished(String id, String title, String state, String reason, Object saved) { finished(id, title, state, reason, saved, -1); }
                @Override public void finished(String id, String title, String state, String reason, Object saved, long size) {
                    store.finished(id, title, state, reason, saved, size);
                    Done d = done.get(id);
                    if (d == null) return;
                    d.at = now(); d.state = state; d.reason = reason; d.saved = saved;
                    d.latch.countDown();
                }
            });
            List<Object[]> inFlight = new ArrayList<>();
            int seq = seq0, count = 0;
            long lastScanCheck = 0;
            try {
                while (now() < end) {
                    if (during && scanAt < 0 && now() - lastScanCheck >= 1_000) {
                        lastScanCheck = now();
                        if (scanSeen()) { scanAt = now(); log("SCANSEEN run=" + run + " t=" + scanAt + " already=0"); }
                    }
                    if (during && scanAt > 0 && now() > scanAt + afterScan) break;   // bis kurz nach dem Scan weiter veröffentlichen
                    if (count >= max && inFlight.isEmpty()) break;
                    while (inFlight.size() < parallel && count < max) {
                        if (pace > 0 && count > 0) Thread.sleep(pace);
                        String key = String.valueOf(seq);
                        bodies.put(key, content(seq));
                        String id = Mp4DownloadsInstrumentedTest.newId();
                        String title = token + seq;
                        Done d = new Done();
                        done.put(id, d);
                        engine.start(id, srv.url("/" + key + ".mp4"), title);
                        inFlight.add(new Object[] {seq, id, title, d});
                        seq++;
                        count++;
                    }
                    if (inFlight.isEmpty()) break;
                    Object[] first = inFlight.remove(0);
                    report((Integer) first[0], (String) first[2], (Done) first[3], timed, ex);
                }
                for (Object[] f : inFlight) report((Integer) f[0], (String) f[2], (Done) f[3], timed, ex);
            } finally {
                engine.stopAll();
                engine.awaitIdle(30_000);
                store.engine.stopAll();
            }
            Thread.sleep(pre ? 600 : SETTLE_MS);   // alle geplanten Abfragen nach dem letzten "done" laufen noch
            return count;
        }
    }

    void report(int seq, String title, Done d, TimedStore timed, ScheduledExecutorService ex) throws RuntimeException {
        try { d.latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String name = DirectDownloads.fileName(title);
        Timing tm = timed.byName.get(name);
        String uri = d.saved instanceof Uri ? d.saved.toString() : "-";
        String id = d.saved instanceof Uri ? ((Uri) d.saved).getLastPathSegment() : "-";
        String sha;
        try { sha = sha(content(seq)); } catch (Exception e) { sha = "-"; }
        log("DL run=" + run + " phase=" + phase + " seq=" + seq + " name=" + name + " sha=" + sha + " size=" + SIZE
            + " insertStart=" + (tm == null ? -1 : tm.insertStart) + " insertEnd=" + (tm == null ? -1 : tm.insertEnd)
            + " commitStart=" + (tm == null ? -1 : tm.commitStart) + " commitEnd=" + (tm == null ? -1 : tm.commitEnd)
            + " commit=" + (tm == null ? "-" : tm.commit) + " done=" + d.at + " state=" + (d.latch.getCount() == 0 ? d.state : "timeout")
            + " reason=" + (d.reason.isEmpty() ? "-" : clean(d.reason)) + " uri=" + uri + " id=" + id);
        if (d.saved instanceof Uri) {
            Uri u = (Uri) d.saved;
            for (long off : "pre".equals(phase) ? URI_OFFSETS_PRE : URI_OFFSETS) ex.schedule(() -> uriQuery(seq, off, u), off, TimeUnit.MILLISECONDS);
        }
    }
}
