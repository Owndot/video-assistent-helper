package de.evp.helfer;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import okhttp3.HttpUrl;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;

/**
 * Gerätetests (Emulator) für OkHttp in der mit R8 geschrumpften Testvariante de.evp.helfer.channeltest: echte
 * HTTP- und HTTPS-Anfragen über den Client aus {@link HttpClients} gegen lokale Testserver auf 127.0.0.1, entfernte
 * AndroidX-Startkomponente, und dass die installierte App tatsächlich die R8-Ausgabe ist.
 * Keine externen Hosts, keine Medien-URLs, Cookies oder Zugangsdaten.
 */
@RunWith(AndroidJUnit4.class)
public class OkHttpInstrumentedTest {
    private static final String TAG = "OKHTTP_TEST";
    private static final int BIG = 4 << 20;   // 4 MiB, gestreamt (nie ganz im Speicher)
    private static final String TLS_ALIAS = "okhttp-test-tls-throwaway";

    static Context ctx() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    static InetAddress loopback() throws IOException { return InetAddress.getByName("127.0.0.1"); }

    static void fill(byte[] chunk, long offset) {
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) ((offset + i) * 31 + 7);
    }

    static byte[] expectedBigSha256() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[64 << 10];
        for (long off = 0; off < BIG; off += chunk.length) { fill(chunk, off); md.update(chunk); }
        return md.digest();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    /** Minimaler HTTP/1.1-Testserver (Keep-Alive, Content-Length, chunked, 302) auf einem gegebenen ServerSocket. */
    static final class LocalServer implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger accepts = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();

        LocalServer(ServerSocket socket) {
            this.socket = socket;
            Thread t = new Thread(this::acceptLoop, "okhttp-test-server");
            t.setDaemon(true);
            t.start();
        }

        String base(String scheme) { return scheme + "://127.0.0.1:" + socket.getLocalPort(); }

        private void acceptLoop() {
            while (true) {
                Socket s;
                try { s = socket.accept(); } catch (IOException closed) { return; }
                accepts.incrementAndGet();
                Thread h = new Thread(() -> serve(s), "okhttp-test-conn");
                h.setDaemon(true);
                h.start();
            }
        }

        private void serve(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(15000);
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = new BufferedOutputStream(sock.getOutputStream());
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    String path = line.split(" ")[1];
                    String header;
                    while ((header = readLine(in)) != null && !header.isEmpty()) { /* GET ohne Rumpf */ }
                    requests.incrementAndGet();
                    if (path.equals("/hello")) {
                        byte[] b = "hello".getBytes(StandardCharsets.US_ASCII);
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + b.length
                            + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(b);
                    } else if (path.equals("/redirect")) {
                        out.write("HTTP/1.1 302 Found\r\nLocation: /hello\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    } else if (path.equals("/big")) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                        byte[] chunk = new byte[64 << 10];
                        for (long off = 0; off < BIG; off += chunk.length) {
                            fill(chunk, off);
                            out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.write(chunk);
                            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        }
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // Verbindung vom Client beendet
            }
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    static Response get(OkHttpClient client, String url) throws IOException {
        return client.newCall(new Request.Builder().url(url).build()).execute();
    }

    /** Echte HTTP-Anfragen: kleiner Rumpf, 4 MiB chunked gestreamt, Weiterleitung, alles über eine Pool-Verbindung. */
    @Test
    public void realHttpRequestsOverLoopback() throws Exception {
        OkHttpClient client = HttpClients.shared(ctx());
        assertEquals("5.5.1", OkHttp.VERSION);
        try (LocalServer srv = new LocalServer(new ServerSocket(0, 50, loopback()))) {
            String base = srv.base("http");
            try (Response r = get(client, base + "/hello")) {
                assertEquals(200, r.code());
                assertEquals(Protocol.HTTP_1_1, r.protocol());
                assertEquals("hello", r.body().string());
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long n = 0;
            try (Response r = get(client, base + "/big"); InputStream in = r.body().byteStream()) {
                assertEquals(200, r.code());
                byte[] buf = new byte[64 << 10];
                int k;
                while ((k = in.read(buf)) > 0) { md.update(buf, 0, k); n += k; }
            }
            assertEquals(BIG, n);
            assertArrayEquals(expectedBigSha256(), md.digest());
            try (Response r = get(client, base + "/redirect")) {
                assertEquals(200, r.code());
                assertNotNull(r.priorResponse());
                assertEquals(302, r.priorResponse().code());
                assertEquals("hello", r.body().string());
            }
            assertEquals("requests", 4, srv.requests.get());
            assertEquals("all requests on one pooled connection", 1, srv.accepts.get());
            Log.i(TAG, "OKHTTP_HTTP version=" + OkHttp.VERSION + " bytes=" + n + " requests=" + srv.requests.get()
                + " connections=" + srv.accepts.get());
        }
    }

    /**
     * Echte HTTPS-Anfrage über die Android-TLS-Plattform von OkHttp (Conscrypt-Adapter, die R8 sonst entfernen
     * könnte). Server-Identität: Wegwerf-EC-Schlüssel im AndroidKeyStore (selbstsigniert, verlässt das Gerät nie);
     * der Client vertraut nur genau diesem Zertifikat.
     */
    @Test
    public void realHttpsRequestOverLoopback() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=127.0.0.1"))
                .build());
            kpg.generateKeyPair();
            X509Certificate cert = (X509Certificate) ks.getCertificate(TLS_ALIAS);
            PrivateKey key = (PrivateKey) ks.getKey(TLS_ALIAS, null);

            X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
                String pick(String keyType) { return keyType != null && keyType.startsWith("EC") ? TLS_ALIAS : null; }
                @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
                @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket s) { return null; }
                @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
                    return pick(keyType) == null ? null : new String[] {TLS_ALIAS};
                }
                @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket s) { return pick(keyType); }
                @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine e) { return pick(keyType); }
                @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {cert}; }
                @Override public PrivateKey getPrivateKey(String alias) { return key; }
            };
            SSLContext serverTls = SSLContext.getInstance("TLS");
            serverTls.init(new KeyManager[] {km}, null, null);

            X509TrustManager onlyThisCert = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("no client certificates");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length == 0 || !chain[0].equals(cert)) throw new CertificateException("not the test identity");
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] {cert}; }
            };
            SSLContext clientTls = SSLContext.getInstance("TLS");
            clientTls.init(null, new TrustManager[] {onlyThisCert}, null);
            OkHttpClient client = HttpClients.shared(ctx()).newBuilder()
                .sslSocketFactory(clientTls.getSocketFactory(), onlyThisCert)
                .hostnameVerifier((host, session) -> "127.0.0.1".equals(host))   // Testzertifikat ohne SAN
                .build();

            try (LocalServer srv = new LocalServer(serverTls.getServerSocketFactory().createServerSocket(0, 50, loopback()))) {
                try (Response r = get(client, srv.base("https") + "/hello")) {
                    assertEquals(200, r.code());
                    assertEquals("hello", r.body().string());
                    assertNotNull(r.handshake());
                    TlsVersion v = r.handshake().tlsVersion();
                    assertTrue(String.valueOf(v), v == TlsVersion.TLS_1_3 || v == TlsVersion.TLS_1_2);
                    assertEquals(cert, r.handshake().peerCertificates().get(0));
                    Log.i(TAG, "OKHTTP_HTTPS tls=" + v.javaName() + " cipher=" + r.handshake().cipherSuite().javaName()
                        + " protocol=" + r.protocol());
                }
            }
        } finally {
            ks.deleteEntry(TLS_ALIAS);
        }
    }

    /** Die AndroidX-Startkomponente ist entfernt; OkHttp hat den Context trotzdem (Public-Suffix-Liste aus den Assets). */
    @SuppressWarnings("deprecation")
    @Test
    public void startupComponentRemovedAndOkHttpInitialised() throws Exception {
        Context c = ctx();
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PROVIDERS);
        if (pi.providers != null) {
            for (ProviderInfo p : pi.providers) assertFalse(p.name, p.name.contains("InitializationProvider"));
        }
        HttpClients.shared(c);
        assertEquals("example.co.uk", HttpUrl.get("https://www.example.co.uk/").topPrivateDomain());
    }

    /** Die installierte App ist die R8-Ausgabe: nicht debugfähig, Unbenutztes entfernt, App- und JNI-Klassen erhalten. */
    @Test
    public void installedAppIsTheShrunkR8Build() throws Exception {
        Context c = ctx();
        assertEquals(0, c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        ClassLoader cl = c.getClassLoader();
        for (String removed : new String[] {
            "androidx.startup.InitializationProvider",
            "okhttp3.internal.platform.PlatformInitializer",
            "okhttp3.internal.ws.RealWebSocket",
        }) {
            try {
                Class.forName(removed, false, cl);
                fail(removed + " is still in the APK: not the R8 output");
            } catch (ClassNotFoundException expected) {
                // von R8 entfernt
            }
        }
        for (String kept : new String[] {"poc.Core", "de.evp.helfer.ChannelServer", "de.evp.helfer.ControlCommands",
            "de.evp.helfer.HelferService", "de.evp.helfer.HttpClients"}) {
            assertEquals(kept, Class.forName(kept, false, cl).getName());
        }
        Log.i(TAG, "OKHTTP_R8 debuggable=false removed=3 kept=5");
    }
}
