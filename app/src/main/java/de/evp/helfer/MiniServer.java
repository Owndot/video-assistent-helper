package de.evp.helfer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Winziger HTTP-Server nur auf 127.0.0.1 (ohne Android-Teile, damit er sich auch am Rechner testen lässt).
 * Jede Anfrage braucht den Kopf "X-EVP-Key". GET für alles, POST nur für /push (rohe Daten im Körper).
 */
public class MiniServer {
    public interface Handler {
        String get(String path, Map<String, String> p);
        String push(Map<String, String> p, byte[] body);
        /** Compatibility adapter for small custom handlers; production overrides this. */
        default String pushStream(Map<String, String> p, InputStream in, long length) throws Exception {
            if (length < 0 || length > Fetcher.MAX_ARRAY_BYTES) throw new Fetcher.ResponseError("Legacy handler body exceeds budget");
            byte[] body = new byte[(int) length];
            int at = 0;
            while (at < body.length) { int n = in.read(body, at, body.length - at); if (n < 0) throw new java.io.EOFException(); at += n; }
            return push(p, body);
        }
    }

    private final int port;
    private final java.util.function.Predicate<String> keyOk;
    private final Handler handler;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32));   // bound accepted sockets before authentication
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private Thread listenerThread;

    public MiniServer(int port, String key, Handler handler) {
        this(port, key::equals, handler);
    }

    /** keyOk entscheidet, ob der mitgeschickte Schlüssel gilt (eigener Zufallsschlüssel der App) */
    public MiniServer(int port, java.util.function.Predicate<String> keyOk, Handler handler) {
        this.port = port;
        this.keyOk = keyOk;
        this.handler = handler;
    }

    /** Koppel-Seite (ohne Schlüssel abrufbar, enthält ihn aber nicht – er steht nur im #-Teil der Adresse im Browser) */
    static String pairHtml(boolean de) {
        return "<!doctype html><html lang=" + (de ? "de" : "en") + "><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
            + "<title>" + (de ? "Video-Assistent verbinden" : "Connect Video Assistant") + "</title></head>"
            + "<body style=\"font:17px/1.4 system-ui,sans-serif;margin:0;padding:32px 22px;background:#16181d;color:#eee\">"
            + "<h2 style=\"margin:0 0 12px\">" + (de ? "Video-Assistent Helper" : "Video Assistant Helper") + "</h2><p id=evp-pair>"
            + (de ? "Das Userscript verbindet sich gerade … Erscheint hier nach ein paar Sekunden nichts, ist Violentmonkey in diesem Browser nicht aktiv oder das Skript zu alt (ab 13.5)."
                  : "The userscript is connecting … If nothing appears here after a few seconds, Violentmonkey isn't active in this browser or the script is too old (13.5 or newer needed).")
            + "</p></body></html>";
    }

    /** Nur Anfragen an 127.0.0.1/localhost (Schutz gegen „DNS-Rebinding“: eine Webseite, deren Name auf 127.0.0.1 zeigt) */
    static boolean hostOk(String host) {
        if (host == null) return true;   // HTTP/1.0 ohne Host-Kopf
        String h = host.trim().toLowerCase(Locale.ROOT);
        int c = h.lastIndexOf(':');
        if (c > 0 && h.indexOf(']') < c) h = h.substring(0, c);
        return h.equals("127.0.0.1") || h.equals("localhost");
    }

    /** Bind before reporting readiness. A competing helper must never appear ready. */
    public synchronized boolean start() {
        if (server != null && !server.isClosed()) return true;
        if (pool.isShutdown()) return false;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        } catch (java.io.IOException e) {
            stop();
            return false;
        }
        final ServerSocket bound = server;
        Thread t = new Thread(() -> {
            try {
                while (!bound.isClosed()) {
                    final Socket s = bound.accept();
                    connections.add(s);
                    if (bound.isClosed()) { closeConnection(s); continue; }
                    try { pool.execute(() -> handle(s)); }
                    catch (RejectedExecutionException full) { closeConnection(s); }
                }
            } catch (java.io.IOException ignored) {
                // Closing the listener during normal shutdown interrupts accept().
            }
        }, "evp-helfer");
        listenerThread = t;
        t.start();
        return true;
    }

    public synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        // close() wakes accept(), but the OS can retain its listening socket until
        // that thread leaves accept(). Await it before a new service binds the port.
        Thread listener = listenerThread;
        if (listener != null && listener != Thread.currentThread()) {
            try { listener.join(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        pool.shutdownNow();
        for (Socket s : connections) closeConnection(s);
    }

    private void closeConnection(Socket s) {
        connections.remove(s);
        try { s.close(); } catch (Exception ignored) {}
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            // Kopf Byte für Byte lesen (danach kommen bei /push rohe Videodaten)
            ByteArrayOutputStream hb = new ByteArrayOutputStream(1024);
            int b, prev = -1, prev2 = -1, prev3 = -1;
            while ((b = in.read()) >= 0) {
                hb.write(b);
                if (b == '\n' && (prev == '\n' || (prev == '\r' && prev2 == '\n') || (prev == '\r' && prev2 == '\n' && prev3 == '\r'))) break;
                prev3 = prev2; prev2 = prev; prev = b;
                if (hb.size() > 65536) { s.close(); return; }
            }
            String[] lines = new String(hb.toByteArray(), StandardCharsets.ISO_8859_1).split("\r?\n");
            if (lines.length == 0 || lines[0].isEmpty()) { s.close(); return; }
            String k = null, host = null, lang = null;
            long clen = -1;
            boolean badFraming = false, transfer = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String name = line.substring(0, c).trim();
                if (name.equalsIgnoreCase("x-evp-key")) k = line.substring(c + 1).trim();
                else if (name.equalsIgnoreCase("host")) host = line.substring(c + 1).trim();
                else if (name.equalsIgnoreCase("accept-language")) lang = line.substring(c + 1).trim();
                else if (name.equalsIgnoreCase("transfer-encoding")) transfer = true;
                else if (name.equalsIgnoreCase("content-length")) {
                    try { long value = Long.parseLong(line.substring(c + 1).trim()); if (value < 0 || clen >= 0 && clen != value) badFraming = true; clen = value; }
                    catch (Exception invalid) { badFraming = true; }
                }
            }
            String[] parts = lines[0].split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            boolean post = "POST".equals(method) && "/push".equals(path);
            if (!hostOk(host)) { reply(s, 403, "{\"ok\":false}"); return; }
            if ("GET".equals(method) && "/pair".equals(path)) {
                boolean de = lang == null ? Tr.de() : lang.toLowerCase(Locale.ROOT).startsWith("de");
                reply(s, 200, pairHtml(de), "text/html; charset=utf-8");
                return;
            }
            if (!("GET".equals(method) || post) || k == null || !keyOk.test(k)) { reply(s, 403, "{\"ok\":false}"); return; }
            Map<String, String> p = parseQuery(query);
            if (post) {
                // The unchanged /push wire format is a fixed-length binary body.
                if (transfer || badFraming || clen < 0) { reply(s, 400, "{\"ok\":false}"); return; }
                if (clen < 0 || clen > (256L << 20)) { reply(s, 413, "{\"ok\":false}"); return; }
                reply(s, 200, handler.pushStream(p, in, clen));
                return;
            }
            reply(s, 200, handler.get(path, p));
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        } finally {
            closeConnection(s);
        }
    }

    static void reply(Socket s, int code, String body) throws Exception {
        reply(s, code, body, "application/json; charset=utf-8");
    }

    static void reply(Socket s, int code, String body, String type) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
        s.close();
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    public static String js(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : (v == null ? "" : v).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    public static long num(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------- Laden über den Browser: Anfragen abholen und Daten annehmen ----------
    public static String pull(Map<Integer, Fetcher.Proxy> proxies, Map<Integer, String> state, Map<String, String> p) {
        int job = (int) num(p.get("job"), -1);
        Fetcher.Proxy px = proxies.get(job);
        if (px == null) return "{\"ok\":true,\"done\":true,\"state\":" + js(state.get(job)) + "}";
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"done\":false,\"reqs\":[");
        boolean first = true;
        for (Fetcher.Proxy.Req r : px.pull(4, 8000)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(r.id).append(",\"u\":").append(js(r.url))
              .append(",\"o\":").append(r.off).append(",\"l\":").append(r.len).append(",\"s\":").append(r.sizeOnly).append('}');
        }
        return sb.append("],\"state\":").append(js(state.get(job))).append('}').toString();
    }

    public static String push(Map<Integer, Fetcher.Proxy> proxies, Map<String, String> p, byte[] body) {
        Fetcher.Proxy px = proxies.get((int) num(p.get("job"), -1));
        if (px == null) return "{\"ok\":false}";
        int id = (int) num(p.get("id"), -1), st = (int) num(p.get("st"), 0);
        long total = num(p.get("total"), -1);
        if (body == null && st < 400) st = 502;
        return "{\"ok\":" + px.push(id, st, total, body) + "}";
    }

    public static String pushStream(Map<Integer, Fetcher.Proxy> proxies, Map<String, String> p, InputStream in, long length) throws Exception {
        Fetcher.Proxy px = proxies.get((int) num(p.get("job"), -1));
        if (px == null) return "{\"ok\":false}";
        int id = (int) num(p.get("id"), -1), st = (int) num(p.get("st"), 0);
        long total = num(p.get("total"), -1);
        return "{\"ok\":" + px.pushStream(id, st, total, in, length) + "}";
    }
}
