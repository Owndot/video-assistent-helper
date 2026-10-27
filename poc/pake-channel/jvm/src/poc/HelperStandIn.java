package poc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM stand-in for the Android helper (NOT Android, NOT production).
 * Modes: "genuine" (uses the Rust core via JNI) and "rogue" (a foreign
 * listener on the same port that records everything and forges replies).
 * Controlled through stdin; reports events on stdout for the test runner.
 */
public final class HelperStandIn {
    static final int MAX_BODY = 65535 + 21;
    static final int MAX_SESSIONS = 4;
    static final long PAIR_TTL_MS = 120_000;
    static final HexFormat HEX = HexFormat.of();

    final int port;
    final boolean rogue;
    final Path stateDir;
    final byte[] identity;
    final Map<String, byte[]> registrations = new LinkedHashMap<>();
    final Map<String, Long> sessions = new LinkedHashMap<>();
    long window;
    volatile boolean tamper;
    byte[] observedKadriPublic = new byte[0];

    HelperStandIn(int port, boolean rogue, Path stateDir) throws Exception {
        this.port = port;
        this.rogue = rogue;
        this.stateDir = stateDir;
        Path idFile = stateDir.resolve(rogue ? "rogue-identity.bin" : "identity.bin");
        if (Files.exists(idFile)) identity = Files.readAllBytes(idFile);
        else { identity = Core.identityGenerate(); Files.write(idFile, identity); }
        Path regs = stateDir.resolve("registrations.txt");
        if (!rogue && Files.exists(regs)) {
            for (String line : Files.readAllLines(regs)) {
                String[] p = line.trim().split(" ");
                if (p.length == 2) registrations.put(p[0], HEX.parseHex(p[1]));
            }
        }
    }

    static void say(String s) { synchronized (System.out) { System.out.println(s); System.out.flush(); } }

