package de.evp.helfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.hls.HlsEngine;
import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsRemux;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import de.evp.helfer.net.DeviceTestClients;

/**
 * P4b auf Wegwerf-Emulatoren (API 26/29/34): die echte HLS-Kette der App – {@link HlsEngine} (SafeHttpClient mit
 * Testregeln gegen einen lokalen Server), {@link Media3HlsRemuxer} (ART, Media3 aus der App) und die echte Ablage
 * {@link Mp4Downloads} (MediaStore-Pending bzw. Teil-Datei). Jede veröffentlichte Datei wird auf diesem Gerät mit dem
 * Plattform-Extractor gelesen und vollständig dekodiert. Dazu: Heap je Sample (ART) für das Sample-Budget, Abbruch
 * durch Speicherdruck und während des Remux, Dienstende + Neustart + Fortsetzen, voller Speicher, MP4 und HLS parallel.
 * Fixtures: synthetische Inhalte aus poc/p4a-hls/tools/make-fixtures.sh als Test-Assets (nie in der App).
 */
@RunWith(AndroidJUnit4.class)
public class HlsDownloadsInstrumentedTest {
    static final String TAG = "HLS_TEST";
    static final byte[] PEER = "device-test-session".getBytes(StandardCharsets.US_ASCII);
    static final String BIND = "dev|1|1";
    static Context ctx;
    static File fx;
    static Server srv;
    static Mp4Downloads store;
    static int seq;

