package de.evp.helfer;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTP header capture, not a simulated browser or connection. */
public class TestReferrerPrivacy {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true"); // test-only Origin positive control
        AtomicReference<String> ref = new AtomicReference<>(), origin = new AtomicReference<>(), cookie = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String clean = base + "/watch%20one?quality=hd";
        String page = base.replace("http://", "http://fixture-user:fixture-password@") +
            "/watch%20one?quality=hd#access_token=fixture-fragment";
        server.createContext("/echo", e -> {
            ref.set(e.getRequestHeaders().getFirst("Referer"));
            origin.set(e.getRequestHeaders().getFirst("Origin"));
            cookie.set(e.getRequestHeaders().getFirst("Cookie"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        try {
            Fetcher.Http client = new Fetcher.Http(page, "TEST-ONLY", "session=fixture");
            client.fetch(base + "/echo", 0, 2);
            check(clean.equals(ref.get()), "Userinfo or fragment escaped in Referer");
            check(base.equals(origin.get()), "Userinfo escaped in Origin");
            check("session=fixture".equals(cookie.get()), "Referrer sanitizing broke same-site Cookie");
            check(clean.equals(Fetcher.Http.referrerFor(page, base + "/echo")), "DownloadManager header policy differs");
            check(Fetcher.Http.referrerFor("https://example.test/private#fixture", base + "/echo") == null,
                "Referrer sanitizer bypassed TLS downgrade guard");
            check("http://[::1]:8080/a%20b?q=x%2Fy".equals(Fetcher.Http.referrerFor(
                "http://u:p@[::1]:8080/a%20b?q=x%2Fy#fragment", "http://[::1]:8080/video")),
                "IPv6 or raw path/query changed");
            String foreign = base.replace("127.0.0.1", "localhost");
            client.fetch(foreign + "/echo?signature=SIGNED%2Ffixture%2Bvalue", -1, -1);
            check((base + "/").equals(ref.get()), "Cross-origin path/query escaped in Referer");
            check("http://example.test/".equals(Fetcher.Http.referrerFor(
                "http://u:p@example.test/private?SECRET=query#fragment", "http://example.test:8080/video")),
                "Different port received full page URL");
            check("http://example.test/private?SECRET=query".equals(Fetcher.Http.referrerFor(
                "http://u:p@example.test:80/private?SECRET=query#fragment", "http://example.test/video")) ||
                "http://example.test:80/private?SECRET=query".equals(Fetcher.Http.referrerFor(
                "http://u:p@example.test:80/private?SECRET=query#fragment", "http://example.test/video")),
                "Default port same-origin comparison failed");
            check("http://example.test/".equals(Fetcher.Http.referrerFor(
                "http://example.test/private?SECRET=query", "https://example.test/video")),
                "Different scheme received full page URL");
            check(Fetcher.Http.referrerFor("file:///SECRET", base) == null, "Non-HTTP page accepted");
            check(Fetcher.Http.referrerFor(page, "file:///video") == null, "Non-HTTP target accepted");
            System.out.println("PASS: real HTTP strips referrer userinfo/fragment, serializes Origin correctly and preserves Cookie/raw path/query; cross-origin path/query are private");
        } finally { server.stop(0); }
    }
}