    synchronized void saveRegistrations() throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, byte[]> e : registrations.entrySet()) b.append(e.getKey()).append(' ').append(HEX.formatHex(e.getValue())).append('\n');
        Files.writeString(stateDir.resolve("registrations.txt"), b.toString());
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

    void handle(HttpExchange x) {
        String path = x.getRequestURI().getPath();
        try {
            String host = x.getRequestHeaders().getFirst("Host");
            if (!"POST".equals(x.getRequestMethod()) || !("127.0.0.1:" + port).equals(host)) { reply(x, 403, new byte[0]); return; }
            byte[] body = readBody(x);
            if (rogue) { rogue(x, path, body); return; }
            byte[] out;
            synchronized (this) { out = genuine(path, body); }
            if (out == null) { reply(x, 404, new byte[0]); return; }
            if (tamper && path.equals("/p1/ch/rec")) out[out.length - 1] ^= 1;
            reply(x, 200, out);
        } catch (SecurityException e) {
            say("REJECT " + path + " " + e.getMessage());
            try { reply(x, 400, new byte[0]); } catch (Exception ignored) {}
        } catch (Exception e) {
            say("ERROR " + path + " " + e);
            try { reply(x, 500, new byte[0]); } catch (Exception ignored) {}
        }
    }

    byte[] genuine(String path, byte[] body) throws Exception {
        long now = System.currentTimeMillis();
        switch (path) {
            case "/p1/pair/m0": {
                if (window == 0) throw new SecurityException("NoPairingWindow");
                return Core.windowOnM0(window, body, now);
            }
            case "/p1/pair/m2": {
                if (window == 0) throw new SecurityException("NoPairingWindow");
                byte[] r;
                try { r = Core.windowOnM2(window, body, now); }
                finally { Core.windowFree(window); window = 0; } // single attempt, success or not
                String reg = HEX.formatHex(r, 0, 16);
                registrations.put(reg, Arrays.copyOfRange(r, 16, 48));
                saveRegistrations();
                say("PAIRED " + reg);
                return Arrays.copyOfRange(r, 48, r.length);
            }
            case "/p1/ch/hs": {
                String reg = HEX.formatHex(Core.hs1RegistrationId(body));
                byte[] pinned = registrations.getOrDefault(reg, new byte[0]);
                long ch = Core.respond(identity, body, pinned);
                String sid = HEX.formatHex(Core.channelSessionId(ch));
                while (sessions.size() >= MAX_SESSIONS) {
                    String oldest = sessions.keySet().iterator().next();
                    Core.channelFree(sessions.remove(oldest));
                }
                sessions.put(sid, ch);
                say("HS_OK " + reg + " session=" + sid);
                return Core.channelHs2(ch);
            }
            case "/p1/ch/rec": {
                if (body.length < 21) throw new SecurityException("Malformed");
                String sid = HEX.formatHex(body, 5, 21);
                Long ch = sessions.get(sid);
                if (ch == null) { say("UNKNOWN_SESSION " + sid); return null; }
                byte[] json;
                try { json = Core.channelOpenControl(ch, body); }
                catch (SecurityException e) { Core.channelFree(sessions.remove(sid)); throw e; }
                return dispatch(ch, new String(json, StandardCharsets.UTF_8));
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

    byte[] dispatch(long ch, String json) {
        String op = field(json, "op");
        if ("ping".equals(op)) return Core.channelSealControl(ch, "{\"ok\":true,\"helper\":\"genuine-standin\"}".getBytes(StandardCharsets.UTF_8));
        if ("download".equals(op)) {
            say("APP_DOWNLOAD url=" + field(json, "u") + " cookie=" + field(json, "c"));
            return Core.channelSealControl(ch, "{\"ok\":true,\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
        }
        if ("media".equals(op)) {
            int t = Integer.parseInt(field(json, "transfer"));
            long size = Long.parseLong(field(json, "size"));
            long off = Long.parseLong(field(json, "offset"));
            if (off >= size) return Core.channelSealEnd(ch, t, size);
            int n = (int) Math.min(60 * 1024, size - off);
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = (byte) (((off + i) * 31 + 7 + t) & 0xff);
            return Core.channelSealMedia(ch, t, off, d);
        }
        return Core.channelSealControl(ch, "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ foreign listener

    void rogue(HttpExchange x, String path, byte[] body) throws Exception {
        Files.write(stateDir.resolve("rogue-capture.bin"), concat(path.getBytes(StandardCharsets.UTF_8), new byte[]{0}, body, "\n----\n".getBytes(StandardCharsets.UTF_8)),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        say("ROGUE_RX " + path + " len=" + body.length);
        long now = System.currentTimeMillis();
        SecureRandom rnd = new SecureRandom();
        switch (path) {
            case "/p1/pair/m0": { // play the helper role with a guessed code
                observedKadriPublic = Arrays.copyOfRange(body, 5, 37);
                window = Core.windowOpen("00000000", Arrays.copyOfRange(identity, 32, 64), now, PAIR_TTL_MS);
                reply(x, 200, Core.windowOnM0(window, body, now));
                return;
            }
            case "/p1/pair/m2": { // cannot verify cA; forge cB
                byte[] m3 = new byte[53];
                System.arraycopy("KHP1".getBytes(StandardCharsets.US_ASCII), 0, m3, 0, 4);
                m3[4] = 4;
                System.arraycopy(body, 5, m3, 5, 16);
                byte[] fake = new byte[32]; rnd.nextBytes(fake);
                System.arraycopy(fake, 0, m3, 21, 32);
                reply(x, 200, m3);
                return;
            }
            case "/p1/ch/hs": { // best effort: own KK responder, else random bytes
                byte[] hs2;
                try {
                    long ch = Core.respond(identity, body, observedKadriPublic);
                    hs2 = Core.channelHs2(ch);
                } catch (SecurityException e) {
                    say("ROGUE_HS_FAILED " + e.getMessage());
                    hs2 = new byte[69];
                    rnd.nextBytes(hs2);
                    System.arraycopy("KHC1".getBytes(StandardCharsets.US_ASCII), 0, hs2, 0, 4);
                    hs2[4] = 0x11;
                }
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
        say("READY " + (rogue ? "rogue" : "genuine") + " " + port + " pub=" + HEX.formatHex(h.identity, 32, 64));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            line = line.trim();
            synchronized (h) {
                if (line.equals("open")) {
                    String code = Core.generateCode();
                    if (h.window != 0) Core.windowFree(h.window);
                    h.window = Core.windowOpen(code, Arrays.copyOfRange(h.identity, 32, 64), System.currentTimeMillis(), PAIR_TTL_MS);
                    say("CODE " + code);
                } else if (line.startsWith("open-code ")) { // deterministic artificial code for negative tests
                    if (h.window != 0) Core.windowFree(h.window);
                    h.window = Core.windowOpen(line.substring(10), Arrays.copyOfRange(h.identity, 32, 64), System.currentTimeMillis(), PAIR_TTL_MS);
                    say("WINDOW_OPEN");
                } else if (line.equals("revoke")) {
                    h.registrations.clear();
                    h.saveRegistrations();
                    for (long ch : h.sessions.values()) Core.channelFree(ch);
                    h.sessions.clear();
                    say("REVOKED");
                } else if (line.equals("tamper on")) { h.tamper = true; say("TAMPER on"); }
                else if (line.equals("tamper off")) { h.tamper = false; say("TAMPER off"); }
                else if (line.equals("quit")) break;
            }
        }
        server.stop(0);
        System.exit(0);
    }
}
