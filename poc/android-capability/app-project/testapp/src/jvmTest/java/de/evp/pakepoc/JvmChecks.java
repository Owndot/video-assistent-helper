package de.evp.pakepoc;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM checks for the plain-Java parts of the test app (no Android, no JNI):
 * SnapshotWriter ordering (F1) and write-on-change (F4), and the request
 * read deadline (F3). Run by tools/run-jvm-checks.sh locally and in CI;
 * tools/java_mutation_check.py runs them against deliberately broken copies.
 * No test framework: each check throws on failure, main exits non-zero.
 */
public final class JvmChecks {
    private static int passed;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static long now() { return System.nanoTime() / 1_000_000; }

    // ------------------------------------------------------------ F1 / F4

    /** A tiny stand-in for the Rust host: registry bytes guarded by a lock. */
    static final class FakeCore {
        final Object lock = new Object();
        byte[] state = new byte[0];
        void set(String s) { synchronized (lock) { state = s.getBytes(StandardCharsets.US_ASCII); } }
        byte[] export() { synchronized (lock) { return state.clone(); } }
    }

    static final class Disk {
        final AtomicReference<byte[]> content = new AtomicReference<>();
        final AtomicInteger writes = new AtomicInteger();
        volatile int failNext;
        void write(byte[] b) throws Exception {
            if (failNext > 0) { failNext--; throw new java.io.IOException("disk full (simulated)"); }
            content.set(b.clone());
            writes.incrementAndGet();
        }
        String text() { byte[] b = content.get(); return b == null ? null : new String(b, StandardCharsets.US_ASCII); }
    }

    /**
     * Regression for F1, deterministic: an older snapshot is exported and
     * held right before its write; a revocation then runs in another thread.
     * The revocation's persist must not complete before the old write, and
     * the file must end up revoked. Against the unserialised writer the
     * revocation completes first and the old snapshot overwrites it.
     */
    static void oldSnapshotNeverOverwritesRevocation() throws Exception {
        FakeCore core = new FakeCore();
        Disk disk = new Disk();
        SnapshotWriter w = new SnapshotWriter(disk::write);
        core.set("A");
        check(w.persist(core::export), "initial write");

        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        w.testHook = snap -> {
            if (new String(snap, StandardCharsets.US_ASCII).equals("A,B")) {   // only the stale request
                reached.countDown();
                check(release.await(10, TimeUnit.SECONDS), "release");
            }
        };
        core.set("A,B");   // a parallel request (new pairing B) changes the registry
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread stale = new Thread(() -> { try { w.persist(core::export); } catch (Throwable t) { err.set(t); } });
        stale.start();
        check(reached.await(10, TimeUnit.SECONDS), "stale snapshot exported");

        core.set("");      // revoke all
        AtomicReference<Boolean> revokeDone = new AtomicReference<>(false);
        Thread revoke = new Thread(() -> { try { w.persist(core::export); revokeDone.set(true); } catch (Throwable t) { err.set(t); } });
        revoke.start();
        // Wait until the revocation either finished (unserialised writer) or
        // is stably blocked on the writer lock (serialised writer).
        long end = now() + 5000, blockedSince = -1;
        while (now() < end && revoke.isAlive()) {
            if (revoke.getState() == Thread.State.BLOCKED) {
                if (blockedSince < 0) blockedSince = now();
                else if (now() - blockedSince > 200) break;
            } else blockedSince = -1;
            Thread.sleep(10);
        }
        check(!revokeDone.get(), "revocation reported saved while an older write was still pending");
        release.countDown();
        stale.join(10_000);
        revoke.join(10_000);
        check(err.get() == null, "no thread failed: " + err.get());
        check("".equals(disk.text()), "file after restart must be revoked, was '" + disk.text() + "'");
        passed++;
    }

