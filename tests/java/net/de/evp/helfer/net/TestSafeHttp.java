package de.evp.helfer.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Negativ- und Funktionstests der sicheren Netzwerkschicht (JVM, echte Sockets gegen lokale Testserver).
 * Jede Schutzschicht wird auch einzeln geprüft: URL-Regeln, DNS, Socket-Aufbau, Prüfung der verbundenen Gegenstelle.
 */
public final class TestSafeHttp {
    static int passed, failed;
    static final InetAddress LOOP = loop();

    static InetAddress loop() { try { return InetAddress.getByName("127.0.0.1"); } catch (UnknownHostException e) { throw new AssertionError(e); } }

    interface Body { void run() throws Exception; }

    static void check(String name, Body b) {
        try { b.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable t) { failed++; System.out.println("FAIL " + name + " -> " + t); }
    }

    static void expect(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static NetBlockedException.Reason blocked(Body b) throws Exception {
        try { b.run(); } catch (NetBlockedException e) { return e.reason; }
        throw new AssertionError("not blocked");
    }

    static InetAddress ip(String s) throws UnknownHostException { return InetAddress.getByName(s); }

    // ---------------------------------------------------------------- Testserver

    interface Handler { void handle(String path, Map<String, String> headers, OutputStream out, Socket s) throws IOException; }

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger accepts = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final AtomicReference<List<String>> alpnOffered = new AtomicReference<>();
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

        static Server plain() throws IOException { return new Server(new ServerSocket(0, 50, LOOP)); }

        int port() { return ss.getLocalPort(); }
        String url(String scheme, String path) { return scheme + "://127.0.0.1:" + port() + path; }
        String url(String path) { return url("http", path); }
        Server route(String path, Handler h) { routes.put(path, h); return this; }

        private void serve(Socket s) {
            try (Socket sock = s) {
                sock.setSoTimeout(20000);
                if (sock instanceof SSLSocket) {
                    ((SSLSocket) sock).setHandshakeApplicationProtocolSelector((x, offered) -> {
                        alpnOffered.set(new ArrayList<>(offered));
                        return offered.contains("http/1.1") ? "http/1.1" : null;
                    });
                }
                InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = new BufferedOutputStream(sock.getOutputStream());
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    String path = line.split(" ")[1];
                    Map<String, String> h = new LinkedHashMap<>();
                    String hl;
                    while ((hl = readLine(in)) != null && !hl.isEmpty()) {
                        int c = hl.indexOf(':');
                        if (c > 0) h.put(hl.substring(0, c).trim().toLowerCase(Locale.ROOT), hl.substring(c + 1).trim());
                    }
                    requests.add(path);
                    headers.add(h);
                    Handler handler = routes.get(path);
                    if (handler == null) text(out, 404, "");
                    else handler.handle(path, h, out, sock);
                    out.flush();
                }
            } catch (IOException ignored) { }
        }

        int count(String path) { int n = 0; for (String r : requests) if (r.equals(path)) n++; return n; }

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

