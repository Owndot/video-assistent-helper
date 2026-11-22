package de.evp.helfer.dashdl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.ControlCommands;
import de.evp.helfer.dash.DashException;
import de.evp.helfer.dash.DashFingerprint;
import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsJournal;
import de.evp.helfer.hls.HlsRemux;
import de.evp.helfer.net.LocalTestClients;
import de.evp.helfer.net.RequestContext;
import de.evp.helfer.net.SafeHttpClient;

/**
 * S3b JVM-Tests: DASH-Koordinator gegen den echten SafeHttpClient und einen lokalen Server mit dem ffmpeg-DASH-Fixture
 * (make-dash-fixtures.sh). Der Remux ist hier eine Nachbildung (wie im HLS-Test); den echten Media3-Zusammenbau prüft
 * run_remux.sh. Geprüft: Fingerabdruck, Profil, Sitzungsbindung, Fmp4Inspect (echte und manipulierte Dateien),
 * Ablauf, Verschlüsselung/Codec, Wiederaufnahme mit starkem ETag, Absturz an Journal-/Veröffentlichungsgrenzen,
 * Abbruchvorrang, Kanalbefehle – und dass nie eine Adresse/ein Token in Journal, Antworten oder Rückmeldungen steht.
 *   java TestDashEngine FIXTURES_DIR WORK_DIR
 */
public final class TestDashEngine {
    static int passed, failed;
    static File fx, base;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    // ------------------------------------------------------------------ lokaler Server

    static final class Server {
        final HttpServer srv;
        final int port;
        volatile String mpdText;                 // ausgeliefertes MPD (null: Datei)
        volatile String gzipMpd;                 // "1": Content-Encoding gzip
        volatile String failPath; volatile int failStatus;
        final AtomicInteger fail503 = new AtomicInteger();
        final Map<String, byte[]> override = new ConcurrentHashMap<>();   // Datei → anderer Inhalt
        volatile boolean redirectPrivate;
        volatile boolean weakEtag;
        final Map<String, AtomicInteger> fullGets = new ConcurrentHashMap<>(), rangeGets = new ConcurrentHashMap<>();
        final List<String> leaks = new CopyOnWriteArrayList<>(), referers = new CopyOnWriteArrayList<>();

        Server() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = srv.getAddress().getPort();
            srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            srv.createContext("/", this::handle);
            srv.start();
        }

        String mpdUrl() { return "http://127.0.0.1:" + port + "/f/avc/manifest.mpd?t=SECRETTOKEN"; }

        void reset() {
            mpdText = null; gzipMpd = null; failPath = null; failStatus = 0; fail503.set(0); override.clear(); redirectPrivate = false; weakEtag = false;
            fullGets.clear(); rangeGets.clear(); leaks.clear(); referers.clear();
        }

        int full(String suffix) { int n = 0; for (Map.Entry<String, AtomicInteger> e : fullGets.entrySet()) if (e.getKey().endsWith(suffix)) n += e.getValue().get(); return n; }
        int fullChunks() { int n = 0; for (Map.Entry<String, AtomicInteger> e : fullGets.entrySet()) if (e.getKey().contains("chunk-")) n += e.getValue().get(); return n; }
        int ranges() { int n = 0; for (AtomicInteger a : rangeGets.values()) n += a.get(); return n; }

