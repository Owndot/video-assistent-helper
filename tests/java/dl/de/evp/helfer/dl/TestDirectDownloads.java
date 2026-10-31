package de.evp.helfer.dl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.evp.helfer.net.LocalTestClients;
import de.evp.helfer.net.SafeHttpClient;

/**
 * JVM-Tests der MP4-Download-Engine gegen echte lokale HTTP-Server (echte Sockets, alle Prüfschichten des
 * SafeHttpClient aktiv). Läuft mit kleinem Heap: große Dateien müssen gestreamt werden.
 */
public final class TestDirectDownloads {
    static int passed, failed;
    static File root;

    interface Body { void run() throws Exception; }

    static void check(String name, Body b) {
        try { b.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable t) { failed++; System.out.println("FAIL " + name + ": " + t); t.printStackTrace(System.out); }
    }

    static void expect(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    // ------------------------------------------------------------------ Testinhalt (deterministisch, nie im Speicher)

    static int at(long i) {
        if (i < 8) return new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'}[(int) i] & 0xff;
        return (int) (((i * 2654435761L) >>> 13) & 0xff);
    }

    static void content(OutputStream out, long from, long to) throws IOException {   // [from, to)
        byte[] buf = new byte[64 << 10];
        for (long p = from; p < to; ) {
            int n = (int) Math.min(buf.length, to - p);
            for (int k = 0; k < n; k++) buf[k] = (byte) at(p + k);
            out.write(buf, 0, n);
            p += n;
        }
    }

    static byte[] expectedSha(long total) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        content(new OutputStream() {
            @Override public void write(int b) { md.update((byte) b); }
            @Override public void write(byte[] b, int off, int len) { md.update(b, off, len); }
        }, 0, total);
        return md.digest();
    }

