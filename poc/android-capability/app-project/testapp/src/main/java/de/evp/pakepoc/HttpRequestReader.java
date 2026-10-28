package de.evp.pakepoc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded HTTP/1.1 request reader for the loopback test server.
 *
 * Besides the size limits, a request must arrive completely within
 * {@link #READ_BUDGET_MS} measured on a monotonic clock from the moment the
 * connection was accepted (review F3). Socket timeouts alone only bound the
 * gap between two bytes, so a client sending one byte every few seconds could
 * hold a worker thread indefinitely; with the budget, header and body share
 * one deadline, and a connection that waited in the queue past its deadline
 * is dropped as soon as a worker picks it up.
 *
 * DoS hardening only; authentication is the Noise channel's job.
 * Plain Java (no Android types) so it is also tested on the JVM.
 */
final class HttpRequestReader {
    static final int MAX_BODY = 65535 + 21;
    static final int MAX_HEADER = 8 * 1024;
    static final long READ_BUDGET_MS = 3_000;
    static final int PER_READ_TIMEOUT_MS = 10_000;

    interface Clock { long now(); }   // monotonic milliseconds

    static final class Req {
        String method, path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private HttpRequestReader() {}

    /** Reads one request; throws SecurityException on any limit or framing violation. */
    static Req read(Socket s, long acceptedAt, Clock clock) throws Exception {
        InputStream in = new BufferedInputStream(new DeadlineInput(s, acceptedAt + READ_BUDGET_MS, clock), 8192);
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

    /** Every read waits at most until the shared deadline. */
    private static final class DeadlineInput extends InputStream {
        private final Socket s;
        private final long deadline;
        private final Clock clock;

        DeadlineInput(Socket s, long deadline, Clock clock) {
            this.s = s;
            this.deadline = deadline;
            this.clock = clock;
        }

        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            long left = deadline - clock.now();
            if (left <= 0) throw new SecurityException("SlowRequest");
            s.setSoTimeout((int) Math.min(left, PER_READ_TIMEOUT_MS));
            try {
                return s.getInputStream().read(b, off, len);
            } catch (SocketTimeoutException e) {
                throw new SecurityException("SlowRequest");
            }
        }

        @Override public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }
    }
}
