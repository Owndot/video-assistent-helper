package de.evp.helfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import poc.Core;
import poc.TestInitiator;

/**
 * Gerätetests (Emulator) des Steuerkanals in der echten Helper-App, Paket de.evp.helfer.channeltest:
 * Keystore-Identität, Kopplung, Noise-KK-Sitzung, Steuerbefehle, Einzel- und Gesamtwiderruf, Neustart,
 * Browser-Schutz, fremder Dienst auf dem Port, langsame Clients, kein Dienst auf dem alten Port 47811,
 * Bildschirm- und Backup-Schutz, Download-Befehle (Phase C2) und eine Logcat-Prüfung auf Geheimnisse.
 * Nur Wegwerf-Identitäten und -Codes; keine Medien, Cookies oder URLs.
 */
@RunWith(AndroidJUnit4.class)
public class ChannelInstrumentedTest {
    private static final String TAG = "CHANNEL_TEST";
    private static final List<String> SECRETS = new ArrayList<>();   // dürfen nie im Logcat stehen
    private static Context ctx;
    private static MainActivity activity;

    static long now() { return SystemClock.elapsedRealtime(); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
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

    @BeforeClass public static void startApp() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        ctx = ins.getTargetContext();
        activity = (MainActivity) ins.startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        HelferService.start(ctx);
        waitFor(() -> HelferService.instance != null, 10_000);
        // Auch der eigene Schlüssel des Helpers darf nicht im Logcat landen.
        IdentityStore.Result helper = new IdentityStore(ctx.getFilesDir(), HelferService.WRAP_ALIAS, ctx.getPackageName()).load(false);
        assertEquals(IdentityStore.State.OK, helper.state);
        SECRETS.add(hex(Arrays.copyOf(helper.identity, 32)));
        Arrays.fill(helper.identity, (byte) 0);
        Log.i(TAG, "CHANNEL_MEASURE start " + memory());
    }

    @AfterClass public static void scanLogcat() throws Exception {
        Log.i(TAG, "CHANNEL_MEASURE end " + memory());
        String logcat = shell("logcat -d");
        for (String s : SECRETS) assertFalse("secret material found in logcat", logcat.contains(s));
        assertTrue("log scan saw our own test output", logcat.contains("CHANNEL_MEASURE"));
    }

    static String memory() {
        Runtime rt = Runtime.getRuntime();
        android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(mi);
        return String.format(Locale.ROOT, "pssKiB=%d javaHeapKiB=%d nativeHeapKiB=%d threads=%d",
            mi.getTotalPss(), (rt.totalMemory() - rt.freeMemory()) / 1024, android.os.Debug.getNativeHeapAllocatedSize() / 1024, Thread.activeCount());
    }

    // ------------------------------------------------------------ Protokoll-Helfer

    static final class Http { int code; byte[] body; }

