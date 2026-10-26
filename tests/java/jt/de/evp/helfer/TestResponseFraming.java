package de.evp.helfer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Fetcher paths; raw HTTP avoids a server framework normalizing invalid headers. */
public final class TestResponseFraming {
    static final String DATA = "0123456789";
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static String headers(String path) {
        switch (path) {
            case "/conflict": return "Transfer-Encoding: chunked\r\nContent-Length: 4\r\n";
            case "/reverse": return "cOnTeNt-LeNgTh: 4\r\ntRaNsFeR-EnCoDiNg: chunked\r\n";
            case "/equal-conflict": return "Transfer-Encoding: chunked\r\nContent-Length: 10\r\n";
            case "/duplicate-conflict": return "Transfer-Encoding: chunked\r\nContent-Length: 10\r\nContent-Length: 4\r\n";
            case "/length-conflict": return "Content-Length: 10\r\nContent-Length: 4\r\n";
            case "/same-lengths": return "Content-Length: 10\r\nContent-Length: 10\r\n";
            case "/chunked": case "/ignored-chunked": return "Transfer-Encoding: chunked\r\n";
            case "/range-chunked": return "Transfer-Encoding: chunked\r\nContent-Range: bytes 3-6/10\r\n";
            case "/range": return "Content-Length: 4\r\nContent-Range: bytes 3-6/10\r\n";
            default: return "Content-Length: 10\r\n";
        }
    }
    static String body(String path) { return path.startsWith("/range") ? "3456" : DATA; }
    static int status(String path) { return path.startsWith("/range") ? 206 : 200; }

    static Map<String,List<String>> fields(String raw) {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            result.computeIfAbsent(line.substring(0, colon), k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
        }
        return result;
    }
    static String last(Map<String,List<String>> fields, String name) {
        for (Map.Entry<String,List<String>> field : fields.entrySet()) {
            if (name.equalsIgnoreCase(field.getKey())) return field.getValue().get(field.getValue().size() - 1);
        }
        return null;
    }

    static final class Simulated extends HttpURLConnection {
        final Map<String,List<String>> fields;
        final InputStream input;
        final int status;
        boolean closed;
        Simulated(String url) throws IOException {
            super(new URL(url));
            String path = this.url.getPath();
            // A decoded stream can expose more bytes than a misleading convenience length.
            fields = fields(path.equals("/hidden-te") ? "Content-Length: 4\r\n" : headers(path));
            input = new ByteArrayInputStream(body(path).getBytes(StandardCharsets.US_ASCII));
            status = status(path);
        }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { closed = true; try { input.close(); } catch (IOException e) { throw new UncheckedIOException(e); } }
        public int getResponseCode() { return status; }
        public Map<String,List<String>> getHeaderFields() { return fields; }
        public String getHeaderField(String name) { return last(fields, name); }
        public InputStream getInputStream() { return input; }
    }

