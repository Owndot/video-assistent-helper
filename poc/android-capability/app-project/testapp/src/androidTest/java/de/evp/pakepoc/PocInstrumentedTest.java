package de.evp.pakepoc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import poc.Core;
import poc.TestInitiator;

/**
 * On-device (emulator or phone) tests of the isolated test app: Keystore
 * storage, monotonic/reboot handling, loopback pairing, Noise KK channel,
 * negative cases, R1/R2 reproduction, memory figures and a log scan.
 * Throwaway identities and codes only; no real media, cookies or URLs.
 */
@RunWith(AndroidJUnit4.class)
public class PocInstrumentedTest {
    private static final String TAG = "PAKEPOC_TEST";
    private static final List<String> SECRETS = new ArrayList<>();   // must never appear in logcat
    private static Context ctx;
    private static Activity activity;

    static long now() { return SystemClock.elapsedRealtime(); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    @BeforeClass public static void startApp() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        ctx = ins.getTargetContext();
        activity = ins.startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ctx.startForegroundService(new Intent(ctx, PocService.class).setAction(PocService.A_START));
        waitFor(() -> PocService.instance != null && PocService.instance.serverState.startsWith("listening"), 10_000);
        // The helper's own private key must not reach logcat either.
        IdentityStore.Result helper = new IdentityStore(ctx.getFilesDir(), PocService.WRAP_ALIAS, ctx.getPackageName()).load(false);
        assertEquals(IdentityStore.State.OK, helper.state);
        SECRETS.add(hex(Arrays.copyOf(helper.identity, 32)));
        Arrays.fill(helper.identity, (byte) 0);
        Log.i(TAG, "POC_MEASURE start " + Measurements.snapshot());
    }

    @AfterClass public static void scanLogcat() throws Exception {
        String logcat = shell("logcat -d");
        for (String s : SECRETS) assertFalse("secret material found in logcat", logcat.contains(s));
        assertTrue("log scan saw our own test output", logcat.contains("POC_MEASURE"));
    }

    interface Cond { boolean ok() throws Exception; }

    static void waitFor(Cond c, long ms) throws Exception {
        long end = now() + ms;
        while (now() < end) { if (c.ok()) return; Thread.sleep(50); }
        fail("timeout");
    }

    static String shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static final class Http { int code; byte[] body; }

    static Http http(String method, int port, String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        Http r = new Http();
        r.code = c.getResponseCode();
        try (InputStream in = r.code == 200 ? c.getInputStream() : c.getErrorStream()) { r.body = in == null ? new byte[0] : readAll(in); }
        c.disconnect();
        return r;
    }

