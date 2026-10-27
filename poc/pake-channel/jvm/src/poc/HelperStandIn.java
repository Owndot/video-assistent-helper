package poc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM stand-in for the Android helper (NOT Android, NOT production).
 * Modes: "genuine" (Rust core via JNI) and "rogue" (a foreign listener on the
 * same port that records everything and forges replies). Controlled through
 * stdin; reports events on stdout for the test runners.
 */
public final class HelperStandIn {
    static final int MAX_BODY = 65535 + 21;
    static final long PAIR_TTL_MS = 120_000;
    static final HexFormat HEX = HexFormat.of();
    static final Pattern EXT_ORIGIN = Pattern.compile("(chrome-extension|moz-extension)://[a-z0-9-]{1,64}");

    final int port;
    final boolean rogue;
    final Path stateDir;
    volatile boolean tamper;

    HelperStandIn(int port, boolean rogue, Path stateDir) throws Exception {
        this.port = port;
        this.rogue = rogue;
        this.stateDir = stateDir;
        Path idFile = stateDir.resolve(rogue ? "rogue-identity.bin" : "identity.bin");
        byte[] identity;
        if (Files.exists(idFile)) identity = Files.readAllBytes(idFile);
        else { identity = Core.identityGenerate(); Files.write(idFile, identity); }
        Path regs = stateDir.resolve("registrations.bin");
        byte[] stored = !rogue && Files.exists(regs) ? Files.readAllBytes(regs) : new byte[0];
        say("READY " + (rogue ? "rogue" : "genuine") + " " + port + " pub=" + Core.hostInit(identity, stored));
    }

    static void say(String s) { synchronized (System.out) { System.out.println(s); System.out.flush(); } }

    void persist() throws Exception {
        if (!rogue) Files.write(stateDir.resolve("registrations.bin"), Core.hostExportRegistrations());
    }

    static byte[] readBody(HttpExchange x) throws Exception {
        String len = x.getRequestHeaders().getFirst("Content-Length");
        if (len == null || x.getRequestHeaders().containsKey("Transfer-Encoding")) throw new SecurityException("Framing");
        long n = Long.parseLong(len);
        if (n < 0 || n > MAX_BODY) throw new SecurityException("TooLarge");
        byte[] b = x.getRequestBody().readNBytes((int) n);
        if (b.length != n) throw new SecurityException("Truncated");
        return b;
    }