    static void text(OutputStream out, int code, String body) throws IOException {
        raw(out, "HTTP/1.1 " + code + " X\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
    }

    static Handler redirect(String location) {
        return (p, h, out, s) -> raw(out, "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n");
    }

    // ---------------------------------------------------------------- Clients

    /** Fester Testresolver: Name -> Antworten (pro Aufruf der nächste Eintrag, der letzte bleibt). */
    static final class FakeDns implements SafeHttpClient.Resolver {
        final Map<String, List<List<InetAddress>>> answers = new LinkedHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        FakeDns answer(String host, InetAddress... a) { answers.computeIfAbsent(host, k -> new ArrayList<>()).add(Arrays.asList(a)); return this; }
        @Override public List<InetAddress> resolve(String host) throws UnknownHostException {
            calls.incrementAndGet();
            List<List<InetAddress>> l = answers.get(host);
            if (l == null || l.isEmpty()) throw new UnknownHostException(host);
            return l.size() > 1 ? l.remove(0) : l.get(0);
        }
    }

    static SafeHttpClient client(Server srv, SafeHttpClient.Resolver dns) {
        return new SafeHttpClient(NetRules.forLocalTests(null, LOOP, srv.port()), dns, null);
    }

    static SafeHttpClient client(Server srv) { return client(srv, new FakeDns()); }

    static String body(SafeResponse r) throws IOException {
        try (SafeResponse x = r; InputStream in = x.body()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static final long T = 10_000;   // Aufrufdauer in den Tests

    // ---------------------------------------------------------------- Tests

    public static void main(String[] args) throws Exception {
        addressPolicy();
        urlRules();
        basicAndCredentials();
        layers();
        dnsRebinding();
        redirects();
        tls(args.length > 0 ? args[0] : null);
        proxies();
        retries();
        timeouts();
        sizes();
        pooling();
        streamingMemory();
        nat64Discovery();
        System.out.println("RESULT safe-http " + (failed == 0 ? "PASS" : "FAIL") + " " + passed + "/" + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    static void addressPolicy() throws Exception {
        String[] allowed = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0",
            "172.15.255.255", "172.32.0.0", "192.0.1.1", "192.169.0.1", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "2606:4700:4700::1111", "2a00:1450:4001::1", "2001:200::1", "3fff:1000::1", "64:ff9b::808:808", "::ffff:8.8.8.8"};
        String[] blockedV4 = {"0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255", "127.0.0.1",
            "127.255.255.254", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.0.170", "192.0.2.1",
            "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "239.255.255.255", "240.0.0.1", "255.255.255.255"};
        String[] blockedV6 = {"::", "::1", "::127.0.0.1", "::8.8.8.8", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:0:10.0.0.1",
            "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::808:808", "fc00::1", "fd12:3456::1", "fe80::1",
            "fec0::1", "ff02::1", "ff0e::1", "100::1", "2001::1", "2001:2::1", "2001:10::1", "2001:20::1", "2001:1ff::1",
            "2001:db8::1", "2002:7f00:1::1", "2002:808:808::1", "3fff::1", "3fff:fff::1", "4000::1", "5f00::1", "fe80::1%1"};
        check("address policy allows global unicast (" + allowed.length + " cases)", () -> {
            for (String a : allowed) expect(AddressPolicy.allowed(ip(a), null), a);
        });
        check("address policy blocks non-global IPv4 (" + blockedV4.length + " cases)", () -> {
            for (String a : blockedV4) expect(!AddressPolicy.allowed(ip(a), null), a);
        });
        check("address policy blocks non-global IPv6 incl. mapped/compatible/NAT64/6to4/Teredo (" + blockedV6.length + " cases)", () -> {
            for (String a : blockedV6) expect(!AddressPolicy.allowed(ip(a), null), a);
        });
        check("raw IPv4-mapped Inet6Address (not converted by Java) is judged by its IPv4 part", () -> {
            byte[] b = new byte[16]; b[10] = (byte) 0xff; b[11] = (byte) 0xff; b[12] = 127; b[15] = 1;
            InetAddress mapped = java.net.Inet6Address.getByAddress(null, b, -1);
            expect(mapped instanceof java.net.Inet6Address, "kept as Inet6Address");
            expect(!AddressPolicy.allowed(mapped, null), "::ffff:127.0.0.1 as Inet6Address");
            b[12] = 8; b[13] = 8; b[14] = 8; b[15] = 8;
            expect(AddressPolicy.allowed(java.net.Inet6Address.getByAddress(null, b, -1), null), "::ffff:8.8.8.8");
        });
        check("network-specific NAT64 prefix: /96 decoded, other lengths blocked", () -> {
            byte[] p = ip("2001:67c:2b0:db32:0:1::").getAddress();
            List<AddressPolicy.Nat64Prefix> nsp = Collections.singletonList(new AddressPolicy.Nat64Prefix(p, 96));
            expect(!AddressPolicy.allowed(ip("2001:67c:2b0:db32:0:1:a00:1"), nsp), "NSP + 10.0.0.1");
            expect(!AddressPolicy.allowed(ip("2001:67c:2b0:db32:0:1:7f00:1"), nsp), "NSP + 127.0.0.1");
            expect(AddressPolicy.allowed(ip("2001:67c:2b0:db32:0:1:808:808"), nsp), "NSP + 8.8.8.8");
            expect(AddressPolicy.allowed(ip("2001:67c:2b0:db32:0:1:a00:1"), null), "without NSP knowledge it is ordinary IPv6");
            List<AddressPolicy.Nat64Prefix> p64 = Collections.singletonList(new AddressPolicy.Nat64Prefix(ip("2001:67c:2b0:db32::").getAddress(), 64));
            expect(!AddressPolicy.allowed(ip("2001:67c:2b0:db32:8:808:808:0"), p64), "/64 NAT64 prefix blocked entirely");
        });
    }

    static void urlRules() throws Exception {
        NetRules r = NetRules.strict(null);
        String[][] bad = {
            {"ftp://example.com/", null}, {"http://example.com:8080/", "PORT"}, {"https://example.com:8443/", "PORT"},
            {"http://user:pw@example.com/", "CREDENTIALS_IN_URL"}, {"http://user@example.com/", "CREDENTIALS_IN_URL"},
            {"http://localhost/", "HOST_NAME"}, {"http://foo.localhost/", "HOST_NAME"}, {"http://printer.local/", "HOST_NAME"},
            {"http://router.lan/", "HOST_NAME"}, {"http://intranet/", "HOST_NAME"}, {"http://metadata.google.internal/", "HOST_NAME"},
            {"http://nas.home.arpa/", "HOST_NAME"}, {"http://127.0.0.1/", "ADDRESS"}, {"http://[::1]/", "ADDRESS"},
            {"http://[::ffff:127.0.0.1]/", "ADDRESS"}, {"http://2130706433/", "ADDRESS"}, {"http://0x7f.0.0.1/", "ADDRESS"},
            {"http://0177.0.0.1/", "ADDRESS"}, {"http://127.1/", "ADDRESS"}, {"http://0x7f000001/", "ADDRESS"}, {"http://1.2.3.4.5/", "ADDRESS"}, {"http://169.254.169.254/latest/meta-data/", "ADDRESS"},
            {"http://[fd00::1]/", "ADDRESS"}, {"http://[64:ff9b::a00:1]/", "ADDRESS"}, {"http://10.0.0.1:443/", "ADDRESS"},
        };
        check("URL rules reject schemes, ports, credentials, local names and private literals (" + bad.length + " cases)", () -> {
            for (String[] c : bad) {
                okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(c[0]);
                if (u == null) continue;   // nicht einmal als URL zulässig
                NetBlockedException.Reason reason = blocked(() -> r.checkUrl(u));
                if (c[1] != null) expect(reason.name().equals(c[1]), c[0] + " -> " + reason);
            }
        });
        check("URL rules accept public http/https on 80/443", () -> {
            r.checkUrl(okhttp3.HttpUrl.parse("http://example.com/a.mp4"));
            r.checkUrl(okhttp3.HttpUrl.parse("https://cdn.example.org:443/b"));
            r.checkUrl(okhttp3.HttpUrl.parse("https://[2606:4700:4700::1111]/"));
        });
        check("obfuscated loopback hosts never reach a socket (OkHttp normalisation + DNS check)", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/x", (p, h, o, s) -> text(o, 200, "x"));
                FakeDns dns = new FakeDns();
                for (String host : new String[] {"0x7f.0.0.1", "0177.0.0.1", "127.1", "2130706433", "017700000001"}) {
                    dns.answer(host, LOOP);
                    SafeHttpClient c = new SafeHttpClient(NetRules.forLocalTests(null, ip("192.0.2.200"), srv.port()), dns, null);
                    try { c.get("http://" + host + ":" + srv.port() + "/x", 1000, T).close(); throw new AssertionError(host + " reached"); }
                    catch (NetBlockedException | UnknownHostException expected) { }
                }
                expect(srv.accepts.get() == 0, "server contacted " + srv.accepts.get());
            }
        });
    }

    static void basicAndCredentials() throws Exception {
        check("GET via test target; no credential headers, fixed headers", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/hello", (p, h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\nSet-Cookie: sid=1\r\nContent-Length: 5\r\n\r\nhello"));
                SafeHttpClient c = client(srv);
                expect(body(c.get(srv.url("/hello"), 100, T)).equals("hello"), "body");
                expect(body(c.get(srv.url("/hello"), 100, T)).equals("hello"), "body 2");
                for (Map<String, String> h : srv.headers) {
                    for (String f : new String[] {"cookie", "cookie2", "authorization", "proxy-authorization"}) expect(!h.containsKey(f), f + " sent");
                    expect("identity".equals(h.get("accept-encoding")), "accept-encoding " + h.get("accept-encoding"));
                    expect(SafeHttpClient.USER_AGENT.equals(h.get("user-agent")), "user-agent");
                }
            }
        });
        check("401 with challenge: no Authorization retry", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/auth", (p, h, o, s) -> raw(o, "HTTP/1.1 401 No\r\nWWW-Authenticate: Basic realm=x\r\nContent-Length: 0\r\n\r\n"));
                try (SafeResponse r = client(srv).get(srv.url("/auth"), 100, T)) { expect(r.code() == 401, "code"); }
                expect(srv.count("/auth") == 1, "requests " + srv.count("/auth"));
            }
        });
        check("Range header and argument checks", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/r", (p, h, o, s) -> text(o, 206, String.valueOf(h.get("range"))));
                SafeHttpClient c = client(srv);
                expect(body(c.get(srv.url("/r"), 10, 19, 100, T)).equals("bytes=10-19"), "closed range");
                expect(body(c.get(srv.url("/r"), 10, -1, 100, T)).equals("bytes=10-"), "open range");
                expect(blocked(() -> c.get(srv.url("/r"), 20, 10, 100, T)) == NetBlockedException.Reason.BAD_ARGUMENT, "reversed range");
                expect(blocked(() -> c.get(srv.url("/r"), 0, T)) == NetBlockedException.Reason.BAD_ARGUMENT, "maxBytes 0");
                expect(blocked(() -> c.get(srv.url("/r"), 10, SafeHttpClient.MAX_CALL_TIMEOUT_MS + 1)) == NetBlockedException.Reason.BAD_ARGUMENT, "timeout");
            }
        });
    }

    static void layers() throws Exception {
        check("socket layer alone blocks a private target (no TCP connection)", () -> {
            try (Server srv = Server.plain()) {
                OkHttpClient raw = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .socketFactory(new GuardedSocketFactory(NetRules.strict(null))).build();   // OkHttp-Standard-DNS, keine URL-Prüfung
                try (Response r = raw.newCall(new Request.Builder().url(srv.url("/")).build()).execute()) { throw new AssertionError("connected"); }
                catch (IOException e) { expect(NetBlockedException.find(e) != null, "not a policy rejection: " + e); }
                expect(srv.accepts.get() == 0, "TCP accepts " + srv.accepts.get());
            }
        });
        check("socket layer: TCP to an allowed address but a non-80/443 port is blocked", () -> {
            Socket s = new GuardedSocketFactory(NetRules.strict(null)).createSocket();
            NetBlockedException.Reason r = NetBlockedException.Reason.ADDRESS;
            try { s.connect(new InetSocketAddress(ip("93.184.216.34"), 22), 1000); throw new AssertionError("connected"); }
            catch (NetBlockedException.Connect e) { r = e.reason(); }
            expect(r == NetBlockedException.Reason.PORT, "reason " + r);
        });
        check("socket layer: unresolved endpoints are refused (no implicit resolution)", () -> {
            Socket s = new GuardedSocketFactory(NetRules.strict(null)).createSocket();
            try { s.connect(InetSocketAddress.createUnresolved("example.com", 443)); throw new AssertionError("connected"); }
            catch (NetBlockedException.Connect expected) { }
        });
        check("peer-check layer alone blocks before any request byte is sent", () -> {
            try (Server srv = Server.plain()) {
                OkHttpClient raw = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .addNetworkInterceptor(SafeHttpClient.networkGuard(NetRules.strict(null))).build();
                Request req = new Request.Builder().url(srv.url("/")).tag(SafeHttpClient.Attempts.class, new SafeHttpClient.Attempts()).build();
                try (Response r = raw.newCall(req).execute()) { throw new AssertionError("sent"); }
                catch (IOException e) { expect(NetBlockedException.find(e) != null, "not a policy rejection: " + e); }
                Thread.sleep(200);
                expect(srv.requests.isEmpty(), "request reached the server");
            }
        });
        check("DNS layer alone rejects private answers", () -> {
            FakeDns d = new FakeDns().answer("evil.example", ip("10.0.0.1"));
            NetBlockedException.Reason r = null;
            try { SafeHttpClient.dns(NetRules.strict(null), d).lookup("evil.example"); }
            catch (NetBlockedException.Host e) { r = e.reason(); }
            expect(r == NetBlockedException.Reason.ADDRESS, "reason " + r);
        });
    }

    static void dnsRebinding() throws Exception {
        check("DNS rebinding: public then private answer -> second connection refused, no request", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/a", (p, h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1\r\n\r\na"));
                FakeDns dns = new FakeDns().answer("media.example", LOOP).answer("media.example", ip("10.0.0.7"));
                SafeHttpClient c = client(srv, dns);
                String u = "http://media.example:" + srv.port() + "/a";
                expect(body(c.get(u, 10, T)).equals("a"), "first");
                expect(blocked(() -> c.get(u, 10, T).close()) == NetBlockedException.Reason.ADDRESS, "second");
                expect(srv.count("/a") == 1, "requests " + srv.count("/a"));
            }
        });
        check("mixed DNS answer (public + private) is rejected as a whole", () -> {
            try (Server srv = Server.plain()) {
                FakeDns dns = new FakeDns().answer("mixed.example", LOOP, ip("192.168.0.10"));
                expect(blocked(() -> client(srv, dns).get("http://mixed.example:" + srv.port() + "/", 10, T).close()) == NetBlockedException.Reason.ADDRESS, "reason");
                expect(srv.accepts.get() == 0, "connected");
            }
        });
        check("IPv6 special answers: mapped, NAT64-embedded private, ULA, link-local", () -> {
            try (Server srv = Server.plain()) {
                // (::ffff:127.0.0.1 wäre hier die zugelassene Testadresse; gemappte Adressen prüft addressPolicy())
                for (String a : new String[] {"::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::c0a8:1", "fd00::5", "fe80::1", "::1", "2002:a00:1::1", "2001::1"}) {
                    FakeDns dns = new FakeDns().answer("v6.example", ip(a));
                    expect(blocked(() -> client(srv, dns).get("http://v6.example:" + srv.port() + "/", 10, T).close()) == NetBlockedException.Reason.ADDRESS, a);
                }
                expect(srv.accepts.get() == 0, "connected");
            }
        });
    }

    static void redirects() throws Exception {
        check("redirects: private literal, local name, foreign port, credentials, scheme, missing Location, loop", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/ok", (p, h, o, s) -> text(o, 200, "ok"))
                   .route("/r-rel", redirect("/ok"))
                   .route("/r-private", redirect("http://10.0.0.1/admin"))
                   .route("/r-meta", redirect("http://169.254.169.254/latest/meta-data/"))
                   .route("/r-v6", redirect("http://[::1]:" + srv.port() + "/ok"))
                   .route("/r-local", redirect("http://localhost:" + srv.port() + "/ok"))
                   .route("/r-port", redirect("http://127.0.0.1:" + (srv.port() + 1) + "/ok"))
                   .route("/r-cred", redirect("http://u:p@127.0.0.1:" + srv.port() + "/ok"))
                   .route("/r-ftp", redirect("ftp://example.com/x"))
                   .route("/r-file", redirect("file:///etc/passwd"))
                   .route("/r-none", (p, h, o, s) -> raw(o, "HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n"))
                   .route("/r-loop", redirect("/r-loop"));
                SafeHttpClient c = client(srv);
                expect(body(c.get(srv.url("/r-rel"), 10, T)).equals("ok"), "relative redirect followed");
                Object[][] cases = {
                    {"/r-private", NetBlockedException.Reason.ADDRESS}, {"/r-meta", NetBlockedException.Reason.ADDRESS},
                    {"/r-v6", NetBlockedException.Reason.ADDRESS}, {"/r-local", NetBlockedException.Reason.HOST_NAME},
                    {"/r-port", NetBlockedException.Reason.PORT}, {"/r-cred", NetBlockedException.Reason.CREDENTIALS_IN_URL},
                    {"/r-ftp", NetBlockedException.Reason.REDIRECT_TARGET}, {"/r-file", NetBlockedException.Reason.REDIRECT_TARGET},
                    {"/r-none", NetBlockedException.Reason.REDIRECT_WITHOUT_LOCATION}, {"/r-loop", NetBlockedException.Reason.TOO_MANY_REDIRECTS},
                };
                for (Object[] k : cases) {
                    NetBlockedException.Reason r = blocked(() -> c.get(srv.url((String) k[0]), 10, T).close());
                    expect(r == k[1], k[0] + " -> " + r);
                }
                expect(srv.count("/r-loop") == SafeHttpClient.MAX_REDIRECTS + 1, "loop requests " + srv.count("/r-loop"));
                expect(srv.count("/ok") == 1, "/ok reached " + srv.count("/ok") + " times");
            }
        });
    }

    static void tls(String keystoreDir) throws Exception {
        if (keystoreDir == null) { check("TLS tests need a throwaway keystore (run.sh creates one)", () -> { throw new AssertionError("missing"); }); return; }
        char[] pw = "throwaway".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(keystoreDir, "server.p12"))) { ks.load(in, pw); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", ks.getCertificate(ks.aliases().nextElement()));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        X509TrustManager tm = (X509TrustManager) tmf.getTrustManagers()[0];
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, new javax.net.ssl.TrustManager[] {tm}, null);
        SafeHttpClient.TestTls testTls = new SafeHttpClient.TestTls(clientCtx.getSocketFactory(), tm, null);
        check("HTTPS: TLS works, ALPN offers only http/1.1, HTTPS->HTTP redirect refused, HTTPS->HTTPS allowed", () -> {
            SSLServerSocket ss = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 50, LOOP);
            try (Server tlsSrv = new Server(ss); Server plain = Server.plain()) {
                plain.route("/ok", (p, h, o, s) -> text(o, 200, "plain"));
                tlsSrv.route("/ok", (p, h, o, s) -> text(o, 200, "tls"))
                      .route("/down", redirect(plain.url("/ok")))
                      .route("/same", redirect("/ok"));
                SafeHttpClient c = new SafeHttpClient(NetRules.forLocalTests(null, LOOP, tlsSrv.port()), new FakeDns(), testTls);
                expect(body(c.get(tlsSrv.url("https", "/ok"), 10, T)).equals("tls"), "https body");
                List<String> offered = tlsSrv.alpnOffered.get();
                expect(offered != null && offered.equals(Collections.singletonList("http/1.1")), "ALPN offered " + offered);
                expect(body(c.get(tlsSrv.url("https", "/same"), 10, T)).equals("tls"), "https->https");
                expect(blocked(() -> c.get(tlsSrv.url("https", "/down"), 10, T).close()) == NetBlockedException.Reason.DOWNGRADE, "downgrade");
                expect(plain.accepts.get() == 0, "plain server contacted");
            }
        });
        check("HTTPS: certificate not trusted by the system trust store is refused (strict client)", () -> {
            SSLServerSocket ss = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 50, LOOP);
            try (Server tlsSrv = new Server(ss)) {
                tlsSrv.route("/ok", (p, h, o, s) -> text(o, 200, "tls"));
                SafeHttpClient c = new SafeHttpClient(NetRules.forLocalTests(null, LOOP, tlsSrv.port()), new FakeDns(), null);
                try { c.get(tlsSrv.url("https", "/ok"), 10, T).close(); throw new AssertionError("untrusted certificate accepted"); }
                catch (javax.net.ssl.SSLException expected) { }
                expect(tlsSrv.count("/ok") == 0, "request sent over untrusted TLS");
            }
        });
    }

    static void proxies() throws Exception {
        check("system HTTP/HTTPS/SOCKS proxy settings and default ProxySelector are ignored", () -> {
            try (Server srv = Server.plain(); ServerSocket proxy = new ServerSocket(0, 10, LOOP)) {
                srv.route("/p", (p, h, o, s) -> text(o, 200, "direct"));
                AtomicInteger proxyHits = new AtomicInteger();
                Thread t = new Thread(() -> { while (true) { try (Socket s = proxy.accept()) { proxyHits.incrementAndGet(); } catch (IOException e) { return; } } });
                t.setDaemon(true); t.start();
                String pp = String.valueOf(proxy.getLocalPort());
                String[] props = {"http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "socksProxyHost", "socksProxyPort", "http.nonProxyHosts"};
                Map<String, String> old = new LinkedHashMap<>();
                for (String k : props) old.put(k, System.getProperty(k));
                ProxySelector oldSel = ProxySelector.getDefault();
                try {
                    System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", pp);
                    System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", pp);
                    System.setProperty("socksProxyHost", "127.0.0.1"); System.setProperty("socksProxyPort", pp);
                    System.setProperty("http.nonProxyHosts", "");
                    ProxySelector.setDefault(new ProxySelector() {
                        @Override public List<Proxy> select(URI uri) {
                            return Collections.singletonList(new Proxy(uri.getScheme().startsWith("socket") ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                                new InetSocketAddress(LOOP, proxy.getLocalPort())));
                        }
                        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
                    });
                    // Gegenprobe: ein gewöhnlicher Socket folgt dem SOCKS-ProxySelector …
                    try (Socket plain = new Socket()) { plain.connect(new InetSocketAddress(LOOP, srv.port()), 2000); } catch (IOException ignored) { }
                    Thread.sleep(200);
                    int viaPlainSocket = proxyHits.get();
                    // … der geprüfte Socket und der Client nicht.
                    try (Socket g = new GuardedSocketFactory(NetRules.forLocalTests(null, LOOP, srv.port())).createSocket()) {
                        g.connect(new InetSocketAddress(LOOP, srv.port()), 2000);
                    }
                    expect(body(client(srv).get(srv.url("/p"), 100, T)).equals("direct"), "direct body");
                    Thread.sleep(200);
                    expect(viaPlainSocket >= 1, "control: new Socket() did not use the SOCKS selector (" + viaPlainSocket + ")");
                    expect(proxyHits.get() == viaPlainSocket, "guarded socket/client used the proxy: " + (proxyHits.get() - viaPlainSocket));
                } finally {
                    for (Map.Entry<String, String> e : old.entrySet()) {
                        if (e.getValue() == null) System.clearProperty(e.getKey()); else System.setProperty(e.getKey(), e.getValue());
                    }
                    ProxySelector.setDefault(oldSel);
                }
            }
        });
    }

    static void retries() throws Exception {
        check("no automatic repeats: 503 Retry-After:0, 408, 421, 500, connection closed after request", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/503", (p, h, o, s) -> raw(o, "HTTP/1.1 503 X\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n"))
                   .route("/408", (p, h, o, s) -> raw(o, "HTTP/1.1 408 X\r\nContent-Length: 0\r\n\r\n"))
                   .route("/421", (p, h, o, s) -> raw(o, "HTTP/1.1 421 X\r\nContent-Length: 0\r\n\r\n"))
                   .route("/500", (p, h, o, s) -> raw(o, "HTTP/1.1 500 X\r\nContent-Length: 0\r\n\r\n"))
                   .route("/drop", (p, h, o, s) -> s.close());
                SafeHttpClient c = client(srv);
                for (String p : new String[] {"/503", "/408", "/421", "/500"}) {
                    try (SafeResponse r = c.get(srv.url(p), 10, T)) { expect(r.code() == Integer.parseInt(p.substring(1)), p + " code " + r.code()); }
                    expect(srv.count(p) == 1, p + " requests " + srv.count(p));
                }
                try { c.get(srv.url("/drop"), 10, T).close(); throw new AssertionError("no error"); } catch (IOException expected) { }
                Thread.sleep(200);
                expect(srv.count("/drop") == 1, "/drop requests " + srv.count("/drop"));
            }
        });
        check("backstop: a second network attempt within one call is refused (simulated follow-up)", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/a", redirect("/b")).route("/b", (p, h, o, s) -> text(o, 200, "b"));
                OkHttpClient raw = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(true)
                    .addNetworkInterceptor(SafeHttpClient.networkGuard(NetRules.forLocalTests(null, LOOP, srv.port()))).build();
                Request req = new Request.Builder().url(srv.url("/a")).tag(SafeHttpClient.Attempts.class, new SafeHttpClient.Attempts()).build();
                try (Response r = raw.newCall(req).execute()) { throw new AssertionError("follow-up sent"); }
                catch (IOException e) { expect(NetBlockedException.find(e).reason == NetBlockedException.Reason.AUTOMATIC_RETRY, "reason " + e); }
                expect(srv.count("/b") == 0, "/b reached");
            }
        });
        check("credential headers are refused even if a request carried them", () -> {
            try (Server srv = Server.plain()) {
                OkHttpClient raw = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .addNetworkInterceptor(SafeHttpClient.networkGuard(NetRules.forLocalTests(null, LOOP, srv.port()))).build();
                for (String h : SafeHttpClient.FORBIDDEN_REQUEST_HEADERS) {
                    Request req = new Request.Builder().url(srv.url("/")).header(h, "secret").tag(SafeHttpClient.Attempts.class, new SafeHttpClient.Attempts()).build();
                    try (Response r = raw.newCall(req).execute()) { throw new AssertionError(h + " sent"); }
                    catch (IOException e) { expect(NetBlockedException.find(e).reason == NetBlockedException.Reason.CREDENTIAL_HEADER, h); }
                }
                expect(srv.requests.isEmpty(), "request reached the server");
            }
        });
    }

    static void timeouts() throws Exception {
        check("call deadline bounds a server that never answers", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/stall", (p, h, o, s) -> { try { Thread.sleep(15000); } catch (InterruptedException ignored) { } });
                long t0 = System.nanoTime();
                try { client(srv).get(srv.url("/stall"), 10, 1000).close(); throw new AssertionError("no timeout"); }
                catch (InterruptedIOException expected) { }
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                expect(ms < 3000, "took " + ms + " ms");
            }
        });
        check("call deadline bounds slowly dripped headers", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/drip", (p, h, o, s) -> {
                    raw(o, "HTTP/1.1 200 OK\r\n"); o.flush();
                    for (int i = 0; i < 100; i++) { raw(o, "X-" + i + ": y\r\n"); o.flush(); try { Thread.sleep(100); } catch (InterruptedException e) { return; } }
                });
                long t0 = System.nanoTime();
                try { client(srv).get(srv.url("/drip"), 10, 1000).close(); throw new AssertionError("no timeout"); }
                catch (InterruptedIOException expected) { }
                expect(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000, "slow");
            }
        });
        check("call deadline bounds a slowly dripped body", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/slowbody", (p, h, o, s) -> {
                    raw(o, "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n"); o.flush();
                    for (int i = 0; i < 1000; i++) { o.write('x'); o.flush(); try { Thread.sleep(50); } catch (InterruptedException e) { return; } }
                });
                long t0 = System.nanoTime();
                try (SafeResponse r = client(srv).get(srv.url("/slowbody"), 5000, 1000)) { readAll(r.body()); throw new AssertionError("no timeout"); }
                catch (InterruptedIOException expected) { }
                expect(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000, "slow");
            }
        });
        check("configured connect/read/write timeouts are bounded", () -> {
            expect(SafeHttpClient.CONNECT_TIMEOUT_MS <= 10_000 && SafeHttpClient.READ_TIMEOUT_MS <= 30_000 && SafeHttpClient.WRITE_TIMEOUT_MS <= 15_000, "timeouts");
        });
    }

    static void sizes() throws Exception {
        check("header and body limits", () -> {
            try (Server srv = Server.plain()) {
                StringBuilder big = new StringBuilder();
                for (int i = 0; i < 700; i++) big.append("X-Pad-").append(i).append(": ").append("p".repeat(50)).append("\r\n");
                srv.route("/bighead", (p, h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\n" + big + "Content-Length: 0\r\n\r\n"))
                   .route("/len", (p, h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n"))
                   .route("/chunked", (p, h, o, s) -> {
                       raw(o, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
                       for (int i = 0; i < 20; i++) raw(o, "400\r\n" + "c".repeat(1024) + "\r\n");
                       raw(o, "0\r\n\r\n");
                   })
                   .route("/gzip", (p, h, o, s) -> raw(o, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 4\r\n\r\nabcd"));
                SafeHttpClient c = client(srv);
                expect(blocked(() -> c.get(srv.url("/bighead"), 10, T).close()) == NetBlockedException.Reason.HEADERS_TOO_LARGE, "headers");
                expect(blocked(() -> c.get(srv.url("/len"), 1000, T).close()) == NetBlockedException.Reason.BODY_TOO_LARGE, "declared length");
                try (SafeResponse r = c.get(srv.url("/chunked"), 10_000, T)) { readAll(r.body()); throw new AssertionError("chunked over limit"); }
                catch (NetBlockedException e) { expect(e.reason == NetBlockedException.Reason.BODY_TOO_LARGE, "reason"); }
                try (SafeResponse r = c.get(srv.url("/chunked"), 20 * 1024, T)) { expect(readAll(r.body()).length == 20 * 1024, "exact limit"); }
                try (SafeResponse r = c.get(srv.url("/gzip"), 100, T)) { expect(new String(readAll(r.body()), StandardCharsets.ISO_8859_1).equals("abcd"), "no transparent gzip"); }
            }
        });
    }

    static void pooling() throws Exception {
        check("connection pool: keep-alive reuse, each reuse re-checked; rebinding cannot move an open connection", () -> {
            try (Server srv = Server.plain()) {
                srv.route("/k", (p, h, o, s) -> text(o, 200, "k"));
                FakeDns dns = new FakeDns().answer("pool.example", LOOP).answer("pool.example", ip("10.9.9.9"));
                SafeHttpClient c = client(srv, dns);
                String u = "http://pool.example:" + srv.port() + "/k";
                for (int i = 0; i < 3; i++) expect(body(c.get(u, 10, T)).equals("k"), "body " + i);
                expect(srv.accepts.get() == 1, "connections " + srv.accepts.get());
                expect(dns.calls.get() == 1, "DNS lookups " + dns.calls.get());
            }
        });
    }

    static void streamingMemory() throws Exception {
        check("streaming 256 MiB with bounded heap (run with -Xmx48m)", () -> {
            final long total = 256L << 20;
            try (Server srv = Server.plain()) {
                srv.route("/big", (p, h, o, s) -> {
                    raw(o, "HTTP/1.1 200 OK\r\nContent-Length: " + total + "\r\n\r\n");
                    byte[] chunk = new byte[64 << 10];
                    for (long off = 0; off < total; off += chunk.length) { Arrays.fill(chunk, (byte) (off >>> 16)); o.write(chunk); }
                });
                Runtime rt = Runtime.getRuntime();
                long maxUsed = 0, n = 0;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (SafeResponse r = client(srv).get(srv.url("/big"), total, 120_000); InputStream in = r.body()) {
                    byte[] buf = new byte[64 << 10];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        md.update(buf, 0, k);
                        n += k;
                        if ((n & ((8 << 20) - 1)) < k) maxUsed = Math.max(maxUsed, rt.totalMemory() - rt.freeMemory());
                    }
                }
                expect(n == total, "bytes " + n);
                System.out.println("  MEASURE streamed=" + (n >> 20) + "MiB maxHeapUsed=" + (maxUsed >> 10) + "KiB maxHeap=" + (rt.maxMemory() >> 20) + "MiB");
            }
        });
    }

    /** Android 8–10: NAT64-Erkennung nach RFC 7050; jede Unklarheit sperrt IPv6 (sicher fehlschlagen). */
    static void nat64Discovery() throws Exception {
        check("NAT64 discovery (RFC 7050) fails closed", () -> {
            List<InetAddress> v4only = Arrays.asList(ip("192.0.0.170"), ip("192.0.0.171"));
            // Kein DNS64, Netz mit IPv4: kein netzspezifisches Präfix, IPv6 normal.
            expect(Nat64Discovery.decide(true, v4only).isEmpty(), "dual stack without DNS64");
            expect(AddressPolicy.allowed(ip("2606:4700::1111"), Nat64Discovery.decide(true, v4only)), "public v6 allowed");
            // Reines IPv6-Netz ohne DNS64 (464XLAT möglich, Präfix unbekannt) und gescheiterte Abfrage: IPv6 gesperrt.
            for (List<AddressPolicy.Nat64Prefix> p : Arrays.asList(Nat64Discovery.decide(false, v4only), Nat64Discovery.decide(true, null),
                     Nat64Discovery.decide(false, null), Nat64Discovery.decide(false, Collections.emptyList()))) {
                expect(!AddressPolicy.allowed(ip("2606:4700::1111"), p), "v6 must be blocked when NAT64 is unknown");
                expect(!AddressPolicy.allowed(ip("2001:db8:64::10.0.0.1"), p), "nsp-embedded private v4");
                expect(AddressPolicy.allowed(ip("8.8.8.8"), p), "v4 still allowed");
                expect(!AddressPolicy.allowed(ip("10.0.0.1"), p), "private v4 still blocked");
            }
            // DNS64 mit netzspezifischem /96-Präfix: Adressen darin nach eingebettetem IPv4 beurteilt.
            List<AddressPolicy.Nat64Prefix> nsp = Nat64Discovery.decide(false, Arrays.asList(ip("2a01:4f8:64::c000:aa"), ip("2a01:4f8:64::c000:ab")));
            expect(nsp.size() == 2 && nsp.get(0).bits == 96, "nsp /96 found");
            expect(!AddressPolicy.allowed(ip("2a01:4f8:64::a00:1"), nsp), "nsp + 10.0.0.1 blocked");
            expect(!AddressPolicy.allowed(ip("2a01:4f8:64::c0a8:101"), nsp), "nsp + 192.168.1.1 blocked");
            expect(!AddressPolicy.allowed(ip("2a01:4f8:64::7f00:1"), nsp), "nsp + 127.0.0.1 blocked");
            expect(AddressPolicy.allowed(ip("2a01:4f8:64::808:808"), nsp), "nsp + 8.8.8.8 allowed");
            expect(AddressPolicy.allowed(ip("2a01:4f8:1::1"), nsp), "other v6 allowed");
            // Bekanntes Präfix 64:ff9b::/96 (ohnehin dekodiert).
            List<AddressPolicy.Nat64Prefix> wkp = Nat64Discovery.decide(false, Arrays.asList(ip("64:ff9b::c000:aa")));
            expect(wkp.size() == 1 && wkp.get(0).bits == 96, "wkp");
            expect(!AddressPolicy.allowed(ip("64:ff9b::a00:1"), wkp), "wkp + private");
            // Andere RFC-6052-Längen: ganzes Präfix gesperrt (nicht dekodiert).
            String[][] lengths = {
                {"2001:4860:c000:aa::", "32", "2001:4860:808:808::"},
                {"2a01:4f8:c0:0:aa::", "40", "2a01:4f8:8:808:8::"},          // /40: 192.0.0 | u | 170
                {"2a01:4f8:6400:c000:0:aa00::", "48", "2a01:4f8:6400:808:8::"},
                {"2a01:4f8:6400:c0:0:aa::", "56", "2a01:4f8:6400:8:8:808::"},
                {"2a01:4f8:6400:1:c0:0:aa00:0", "64", "2a01:4f8:6400:1:8:808:800:0"},
            };
            for (String[] l : lengths) {
                List<AddressPolicy.Nat64Prefix> p = Nat64Discovery.decide(true, Arrays.asList(ip(l[0])));
                expect(p.size() == 1 && p.get(0).bits == Integer.parseInt(l[1]), "length " + l[1] + ": " + (p.isEmpty() ? "none" : p.get(0).bits));
                expect(!AddressPolicy.allowed(ip(l[2]), p), "/" + l[1] + " prefix must be blocked entirely");
            }
            // AAAA ohne erkennbare Einbettung (unbekannte Synthese): IPv6 gesperrt.
            List<AddressPolicy.Nat64Prefix> odd = Nat64Discovery.decide(true, Arrays.asList(ip("2a01:4f8::1")));
            expect(!AddressPolicy.allowed(ip("2606:4700::1111"), odd), "unexplained AAAA");
            // "u"-Oktett gesetzt: keine gültige Einbettung für Längen < 96.
            expect(Nat64Discovery.prefixOf(ip("2a01:4f8:c0:0:ffaa::").getAddress()) == null, "u octet");
            // IPv4-mapped Antworten zählen als IPv4.
            expect(Nat64Discovery.decide(true, Arrays.asList(ip("::ffff:192.0.0.170"))).isEmpty(), "mapped");
        });
    }
}