    @BeforeClass public static void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT < 29) {
            for (String p : Mp4Downloads.LEGACY_STORAGE_PERMISSIONS) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(ctx.getPackageName(), p);
            }
        }
        fx = new File(ctx.getCacheDir(), "hls-fx");
        AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        for (String dir : am.list("")) {
            String[] files = am.list(dir);
            if (files == null || files.length == 0 || !java.util.Arrays.asList(files).contains("master.m3u8")) continue;
            File d = new File(fx, dir);
            d.mkdirs();
            for (String f : files) {
                try (InputStream in = am.open(dir + "/" + f); OutputStream out = new FileOutputStream(new File(d, f))) {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                }
            }
        }
        assertTrue("HLS fixtures packaged as test assets", new File(fx, "ts_sep/master.m3u8").isFile());
        srv = new Server();
        store = new Mp4Downloads(ctx, "mp4-jobs-hls-test", DeviceTestClients.loopback(srv.ss.getLocalPort()), () -> Mp4Downloads.legacyWritePermitted(ctx));
    }

    @AfterClass public static void tearDown() throws Exception {
        if (srv != null) srv.close();
        if (store != null) store.engine.stopAll();
        new File(ctx.getFilesDir(), "mp4-jobs-hls-test").delete();
    }

    /** Ablage-Hülle: zählt Veröffentlichungen und kann freien Speicher vortäuschen. */
    static final class Store implements DownloadStore {
        final Mp4Downloads real;
        volatile long freeOverride = -1;
        Store(Mp4Downloads real) { this.real = real; }
        @Override public long freeBytes() throws IOException { return freeOverride >= 0 ? freeOverride : real.freeBytes(); }
        @Override public boolean writable() { return real.writable(); }
        @Override public boolean isPublished(String ref, long total) throws IOException { return real.isPublished(ref, total); }
        @Override public Target create(String displayName, String mime) throws IOException { return real.create(displayName, mime); }
        @Override public byte[] loadJournal() { return null; }
        @Override public void saveJournal(byte[] data) { }
    }

    static final class Events implements DirectDownloads.Listener {
        final Map<String, Object> saved = new ConcurrentHashMap<>();
        @Override public void progress(String id, String title, long bytes, long total) { }
        final Map<String, String> ended = new ConcurrentHashMap<>();
        @Override public synchronized void finished(String id, String title, String state, String reason, Object s) {
            if (s != null) saved.put(id, s);
            ended.put(id, state);
            notifyAll();
        }
        /** Die Engine meldet den Endzustand vor dem Rückruf (außerhalb ihrer Sperren): auf den Rückruf warten. */
        synchronized Object saved(String id, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!ended.containsKey(id) && System.currentTimeMillis() < end) wait(Math.max(1, end - System.currentTimeMillis()));
            return saved.get(id);
        }
    }

    static final class Rig {
        final File root = new File(ctx.getCacheDir(), "hls-jobs-test-" + (++seq));
        final Store st = new Store(store);
        final Events ev = new Events();
        final JobSlots slots;
        HlsEngine engine;
        Rig() throws Exception { this(new JobSlots(2, 4)); }
        Rig(JobSlots slots) throws Exception { this.slots = slots; restart(); }
        void restart() throws Exception {
            engine = new HlsEngine(DeviceTestClients.loopback(srv.ss.getLocalPort()), st, ev, root, new Media3HlsRemuxer(HlsDownloads.opusSupported()),
                slots, HlsDownloads.opusSupported(), HlsDownloads.sampleBudget(Runtime.getRuntime().maxMemory()));
        }
        void close() throws Exception {
            engine.stopAll();
            assertTrue("engine idle", engine.awaitIdle(30_000));
            for (Object s : ev.saved.values()) delete(s);
            deleteTree(root);
        }
    }

    static String id() { return String.format(Locale.ROOT, "%032x", System.nanoTime()); }

    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":(\"([^\"]*)\"|(-?[0-9.]+|true|false))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }

    static int intField(String json, String k) {
        String v = field(json, k);
        return v == null ? -1 : Integer.parseInt(v);
    }

    static String firstOption(String plan) {
        Matcher m = Pattern.compile("\\{\"id\":\"([0-9a-f]{24})\"").matcher(plan);
        return m.find() ? m.group(1) : null;
    }

    static String await(HlsEngine e, String id, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        String st = e.status(id);
        while (System.nanoTime() < end) {
            st = e.status(id);
            String s = field(st, "state");
            if (!"running".equals(s)) return st;
            Thread.sleep(20);
        }
        return st;
    }

    static String run(Rig r, String fixture, String id) throws Exception {
        String plan = r.engine.plan(PEER, BIND, srv.url(fixture + "/master.m3u8"));
        assertEquals(plan, "true", field(plan, "ok"));
        String st = r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "HLS " + fixture);
        assertEquals(st, "true", field(st, "ok"));
        return await(r.engine, id, 120_000);
    }

    // ------------------------------------------------------------------ Tests

    @Test public void allProfilesRemuxPublishAndDecodeOnThisDevice() throws Exception {
        Rig r = new Rig();
        try {
            String[] names = {"ts_muxed", "ts_sep", "ts_sep_offset", "ts_multi", "fmp4_muxed", "fmp4_sep", "fmp4_sep_offset", "fmp4_sep_voffset", "fmp4_sep_noedts"};
            for (String n : names) {
                String id = id();
                String st = run(r, n, id);
                assertEquals(n + ": " + st + " " + r.engine.diagnostics(id), "done", field(st, "state"));
                Object saved = r.ev.saved(id, 10_000);
                assertNotNull(n + ": published reference", saved);
                int[] dec = decodeAll(saved);
                Log.i(TAG, "HLS_DEVICE api=" + Build.VERSION.SDK_INT + " fixture=" + n + " tracks=" + dec[0] + " videoSamples=" + dec[1] + " videoDecoded=" + dec[2]
                    + " audioSamples=" + dec[3] + " audioDecoded=" + dec[4] + " bytes=" + field(st, "total"));
                assertEquals(n + ": exactly video + audio", 2, dec[0]);
                assertTrue(n + ": video decoded completely " + dec[1] + "/" + dec[2], dec[1] > 400 && dec[2] >= dec[1] - 2);
                assertTrue(n + ": audio decoded completely " + dec[3] + "/" + dec[4], dec[3] > 800 && dec[4] >= dec[3] - 2);
            }
            // Opus: nur, wo das Gerät es nachweislich dekodiert; sonst klare Ablehnung ohne Datei
            String id = id();
            String st = run(r, "fmp4_opus_sep", id);
            if (HlsDownloads.opusSupported()) {
                assertEquals(st, "done", field(st, "state"));
                assertEquals(2, decodeAll(r.ev.saved(id, 10_000))[0]);
            } else {
                assertEquals(st, "failed", field(st, "state"));
                assertTrue(st, "audio_codec".equals(field(st, "reason")) || "no_supported_variant".equals(field(st, "reason")));
                assertFalse(r.ev.saved.containsKey(id));
            }
            Log.i(TAG, "HLS_OPUS api=" + Build.VERSION.SDK_INT + " supported=" + HlsDownloads.opusSupported() + " result=" + field(st, "state") + ":" + field(st, "reason"));
        } finally {
            r.close();
        }
    }

    /**
     * ART-Heap je Sample im Muxer-Index (10-min-Fixture, ~43 000 Samples) → Grundlage des Sample-Budgets.
     * Gemessen wird der nach einer vollständigen GC noch belegte Heap (nicht Müll, der jederzeit frei wird), und zwar
     * im Remux-Thread an den Prüfpunkten des Remuxers (alle 2048 Samples), wenn der Index genau so viele Samples hält.
     * Steigung (Regression) = Kosten je Sample; Achsenabschnitt = feste Kosten (Puffer, Extraktoren, Warteschlangen).
     */
    @Test public void heapPerSampleSupportsTheBudget() throws Exception {
        List<File> v = res(new File(fx, "ts_long600"), "v0.m3u8"), a = res(new File(fx, "ts_long600"), "v1.m3u8");
        File out = new File(ctx.getCacheDir(), "long.mp4");
        Runtime rt = Runtime.getRuntime();
        gc();
        long before = rt.totalMemory() - rt.freeMemory();
        List<long[]> points = new ArrayList<>();      // {Samples im Index, belegter Heap nach GC}
        int[] calls = {0};
        Media3HlsRemuxer m = new Media3HlsRemuxer(false);
        long t0 = System.nanoTime();
        HlsRemux.Result res = m.remux(new HlsRemux.Input(v, a, false, 1_000_000), out, () -> {
            long n = (long) calls[0]++ * Media3HlsRemuxer.MEMORY_CHECK_EVERY + 1;
            for (int i = 0; i < 2; i++) rt.gc();
            points.add(new long[] {n, rt.totalMemory() - rt.freeMemory()});
            return false;
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long samples = res.videoSamples + res.audioSamples;
        // Der letzte Aufruf liegt nach der Schleife (alle Samples im Index, Muxer noch offen).
        points.get(points.size() - 1)[0] = samples;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (long[] p : points) { sx += p[0]; sy += p[1]; sxx += (double) p[0] * p[0]; sxy += (double) p[0] * p[1]; }
        int k = points.size();
        double slope = (k * sxy - sx * sy) / (k * sxx - sx * sx);
        long perSample = (long) Math.ceil(Math.max(0, slope));
        long fixed = Math.max(0, (long) ((sy - slope * sx) / k) - before);
        long budget = HlsDownloads.sampleBudget(rt.maxMemory());
        long budgetHeap = fixed + budget * perSample;
        Log.i(TAG, "HLS_MEASURE api=" + Build.VERSION.SDK_INT + " samples=" + samples + " ms=" + ms + " points=" + k
            + " retainedFixedKiB=" + fixed / 1024 + " retainedEndKiB=" + (points.get(k - 1)[1] - before) / 1024
            + " bytesPerSample=" + perSample + " maxHeapMiB=" + rt.maxMemory() / (1 << 20) + " budgetSamples=" + budget
            + " budgetHeapMiB=" + budgetHeap / (1 << 20));
        m.verify(out, res, 600, 600, 4, () -> false);
        out.delete();
        assertTrue("10 min clip", samples > 40_000);
        assertTrue("enough measuring points: " + k, k >= 10);
        // Die Annahme hinter dem Budget (128 B/Sample) muss auf diesem ART gelten, und volles Budget plus feste
        // Kosten dürfen höchstens den halben Heap beanspruchen.
        assertTrue("heap per sample " + perSample + " B exceeds the budget assumption", perSample <= HlsDownloads.HEAP_BYTES_PER_SAMPLE);
        assertTrue("budget fits half the heap: " + budgetHeap, budgetHeap <= rt.maxMemory() / 2);
    }

    @Test public void memoryPressureAbortsCleanly() throws Exception {
        File out = new File(ctx.getCacheDir(), "pressure.mp4");
        List<File> v = res(new File(fx, "ts_sep"), "v0.m3u8"), a = res(new File(fx, "ts_sep"), "v1.m3u8");
        String reason = null;
        try { new Media3HlsRemuxer(false, 0.999).remux(new HlsRemux.Input(v, a, false, 1_000_000), out, () -> false); }
        catch (HlsException e) { reason = e.getMessage(); }
        assertEquals("memory", reason);
        assertFalse("no partial output", out.exists());
        String limit = null;
        try { new Media3HlsRemuxer(false).remux(new HlsRemux.Input(v, a, false, 100), out, () -> false); }
        catch (HlsException e) { limit = e.getMessage(); }
        assertEquals("samples-limit", limit);
        assertFalse(out.exists());
    }

    @Test public void cancelDuringRemuxIsFastAndLeavesNothing() throws Exception {
        List<File> v = res(new File(fx, "ts_long600"), "v0.m3u8"), a = res(new File(fx, "ts_long600"), "v1.m3u8");
        File out = new File(ctx.getCacheDir(), "cancel.mp4");
        AtomicBoolean stop = new AtomicBoolean();
        new Thread(() -> { try { Thread.sleep(200); } catch (InterruptedException ignored) { } stop.set(true); }).start();
        long t0 = System.nanoTime();
        String reason = null;
        try { new Media3HlsRemuxer(false).remux(new HlsRemux.Input(v, a, false, 1_000_000), out, stop::get); }
        catch (HlsException e) { reason = e.getMessage(); }
        long afterCancel = (System.nanoTime() - t0) / 1_000_000 - 200;
        Log.i(TAG, "HLS_CANCEL api=" + Build.VERSION.SDK_INT + " msAfterCancel=" + afterCancel + " result=" + reason);
        assertEquals("cancelled", reason);
        assertTrue("cancel " + afterCancel + " ms", afterCancel < 1000);
        assertFalse(out.exists());
    }

    @Test public void serviceStopRestartAndResume() throws Exception {
        Rig r = new Rig();
        try {
            srv.delayMs = 150;
            String id = id();
            String plan = r.engine.plan(PEER, BIND, srv.url("ts_sep/master.m3u8"));
            r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "Resume");
            // Der Server-Zähler gilt für die ganze Klasse: gewartet wird auf den eigenen Fortschritt des Auftrags
            // (verifizierte, im Journal stehende Segmente), nicht auf einen absoluten Zählerstand.
            long end = System.nanoTime() + 20_000_000_000L;
            while (intField(r.engine.status(id), "done") < 2 && System.nanoTime() < end) Thread.sleep(10);
            r.engine.stopAll();
            String st = await(r.engine, id, 20_000);
            assertEquals(st, "interrupted", field(st, "state"));
            int kept = intField(st, "done");
            assertTrue("segments kept before the stop: " + st, kept >= 2 && kept < 11);
            srv.delayMs = 0;
            // Abgebrochene Anfragen schlafen im Server noch ihre Verzögerung ab und zählen erst danach: erst wenn keine
            // Anfrage mehr offen ist, ist der Zähler der Ausgangswert (Bedingung, keine feste Wartezeit).
            assertTrue("server quiet", srv.quiesce(10_000));
            r.restart();                                   // neue Engine auf demselben Ordner (wie nach Prozessende)
            String afterRestart = r.engine.status(id);
            assertEquals(afterRestart, "interrupted", field(afterRestart, "state"));
            assertEquals(afterRestart, kept, intField(afterRestart, "done"));
            int before = srv.segments.get();
            String p2 = r.engine.plan(PEER, BIND, srv.url("ts_sep/master.m3u8"));
            r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "Resume");
            st = await(r.engine, id, 120_000);
            assertEquals(st, "done", field(st, "state"));
            int refetched = srv.segments.get() - before;
            // ts_sep: 5 Video- + 6 Tonsegmente; genau die nicht bestätigten werden neu geladen (wie der JVM-Test)
            assertEquals("verified segments reused via strong ETag (kept " + kept + ")", 11 - kept, refetched);
            assertEquals(2, decodeAll(r.ev.saved(id, 10_000))[0]);
        } finally {
            srv.delayMs = 0;
            r.close();
        }
    }

    @Test public void persistentCancelWinsAfterRestart() throws Exception {
        Rig r = new Rig();
        try {
            srv.delayMs = 150;
            String id = id();
            String plan = r.engine.plan(PEER, BIND, srv.url("ts_sep/master.m3u8"));
            r.engine.start(PEER, BIND, id, field(plan, "planId"), firstOption(plan), "Cancel");
            long end = System.nanoTime() + 20_000_000_000L;
            while (intField(r.engine.status(id), "done") < 1 && System.nanoTime() < end) Thread.sleep(10);
            String c = r.engine.cancel(id);
            assertNotNull(c);
            String st = await(r.engine, id, 20_000);
            assertEquals(st, "cancelled", field(st, "state"));
            srv.delayMs = 0;
            r.restart();
            assertEquals("cancelled", field(r.engine.status(id), "state"));
            String p2 = r.engine.plan(PEER, BIND, srv.url("ts_sep/master.m3u8"));
            assertEquals("cancelled", field(r.engine.start(PEER, BIND, id, field(p2, "planId"), firstOption(p2), "Cancel"), "state"));
            assertFalse("nothing published", r.ev.saved.containsKey(id));
        } finally {
            srv.delayMs = 0;
            r.close();
        }
    }

    @Test public void fullStorageFailsBeforeAnySegment() throws Exception {
        Rig r = new Rig();
        try {
            r.st.freeOverride = 64L << 20;
            int before = srv.segments.get();
            String id = id();
            String st = run(r, "ts_sep", id);
            assertEquals(st, "failed", field(st, "state"));
            assertEquals(st, "no_space", field(st, "reason"));
            assertEquals("no segment fetched", before, srv.segments.get());
        } finally {
            r.close();
        }
    }

    /** MP4 und HLS gleichzeitig mit gemeinsamen Grenzen (wie in der App): beide fertig, Grenzen eingehalten. */
    @Test public void parallelMp4AndHls() throws Exception {
        JobSlots shared = new JobSlots(2, 4);
        Rig r = new Rig(shared);
        Events mp4Events = new Events();
        DirectDownloads mp4 = new DirectDownloads(DeviceTestClients.loopback(srv.ss.getLocalPort()), store, mp4Events, shared);
        try {
            srv.delayMs = 40;
            CountDownLatch hold = new CountDownLatch(1);
            srv.hold = hold;          // beide Jobs bleiben aktiv, bis der dritte Start geprüft ist
            String mid = id(), hid = id();
            assertTrue(mp4.start(mid, srv.url("big.mp4"), "MP4 parallel").contains("running"));
            String plan = r.engine.plan(PEER, BIND, srv.url("fmp4_sep/master.m3u8"));
            assertTrue(r.engine.start(PEER, BIND, hid, field(plan, "planId"), firstOption(plan), "HLS parallel").contains("running"));
            String third = r.engine.plan(PEER, BIND, srv.url("ts_sep/master.m3u8"));
            assertTrue("third job busy", r.engine.start(PEER, BIND, id(), field(third, "planId"), firstOption(third), "x").contains("busy"));
            srv.hold = null;
            hold.countDown();
            String hs = await(r.engine, hid, 120_000);
            assertTrue("mp4 idle", mp4.awaitIdle(120_000));
            assertEquals(hs, "done", field(hs, "state"));
            assertTrue(mp4.status(mid), mp4.status(mid).contains("\"state\":\"done\""));
            Log.i(TAG, "HLS_PARALLEL api=" + Build.VERSION.SDK_INT + " maxInFlight=" + srv.maxInFlight.get());
            assertTrue("at most 4 HTTP requests at once: " + srv.maxInFlight.get(), srv.maxInFlight.get() <= 4);
            assertEquals(0, shared.inUse());
            assertEquals(0, shared.httpInUse());
        } finally {
            srv.delayMs = 0;
            CountDownLatch h = srv.hold;
            srv.hold = null;
            if (h != null) h.countDown();
            mp4.stopAll();
            mp4.awaitIdle(30_000);
            for (Object s : mp4Events.saved.values()) delete(s);
            r.close();
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static List<File> res(File dir, String list) throws IOException {
        List<File> out = new ArrayList<>();
        for (String l : Files.readAllLines(new File(dir, list).toPath())) {
            if (l.startsWith("#EXT-X-MAP:URI=\"")) out.add(new File(dir, l.substring(16, l.indexOf('"', 16))));
            else if (!l.startsWith("#") && !l.isEmpty()) out.add(new File(dir, l));
        }
        return out;
    }

    static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) { Runtime.getRuntime().gc(); Thread.sleep(50); }
    }

    static Uri uri(Object saved) { return saved instanceof Uri ? (Uri) saved : Uri.fromFile((File) saved); }

    static void delete(Object saved) {
        try {
            if (saved instanceof Uri && "content".equals(((Uri) saved).getScheme())) ctx.getContentResolver().delete((Uri) saved, null, null);
            else if (saved instanceof Uri) new File(((Uri) saved).getPath()).delete();
            else if (saved instanceof File) ((File) saved).delete();
        } catch (RuntimeException ignored) { }
    }

    /** {Spuren, Video-Samples, Video dekodiert, Ton-Samples, Ton dekodiert} der veröffentlichten Datei, auf diesem Gerät. */
    static int[] decodeAll(Object saved) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        int[] r = new int[5];
        try {
            ex.setDataSource(ctx, uri(saved), null);
            r[0] = ex.getTrackCount();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                int[] n = decode(saved, i, fmt);
                boolean video = fmt.getString(MediaFormat.KEY_MIME).startsWith("video/");
                r[video ? 1 : 3] = n[0];
                r[video ? 2 : 4] = n[1];
            }
        } finally {
            ex.release();
        }
        return r;
    }

    static int[] decode(Object saved, int track, MediaFormat fmt) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri(saved), null);
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEnd = false, outEnd = false;
            int samples = 0, decoded = 0;
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (!outEnd) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("decode-timeout");
                if (!inEnd) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) { codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true; }
                        else { codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0); samples++; ex.advance(); }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    if (info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) decoded++;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEnd = true;
                    codec.releaseOutputBuffer(out, false);
                }
            }
            return new int[] {samples, decoded};
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            ex.release();
        }
    }

    /** Lokaler Fixture-Server (Host dl.test über die Testregeln): starker ETag je Datei, If-Range-Bestätigung, Zähler. */
    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger segments = new AtomicInteger(), inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        volatile int delayMs;
        /** Gesetzt: Daten-Antworten (MP4, Segmente) warten, bis der Test freigibt – Jobs bleiben sicher aktiv. */
        volatile CountDownLatch hold;
        Server() throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
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
        String url(String path) { return "http://dl.test:" + ss.getLocalPort() + "/" + path; }
        /** Wartet, bis keine Anfrage mehr bearbeitet wird (Zähler danach stabil). */
        boolean quiesce(long ms) throws InterruptedException {
            long end = System.nanoTime() + ms * 1_000_000L;
            while (inFlight.get() > 0) {
                if (System.nanoTime() > end) return false;
                Thread.sleep(5);
            }
            return true;
        }
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
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    String path = line.split(" ")[1];
                    int q = path.indexOf('?');
                    if (q >= 0) path = path.substring(0, q);
                    if (delayMs > 0) Thread.sleep(delayMs);
                    CountDownLatch h = hold;
                    if (h != null && !path.endsWith(".m3u8") && !h.await(60, TimeUnit.SECONDS)) return;
                    byte[] body;
                    String type;
                    if (path.equals("/big.mp4")) {
                        body = new byte[4 << 20];
                        System.arraycopy(new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'}, 0, body, 0, 8);
                        type = "video/mp4";
                    } else {
                        File f = new File(fx, path.substring(1));
                        if (!f.isFile() || !f.getCanonicalPath().startsWith(fx.getCanonicalPath())) {
                            Mp4DownloadsInstrumentedTest.head(out, "404 Not Found", "Content-Length: 0", "Connection: close");
                            return;
                        }
                        body = Files.readAllBytes(f.toPath());
                        type = path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "application/octet-stream";
                        if (!path.endsWith(".m3u8")) segments.incrementAndGet();
                    }
                    String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(body)) + "-" + body.length + "\"";
                    String range = hd.get("range"), ifRange = hd.get("if-range");
                    if ("bytes=0-0".equals(range) && etag.equals(ifRange)) {
                        if (!path.endsWith(".m3u8")) segments.decrementAndGet();   // Bestätigung, kein Neuladen
                        Mp4DownloadsInstrumentedTest.head(out, "206 Partial Content", "Content-Length: 1", "Content-Range: bytes 0-0/" + body.length,
                            "ETag: " + etag, "Content-Type: " + type, "Connection: close");
                        out.write(body, 0, 1);
                        return;
                    }
                    Mp4DownloadsInstrumentedTest.head(out, "200 OK", "Content-Length: " + body.length, "ETag: " + etag, "Content-Type: " + type,
                        "Accept-Ranges: bytes", "Connection: close");
                    out.write(body);
                } finally {
                    inFlight.decrementAndGet();
                }
            } catch (Exception ignored) {
            }
        }
        @Override public void close() throws IOException { ss.close(); }
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }
}
