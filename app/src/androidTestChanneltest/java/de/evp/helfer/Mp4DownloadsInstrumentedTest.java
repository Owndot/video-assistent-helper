package de.evp.helfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.net.DeviceTestClients;

/**
 * Gerätetests der direkten MP4-Downloads (Phase C2) mit der echten Android-Ablage {@link Mp4Downloads}: MediaStore
 * (API 29+) bzw. app-eigener Ordner + Medienscanner (API 26–28), StatFs, Journal, Benachrichtigungen. Der Server ist
 * ein lokaler Testserver; der Client hat alle Prüfschichten mit Testregeln (nur 127.0.0.1:Port).
 * Nur erzeugte Testdaten, keine echten Medien-URLs.
 */
@RunWith(AndroidJUnit4.class)
public class Mp4DownloadsInstrumentedTest {
    private static final String TAG = "MP4_TEST";
    private static final String SECRET = "C2SECRETTOKEN";
    private static Context ctx;
    private static int idCounter;

    @BeforeClass public static void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant " + ctx.getPackageName() + " android.permission.POST_NOTIFICATIONS");
    }

    /** URL, Host und Token der Testserver dürfen nie im Logcat stehen (auch nicht in Fehlermeldungen). */
    @org.junit.AfterClass public static void logcatHasNoUrl() throws Exception {
        String logcat = shell("logcat -d");
        assertFalse("URL token found in logcat", logcat.contains(SECRET));
        assertFalse("test host found in logcat", logcat.contains("dl.test:"));
    }

    static String shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] b = new byte[8192];
            StringBuilder sb = new StringBuilder();
            for (int n; (n = in.read(b)) > 0; ) sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
            return sb.toString();
        }
    }

    static synchronized String newId() {
        return String.format(Locale.ROOT, "%016x%016x", SystemClock.elapsedRealtimeNanos(), ++idCounter);
    }

    // ------------------------------------------------------------ Testinhalt und Server

    static int at(long i) {
        if (i < 8) return new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'}[(int) i] & 0xff;
        return (int) (((i * 2654435761L) >>> 13) & 0xff);
    }

    static void content(OutputStream out, long from, long to) throws IOException {
        byte[] buf = new byte[64 << 10];
        for (long p = from; p < to; ) {
            int n = (int) Math.min(buf.length, to - p);
            for (int k = 0; k < n; k++) buf[k] = (byte) at(p + k);
            out.write(buf, 0, n);
            p += n;
        }
    }

    static byte[] expectedSha(long total) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 << 10];
        for (long p = 0; p < total; ) {
            int n = (int) Math.min(buf.length, total - p);
            for (int k = 0; k < n; k++) buf[k] = (byte) at(p + k);
            md.update(buf, 0, n);
            p += n;
        }
        return md.digest();
    }

    static byte[] sha(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 << 10];
        for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        return md.digest();
    }

    interface Handler { void handle(String line, Map<String, String> headers, OutputStream out, int index) throws Exception; }

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger connections = new AtomicInteger(), requests = new AtomicInteger();
        final List<Socket> open = new CopyOnWriteArrayList<>();

        Server(Handler h) throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (true) {
                    Socket s;
                    try { s = ss.accept(); } catch (IOException e) { return; }
                    connections.incrementAndGet();
                    open.add(s);
                    new Thread(() -> serve(s, h)).start();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void serve(Socket s, Handler h) {
            try (Socket sock = s) {
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) return;
                    Map<String, String> hd = new HashMap<>();
                    for (String l; !(l = readLine(in)).isEmpty(); ) {
                        int c = l.indexOf(':');
                        hd.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
                    }
                    h.handle(line, hd, out, requests.getAndIncrement());
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                open.remove(s);
            }
        }

        String url(String path) { return "http://dl.test:" + ss.getLocalPort() + path + "?sig=" + SECRET; }

        @Override public void close() throws IOException {
            ss.close();
            for (Socket s : open) { try { s.close(); } catch (IOException ignored) { } }
        }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) return b.length() == 0 ? null : b.toString();
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    static void head(OutputStream out, String status, String... headers) throws IOException {
        StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        for (String h : headers) if (!h.isEmpty()) b.append(h).append("\r\n");
        out.write(b.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Ganze Datei oder Bereich; {@code cutAt} &gt;= 0: Verbindung nach so vielen Bytes (absolut) beenden. */
    static void serveFile(Map<String, String> h, OutputStream out, long total, String etag, long cutAt) throws IOException {
        String r = h.get("range");
        long start = r == null ? -1 : Long.parseLong(r.substring(6, r.indexOf('-')));
        String v = etag == null ? "" : "ETag: " + etag;
        if (start < 0) {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total, v);
            start = 0;
        } else {
            head(out, "206 Partial Content", "Content-Type: video/mp4", "Content-Length: " + (total - start),
                "Content-Range: bytes " + start + "-" + (total - 1) + "/" + total, v);
        }
        long end = cutAt > start ? Math.min(cutAt, total) : total;
        content(out, start, end);
        out.flush();
        if (end < total) throw new IOException("cut");
    }

    // ------------------------------------------------------------ Engine mit echter Android-Ablage

    static final class Events implements DirectDownloads.Listener {
        final Map<String, String> finished = new ConcurrentHashMap<>();
        final Map<String, Object> saved = new ConcurrentHashMap<>();
        final DirectDownloads.Listener real;
        Events(DirectDownloads.Listener real) { this.real = real; }
        @Override public void progress(String id, String title, long bytes, long total) { real.progress(id, title, bytes, total); }
        @Override public synchronized void finished(String id, String title, String state, String reason, Object s) {
            real.finished(id, title, state, reason, s);
            if (s != null) saved.put(id, s);
            finished.put(id, state + (reason.isEmpty() ? "" : ":" + reason));
            notifyAll();
        }
        synchronized String await(String id, long ms) throws InterruptedException {
            long end = SystemClock.elapsedRealtime() + ms;
            while (!finished.containsKey(id)) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) fail("timeout waiting for " + id);
                wait(left);
            }
            return finished.get(id);
        }
    }

    Mp4Downloads store;
    Events events;
    DirectDownloads engine;
    final List<Uri> cleanup = new ArrayList<>();
    final List<File> cleanupFiles = new ArrayList<>();
    String journalName;

    void engineFor(Server srv) throws Exception {
        journalName = "mp4-jobs-test-" + newId();
        store = new Mp4Downloads(ctx, journalName, DeviceTestClients.loopback(srv.ss.getLocalPort()));
        events = new Events(store);
        engine = new DirectDownloads(DeviceTestClients.loopback(srv.ss.getLocalPort()), store, events);
    }

    @Before public void before() { cleanup.clear(); cleanupFiles.clear(); }

    @After public void after() {
        if (engine != null) engine.stopAll();
        ContentResolver cr = ctx.getContentResolver();
        for (Uri u : cleanup) { try { if ("content".equals(u.getScheme())) cr.delete(u, null, null); } catch (RuntimeException ignored) { } }
        for (File f : cleanupFiles) f.delete();
        if (journalName != null) new File(ctx.getFilesDir(), journalName).delete();
    }

    static File legacyDir() { return new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), Downloads.FOLDER); }

    /** Unfertige Reste dieses Tests: pending MediaStore-Einträge bzw. .part-Dateien und Besitz-Markierungen. */
    static int leftovers(String title) {
        int n = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            String folder = Environment.DIRECTORY_DOWNLOADS + "/" + Downloads.FOLDER + "/";
            try (Cursor c = ctx.getContentResolver().query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                    new String[] {MediaStore.Downloads._ID}, "owner_package_name=? AND is_pending=1 AND relative_path=? AND _display_name LIKE ?",
                    new String[] {ctx.getPackageName(), folder, "%--" + title + "%"}, null)) {
                if (c != null) n += c.getCount();
            }
        } else {
            File[] parts = legacyDir().listFiles();
            if (parts != null) for (File f : parts) if (f.getName().startsWith(".evp-pending-")) n++;
        }
        return n;
    }

    static int proofs() {
        File[] l = new File(ctx.getFilesDir(), "download-pending-v1").listFiles();
        return l == null ? 0 : l.length;
    }

    static String title(String what) { return "C2 " + what + " " + Long.toHexString(SystemClock.elapsedRealtimeNanos()); }

    // ------------------------------------------------------------ Tests

    /** Fertige Datei über MediaStore (API 29+) bzw. Medienscanner (API 26–28); Inhalt byte-genau; begrenzter Speicher. */
    @Test public void publishesThroughMediaStoreAndReadsBack() throws Exception {
        long total = 64L << 20;
        try (Server srv = new Server((line, h, out, i) -> serveFile(h, out, total, "\"v1\"", -1))) {
            engineFor(srv);
            String id = newId(), t = title("Publish");
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long heap0 = rt.totalMemory() - rt.freeMemory();
            long cpu0 = android.os.Process.getElapsedCpuTime();
            android.os.Debug.MemoryInfo m0 = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(m0);
            long t0 = SystemClock.elapsedRealtime();
            long[] peakHeap = {0};
            Thread sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peakHeap[0] = Math.max(peakHeap[0], rt.totalMemory() - rt.freeMemory());
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            });
            sampler.start();
            String started = engine.start(id, srv.url("/v.mp4"), t);
            assertTrue(started, started.contains("\"state\":\"running\""));
            assertEquals("done", events.await(id, 120_000));
            sampler.interrupt();
            long ms = SystemClock.elapsedRealtime() - t0, cpuMs = android.os.Process.getElapsedCpuTime() - cpu0;
            android.os.Debug.MemoryInfo m1 = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(m1);
            Object saved = events.saved.get(id);
            assertNotNull(saved);
            Uri uri = (Uri) saved;
            cleanup.add(uri);
            ContentResolver cr = ctx.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals("content", uri.getScheme());
                try (Cursor c = cr.query(uri, new String[] {MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING,
                        MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.SIZE, MediaStore.Downloads.MIME_TYPE}, null, null, null)) {
                    assertNotNull(c);
                    assertTrue(c.moveToFirst());
                    assertEquals(t + ".mp4", c.getString(0));
                    assertEquals(0, c.getInt(1));
                    assertEquals(Environment.DIRECTORY_DOWNLOADS + "/" + Downloads.FOLDER + "/", c.getString(2));
                    assertEquals(total, c.getLong(3));
                    assertEquals("video/mp4", c.getString(4));
                }
                try (InputStream in = cr.openInputStream(uri)) { assertArrayEquals(expectedSha(total), sha(in)); }
            } else {
                File f = new File(legacyDir(), t + ".mp4");
                cleanupFiles.add(f);
                assertEquals(total, f.length());
                try (InputStream in = new FileInputStream(f)) { assertArrayEquals(expectedSha(total), sha(in)); }
                Log.i(TAG, "MP4_LEGACY_SCAN sdk=" + Build.VERSION.SDK_INT + " scheme=" + uri.getScheme() + " mediaStore=" + uri.toString().startsWith("content://media/"));
            }
            assertEquals(0, leftovers(t));
            assertEquals(1, srv.requests.get());
            // Bleibender Heap nach GC (wie OKHTTP_RAM in C1); die Spitze enthält noch nicht eingesammelte Okio-Segmente
            // und wird nur protokolliert.
            System.gc();
            Runtime.getRuntime().runFinalization();
            System.gc();
            long heapGrowth = (rt.totalMemory() - rt.freeMemory()) - heap0;
            Log.i(TAG, String.format(Locale.ROOT, "MP4_MEASURE sdk=%d MiB=%d ms=%d MiBps=%.1f cpuMs=%d heapGrowthAfterGcKiB=%d heapPeakGrowthKiB=%d pssGrowthKiB=%d",
                Build.VERSION.SDK_INT, total >> 20, ms, (total / 1048576.0) / (ms / 1000.0), cpuMs, heapGrowth / 1024, (peakHeap[0] - heap0) / 1024,
                m1.getTotalPss() - m0.getTotalPss()));
            assertTrue("heap grew by " + heapGrowth / 1024 + " KiB", heapGrowth < 8L << 20);
            // Benachrichtigung: Titel und Text ohne URL, Host oder Token.
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            boolean seen = false;
            for (StatusBarNotification n : nm.getActiveNotifications()) {
                Bundle ex = n.getNotification().extras;
                String all = n.getTag() + " " + ex.getCharSequence("android.title") + " " + ex.getCharSequence("android.text");
                assertFalse(all, all.contains(SECRET) || all.contains("dl.test") || all.contains("http"));
                if (("mp4-" + id).equals(n.getTag())) seen = true;
            }
            if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) assertTrue("done notification posted", seen);
            nm.cancel("mp4-" + id, 0x4d50);
        }
    }

    /** Abbruch während des Rumpfs: kein sichtbarer oder pending Eintrag, keine Markierung, keine Teil-Datei. */
    @Test public void cancelRemovesEverything() throws Exception {
        long total = 256L << 20;
        try (Server srv = new Server((line, h, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total, "ETag: \"x\"");
            for (long p = 0; p < total; p += 64 << 10) { content(out, p, Math.min(total, p + (64 << 10))); out.flush(); Thread.sleep(5); }
        })) {
            engineFor(srv);
            int proofsBefore = proofs();
            String id = newId(), t = title("Cancel");
            engine.start(id, srv.url("/v.mp4"), t);
            long end = SystemClock.elapsedRealtime() + 20_000;
            while (leftovers(t) == 0 && SystemClock.elapsedRealtime() < end) Thread.sleep(20);
            assertEquals("pending entry exists while downloading", 1, leftovers(t));
            Thread.sleep(300);
            long t0 = SystemClock.elapsedRealtime();
            engine.cancel(id);
            assertEquals("cancelled", events.await(id, 10_000));
            long ms = SystemClock.elapsedRealtime() - t0;
            assertTrue("cancel took " + ms + " ms", ms < 5_000);
            assertEquals(0, leftovers(t));
            assertEquals(proofsBefore, proofs());
            assertEquals(1, srv.requests.get());
            assertTrue(engine.status(id).contains("\"state\":\"cancelled\""));
            Log.i(TAG, "MP4_CANCEL sdk=" + Build.VERSION.SDK_INT + " ms=" + ms);
        }
    }

    /** Unterbrechung ohne Validator: Fehler, Reste entfernt; mit ETag: Fortsetzen per Range, Inhalt identisch. */
    @Test public void interruptionFailsCleanOrResumesExactly() throws Exception {
        long total = 8L << 20;
        try (Server srv = new Server((line, h, out, i) -> serveFile(h, out, total, line.contains("/etag") ? "\"e\"" : null, h.get("range") == null ? 3L << 20 : -1))) {
            engineFor(srv);
            int proofsBefore = proofs();
            String a = newId(), ta = title("NoValidator");
            engine.start(a, srv.url("/plain"), ta);
            assertEquals("failed:network", events.await(a, 60_000));
            assertEquals(0, leftovers(ta));
            assertEquals(proofsBefore, proofs());
            String b = newId(), tb = title("Resume");
            engine.start(b, srv.url("/etag"), tb);
            assertEquals("done", events.await(b, 60_000));
            Uri uri = (Uri) events.saved.get(b);
            cleanup.add(uri);
            if (Build.VERSION.SDK_INT >= 29) {
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) { assertArrayEquals(expectedSha(total), sha(in)); }
            } else {
                File f = new File(legacyDir(), tb + ".mp4");
                cleanupFiles.add(f);
                try (InputStream in = new FileInputStream(f)) { assertArrayEquals(expectedSha(total), sha(in)); }
            }
            assertEquals(3, srv.requests.get());
        }
    }

    /** Zu wenig Speicher (echtes StatFs): Ablehnung vor jedem Eintrag. */
    @Test public void notEnoughSpaceFailsBeforeAnyEntry() throws Exception {
        long free = new android.os.StatFs(ctx.getExternalFilesDir(null).getPath()).getAvailableBytes();
        // Passt nicht mit Reserve (1 MiB zu viel); Server sendet nur den Anfang.
        long len = Math.min(DirectDownloads.MAX_FILE_BYTES, free - DirectDownloads.SPACE_RESERVE_BYTES + (1 << 20));
        try (Server srv = new Server((line, h, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + len);
            content(out, 0, 1 << 20);
        })) {
            engineFor(srv);
            assertTrue("free " + free, free > 0);
            int proofsBefore = proofs();
            String id = newId(), t = title("NoSpace");
            engine.start(id, srv.url("/v.mp4"), t);
            String r = events.await(id, 30_000);
            assertTrue(r, r.equals("failed:no_space") || (free > DirectDownloads.MAX_FILE_BYTES && r.equals("failed:too_large")));
            assertEquals(0, leftovers(t));
            assertEquals(proofsBefore, proofs());
            Log.i(TAG, "MP4_NOSPACE sdk=" + Build.VERSION.SDK_INT + " freeMiB=" + (free >> 20) + " result=" + r);
        }
    }

    /** Doppelte und verspätete Starts: ein Abruf; das Journal überdauert eine neue Engine (Neustart). */
    @Test public void duplicateAndLateStartsFetchOnce() throws Exception {
        try (Server srv = new Server((line, h, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 65536");
            content(out, 0, 8);
            out.flush();
            Thread.sleep(500);
            content(out, 8, 65536);
        })) {
            engineFor(srv);
            String id = newId(), t = title("Dup");
            engine.start(id, srv.url("/v.mp4"), t);
            engine.start(id, srv.url("/v.mp4"), t);
            engine.start(id, srv.url("/other.mp4"), "Other");
            assertEquals("done", events.await(id, 30_000));
            cleanup.add((Uri) events.saved.get(id));
            if (Build.VERSION.SDK_INT < 29) cleanupFiles.add(new File(legacyDir(), t + ".mp4"));
            assertTrue(engine.start(id, srv.url("/v.mp4"), t).contains("\"state\":\"done\""));
            DirectDownloads restarted = new DirectDownloads(DeviceTestClients.loopback(srv.ss.getLocalPort()), store, events);
            assertTrue(restarted.start(id, srv.url("/v.mp4"), t).contains("\"state\":\"done\""));
            Thread.sleep(300);
            assertEquals(1, srv.requests.get());
            restarted.stopAll();
        }
    }

    /** Prozessende mitten im Download: der nächste Prozessstart (Downloads.initialize) entfernt den Rest. */
    @Test public void leftoversAfterProcessDeathAreRemovedOnNextStart() throws Exception {
        try (Server srv = new Server((line, h, out, i) -> { })) {
            engineFor(srv);
            String t = title("Crash");
            DownloadStore.Target target = store.create(t + ".mp4", "video/mp4");
            target.out().write(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'});
            target.out().flush();
            assertEquals(1, leftovers(t));
            // "Neuer Prozess": die einmalige Bereinigung erneut ausführen (Schalter nur im Test zurückgesetzt).
            java.lang.reflect.Field f = Downloads.class.getDeclaredField("initialized");
            f.setAccessible(true);
            synchronized (Downloads.class) { f.setBoolean(null, false); }
            Downloads.initialize(ctx);
            assertEquals(0, leftovers(t));
            target.abort();
        }
    }
}
