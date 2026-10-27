package de.evp.pakepoc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import poc.Core;

/**
 * Loopback test server. Binds only 127.0.0.1:47899 (protocol) and
 * 127.0.0.1:47900 (one static probe page, different origin). Endpoints:
 * POST /p1/pair/m0, /p1/pair/m2, /p1/ch/hs, /p1/ch/rec. Application ops:
 * "ping" and bounded binary "dummy" records only. No downloads, URLs,
 * cookies, legacy bearer or production endpoints.
 *
 * Rogue mode simulates a foreign listener on the same port for a manual
 * device test: it records sizes and plaintext hits, forges replies, and
 * holds only an ephemeral identity.
 */
final class PocServer {
    interface Host {
        long now();
        void event(String line);
        void persist();
    }

    static final int PORT = 47899;
    static final int PROBE_PORT = 47900;
    static final int MAX_BODY = 65535 + 21;
    static final int MAX_HEADER = 8 * 1024;
    static final long PAIR_TTL_MS = 120_000;
    static final Pattern EXT_ORIGIN = Pattern.compile("(chrome-extension|moz-extension)://[a-z0-9-]{1,64}");
    static final Object CORE = new Object();   // the Rust host is process-global

    private final Host host;
    private final boolean rogue;
    private final byte[] probeHtml;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
    private ServerSocket server, probe;
    final Map<String, Integer> rogueSeen = new HashMap<>();
    volatile int roguePlaintextHits;

    PocServer(Host host, boolean rogue, byte[] probeHtml) {
        this.host = host;
        this.rogue = rogue;
        this.probeHtml = probeHtml;
    }

    synchronized boolean start() {
        try {
            server = bind(PORT);
            if (!rogue && probeHtml != null) probe = bind(PROBE_PORT);
        } catch (Exception e) {
            stop();
            return false;
        }
        accept(server, false);
        if (probe != null) accept(probe, true);
        return true;
    }

    private static ServerSocket bind(int port) throws Exception {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        return s;
    }

