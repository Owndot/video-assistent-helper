package de.evp.helfer;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** Real local TLS/HTTP, with a disposable certificate trusted only by this test client. */
public class TestTlsCookieBoundary {
    static final String COOKIE = "session=test-only";
    static final AtomicReference<String> cookie = new AtomicReference<>();
    static final AtomicReference<String> referer = new AtomicReference<>();
    static final AtomicReference<String> origin = new AtomicReference<>();
    static final AtomicReference<String> requestUri = new AtomicReference<>();
    static final AtomicReference<String> range = new AtomicReference<>();
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static boolean empty(String value) { return value == null || value.isEmpty(); }
    static void echo(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
        requestUri.set(e.getRequestURI().toString());
        cookie.set(e.getRequestHeaders().getFirst("Cookie"));
        referer.set(e.getRequestHeaders().getFirst("Referer"));
        origin.set(e.getRequestHeaders().getFirst("Origin"));
        range.set(e.getRequestHeaders().getFirst("Range"));
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(200, body.length);
        e.getResponseBody().write(body);
        e.close();
    }
    static void redirect(com.sun.net.httpserver.HttpExchange e, String target) throws java.io.IOException {
        e.getResponseHeaders().set("Location", target);
        e.sendResponseHeaders(302, -1); e.close();
    }
    static class Client extends Fetcher.Http {
        final SSLContext tls;
        Client(String page, SSLContext tls) { super(page, "TEST-ONLY", COOKIE); this.tls = tls; }
        @Override HttpURLConnection open(String url) throws java.io.IOException {
            HttpURLConnection c = super.open(url);
            if (c instanceof HttpsURLConnection) ((HttpsURLConnection)c).setSSLSocketFactory(tls.getSocketFactory());
            return c;
        }
    }
    public static void main(String[] args) throws Exception {
        // Test JVM only: make Origin observable like Android; TLS trust stays strict.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        Path folder = Files.createTempDirectory("helper-tls-fixture-");
        Path cert = folder.resolve("test-only.p12");
        HttpServer plain = null; HttpsServer secure = null;
        try {
            Process keytool = new ProcessBuilder("keytool", "-genkeypair", "-storetype", "PKCS12",
                "-keystore", cert.toString(), "-storepass", "test-only", "-keypass", "test-only",
                "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=LOCAL-TEST-ONLY", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!keytool.waitFor(20, TimeUnit.SECONDS)) { keytool.destroyForcibly().waitFor(); throw new AssertionError("Test certificate generation timed out"); }
            check(keytool.exitValue() == 0, "Test certificate generation failed");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(cert)) { keys.load(in, "test-only".toCharArray()); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "test-only".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            secure.setHttpsConfigurator(new HttpsConfigurator(context));
            final String http = "http://localhost:" + plain.getAddress().getPort();
            final String https = "https://localhost:" + secure.getAddress().getPort();
            final String foreign = "https://127.0.0.1:" + secure.getAddress().getPort();
            plain.createContext("/echo", TestTlsCookieBoundary::echo);
            plain.createContext("/up", e -> redirect(e, https + "/echo"));
            secure.createContext("/echo", TestTlsCookieBoundary::echo);
            secure.createContext("/down", e -> redirect(e, http + "/echo"));
            secure.createContext("/cross", e -> redirect(e, foreign + "/echo"));
            plain.start(); secure.start();
            secure.createContext("/back", e -> redirect(e, http + "/up"));
            Client client = new Client(https + "/PRIVATE_PATH_SECRET?token=QUERY_SECRET#FRAGMENT_SECRET", context);
            client.fetch(https + "/echo", 0, 2);
            check(COOKIE.equals(cookie.get()), "HTTPS positive control lost Cookie");
            check(!empty(referer.get()) && !empty(origin.get()), "HTTPS page headers lost");
            client.fetch(http + "/echo", 0, 2);
            check(empty(cookie.get()), "Cookie sent from HTTPS page to initial HTTP target");
            check(empty(referer.get()) && empty(origin.get()), "HTTPS page headers sent to initial HTTP target");
            client.fetch(https + "/down", 0, 2);
            check(empty(cookie.get()), "Cookie crossed HTTPS downgrade redirect");
            check(empty(referer.get()) && empty(origin.get()), "HTTPS page headers crossed downgrade redirect");
            check("bytes=0-1".equals(range.get()), "Range lost across downgrade");
            client.fetch(https + "/cross", 0, 2);
            check(empty(cookie.get()), "Cookie crossed HTTPS site boundary");
            check((https + "/").equals(referer.get()), "TLS redirect leaked private path/query");
            String signed = "/echo?sig=SIGNED%2Fsecret%2Bfixture&order=2&order=1";
            client.fetch(foreign + signed, -1, -1);
            check(signed.equals(requestUri.get()), "Signed media query changed");
            check((https + "/").equals(referer.get()), "Foreign TLS target received full Referer");
            client.fetch(https + "/back", -1, -1);
            check(empty(referer.get()) && empty(origin.get()), "Downgrade then upgrade restored page headers");
            Path file = folder.resolve("download.mp4");
            client.streamToFile(foreign + signed, file.toFile(), null);
            check("ok".equals(Files.readString(file)), "Native file transfer changed response bytes");
            check((https + "/").equals(referer.get()) && signed.equals(requestUri.get()),
                "Native file transfer leaked page or changed signed query");
            Files.delete(file);
            client.streamToFile(https + "/down", file.toFile(), null);
            check(empty(referer.get()) && empty(origin.get()), "File redirect leaked downgrade headers");
            Files.delete(file);
            Client local = new Client(http + "/page", context);
            local.fetch(http + "/echo", 0, 2);
            check(COOKIE.equals(cookie.get()), "HTTP media-server authentication regressed");
            local.fetch(http + "/up", 0, 2);
            check(COOKIE.equals(cookie.get()), "Safe HTTPS upgrade lost authentication");
            try {
                new Fetcher.Http(https + "/page", "TEST", COOKIE).size(https + "/echo");
                throw new AssertionError("Production client trusted the fixture certificate");
            } catch (SSLHandshakeException expected) {}
            System.out.println("PASS: real TLS/HTTP initial downgrade, redirects, Cookie/Referer/Origin, Range, HTTP compatibility and untrusted TLS rejection");
        } finally {
            if (plain != null) plain.stop(0);
            if (secure != null) secure.stop(0);
            Files.deleteIfExists(cert); Files.deleteIfExists(folder);
        }
    }
}
