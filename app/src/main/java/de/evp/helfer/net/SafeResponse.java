package de.evp.helfer.net;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Response;

/**
 * Antwort des {@link SafeHttpClient}: Status, wenige Metadaten-Header und ein einmal lesbarer, größenbegrenzter
 * Rumpf-Stream (kein Puffern der ganzen Antwort). Muss geschlossen werden.
 */
public final class SafeResponse implements Closeable {
    private final Response response;
    private final String url;
    private final long maxBodyBytes;
    private InputStream body;

    SafeResponse(Response response, String url, long maxBodyBytes) throws NetBlockedException {
        this.response = response;
        this.url = url;
        this.maxBodyBytes = maxBodyBytes;
        long declared = response.body().contentLength();
        if (declared > maxBodyBytes) {
            response.close();
            throw new NetBlockedException(NetBlockedException.Reason.BODY_TOO_LARGE, declared + " > " + maxBodyBytes);
        }
    }

    public int code() { return response.code(); }

    /** Endgültige URL nach geprüften Weiterleitungen. */
    public String url() { return url; }

    /** Länge laut Server, -1 wenn unbekannt (z. B. chunked). */
    public long contentLength() { return response.body().contentLength(); }

    public String contentType() { return response.header("Content-Type"); }
    public String contentRange() { return response.header("Content-Range"); }
    public String acceptRanges() { return response.header("Accept-Ranges"); }
    public String etag() { return response.header("ETag"); }
    public String lastModified() { return response.header("Last-Modified"); }
    public String contentEncoding() { return response.header("Content-Encoding"); }

    /** Rumpf als Stream; bricht mit {@link NetBlockedException} (BODY_TOO_LARGE) ab, sobald die Grenze überschritten wird. */
    public synchronized InputStream body() {
        if (body == null) body = new Bounded(response.body().byteStream(), maxBodyBytes);
        return body;
    }

    @Override public void close() { response.close(); }

    static final class Bounded extends FilterInputStream {
        private final long max;
        private long count;

        Bounded(InputStream in, long max) { super(in); this.max = max; }

        private void add(long n) throws NetBlockedException {
            if (n > 0) count += n;
            if (count > max) throw new NetBlockedException(NetBlockedException.Reason.BODY_TOO_LARGE, "more than " + max);
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, (int) Math.min(len, max - count + 1));
            add(n);
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long k = super.skip(Math.min(n, max - count + 1));
            add(k);
            return k;
        }

        @Override public boolean markSupported() { return false; }
    }
}
