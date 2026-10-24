package de.evp.helfer;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real host HTTP transport tests, explicitly not Android UI coverage. */
public final class TestCredentialedStream {
    static final long LENGTH = 64L << 20, PREFIX = 1L << 20;
    static final byte[] BLOCK = new byte[64 << 10];
    static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    static final AtomicLong resumedAt = new AtomicLong(-1);

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static String digest(InputStream input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[16384];
        for (int count; (count = input.read(block)) >= 0;) md.update(block, 0, count);
        return HexFormat.of().formatHex(md.digest());
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < BLOCK.length; i++) BLOCK[i] = (byte) (i % 251);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (long i = 0; i < LENGTH; i += BLOCK.length) md.update(BLOCK);
        String expected = HexFormat.of().formatHex(md.digest());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int nth = calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            boolean brokenFirst = (path.equals("/resume") || path.equals("/ignore") || path.equals("/bad")) && nth == 1;
            boolean chunked = path.equals("/unknown");
            long offset = 0;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) offset = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
            boolean partial = range != null && !path.equals("/ignore");
            if (range != null && path.equals("/resume")) {
                resumedAt.set(offset);
                require("\"fixture-v1\"".equals(exchange.getRequestHeaders().getFirst("If-Range")), "Missing validator on resumed request");
            }
            exchange.getResponseHeaders().set("ETag", "\"fixture-v1\"");
            if (partial) exchange.getResponseHeaders().set("Content-Range",
                "bytes " + (path.equals("/bad") ? 0 : offset) + "-" + (LENGTH - 1) + "/" + LENGTH);
            else offset = 0;
            exchange.sendResponseHeaders(partial ? 206 : 200, chunked ? 0 : LENGTH - offset);
            try (OutputStream output = exchange.getResponseBody()) {
                long end = brokenFirst ? PREFIX : LENGTH;
                while (offset < end) {
                    int count = (int) Math.min(BLOCK.length, end - offset);
                    output.write(BLOCK, (int) (offset % BLOCK.length), count);
                    offset += count;
                }
            } catch (IOException expectedDisconnect) {
                // Intentional truncation/cancellation/invalid-range rejection.
            } finally {
                exchange.close();
            }
        });
        server.start();
        Path root = Files.createTempDirectory("credentialed-stream-test-");
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        Fetcher.Http client = new Fetcher.Http(origin + "/page", "test-only", "sentinel=test-only");
        try {
            for (String endpoint : List.of("known", "unknown", "resume", "ignore")) {
                File file = root.resolve(endpoint + ".mp4").toFile();
                client.streamToFile(origin + "/" + endpoint, file, null);
                require(file.length() == LENGTH, endpoint + " file size mismatch");
                try (InputStream in = new FileInputStream(file)) {
                    require(expected.equals(digest(in)), endpoint + " corrupted download");
                }
                Files.delete(file.toPath());
                System.out.println("PASS: bounded 64MiB " + endpoint + " transport");
            }
            require(resumedAt.get() == PREFIX, "Transfer did not resume from verified prefix");
            require(calls.get("/resume").get() == 2, "Unexpected resume network requests");
            File bad = root.resolve("bad.mp4").toFile();
            try {
                client.streamToFile(origin + "/bad", bad, null);
                throw new AssertionError("Mismatched Content-Range accepted");
            } catch (IOException expectedRangeError) {
                require(expectedRangeError.getMessage().contains("range"), "Wrong failure for invalid Range");
            }
            AtomicBoolean cancel = new AtomicBoolean();
            HlsCore.Listener listener = new HlsCore.Listener() {
                public void progress(int done, int total) { cancel.set(true); }
                public boolean cancelled() { return cancel.get(); }
            };
            File cancelled = root.resolve("cancelled.mp4").toFile();
            try {
                client.streamToFile(origin + "/cancel", cancelled, listener);
                throw new AssertionError("Cancelled download reported success");
            } catch (IOException expectedCancel) {
                require(expectedCancel.getMessage().contains("abgebrochen"), "Cancellation was not retained");
                require(cancelled.length() < LENGTH, "Cancellation retained whole file");
                require(calls.get("/cancel").get() == 1, "Cancellation incorrectly retried");
            }
            System.out.println("PASS: invalid range rejected and cancellation did not retry");
            System.out.println("PASS: 64MiB files streamed under the configured 32MiB Java heap");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
