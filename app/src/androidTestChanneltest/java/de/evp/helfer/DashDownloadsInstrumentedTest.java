package de.evp.helfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.dash.DashFingerprint;
import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dashdl.DashEngine;
import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import de.evp.helfer.net.DeviceTestClients;
import de.evp.helfer.net.RequestContext;

/**
 * S3b auf Wegwerf-Emulatoren (API 26/29/34): die echte DASH-Kette der App – {@link DashEngine} (SafeHttpClient mit
 * Testregeln gegen einen lokalen Server), {@link Media3HlsRemuxer} (ART, Media3 aus der App), echte Ablage
 * {@link Mp4Downloads}. Jede veröffentlichte Datei wird auf diesem Gerät mit dem Plattform-Extractor gelesen und
 * vollständig dekodiert. Dazu: verschlüsseltes Init (vor jedem Stück abgelehnt), abweichender Fingerabdruck,
 * Dienstende + neue Engine + Fortsetzen, persistenter Abbruch. Fixtures: tests/java/dashdl/make-dash-fixtures.sh
 * (Test-Assets unter dash/avc, nie in der App).
 */
@RunWith(AndroidJUnit4.class)
public class DashDownloadsInstrumentedTest {
    static final String TAG = "DASH_TEST";
    static final byte[] PEER = "device-test-session".getBytes(StandardCharsets.US_ASCII);
    static final String BIND = "dev|1|1";
    static Context ctx;
    static File fx;
    static Server srv;
    static Mp4Downloads store;
    static int seq;