        void handle(HttpExchange x) throws IOException {
            try {
                for (String h : new String[] {"Cookie", "Authorization", "Origin"}) if (x.getRequestHeaders().containsKey(h)) leaks.add(h);
                String ref = x.getRequestHeaders().getFirst("Referer");
                if (ref != null) referers.add(ref);
                String path = x.getRequestURI().getRawPath();
                if (!path.startsWith("/f/")) { x.sendResponseHeaders(404, -1); return; }
                String rel = path.substring(3), name = rel.substring(rel.lastIndexOf('/') + 1);
                if (redirectPrivate && name.startsWith("chunk-")) {
                    x.getResponseHeaders().set("Location", "http://10.0.0.1/" + name);
                    x.sendResponseHeaders(302, -1); return;
                }
                if (name.equals(failPath)) { x.sendResponseHeaders(failStatus, -1); return; }
                if (name.startsWith("chunk-") && fail503.getAndUpdate(v -> Math.max(0, v - 1)) > 0) { x.sendResponseHeaders(503, -1); return; }
                byte[] body;
                if (name.equals("manifest.mpd") && mpdText != null) body = mpdText.getBytes(StandardCharsets.UTF_8);
                else if (override.containsKey(name)) body = override.get(name);
                else {
                    File f = new File(fx, rel);
                    if (!f.isFile() || !f.getCanonicalPath().startsWith(fx.getCanonicalPath())) { x.sendResponseHeaders(404, -1); return; }
                    body = Files.readAllBytes(f.toPath());
                }
                if (name.equals("manifest.mpd")) {
                    if (gzipMpd != null) x.getResponseHeaders().set("Content-Encoding", "gzip");
                    x.getResponseHeaders().set("Content-Type", "application/dash+xml");
                    x.sendResponseHeaders(200, body.length);
                    try (OutputStream o = x.getResponseBody()) { o.write(body); }
                    return;
                }
                String etag = (weakEtag ? "W/" : "") + "\"" + sha(body).substring(0, 16) + "\"";
                x.getResponseHeaders().set("ETag", etag);
                String range = x.getRequestHeaders().getFirst("Range"), ifRange = x.getRequestHeaders().getFirst("If-Range");
                if ("bytes=0-0".equals(range) && etag.equals(ifRange) && !weakEtag) {
                    rangeGets.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                    x.getResponseHeaders().set("Content-Range", "bytes 0-0/" + body.length);
                    x.sendResponseHeaders(206, 1);
                    try (OutputStream o = x.getResponseBody()) { o.write(body, 0, 1); }
                    return;
                }
                fullGets.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                x.sendResponseHeaders(200, body.length);
                try (OutputStream o = x.getResponseBody()) { o.write(body); }
            } catch (IOException e) {
                // Client hat abgebrochen
            } finally {
                x.close();
            }
        }
    }

    static String sha(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ Ablage, Remux, Rückmeldungen (wie im HLS-Test)

    static final class DirStore implements DownloadStore {
        final File dir;
        volatile long free = Long.MAX_VALUE / 4;
        final AtomicInteger committed = new AtomicInteger(), aborted = new AtomicInteger();
        DirStore(File dir) { this.dir = dir; dir.mkdirs(); }
        @Override public long freeBytes() { return free; }
        @Override public boolean writable() { return true; }
        @Override public boolean isPublished(String ref, long total) { File f = new File(ref); return dir.equals(f.getParentFile()) && f.isFile() && f.length() == total; }
        @Override public Target create(String displayName, String mime) throws IOException {
            File part = File.createTempFile(".part-", ".tmp", dir);
            File f0 = new File(dir, displayName);
            for (int n = 2; f0.exists(); n++) f0 = new File(dir, displayName + "-" + n);   // wie die echte Ablage: kein Überschreiben
            final File fin = f0;
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
        void processStartCleanup() { for (String n : files()) if (n.startsWith(".part-")) new File(dir, n).delete(); }
    }

    static final class FakeRemux implements HlsRemux {
        volatile String failWith;
        volatile boolean ioFail;
        final AtomicInteger runs = new AtomicInteger();
        volatile Input last;
        @Override public Result remux(Input in, File out, BooleanSupplier cancelled) throws IOException, HlsException {
            runs.incrementAndGet();
            last = in;
            if (failWith != null) { out.delete(); throw new HlsException(failWith); }
            if (ioFail) { out.delete(); throw new java.io.EOFException("truncated sample"); }
            try (OutputStream o = new FileOutputStream(out)) {
                for (File f : in.video) o.write(Files.readAllBytes(f.toPath()));
                for (File f : in.audio) o.write(Files.readAllBytes(f.toPath()));
            }
            return new Result(in.video.size(), in.audio.size(), 0, 0);
        }
        volatile double lastV, lastA, lastTol = -1;
        @Override public void verify(File out, Result expected, double v, double a, double tol, BooleanSupplier cancelled) throws HlsException {
            lastV = v; lastA = a; lastTol = tol;
            if (!out.isFile() || out.length() == 0) throw new HlsException("verify-missing");
            // Fix 1: erwartet wird die tatsächliche Stückdauer (Fixture: 20 s Video, ≈ 20 s Ton), nicht die Plandauer
            if (Math.abs(v - 20.0) > 0.1 || Math.abs(a - 20.0) > 0.1 || tol < 1.0) throw new HlsException("verify-duration");
        }
    }

    static final class Events implements DirectDownloads.Listener {
        final Map<String, String> finished = new ConcurrentHashMap<>();
        final List<String> all = new CopyOnWriteArrayList<>();
        @Override public void progress(String id, String title, long bytes, long total) { all.add("progress " + id + " " + title + " " + bytes); }
        @Override public void finished(String id, String title, String state, String reason, Object saved) {
            all.add("finished " + id + " " + title + " " + state + " " + reason);
            finished.put(id, state + (reason.isEmpty() ? "" : ":" + reason));
        }
    }

    static final class Crash extends Error { Crash(String p) { super("crash at " + p, null, false, false); } }

    static class CrashHooks implements HlsJournal.Hooks {
        volatile String point;
        volatile int hit;
        final AtomicInteger seen = new AtomicInteger();
        final AtomicBoolean dead = new AtomicBoolean();
        @Override public void at(String p) {
            if (dead.get()) throw new Crash(p);
            if (p.equals(point) && seen.incrementAndGet() == hit) { dead.set(true); throw new Crash(p); }
        }
    }

    static final class Rig {
        final File root, out;
        final DirStore store;
        final FakeRemux remux = new FakeRemux();
        final Events events = new Events();
        final JobSlots slots = new JobSlots(2, 4);
        final AtomicLong now = new AtomicLong(1_000_000);
        DashEngine engine;
        CrashHooks hooks = new CrashHooks();
        final SafeHttpClient http;
        final AtomicBoolean online = new AtomicBoolean(true);
        final de.evp.helfer.dl.Retry retry = new de.evp.helfer.dl.Retry(online::get, de.evp.helfer.dl.Retry.Clock.SYSTEM, 1000);
        Rig(String name, Server s) throws Exception {
            root = new File(base, name + "/jobs");
            out = new File(base, name + "/out");
            store = new DirStore(out);
            http = LocalTestClients.loopback(s.port);
            restart();
        }
        void restart() throws Exception {
            waitForWorkers();
            store.processStartCleanup();
            hooks = new CrashHooks();
            engine = new DashEngine(http, store, events, root, remux, slots, 1_000_000, hooks, now::get);
            engine.setRetry(retry);
        }
        File dir(String id) { return new File(root, id); }
    }

    static void waitForWorkers() throws InterruptedException {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) {
            boolean any = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().startsWith("evp-dash")) any = true;
            if (!any) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("DASH worker threads still alive");
    }

    static final byte[] PEER = "peer-session-0001".getBytes(StandardCharsets.US_ASCII);
    static final String BIND = "p1|3|7";
    static int idSeq;
    static String newId() { return String.format("%032x", ++idSeq + 0xdac000L); }

    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":(\"([^\"]*)\"|(-?[0-9.]+)|(true|false))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4)) : null;
    }

    static List<String> optionIds(String plan) {
        List<String> l = new ArrayList<>();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f]{24})\"").matcher(plan);
        while (m.find()) l.add(m.group(1));
        return l;
    }

    /** Fingerabdruck so, wie Kadri ihn aus dem gebundenen MPD berechnet (Basis: finale Adresse ohne Fragment). */
    static String fpOf(String mpd, String url) throws DashException {
        return DashFingerprint.hex(DashManifest.parse(mpd.getBytes(StandardCharsets.UTF_8), url));
    }

    static String mpd() throws IOException { return new String(Files.readAllBytes(new File(fx, "avc/manifest.mpd").toPath()), StandardCharsets.UTF_8); }

    static String await(Rig r, String id) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end) {
            String s = r.engine.status(id);
            String st = field(s, "state");
            if (!"running".equals(st)) return s;
            Thread.sleep(20);
        }
        return r.engine.status(id);
    }

    static String plan(Rig r, Server s, String mpdText) throws Exception {
        return r.engine.plan(PEER, BIND, s.mpdUrl(), fpOf(mpdText, s.mpdUrl()), new int[] {0, 2}, RequestContext.NONE);
    }

    static String journalText(Rig r, String id) throws IOException {
        StringBuilder sb = new StringBuilder();
        File[] fs = r.dir(id).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().equals("journal") || f.getName().equals("segments.log")) sb.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1));
        return sb.toString();
    }

    static boolean clean(String s, Server srv) {
        return !s.contains("SECRETTOKEN") && !s.contains("127.0.0.1") && !s.contains("/f/") && !s.contains("chunk-") && !s.contains("manifest") && !s.contains(":" + srv.port);
    }

    // ------------------------------------------------------------------ mp4-Manipulation für Negativfälle

    /** Kopf eines Containers bis zu seinen Kindern (FullBox stsd: + Version/Zähler; Probeneinträge: feste Felder). */
    static int childOffset(String type) { return type.equals("stsd") ? 16 : type.equals("avc1") ? 86 : type.equals("mp4a") ? 36 : 8; }

    /** Fügt in den ersten Container {@code path} (z. B. "moov" oder "moof/traf") eine Box als erstes Kind ein und passt die Größen an. */
    static byte[] insertBox(byte[] file, String path, String type, byte[] payload) { return insert(file, path, type, payload, true); }

    /** Wie insertBox, aber direkt nach dem 8-Byte-Kopf (vor Version/Zähler) – absichtlich falsche Struktur. */
    static byte[] insertRaw(byte[] file, String path, String type, byte[] payload) { return insert(file, path, type, payload, false); }

    static byte[] insert(byte[] file, String path, String type, byte[] payload, boolean asChild) {
        String[] p = path.split("/");
        List<int[]> chain = new ArrayList<>();
        int from = 0, to = file.length;
        for (String want : p) {
            int pos = from, found = -1;
            while (pos + 8 <= to) {
                int size = ByteBuffer.wrap(file, pos, 4).getInt();
                String t = new String(file, pos + 4, 4, StandardCharsets.US_ASCII);
                if (t.equals(want)) { found = pos; break; }
                pos += size;
            }
            if (found < 0) throw new IllegalStateException("no " + want);
            int size = ByteBuffer.wrap(file, found, 4).getInt();
            chain.add(new int[] {found, size});
            from = found + childOffset(want); to = found + size;
        }
        int at = chain.get(chain.size() - 1)[0] + (asChild ? childOffset(p[p.length - 1]) : 8);
        int add = 8 + payload.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(file, 0, at);
        ByteBuffer b = ByteBuffer.allocate(add);
        b.putInt(add).put(type.getBytes(StandardCharsets.US_ASCII)).put(payload);
        o.write(b.array(), 0, add);
        o.write(file, at, file.length - at);
        byte[] r = o.toByteArray();
        for (int[] c : chain) ByteBuffer.wrap(r, c[0], 4).putInt(c[1] + add);
        return r;
    }

    static byte[] replace4(byte[] file, String from, String to) {
        byte[] r = file.clone();
        byte[] f = from.getBytes(StandardCharsets.US_ASCII), t = to.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + 4 <= r.length; i++) {
            if (r[i] == f[0] && r[i + 1] == f[1] && r[i + 2] == f[2] && r[i + 3] == f[3]) { System.arraycopy(t, 0, r, i, 4); return r; }
        }
        throw new IllegalStateException("no " + from);
    }

    /** Setzt im esds den objectTypeIndication des DecoderConfigDescriptor (Tag 0x04, Länge, OTI). */
    static byte[] patchOti(byte[] file, int oti) {
        byte[] r = file.clone();
        int e = -1;
        for (int i = 0; i + 4 <= r.length; i++) if (r[i] == 'e' && r[i + 1] == 's' && r[i + 2] == 'd' && r[i + 3] == 's') { e = i + 8; break; }
        if (e < 0) throw new IllegalStateException("no esds");
        for (int i = e; i < r.length - 6; i++) {
            if (r[i] != 0x04) continue;
            int j = i + 1;
            while (j < i + 5 && (r[j] & 0x80) != 0) j++;
            if ((r[j + 1] & 0xff) == 0x40) { r[j + 1] = (byte) oti; return r; }
        }
        throw new IllegalStateException("no DecoderConfigDescriptor");
    }

    /** Ersetzt die 5 Bytes der AudioSpecificConfig der FFmpeg-Init (DecoderSpecificInfo 05 80 80 80 05). */
    static byte[] patchAsc(byte[] file, String hex) {
        hex = hex.replace(" ", "");
        byte[] r = file.clone();
        for (int i = 0; i + 10 <= r.length; i++) {
            if (r[i] == 0x05 && (r[i + 1] & 0xff) == 0x80 && (r[i + 2] & 0xff) == 0x80 && (r[i + 3] & 0xff) == 0x80 && r[i + 4] == 0x05) {
                for (int k = 0; k < 5; k++) r[i + 5 + k] = (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
                return r;
            }
        }
        throw new IllegalStateException("no DecoderSpecificInfo");
    }

    static int indexOf(byte[] b, String type) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + 4 <= b.length; i++) if (b[i] == t[0] && b[i + 1] == t[1] && b[i + 2] == t[2] && b[i + 3] == t[3]) return i;
        throw new IllegalStateException("no " + type);
    }

    static Fmp4Inspect.Info initInfo(byte[] b, String kind, String codecs) throws Exception {
        File f = File.createTempFile("init", ".m4s", base);
        Files.write(f.toPath(), b);
        try { return Fmp4Inspect.init(f, kind, codecs); } finally { f.delete(); }
    }

    static long segDuration(byte[] b, Fmp4Inspect.Info info) throws Exception {
        File f = File.createTempFile("seg", ".m4s", base);
        Files.write(f.toPath(), b);
        try { return Fmp4Inspect.duration(f, info); } finally { f.delete(); }
    }

    /** Wie segDuration, ein Ablehnungsgrund ergibt −1 (lässt die Prüfung scheitern statt den Lauf abzubrechen). */
    static long safeDuration(byte[] b, Fmp4Inspect.Info info) throws Exception {
        try { return segDuration(b, info); } catch (DashException e) { return -1; }
    }

    static String segDurationReason(byte[] b, Fmp4Inspect.Info info) throws Exception {
        try { return "ok " + segDuration(b, info); } catch (DashException e) { return e.reason; }
    }

    static byte[] read(String name) throws IOException { return Files.readAllBytes(new File(fx, "avc/" + name).toPath()); }

    static String inspectInit(byte[] b, String kind, String codecs) throws IOException {
        File f = File.createTempFile("init", ".m4s", base);
        Files.write(f.toPath(), b);
        try { Fmp4Inspect.init(f, kind, codecs); return "ok"; } catch (DashException e) { return e.reason; } finally { f.delete(); }
    }

    static String inspectSeg(byte[] b) throws IOException {
        File f = File.createTempFile("seg", ".m4s", base);
        Files.write(f.toPath(), b);
        try { Fmp4Inspect.segment(f); return "ok"; } catch (DashException e) { return e.reason; } finally { f.delete(); }
    }

    // ================================================================== Tests

    public static void main(String[] args) throws Exception {
        fx = new File(args[0]);
        base = new File(args[1]);
        base.mkdirs();
        inspect();
        commands();
        Server s = new Server();
        try {
            planning(s);
            binding(s);
            happy(s);
            negatives(s);
            durations(s);
            resume(s);
            crashes(s);
            cancel(s);
        } finally {
            s.srv.stop(0);
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void inspect() throws Exception {
        byte[] v0 = read("init-0.m4s"), v1 = read("init-1.m4s"), a = read("init-2.m4s"), seg = read("chunk-0-00002.m4s"), aseg = read("chunk-2-00001.m4s");
        check("Fmp4Inspect: echte Inits (avc1.4d400c, avc1.4d401e, mp4a.40.2) und Stücke werden angenommen",
            inspectInit(v0, "video", "avc1.4d400c").equals("ok") && inspectInit(v1, "video", "avc1.4d401e").equals("ok")
                && inspectInit(a, "audio", "mp4a.40.2").equals("ok") && inspectSeg(seg).equals("ok") && inspectSeg(aseg).equals("ok"),
            inspectInit(v0, "video", "avc1.4d400c") + "/" + inspectInit(a, "audio", "mp4a.40.2") + "/" + inspectSeg(seg));
        String[][] init = {
            {"Codec im MPD ≠ avcC (Profil/Level)", inspectInit(v0, "video", "avc1.640028"), "codec_mismatch"},
            {"avc3 im MPD, avc1 im Init", inspectInit(v0, "video", "avc3.4d400c"), "codec_mismatch"},
            {"Tonspur als Video gemeldet (hdlr soun)", inspectInit(a, "video", "avc1.4d400c"), "init_track"},
            {"Video-Init als Ton gemeldet", inspectInit(v0, "audio", "mp4a.40.2"), "init_track"},
            {"Probeneintrag encv statt avc1", inspectInit(replace4(v0, "avc1", "encv"), "video", "avc1.4d400c"), "encrypted"},
            {"Probeneintrag enca statt mp4a", inspectInit(replace4(a, "mp4a", "enca"), "audio", "mp4a.40.2"), "encrypted"},
            {"pssh in moov", inspectInit(insertBox(v0, "moov", "pssh", new byte[24]), "video", "avc1.4d400c"), "encrypted"},
            {"sinf im Probeneintrag avc1", inspectInit(insertBox(v0, "moov/trak/mdia/minf/stbl/stsd/avc1", "sinf", new byte[8]), "video", "avc1.4d400c"), "encrypted"},
            {"sinf im Probeneintrag mp4a", inspectInit(insertBox(a, "moov/trak/mdia/minf/stbl/stsd/mp4a", "sinf", new byte[8]), "audio", "mp4a.40.2"), "encrypted"},
            {"Box vor Version/Zähler im stsd (Zähler ≠ 1)", inspectInit(insertRaw(v0, "moov/trak/mdia/minf/stbl/stsd", "free", new byte[8]), "video", "avc1.4d400c"), "init_track"},
            {"tenc in trak", inspectInit(insertBox(v0, "moov/trak", "tenc", new byte[24]), "video", "avc1.4d400c"), "encrypted"},
            {"unbekannte Box oben", inspectInit(concat(v0, box("abcd", new byte[4])), "video", "avc1.4d400c"), "init_box"},
            {"zweiter trak", inspectInit(concatMoovTrak(v0), "video", "avc1.4d400c"), "mp4_structure"},
            {"abgeschnittene Datei", inspectInit(Arrays.copyOf(v0, v0.length - 10), "video", "avc1.4d400c"), "mp4_structure"},
            {"zu groß (> 1 MiB)", inspectInit(concat(v0, box("free", new byte[1 << 20])), "video", "avc1.4d400c"), "init_size"},
            {"esds: objectTypeIndication 0x6B (MP3) statt 0x40 (AAC)", inspectInit(patchOti(a, 0x6B), "audio", "mp4a.40.2"), "codec_mismatch"},
            // Fix 1 (P1 AAC): echte AAC-LC-Init (FFmpeg, ASC 11 90 56 e5 00) gegen HE-AAC/HE-AACv2 im MPD
            {"AAC-LC-Init, MPD mp4a.40.5 (HE-AAC)", inspectInit(a, "audio", "mp4a.40.5"), "codec_mismatch"},
            {"AAC-LC-Init, MPD mp4a.40.29 (HE-AACv2)", inspectInit(a, "audio", "mp4a.40.29"), "codec_mismatch"},
            {"AAC-LC-Init, MPD mp4a.40.3 (kein LC)", inspectInit(a, "audio", "mp4a.40.3"), "codec_mismatch"},
            {"ASC audioObjectType 5 (explizites SBR), MPD mp4a.40.2", inspectInit(patchAsc(a, "2990 56e5 00"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC audioObjectType 29 (explizites PS), MPD mp4a.40.2", inspectInit(patchAsc(a, "e990 56e5 00"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC rückwärtskompatibles SBR (0x2b7, AOT 5, sbrPresentFlag 1)", inspectInit(patchAsc(a, "1190 56e5 80"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC Synchronisationserweiterung mit anderem Typ (22)", inspectInit(patchAsc(a, "1190 56f6 00"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC Kanalkonfiguration 0 (PCE, nicht geprüft)", inspectInit(patchAsc(a, "1180 56e5 00"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC Füllbits ≠ 0", inspectInit(patchAsc(a, "1190 56e5 01"), "audio", "mp4a.40.2"), "codec_mismatch"},
            {"ASC AAC-LC ohne Erweiterung (nur Füllbits 0) wird angenommen", inspectInit(patchAsc(a, "1190 0000 00"), "audio", "mp4a.40.2"), "ok"},
            {"ohne mvex (nicht fragmentiert)", inspectInit(replace4(v0, "mvex", "free"), "video", "avc1.4d400c"), "mp4_structure"},
        };
        for (String[] t : init) check("Fmp4Inspect Init: " + t[0] + " → " + t[2], t[1].equals(t[2]), t[1]);
        // Fix 1: Zeitangaben (mdhd, trex) und Probendauern je Stück (tfhd-Standard bzw. Dauer je Probe in trun)
        Fmp4Inspect.Info vi = initInfo(v1, "video", "avc1.4d401e"), ai = initInfo(a, "audio", "mp4a.40.2");
        long vTicks = 0, aTicks = 0;
        for (int i = 1; i <= 5; i++) vTicks += safeDuration(read("chunk-1-0000" + i + ".m4s"), vi);
        for (int i = 1; i <= 6; i++) aTicks += safeDuration(read("chunk-2-0000" + i + ".m4s"), ai);
        check("Fmp4Inspect Dauer: Video 12800/s, 5 × 51200 (tfhd-Standard) = 20 s; Ton 48000/s, Summe inkl. letztem Stück (Dauer je Probe in trun) ≈ 20 s",
            vi.timescale == 12800 && safeDuration(read("chunk-1-00001.m4s"), vi) == 51200 && vTicks == 256000 && ai.timescale == 48000 && Math.abs(aTicks - 960000) <= 1024,
            vi.timescale + " " + vTicks + " " + ai.timescale + " " + aTicks);
        byte[] noDefault = read("chunk-1-00001.m4s").clone();
        int tf = indexOf(noDefault, "tfhd");
        noDefault[tf + 7] = (byte) (noDefault[tf + 7] & ~0x08);   // tfhd ohne default-sample-duration
        byte[] trexInit = v1.clone();
        int tx = indexOf(trexInit, "trex");
        ByteBuffer.wrap(trexInit, tx + 16, 4).putInt(512);          // trex default_sample_duration = 512
        Fmp4Inspect.Info ti = initInfo(trexInit, "video", "avc1.4d401e");
        check("Fmp4Inspect Dauer: ohne tfhd-/trun-Dauer gilt der trex-Standard aus dem Init (100 × 512 = 51200)",
            ti.defaultDuration == 512 && safeDuration(noDefault, ti) == 51200, ti.defaultDuration + " " + safeDuration(noDefault, ti));
        check("Fmp4Inspect Dauer: weder trun- noch tfhd- noch trex-Dauer → segment_timing",
            "segment_timing".equals(segDurationReason(noDefault, new Fmp4Inspect.Info(12800, 0))), segDurationReason(noDefault, new Fmp4Inspect.Info(12800, 0)));
        String[][] segs = {
            {"senc in traf", inspectSeg(insertBox(seg, "moof/traf", "senc", new byte[8])), "encrypted"},
            {"saiz in traf", inspectSeg(insertBox(seg, "moof/traf", "saiz", new byte[9])), "encrypted"},
            {"pssh in moof", inspectSeg(insertBox(seg, "moof", "pssh", new byte[24])), "encrypted"},
            {"sgpd seig (Schlüsselwechsel)", inspectSeg(insertBox(seg, "moof/traf", "sgpd", seigPayload())), "encrypted"},
            {"PIFF-uuid (Probenverschlüsselung)", inspectSeg(insertBox(seg, "moof/traf", "uuid", piff())), "encrypted"},
            {"unbekannte Box in traf", inspectSeg(insertBox(seg, "moof/traf", "zzzz", new byte[4])), "segment_box"},
            {"unbekannte Box oben", inspectSeg(concat(seg, box("qqqq", new byte[4]))), "segment_box"},
            {"ohne moof (nur mdat)", inspectSeg(box("mdat", new byte[16])), "segment_box"},
            {"Größenfeld zeigt über das Dateiende", inspectSeg(Arrays.copyOf(seg, seg.length - 3)), "mp4_structure"},
        };
        for (String[] t : segs) check("Fmp4Inspect Stück: " + t[0] + " → " + t[2], t[1].equals(t[2]), t[1]);
    }

    static byte[] box(String type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.putInt(8 + payload.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(payload);
        return b.array();
    }

    static byte[] concat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    static byte[] seigPayload() { ByteBuffer b = ByteBuffer.allocate(12); b.putInt(0x01000000).put("seig".getBytes(StandardCharsets.US_ASCII)).putInt(0); return b.array(); }

    static byte[] piff() {
        byte[] u = new byte[24];
        String hex = "a2394f525a9b4f14a2446c427c648df4";
        for (int i = 0; i < 16; i++) u[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return u;
    }

    /** Init mit zwei trak-Boxen (der vorhandene trak doppelt). */
    static byte[] concatMoovTrak(byte[] init) {
        int pos = 0;
        while (pos + 8 <= init.length) {
            int size = ByteBuffer.wrap(init, pos, 4).getInt();
            if (new String(init, pos + 4, 4, StandardCharsets.US_ASCII).equals("moov")) {
                int p = pos + 8;
                while (p + 8 <= pos + size) {
                    int s = ByteBuffer.wrap(init, p, 4).getInt();
                    if (new String(init, p + 4, 4, StandardCharsets.US_ASCII).equals("trak")) {
                        byte[] trak = Arrays.copyOfRange(init, p, p + s);
                        byte[] payload = Arrays.copyOfRange(trak, 8, trak.length);
                        return insertBox(init, "moov", "trak", payload);
                    }
                    p += s;
                }
            }
            pos += size;
        }
        throw new IllegalStateException("no trak");
    }

    // ------------------------------------------------------------------ Kanalbefehle

    static final class FakeDash implements ControlCommands.DashControl {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile String known = "";
        @Override public String plan(byte[] peer, String bind, String url, String fp, int[] played, RequestContext c) {
            calls.add("plan " + bind + " " + fp + " " + Arrays.toString(played) + " " + (c.referer != null));
            return "{\"ok\":true,\"planId\":\"p\"}";
        }
        @Override public String start(byte[] peer, String bind, String id, String planId, String option, String name) { calls.add("start " + id); return "{\"ok\":true,\"id\":\"" + id + "\",\"kind\":\"dash\"}"; }
        @Override public boolean knows(String id) { return id.equals(known); }
        @Override public String status(String id) { calls.add("status " + id); return "{\"ok\":true,\"id\":\"" + id + "\",\"kind\":\"dash\",\"state\":\"running\"}"; }
        @Override public String cancel(String id) { calls.add("cancel " + id); return "{\"ok\":true,\"id\":\"" + id + "\",\"kind\":\"dash\",\"state\":\"cancelled\"}"; }
    }

    static final ControlCommands.Device DEV = new ControlCommands.Device() {
        public void bright(float l) { } public void vol(float l) { } public void beat() { } public void end() { }
        public String status() { return "{\"ok\":true}"; }
    };

    static final class FakeDl implements ControlCommands.DownloadControl {
        public String start(String id, String url, String name) { return "{\"ok\":true,\"id\":\"" + id + "\",\"kind\":\"mp4\"}"; }
        public String status(String id) { return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"unknown\"}"; }
        public String cancel(String id) { return "{}"; }
    }

    static String cmd(String json, ControlCommands.DashControl dash) {
        // eine Ausnahme ist ein Fehler des Kanals (nie eine Antwort) und lässt jede Prüfung scheitern
        try { return ControlCommands.handle(json.getBytes(StandardCharsets.UTF_8), DEV, new FakeDl(), null, dash, PEER); }
        catch (RuntimeException e) { return "EXCEPTION " + e; }
    }

    static void commands() {
        FakeDash d = new FakeDash();
        String fp = repeat('a', 64), id = repeat('1', 32), plan = repeat('2', 32), opt = repeat('3', 24);
        String bind = "\"bind\":{\"player\":\"p1\",\"gen\":3,\"rev\":7}";
        String ok = cmd("{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://cdn.example/m.mpd?t=1\",\"fp\":\"" + fp + "\"," + bind + ",\"played\":\"0,3\",\"ref\":\"https://site.example/\"}", d);
        check("Kanal: dashplan mit fp, played und ref wird angenommen und weitergereicht", ok.contains("\"planId\"") && d.calls.get(0).equals("plan p1|3|7 " + fp + " [0, 3] true"), d.calls);
        String[] bad = {
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\"," + bind + "}",                                       // ohne fp
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + repeat('a', 63) + "\"," + bind + "}",       // fp zu kurz
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + repeat('A', 64) + "\"," + bind + "}",       // Großbuchstaben
            "{\"op\":\"dashplan\",\"v\":2,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + "}",                    // falsche Version
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"}",                                 // ohne bind
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"played\":\"0,0\"}",  // doppelt
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"played\":\"01\"}",   // führende Null
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"played\":\"1,2,3,4,5,6,7,8,9\"}",
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"played\":\"4096\"}",
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"cookie\":\"a\"}",    // fremdes Feld
            "{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + ",\"planId\":\"" + plan + "\"}",
            "{\"op\":\"hlsplan\",\"v\":1,\"url\":\"https://x/m.m3u8\",\"fp\":\"" + fp + "\"," + bind + "}",                    // fp nur bei dashplan
            "{\"op\":\"dashstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\"," + bind + "}",                    // ohne option
            "{\"op\":\"dashstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\",\"option\":\"" + opt + "\",\"fp\":\"" + fp + "\"," + bind + "}",
            "{\"op\":\"dashstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\",\"option\":\"" + opt + "\"," + bind + ",\"ref\":\"https://site.example/\"}",
        };
        int rejected = 0;
        for (String b : bad) if (cmd(b, d).equals(ControlCommands.REJECTED)) rejected++; else System.out.println("     angenommen: " + b);
        check("Kanal: " + bad.length + " ungültige DASH-Anfragen abgelehnt (fp-Form, Version, Bindung, played-Form, fremde Felder, Kontext nur bei dashplan)", rejected == bad.length, rejected);
        String st = cmd("{\"op\":\"dashstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\",\"option\":\"" + opt + "\"," + bind + ",\"name\":\"Film\"}", d);
        check("Kanal: dashstart angenommen", st.contains("\"kind\":\"dash\"") && d.calls.contains("start " + id), st);
        check("Kanal: ohne DASH-Anbindung werden dashplan/dashstart abgelehnt (wie ein alter Helfer)",
            cmd("{\"op\":\"dashplan\",\"v\":1,\"url\":\"https://x/m.mpd\",\"fp\":\"" + fp + "\"," + bind + "}", null).equals(ControlCommands.REJECTED), "");
        d.known = id;
        String s1 = cmd("{\"op\":\"dlstatus\",\"id\":\"" + id + "\"}", d), c1 = cmd("{\"op\":\"dlcancel\",\"id\":\"" + id + "\"}", d);
        check("Kanal: dlstatus/dlcancel einer DASH-Kennung gehen an DASH", s1.contains("\"kind\":\"dash\"") && c1.contains("cancelled") && d.calls.contains("cancel " + id), d.calls);
        ControlCommands.HlsControl hlsKnows = new ControlCommands.HlsControl() {
            public String plan(byte[] p, String b, String u) { return "{}"; }
            public String start(byte[] p, String b, String i, String pl, String o, String n) { return "{\"hls\":1}"; }
            public boolean knows(String i) { return i.equals(id); }
            public String status(String i) { return "{\"kind\":\"hls\"}"; }
            public String cancel(String i) { return "{}"; }
        };
        FakeDash fresh = new FakeDash();
        String inUse = ControlCommands.handle(("{\"op\":\"dashstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\",\"option\":\"" + opt + "\"," + bind + "}").getBytes(StandardCharsets.UTF_8),
            DEV, new FakeDl(), hlsKnows, fresh, PEER);
        String inUse2 = ControlCommands.handle(("{\"op\":\"hlsstart\",\"v\":1,\"id\":\"" + id + "\",\"planId\":\"" + plan + "\",\"option\":\"" + opt + "\"," + bind + "}").getBytes(StandardCharsets.UTF_8),
            DEV, new FakeDl(), new ControlCommands.HlsControl() {
                public String plan(byte[] p, String b, String u) { return "{}"; }
                public String start(byte[] p, String b, String i, String pl, String o, String n) { return "{\"hls\":1}"; }
                public boolean knows(String i) { return false; }
                public String status(String i) { return "{}"; }
                public String cancel(String i) { return "{}"; }
            }, d, PEER);
        check("Kanal: eine Kennung gehört genau einer Art (HLS-Kennung startet kein DASH und umgekehrt) → id_in_use",
            inUse.contains("id_in_use") && fresh.calls.isEmpty() && inUse2.contains("id_in_use"), inUse + " " + inUse2);
        String status = ControlCommands.statusJson("1.0", true, true, 3, 15, 0.5f, Boolean.FALSE, true);
        check("Status: Fähigkeit dashdl {v:1, fmp4-avc-aac, kadri-dash-fp/1, ctx}; ohne DASH unverändert",
            status.contains("\"dashdl\":{\"v\":1,\"profiles\":[\"fmp4-avc-aac\"],\"fp\":\"kadri-dash-fp/1\",\"ctx\":1}") && status.contains("\"hls\":{")
                && ControlCommands.statusJson("1.0", true, true, 3, 15, 0.5f, Boolean.FALSE, false).equals(ControlCommands.statusJson("1.0", true, true, 3, 15, 0.5f, Boolean.FALSE)), status);
    }

    static String repeat(char c, int n) { char[] a = new char[n]; Arrays.fill(a, c); return new String(a); }

    // ------------------------------------------------------------------ Planung

    static void planning(Server s) throws Exception {
        s.reset();
        Rig r = new Rig("plan", s);
        String m = mpd();
        String p = plan(r, s, m);
        List<String> ids = optionIds(p);
        check("dashplan: Fingerabdruck stimmt → Plan mit 2 Optionen (320×180, 640×360, je + AAC), fp kadri-dash-fp/1, Dauer 20 s",
            "true".equals(field(p, "ok")) && ids.size() == 2 && p.contains("\"fp\":\"kadri-dash-fp/1\"") && p.contains("\"duration\":20.000")
                && p.contains("\"height\":180") && p.contains("\"height\":360") && p.contains("\"video\":\"avc1.4d400c\"") && p.contains("\"audio\":\"mp4a.40.2\""), p);
        check("dashplan: played-Hinweis markiert nur die Option mit beiden gespielten Darstellungen", p.indexOf("\"played\":true") > 0 && p.indexOf("\"played\":true") == p.lastIndexOf("\"played\":true"), p);
        check("dashplan: Antwort ohne Adresse, Host, Token oder Dateinamen", clean(p, s), p);
        String bad = r.engine.plan(PEER, BIND, s.mpdUrl(), repeat('0', 64), null, RequestContext.NONE);
        check("dashplan: Fingerabdruck weicht ab (anderes MPD als in Kadri gebunden) → manifest_mismatch", "manifest_mismatch".equals(field(bad, "reason")) && "fingerprint".equals(field(bad, "phase")), bad);
        check("dashplan: ohne gültigen Fingerabdruck → fingerprint_missing", "fingerprint_missing".equals(field(r.engine.plan(PEER, BIND, s.mpdUrl(), null, null, RequestContext.NONE), "reason")), "");
        // Fingerabdruck über eine andere Dokumentadresse (Kadri hat das MPD unter anderer Basis gebunden) → abweichend
        String other = r.engine.plan(PEER, BIND, s.mpdUrl(), fpOf(m, "https://cdn.example/elsewhere/manifest.mpd"), null, RequestContext.NONE);
        check("dashplan: gleicher Text, andere Basisadresse (relative Stücke anderswo) → manifest_mismatch", "manifest_mismatch".equals(field(other, "reason")), other);
        String[][] prof = {
            {"ContentProtection", m.replace("<AdaptationSet id=\"0\"", "<AdaptationSet id=\"0\"").replace("<Representation id=\"0\"", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/><Representation id=\"0\""), "dash_protected"},
            {"type=dynamic", m.replace("type=\"static\"", "type=\"dynamic\""), "dash_live"},
            {"nur Video (kein Ton-Set)", m.replaceAll("(?s)<AdaptationSet id=\"1\".*?</AdaptationSet>", ""), "dash_no_audio"},
            {"HEVC statt H.264", m.replace("avc1.4d400c", "hvc1.1.6.L93.B0").replace("avc1.4d401e", "hvc1.1.6.L93.B0"), "dash_codec"},
        };
        for (String[] t : prof) {
            s.mpdText = t[1];
            String res = plan(r, s, t[1]);
            check("dashplan: " + t[0] + " → " + t[2], t[2].equals(field(res, "reason")), res);
        }
        s.mpdText = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>";
        String dt = r.engine.plan(PEER, BIND, s.mpdUrl(), repeat('0', 64), null, RequestContext.NONE);
        check("dashplan: DOCTYPE im MPD → mpd_doctype (Lesen schlägt vor dem Fingerabdruck fehl)", "mpd_doctype".equals(field(dt, "reason")) && "mpd_parse".equals(field(dt, "phase")), dt);
        StringBuilder huge = new StringBuilder(m.substring(0, m.indexOf("<Period")));
        while (huge.length() < 4_100_000) huge.append("<!-- ").append(repeat('x', 4000)).append(" -->");
        s.mpdText = huge.append(m.substring(m.indexOf("<Period"))).toString();
        check("dashplan: MPD über 4 MB → manifest_size", "manifest_size".equals(field(r.engine.plan(PEER, BIND, s.mpdUrl(), repeat('0', 64), null, RequestContext.NONE), "reason")), "");
        s.mpdText = null;
        s.gzipMpd = "1";
        check("dashplan: Content-Encoding gzip → encoding", "encoding".equals(field(r.engine.plan(PEER, BIND, s.mpdUrl(), fpOf(m, s.mpdUrl()), null, RequestContext.NONE), "reason")), "");
        s.gzipMpd = null;
        s.failPath = "manifest.mpd"; s.failStatus = 404;
        check("dashplan: MPD 404 → http_404", "http_404".equals(field(r.engine.plan(PEER, BIND, s.mpdUrl(), fpOf(m, s.mpdUrl()), null, RequestContext.NONE), "reason")), "");
        s.failPath = null;
        // 15 000 Stücke je Spur: einzeln im S3-Profil erlaubt, zusammen über der Journalgrenze → keine Option
        String many = m.replace("<S t=\"0\" d=\"51200\" r=\"4\" />", "<S t=\"0\" d=\"51200\" r=\"14999\" />").replace("PT20.0S", "PT60000S")
            .replaceAll("(?s)<SegmentTimeline>\\s*<S t=\"0\" d=\"188416\" />.*?</SegmentTimeline>", "<SegmentTimeline><S t=\"0\" d=\"192000\" r=\"14999\" /></SegmentTimeline>");
        s.mpdText = many;
        String lim = plan(r, s, many);
        check("dashplan: Video + Ton über der Journalgrenze (2 × 15 001 Ressourcen) → segments_limit", "segments_limit".equals(field(lim, "reason")), lim);
        s.mpdText = null;
        String ctxPlan = r.engine.plan(PEER, BIND, s.mpdUrl(), fpOf(m, s.mpdUrl()), null, RequestContext.of("http://127.0.0.1:" + s.port + "/", null));
        check("dashplan: Referer der Seitenherkunft nur mit Anfragekontext; nie Cookie/Authorization/Origin", "true".equals(field(ctxPlan, "ok")) && s.referers.size() == 1 && s.leaks.isEmpty(), s.referers + " " + s.leaks);
    }

    // ------------------------------------------------------------------ Bindung des Plans

    static void binding(Server s) throws Exception {
        s.reset();
        Rig r = new Rig("bind", s);
        String m = mpd();
        String p = plan(r, s, m), planId = field(p, "planId"), opt = optionIds(p).get(0);
        check("dashstart: andere Kanal-Sitzung → plan_stale", "plan_stale".equals(field(r.engine.start("other-peer".getBytes(StandardCharsets.US_ASCII), BIND, newId(), planId, opt, "x"), "reason")), "");
        check("dashstart: andere Kadri-Bindung → plan_stale", "plan_stale".equals(field(r.engine.start(PEER, "p1|3|8", newId(), planId, opt, "x"), "reason")), "");
        check("dashstart: unbekannte Option → option_unknown", "option_unknown".equals(field(r.engine.start(PEER, BIND, newId(), planId, repeat('f', 24), "x"), "reason")), "");
        r.now.addAndGet(DashEngine.PLAN_TTL_MS + 1);
        check("dashstart: Plan abgelaufen (TTL) → plan_stale", "plan_stale".equals(field(r.engine.start(PEER, BIND, newId(), planId, opt, "x"), "reason")), "");
        String p2 = plan(r, s, m), id = newId();
        String st = r.engine.start(PEER, BIND, id, field(p2, "planId"), optionIds(p2).get(0), "Film");
        await(r, id);
        check("dashstart: ein Plan startet höchstens einen Auftrag", "plan_stale".equals(field(r.engine.start(PEER, BIND, newId(), field(p2, "planId"), optionIds(p2).get(0), "x"), "reason")), st);
        for (int i = 0; i < DashEngine.MAX_PLANS_PER_PEER + 1; i++) plan(r, s, m);
        String last = plan(r, s, m);
        check("dashplan: höchstens " + DashEngine.MAX_PLANS_PER_PEER + " Pläne je Sitzung (älteste fallen heraus)", "true".equals(field(last, "ok")), last);
    }

    // ------------------------------------------------------------------ Ablauf

    static void happy(Server s) throws Exception {
        s.reset();
        Rig r = new Rig("happy", s);
        String m = mpd();
        String p = plan(r, s, m), id = newId();
        String opt = optionIds(p).get(1);   // 640×360 + AAC
        String st = r.engine.start(PEER, BIND, id, field(p, "planId"), opt, "Mein Film");
        String fin = await(r, id);
        check("Ablauf: dashstart → done (Init + 5 Video- und 6 Tonstücke, ein Remux)", "done".equals(field(fin, "state")) && "13".equals(field(fin, "of")) && r.remux.runs.get() == 1
            && r.remux.last.video.size() == 6 && r.remux.last.audio.size() == 7 && r.remux.last.fmp4, fin + " " + st);
        File[] outs = r.out.listFiles((d, n) -> !n.startsWith("."));
        byte[] expect = concat(concat(concat(read("init-1.m4s"), cat("chunk-1-0000", 1, 5)), read("init-2.m4s")), cat("chunk-2-0000", 1, 6));
        check("Ablauf: veröffentlichte Datei = Init + Stücke in Folge (Nachbildung), total = Dateigröße, genau ein Commit",
            outs != null && outs.length == 1 && Arrays.equals(Files.readAllBytes(outs[0].toPath()), expect) && String.valueOf(expect.length).equals(field(fin, "total"))
                && r.store.committed.get() == 1, outs == null ? "-" : Arrays.toString(outs));
        check("Ablauf: das gewählte (größere) Video wurde geladen, das andere nicht", s.full("init-1.m4s") == 1 && s.full("init-0.m4s") == 0 && s.full("chunk-0-00001.m4s") == 0, s.fullGets);
        check("Ablauf: Auftragsdaten nach done gelöscht (nur Journal bleibt), Journal ohne Adresse/Token", r.dir(id).list().length == 1 && clean(journalText(r, id), s), Arrays.toString(r.dir(id).list()));
        check("Ablauf: Status und Rückmeldungen ohne Adresse/Token; kind=dash", clean(fin, s) && "dash".equals(field(fin, "kind")) && clean(String.join("\n", r.events.all), s), r.events.all);
        check("Ablauf: keine Cookies/Authorization/Origin, kein Referer ohne Kontext", s.leaks.isEmpty() && s.referers.isEmpty(), s.leaks + " " + s.referers);
        check("Ablauf: dlstatus einer erledigten Kennung bleibt done", "done".equals(field(r.engine.status(id), "state")), "");
        check("Ablauf (Fix 1): Ausgabeprüfung gegen die tatsächliche Stückdauer (Video 20 s), Toleranz genau 1 s statt der Stückdauer (4 s)",
            Math.abs(r.remux.lastV - 20.0) < 1e-9 && Math.abs(r.remux.lastA - 20.0) < 0.1 && r.remux.lastTol == 1.0,
            r.remux.lastV + " " + r.remux.lastA + " " + r.remux.lastTol);
    }

    static byte[] cat(String prefix, int from, int to) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = from; i <= to; i++) o.write(read(prefix + i + ".m4s"));
        return o.toByteArray();
    }

    static String run(Rig r, Server s, String mpdText, int opt) throws Exception {
        String p = plan(r, s, mpdText), id = newId();
        r.engine.start(PEER, BIND, id, field(p, "planId"), optionIds(p).get(opt), "x");
        return id;
    }

    /** Fix 1 (P1 Dauer): formal gültige MPDs, deren Dauern nicht zu den echten Fragmenten passen, und legitime Abweichungen. */
    static void durations(Server s) throws Exception {
        String m = mpd();
        s.reset();
        Rig r = new Rig("dur", s);
        // Gegenbeispiel aus der Prüfung: letztes Stück im MPD 600 s (S3a erlaubt bis 3600 s), Periode 616 s; die echten
        // Fragmente sind 20 s lang. Vorher: Toleranz = größte Stückdauer (600 s) → |20 − 616| = 596 < 600 → veröffentlicht.
        String inflated = m.replace("mediaPresentationDuration=\"PT20.0S\"", "mediaPresentationDuration=\"PT616.0S\"")
            .replace("<S t=\"0\" d=\"51200\" r=\"4\" />", "<S t=\"0\" d=\"51200\" r=\"3\" /><S d=\"7680000\" />")
            .replace("<S d=\"2560\" />", "<S d=\"" + (616L * 48000 - 957440) + "\" />");
        s.mpdText = inflated;
        String p = plan(r, s, inflated);
        check("Dauer: MPD mit 600-s-Schlussstück (616 s) ist formal gültig (dashplan ok, 2 Optionen)", p.contains("\"ok\":true") && optionIds(p).size() == 2, p);
        int runs = r.remux.runs.get();
        String id = run(r, s, inflated, 1), st = await(r, id);
        check("Dauer: echte Fragmente (20 s) viel kürzer als das MPD (616 s) → failed media_duration vor dem Zusammenbau, nichts veröffentlicht",
            "failed".equals(field(st, "state")) && "media_duration".equals(field(st, "reason")) && r.remux.runs.get() == runs && r.store.committed.get() == 0
                && r.dir(id).list().length == 1, st + " " + r.engine.diagnostics(id));
        check("Dauer: Status und Diagnose ohne Adresse/Token", clean(st, s) && clean(r.engine.diagnostics(id), s), st);
        s.reset();
        String doubled = m.replace("PT20.0S", "PT40.0S").replace("d=\"51200\"", "d=\"102400\"").replace("d=\"188416\"", "d=\"376832\"")
            .replace("d=\"192512\"", "d=\"385024\"").replace("d=\"191488\"", "d=\"382976\"").replace("d=\"2560\"", "d=\"5120\"");
        s.mpdText = doubled;
        id = run(r, s, doubled, 1); st = await(r, id);
        check("Dauer: alle Stücke im MPD doppelt so lang (40 s) wie die Fragmente → failed media_duration",
            "failed".equals(field(st, "state")) && "media_duration".equals(field(st, "reason")) && r.store.committed.get() == 0, st);
        s.reset();
        String halved = m.replace("PT20.0S", "PT10.0S").replace("d=\"51200\"", "d=\"25600\"").replace("d=\"188416\"", "d=\"94208\"")
            .replace("d=\"192512\"", "d=\"96256\"").replace("d=\"191488\"", "d=\"95744\"").replace("d=\"2560\"", "d=\"1280\"");
        s.mpdText = halved;
        id = run(r, s, halved, 1); st = await(r, id);
        check("Dauer: Fragmente doppelt so lang wie das MPD (20 s statt 10 s) → failed media_duration (obere Grenze)",
            "failed".equals(field(st, "state")) && "media_duration".equals(field(st, "reason")) && r.store.committed.get() == 0, st);
        s.reset();
        // Lücke nur im Video: ein 4-s-Videostück durch das echte 2-s-Schlussstück der $Number$-Quelle (gleiches Profil)
        s.override.put("chunk-1-00003.m4s", Files.readAllBytes(new File(fx, "num/chunk-0-00005.m4s").toPath()));
        id = run(r, s, m, 1); st = await(r, id);
        check("Dauer: Videostück durch ein echtes 2-s-Stück ersetzt (2 s Video fehlen) → failed media_duration",
            "failed".equals(field(st, "state")) && "media_duration".equals(field(st, "reason")) && r.store.committed.get() == 0, st);
        s.reset();
        // Lücke nur im Ton: ein echtes 4-s-Tonstück durch das echte Schlussstück (≈ 0,06 s) ersetzt – gültiges fMP4,
        // gültiges MPD, aber ≈ 4 s Ton fehlen
        s.override.put("chunk-2-00002.m4s", read("chunk-2-00006.m4s"));
        id = run(r, s, m, 1); st = await(r, id);
        check("Dauer: Tonstück durch ein echtes, viel kürzeres Stück ersetzt (≈ 4 s Ton fehlen) → failed media_duration",
            "failed".equals(field(st, "state")) && "media_duration".equals(field(st, "reason")) && r.store.committed.get() == 0, st);
        s.reset();
        // Legitim: die Periode (18,5 s) schneidet das letzte Stück an; die Fragmente enthalten 1,5 s hinter dem Periodenende
        String clipped = m.replace("PT20.0S", "PT18.5S");
        s.mpdText = clipped;
        id = run(r, s, clipped, 1); st = await(r, id);
        check("Dauer: Periode 18,5 s schneidet das letzte Stück an (Fragmente 20 s) → done; Spielraum genau die 1,5 s hinter der Periode",
            "done".equals(field(st, "state")) && Math.abs(r.remux.lastTol - 2.5) < 1e-6 && Math.abs(r.remux.lastV - 20.0) < 1e-9, st + " tol=" + r.remux.lastTol);
        s.reset();
        // Nur HE-AAC im MPD → keine Option (fail-closed), eigener Grund
        String heaac = m.replace("codecs=\"mp4a.40.2\"", "codecs=\"mp4a.40.5\"");
        s.mpdText = heaac;
        p = plan(r, s, heaac);
        check("AAC: MPD nur mit HE-AAC (mp4a.40.5) → dashplan ok:false audio_codec (Phase options)", "audio_codec".equals(field(p, "reason")) && "options".equals(field(p, "phase")), p);
        s.mpdText = m.replace("codecs=\"mp4a.40.2\"", "codecs=\"mp4a.40.29\"");
        p = plan(r, s, s.mpdText);
        s.reset();
        check("AAC: MPD nur mit HE-AACv2 (mp4a.40.29) → dashplan ok:false audio_codec", "audio_codec".equals(field(p, "reason")), p);
    }

    static void negatives(Server s) throws Exception {
        String m = mpd();
        s.reset();
        Rig r = new Rig("neg", s);
        s.override.put("init-1.m4s", replace4(read("init-1.m4s"), "avc1", "encv"));
        String id = run(r, s, m, 1), st = await(r, id);
        check("Verschlüsseltes Init (encv): failed encrypted, kein Stück geladen, nichts veröffentlicht, Daten gelöscht",
            "failed".equals(field(st, "state")) && "encrypted".equals(field(st, "reason")) && s.fullChunks() == 0 && r.store.committed.get() == 0 && r.dir(id).list().length == 1, st + " " + s.fullGets);
        s.reset();
        s.override.put("chunk-2-00003.m4s", insertBox(read("chunk-2-00003.m4s"), "moof/traf", "senc", new byte[8]));
        id = run(r, s, m, 1); st = await(r, id);
        check("Verschlüsseltes Stück (senc in traf): failed encrypted, nichts veröffentlicht, Daten gelöscht",
            "failed".equals(field(st, "state")) && "encrypted".equals(field(st, "reason")) && r.store.committed.get() == 0 && r.remux.runs.get() == 0 && r.dir(id).list().length == 1, st);
        s.reset();
        String mismatch = m.replace("avc1.4d401e", "avc1.640028");
        s.mpdText = mismatch;
        id = run(r, s, mismatch, 1); st = await(r, id);
        check("Codec im MPD ≠ Init (avcC): failed codec_mismatch vor dem ersten Stück", "failed".equals(field(st, "state")) && "codec_mismatch".equals(field(st, "reason")) && s.fullChunks() == 0, st);
        s.reset();
        String p = plan(r, s, m);
        s.mpdText = m.replace("bandwidth=\"322838\"", "bandwidth=\"322839\"");
        id = newId();
        r.engine.start(PEER, BIND, id, field(p, "planId"), optionIds(p).get(1), "x");
        st = await(r, id);
        check("MPD zwischen dashplan und dashstart geändert (Fingerabdruck): failed plan_changed, nichts geladen", "failed".equals(field(st, "state")) && "plan_changed".equals(field(st, "reason")) && s.fullChunks() == 0, st);
        s.reset();
        s.redirectPrivate = true;
        id = run(r, s, m, 0); st = await(r, id);
        check("Stück leitet auf private Adresse um → failed blocked_*, nichts veröffentlicht", "failed".equals(field(st, "state")) && String.valueOf(field(st, "reason")).startsWith("blocked_") && r.store.committed.get() == 0, st);
        s.reset();
        r.remux.failWith = "encrypted-sample";
        id = run(r, s, m, 0); st = await(r, id);
        check("Remuxer meldet verschlüsselte Samples → failed encrypted_sample (Sicherheitsregel des Remuxers unverändert wirksam)", "failed".equals(field(st, "state")) && "encrypted_sample".equals(field(st, "reason")), st);
        r.remux.failWith = null;
        s.reset();
        r.remux.ioFail = true;
        int runsBefore = r.remux.runs.get();
        id = run(r, s, m, 0); st = await(r, id);
        check("E/A-Fehler im Remuxer (abgeschnittene Probe) → failed remux_io, kein automatischer neuer Versuch", "failed".equals(field(st, "state")) && "remux_io".equals(field(st, "reason"))
            && r.remux.runs.get() == runsBefore + 1, st);
        r.remux.ioFail = false;
        s.reset();
        r.store.free = 1000;
        id = run(r, s, m, 0); st = await(r, id);
        check("Speicherquote vor dem Start → failed no_space, weder Init noch Stück geladen", "failed".equals(field(st, "state")) && "no_space".equals(field(st, "reason"))
            && s.full("init-0.m4s") == 0 && s.full("init-2.m4s") == 0 && s.fullChunks() == 0, st + " " + s.fullGets);
        r.store.free = Long.MAX_VALUE / 4;
    }

    // ------------------------------------------------------------------ Wiederaufnahme

    static void resume(Server s) throws Exception {
        String m = mpd();
        s.reset();
        Rig r = new Rig("resume", s);
        s.failPath = "chunk-1-00004.m4s"; s.failStatus = 404;
        String p = plan(r, s, m), id = newId(), opt = optionIds(p).get(1);
        r.engine.start(PEER, BIND, id, field(p, "planId"), opt, "x");
        String st = await(r, id);
        check("Stück 404: interrupted http_404 mit Fehlerort video_segment/Index 3 (ohne Adresse)", "interrupted".equals(field(st, "state")) && "http_404".equals(field(st, "reason"))
            && "video_segment".equals(field(st, "at")) && "3".equals(field(st, "at_index")) && clean(st, s), st);
        s.failPath = null;
        s.fullGets.clear();
        String p2 = plan(r, s, m);
        r.engine.start(PEER, BIND, id, field(p2, "planId"), optionIds(p2).get(1), "x");
        st = await(r, id);
        check("Wiederaufnahme (neuer dashstart, gleiche Kennung): done; geladene Stücke per starkem ETag bestätigt, nur Fehlendes neu geladen",
            "done".equals(field(st, "state")) && s.ranges() >= 1 && s.full("chunk-1-00004.m4s") == 1 && s.full("init-1.m4s") == 0 && s.full("init-2.m4s") == 0, st + " full=" + s.fullGets + " ranges=" + s.ranges());
        // ohne starken ETag: alles neu laden
        s.reset();
        s.weakEtag = true;
        s.failPath = "chunk-1-00004.m4s"; s.failStatus = 404;
        p = plan(r, s, m); id = newId();
        r.engine.start(PEER, BIND, id, field(p, "planId"), optionIds(p).get(1), "x");
        await(r, id);
        s.failPath = null; s.fullGets.clear();
        p2 = plan(r, s, m);
        r.engine.start(PEER, BIND, id, field(p2, "planId"), optionIds(p2).get(1), "x");
        st = await(r, id);
        check("Wiederaufnahme ohne starken ETag: keine Wiederverwendung, alles neu geladen", "done".equals(field(st, "state")) && s.ranges() == 0 && s.full("init-1.m4s") == 1 && s.full("chunk-1-00001.m4s") == 1, st);
        // vorübergehend 503: automatischer neuer Versuch im selben Auftrag
        s.reset();
        s.fail503.set(2);
        id = run(r, s, m, 0);
        st = await(r, id);
        check("Vorübergehend 503: automatischer neuer Versuch (P4c-Regeln) → done", "done".equals(field(st, "state")), st);
        // „Fortsetzen“ aus der Benachrichtigung (Quelle im Arbeitsspeicher)
        s.reset();
        s.failPath = "chunk-0-00002.m4s"; s.failStatus = 410;
        id = run(r, s, m, 0);
        st = await(r, id);
        boolean can = r.engine.canResumeLocal(id);
        s.failPath = null;
        boolean started = r.engine.resumeLocal(id);
        st = await(r, id);
        check("Unterbrochen (410) → canResumeLocal; resumeLocal setzt mit derselben Quelle fort → done", can && started && "done".equals(field(st, "state")), st);
    }

    // ------------------------------------------------------------------ Absturz an den Grenzen

    static void crashes(Server s) throws Exception {
        String m = mpd();
        for (String point : new String[] {"segment-fetched", "segment-renamed", "segment-journaled", "remux-start", "verified", "publish-journaled", "publish-copied", "published", "done-journaled"}) {
            s.reset();
            Rig r = new Rig("crash-" + point, s);
            r.hooks.point = point;
            r.hooks.hit = point.startsWith("segment") ? 4 : 1;
            String id = run(r, s, m, 0);
            long end = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < end && !r.hooks.dead.get()) Thread.sleep(10);
            r.restart();
            String after = r.engine.status(id);
            boolean published = point.equals("published") || point.equals("done-journaled");
            String expectState = published ? "done" : "interrupted";
            boolean ok = expectState.equals(field(after, "state"));
            if (!published) {
                String p2 = plan(r, s, m);
                r.engine.start(PEER, BIND, id, field(p2, "planId"), optionIds(p2).get(0), "x");
                after = await(r, id);
                ok = ok && "done".equals(field(after, "state"));
            }
            File[] outs = r.out.listFiles((d, n) -> !n.startsWith("."));
            check("Absturz bei " + point + " → nach Neustart " + expectState + (published ? "" : ", Wiederaufnahme → done") + "; genau eine veröffentlichte Datei, keine Teil-Datei",
                ok && outs != null && outs.length == 1 && r.out.list().length == 1, after + " " + Arrays.toString(r.out.list()));
        }
    }

    // ------------------------------------------------------------------ Abbruch

    static void cancel(Server s) throws Exception {
        String m = mpd();
        s.reset();
        Rig r = new Rig("cancel", s);
        s.failPath = "chunk-0-00003.m4s"; s.failStatus = 404;
        String id = run(r, s, m, 0);
        await(r, id);
        String c = r.engine.cancel(id);
        check("Abbruch eines unterbrochenen Auftrags: sofort cancelled, Daten gelöscht", "cancelled".equals(field(c, "state")) && r.dir(id).list().length == 1, c);
        s.failPath = null;
        String p = plan(r, s, m);
        String again = r.engine.start(PEER, BIND, id, field(p, "planId"), optionIds(p).get(0), "x");
        check("Nach Abbruch startet dieselbe Kennung nie wieder", "cancelled".equals(field(again, "state")), again);
        // Abbruchwunsch gewinnt nach Absturz vor einer Wiederaufnahme
        s.reset();
        r.hooks.point = "segment-journaled"; r.hooks.hit = 3;
        s.failPath = null;
        String id2 = run(r, s, m, 0);
        long end = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < end && !r.hooks.dead.get()) Thread.sleep(10);
        waitForWorkers();
        HlsJournal j = HlsJournal.load(r.dir(id2));
        j.cancelRequested = true;
        j.save(r.dir(id2), HlsJournal.Hooks.NONE);
        r.restart();
        String after = r.engine.status(id2);
        check("Abbruchwunsch im Journal gewinnt nach Neustart (cancelled, Daten gelöscht)", "cancelled".equals(field(after, "state")) && r.dir(id2).list().length == 1, after);
    }
}