    static void reply(HttpExchange x, int code, byte[] body) throws Exception {
        x.getResponseHeaders().set("Content-Type", "application/octet-stream");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = x.getResponseBody()) { o.write(body); }
        x.close();
    }

    /**
     * Browser-facing gate. It keeps ordinary web pages from reaching the
     * protocol endpoints (custom header + non-simple content type force a
     * CORS preflight that is never answered; foreign Origins are refused).
     * It is NOT authentication: any local app can send these headers.
     */
    String gate(HttpExchange x) {
        var h = x.getRequestHeaders();
        if ("OPTIONS".equals(x.getRequestMethod())) return "preflight";
        if (!"POST".equals(x.getRequestMethod())) return "method";
        if (!("127.0.0.1:" + port).equals(h.getFirst("Host"))) return "host";
        if (!"application/octet-stream".equals(h.getFirst("Content-Type"))) return "content-type";
        if (!"2".equals(h.getFirst("X-Kadri-Helper"))) return "custom-header";
        String origin = h.getFirst("Origin");
        if (origin != null && !EXT_ORIGIN.matcher(origin).matches()) return "origin";
        return null;
    }

    void handle(HttpExchange x) {
        String path = x.getRequestURI().getPath();
        try {
            if (rogue) { rogue(x, path, readBody(x)); return; }
            String why = gate(x);
            say("REQ " + x.getRequestMethod() + " " + path + " origin=" + x.getRequestHeaders().getFirst("Origin")
                + (why == null ? "" : " GATE_REJECT " + why));
            if (why != null) { reply(x, 403, new byte[0]); return; }
            byte[] body = readBody(x);
            byte[] out;
            synchronized (this) { out = genuine(path, body); }
            if (tamper && path.equals("/p1/ch/rec")) out[out.length - 1] ^= 1;
            reply(x, 200, out);
        } catch (SecurityException e) {
            say("REJECT " + path + " " + e.getMessage());
            try { reply(x, "UnknownSession".equals(e.getMessage()) ? 404 : 400, new byte[0]); } catch (Exception ignored) {}
        } catch (Exception e) {
            say("ERROR " + path + " " + e);
            try { reply(x, 500, new byte[0]); } catch (Exception ignored) {}
        }
    }

    byte[] genuine(String path, byte[] body) throws Exception {
        long now = System.currentTimeMillis();
        switch (path) {
            case "/p1/pair/m0":
                return Core.windowOnM0(body, now);
            case "/p1/pair/m2": {
                byte[] r = Core.windowOnM2(body, now);
                persist();
                say("PAIRED_PENDING " + HEX.formatHex(r, 0, 16));
                return Arrays.copyOfRange(r, 16, r.length);
            }
            case "/p1/ch/hs": {
                byte[] hs2 = Core.hostHandshake(body, now);
                say("HS_HALF_OPEN session=" + HEX.formatHex(hs2, 5, 21) + " " + Core.hostStats(now));
                return hs2;
            }
            case "/p1/ch/rec": {
                byte[] r = Core.hostOpen(body, now);
                persist();
                byte[] sid = Arrays.copyOfRange(r, 0, 16);
                return dispatch(sid, new String(r, 17, r.length - 17, StandardCharsets.UTF_8), now);
            }
            default:
                throw new SecurityException("UnknownPath");
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"([^\"]*)\"|(-?\\d+))").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    byte[] dispatch(byte[] sid, String json, long now) {
        String op = field(json, "op");
        if ("ping".equals(op)) return Core.hostSealControl(sid, "{\"ok\":true,\"helper\":\"genuine-standin\"}".getBytes(StandardCharsets.UTF_8), now);
        if ("download".equals(op)) {
            say("APP_DOWNLOAD url=" + field(json, "u") + " cookie=" + field(json, "c"));
            return Core.hostSealControl(sid, "{\"ok\":true,\"accepted\":true}".getBytes(StandardCharsets.UTF_8), now);
        }
        if ("media".equals(op)) {
            int t = Integer.parseInt(field(json, "transfer"));
            long size = Long.parseLong(field(json, "size"));
            long off = Long.parseLong(field(json, "offset"));
            if (off >= size) return Core.hostSealEnd(sid, t, size, now);
            int n = (int) Math.min(60 * 1024, size - off);
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = (byte) (((off + i) * 31 + 7 + t) & 0xff);
            return Core.hostSealMedia(sid, t, off, d, now);
        }
        return Core.hostSealControl(sid, "{\"ok\":false}".getBytes(StandardCharsets.UTF_8), now);
    }

    // ------------------------------------------------------------ foreign listener

    void rogue(HttpExchange x, String path, byte[] body) throws Exception {
        Files.write(stateDir.resolve("rogue-capture.bin"), concat(path.getBytes(StandardCharsets.UTF_8), new byte[]{0}, body, "\n----\n".getBytes(StandardCharsets.UTF_8)),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        StringBuilder hdr = new StringBuilder();
        x.getRequestHeaders().forEach((k, v) -> hdr.append(k).append(": ").append(String.join(",", v)).append('\n'));
        Files.writeString(stateDir.resolve("rogue-headers.txt"), path + "\n" + hdr + "----\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        say("ROGUE_RX " + path + " len=" + body.length);
        long now = System.currentTimeMillis();
        SecureRandom rnd = new SecureRandom();
        switch (path) {
            case "/p1/pair/m0": { // play the helper role with a guessed code
                Core.windowOpen("00000000", now, PAIR_TTL_MS);
                reply(x, 200, Core.windowOnM0(body, now));
                return;
            }
            case "/p1/pair/m2": { // cannot verify cA; forge cB
                byte[] m3 = new byte[53];
                System.arraycopy("KHP2".getBytes(StandardCharsets.US_ASCII), 0, m3, 0, 4);
                m3[4] = 4;
                System.arraycopy(body, 5, m3, 5, 16);
                byte[] fake = new byte[32]; rnd.nextBytes(fake);
                System.arraycopy(fake, 0, m3, 21, 32);
                reply(x, 200, m3);
                return;
            }
            case "/p1/ch/hs": { // no registration for this key: forge a reply
                byte[] hs2 = new byte[69];
                rnd.nextBytes(hs2);
                System.arraycopy("KHC2".getBytes(StandardCharsets.US_ASCII), 0, hs2, 0, 4);
                hs2[4] = 0x11;
                reply(x, 200, hs2);
                return;
            }
            default:
                reply(x, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)); // forged success
        }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        boolean rogue = "rogue".equals(args[1]);
        HelperStandIn h = new HelperStandIn(port, rogue, Path.of(args[2]));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.createContext("/", h::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        say("LISTENING");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            line = line.trim();
            synchronized (h) {
                long now = System.currentTimeMillis();
                if (line.equals("open")) say("CODE " + Core.windowOpen(null, now, PAIR_TTL_MS));
                else if (line.startsWith("open-code ")) { Core.windowOpen(line.substring(10), now, PAIR_TTL_MS); say("WINDOW_OPEN"); }
                else if (line.equals("revoke")) { Core.hostRevokeAll(); h.persist(); say("REVOKED"); }
                else if (line.equals("stats")) say("STATS " + Core.hostStats(now));
                else if (line.equals("tamper on")) { h.tamper = true; say("TAMPER on"); }
                else if (line.equals("tamper off")) { h.tamper = false; say("TAMPER off"); }
                else if (line.equals("quit")) break;
            }
        }
        server.stop(0);
        System.exit(0);
    }
}
