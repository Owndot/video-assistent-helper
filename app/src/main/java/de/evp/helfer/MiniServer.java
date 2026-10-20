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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Winziger HTTP-Server nur auf 127.0.0.1 (ohne Android-Teile, damit er sich auch am Rechner testen lässt).
 * Jede Anfrage braucht den Kopf "X-EVP-Key". GET für alles, POST nur für /push (rohe Daten im Körper).
 */
public class MiniServer {
    public interface Handler {
        String get(String path, Map<String, String> p);
        String push(Map<String, String> p, byte[] body);
    }

    private final int port;
    private final String key;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(10);   // auch für Abholen/Liefern beim Laden über den Browser
    private ServerSocket server;

    public MiniServer(int port, String key, Handler handler) {
        this.port = port;
        this.key = key;
        this.handler = handler;
    }

    public void start() {
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
                while (!server.isClosed()) {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            }
        }, "evp-helfer");
        t.start();
    }

    public void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
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
            String k = null;
            long clen = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String name = line.substring(0, c).trim();
                if (name.equalsIgnoreCase("x-evp-key")) k = line.substring(c + 1).trim();
                else if (name.equalsIgnoreCase("content-length")) { try { clen = Long.parseLong(line.substring(c + 1).trim()); } catch (Exception ignored) {} }
            }
            String[] parts = lines[0].split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            boolean post = "POST".equals(method) && "/push".equals(path);
            if (!("GET".equals(method) || post) || !key.equals(k)) { reply(s, 403, "{\"ok\":false}"); return; }
            Map<String, String> p = parseQuery(query);
            if (post) {
                if (clen < 0 || clen > (256L << 20)) { reply(s, 413, "{\"ok\":false}"); return; }
                byte[] body = new byte[(int) clen];
                int got = 0;
                while (got < clen) {
                    int n = in.read(body, got, (int) clen - got);
                    if (n < 0) break;
                    got += n;
                }
                reply(s, 200, handler.push(p, got == clen ? body : null));
                return;
            }
            reply(s, 200, handler.get(path, p));
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    static void reply(Socket s, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
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
}