    /** Rohe HTTP-Anfrage an 127.0.0.1:port; Host und Origin frei wählbar. */
    static Http raw(int port, String method, String path, String headers, byte[] body) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            s.setSoTimeout(5000);
            OutputStream o = s.getOutputStream();
            o.write((method + " " + path + " HTTP/1.1\r\n" + headers + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            o.write(body);
            o.flush();
            byte[] all = readAll(s.getInputStream());
            String text = new String(all, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            Http r = new Http();
            r.code = Integer.parseInt(text.substring(9, 12));
            r.body = Arrays.copyOfRange(all, split + 4, all.length);
            return r;
        }
    }

    static final String GOOD = "Host: 127.0.0.1:47899\r\nContent-Type: application/octet-stream\r\nX-Kadri-Helper: 2\r\n";

    static Http raw(String path, byte[] body) throws Exception { return raw(47899, "POST", path, GOOD, body); }

    static byte[] post(String path, byte[] body) throws Exception {
        Http r = raw(path, body);
        if (r.code != 200) throw new IllegalStateException("HTTP " + r.code);
        return r.body;
    }

    /** Kadri-seitige Wegwerf-Identität: private(32) || public(32). */
    static byte[] kadriIdentity() {
        byte[] id = Core.identityGenerate();
        SECRETS.add(hex(Arrays.copyOf(id, 32)));
        return id;
    }

    static String openWindow() {
        String code = HelferService.instance.openPairingWindow();
        assertNotNull(code);
        SECRETS.add(code);
        return code;
    }

    /** registration_id(16) || helper_public(32) */
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

    static String request(long h, String json) throws Exception {
        byte[] r = TestInitiator.open(h, post("/p1/ch/rec", TestInitiator.sealControl(h, json.getBytes(StandardCharsets.UTF_8))));
        assertEquals("control reply", 1, r[0]);
        return new String(r, 1, r.length - 1, StandardCharsets.UTF_8);
    }

    /** Die erste authentisierte Anfrage bestätigt die Registrierung. */
    static long pairAndConfirm(byte[] id, byte[] pin) throws Exception {
        long h = connect(id, pin);
        assertTrue(request(h, "{\"op\":\"status\"}").startsWith("{\"ok\":true,\"v\":3,\"dl\":false,"));
        return h;
    }

    static void restartService() throws Exception {
        restartService(() -> HelferService.instance != null);
    }

    static void restartService(Cond expected) throws Exception {
        ctx.stopService(new Intent(ctx, HelferService.class));
        waitFor(() -> HelferService.instance == null && !HelferService.running, 10_000);
        HelferService.start(ctx);
        waitFor(expected, 10_000);
    }

    @Before public void clean() throws Exception {
        if (HelferService.instance == null) restartService();
        assertTrue("revocation saved", HelferService.instance.revoke(null));
    }

    static File identityFile(File dir) { return new File(dir, "identity.v1"); }

    static boolean keyExists(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks.containsAlias(alias);
    }

    // ------------------------------------------------------------ Speicher (F2, R3)

    /** F2: jede Datei-/Schlüssel-Kombination ergibt ihren Zustand; nichts rotiert still. */
    @Test public void identityStateMachineNeverRotatesSilently() throws Exception {
        File dir = new File(ctx.getCacheDir(), "idtest-" + now());
        assertTrue(dir.mkdirs());
        String alias = "helfer-channel-instrumentation-" + now();
        IdentityStore s = new IdentityStore(dir, alias, ctx.getPackageName());
        assertEquals(IdentityStore.State.ERROR_INCONSISTENT, s.load(false).state);
        assertFalse(identityFile(dir).exists());
        assertFalse(keyExists(alias));
        IdentityStore.Result a = s.load(true);
        assertEquals(IdentityStore.State.CREATED, a.state);
        byte[] file = readAll(new FileInputStream(identityFile(dir)));
        assertFalse("private key bytes must not be stored in clear", hex(file).contains(hex(Arrays.copyOf(a.identity, 32))));
        for (boolean allow : new boolean[]{true, false}) {
            IdentityStore.Result b = s.load(allow);
            assertEquals(IdentityStore.State.OK, b.state);
            assertArrayEquals(a.identity, b.identity);
            Arrays.fill(b.identity, (byte) 0);
        }
        assertTrue(identityFile(dir).delete());
        assertEquals(IdentityStore.State.ERROR_INCONSISTENT, s.load(true).state);
        assertFalse("no new identity file written", identityFile(dir).exists());
        assertTrue("Keystore key not deleted", keyExists(alias));
        IdentityStore.writeAtomically(identityFile(dir), file);
        for (int pos : new int[]{file.length - 1, 20}) {
            byte[] bad = file.clone();
            bad[pos] ^= 1;
            IdentityStore.writeAtomically(identityFile(dir), bad);
            assertEquals(IdentityStore.State.ERROR_CORRUPT, s.load(true).state);
        }
        IdentityStore.writeAtomically(identityFile(dir), file);
        assertEquals("AAD binds the package", IdentityStore.State.ERROR_CORRUPT, new IdentityStore(dir, alias, "de.evp.helfer").load(true).state);
        assertEquals(IdentityStore.State.OK, s.load(true).state);
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(alias);
        assertEquals(IdentityStore.State.ERROR_KEY_MISSING, s.load(true).state);
        s.reset();
        IdentityStore.Result c = s.load(true);
        assertEquals(IdentityStore.State.CREATED, c.state);
        assertFalse(Arrays.equals(a.identity, c.identity));
        Arrays.fill(a.identity, (byte) 0);
        Arrays.fill(c.identity, (byte) 0);
        s.reset();
    }

    /** F2 Ende zu Ende: fehlt die Identitätsdatei, startet der Kanal nicht; Kopplungen bleiben, keine neue Identität. */
    @Test public void lostIdentityFileStopsTheChannelAndKeepsPairings() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        TestInitiator.free(pairAndConfirm(id, pin));
        File f = identityFile(ctx.getFilesDir());
        File reg = new File(ctx.getFilesDir(), "registrations.v1");
        byte[] saved = readAll(new FileInputStream(f));
        byte[] regBefore = readAll(new FileInputStream(reg));
        assertTrue(f.delete());
        restartService(() -> HelferService.startupError != null);
        assertNull("service must not run without its identity", HelferService.instance);
        assertTrue(HelferService.startupError, HelferService.startupError.contains("ERROR_INCONSISTENT"));
        assertFalse("no replacement identity", f.exists());
        assertTrue("Keystore key kept", keyExists(HelferService.WRAP_ALIAS));
        assertArrayEquals("registrations untouched", regBefore, readAll(new FileInputStream(reg)));
        IdentityStore.writeAtomically(f, saved);
        restartService();
        TestInitiator.free(pairAndConfirm(id, pin));
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

    // ------------------------------------------------------------ Kopplung und Steuerbefehle

    @Test public void pairingAndControlCommands() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        assertFalse("pending until the first authenticated record", HelferService.instance.registrations().get(0).confirmed);
        long h = pairAndConfirm(id, pin);
        assertTrue(HelferService.instance.registrations().get(0).confirmed);
        long t0 = now();
        String s = request(h, "{\"op\":\"bright\",\"v\":0.5}");
        assertTrue(s, s.startsWith("{\"ok\":true,\"v\":3,\"dl\":false,\"dash\":false,\"proxy\":false,\"app\":\""));
        assertTrue(s, s.contains("-channeltest\""));
        assertTrue(request(h, "{\"op\":\"vol\",\"v\":0.2}").startsWith("{\"ok\":true"));
        assertTrue(request(h, "{\"op\":\"vol\"}").startsWith("{\"ok\":true"));
        assertTrue(request(h, "{\"op\":\"beat\"}").startsWith("{\"ok\":true"));
        assertEquals("{\"ok\":true}", request(h, "{\"op\":\"end\"}"));
        long perCommand = (now() - t0) / 5;
        // Ungültig: abgelehnt, Sitzung bleibt nutzbar.
        assertEquals("{\"ok\":false}", request(h, "{\"op\":\"status\",\"url\":\"abc\"}"));
        assertEquals("{\"ok\":false}", request(h, "{\"op\":\"dl\"}"));
        assertEquals("{\"ok\":false}", request(h, "{\"op\":\"bright\",\"v\":2}"));
        assertTrue(request(h, "{\"op\":\"status\"}").startsWith("{\"ok\":true"));
        Log.i(TAG, String.format(Locale.ROOT, "CHANNEL_COMMAND avgMs=%d", perCommand));
        TestInitiator.free(h);
    }