    /** Map.of needs API 30; minSdk is 29. */
    static Map<String, String> hdr(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static byte[] post(String path, byte[] body) throws Exception {
        Http r = http("POST", 47899, path, body, hdr("Content-Type", "application/octet-stream", "X-Kadri-Helper", "2"));
        if (r.code != 200) throw new IllegalStateException("HTTP " + r.code);
        return r.body;
    }

    /** A Kadri-side test identity: private(32) || public(32). */
    static byte[] kadriIdentity() {
        byte[] id = Core.identityGenerate();
        SECRETS.add(hex(Arrays.copyOf(id, 32)));
        return id;
    }

    static String openWindow() {
        String code = PocService.instance.openWindow();
        assertNotNull(code);
        SECRETS.add(code);
        return code;
    }

    /** Returns registration_id(16) || helper_public(32). */
    static byte[] pair(byte[] id, String code) throws Exception {
        long h = TestInitiator.pairStart(id, code, now(), 60_000);
        try {
            byte[] m1 = post("/p1/pair/m0", TestInitiator.m0(h));
            byte[] m3 = post("/p1/pair/m2", TestInitiator.onM1(h, m1, now()));
            return TestInitiator.onM3(h, m3, now());
        } finally {
            TestInitiator.free(h);
        }
    }

    static long connect(byte[] id, byte[] pin) throws Exception {
        long h = TestInitiator.handshakeStart(id, Arrays.copyOfRange(pin, 16, 48), Arrays.copyOf(pin, 16));
        TestInitiator.finish(h, post("/p1/ch/hs", TestInitiator.hs1(h)));
        return h;
    }

    static byte[] request(long h, String json) throws Exception {
        byte[] rec = TestInitiator.sealControl(h, json.getBytes(StandardCharsets.UTF_8));
        return TestInitiator.open(h, post("/p1/ch/rec", rec));
    }

    /** First authenticated round trip confirms the pending registration. */
    static long pairAndConfirm(byte[] id, byte[] pin) throws Exception {
        long h = connect(id, pin);
        byte[] r = request(h, "{\"op\":\"ping\"}");
        assertEquals(1, r[0]);
        assertTrue(new String(r, 1, r.length - 1, StandardCharsets.UTF_8).contains("android-test-app"));
        return h;
    }

    static void restartService() throws Exception {
        restartService(() -> PocService.instance.serverState.startsWith("listening"));
    }

    static void restartService(Cond expected) throws Exception {
        ctx.stopService(new Intent(ctx, PocService.class));
        waitFor(() -> PocService.instance == null, 10_000);
        ctx.startForegroundService(new Intent(ctx, PocService.class).setAction(PocService.A_START));
        waitFor(() -> PocService.instance != null && expected.ok(), 10_000);
    }

    /** Waits until t finished or is stably BLOCKED on a monitor (200 ms). */
    static void waitBlockedOrDone(Thread t) throws Exception {
        long end = now() + 5000, since = -1;
        while (now() < end && t.isAlive()) {
            if (t.getState() == Thread.State.BLOCKED) {
                if (since < 0) since = now();
                else if (now() - since > 200) return;
            } else since = -1;
            Thread.sleep(10);
        }
    }

    @Before public void clean() {
        assertTrue("revocation saved", PocService.instance.revokeAll());
    }

    // ------------------------------------------------------------ storage

    static File identityFile(File dir) { return new File(dir, "identity.v1"); }

    static boolean keyExists(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks.containsAlias(alias);
    }

    /** F2: every file/key combination gives its defined state; nothing rotates implicitly. */
    @Test public void identityStateMachineNeverRotatesSilently() throws Exception {
        File dir = new File(ctx.getCacheDir(), "idtest-" + now());
        assertTrue(dir.mkdirs());
        String alias = "pakepoc-instrumentation-" + now();
        IdentityStore s = new IdentityStore(dir, alias, ctx.getPackageName());

        // file missing, key missing, first start not allowed (registrations exist): explicit error, nothing created
        assertEquals(IdentityStore.State.ERROR_INCONSISTENT, s.load(false).state);
        assertFalse(identityFile(dir).exists());
        assertFalse(keyExists(alias));

        // file missing, key missing, first start: new identity
        IdentityStore.Result a = s.load(true);
        assertEquals(IdentityStore.State.CREATED, a.state);
        byte[] file = readAll(new FileInputStream(identityFile(dir)));
        assertFalse("private key bytes must not be stored in clear", hex(file).contains(hex(Arrays.copyOf(a.identity, 32))));

        // file present, key present, authenticated: the same identity, whatever the caller allows
        for (boolean allow : new boolean[]{true, false}) {
            IdentityStore.Result b = s.load(allow);
            assertEquals(IdentityStore.State.OK, b.state);
            assertArrayEquals(a.identity, b.identity);
            Arrays.fill(b.identity, (byte) 0);
        }

        // file missing, key present: inconsistency, no rotation, key kept
        assertTrue(identityFile(dir).delete());
        assertEquals(IdentityStore.State.ERROR_INCONSISTENT, s.load(true).state);
        assertFalse("no new identity file written", identityFile(dir).exists());
        assertTrue("Keystore key not deleted", keyExists(alias));
        IdentityStore.writeAtomically(identityFile(dir), file);
        IdentityStore.Result back = s.load(true);
        assertEquals(IdentityStore.State.OK, back.state);
        assertArrayEquals("restored file gives the old identity", a.identity, back.identity);
        Arrays.fill(back.identity, (byte) 0);

        // GCM authentication fails (flipped tag bit, flipped ciphertext bit): explicit error, file untouched
        for (int pos : new int[]{file.length - 1, 20}) {
            byte[] bad = file.clone();
            bad[pos] ^= 1;
            IdentityStore.writeAtomically(identityFile(dir), bad);
            assertEquals(IdentityStore.State.ERROR_CORRUPT, s.load(true).state);
            assertArrayEquals(bad, readAll(new FileInputStream(identityFile(dir))));
        }
        // wrong format (truncated) and wrong binding (other package name in the AAD)
        IdentityStore.writeAtomically(identityFile(dir), Arrays.copyOf(file, file.length - 3));
        assertEquals(IdentityStore.State.ERROR_CORRUPT, s.load(true).state);
        IdentityStore.writeAtomically(identityFile(dir), file);
        assertEquals(IdentityStore.State.ERROR_CORRUPT, new IdentityStore(dir, alias, "other.package").load(true).state);
        assertEquals(IdentityStore.State.OK, s.load(true).state);

        // file present, key missing (e.g. data restored on another device): explicit error, file kept
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(alias);
        assertEquals(IdentityStore.State.ERROR_KEY_MISSING, s.load(true).state);
        assertArrayEquals(file, readAll(new FileInputStream(identityFile(dir))));

        // Only an explicit reset creates a new identity.
        s.reset();
        IdentityStore.Result c = s.load(true);
        assertEquals(IdentityStore.State.CREATED, c.state);
        assertFalse(Arrays.equals(a.identity, c.identity));
        Arrays.fill(a.identity, (byte) 0);
        Arrays.fill(c.identity, (byte) 0);
        Log.i(TAG, "POC_KEYSTORE " + s.wrappingKeyInfo());
        s.reset();
    }

    /** F2 end to end: a lost identity file stops the server and leaves pairings untouched. */
    @Test public void lostIdentityFileStopsTheServerAndKeepsPairings() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        TestInitiator.free(pairAndConfirm(id, pin));
        File f = identityFile(ctx.getFilesDir());
        File reg = new File(ctx.getFilesDir(), "registrations.v1");
        byte[] saved = readAll(new FileInputStream(f));
        byte[] regBefore = readAll(new FileInputStream(reg));
        assertTrue(f.delete());
        restartService(() -> PocService.instance.identityState.startsWith("ERROR_INCONSISTENT"));
        assertFalse(PocService.instance.serverState.startsWith("listening"));
        assertFalse("no replacement identity", f.exists());
        assertTrue("Keystore key kept", keyExists(PocService.WRAP_ALIAS));
        assertArrayEquals("registrations untouched", regBefore, readAll(new FileInputStream(reg)));
        IdentityStore.writeAtomically(f, saved);
        restartService();
        TestInitiator.free(pairAndConfirm(id, pin));   // the existing pairing still works
    }