    static byte[] fileSha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 << 10];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return md.digest();
    }

    // ------------------------------------------------------------------ lokaler HTTP-Server

    static final class Req {
        final String line;
        final Map<String, String> headers;
        Req(String line, Map<String, String> headers) { this.line = line; this.headers = headers; }
        String header(String n) { return headers.get(n.toLowerCase(Locale.ROOT)); }
        long rangeStart() {
            String r = header("range");
            if (r == null) return -1;
            return Long.parseLong(r.substring("bytes=".length(), r.indexOf('-')));
        }
    }

    interface Handler { void handle(Req req, OutputStream out, int index) throws Exception; }

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final Handler handler;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final List<Req> seen = new CopyOnWriteArrayList<>();
        final List<Socket> open = new CopyOnWriteArrayList<>();
        final AtomicInteger aborted = new AtomicInteger();
        volatile boolean closed;

        Server(Handler handler) throws IOException {
            this.ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.handler = handler;
            Thread t = new Thread(this::loop, "test-http");
            t.setDaemon(true);
            t.start();
        }

        int port() { return ss.getLocalPort(); }
        String url(String path) { return "http://dl.example:" + port() + path; }

        void loop() {
            while (!closed) {
                Socket s;
                try { s = ss.accept(); } catch (IOException e) { return; }
                connections.incrementAndGet();
                open.add(s);
                Thread t = new Thread(() -> serve(s), "test-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        void serve(Socket s) {
            try (Socket sock = s) {
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) return;
                    Map<String, String> h = new HashMap<>();
                    for (String l; !(l = readLine(in)).isEmpty(); ) {
                        int c = l.indexOf(':');
                        h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
                    }
                    Req r = new Req(line, h);
                    seen.add(r);
                    int idx = requests.getAndIncrement();
                    handler.handle(r, out, idx);
                    out.flush();
                }
            } catch (Exception e) {
                aborted.incrementAndGet();
            } finally {
                open.remove(s);
            }
        }

        @Override public void close() throws IOException {
            closed = true;
            ss.close();
            for (Socket s : open) { try { s.close(); } catch (IOException ignored) { } }
        }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) return b.length() == 0 ? null : b.toString();
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    static void head(OutputStream out, String status, String... headers) throws IOException {
        StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        for (String h : headers) if (!h.isEmpty()) b.append(h).append("\r\n");
        b.append("\r\n");
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Ganze Datei oder Bereich; {@code cutAt} &gt;= 0: Verbindung nach so vielen Bytes (absolut) hart beenden. */
    static void serveFile(Req r, OutputStream out, long total, String etag, long cutAt) throws IOException {
        long start = r.rangeStart();
        String ifRange = r.header("if-range");
        if (start >= 0 && ifRange != null && !ifRange.equals(etag)) start = -1;   // RFC 9110 13.1.5: dann ganze Ressource
        String validator = etag == null ? "" : "ETag: " + etag;
        if (start < 0) {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total, "Accept-Ranges: bytes", validator);
            start = 0;
        } else {
            head(out, "206 Partial Content", "Content-Type: video/mp4", "Content-Length: " + (total - start),
                "Content-Range: bytes " + start + "-" + (total - 1) + "/" + total, validator);
        }
        long end = cutAt >= 0 && cutAt > start ? Math.min(cutAt, total) : total;
        content(out, start, end);
        out.flush();
        if (end < total) throw new IOException("cut");    // Verbindung schließen: Unterbrechung
    }

    // ------------------------------------------------------------------ Testablage (Ordner)

    static final class DirStore implements DownloadStore {
        final File dir;
        final AtomicLong free = new AtomicLong(Long.MAX_VALUE / 4);
        volatile long freeAfterBytes = -1, freeLater = 0;      // ab so vielen geschriebenen Bytes: free = freeLater
        volatile long failWriteAfter = -1;                     // simuliertes ENOSPC beim Schreiben
        final AtomicLong written = new AtomicLong();
        final AtomicInteger created = new AtomicInteger(), committed = new AtomicInteger(), aborted = new AtomicInteger();
        byte[] journal;
        volatile boolean failJournal;
        volatile int failJournalAfterSaves = -1;              // so viele Schreibvorgänge gelingen, dann Fehler
        volatile boolean writable = true;
        volatile byte[] journalAtCreate, journalAtCommit;     // Stand des Journals bei einem "Absturz" an dieser Stelle
        volatile boolean failPublishedCheck;
        int saves;

        DirStore(String name) throws IOException {
            dir = new File(root, name);
            if (!dir.mkdirs()) throw new IOException("mkdir");
        }

        @Override public long freeBytes() {
            if (freeAfterBytes >= 0 && written.get() >= freeAfterBytes) return freeLater;
            return free.get();
        }

        @Override public boolean writable() { return writable; }

        @Override public boolean isPublished(String ref, long total) throws IOException {
            if (failPublishedCheck) throw new IOException("media store unavailable");
            File f = new File(ref);
            return f.getParentFile() != null && f.getParentFile().equals(dir) && f.isFile() && f.length() == total;
        }

        @Override public Target create(String displayName, String mime) throws IOException {
            expect(mime.equals("video/mp4"), "mime");
            created.incrementAndGet();
            journalAtCreate = loadJournal();
            File part = File.createTempFile(".part-", ".tmp", dir);
            File fin = new File(dir, displayName);
            OutputStream fos = new FileOutputStream(part);
            OutputStream counted = new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (failWriteAfter >= 0 && written.get() + len > failWriteAfter) throw new IOException("ENOSPC");
                    fos.write(b, off, len);
                    written.addAndGet(len);
                }
                @Override public void close() throws IOException { fos.close(); }
            };
            return new Target() {
                boolean done;
                @Override public OutputStream out() { return counted; }
                @Override public String ref() { return fin.getPath(); }
                @Override public synchronized Object commit() throws IOException {
                    fos.close();
                    if (!part.renameTo(fin)) throw new IOException("rename");
                    done = true;
                    committed.incrementAndGet();
                    journalAtCommit = loadJournal();     // veröffentlicht, Journal noch nicht "DONE"
                    return fin;
                }
                @Override public synchronized void abort() {
                    if (done) return;
                    try { fos.close(); } catch (IOException ignored) { }
                    part.delete();
                    aborted.incrementAndGet();
                }
            };
        }

        @Override public synchronized byte[] loadJournal() { return journal == null ? null : journal.clone(); }

        @Override public synchronized void saveJournal(byte[] data) throws IOException {
            if (failJournal || (failJournalAfterSaves >= 0 && saves >= failJournalAfterSaves)) throw new IOException("journal");
            saves++;
            journal = data.clone();
        }

        String[] files() { String[] l = dir.list(); return l == null ? new String[0] : l; }
        File only() { String[] l = files(); expect(l.length == 1, "files: " + String.join(",", l)); return new File(dir, l[0]); }
        void expectEmpty() { expect(files().length == 0, "leftovers: " + String.join(",", files())); }
    }

    // ------------------------------------------------------------------ Rückmeldungen

    static final class Events implements DirectDownloads.Listener {
        final Map<String, String> finished = new ConcurrentHashMap<>();
        final List<String> all = new CopyOnWriteArrayList<>();
        @Override public void progress(String id, String title, long bytes, long total) { all.add("progress " + id + " " + title + " " + bytes + "/" + total); }
        @Override public synchronized void finished(String id, String title, String state, String reason, Object saved) {
            expect(state.equals("done") == (saved instanceof File), "saved handle only on done: " + state + " " + saved);
            all.add("finished " + id + " " + title + " " + state + " " + reason);
            finished.put(id, state + (reason.isEmpty() ? "" : ":" + reason));
            notifyAll();
        }
        synchronized String await(String id, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!finished.containsKey(id)) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new AssertionError("timeout waiting for " + id);
                wait(left);
            }
            return finished.get(id);
        }
    }

    static int idCounter;
    static synchronized String newId() { return String.format(Locale.ROOT, "%032x", ++idCounter); }

    static DirectDownloads engine(SafeHttpClient c, DirStore store, Events ev) throws IOException {
        return new DirectDownloads(c, store, ev, 60_000);
    }

    /** Startet einen Download und wartet auf das Ende; liefert "zustand[:grund]". */
    static String run(Server srv, DirStore store, String path) throws Exception {
        Events ev = new Events();
        DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
        String id = newId();
        d.start(id, srv.url(path), "Clip");
        String r = ev.await(id, 60_000);
        d.stopAll();
        if (System.getenv("DL_DEBUG") != null) System.out.println("  " + path + " -> " + r);
        return r;
    }

    // ------------------------------------------------------------------ Tests

    public static void main(String[] args) throws Exception {
        root = new File(args[0]);
        check("large file streamed with bounded heap, sha-256 identical", TestDirectDownloads::largeFile);
        check("wrong status / 206 to plain request", TestDirectDownloads::statusChecks);
        check("content-length required, type and mp4 signature", TestDirectDownloads::headerChecks);
        check("too large rejected before any byte stored", TestDirectDownloads::tooLarge);
        check("truncated body without validator fails, partial file removed", TestDirectDownloads::truncatedNoValidator);
        check("weak etag does not allow resume", TestDirectDownloads::weakEtag);
        check("interruption resumed with exact range, content identical", TestDirectDownloads::resumeOk);
        check("range errors: 200 to range, wrong start/end/total, length, changed etag", TestDirectDownloads::rangeErrors);
        check("at most 3 resumes", TestDirectDownloads::maxResumes);
        check("If-Range honoured: changed representation answers 200, never mixed", TestDirectDownloads::ifRangeHonoured);
        check("only syntactically strong ETags allow resume", TestDirectDownloads::etagSyntax);
        check("content-encoding other than identity rejected", TestDirectDownloads::contentEncoding);
        check("storage not writable (permission): failed before any network access", TestDirectDownloads::storagePermission);
        check("crash recovery: published -> done, unpublished -> interrupted, unsure -> interrupted", TestDirectDownloads::crashRecovery);
        check("journal write failure after create: failed, partial removed", TestDirectDownloads::journalFailureAfterCreate);
        check("no space before download: nothing created", TestDirectDownloads::noSpaceBefore);
        check("no space during download: stopped and cleaned", TestDirectDownloads::noSpaceDuring);
        check("write error (ENOSPC) is a storage failure, never resumed", TestDirectDownloads::writeError);
        check("cancel during body: connection closed, partial removed", TestDirectDownloads::cancelBody);
        check("cancel while waiting for headers (no 30 s read timeout)", TestDirectDownloads::cancelHeaders);
        check("cancel unknown id / after done", TestDirectDownloads::cancelOther);
        check("duplicate and late start: one request only", TestDirectDownloads::duplicates);
        check("journal across restart: done stays done, running becomes interrupted", TestDirectDownloads::journal);
        check("journal failure: start refused, nothing fetched", TestDirectDownloads::journalFailure);
        check("corrupt journal fails closed", TestDirectDownloads::corruptJournal);
        check("parallel limit: third start busy, not recorded", TestDirectDownloads::parallelLimit);
        check("blocked targets: failed, zero connections, no fallback", TestDirectDownloads::blocked);
        check("blocked redirect: second server never contacted", TestDirectDownloads::blockedRedirect);
        check("no URL in responses, journal or notifications", TestDirectDownloads::noUrlLeak);
        check("no cookies or credentials sent", TestDirectDownloads::noCredentials);
        check("stopAll cancels running downloads", TestDirectDownloads::stopAll);
        check("file name never from URL", TestDirectDownloads::names);
        System.out.println("dl tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void largeFile() throws Exception {
        long total = Long.getLong("dl.large", 512L << 20);
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, "\"v1\"", -1))) {
            DirStore store = new DirStore("large");
            Runtime rt = Runtime.getRuntime();
            AtomicLong peak = new AtomicLong();
            Thread sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            long t0 = System.nanoTime();
            String r = run(srv, store, "/big.mp4?token=SECRET");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            sampler.interrupt();
            expect(r.equals("done"), r);
            File f = store.only();
            expect(f.getName().equals("Clip.mp4"), f.getName());
            expect(f.length() == total, "length " + f.length());
            expect(java.util.Arrays.equals(fileSha(f), expectedSha(total)), "sha-256");
            System.out.println("  " + (total >> 20) + " MiB in " + ms + " ms, heap peak " + (peak.get() >> 20) + " MiB of max "
                + (rt.maxMemory() >> 20) + " MiB");
            expect(srv.requests.get() == 1, "requests " + srv.requests.get());
            f.delete();
        }
    }

    static void statusChecks() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            if (r.line.contains("/404")) head(out, "404 Not Found", "Content-Length: 0");
            else if (r.line.contains("/206")) head(out, "206 Partial Content", "Content-Length: 16", "Content-Range: bytes 0-15/16");
            else head(out, "500 Oops", "Content-Length: 0");
            if (r.line.contains("/206")) content(out, 0, 16);
        })) {
            DirStore store = new DirStore("status");
            expect(run(srv, store, "/404").equals("failed:http_404"), "404");
            expect(run(srv, store, "/206").equals("failed:http_206"), "206");
            expect(run(srv, store, "/500").equals("failed:http_500"), "500");
            expect(store.created.get() == 0, "nothing created");
            store.expectEmpty();
        }
    }

    static void headerChecks() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            if (r.line.contains("/chunked")) {
                head(out, "200 OK", "Content-Type: video/mp4", "Transfer-Encoding: chunked");
                out.write("8\r\n\0\0\0\u0018ftyp\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } else if (r.line.contains("/html")) {
                head(out, "200 OK", "Content-Type: text/html; charset=utf-8", "Content-Length: 16");
                content(out, 0, 16);
            } else if (r.line.contains("/notmp4")) {
                head(out, "200 OK", "Content-Type: application/octet-stream", "Content-Length: 16");
                out.write("<html><body></bo".getBytes(StandardCharsets.ISO_8859_1));
            } else if (r.line.contains("/empty")) {
                head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 0");
            } else if (r.line.contains("/notype")) {
                head(out, "200 OK", "Content-Length: 1024");
                content(out, 0, 1024);
            }
        })) {
            DirStore store = new DirStore("headers");
            expect(run(srv, store, "/chunked").equals("failed:no_length"), "chunked");
            expect(run(srv, store, "/html").equals("failed:type"), "html");
            expect(run(srv, store, "/notmp4").equals("failed:not_mp4"), "not mp4");
            expect(run(srv, store, "/empty").equals("failed:no_length"), "empty");
            expect(store.created.get() == 0, "nothing created");
            expect(run(srv, store, "/notype").equals("done"), "no content-type is accepted when the signature matches");
            expect(store.only().length() == 1024, "length");
        }
        expect(DirectDownloads.acceptedType("video/MP4; codecs=avc1"), "params");
        expect(!DirectDownloads.acceptedType("video/webm"), "webm");
        expect(!DirectDownloads.acceptedType("application/vnd.apple.mpegurl"), "hls");
        expect(!DirectDownloads.acceptedType("application/dash+xml"), "dash");
    }

    static void tooLarge() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + (DirectDownloads.MAX_FILE_BYTES + 1));
            content(out, 0, 8);
        })) {
            DirStore store = new DirStore("toolarge");
            expect(run(srv, store, "/huge").equals("failed:too_large"), "too large");
            expect(store.created.get() == 0, "nothing created");
        }
    }

    static void truncatedNoValidator() throws Exception {
        long total = 1 << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, null, 300_000))) {
            DirStore store = new DirStore("trunc");
            expect(run(srv, store, "/v.mp4").equals("failed:network"), "network");
            expect(srv.requests.get() == 1, "no resume without validator");
            expect(store.created.get() == 1 && store.aborted.get() == 1 && store.committed.get() == 0, "aborted");
            store.expectEmpty();
        }
    }

    static void weakEtag() throws Exception {
        long total = 1 << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, "W/\"weak\"", 300_000))) {
            DirStore store = new DirStore("weak");
            expect(run(srv, store, "/v.mp4").equals("failed:network"), "network");
            expect(srv.requests.get() == 1, "weak etag must not resume");
            store.expectEmpty();
        }
    }

    static void resumeOk() throws Exception {
        long total = 6L << 20;
        try (Server srv = new Server((r, out, i) -> {
            // Erster Abruf bricht bei 1 MiB ab, zweiter bei 3 MiB, dritter liefert den Rest.
            long cut = i == 0 ? 1 << 20 : i == 1 ? 3 << 20 : -1;
            serveFile(r, out, total, "\"abc\"", cut);
        })) {
            DirStore store = new DirStore("resume");
            expect(run(srv, store, "/v.mp4").equals("done"), "done");
            expect(srv.requests.get() == 3, "requests " + srv.requests.get());
            expect(srv.seen.get(0).header("range") == null, "first without range");
            expect(srv.seen.get(1).header("range").equals("bytes=" + (1 << 20) + "-" + (total - 1)), srv.seen.get(1).header("range"));
            expect(srv.seen.get(2).header("range").equals("bytes=" + (3 << 20) + "-" + (total - 1)), srv.seen.get(2).header("range"));
            // If-Range mit genau dem starken ETag der ersten Antwort, nie ohne (RFC 9110 13.1.5).
            expect(srv.seen.get(0).header("if-range") == null, "no If-Range without Range");
            expect("\"abc\"".equals(srv.seen.get(1).header("if-range")) && "\"abc\"".equals(srv.seen.get(2).header("if-range")), "If-Range");
            File f = store.only();
            expect(java.util.Arrays.equals(fileSha(f), expectedSha(total)), "sha-256 after resume");
        }
        // Last-Modified ist kein starker Validator (Sekundenauflösung, RFC 9110 8.8.2.2): kein Fortsetzen, sauberer Fehler.
        try (Server srv = new Server((r, out, i) -> {
            long start = r.rangeStart();
            if (start < 0) head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 4096", "Last-Modified: Tue, 01 Sep 2026 10:00:00 GMT");
            else head(out, "206 Partial Content", "Content-Type: video/mp4", "Content-Length: " + (4096 - start),
                "Content-Range: bytes " + start + "-4095/4096", "Last-Modified: Tue, 01 Sep 2026 10:00:00 GMT");
            content(out, Math.max(0, start), start < 0 ? 1000 : 4096);
            if (start < 0) throw new IOException("cut");
        })) {
            DirStore store = new DirStore("resume-lm");
            String r = run(srv, store, "/v.mp4");
            expect(r.equals("failed:network"), "last-modified only: " + r);
            expect(srv.requests.get() == 1, "no range request with Last-Modified only");
            expect(store.created.get() == 1 && store.aborted.get() == 1, "partial removed");
            store.expectEmpty();
        }
    }

    static void rangeErrors() throws Exception {
        long total = 1 << 20, cut = 200_000;
        String[][] cases = {
            // Antwort auf die Fortsetzung, erwarteter Grund
            {"200", "range_ignored"},
            {"bad-start", "range_mismatch"},
            {"bad-end", "range_mismatch"},
            {"bad-total", "range_mismatch"},
            {"bad-header", "range_header"},
            {"no-header", "range_header"},
            {"bad-length", "range_length"},
            {"etag-changed", "changed"},
            {"etag-missing", "changed"},
            {"416", "range_http_416"},
            {"weak-etag", "changed"},
            {"multipart", "range_multipart"},
            {"gzip", "encoding"},
        };
        for (String[] c : cases) {
            String mode = c[0];
            try (Server srv = new Server((r, out, i) -> {
                if (i == 0) { serveFile(r, out, total, "\"e1\"", cut); return; }
                long s = r.rangeStart();
                String cr = "Content-Range: bytes " + s + "-" + (total - 1) + "/" + total;
                String len = "Content-Length: " + (total - s);
                String et = "ETag: \"e1\"";
                String ty = "Content-Type: video/mp4", enc = "";
                switch (mode) {
                    case "200": head(out, "200 OK", "Content-Length: " + total, et); content(out, 0, total); return;
                    case "bad-start": cr = "Content-Range: bytes " + (s - 1) + "-" + (total - 1) + "/" + total; len = "Content-Length: " + (total - s + 1); break;
                    case "bad-end": cr = "Content-Range: bytes " + s + "-" + (total - 2) + "/" + total; len = "Content-Length: " + (total - s - 1); break;
                    case "bad-total": cr = "Content-Range: bytes " + s + "-" + (total - 1) + "/" + (total + 1); break;
                    case "bad-header": cr = "Content-Range: items " + s + "-" + (total - 1) + "/" + total; break;
                    case "no-header": cr = "X-None: 1"; break;
                    case "bad-length": len = "Content-Length: " + (total - s - 10); break;
                    case "etag-changed": et = "ETag: \"e2\""; break;
                    case "etag-missing": et = "X-None: 1"; break;
                    case "416": head(out, "416 Range Not Satisfiable", "Content-Length: 0"); return;
                    case "weak-etag": et = "ETag: W/\"e1\""; break;
                    case "multipart": ty = "Content-Type: multipart/byteranges; boundary=B"; cr = "X-None: 1"; break;
                    case "gzip": enc = "Content-Encoding: gzip"; break;
                    default: throw new AssertionError(mode);
                }
                head(out, "206 Partial Content", ty, len, cr, et, enc);
                long n = Long.parseLong(len.substring("Content-Length: ".length()));
                content(out, s, s + n);
            })) {
                DirStore store = new DirStore("range-" + mode);
                String r = run(srv, store, "/v.mp4");
                expect(r.equals("failed:" + c[1]), mode + ": " + r);
                expect(srv.requests.get() == 2, mode + ": requests " + srv.requests.get());
                expect("\"e1\"".equals(srv.seen.get(1).header("if-range")), mode + ": If-Range");
                store.expectEmpty();
            }
        }
        expect(!DirectDownloads.CONTENT_RANGE.matcher("bytes 1-2/*").matches(), "unknown total rejected");
        expect(!DirectDownloads.CONTENT_RANGE.matcher("bytes 99999999999999999999-1/2").matches(), "overflow rejected");
    }

    static void maxResumes() throws Exception {
        long total = 1 << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, "\"x\"", Math.max(0, r.rangeStart()) + 100_000))) {
            DirStore store = new DirStore("maxres");
            expect(run(srv, store, "/v.mp4").equals("failed:network"), "network");
            expect(srv.requests.get() == 1 + DirectDownloads.MAX_RESUMES, "requests " + srv.requests.get());
            store.expectEmpty();
        }
    }

    static void noSpaceBefore() throws Exception {
        long total = 1 << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, null, -1))) {
            DirStore store = new DirStore("nospace");
            store.free.set(total + DirectDownloads.SPACE_RESERVE_BYTES - 1);
            expect(run(srv, store, "/v.mp4").equals("failed:no_space"), "no space");
            expect(store.created.get() == 0, "nothing created");
            store.free.set(total + DirectDownloads.SPACE_RESERVE_BYTES);
            expect(run(srv, store, "/v.mp4").equals("done"), "exactly enough");
        }
    }

    static void noSpaceDuring() throws Exception {
        long total = 32L << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, null, -1))) {
            DirStore store = new DirStore("nospace2");
            store.freeAfterBytes = 4L << 20;      // anderer Prozess füllt den Speicher
            store.freeLater = 8L << 20;          // Rest (≈ 24 MiB) passt nicht mehr
            expect(run(srv, store, "/v.mp4").equals("failed:no_space"), "no space during");
            expect(store.written.get() < 16L << 20, "stopped early: " + store.written.get());
            expect(store.aborted.get() == 1 && store.committed.get() == 0, "aborted");
            store.expectEmpty();
        }
    }

    static void writeError() throws Exception {
        long total = 4L << 20;
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, total, "\"x\"", -1))) {
            DirStore store = new DirStore("enospc");
            store.failWriteAfter = 1L << 20;
            expect(run(srv, store, "/v.mp4").equals("failed:storage"), "storage");
            expect(srv.requests.get() == 1, "write errors are not resumed");
            store.expectEmpty();
        }
    }

    static void cancelBody() throws Exception {
        long total = 64L << 20;
        try (Server srv = new Server((r, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total, "ETag: \"x\"");
            content(out, 0, 1 << 20);
            out.flush();
            for (long p = 1 << 20; p < total; p += 1024) { content(out, p, p + 1024); out.flush(); Thread.sleep(5); }
        })) {
            DirStore store = new DirStore("cancel");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String id = newId();
            d.start(id, srv.url("/v.mp4"), "Clip");
            long until = System.currentTimeMillis() + 10_000;
            while (store.written.get() < (1 << 20) && System.currentTimeMillis() < until) Thread.sleep(10);
            expect(store.written.get() >= 1 << 20, "started");
            long t0 = System.nanoTime();
            String c = d.cancel(id);
            expect(c.contains("\"id\":\"" + id + "\""), c);
            String r = ev.await(id, 5_000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            expect(r.equals("cancelled"), r);
            expect(ms < 2_000, "cancel took " + ms + " ms");
            expect(d.status(id).contains("\"state\":\"cancelled\""), d.status(id));
            expect(srv.requests.get() == 1, "no resume after cancel");
            store.expectEmpty();
            until = System.currentTimeMillis() + 5_000;
            while (!srv.open.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
            expect(srv.open.isEmpty(), "server connection closed");
            d.stopAll();
        }
    }

    static void cancelHeaders() throws Exception {
        try (Server srv = new Server((r, out, i) -> Thread.sleep(60_000))) {
            DirStore store = new DirStore("cancelhdr");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String id = newId();
            d.start(id, srv.url("/v.mp4"), "Clip");
            long until = System.currentTimeMillis() + 5_000;
            while (srv.requests.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(10);
            expect(srv.requests.get() == 1, "request sent");
            long t0 = System.nanoTime();
            d.cancel(id);
            String r = ev.await(id, 5_000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            expect(r.equals("cancelled"), r);
            expect(ms < 2_000, "cancel took " + ms + " ms");
            expect(store.created.get() == 0, "nothing created");
            d.stopAll();
        }
    }

    static void cancelOther() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1))) {
            DirStore store = new DirStore("cancelother");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String unknown = newId();
            expect(d.cancel(unknown).contains("\"state\":\"unknown\""), "unknown");
            expect(d.status(unknown).contains("\"state\":\"unknown\""), "unknown status");
            String id = newId();
            d.start(id, srv.url("/v.mp4"), "Clip");
            expect(ev.await(id, 10_000).equals("done"), "done");
            expect(d.cancel(id).contains("\"state\":\"done\""), "cancel after done keeps done");
            expect(store.only().length() == 4096, "file kept");
            d.stopAll();
        }
    }

    static void duplicates() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 65536");
            content(out, 0, 8);
            out.flush();
            Thread.sleep(400);
            content(out, 8, 65536);
        })) {
            DirStore store = new DirStore("dup");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String id = newId();
            String a = d.start(id, srv.url("/v.mp4"), "Clip");
            String b = d.start(id, srv.url("/v.mp4"), "Clip");          // wiederholt
            String c = d.start(id, srv.url("/other.mp4"), "Other");      // gleiche Kennung, andere URL
            expect(a.contains("\"state\":\"running\""), a);
            expect(b.contains("\"state\":\"running\"") && c.contains("\"state\":\"running\""), b + c);
            expect(ev.await(id, 10_000).equals("done"), "done");
            String late = d.start(id, srv.url("/v.mp4"), "Clip");        // verspätet
            expect(late.contains("\"state\":\"done\""), late);
            Thread.sleep(200);
            expect(srv.requests.get() == 1, "requests " + srv.requests.get());
            expect(store.files().length == 1 && store.created.get() == 1, "one file");
            d.stopAll();
        }
    }

    static void journal() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            if (r.line.contains("/slow")) { head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 65536"); content(out, 0, 8); out.flush(); Thread.sleep(60_000); }
            else serveFile(r, out, 4096, null, -1);
        })) {
            DirStore store = new DirStore("journal");
            Events ev = new Events();
            DirectDownloads d1 = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String done = newId(), running = newId();
            d1.start(done, srv.url("/v.mp4"), "Clip");
            expect(ev.await(done, 10_000).equals("done"), "done");
            d1.start(running, srv.url("/slow"), "Slow");
            long until = System.currentTimeMillis() + 5_000;
            while (srv.requests.get() < 2 && System.currentTimeMillis() < until) Thread.sleep(10);
            // "Absturz": Journal steht auf running; neue Engine liest es ein.
            byte[] snapshot = store.loadJournal();
            expect(new String(snapshot, StandardCharsets.US_ASCII).contains(running + " RUNNING"), "journal running");
            DirStore crashed = new DirStore("journal2");
            crashed.journal = snapshot;
            Events ev2 = new Events();
            DirectDownloads d2 = engine(LocalTestClients.loopback(srv.port()), crashed, ev2);
            expect(d2.status(done).contains("\"state\":\"done\""), d2.status(done));
            expect(d2.status(running).contains("\"state\":\"interrupted\""), d2.status(running));
            int before = srv.requests.get();
            expect(d2.start(done, srv.url("/v.mp4"), "Clip").contains("\"state\":\"done\""), "late start after restart");
            expect(d2.start(running, srv.url("/slow"), "Slow").contains("\"state\":\"interrupted\""), "interrupted is final");
            Thread.sleep(200);
            expect(srv.requests.get() == before, "no request after restart");
            expect(!new String(crashed.loadJournal(), StandardCharsets.US_ASCII).contains("RUNNING"), "journal rewritten");
            d1.stopAll();
            d2.stopAll();
            expect(ev.await(running, 5_000).equals("cancelled"), "stopAll");
        }
        // Begrenzung: höchstens JOURNAL_MAX Einträge, älteste beendete fallen heraus.
        try (Server srv = new Server((r, out, i) -> head(out, "404 Not Found", "Content-Length: 0"))) {
            DirStore store = new DirStore("journal-cap");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String first = null;
            for (int k = 0; k < DirectDownloads.JOURNAL_MAX + 10; k++) {
                String id = newId();
                if (first == null) first = id;
                d.start(id, srv.url("/x"), "x");
                ev.await(id, 10_000);
            }
            String[] lines = new String(store.loadJournal(), StandardCharsets.US_ASCII).split("\n");
            expect(lines.length - 1 <= DirectDownloads.JOURNAL_MAX, "journal size " + lines.length);
            expect(d.status(first).contains("unknown"), "oldest dropped");
            d.stopAll();
        }
    }

    static void journalFailure() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1))) {
            DirStore store = new DirStore("journalfail");
            store.failJournal = true;
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String id = newId();
            String r = d.start(id, srv.url("/v.mp4"), "Clip");
            expect(r.contains("\"state\":\"busy\""), r);
            Thread.sleep(300);
            expect(srv.connections.get() == 0, "nothing fetched without durable record");
            expect(d.status(id).contains("unknown"), "not recorded");
            d.stopAll();
        }
    }

    static void corruptJournal() throws Exception {
        for (String j : new String[] {"garbage", "mp4-jobs-v1\nzz RUNNING 0 0 x", "mp4-jobs-v1\n" + "0".repeat(32) + " NOPE 0 0 x"}) {
            DirStore store = new DirStore("corrupt-" + Math.abs(j.hashCode()));
            store.journal = j.getBytes(StandardCharsets.US_ASCII);
            try {
                engine(LocalTestClients.strict(), store, new Events());
                throw new AssertionError("accepted corrupt journal: " + j);
            } catch (IOException | IllegalArgumentException expected) {
                // Dienst startet keine Downloads mit unklarem Journal (sonst wären Doppelstarts möglich).
            }
        }
    }

    static void parallelLimit() throws Exception {
        Object gate = new Object();
        boolean[] open = {false};
        try (Server srv = new Server((r, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 4096");
            content(out, 0, 8);
            out.flush();
            synchronized (gate) { while (!open[0]) gate.wait(); }
            content(out, 8, 4096);
        })) {
            DirStore store = new DirStore("parallel");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String a = newId(), b = newId(), c = newId();
            d.start(a, srv.url("/a"), "A");
            d.start(b, srv.url("/b"), "B");
            String third = d.start(c, srv.url("/c"), "C");
            expect(third.contains("\"state\":\"busy\"") && third.contains("\"ok\":false"), third);
            expect(d.status(c).contains("unknown"), "busy is not recorded");
            long until = System.currentTimeMillis() + 5_000;
            while (srv.requests.get() < 2 && System.currentTimeMillis() < until) Thread.sleep(10);
            Thread.sleep(200);
            expect(srv.requests.get() == 2, "only two requests: " + srv.requests.get());
            synchronized (gate) { open[0] = true; gate.notifyAll(); }
            expect(ev.await(a, 10_000).equals("done") && ev.await(b, 10_000).equals("done"), "both done");
            d.start(c, srv.url("/c"), "C");
            expect(ev.await(c, 10_000).equals("done"), "third later");
            d.stopAll();
        }
    }

    static void blocked() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1))) {
            DirStore store = new DirStore("blocked");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.strict(), store, ev);
            String[][] cases = {
                {"http://127.0.0.1:" + srv.port() + "/v.mp4", "blocked_"},
                {"http://127.0.0.1/v.mp4", "blocked_address"},
                {"http://localhost/v.mp4", "blocked_host_name"},
                {"http://10.0.0.1/v.mp4", "blocked_address"},
                {"http://169.254.169.254/latest/meta-data", "blocked_address"},
                {"http://[::1]/v.mp4", "blocked_address"},
                {"http://[::ffff:192.168.1.1]/v.mp4", "blocked_address"},
                {"http://2130706433/v.mp4", "blocked_"},
                {"http://router.lan/v.mp4", "blocked_host_name"},
                {"ftp://example.com/v.mp4", "blocked_bad_url"},
                {"http://user:pw@example.com/v.mp4", "blocked_credentials_in_url"},
                {"http://example.com:8080/v.mp4", "blocked_port"},
                {"file:///sdcard/x.mp4", "blocked_"},
            };
            for (String[] c : cases) {
                String id = newId();
                d.start(id, c[0], "x");
                String r = ev.await(id, 10_000);
                expect(r.startsWith("failed:" + c[1]), c[0] + " -> " + r);
            }
            expect(srv.connections.get() == 0, "server contacted " + srv.connections.get() + " times");
            expect(store.created.get() == 0, "nothing created");
            d.stopAll();
        }
    }

    static void blockedRedirect() throws Exception {
        try (Server second = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1));
             Server first = new Server((r, out, i) -> head(out, "302 Found", "Location: http://127.0.0.1:" + second.port() + "/v.mp4", "Content-Length: 0"))) {
            DirStore store = new DirStore("redirect");
            String r = run(first, store, "/start");
            expect(r.startsWith("failed:blocked_"), r);
            expect(first.requests.get() == 1, "one request, no retry");
            expect(second.connections.get() == 0, "redirect target contacted");
            store.expectEmpty();
        }
    }

    static void noUrlLeak() throws Exception {
        long total = 2L << 20;
        try (Server srv = new Server((r, out, i) -> {
            if (r.line.contains("/fail")) { serveFile(r, out, total, null, 100_000); return; }
            serveFile(r, out, total, "\"x\"", i == 0 ? 500_000 : -1);
        })) {
            DirStore store = new DirStore("leak");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            List<String> responses = new ArrayList<>();
            String a = newId(), b = newId();
            responses.add(d.start(a, srv.url("/media/SECRETPATH.mp4?sig=SECRETTOKEN"), "Clip"));
            expect(ev.await(a, 10_000).equals("done"), "done");
            responses.add(d.start(b, srv.url("/fail/SECRETPATH.mp4?sig=SECRETTOKEN"), "Clip"));
            expect(ev.await(b, 10_000).equals("failed:network"), "fail");
            responses.add(d.status(a));
            responses.add(d.status(b));
            responses.add(d.cancel(a));
            List<String> everything = new ArrayList<>(responses);
            everything.addAll(ev.all);
            everything.add(new String(store.loadJournal(), StandardCharsets.US_ASCII));
            everything.addAll(java.util.Arrays.asList(store.files()));
            for (String s : everything) {
                for (String secret : new String[] {"SECRET", "dl.example", "http", "sig=", String.valueOf(srv.port())}) {
                    expect(!s.contains(secret), "leak of " + secret + " in: " + s);
                }
            }
            d.stopAll();
        }
    }

    static void noCredentials() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1))) {
            DirStore store = new DirStore("creds");
            expect(run(srv, store, "/v.mp4").equals("done"), "done");
            Req r = srv.seen.get(0);
            for (String h : new String[] {"cookie", "cookie2", "authorization", "proxy-authorization", "referer", "origin"}) {
                expect(r.header(h) == null, "sent " + h);
            }
            expect("identity".equals(r.header("accept-encoding")), "identity");
        }
    }

    static void stopAll() throws Exception {
        try (Server srv = new Server((r, out, i) -> { head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 65536"); content(out, 0, 8); out.flush(); Thread.sleep(60_000); })) {
            DirStore store = new DirStore("stopall");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String a = newId();
            d.start(a, srv.url("/a"), "A");
            long until = System.currentTimeMillis() + 5_000;
            while (store.created.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(10);
            d.stopAll();
            expect(ev.await(a, 5_000).equals("cancelled"), "cancelled");
            expect(d.start(newId(), srv.url("/b"), "B").contains("busy"), "no start after stop");
            store.expectEmpty();
            d.serviceStarted();                                    // Dienst-Neustart im selben Prozess
            expect(d.status(a).contains("cancelled"), "state kept");
            expect(d.start(a, srv.url("/a"), "A").contains("cancelled"), "old id stays final");
        }
    }

    static void names() {
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle(null)).equals("Video.mp4"), "default");
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle("../../etc/passwd")).equals("etcpasswd.mp4"),
            DirectDownloads.fileName(DirectDownloads.displayTitle("../../etc/passwd")));
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle("Urlaub 2026 (Teil 1)")).equals("Urlaub 2026 (Teil 1).mp4"), "plain");
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle("a<b>:c|d?*")).equals("abcd.mp4"), "reserved chars");
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle("....")).equals("Video.mp4"), "dots only");
        expect(DirectDownloads.displayTitle("x".repeat(500)).length() == 80, "length cap");
        expect(DirectDownloads.fileName(DirectDownloads.displayTitle("Ünïcødé")).equals("Ünïcødé.mp4"), "unicode letters kept");
    }

    static void ifRangeHonoured() throws Exception {
        long total = 1 << 20;
        String[] current = {"\"v1\""};
        try (Server srv = new Server((r, out, i) -> {
            String tag = current[0];
            current[0] = "\"v2\"";                         // Darstellung ändert sich nach der ersten Antwort
            serveFile(r, out, total, tag, i == 0 ? 300_000 : -1);
        })) {
            DirStore store = new DirStore("ifrange");
            String r = run(srv, store, "/v.mp4");
            expect(r.equals("failed:range_ignored"), r);
            expect(srv.requests.get() == 2, "requests " + srv.requests.get());
            expect("\"v1\"".equals(srv.seen.get(1).header("if-range")), "If-Range carries the first ETag");
            store.expectEmpty();
        }
    }

    static void etagSyntax() throws Exception {
        for (String tag : new String[] {"abc", "\"a b\"", "W/\"x\"", "\"x", "w/\"x\"", "\"a\"\"b\""}) {
            long total = 1 << 20;
            try (Server srv = new Server((r, out, i) -> {
                head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total, "ETag: " + tag);
                content(out, 0, 200_000);
                out.flush();
                throw new IOException("cut");
            })) {
                DirStore store = new DirStore("etag-" + Math.abs(tag.hashCode()));
                String r = run(srv, store, "/v.mp4");
                expect(r.equals("failed:network") && srv.requests.get() == 1, tag + " -> " + r + " / " + srv.requests.get());
                store.expectEmpty();
            }
        }
        expect(SafeHttpClient.isStrongEtag("\"abc\"") && SafeHttpClient.isStrongEtag("\"\""), "strong");
        expect(!SafeHttpClient.isStrongEtag("W/\"abc\"") && !SafeHttpClient.isStrongEtag(null) && !SafeHttpClient.isStrongEtag("\"\u00e4\""), "weak/invalid");
    }

    static void contentEncoding() throws Exception {
        try (Server srv = new Server((r, out, i) -> {
            head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: 4096", "Content-Encoding: gzip");
            content(out, 0, 4096);
        })) {
            DirStore store = new DirStore("encoding");
            expect(run(srv, store, "/v.mp4").equals("failed:encoding"), "gzip");
            expect(store.created.get() == 0, "nothing created");
        }
        expect(DirectDownloads.identityEncoding(null) && DirectDownloads.identityEncoding("identity") && DirectDownloads.identityEncoding(" Identity "), "identity");
        expect(!DirectDownloads.identityEncoding("br") && !DirectDownloads.identityEncoding("gzip, identity"), "encodings");
    }

    static void storagePermission() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 4096, null, -1))) {
            DirStore store = new DirStore("permission");
            store.writable = false;
            expect(run(srv, store, "/v.mp4").equals("failed:storage_permission"), "permission");
            expect(srv.connections.get() == 0, "network contacted without storage permission");
            expect(store.created.get() == 0, "nothing created");
            store.writable = true;
            expect(run(srv, store, "/v.mp4").equals("done"), "after grant");
        }
    }

    static void crashRecovery() throws Exception {
        long total = 256 << 10;
        try (Server srv = new Server((r, out, i) -> {
            if (r.line.contains("/slow")) { head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + total); content(out, 0, 8); out.flush(); Thread.sleep(60_000); }
            else serveFile(r, out, total, null, -1);
        })) {
            // (a) Absturz nach dem Veröffentlichen, bevor "DONE" im Journal stand: Datei vorhanden -> fertig.
            DirStore store = new DirStore("crash-a");
            Events ev = new Events();
            DirectDownloads d = engine(LocalTestClients.loopback(srv.port()), store, ev);
            String id = newId();
            d.start(id, srv.url("/v.mp4"), "Clip");
            expect(ev.await(id, 10_000).equals("done"), "done");
            d.stopAll();
            String atCommit = new String(store.journalAtCommit, StandardCharsets.US_ASCII);
            expect(atCommit.startsWith("mp4-jobs-v2\n") && atCommit.contains(id + " RUNNING ") && !atCommit.endsWith(" -"), "journal at commit: " + atCommit);
            store.journal = store.journalAtCommit;
            DirectDownloads after = engine(LocalTestClients.loopback(srv.port()), store, new Events());
            expect(after.status(id).contains("\"state\":\"done\"") && after.status(id).contains("\"bytes\":" + total), after.status(id));
            int before = srv.requests.get();
            expect(after.start(id, srv.url("/v.mp4"), "Clip").contains("\"state\":\"done\""), "late start");
            expect(srv.requests.get() == before, "no new request");
            expect(!new String(store.journal, StandardCharsets.US_ASCII).contains("RUNNING"), "journal rewritten as done");
            after.stopAll();
            // (c) Prüfung der Ablage scheitert: sicher "interrupted", nie ungeprüft "done".
            store.journal = store.journalAtCommit;
            store.failPublishedCheck = true;
            DirectDownloads unsure = engine(LocalTestClients.loopback(srv.port()), store, new Events());
            expect(unsure.status(id).contains("\"state\":\"interrupted\""), unsure.status(id));
            unsure.stopAll();

            // (b) Absturz während des Schreibens: Verweis steht im Journal, Datei nie veröffentlicht -> unterbrochen.
            DirStore store2 = new DirStore("crash-b");
            Events ev2 = new Events();
            DirectDownloads d2 = engine(LocalTestClients.loopback(srv.port()), store2, ev2);
            String id2 = newId();
            d2.start(id2, srv.url("/slow"), "Slow");
            long until = System.currentTimeMillis() + 5_000;
            while ((store2.journal == null || !new String(store2.journal, StandardCharsets.US_ASCII).matches("(?s).*" + id2 + " RUNNING \\S+ \\S+ \\S* [A-Za-z0-9_-]+.*"))
                   && System.currentTimeMillis() < until) Thread.sleep(10);
            byte[] crashed = store2.loadJournal();
            d2.cancel(id2);                                  // der "abgestürzte" Prozess räumt nichts mehr auf …
            expect(ev2.await(id2, 5_000).equals("cancelled"), "cancelled");
            store2.journal = crashed;                         // … und hinterlässt dieses Journal
            DirectDownloads after2 = engine(LocalTestClients.loopback(srv.port()), store2, new Events());
            expect(after2.status(id2).contains("\"state\":\"interrupted\""), after2.status(id2));
            after2.stopAll();
            d2.stopAll();
        }
        // (d) Journal v1 (vor diesem Schritt) bleibt lesbar; laufend -> unterbrochen.
        DirStore v1 = new DirStore("journal-v1");
        String a = newId(), b = newId();
        v1.journal = ("mp4-jobs-v1\n" + a + " DONE 10 10 \n" + b + " RUNNING 5 10 ").getBytes(StandardCharsets.US_ASCII);
        DirectDownloads e1 = engine(LocalTestClients.strict(), v1, new Events());
        expect(e1.status(a).contains("done") && e1.status(b).contains("interrupted"), "v1");
        e1.stopAll();
        // (e) beschädigter Verweis: sicher fehlschlagen (keine Downloads statt unklarer Zustände).
        DirStore bad = new DirStore("journal-bad-ref");
        bad.journal = ("mp4-jobs-v2\n" + newId() + " RUNNING 5 10  !!!").getBytes(StandardCharsets.US_ASCII);
        try { engine(LocalTestClients.strict(), bad, new Events()); throw new AssertionError("accepted bad ref"); }
        catch (IOException expected) { }
    }

    static void journalFailureAfterCreate() throws Exception {
        try (Server srv = new Server((r, out, i) -> serveFile(r, out, 1 << 20, null, -1))) {
            DirStore store = new DirStore("journal-after-create");
            store.failJournalAfterSaves = 1;                 // Start vermerkt, Verweis nicht mehr
            String r = run(srv, store, "/v.mp4");
            expect(r.equals("failed:storage"), r);
            expect(store.created.get() == 1 && store.aborted.get() == 1 && store.committed.get() == 0, "aborted");
            store.expectEmpty();
        }
    }
}
