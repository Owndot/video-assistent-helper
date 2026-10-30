package de.evp.helfer.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Debug;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import okhttp3.HttpUrl;

/**
 * Gerätetests (Emulator API 34 und API 26) der sicheren Netzwerkschicht in der mit R8 geschrumpften Testvariante:
 * echte HTTP- und HTTPS-Anfragen über den {@link SafeHttpClient} gegen lokale Testserver, Negativfälle auf dem
 * Android-Socket-Pfad (private Ziele, DNS-Rebinding, Proxys, Wiederholungen), RAM beim Streamen, entfernte
 * Startkomponente und Nachweis, dass die installierte App die R8-Ausgabe ist. Keine externen Hosts.
 */
@RunWith(AndroidJUnit4.class)
public class SafeHttpInstrumentedTest {
    private static final String TAG = "OKHTTP_TEST";
    private static final String TLS_ALIAS = "safehttp-test-tls-throwaway";
    private static final long T = 15_000;

    static Context ctx() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    static InetAddress loop() throws UnknownHostException { return InetAddress.getByName("127.0.0.1"); }

    // ------------------------------------------------------------------ Testserver

    interface Handler { void handle(Map<String, String> headers, OutputStream out, Socket s) throws IOException; }

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger accepts = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, Handler> routes = new LinkedHashMap<>();

        Server(ServerSocket ss) {
            this.ss = ss;
            Thread t = new Thread(() -> {
                while (true) {
                    Socket s;
                    try { s = ss.accept(); } catch (IOException e) { return; }
                    accepts.incrementAndGet();
                    Thread h = new Thread(() -> serve(s));
                    h.setDaemon(true);
                    h.start();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        static Server plain() throws IOException { return new Server(new ServerSocket(0, 50, loop())); }

        int port() { return ss.getLocalPort(); }
        String url(String scheme, String path) { return scheme + "://127.0.0.1:" + port() + path; }
        Server route(String path, Handler h) { routes.put(path, h); return this; }
        int count(String path) { int n = 0; for (String r : requests) if (r.equals(path)) n++; return n; }

        private void serve(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(20000);
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = new BufferedOutputStream(sock.getOutputStream());
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    String path = line.split(" ")[1];
                    Map<String, String> h = new LinkedHashMap<>();
                    String hl;
                    while ((hl = readLine(in)) != null && !hl.isEmpty()) {
                        int c = hl.indexOf(':');
                        if (c > 0) h.put(hl.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT), hl.substring(c + 1).trim());
                    }
                    requests.add(path);
                    Handler handler = routes.get(path);
                    if (handler == null) raw(out, "HTTP/1.1 404 X\r\nContent-Length: 0\r\n\r\n");
                    else handler.handle(h, out, sock);
                    out.flush();
                }
            } catch (IOException ignored) { }
        }

        @Override public void close() throws IOException { ss.close(); }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { if (c != '\r') b.write(c); }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static void raw(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); }