    @Test public void registryStoreDetectsReboot() throws Exception {
        File dir = new File(ctx.getCacheDir(), "regtest-" + now());
        assertTrue(dir.mkdirs());
        new RegistryStore(dir, 5).save(new byte[57]);
        assertTrue(new RegistryStore(dir, 5).load().sameBoot);
        assertFalse(new RegistryStore(dir, 6).load().sameBoot);
        IdentityStore.writeAtomically(new File(dir, "registrations.v1"), "XXXX".getBytes(StandardCharsets.US_ASCII));
        try { new RegistryStore(dir, 5).load(); fail("corrupt registry accepted"); } catch (IllegalStateException expected) {}
    }

    // ------------------------------------------------------------ protocol over real loopback

    @Test public void pairingHandshakePingAndBoundedDummyRecords() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long h = pairAndConfirm(id, pin);
        Log.i(TAG, "POC_MEASURE before " + Measurements.snapshot());
        AtomicInteger writes = new AtomicInteger();
        PocService.instance.writer.testHook = snap -> writes.incrementAndGet();
        long size = 8L << 20, offset = 0, records = 0, t0 = now();
        while (true) {
            byte[] r = request(h, "{\"op\":\"dummy\",\"transfer\":1,\"size\":" + size + ",\"offset\":" + offset + "}");
            ByteBuffer bb = ByteBuffer.wrap(r);
            if (r[0] == 3) { assertEquals(offset, bb.getLong(5)); break; }
            assertEquals(2, r[0]);
            assertEquals(offset, bb.getLong(5));
            int n = r.length - 13;
            assertTrue(n > 0 && n <= 61440);
            for (int i = 0; i < n; i++) assertEquals((byte) (((offset + i) * 31 + 8) & 0xff), r[13 + i]);
            offset += n;
            records++;
        }
        assertEquals(size, offset);
        long ms = now() - t0;
        PocService.instance.writer.testHook = null;
        assertEquals("F4: no registry write while streaming records", 0, writes.get());
        Log.i(TAG, String.format(Locale.ROOT, "POC_THROUGHPUT bytes=%d records=%d ms=%d MiBps=%.1f", size, records, ms, size / 1048576.0 / (ms / 1000.0)));
        Log.i(TAG, "POC_MEASURE after " + Measurements.snapshot());
        TestInitiator.free(h);
    }

    @Test public void wrongCodeFailsAndBurnsTheWindow() throws Exception {
        byte[] id = kadriIdentity();
        String code = openWindow();
        String wrong = (code.charAt(0) == '9' ? "0" : String.valueOf((char) (code.charAt(0) + 1))) + code.substring(1);
        try { pair(id, wrong); fail("wrong code accepted"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        try { pair(id, code); fail("burned window accepted a second attempt"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
    }

    @Test public void wrongPinIsRejectedByTheGenuineHelper() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        pairAndConfirm(id, pin);
        byte[] wrongPin = pin.clone();
        System.arraycopy(Core.identityGenerate(), 32, wrongPin, 16, 32);
        try { connect(id, wrongPin); fail("handshake with wrong pinned key succeeded"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        pairAndConfirm(id, pin); // correct pin still works
    }

    @Test public void browserGateRefusesWebLikeRequests() throws Exception {
        byte[] body = new byte[37];
        assertEquals(403, http("POST", 47899, "/p1/pair/m0", body, hdr("Content-Type", "application/octet-stream")).code);
        assertEquals(403, http("POST", 47899, "/p1/pair/m0", body, hdr("Content-Type", "text/plain", "X-Kadri-Helper", "2")).code);
        assertEquals(403, http("POST", 47899, "/p1/pair/m0", body, hdr("Content-Type", "application/octet-stream", "X-Kadri-Helper", "2", "Origin", "https://evil.example")).code);
        assertEquals(403, http("GET", 47899, "/p1/pair/m0", null, hdr()).code);
        assertEquals(403, http("OPTIONS", 47899, "/p1/pair/m0", null, hdr("Origin", "https://evil.example")).code);
        Http page = http("GET", 47900, "/kadri-poc-probe.html", null, hdr());
        assertEquals(200, page.code);
        assertTrue(new String(page.body, StandardCharsets.UTF_8).contains("TEST-SEITE"));
        assertEquals(404, http("GET", 47900, "/p1/pair/m0", null, hdr()).code);
        assertEquals(404, http("POST", 47900, "/p1/pair/m0", body, hdr("Content-Type", "application/octet-stream", "X-Kadri-Helper", "2")).code);
    }

    @Test public void foreignListenerReceivesCiphertextOnlyAndSessionIsDropped() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long live = pairAndConfirm(id, pin);
        ctx.stopService(new Intent(ctx, PocService.class));
        waitFor(() -> PocService.instance == null, 10_000);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (ServerSocket rogue = new ServerSocket()) {
            rogue.setReuseAddress(true);
            rogue.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 47899));
            Thread t = new Thread(() -> {
                try (Socket s = rogue.accept()) {
                    s.setSoTimeout(3000);
                    byte[] buf = new byte[70000];
                    int n = s.getInputStream().read(buf);
                    captured.write(buf, 0, Math.max(n, 0));
                    byte[] forged = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + forged.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    s.getOutputStream().write(forged);
                } catch (Exception ignored) {}
            });
            t.start();
            try { request(live, "{\"op\":\"ping\",\"note\":\"SECRET-MARKER-41\"}"); fail("forged reply accepted"); }
            catch (SecurityException expected) { /* Malformed: not a channel record */ }
            t.join(5000);
            try { request(live, "{\"op\":\"ping\"}"); fail("dead session reused"); }
            catch (SecurityException expected) { assertEquals("WrongState", expected.getMessage()); }
        }
        String seen = new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue("rogue saw the request", seen.contains("POST /p1/ch/rec"));
        assertFalse(seen.contains("SECRET-MARKER-41"));
        assertFalse(seen.contains("\"op\""));
        assertFalse(seen.toLowerCase(Locale.ROOT).contains("x-evp-key"));
        restartService();
        pairAndConfirm(id, pin); // persisted registration, fresh handshake
    }

    @Test public void restartKeepsRegistrationsButNotSessions() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long old = pairAndConfirm(id, pin);
        restartService();
        try { request(old, "{\"op\":\"ping\"}"); fail("old session survived restart"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        pairAndConfirm(id, pin);
    }

    @Test public void revocationAndRePairing() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long live = pairAndConfirm(id, pin);
        PocService.instance.revokeAll();
        try { request(live, "{\"op\":\"ping\"}"); fail("revoked session still works"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        try { connect(id, pin); fail("revoked registration handshakes"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        byte[] pin2 = pair(id, openWindow());
        assertFalse(Arrays.equals(Arrays.copyOf(pin, 16), Arrays.copyOf(pin2, 16)));
        pairAndConfirm(id, pin2);
    }

    /** R1 – known availability risk, reproduced and measured, not fixed. */
    @Test public void r1ReplayedHs1EvictsAFreshHandshake() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        pairAndConfirm(id, pin);
        long h = TestInitiator.handshakeStart(id, Arrays.copyOfRange(pin, 16, 48), Arrays.copyOf(pin, 16));
        byte[] hs1 = TestInitiator.hs1(h);
        TestInitiator.finish(h, post("/p1/ch/hs", hs1));
        long t0 = now();
        for (int i = 0; i < 2; i++) post("/p1/ch/hs", hs1);   // attacker replays the recorded HS1
        try { request(h, "{\"op\":\"ping\"}"); fail("expected eviction"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        long recover = now();
        pairAndConfirm(id, pin);
        Log.i(TAG, String.format(Locale.ROOT, "POC_R1 replays=2 evicted=true replayMs=%d recoveryHandshakeMs=%d", recover - t0, now() - recover));
    }

    /** R2 – known availability risk: a known session id allows termination only. */
    @Test public void r2KnownSessionIdAllowsTerminationNotTakeover() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long h = connect(id, pin);
        byte[] rec = TestInitiator.sealControl(h, "{\"op\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        byte[] garbage = Arrays.copyOf(rec, 21 + 40);
        Arrays.fill(garbage, 21, garbage.length, (byte) 0);
        try { post("/p1/ch/rec", garbage); fail("garbage accepted"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        try { TestInitiator.open(h, post("/p1/ch/rec", rec)); fail("terminated session continued"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        pairAndConfirm(id, pin);
    }

    // ------------------------------------------------------------ review F1, F3, F5

    /**
     * F1 regression, deterministic: A is confirmed and saved. A parallel
     * pairing of B exports {A, B} and is held right before its write. All
     * registrations are then revoked. The revocation must not report success
     * before the older write is done, and after a restart A (and B) stay revoked.
     */
    @Test public void f1StaleSnapshotCannotUndoRevocationAcrossRestart() throws Exception {
        byte[] idA = kadriIdentity();
        byte[] pinA = pair(idA, openWindow());
        TestInitiator.free(pairAndConfirm(idA, pinA));
        SnapshotWriter w = PocService.instance.writer;
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        w.testHook = snap -> {
            if (armed.compareAndSet(true, false)) {
                reached.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS));
            }
        };
        byte[] idB = kadriIdentity();
        String codeB = openWindow();
        AtomicReference<byte[]> pinB = new AtomicReference<>();
        Thread stale = new Thread(() -> { try { pinB.set(pair(idB, codeB)); } catch (Exception ignored) {} });
        stale.start();
        assertTrue("stale snapshot exported", reached.await(20, TimeUnit.SECONDS));
        AtomicReference<Boolean> revoked = new AtomicReference<>();
        Thread revoke = new Thread(() -> revoked.set(PocService.instance.revokeAll()));
        revoke.start();
        waitBlockedOrDone(revoke);
        assertNull("revocation reported saved while an older write was pending", revoked.get());
        release.countDown();
        stale.join(20_000);
        revoke.join(20_000);
        w.testHook = null;
        assertEquals(Boolean.TRUE, revoked.get());
        restartService();
        assertTrue(PocService.instance.status(), PocService.instance.status().contains("registrations=0"));
        try { connect(idA, pinA); fail("revoked A handshakes after restart"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        if (pinB.get() != null) {
            try { connect(idB, pinB.get()); fail("B paired before the revocation survived it"); }
            catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        }
    }

    /**
     * F3: 24 slow local clients (one header byte every 250 ms) cannot keep
     * the 4 workers busy. All are dropped within the read budget, and a
     * legitimate handshake + ping is served again right after.
     */
    @Test public void f3SlowClientsAreDroppedAndLegitimateRequestsServed() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        TestInitiator.free(pairAndConfirm(id, pin));
        final int n = 24;
        long[] dropped = new long[n];
        Thread[] attackers = new Thread[n];
        long t0 = now();
        for (int i = 0; i < n; i++) {
            final int k = i;
            attackers[i] = new Thread(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 47899), 2000);
                    s.setSoTimeout(1);
                    OutputStream o = s.getOutputStream();
                    InputStream in = s.getInputStream();
                    o.write("POST /p1/ch/rec HTTP/1.1\r\nHost: 127.0.0.1:47899\r\nX-Slow: ".getBytes(StandardCharsets.ISO_8859_1));
                    while (now() - t0 < 20_000) {
                        o.write('a');
                        o.flush();
                        try { in.read(); break; }                      // reply or EOF: dropped by the server
                        catch (java.net.SocketTimeoutException still) { Thread.sleep(250); }
                    }
                } catch (Exception closed) { /* reset: dropped */ }
                dropped[k] = now() - t0;
            });
            attackers[i].start();
        }
        Thread.sleep(500);
        long legitStart = now();
        int attempts = 0;
        boolean served = false;
        while (!served && now() - legitStart < 15_000) {
            attempts++;
            try {
                long h = connect(id, pin);
                served = request(h, "{\"op\":\"ping\"}")[0] == 1;
                TestInitiator.free(h);
            } catch (Exception busy) { Thread.sleep(100); }
        }
        long servedAfter = now() - t0;
        long maxDrop = 0;
        for (int i = 0; i < n; i++) { attackers[i].join(25_000); maxDrop = Math.max(maxDrop, dropped[i]); }
        Log.i(TAG, String.format(Locale.ROOT, "POC_SLOWCLIENT attackers=%d budgetMs=%d maxDropMs=%d legitServedAfterMs=%d attempts=%d",
            n, HttpRequestReader.READ_BUDGET_MS, maxDrop, servedAfter, attempts));
        assertTrue("legitimate request served under slow-client load", served);
        assertTrue("legitimate request served within budget + 4 s, took " + servedAfter, servedAfter < HttpRequestReader.READ_BUDGET_MS + 4000);
        assertTrue("all slow clients dropped within budget + 3 s, max " + maxDrop, maxDrop < HttpRequestReader.READ_BUDGET_MS + 3000);
    }

    /** F5: code screen not captured for Recents/screenshots; no backup or device transfer. */
    @Test public void f5ScreenAndBackupHardening() {
        assertTrue("FLAG_SECURE", (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertEquals("allowBackup=false", 0, ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        assertEquals("de.evp.pakepoc.test", ctx.getPackageName());
    }
}