    /**
     * Phase C2: Download-Befehle über den echten Kanal erreichen die Produktiv-Engine mit dem strengen Client. Private
     * und lokale Ziele scheitern mit "blocked_…" ohne Verbindung und ohne Ausweichweg; doppelte Starts starten nichts;
     * die Antworten nennen die URL nicht. Der Status bleibt "dl":false (alte Download-Funktion aus).
     */
    @Test public void downloadCommandsUseOnlyTheHardenedClient() throws Exception {
        // Android 8–9: ohne Speicherberechtigung endete jeder Auftrag (richtig) schon vor dem Netzwerk mit
        // storage_permission; hier sollen die Netzwerkregeln greifen, also wie im Dialog der App erteilen.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            for (String p : Mp4Downloads.LEGACY_STORAGE_PERMISSIONS) shell("pm grant " + ctx.getPackageName() + " " + p);
        }
        byte[] id = kadriIdentity();
        long h = pairAndConfirm(id, pair(id, openWindow()));
        try (ServerSocket local = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            local.setSoTimeout(1);
            String token = "C2CHANNELSECRET" + Long.toHexString(now());
            SECRETS.add(token);
            String[][] cases = {
                {"http://127.0.0.1:" + local.getLocalPort() + "/v.mp4?sig=" + token, "blocked_port"},
                {"http://127.0.0.1/v.mp4?sig=" + token, "blocked_address"},
                {"http://10.0.2.2/v.mp4?sig=" + token, "blocked_address"},
                {"http://169.254.169.254/latest?sig=" + token, "blocked_address"},
                {"http://[::1]/v.mp4?sig=" + token, "blocked_address"},
                {"http://localhost/v.mp4?sig=" + token, "blocked_host_name"},
                {"http://2130706433/v.mp4?sig=" + token, "blocked_"},
                {"https://user:pw@example.com/v.mp4?sig=" + token, "blocked_credentials_in_url"},
            };
            for (int k = 0; k < cases.length; k++) {
                String[] c = cases[k];
                String job = hex(Core.identityGenerate()).substring(0, 32);
                String r = request(h, "{\"op\":\"dlstart\",\"id\":\"" + job + "\",\"url\":\"" + c[0] + "\",\"name\":\"C2 Kanal\"}");
                assertTrue(r, r.startsWith("{\"ok\":true,\"id\":\"" + job + "\""));
                assertFalse(r, r.contains(token) || r.contains("http"));
                String st = r;
                long end = now() + 15_000;
                while (st.contains("\"state\":\"running\"") && now() < end) { Thread.sleep(50); st = request(h, "{\"op\":\"dlstatus\",\"id\":\"" + job + "\"}"); }
                assertTrue("case " + k + " (" + c[1] + ") -> " + st, st.contains("\"state\":\"failed\"") && st.contains("\"reason\":\"" + c[1]));
                assertFalse(st, st.contains(token));
                // Wiederholter Start derselben Kennung: bekannter Zustand, kein neuer Versuch.
                assertTrue(request(h, "{\"op\":\"dlstart\",\"id\":\"" + job + "\",\"url\":\"https://example.com/v.mp4\"}").contains("\"state\":\"failed\""));
                assertTrue(request(h, "{\"op\":\"dlcancel\",\"id\":\"" + job + "\"}").contains("\"state\":\"failed\""));
            }
            try (Socket s = local.accept()) { fail("local target was contacted"); } catch (java.net.SocketTimeoutException expected) { }
        }
        String unknown = "ffffffffffffffffffffffffffffffff";
        assertTrue(request(h, "{\"op\":\"dlstatus\",\"id\":\"" + unknown + "\"}").contains("\"state\":\"unknown\""));
        assertEquals("{\"ok\":false}", request(h, "{\"op\":\"dlstart\",\"id\":\"" + unknown + "\",\"url\":\"https://a.example/x\",\"cookie\":\"a\"}"));
        assertTrue(request(h, "{\"op\":\"status\"}").contains("\"dl\":false"));
        TestInitiator.free(h);
    }

    @Test public void wrongCodeFailsAndBurnsTheWindow() throws Exception {
        byte[] id = kadriIdentity();
        String code = openWindow();
        String wrong = (code.charAt(0) == '9' ? "0" : String.valueOf((char) (code.charAt(0) + 1))) + code.substring(1);
        try { pair(id, wrong); fail("wrong code accepted"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        try { pair(id, code); fail("burned window accepted a second attempt"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        assertTrue(HelferService.instance.registrations().isEmpty());
    }

    @Test public void wrongPinIsRejectedByTheGenuineHelper() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        TestInitiator.free(pairAndConfirm(id, pin));
        byte[] wrongPin = pin.clone();
        System.arraycopy(Core.identityGenerate(), 32, wrongPin, 16, 32);
        try { connect(id, wrongPin); fail("handshake with wrong pinned key succeeded"); } catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        TestInitiator.free(pairAndConfirm(id, pin));
    }

    @Test public void browserGateRefusesWebLikeRequests() throws Exception {
        byte[] body = new byte[37];
        String ct = "Content-Type: application/octet-stream\r\n", kh = "X-Kadri-Helper: 2\r\n", host = "Host: 127.0.0.1:47899\r\n";
        assertEquals(400, raw(47899, "POST", "/p1/pair/m0", GOOD + "Origin: chrome-extension://abcdefghijklmnop\r\n", body).code);
        assertEquals(403, raw(47899, "POST", "/p1/pair/m0", host + ct, body).code);
        assertEquals(403, raw(47899, "POST", "/p1/pair/m0", host + "Content-Type: text/plain\r\n" + kh, body).code);
        assertEquals(403, raw(47899, "POST", "/p1/pair/m0", GOOD + "Origin: https://evil.example\r\n", body).code);
        assertEquals(403, raw(47899, "POST", "/p1/pair/m0", GOOD + "Origin: null\r\n", body).code);
        assertEquals(403, raw(47899, "POST", "/p1/pair/m0", "Host: localhost:47899\r\n" + ct + kh, body).code);
        assertEquals(403, raw(47899, "GET", "/status", host + kh, new byte[0]).code);
        assertEquals(403, raw(47899, "OPTIONS", "/p1/pair/m0", host + "Origin: https://evil.example\r\n", new byte[0]).code);
        assertEquals("legacy pairing path", 400, raw("/pair", new byte[0]).code);
    }

    /** Alter Klartext-Port: kein Dienst. */
    @Test public void nothingListensOnTheLegacyPort() throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 47811), 2000);
            fail("something listens on 127.0.0.1:47811");
        } catch (java.net.ConnectException expected) { /* refused */ }
    }

    @Test public void foreignListenerReceivesCiphertextOnlyAndSessionIsDropped() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long live = pairAndConfirm(id, pin);
        ctx.stopService(new Intent(ctx, HelferService.class));
        waitFor(() -> HelferService.instance == null && !HelferService.running, 10_000);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (ServerSocket rogue = new ServerSocket()) {
            rogue.setReuseAddress(true);
            // Der echte Dienst ist beendet; höchstens 3 s darauf warten, dass das System den Port freigibt (Wartezeit im Bericht).
            long bindStart = now();
            for (;;) {
                try { rogue.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 47899)); break; }
                catch (java.net.BindException busy) { if (now() - bindStart > 3000) throw busy; Thread.sleep(50); }
            }
            Log.i(TAG, String.format(Locale.ROOT, "CHANNEL_ROGUE_BIND waitedMs=%d", now() - bindStart));
            Thread t = new Thread(() -> {
                try (Socket s = rogue.accept()) {
                    s.setSoTimeout(3000);
                    byte[] buf = new byte[70000];
                    int n = s.getInputStream().read(buf);
                    captured.write(buf, 0, Math.max(n, 0));
                    byte[] forged = "{\"ok\":true,\"bright\":1}".getBytes(StandardCharsets.UTF_8);
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + forged.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    s.getOutputStream().write(forged);
                } catch (Exception ignored) {}
            });
            t.start();
            try { request(live, "{\"op\":\"bright\",\"v\":0.123}"); fail("forged reply accepted"); }
            catch (SecurityException expected) { /* kein Kanal-Record */ }
            t.join(5000);
            try { request(live, "{\"op\":\"status\"}"); fail("dead session reused"); }
            catch (SecurityException expected) { assertEquals("WrongState", expected.getMessage()); }
        }
        String seen = new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue("rogue saw the request", seen.contains("POST /p1/ch/rec"));
        assertFalse(seen.contains("\"op\""));
        assertFalse(seen.contains("bright"));
        assertFalse(seen.contains("0.123"));
        assertFalse(seen.toLowerCase(Locale.ROOT).contains("x-evp-key"));
        restartService();
        TestInitiator.free(pairAndConfirm(id, pin));
    }

    @Test public void restartKeepsRegistrationsButNotSessions() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        long old = pairAndConfirm(id, pin);
        restartService();
        try { request(old, "{\"op\":\"status\"}"); fail("old session survived restart"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        TestInitiator.free(pairAndConfirm(id, pin));
        TestInitiator.free(old);
    }

    /** Einzelwiderruf (hostRevoke): nur dieser Browser verliert Sitzung und Registrierung – auch nach einem Neustart. */
    @Test public void revokeOneKeepsTheOthersAcrossRestart() throws Exception {
        byte[] ida = kadriIdentity(), idb = kadriIdentity();
        byte[] pa = pair(ida, openWindow()), pb = pair(idb, openWindow());
        long ha = pairAndConfirm(ida, pa), hb = pairAndConfirm(idb, pb);
        assertTrue(HelferService.instance.revoke(Arrays.copyOf(pa, 16)));
        List<HelferService.Registration> regs = HelferService.instance.registrations();
        assertEquals(1, regs.size());
        assertArrayEquals(Arrays.copyOf(pb, 16), regs.get(0).id);
        try { request(ha, "{\"op\":\"status\"}"); fail("revoked session still works"); }
        catch (IllegalStateException e) { assertEquals("HTTP 404", e.getMessage()); }
        try { connect(ida, pa); fail("revoked registration handshakes"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        assertTrue(request(hb, "{\"op\":\"status\"}").startsWith("{\"ok\":true"));
        restartService();
        try { connect(ida, pa); fail("revocation lost across restart"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        TestInitiator.free(pairAndConfirm(idb, pb));
        // Neu koppeln ergibt eine neue Registrierung.
        byte[] pa2 = pair(ida, openWindow());
        assertFalse(Arrays.equals(Arrays.copyOf(pa, 16), Arrays.copyOf(pa2, 16)));
        TestInitiator.free(pairAndConfirm(ida, pa2));
        assertTrue(HelferService.instance.revoke(null));
        assertTrue(HelferService.instance.registrations().isEmpty());
        try { connect(idb, pb); fail("revoke all left B"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        TestInitiator.free(ha);
        TestInitiator.free(hb);
    }

    /** Zurücksetzen: neue Identität, alte Pins passen nicht mehr (keine stille Annahme). */
    /** F3: Widerruf nicht gespeichert → Meldung sagt, dass die Kopplung nach einem Neustart weiter bestehen kann – und so ist es. */
    @Test public void failedRevokeWarnsThatThePairingMaySurviveARestart() throws Exception {
        byte[] id = kadriIdentity();
        TestInitiator.free(pairAndConfirm(id, pair(id, openWindow())));
        int before = HelferService.instance.registrations().size();
        assertEquals(1, before);
        File dir = ctx.getFilesDir();
        // Verzeichnis UND Registry-Datei sperren: android.util.AtomicFile schreibt bis API 29 an Ort und Stelle weiter,
        // wenn das Umbenennen in .bak scheitert – ein schreibgeschütztes Verzeichnis allein verhindert dort kein Speichern.
        File registry = new File(dir, "registrations.v1");
        assertTrue("registry file present", registry.isFile());
        boolean ok;
        assertTrue("registry file read-only", registry.setWritable(false, false));
        assertTrue("files dir read-only", dir.setWritable(false, false));
        try { ok = HelferService.instance.revoke(null); }
        finally {
            assertTrue("files dir writable again", dir.setWritable(true, true));
            assertTrue("registry file writable again", registry.setWritable(true, true));
        }
        assertFalse("revoke must report failure when it cannot be saved", ok);
        assertEquals(HelferService.revokeFailedText(), HelferService.startupError);
        assertTrue(HelferService.startupError, HelferService.startupError.contains("nach einem Neustart weiterhin bestehen")
            || HelferService.startupError.contains("may still exist after a restart"));
        restartService();
        assertEquals("the unsaved revocation is lost: the pairing still exists after the restart", before,
            HelferService.instance.registrations().size());
        assertTrue("disconnecting again succeeds", HelferService.instance.revoke(null));
        restartService();
        assertEquals(0, HelferService.instance.registrations().size());
    }

    @Test public void resetIdentityInvalidatesOldPins() throws Exception {
        byte[] id = kadriIdentity();
        byte[] pin = pair(id, openWindow());
        TestInitiator.free(pairAndConfirm(id, pin));
        assertNull(HelferService.instance.resetIdentity());
        assertTrue(HelferService.instance.registrations().isEmpty());
        try { connect(id, pin); fail("old pin accepted after reset"); }
        catch (IllegalStateException e) { assertEquals("HTTP 400", e.getMessage()); }
        byte[] pin2 = pair(id, openWindow());
        assertFalse("new helper key", Arrays.equals(Arrays.copyOfRange(pin, 16, 48), Arrays.copyOfRange(pin2, 16, 48)));
        TestInitiator.free(pairAndConfirm(id, pin2));
        IdentityStore.Result helper = new IdentityStore(ctx.getFilesDir(), HelferService.WRAP_ALIAS, ctx.getPackageName()).load(false);
        SECRETS.add(hex(Arrays.copyOf(helper.identity, 32)));
        Arrays.fill(helper.identity, (byte) 0);
    }

    /** F3: 24 langsame lokale Clients blockieren die 4 Worker nicht; legitime Anfragen werden bedient. */
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
                        try { in.read(); break; }
                        catch (java.net.SocketTimeoutException still) { Thread.sleep(250); }
                    }
                } catch (Exception closed) { /* zurückgesetzt: verworfen */ }
                dropped[k] = now() - t0;
            });
            attackers[i].start();
        }
        Thread.sleep(500);
        long legitStart = now();
        boolean served = false;
        while (!served && now() - legitStart < 15_000) {
            try {
                long h = connect(id, pin);
                served = request(h, "{\"op\":\"status\"}").startsWith("{\"ok\":true");
                TestInitiator.free(h);
            } catch (Exception busy) { Thread.sleep(100); }
        }
        long servedAfter = now() - t0;
        long maxDrop = 0;
        for (int i = 0; i < n; i++) { attackers[i].join(25_000); maxDrop = Math.max(maxDrop, dropped[i]); }
        Log.i(TAG, String.format(Locale.ROOT, "CHANNEL_SLOWCLIENT attackers=%d maxDropMs=%d legitServedAfterMs=%d", n, maxDrop, servedAfter));
        assertTrue("legitimate request served under slow-client load", served);
        assertTrue("served within budget + 4 s, took " + servedAfter, servedAfter < HttpRequestReader.READ_BUDGET_MS + 4000);
        assertTrue("slow clients dropped within budget + 3 s, max " + maxDrop, maxDrop < HttpRequestReader.READ_BUDGET_MS + 3000);
    }

    /** Eigenes Testpaket, Code-Fenster vor Bildschirmfotos geschützt, keine Sicherung oder Geräteübertragung. */
    @Test public void packageScreenAndBackupHardening() throws Exception {
        assertEquals("de.evp.helfer.channeltest", ctx.getPackageName());
        assertEquals("allowBackup=false", 0, ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        // Frische, sichtbare Activity (die vom Testbeginn ist nach den Dienst-Neustarts nicht mehr im Vordergrund).
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        MainActivity a = (MainActivity) ins.startActivitySync(new Intent(ctx, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        ins.waitForIdleSync();
        ins.runOnMainSync(a::showCode);
        waitFor(() -> a.codeDialog != null && a.codeDialog.isShowing(), 5000);
        assertTrue("FLAG_SECURE", (a.codeDialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        SECRETS.add(HelferService.instance.code);
        ins.runOnMainSync(() -> a.codeDialog.dismiss());
    }
}