    @BeforeClass public static void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HlsDownloadsInstrumentedTest.ctx = ctx;   // decodeAll/delete der HLS-Gerätetests benutzen
        if (Build.VERSION.SDK_INT < 29) {
            for (String p : Mp4Downloads.LEGACY_STORAGE_PERMISSIONS) Mp4DownloadsInstrumentedTest.shell("pm grant " + ctx.getPackageName() + " " + p);
        }
        fx = new File(ctx.getCacheDir(), "dash-fx");
        File d = new File(fx, "avc");
        d.mkdirs();
        AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        String[] files = am.list("dash/avc");
        assertTrue("DASH fixtures packaged as test assets (dash/avc)", files != null && java.util.Arrays.asList(files).contains("manifest.mpd"));
        for (String f : files) {
            try (InputStream in = am.open("dash/avc/" + f); OutputStream out = new FileOutputStream(new File(d, f))) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
        }
        srv = new Server();
        store = new Mp4Downloads(ctx, "mp4-jobs-dash-test", DeviceTestClients.loopback(srv.ss.getLocalPort()), () -> Mp4Downloads.legacyWritePermitted(ctx));
    }

    @AfterClass public static void tearDown() throws Exception {
        if (srv != null) srv.close();
        if (store != null) store.engine.stopAll();
    }

    static final class Events implements DirectDownloads.Listener {
        final Map<String, Object> saved = new ConcurrentHashMap<>();
        @Override public void progress(String id, String title, long bytes, long total) { }
        @Override public synchronized void finished(String id, String title, String state, String reason, Object s) {
            if (s != null) saved.put(id, s);
            notifyAll();
        }
        synchronized Object saved(String id, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!saved.containsKey(id) && System.currentTimeMillis() < end) wait(Math.max(1, end - System.currentTimeMillis()));
            return saved.get(id);
        }
    }

    static final class Rig {
        final File root = new File(ctx.getCacheDir(), "dash-jobs-test-" + (++seq));
        final Events ev = new Events();
        final JobSlots slots = new JobSlots(2, 4);
        DashEngine engine;
        Rig() throws Exception { restart(); }
        void restart() throws Exception {
            engine = new DashEngine(DeviceTestClients.loopback(srv.ss.getLocalPort()), store, ev, root, new Media3HlsRemuxer(false), slots,
                HlsDownloads.sampleBudget(Runtime.getRuntime().maxMemory()));
        }
        void close() throws Exception {
            engine.stopAll();
            assertTrue("engine idle", engine.awaitIdle(30_000));
            for (Object s : ev.saved.values()) HlsDownloadsInstrumentedTest.delete(s);
            HlsDownloadsInstrumentedTest.deleteTree(root);
        }
    }

    static String id() { return String.format(Locale.ROOT, "%032x", System.nanoTime()); }

    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":(\"([^\"]*)\"|(-?[0-9.]+|true|false))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }

    static String option(String plan, int height) {
        Matcher m = Pattern.compile("\\{\"id\":\"([0-9a-f]{24})\",\"height\":" + height + ",").matcher(plan);
        return m.find() ? m.group(1) : null;
    }

    static String fp() throws Exception {
        return DashFingerprint.hex(DashManifest.parse(Files.readAllBytes(new File(fx, "avc/manifest.mpd").toPath()), srv.url("avc/manifest.mpd")));
    }

    static String plan(Rig r) throws Exception {
        String p = r.engine.plan(PEER, BIND, srv.url("avc/manifest.mpd"), fp(), null, RequestContext.NONE);
        assertEquals(p, "true", field(p, "ok"));
        return p;
    }

    static String await(DashEngine e, String id, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        String st = e.status(id);
        while (System.nanoTime() < end) {
            st = e.status(id);
            if (!"running".equals(field(st, "state"))) return st;
            Thread.sleep(20);
        }
        return st;
    }

    // ------------------------------------------------------------------ Tests

    @Test public void dashRemuxPublishAndDecodeOnThisDevice() throws Exception {
        Rig r = new Rig();
        try {
            for (int h : new int[] {180, 360}) {
                String p = plan(r), id = id();
                String st = r.engine.start(PEER, BIND, id, field(p, "planId"), option(p, h), "DASH " + h);
                assertEquals(st, "true", field(st, "ok"));
                st = await(r.engine, id, 120_000);
                assertEquals(h + ": " + st + " " + r.engine.diagnostics(id), "done", field(st, "state"));
                Object saved = r.ev.saved(id, 10_000);
                assertNotNull(h + ": published reference", saved);
                int[] dec = HlsDownloadsInstrumentedTest.decodeAll(saved);
                Log.i(TAG, "DASH_DEVICE api=" + Build.VERSION.SDK_INT + " height=" + h + " tracks=" + dec[0] + " videoSamples=" + dec[1] + " videoDecoded=" + dec[2]
                    + " audioSamples=" + dec[3] + " audioDecoded=" + dec[4] + " bytes=" + field(st, "total"));
                assertEquals(h + ": exactly video + audio", 2, dec[0]);
                assertTrue(h + ": video decoded completely " + dec[1] + "/" + dec[2], dec[1] == 500 && dec[2] >= dec[1] - 2);
                assertTrue(h + ": audio decoded completely " + dec[3] + "/" + dec[4], dec[3] > 800 && dec[4] >= dec[3] - 2);
            }
        } finally {
            r.close();
        }
    }

    @Test public void encryptedInitFailsBeforeAnySegment() throws Exception {
        Rig r = new Rig();
        try {
            byte[] init = Files.readAllBytes(new File(fx, "avc/init-1.m4s").toPath());
            for (int i = 0; i + 4 <= init.length; i++) {
                if (init[i] == 'a' && init[i + 1] == 'v' && init[i + 2] == 'c' && init[i + 3] == '1') { init[i] = 'e'; init[i + 1] = 'n'; init[i + 2] = 'c'; init[i + 3] = 'v'; break; }
            }
            srv.override.put("/avc/init-1.m4s", init);
            int before = srv.chunks.get();
            String p = plan(r), id = id();
            r.engine.start(PEER, BIND, id, field(p, "planId"), option(p, 360), "DASH enc");
            String st = await(r.engine, id, 60_000);
            assertEquals(st, "failed", field(st, "state"));
            assertEquals(st, "encrypted", field(st, "reason"));
            assertEquals("no segment requested", before, srv.chunks.get());
            assertFalse(r.ev.saved.containsKey(id));
        } finally {
            srv.override.clear();
            r.close();
        }
    }

    @Test public void fingerprintMismatchIsRejected() throws Exception {
        Rig r = new Rig();
        try {
            String p = r.engine.plan(PEER, BIND, srv.url("avc/manifest.mpd"), "00000000000000000000000000000000000000000000000000000000000000ff", null, RequestContext.NONE);
            assertEquals(p, "manifest_mismatch", field(p, "reason"));
        } finally {
            r.close();
        }
    }

    @Test public void serviceStopNewEngineAndResume() throws Exception {
        Rig r = new Rig();
        try {
            CountDownLatch hold = new CountDownLatch(1);
            srv.holdAfter.set(4);
            srv.hold = hold;
            String p = plan(r), id = id();
            r.engine.start(PEER, BIND, id, field(p, "planId"), option(p, 180), "DASH resume");
            long end = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < end && srv.waiting.get() == 0) Thread.sleep(20);
            r.engine.stopAll();
            hold.countDown();
            srv.hold = null;
            assertTrue(r.engine.awaitIdle(30_000));
            assertEquals("interrupted", field(r.engine.status(id), "state"));
            r.restart();   // „Prozess neu“: neue Engine auf demselben Ordner
            assertEquals("interrupted", field(r.engine.status(id), "state"));
            String p2 = plan(r);
            r.engine.start(PEER, BIND, id, field(p2, "planId"), option(p2, 180), "DASH resume");
            String st = await(r.engine, id, 120_000);
            assertEquals(st + " " + r.engine.diagnostics(id), "done", field(st, "state"));
            int[] dec = HlsDownloadsInstrumentedTest.decodeAll(r.ev.saved(id, 10_000));
            assertEquals(2, dec[0]);
            assertTrue("resumed output decodes", dec[1] == 500 && dec[2] >= dec[1] - 2);
        } finally {
            srv.hold = null;
            r.close();
        }
    }

    @Test public void persistentCancelLeavesNothing() throws Exception {
        Rig r = new Rig();
        try {
            CountDownLatch hold = new CountDownLatch(1);
            srv.holdAfter.set(2);
            srv.hold = hold;
            String p = plan(r), id = id();
            r.engine.start(PEER, BIND, id, field(p, "planId"), option(p, 360), "DASH cancel");
            long end = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < end && srv.waiting.get() == 0) Thread.sleep(20);
            String c = r.engine.cancel(id);
            hold.countDown();
            srv.hold = null;
            String st = await(r.engine, id, 30_000);
            assertEquals(c + " " + st, "cancelled", field(st, "state"));
            assertFalse(r.ev.saved.containsKey(id));
            File dir = new File(r.root, id);
            String[] left = dir.list();
            assertTrue("only the journal is left", left != null && left.length == 1 && left[0].equals("journal"));
            r.restart();
            assertEquals("cancelled", field(r.engine.status(id), "state"));
        } finally {
            srv.hold = null;
            r.close();
        }
    }

    // ------------------------------------------------------------------ lokaler Server

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger chunks = new AtomicInteger(), waiting = new AtomicInteger(), holdAfter = new AtomicInteger();
        final Map<String, byte[]> override = new ConcurrentHashMap<>();
        volatile CountDownLatch hold;
        Server() throws IOException {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50);
            Thread t = new Thread(() -> {
                while (true) {
                    Socket s;
                    try { s = ss.accept(); } catch (IOException e) { return; }
                    new Thread(() -> serve(s)).start();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        String url(String path) { return "http://dl.test:" + ss.getLocalPort() + "/" + path + "?t=DEVICE"; }
        void serve(Socket s) {
            try (Socket sock = s) {
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                String line = Mp4DownloadsInstrumentedTest.readLine(in);
                if (line == null) return;
                Map<String, String> hd = new java.util.HashMap<>();
                for (String l; !(l = Mp4DownloadsInstrumentedTest.readLine(in)).isEmpty(); ) {
                    int c = l.indexOf(':');
                    hd.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
                }
                String path = line.split(" ")[1];
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                boolean chunk = path.contains("/chunk-");
                CountDownLatch h = hold;
                if (h != null && chunk && chunks.get() >= holdAfter.get()) {
                    waiting.incrementAndGet();
                    try { if (!h.await(60, TimeUnit.SECONDS)) return; } finally { waiting.decrementAndGet(); }
                }
                byte[] body = override.get(path);
                if (body == null) {
                    File f = new File(fx, path.substring(1));
                    if (!f.isFile() || !f.getCanonicalPath().startsWith(fx.getCanonicalPath())) {
                        Mp4DownloadsInstrumentedTest.head(out, "404 Not Found", "Content-Length: 0", "Connection: close");
                        return;
                    }
                    body = Files.readAllBytes(f.toPath());
                }
                String type = path.endsWith(".mpd") ? "application/dash+xml" : "video/mp4";
                String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(body)) + "-" + body.length + "\"";
                if ("bytes=0-0".equals(hd.get("range")) && etag.equals(hd.get("if-range"))) {
                    Mp4DownloadsInstrumentedTest.head(out, "206 Partial Content", "Content-Length: 1", "Content-Range: bytes 0-0/" + body.length,
                        "ETag: " + etag, "Content-Type: " + type, "Connection: close");
                    out.write(body, 0, 1);
                    return;
                }
                if (chunk) chunks.incrementAndGet();
                Mp4DownloadsInstrumentedTest.head(out, "200 OK", "Content-Length: " + body.length, "ETag: " + etag, "Content-Type: " + type, "Connection: close");
                out.write(body);
            } catch (IOException | InterruptedException ignored) {
                // Client hat abgebrochen
            }
        }
        @Override public void close() throws IOException { ss.close(); }
    }
}