    /** F4: unchanged state is not rewritten; changes are. */
    static void writesOnlyOnChange() throws Exception {
        FakeCore core = new FakeCore();
        Disk disk = new Disk();
        SnapshotWriter w = new SnapshotWriter(disk::write);
        core.set("A");
        check(w.persist(core::export), "first write");
        for (int i = 0; i < 100; i++) check(!w.persist(core::export), "unchanged state rewritten");
        check(disk.writes.get() == 1, "exactly one write, got " + disk.writes.get());
        core.set("A*");   // e.g. first confirmation
        check(w.persist(core::export), "changed state written");
        check(disk.writes.get() == 2 && "A*".equals(disk.text()), "second write");
        check(!w.persist(() -> null), "stale owner exports nothing");
        passed++;
    }

    /** A failed write throws, is not recorded as durable and is retried. */
    static void failedWriteIsNotDurable() throws Exception {
        FakeCore core = new FakeCore();
        Disk disk = new Disk();
        SnapshotWriter w = new SnapshotWriter(disk::write);
        core.set("A");
        w.persist(core::export);
        core.set("");
        disk.failNext = 1;
        boolean threw = false;
        try { w.persist(core::export); } catch (java.io.IOException e) { threw = true; }
        check(threw, "write failure must surface");
        check("A".equals(disk.text()), "disk unchanged after failure");
        check(w.persist(core::export), "retried after failure");
        check("".equals(disk.text()), "revocation saved on retry");
        passed++;
    }

    /** Identity reset deletes in order with writes; the next persist writes again. */
    static void deleteResetsDurableState() throws Exception {
        FakeCore core = new FakeCore();
        Disk disk = new Disk();
        SnapshotWriter w = new SnapshotWriter(disk::write);
        core.set("A");
        w.persist(core::export);
        w.delete(() -> disk.content.set(null));
        check(disk.text() == null, "deleted");
        check(w.persist(core::export), "state written again after delete");
        passed++;
    }

