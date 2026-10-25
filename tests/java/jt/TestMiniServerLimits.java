import de.evp.helfer.MiniServer;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/** Actual loopback sockets; reflection only synchronizes accepted worker activity. */
public class TestMiniServerLimits {
    static final String KEY = "fixture-key-not-production";
    static final MiniServer.Handler HANDLER = new MiniServer.Handler() {
        public String get(String path, Map<String,String> p) { return "{\"ok\":true}"; }
        public String push(Map<String,String> p, byte[] body) { return "{\"ok\":true}"; }
    };
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))) { return s.getLocalPort(); }
    }
    static ThreadPoolExecutor pool(MiniServer server) throws Exception {
        java.lang.reflect.Field f = MiniServer.class.getDeclaredField("pool");
        f.setAccessible(true); return (ThreadPoolExecutor)f.get(server);
    }
    static void until(BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(done.getAsBoolean(), "Socket test synchronization timed out");
    }
    static Socket partial(int port) throws Exception {
        Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        s.getOutputStream().write('G'); s.getOutputStream().flush(); return s;
    }
    static String raw(int port, String path, String host, String key) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(2000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host +
                (key == null ? "" : "\r\nX-EVP-Key: " + key) + "\r\n\r\n";
            s.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static void stopClosesReaders() throws Exception {
        int port = freePort(); MiniServer server = new MiniServer(port, KEY, HANDLER);
        Socket client = null;
        List<Socket> active = new ArrayList<>();
        Object queuedTask = null;
        try {
            check(server.start(), "Listener failed");
            ThreadPoolExecutor workers = pool(server);
            for (int i = 0; i < 10; i++) active.add(partial(port));
            until(() -> workers.getActiveCount() == 10);
            client = partial(port);
            until(() -> workers.getQueue().size() == 1);
            queuedTask = workers.getQueue().peek(); // keep GC from masking an unclosed discarded socket
            server.stop(); client.setSoTimeout(1000);
            try { check(client.getInputStream().read() == -1, "Shutdown sent unexpected response"); }
            catch (SocketTimeoutException e) { throw new AssertionError("Accepted connection remained open after stop", e); }
            catch (SocketException closed) {}
            for (Socket reader : active) {
                reader.setSoTimeout(1000);
                try { check(reader.getInputStream().read() == -1, "Active reader survived shutdown"); }
                catch (SocketException closed) {}
            }
        } finally {
            java.lang.ref.Reference.reachabilityFence(queuedTask);
            if (client != null) client.close();
            for (Socket reader : active) reader.close();
            server.stop();
        }
    }
    static void overloadRecovers() throws Exception {
        int port = freePort(); MiniServer server = new MiniServer(port, KEY, HANDLER);
        List<Socket> clients = new ArrayList<>();
        try {
            check(server.start(), "Listener failed");
            ThreadPoolExecutor workers = pool(server);
            for (int i = 0; i < 10; i++) clients.add(partial(port));
            until(() -> workers.getActiveCount() == 10);
            for (int i = 0; i < 48; i++) clients.add(partial(port));
            until(() -> workers.getQueue().size() > 0);
            check(workers.getQueue().size() <= 32, "Unauthenticated socket queue is unbounded");
            int closed = 0;
            for (Socket client : clients) {
                client.setSoTimeout(20);
                try { if (client.getInputStream().read() < 0) closed++; }
                catch (SocketTimeoutException busy) {}
                catch (SocketException rejected) { closed++; }
            }
            check(closed > 0, "Overload connections were not rejected");
            for (Socket client : clients) client.close();
            until(() -> workers.getActiveCount() == 0 && workers.getQueue().isEmpty());
            check(raw(port, "/ping", "127.0.0.1", KEY).startsWith("HTTP/1.1 200"), "Listener did not recover");
            check(raw(port, "/ping", "127.0.0.1", null).startsWith("HTTP/1.1 403"), "Missing key accepted");
            check(raw(port, "/ping", "127.0.0.1", "wrong-key").startsWith("HTTP/1.1 403"), "Wrong key accepted");
            check(raw(port, "/ping", "evil.example", KEY).startsWith("HTTP/1.1 403"), "Foreign Host accepted");
            String pair = raw(port, "/pair", "127.0.0.1", null);
            check(pair.startsWith("HTTP/1.1 200") && !pair.contains(KEY), "Pair page authentication/secret exposure regressed");
        } finally { for (Socket client : clients) client.close(); server.stop(); }
    }
    public static void main(String[] args) throws Exception {
        stopClosesReaders();
        if (args.length == 0 || !"--stop-only".equals(args[0])) overloadRecovers();
        System.out.println("PASS: accepted socket shutdown, bounded unauthenticated queue, overload recovery and pairing/authentication");
    }
}
