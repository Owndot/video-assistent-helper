package de.evp.pakepoc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

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

    static long now() { return SystemClock.elapsedRealtime(); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    @BeforeClass public static void startApp() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        ctx = ins.getTargetContext();
        ins.startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ctx.startForegroundService(new Intent(ctx, PocService.class).setAction(PocService.A_START));
        waitFor(() -> PocService.instance != null && PocService.instance.serverState.startsWith("listening"), 10_000);
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
        ctx.stopService(new Intent(ctx, PocService.class));
        waitFor(() -> PocService.instance == null, 10_000);
        ctx.startForegroundService(new Intent(ctx, PocService.class).setAction(PocService.A_START));
        waitFor(() -> PocService.instance != null && PocService.instance.serverState.startsWith("listening"), 10_000);
    }

    @Before public void clean() {
        PocService.instance.revokeAll();
    }

    // ------------------------------------------------------------ storage

    @Test public void keystoreWrappedIdentityAndExplicitFailureStates() throws Exception {
        File dir = new File(ctx.getCacheDir(), "idtest-" + now());
        assertTrue(dir.mkdirs());
        String alias = "pakepoc-instrumentation-" + now();
        IdentityStore s = new IdentityStore(dir, alias, ctx.getPackageName());
        IdentityStore.Result a = s.load();
        assertEquals(IdentityStore.State.CREATED, a.state);
        IdentityStore.Result b = s.load();
        assertEquals(IdentityStore.State.OK, b.state);
        assertArrayEquals(a.identity, b.identity);
        byte[] file = readAll(new FileInputStream(new File(dir, "identity.v1")));
        assertFalse("private key bytes must not be stored in clear", hex(file).contains(hex(Arrays.copyOf(a.identity, 32))));
        // Tampered file: explicit error, nothing regenerated.
        byte[] bad = file.clone();
        bad[bad.length - 1] ^= 1;
        IdentityStore.writeAtomically(new File(dir, "identity.v1"), bad);
        assertEquals(IdentityStore.State.ERROR_CORRUPT, s.load().state);
        assertArrayEquals(bad, readAll(new FileInputStream(new File(dir, "identity.v1"))));
        IdentityStore.writeAtomically(new File(dir, "identity.v1"), file);
        // Keystore key lost (e.g. data restored elsewhere): explicit error.
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(alias);
        assertEquals(IdentityStore.State.ERROR_KEY_MISSING, s.load().state);
        // Only an explicit reset creates a new identity.
        s.reset();
        IdentityStore.Result c = s.load();
        assertEquals(IdentityStore.State.CREATED, c.state);
        assertFalse(Arrays.equals(a.identity, c.identity));
        Arrays.fill(a.identity, (byte) 0); Arrays.fill(b.identity, (byte) 0); Arrays.fill(c.identity, (byte) 0);
        s.reset();
        Log.i(TAG, "POC_KEYSTORE " + s.wrappingKeyInfo());
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
}