    /** Tiny sequential raw server: fixed synthetic bytes, closes every response. */
    static final class RawServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        RawServer() throws IOException {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line = request.readLine();
                        if (line == null) continue;
                        String path = line.split(" ")[1];
                        int bytes = line.length();
                        while ((line = request.readLine()) != null && !line.isEmpty()) {
                            bytes += line.length();
                            if (bytes > 8192) throw new IOException("Oversize fixture request");
                        }
                        String h = headers(path), data = body(path);
                        boolean chunked = last(fields(h), "Transfer-Encoding") != null;
                        String payload = chunked ? Integer.toHexString(data.length()) + "\r\n" + data + "\r\n0\r\n\r\n" : data;
                        String response = "HTTP/1.1 " + status(path) + " OK\r\n" + h + "Connection: close\r\n\r\n" + payload;
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    } catch (SocketException stopped) {
                        if (!server.isClosed()) failure.compareAndSet(null, stopped);
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }
            }, "raw-framing-fixture");
            thread.start();
        }
        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
        public void close() throws Exception {
            server.close(); thread.join(4000);
            check(!thread.isAlive(), "Raw fixture did not stop");
            if (failure.get() != null) throw new AssertionError("Raw fixture failed", failure.get());
        }
    }

    static void proveParser(String base) throws Exception {
        Fetcher.Http client = new Fetcher.Http(null, null, null);
        HttpURLConnection c = client.response(base + "/conflict", -1, -1);
        try {
            check("chunked".equalsIgnoreCase(c.getHeaderField("Transfer-Encoding")), "Parser hid Transfer-Encoding");
            check("4".equals(c.getHeaderField("Content-Length")), "Parser hid Content-Length");
            check("chunked".equalsIgnoreCase(last(c.getHeaderFields(), "Transfer-Encoding")) &&
                "4".equals(last(c.getHeaderFields(), "Content-Length")), "Header map lost conflicting framing");
            check(c.getContentLengthLong() == 4, "Convenience length does not reproduce misleading CL");
            String decoded;
            try (InputStream in = c.getInputStream()) {
                decoded = new String(in.readNBytes(64), StandardCharsets.US_ASCII);
            }
            check(DATA.equals(decoded), "Platform did not return the ten decoded chunk bytes: " + decoded);
            System.out.println("PARSER: " + c.getClass().getName() + " retains TE=chunked and CL=4 in both APIs; getContentLengthLong=4, decoded=" + decoded);
        } finally { c.disconnect(); }
    }

    static void rejected(Fetcher.Http client, String url, File file, AtomicInteger requests) throws Exception {
        int before = requests.get();
        List<String> accepted = new ArrayList<>();
        try {
            byte[] data = client.fetch(url, -1, -1);
            accepted.add("fetch=" + new String(data, StandardCharsets.US_ASCII));
        } catch (Fetcher.ResponseError expected) { /* permanent error, never a partial success */ }
        Files.writeString(file.toPath(), "stale temporary data");
        try {
            client.download(url, -1, -1, file, null);
            accepted.add("download=" + file.length() + " bytes (" + Files.readString(file.toPath()) + ")");
        } catch (Fetcher.ResponseError expected) { check(!file.exists(), "Failed temporary download retained"); }
        check(accepted.isEmpty(), "Framing accepted by production paths: " + String.join(", ", accepted));
        check(!file.exists(), "Rejected download file exists");
        check(requests.get() - before == 2, "Permanent framing error retried");
    }

    static void run(String base, boolean simulated, Path temp) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<Simulated> connections = new ArrayList<>();
        Fetcher.Http client = new Fetcher.Http(null, null, null) {
            @Override HttpURLConnection open(String url) throws IOException {
                requests.incrementAndGet();
                if (!simulated) return super.open(url);
                Simulated c = new Simulated(url); connections.add(c); return c;
            }
        };
        File out = temp.resolve("response.part").toFile();
        for (String path : new String[]{"/conflict", "/reverse", "/equal-conflict", "/duplicate-conflict", "/length-conflict"}) {
            rejected(client, base + path, out, requests);
        }
        if (simulated) rejected(client, base + "/hidden-te", out, requests);
        for (String path : new String[]{"/known", "/same-lengths", "/chunked", "/range", "/range-chunked", "/ignored", "/ignored-chunked"}) {
            boolean range = path.startsWith("/range") || path.startsWith("/ignored");
            String expected = range ? "3456" : DATA;
            byte[] actual = client.fetch(base + path, range ? 3 : -1, range ? 4 : -1);
            check(expected.equals(new String(actual, StandardCharsets.US_ASCII)), "Valid fetch failed: " + path);
            client.download(base + path, range ? 3 : -1, range ? 4 : -1, out, null);
            check(expected.equals(Files.readString(out.toPath())), "Valid file failed: " + path);
            Files.delete(out.toPath());
        }
        if (simulated) for (Simulated c : connections) check(c.closed, "Simulated connection not disconnected");
        System.out.println("PASS: " + (simulated ? "simulated" : "raw HTTP") + " production fetch/download reject contradictory framing without retry or retained files; valid 200/chunked/206 and ignored Range remain exact");
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("response-framing-");
        try {
            if (args.length == 1 && args[0].equals("--simulated")) run("http://fixture.invalid", true, temp);
            else try (RawServer server = new RawServer()) { proveParser(server.base()); run(server.base(), false, temp); }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