    static void text(OutputStream out, String body) throws IOException {
        raw(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
    }

    static final class FakeDns implements SafeHttpClient.Resolver {
        final List<List<InetAddress>> answers = new ArrayList<>();
        FakeDns answer(InetAddress... a) { answers.add(Arrays.asList(a)); return this; }
        @Override public synchronized List<InetAddress> resolve(String host) throws UnknownHostException {
            if (answers.isEmpty()) throw new UnknownHostException(host);
            return answers.size() > 1 ? answers.remove(0) : answers.get(0);
        }
    }

    static SafeHttpClient testClient(Server srv, SafeHttpClient.Resolver dns) throws IOException {
        return new SafeHttpClient(NetRules.forLocalTests(NetRules.NO_NETWORK_NAT64, loop(), srv.port()), dns, null);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String body(SafeResponse r) throws IOException {
        try (SafeResponse x = r; InputStream in = x.body()) { return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    interface Call { void run() throws Exception; }

    static NetBlockedException.Reason blocked(Call c) throws Exception {
        try { c.run(); } catch (NetBlockedException e) { return e.reason; }
        fail("not blocked");
        return null;
    }

    static void fill(byte[] chunk, long offset) { for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) ((offset + i) * 31 + 7); }

    // ------------------------------------------------------------------ Tests

    /** Echte HTTP-Anfragen: Content-Length, 4 MiB chunked gestreamt, relative Weiterleitung, eine Pool-Verbindung. */
    @Test
    public void realHttpRequestsThroughTheSafeClient() throws Exception {
        final int big = 4 << 20;
        try (Server srv = Server.plain()) {
            srv.route("/hello", (h, o, s) -> text(o, "hello"))
               .route("/r", (h, o, s) -> raw(o, "HTTP/1.1 302 Found\r\nLocation: /hello\r\nContent-Length: 0\r\n\r\n"))
               .route("/big", (h, o, s) -> {
                   raw(o, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
                   byte[] chunk = new byte[64 << 10];
                   for (long off = 0; off < big; off += chunk.length) {
                       fill(chunk, off);
                       raw(o, Integer.toHexString(chunk.length) + "\r\n");
                       o.write(chunk);
                       raw(o, "\r\n");
                   }
                   raw(o, "0\r\n\r\n");
               });
            SafeHttpClient c = testClient(srv, new FakeDns());
            assertEquals("hello", body(c.get(srv.url("http", "/hello"), 100, T)));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long n = 0;
            try (SafeResponse r = c.get(srv.url("http", "/big"), big, T); InputStream in = r.body()) {
                byte[] buf = new byte[64 << 10];
                int k;
                while ((k = in.read(buf)) > 0) { md.update(buf, 0, k); n += k; }
            }
            MessageDigest exp = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[64 << 10];
            for (long off = 0; off < big; off += chunk.length) { fill(chunk, off); exp.update(chunk); }
            assertEquals(big, n);
            assertArrayEquals(exp.digest(), md.digest());
            assertEquals("hello", body(c.get(srv.url("http", "/r"), 100, T)));
            assertEquals("one pooled connection", 1, srv.accepts.get());
            Log.i(TAG, "OKHTTP_HTTP bytes=" + n + " requests=" + srv.requests.size() + " connections=" + srv.accepts.get());
        }
    }

    /** Echte HTTPS-Anfrage über die Android-TLS-Plattform; Server-Identität im AndroidKeyStore (Wegwerf-Schlüssel). */
    @Test
    public void realHttpsRequestThroughTheSafeClient() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=127.0.0.1"))
                .build());
            kpg.generateKeyPair();
            X509Certificate cert = (X509Certificate) ks.getCertificate(TLS_ALIAS);
            PrivateKey key = (PrivateKey) ks.getKey(TLS_ALIAS, null);
            X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
                String pick(String keyType) { return keyType != null && keyType.startsWith("EC") ? TLS_ALIAS : null; }
                @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
                @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket s) { return null; }
                @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return pick(keyType) == null ? null : new String[] {TLS_ALIAS}; }
                @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket s) { return pick(keyType); }
                @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine e) { return pick(keyType); }
                @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {cert}; }
                @Override public PrivateKey getPrivateKey(String alias) { return key; }
            };
            SSLContext serverTls = SSLContext.getInstance("TLS");
            serverTls.init(new KeyManager[] {km}, null, null);
            X509TrustManager onlyThisCert = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("no client certificates"); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length == 0 || !chain[0].equals(cert)) throw new CertificateException("not the test identity");
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] {cert}; }
            };
            SSLContext clientTls = SSLContext.getInstance("TLS");
            clientTls.init(null, new TrustManager[] {onlyThisCert}, null);
            try (Server srv = new Server(serverTls.getServerSocketFactory().createServerSocket(0, 50, loop()));
                 Server plain = Server.plain()) {
                plain.route("/x", (h, o, s) -> text(o, "plain"));
                srv.route("/hello", (h, o, s) -> text(o, "tls"))
                   .route("/down", (h, o, s) -> raw(o, "HTTP/1.1 302 Found\r\nLocation: " + plain.url("http", "/x") + "\r\nContent-Length: 0\r\n\r\n"));
                SafeHttpClient c = new SafeHttpClient(NetRules.forLocalTests(NetRules.NO_NETWORK_NAT64, loop(), srv.port()), new FakeDns(),
                    new SafeHttpClient.TestTls(clientTls.getSocketFactory(), onlyThisCert, (host, session) -> "127.0.0.1".equals(host)));
                assertEquals("tls", body(c.get(srv.url("https", "/hello"), 100, T)));
                assertEquals(NetBlockedException.Reason.DOWNGRADE, blocked(() -> c.get(srv.url("https", "/down"), 100, T).close()));
                assertEquals("plain server contacted", 0, plain.accepts.get());
                // Ohne Test-Vertrauen (System-Vertrauensspeicher) wird das selbstsignierte Zertifikat abgelehnt.
                SafeHttpClient strictTrust = testClient(srv, new FakeDns());
                try { strictTrust.get(srv.url("https", "/hello"), 100, T).close(); fail("untrusted certificate accepted"); }
                catch (javax.net.ssl.SSLException expected) { }
                assertEquals("only the trusted request reached the server", 1, srv.count("/hello"));
                Log.i(TAG, "OKHTTP_HTTPS sdk=" + Build.VERSION.SDK_INT + " ok");
            }
        } finally {
            ks.deleteEntry(TLS_ALIAS);
        }
    }

    /** Der produktive Client (strenge Regeln) lehnt lokale und private Ziele ab, ohne eine Verbindung aufzubauen. */
    @Test
    public void strictClientRefusesLocalAndPrivateTargets() throws Exception {
        SafeHttpClient strict = SafeHttp.client(ctx());
        Object[][] cases = {
            {"http://127.0.0.1/", NetBlockedException.Reason.ADDRESS}, {"http://[::1]/", NetBlockedException.Reason.ADDRESS},
            {"http://[::ffff:127.0.0.1]/", NetBlockedException.Reason.ADDRESS}, {"http://2130706433/", NetBlockedException.Reason.ADDRESS},
            {"http://10.0.2.2/", NetBlockedException.Reason.ADDRESS},          // Emulator: Loopback des Hosts
            {"http://169.254.169.254/latest/meta-data/", NetBlockedException.Reason.ADDRESS},
            {"http://192.168.1.1/", NetBlockedException.Reason.ADDRESS}, {"http://[fd00::1]/", NetBlockedException.Reason.ADDRESS},
            {"http://[64:ff9b::a00:1]/", NetBlockedException.Reason.ADDRESS}, {"http://localhost/", NetBlockedException.Reason.HOST_NAME},
            {"http://printer.local/", NetBlockedException.Reason.HOST_NAME}, {"http://example.com:8080/", NetBlockedException.Reason.PORT},
            {"http://u:p@example.com/", NetBlockedException.Reason.CREDENTIALS_IN_URL}, {"ftp://example.com/", NetBlockedException.Reason.BAD_URL},
        };
        for (Object[] k : cases) {
            NetBlockedException.Reason r = blocked(() -> strict.get((String) k[0], 100, T).close());
            assertEquals((String) k[0], k[1], r);
        }
        // Socket-Schicht allein auf dem Android-Socket-Pfad: kein TCP-Aufbau zu einem privaten Ziel.
        try (Server srv = Server.plain()) {
            Socket s = new GuardedSocketFactory(NetRules.strict(NetRules.NO_NETWORK_NAT64)).createSocket();
            try { s.connect(new InetSocketAddress(loop(), srv.port()), 2000); fail("connected"); }
            catch (NetBlockedException.Connect expected) { }
            Thread.sleep(200);
            assertEquals("TCP accepts", 0, srv.accepts.get());
        }
    }

    /** DNS-Rebinding und private DNS-Antworten auf dem Gerät. */
    @Test
    public void dnsRebindingAndPrivateAnswersAreRefused() throws Exception {
        try (Server srv = Server.plain()) {
            srv.route("/a", (h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1\r\n\r\na"));
            FakeDns dns = new FakeDns().answer(loop()).answer(InetAddress.getByName("10.0.0.7"));
            SafeHttpClient c = testClient(srv, dns);
            String u = "http://media.example:" + srv.port() + "/a";
            assertEquals("a", body(c.get(u, 10, T)));
            assertEquals(NetBlockedException.Reason.ADDRESS, blocked(() -> c.get(u, 10, T).close()));
            assertEquals(1, srv.count("/a"));
            for (String a : new String[] {"::ffff:10.0.0.1", "64:ff9b::c0a8:1", "fd00::5", "fe80::1", "2002:a00:1::1"}) {
                SafeHttpClient v6 = testClient(srv, new FakeDns().answer(InetAddress.getByName(a)));
                assertEquals(a, NetBlockedException.Reason.ADDRESS, blocked(() -> v6.get("http://v6.example:" + srv.port() + "/a", 10, T).close()));
            }
        }
    }

    /** System-Proxys (Eigenschaften und ProxySelector) werden nicht verwendet; keine automatischen Wiederholungen. */
    @Test
    public void noSystemProxiesAndNoAutomaticRepeats() throws Exception {
        try (Server srv = Server.plain(); ServerSocket proxy = new ServerSocket(0, 10, loop())) {
            srv.route("/p", (h, o, s) -> text(o, "direct"))
               .route("/503", (h, o, s) -> raw(o, "HTTP/1.1 503 X\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n"))
               .route("/drop", (h, o, s) -> s.close());
            AtomicInteger hits = new AtomicInteger();
            Thread t = new Thread(() -> { while (true) { try (Socket s = proxy.accept()) { hits.incrementAndGet(); } catch (IOException e) { return; } } });
            t.setDaemon(true);
            t.start();
            String pp = String.valueOf(proxy.getLocalPort());
            String[] props = {"http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "socksProxyHost", "socksProxyPort"};
            Map<String, String> old = new LinkedHashMap<>();
            for (String k : props) old.put(k, System.getProperty(k));
            ProxySelector oldSel = ProxySelector.getDefault();
            try {
                for (int i = 0; i < props.length; i += 2) { System.setProperty(props[i], "127.0.0.1"); System.setProperty(props[i + 1], pp); }
                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy.getLocalPort())));
                    }
                    @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
                });
                SafeHttpClient c = testClient(srv, new FakeDns());
                assertEquals("direct", body(c.get(srv.url("http", "/p"), 100, T)));
                try (SafeResponse r = c.get(srv.url("http", "/503"), 10, T)) { assertEquals(503, r.code()); }
                try { c.get(srv.url("http", "/drop"), 10, T).close(); fail("no error"); } catch (IOException expected) { }
                Thread.sleep(200);
                assertEquals("proxy used", 0, hits.get());
                assertEquals("503 repeated", 1, srv.count("/503"));
                assertEquals("dropped request repeated", 1, srv.count("/drop"));
            } finally {
                for (Map.Entry<String, String> e : old.entrySet()) {
                    if (e.getValue() == null) System.clearProperty(e.getKey()); else System.setProperty(e.getKey(), e.getValue());
                }
                ProxySelector.setDefault(oldSel);
            }
        }
    }

    /** RAM beim Streamen von 64 MiB (HTTP/1.1, Gegendruck über TCP; langsamer Leser). */
    @Test
    public void streamingKeepsMemoryBounded() throws Exception {
        final long total = 64L << 20;
        try (Server srv = Server.plain()) {
            srv.route("/big", (h, o, s) -> {
                raw(o, "HTTP/1.1 200 OK\r\nContent-Length: " + total + "\r\n\r\n");
                byte[] chunk = new byte[64 << 10];
                for (long off = 0; off < total; off += chunk.length) o.write(chunk);
            });
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long heap0 = rt.totalMemory() - rt.freeMemory();
            long pss0 = Debug.getPss();
            long heapMax = heap0, pssMax = pss0, n = 0;
            try (SafeResponse r = testClient(srv, new FakeDns()).get(srv.url("http", "/big"), total, 120_000); InputStream in = r.body()) {
                byte[] buf = new byte[64 << 10];
                int k;
                while ((k = in.read(buf)) > 0) {
                    n += k;
                    if ((n & ((4 << 20) - 1)) < k) {
                        heapMax = Math.max(heapMax, rt.totalMemory() - rt.freeMemory());
                        pssMax = Math.max(pssMax, Debug.getPss());
                        if (n < (16 << 20)) Thread.sleep(20);   // langsamer Leser am Anfang
                    }
                }
            }
            assertEquals(total, n);
            long heapGrowKiB = (heapMax - heap0) >> 10, pssGrowKiB = pssMax - pss0;
            Log.i(TAG, "OKHTTP_RAM sdk=" + Build.VERSION.SDK_INT + " streamedMiB=" + (n >> 20) + " heapGrowthKiB=" + heapGrowKiB
                + " pssGrowthKiB=" + pssGrowKiB + " pssStartKiB=" + pss0);
            assertTrue("heap grew by " + heapGrowKiB + " KiB", heapGrowKiB < 16 * 1024);
        }
    }

    /** NAT64-Präfix des aktiven Netzes ist abfragbar (Berechtigung vorhanden); ohne NAT64 im Emulator: leer. */
    @Test
    public void networkNat64PrefixLookupWorks() {
        List<AddressPolicy.Nat64Prefix> p = SafeHttp.networkNat64(ctx().getApplicationContext());
        assertNotNull(p);
        Log.i(TAG, "OKHTTP_NAT64 sdk=" + Build.VERSION.SDK_INT + " prefixes=" + p.size());
    }

    /** Die AndroidX-Startkomponente ist entfernt; OkHttp hat den Context trotzdem (über SafeHttp). */
    @SuppressWarnings("deprecation")
    @Test
    public void startupComponentRemovedAndOkHttpInitialised() throws Exception {
        Context c = ctx();
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PROVIDERS);
        if (pi.providers != null) for (ProviderInfo p : pi.providers) assertFalse(p.name, p.name.contains("InitializationProvider"));
        SafeHttp.client(c);
        assertEquals("example.co.uk", HttpUrl.get("https://www.example.co.uk/").topPrivateDomain());
    }

    /** Die installierte App ist die R8-Ausgabe; der alte ungehärtete Client existiert nicht mehr. */
    @Test
    public void installedAppIsTheShrunkR8Build() throws Exception {
        Context c = ctx();
        assertEquals(0, c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        ClassLoader cl = c.getClassLoader();
        for (String removed : new String[] {"androidx.startup.InitializationProvider", "okhttp3.internal.ws.RealWebSocket", "de.evp.helfer.HttpClients"}) {
            try { Class.forName(removed, false, cl); fail(removed + " is in the APK"); } catch (ClassNotFoundException expected) { }
        }
        for (String kept : new String[] {"poc.Core", "de.evp.helfer.ChannelServer", "de.evp.helfer.ControlCommands", "de.evp.helfer.HelferService",
            "de.evp.helfer.net.SafeHttpClient", "de.evp.helfer.net.GuardedSocketFactory"}) {
            assertEquals(kept, Class.forName(kept, false, cl).getName());
        }
        assertNotNull(SafeHttp.client(c));
        Log.i(TAG, "OKHTTP_R8 sdk=" + Build.VERSION.SDK_INT + " debuggable=false");
    }
}
