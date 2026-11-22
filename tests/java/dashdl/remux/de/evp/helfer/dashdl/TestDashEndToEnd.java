package de.evp.helfer.dashdl;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.dash.DashFingerprint;
import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.hls.HlsRemux;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import de.evp.helfer.net.LocalTestClients;
import de.evp.helfer.net.RequestContext;

/**
 * S3b Ende-zu-Ende auf dem JVM: DashEngine + echter SafeHttpClient + lokaler Server + unveränderter Media3-Remuxer.
 * dashplan (Fingerabdruck wie Kadri) → dashstart → veröffentlichte MP4 → analyze.py gegen die DASH-Quelle.
 *   java TestDashEndToEnd FIXTURES_DIR WORK_DIR ANALYZE_PY
 */
public final class TestDashEndToEnd {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":(\"([^\"]*)\"|(-?[0-9.]+))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }

    static volatile File served;          // Verzeichnis der Fixture
    static volatile byte[] mpdOverride;   // ausgeliefertes MPD (null: Datei)

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), work = new File(args[1]);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", x -> {
            String rel = x.getRequestURI().getPath().replaceFirst("^/v/", "");
            File f = new File(served, rel);
            byte[] b = rel.equals("manifest.mpd") && mpdOverride != null ? mpdOverride : f.isFile() ? Files.readAllBytes(f.toPath()) : null;
            if (b == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            x.getResponseHeaders().set("ETag", "\"" + Integer.toHexString(Arrays.hashCode(b)) + "\"");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
            x.close();
        });
        srv.start();
        int port = srv.getAddress().getPort();
        File avc = new File(root, "avc"), num = new File(root, "num");
        String m = new String(Files.readAllBytes(new File(avc, "manifest.mpd").toPath()), StandardCharsets.UTF_8);

        // 1. Zeitleiste (20 s), unverändert
        File mp4 = scenario("avc", avc, null, port, work, "done", null);
        analyze("avc", args[2], new File(avc, "manifest.mpd"), mp4, null);
        // 2. Fix 1: echte FFmpeg-Quelle mit $Number$ + @duration (18 s, letztes Stück 2 s statt @duration 4 s)
        mp4 = scenario("number", num, null, port, work, "done", null);
        analyze("number", args[2], new File(num, "manifest.mpd"), mp4, null);
        // 3. Fix 1: Periode 18,5 s schneidet das letzte Stück an – die Fragmente reichen legitim 1,5 s darüber hinaus
        mp4 = scenario("clipped", avc, m.replace("PT20.0S", "PT18.5S"), port, work, "done", null);
        analyze("clipped", args[2], new File(avc, "manifest.mpd"), mp4, null);
        // 4. Fix 1 Gegenbeispiel: formal gültiges MPD (letztes Stück 600 s, Periode 616 s), echte Fragmente nur 20 s.
        //    Vorher: Toleranz = größte Stückdauer (600 s) ≥ |≈20 − 616| → veröffentlicht. Jetzt: failed, nichts veröffentlicht.
        String inflated = m.replace("mediaPresentationDuration=\"PT20.0S\"", "mediaPresentationDuration=\"PT616.0S\"")
            .replace("<S t=\"0\" d=\"51200\" r=\"4\" />", "<S t=\"0\" d=\"51200\" r=\"3\" /><S d=\"7680000\" />")
            .replace("<S d=\"2560\" />", "<S d=\"" + (616L * 48000 - 957440) + "\" />");
        scenario("short-fragments", avc, inflated, port, work, "failed", "media_duration");
        // 5. Fix 2: Periode 10 s, deklarierte Stückdauer 3600 s (timescale 1000, duration 3600000) wie im Prüfbefund;
        //    die echten Fragmente sind 10 s lang (je Spur genau ein Stück). Legitim → done.
        File lng = new File(root, "long");
        String l = new String(Files.readAllBytes(new File(lng, "manifest.mpd").toPath()), StandardCharsets.UTF_8)
            .replace("timescale=\"1000000\" duration=\"2000000000\"", "timescale=\"1000\" duration=\"3600000\"");
        check("period10-seg3600: MPD deklariert 3600 s je Stück bei 10 s Periode", l.contains("duration=\"3600000\"") && l.contains("PT10.0S"), "");
        mp4 = scenario("period10-seg3600", lng, l, port, work, "done", null);
        analyze("period10-seg3600", args[2], new File(lng, "manifest.mpd"), mp4, null);
        // 6. Fix 2: dieselbe Quelle, aber die Ausgabe des (echten) Remuxers ist deutlich kürzer (4 s statt 10 s): ein
        //    Remuxer, der statt der übergebenen Stücke nur Init + erstes Stück der $Number$-Quelle zusammenbaut. Die
        //    unveränderte Media3-Prüfung muss das ablehnen – vorher hätte die Toleranz 1 s + 3590 s betragen.
        Media3HlsRemuxer real = new Media3HlsRemuxer(false);
        HlsRemux shortening = new HlsRemux() {
            public Result remux(Input in, File out, java.util.function.BooleanSupplier c) throws IOException, de.evp.helfer.hls.HlsException {
                List<File> v = Arrays.asList(new File(num, "init-0.m4s"), new File(num, "chunk-0-00001.m4s"));
                List<File> a = Arrays.asList(new File(num, "init-1.m4s"), new File(num, "chunk-1-00001.m4s"));
                return real.remux(new Input(v, a, true, in.maxSamples), out, c);
            }
            public void verify(File out, Result r, double vs, double as, double tol, java.util.function.BooleanSupplier c) throws IOException, de.evp.helfer.hls.HlsException {
                lastTolerance = tol;
                real.verify(out, r, vs, as, tol, c);
            }
        };
        scenario("period10-seg3600-short-output", lng, l, port, work, "failed", "verify_duration", shortening);
        check("period10-seg3600-short-output: Ausgabeprüfung mit genau 1 s Toleranz (ohne Anteil außerhalb der Periode)", lastTolerance == 1.0, lastTolerance);
        srv.stop(0);
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static volatile double lastTolerance = -1;

    static File scenario(String name, File fx, String mpd, int port, File work, String wantState, String wantReason) throws Exception {
        return scenario(name, fx, mpd, port, work, wantState, wantReason, new Media3HlsRemuxer(false));
    }

    static File scenario(String name, File fx, String mpd, int port, File work, String wantState, String wantReason, HlsRemux remux) throws Exception {
        served = fx;
        mpdOverride = mpd == null ? null : mpd.getBytes(StandardCharsets.UTF_8);
        File out = new File(work, name + "/out"), jobs = new File(work, name + "/jobs");
        out.mkdirs();
        DownloadStore store = new DownloadStore() {
            public long freeBytes() { return Long.MAX_VALUE / 4; }
            public boolean writable() { return true; }
            public boolean isPublished(String ref, long total) { return new File(ref).length() == total; }
            public Target create(String n, String mime) throws IOException {
                File part = new File(out, ".part"), fin = new File(out, n);
                OutputStream fos = new FileOutputStream(part);
                return new Target() {
                    public OutputStream out() { return fos; }
                    public String ref() { return fin.getPath(); }
                    public Object commit() throws IOException { fos.close(); if (!part.renameTo(fin)) throw new IOException("rename"); return fin; }
                    public void abort() { try { fos.close(); } catch (IOException ignored) { } part.delete(); }
                };
            }
            public byte[] loadJournal() { return null; }
            public void saveJournal(byte[] d) { }
        };
        DirectDownloads.Listener events = new DirectDownloads.Listener() {
            public void progress(String id, String t, long b, long total) { }
            public void finished(String id, String t, String state, String reason, Object saved) { }
        };
        DashEngine engine = new DashEngine(LocalTestClients.loopback(port), store, events, jobs, remux, new JobSlots(2, 4), 1_000_000);
        String url = "http://127.0.0.1:" + port + "/v/manifest.mpd?t=SECRET";
        byte[] mpdBytes = mpdOverride != null ? mpdOverride : Files.readAllBytes(new File(fx, "manifest.mpd").toPath());
        String fp = DashFingerprint.hex(DashManifest.parse(mpdBytes, url));
        byte[] peer = "peer".getBytes(StandardCharsets.US_ASCII);
        String plan = engine.plan(peer, "p|1|1", url, fp, new int[] {0, 1, 2}, RequestContext.NONE);
        Matcher mm = Pattern.compile("\"id\":\"([0-9a-f]{24})\"[^}]*\"height\":360").matcher(plan);
        boolean planned = plan.contains("\"ok\":true") && mm.find();
        check(name + ": dashplan gegen den lokalen Server: Option 360p + AAC", planned, plan);
        if (!planned) return new File(out, "missing");
        String id = "0123456789abcdef0123456789abcdef";
        engine.start(peer, "p|1|1", id, field(plan, "planId"), mm.group(1), "dash-e2e");
        String st = engine.status(id);
        long end = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < end && "running".equals(field(st, "state"))) { Thread.sleep(50); st = engine.status(id); }
        File[] pub = out.listFiles((d, n) -> !n.startsWith("."));
        File mp4 = pub != null && pub.length == 1 ? pub[0] : new File(out, "missing");
        if ("done".equals(wantState)) {
            check(name + ": dashstart → done; veröffentlichte MP4 = gemeldete Größe", "done".equals(field(st, "state")) && mp4.isFile() && String.valueOf(mp4.length()).equals(field(st, "total")),
                st + " / " + engine.diagnostics(id));
        } else {
            check(name + ": dashstart → " + wantState + " " + wantReason + ", keine veröffentlichte Datei", wantState.equals(field(st, "state")) && wantReason.equals(field(st, "reason"))
                && (pub == null || pub.length == 0), st + " / " + engine.diagnostics(id) + " / " + (pub == null ? "-" : Arrays.toString(pub)));
        }
        return mp4;
    }

    static void analyze(String name, String py, File src, File mp4, String duration) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList("python3", "-I", py, src.getPath(), mp4.getPath(), "--audio", "aac"));
        if (duration != null) { cmd.add("--duration"); cmd.add(duration); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String an = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        check(name + ": analyze.py: H.264 + AAC, Bilder, Dauer und A/V-Versatz wie die Quelle", an.startsWith("PASS") && an.contains("\"fails\": []"), an);
    }
}