    private void accept(ServerSocket ss, boolean isProbe) {
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    try { pool.execute(() -> handle(s, isProbe)); }
                    catch (RejectedExecutionException full) { close(s); }
                } catch (Exception closed) { return; }
            }
        }, isProbe ? "pakepoc-probe" : "pakepoc-server");
        t.start();
    }

    synchronized void stop() {
        for (ServerSocket s : new ServerSocket[]{server, probe}) {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
        pool.shutdownNow();
    }

    private static void close(Socket s) { try { s.close(); } catch (Exception ignored) {} }

    // ------------------------------------------------------------ HTTP

    private static final class Req {
        String method, path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private static Req read(InputStream in) throws Exception {
        ByteArrayOutputStream hb = new ByteArrayOutputStream(512);
        int b, last4 = 0;
        while (last4 != 0x0d0a0d0a) {   // "\r\n\r\n"
            if ((b = in.read()) < 0) throw new SecurityException("Truncated");
            hb.write(b);
            if (hb.size() > MAX_HEADER) throw new SecurityException("HeaderTooLarge");
            last4 = (last4 << 8) | b;
        }
        String[] lines = hb.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3) throw new SecurityException("RequestLine");
        Req r = new Req();
        r.method = first[0];
        r.path = first[1];
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            String k = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT);
            if (r.headers.containsKey(k)) throw new SecurityException("DuplicateHeader");
            r.headers.put(k, lines[i].substring(c + 1).trim());
        }
        if (r.headers.containsKey("transfer-encoding")) throw new SecurityException("Framing");
        if ("POST".equals(r.method)) {
            String cl = r.headers.get("content-length");
            if (cl == null || !cl.matches("[0-9]{1,6}")) throw new SecurityException("Framing");
            int n = Integer.parseInt(cl);
            if (n > MAX_BODY) throw new SecurityException("TooLarge");
            r.body = new byte[n];
            int at = 0;
            while (at < n) {
                int k = in.read(r.body, at, n - at);
                if (k < 0) throw new SecurityException("Truncated");
                at += k;
            }
        }
        return r;
    }

    private static void reply(Socket s, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Browser-facing gate: DoS guard against web pages, NOT authentication. */
    private String gate(Req r) {
        if ("OPTIONS".equals(r.method)) return "preflight";
        if (!"POST".equals(r.method)) return "method";
        if (!("127.0.0.1:" + PORT).equals(r.headers.get("host"))) return "host";
        if (!"application/octet-stream".equals(r.headers.get("content-type"))) return "content-type";
        if (!"2".equals(r.headers.get("x-kadri-helper"))) return "custom-header";
        String origin = r.headers.get("origin");
        if (origin != null && !EXT_ORIGIN.matcher(origin).matches()) return "origin";
        return null;
    }

    private void handle(Socket s, boolean isProbe) {
        String path = "?";
        try {
            s.setSoTimeout(10_000);
            s.setTcpNoDelay(true);   // avoid Nagle/delayed-ACK stalls on small replies
            Req r = read(s.getInputStream());
            path = r.path;
            if (isProbe) {
                boolean ok = "GET".equals(r.method) && "/kadri-poc-probe.html".equals(r.path);
                reply(s, ok ? 200 : 404, "text/html; charset=utf-8", ok ? probeHtml : new byte[0]);
                return;
            }
            if (rogue) { rogue(s, r); return; }
            String why = gate(r);
            if (why != null) {
                host.event("GATE_REJECT " + r.method + " " + r.path + " " + why);
                reply(s, 403, "application/octet-stream", new byte[0]);
                return;
            }
            byte[] out = route(r.path, r.body);
            reply(s, 200, "application/octet-stream", out);
        } catch (SecurityException e) {
            host.event("REJECT " + path + " " + e.getMessage());
            try { reply(s, "UnknownSession".equals(e.getMessage()) ? 404 : 400, "application/octet-stream", new byte[0]); } catch (Exception ignored) {}
        } catch (Exception e) {
            host.event("ERROR " + path + " " + e.getClass().getSimpleName());
            try { reply(s, 500, "application/octet-stream", new byte[0]); } catch (Exception ignored) {}
        } finally {
            close(s);
        }
    }

    private static String hex8(byte[] b, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + 4; i++) sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
        return sb.toString();
    }

    private byte[] route(String path, byte[] body) {
        long now = host.now();
        switch (path) {
            case "/p1/pair/m0":
                synchronized (CORE) { return Core.windowOnM0(body, now); }
            case "/p1/pair/m2": {
                byte[] r;
                synchronized (CORE) { r = Core.windowOnM2(body, now); }
                host.persist();
                host.event("PAIRED_PENDING reg=" + hex8(r, 0) + "…");
                return Arrays.copyOfRange(r, 16, r.length);
            }
            case "/p1/ch/hs": {
                byte[] hs2;
                synchronized (CORE) { hs2 = Core.hostHandshake(body, now); }
                host.event("HS_HALF_OPEN reg=" + hex8(body, 5) + "…");
                return hs2;
            }
            case "/p1/ch/rec": {
                byte[] r;
                synchronized (CORE) { r = Core.hostOpen(body, now); }
                host.persist();
                byte[] sid = Arrays.copyOfRange(r, 0, 16);
                return dispatch(sid, new String(r, 17, r.length - 17, StandardCharsets.UTF_8), now);
            }
            default:
                throw new SecurityException("UnknownPath");
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"([^\"]*)\"|(-?\\d+))").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    private byte[] dispatch(byte[] sid, String json, long now) {
        String op = field(json, "op");
        synchronized (CORE) {
            if ("ping".equals(op)) {
                host.event("REC op=ping");
                return Core.hostSealControl(sid, "{\"ok\":true,\"helper\":\"android-test-app\"}".getBytes(StandardCharsets.UTF_8), now);
            }
            if ("dummy".equals(op)) {
                int t = Integer.parseInt(field(json, "transfer"));
                long size = Math.min(Long.parseLong(field(json, "size")), 64L << 20); // bounded dummy data only
                long off = Long.parseLong(field(json, "offset"));
                if (off < 0) throw new SecurityException("Malformed");
                if (off >= size) return Core.hostSealEnd(sid, t, size, now);
                int n = (int) Math.min(60 * 1024, size - off);
                byte[] d = new byte[n];
                for (int i = 0; i < n; i++) d[i] = (byte) (((off + i) * 31 + 7 + t) & 0xff);
                return Core.hostSealMedia(sid, t, off, d, now);
            }
            return Core.hostSealControl(sid, "{\"ok\":false}".getBytes(StandardCharsets.UTF_8), now);
        }
    }

    // ------------------------------------------------------------ foreign listener simulation

    private static final String[] PLAINTEXT_MARKERS = {"\"op\"", "ping", "dummy", "X-EVP-Key"};

    private void rogue(Socket s, Req r) throws Exception {
        synchronized (rogueSeen) { rogueSeen.merge(r.method + " " + r.path, 1, Integer::sum); }
        String text = new String(r.body, StandardCharsets.ISO_8859_1) + r.headers.toString();
        for (String m : PLAINTEXT_MARKERS) if (text.contains(m)) roguePlaintextHits++;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        host.event("ROGUE_RX " + r.path + " len=" + r.body.length + " sha256=" + hex8(md.digest(r.body), 0) + "…");
        long now = host.now();
        SecureRandom rnd = new SecureRandom();
        byte[] out;
        switch (r.path) {
            case "/p1/pair/m0":   // play the helper role with a guessed code
                synchronized (CORE) { Core.windowOpen("00000000", now, PAIR_TTL_MS); out = Core.windowOnM0(r.body, now); }
                break;
            case "/p1/pair/m2":   // cannot verify cA; forge cB
                out = new byte[53];
                System.arraycopy("KHP2".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
                out[4] = 4;
                System.arraycopy(r.body, 5, out, 5, Math.min(16, Math.max(0, r.body.length - 5)));
                byte[] fake = new byte[32]; rnd.nextBytes(fake);
                System.arraycopy(fake, 0, out, 21, 32);
                break;
            case "/p1/ch/hs":     // no registration: forge a reply
                out = new byte[69];
                rnd.nextBytes(out);
                System.arraycopy("KHC2".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
                out[4] = 0x11;
                break;
            default:
                out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8); // forged success
        }
        reply(s, 200, "application/octet-stream", out);
    }
}