    // ------------------------------------------------------------ F3

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Server() throws Exception {
            ss = new ServerSocket();
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        }
        int port() { return ss.getLocalPort(); }
        /** Accepts one connection; delayMs simulates time spent in the queue. */
        Thread serveOne(long delayMs) {
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    long acceptedAt = now();
                    if (delayMs > 0) Thread.sleep(delayMs);
                    try {
                        HttpRequestReader.Req r = HttpRequestReader.read(s, acceptedAt, JvmChecks::now);
                        outcomes.add("ok " + r.method + " " + r.path + " " + r.body.length);
                    } catch (SecurityException e) {
                        outcomes.add("reject " + e.getMessage() + " after " + (now() - acceptedAt));
                    }
                } catch (Exception e) {
                    outcomes.add("error " + e);
                }
            });
            t.start();
            return t;
        }
        @Override public void close() throws java.io.IOException { ss.close(); }
    }

    static Socket connect(int port) throws Exception {
        Socket c = new Socket();
        c.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
        return c;
    }

    static void completeRequestIsRead() throws Exception {
        try (Server srv = new Server()) {
            Thread t = srv.serveOne(0);
            try (Socket c = connect(srv.port())) {
                byte[] body = new byte[HttpRequestReader.MAX_BODY];
                OutputStream o = c.getOutputStream();
                o.write(("POST /p1/ch/rec HTTP/1.1\r\nHost: 127.0.0.1:47899\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                o.write(body);
                o.flush();
                t.join(5000);
            }
            check(srv.outcomes.equals(List.of("ok POST /p1/ch/rec " + HttpRequestReader.MAX_BODY)), "outcome " + srv.outcomes);
        }
        passed++;
    }

    /** One byte every 200 ms never times out per read, but hits the total budget. */
    static void tricklingClientHitsTheBudget() throws Exception {
        try (Server srv = new Server()) {
            Thread t = srv.serveOne(0);
            long t0 = now();
            try (Socket c = connect(srv.port())) {
                OutputStream o = c.getOutputStream();
                byte[] head = "POST /p1/ch/rec HTTP/1.1\r\nX-Slow: ".getBytes(StandardCharsets.ISO_8859_1);
                for (int i = 0; t.isAlive() && now() - t0 < 4 * HttpRequestReader.READ_BUDGET_MS; i++) {
                    try { o.write(head[i % head.length]); o.flush(); } catch (Exception closed) { break; }
                    Thread.sleep(200);
                }
                t.join(2000);
            }
            check(srv.outcomes.size() == 1 && srv.outcomes.get(0).startsWith("reject SlowRequest"), "outcome " + srv.outcomes);
            long took = Long.parseLong(srv.outcomes.get(0).replaceAll(".* after ", ""));
            check(took >= HttpRequestReader.READ_BUDGET_MS - 50 && took <= HttpRequestReader.READ_BUDGET_MS + 1000, "dropped after " + took + " ms");
        }
        passed++;
    }

    /** Header complete, body trickled: header and body share one budget. */
    static void slowBodySharesTheBudget() throws Exception {
        try (Server srv = new Server()) {
            Thread t = srv.serveOne(0);
            long t0 = now();
            try (Socket c = connect(srv.port())) {
                OutputStream o = c.getOutputStream();
                o.write("POST /p1/ch/rec HTTP/1.1\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                while (t.isAlive() && now() - t0 < 4 * HttpRequestReader.READ_BUDGET_MS) {
                    try { o.write(0); o.flush(); } catch (Exception closed) { break; }
                    Thread.sleep(200);
                }
                t.join(2000);
            }
            check(srv.outcomes.size() == 1 && srv.outcomes.get(0).startsWith("reject SlowRequest"), "outcome " + srv.outcomes);
        }
        passed++;
    }

    /** A connection that waited in the queue past its deadline is dropped at once. */
    static void queuedPastDeadlineIsDroppedImmediately() throws Exception {
        try (Server srv = new Server()) {
            Thread t = srv.serveOne(HttpRequestReader.READ_BUDGET_MS + 100);
            try (Socket c = connect(srv.port())) {
                c.getOutputStream().write("POST /p1/ch/rec HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
                t.join(HttpRequestReader.READ_BUDGET_MS + 3000);
            }
            check(srv.outcomes.size() == 1 && srv.outcomes.get(0).startsWith("reject SlowRequest"), "outcome " + srv.outcomes);
            long took = Long.parseLong(srv.outcomes.get(0).replaceAll(".* after ", ""));
            check(took <= HttpRequestReader.READ_BUDGET_MS + 400, "not dropped at once: " + took + " ms");
        }
        passed++;
    }

    static void limitsStillEnforced() throws Exception {
        String[][] cases = {
            {"POST /p1/ch/rec HTTP/1.1\r\nX-Big: " + "a".repeat(HttpRequestReader.MAX_HEADER) + "\r\n\r\n", "reject HeaderTooLarge"},
            {"POST /p1/ch/rec HTTP/1.1\r\nContent-Length: " + (HttpRequestReader.MAX_BODY + 1) + "\r\n\r\n", "reject TooLarge"},
            {"POST /p1/ch/rec HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", "reject Framing"},
            {"POST /p1/ch/rec HTTP/1.1\r\nA: 1\r\na: 2\r\n\r\n", "reject DuplicateHeader"},
        };
        for (String[] c : cases) {
            try (Server srv = new Server()) {
                Thread t = srv.serveOne(0);
                try (Socket s = connect(srv.port())) {
                    s.getOutputStream().write(c[0].getBytes(StandardCharsets.ISO_8859_1));
                    t.join(5000);
                }
                check(srv.outcomes.size() == 1 && srv.outcomes.get(0).startsWith(c[1]), c[1] + " vs " + srv.outcomes);
            }
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        oldSnapshotNeverOverwritesRevocation();
        writesOnlyOnChange();
        failedWriteIsNotDurable();
        deleteResetsDurableState();
        completeRequestIsRead();
        tricklingClientHitsTheBudget();
        slowBodySharesTheBudget();
        queuedPastDeadlineIsDroppedImmediately();
        limitsStillEnforced();
        System.out.println("JVM checks: " + passed + "/9 passed");
    }
}
