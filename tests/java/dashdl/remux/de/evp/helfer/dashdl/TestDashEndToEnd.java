package de.evp.helfer.dashdl;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.dash.DashFingerprint;
import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
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

    public static void main(String[] args) throws Exception {
        File fx = new File(args[0], "avc"), work = new File(args[1]);
        File out = new File(work, "out"), jobs = new File(work, "jobs");
        out.mkdirs();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", x -> {
            File f = new File(fx, x.getRequestURI().getPath().replaceFirst("^/v/", ""));
            if (!f.isFile()) { x.sendResponseHeaders(404, -1); x.close(); return; }
            byte[] b = Files.readAllBytes(f.toPath());
            x.getResponseHeaders().set("ETag", "\"" + Integer.toHexString(Arrays.hashCode(b)) + "\"");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
            x.close();
        });
        srv.start();
        int port = srv.getAddress().getPort();
        DownloadStore store = new DownloadStore() {
            public long freeBytes() { return Long.MAX_VALUE / 4; }
            public boolean writable() { return true; }
            public boolean isPublished(String ref, long total) { return new File(ref).length() == total; }
            public Target create(String name, String mime) throws IOException {
                File part = new File(out, ".part"), fin = new File(out, name);
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
        DashEngine engine = new DashEngine(LocalTestClients.loopback(port), store, events, jobs, new Media3HlsRemuxer(false), new JobSlots(2, 4), 1_000_000);
        String url = "http://127.0.0.1:" + port + "/v/manifest.mpd?t=SECRET";
        String fp = DashFingerprint.hex(DashManifest.parse(Files.readAllBytes(new File(fx, "manifest.mpd").toPath()), url));
        byte[] peer = "peer".getBytes(StandardCharsets.US_ASCII);
        String plan = engine.plan(peer, "p|1|1", url, fp, new int[] {1, 2}, RequestContext.NONE);
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f]{24})\"[^}]*\"height\":360").matcher(plan);
        check("dashplan gegen den lokalen Server: Option 360p + AAC", "true".equals(String.valueOf(plan.contains("\"ok\":true"))) && m.find(), plan);
        String id = "0123456789abcdef0123456789abcdef";
        engine.start(peer, "p|1|1", id, field(plan, "planId"), m.group(1), "dash-e2e");
        String st = engine.status(id);
        long end = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < end && "running".equals(field(st, "state"))) { Thread.sleep(50); st = engine.status(id); }
        File[] pub = out.listFiles((d, n) -> !n.startsWith("."));
        File mp4 = pub != null && pub.length == 1 ? pub[0] : new File(out, "missing");
        check("dashstart → done; veröffentlichte MP4 = gemeldete Größe", "done".equals(field(st, "state")) && mp4.isFile() && String.valueOf(mp4.length()).equals(field(st, "total")),
            st + " / " + engine.diagnostics(id));
        Process p = new ProcessBuilder(Arrays.asList("python3", "-I", args[2], new File(fx, "manifest.mpd").getPath(), mp4.getPath(), "--audio", "aac")).redirectErrorStream(true).start();
        String an = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        check("analyze.py: H.264 + AAC, 500 Bilder, Dauer und A/V-Versatz wie die Quelle", an.startsWith("PASS") && an.contains("\"fails\": []"), an);
        srv.stop(0);
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
