package de.evp.helfer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Holt Daten (ganz oder als Byte-Bereich). Direkt übers Netz – oder über den Browser (siehe Proxy). */
public interface Fetcher {
    byte[] fetch(String url, long off, long len) throws IOException;

    /** Gesamtgröße einer Datei (-1 = unbekannt) – nur für Dateien in Stücken nötig */
    long size(String url) throws IOException;

    /** Direkt übers Netz, mit Herkunftsseite (Referer), Browser-Kennung und – falls bekannt – Cookies der Seite */
    class Http implements Fetcher {
        final String referer, userAgent, cookie, cookieBase;

        public Http(String referer, String userAgent, String cookie) {
            this.referer = referer;
            this.userAgent = userAgent;
            this.cookie = cookie;
            String h = "";
            try { h = new URL(referer).getHost(); } catch (Exception ignored) {}
            this.cookieBase = baseOf(h);
        }

        static String baseOf(String host) { return PublicSuffix.site(host); }

        /** Cookies der Seite nur an deren eigene Server – nie an fremde Adressen aus einer Playlist */
        boolean cookieFor(String url) {
            if (cookie == null || cookie.isEmpty() || cookieBase.isEmpty()) return false;
            try {
                return cookieBase.equals(baseOf(new URL(url).getHost()));
            } catch (Exception e) { return false; }
        }

        HttpURLConnection open(String url) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            if (userAgent != null && !userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
            if (referer != null && !referer.isEmpty()) {
                c.setRequestProperty("Referer", referer);
                try { URL r = new URL(referer); c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority()); } catch (Exception ignored) {}
            }
            if (cookieFor(url)) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Accept", "*/*");
            return c;
        }

        /** Follow each hop explicitly so platform redirects cannot carry a manual Cookie header. */
        HttpURLConnection response(String url, long off, long len) throws IOException {
            return response(url, off, len, null, false);
        }

        private HttpURLConnection response(String url, long off, long len, String ifRange, boolean fileTransfer) throws IOException {
            URL current=new URL(url); boolean credentials=true;
            for(int hop=0;hop<=10;hop++){
                String protocol=current.getProtocol();
                if(!"http".equals(protocol)&&!"https".equals(protocol))throw new IOException("Only HTTP(S) redirects are allowed");
                HttpURLConnection c=open(current.toExternalForm());
                if(fileTransfer)c.setRequestProperty("Accept-Encoding","identity");
                if(!credentials)c.setRequestProperty("Cookie","");
                if(off>=0&&len>0)c.setRequestProperty("Range","bytes="+off+"-"+(off+len-1));
                else if(off>=0&&ifRange!=null)c.setRequestProperty("Range","bytes="+off+"-");
                if(ifRange!=null)c.setRequestProperty("If-Range",ifRange);
                int code;
                try { code=c.getResponseCode(); }
                catch(IOException e){c.disconnect();throw e;}
                if(code!=300&&code!=301&&code!=302&&code!=303&&code!=307&&code!=308)return c;
                String location=c.getHeaderField("Location");
                c.disconnect();
                if(location==null||location.isEmpty())throw new IOException("Redirect without Location");
                URL next=new URL(current,location);
                if("https".equals(protocol)&&!"https".equals(next.getProtocol()))credentials=false;
                current=next;
            }
            throw new IOException("Too many redirects");
        }


        /**
         * Credentialed files cannot use DownloadManager: it forwards a manual
         * Cookie across redirects. Stream through the existing hop policy with
         * bounded memory; resumptions require a validator and verified Range.
         */
        java.io.File streamToFile(String url, java.io.File out, HlsCore.Listener listener) throws IOException {
            String validator = null;
            long total = -1, lastProgress = 0;
            IOException failure = null;
            byte[] buffer = new byte[64 << 10];
            for (int attempt = 0; attempt < 4; attempt++) {
                if (Thread.currentThread().isInterrupted() || listener != null && listener.cancelled())
                    throw new IOException("abgebrochen");
                long offset = validator != null && out.isFile() ? out.length() : 0;
                if (total >= 0 && offset == total) return out;
                HttpURLConnection connection = null;
                try {
                    connection = response(url, offset > 0 ? offset : -1, -1, offset > 0 ? validator : null, true);
                    int code = connection.getResponseCode();
                    if (code >= 400) throw new HttpError(code);
                    String encoding = connection.getHeaderField("Content-Encoding");
                    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                        throw new IOException("Unexpected file Content-Encoding");
                    boolean append = code == 206 && offset > 0;
                    long expectedBody = connection.getContentLengthLong();
                    if (code == 206) {
                        String range = connection.getHeaderField("Content-Range");
                        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                            "bytes (\\d+)-(\\d+)/(\\d+)").matcher(range == null ? "" : range);
                        if (!append || !match.matches()) throw new IOException("Invalid resumed Content-Range");
                        long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
                        long length = Long.parseLong(match.group(3));
                        if (start != offset || end < start || end >= length ||
                            expectedBody >= 0 && expectedBody != end - start + 1 ||
                            total >= 0 && length != total) throw new IOException("Mismatched resumed file range");
                        expectedBody = end - start + 1;
                        total = length;
                    } else if (code == 200) {
                        // If-Range changed or Range was ignored: restart, never
                        // append a complete response to an existing prefix.
                        offset = 0;
                        total = expectedBody;
                    } else {
                        throw new IOException("Unexpected file HTTP status " + code);
                    }
                    String tag = connection.getHeaderField("ETag");
                    String modified = connection.getHeaderField("Last-Modified");
                    String nextValidator = tag != null && !tag.startsWith("W/") ? tag : modified;
                    if (append && !java.util.Objects.equals(validator, nextValidator)) {
                        validator = null;
                        throw new IOException("File changed during resume");
                    }
                    validator = nextValidator;
                    long received = 0;
                    try (InputStream in = new BufferedInputStream(connection.getInputStream());
                         java.io.OutputStream file = new java.io.FileOutputStream(out, append)) {
                        while (true) {
                            if (Thread.currentThread().isInterrupted() || listener != null && listener.cancelled())
                                throw new IOException("abgebrochen");
                            int count = in.read(buffer);
                            if (count < 0) break;
                            file.write(buffer, 0, count);
                            received += count;
                            offset += count;
                            long now = System.currentTimeMillis();
                            if (listener != null && now - lastProgress >= 250) {
                                listener.progress(total > 0 ? (int) Math.min(1000, offset * 1000.0 / total) : 0,
                                    total > 0 ? 1000 : 0);
                                lastProgress = now;
                            }
                        }
                    }
                    if (expectedBody >= 0 && received != expectedBody || total >= 0 && offset != total)
                        throw new IOException("Incomplete file body");
                    if (listener != null) listener.progress(1, 1);
                    return out;
                } catch (HttpError e) {
                    if (e.code == 401 || e.code == 403 || e.code == 404 || e.code == 410) throw e;
                    failure = e;
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted() || listener != null && listener.cancelled()) throw e;
                    failure = e;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                if (attempt < 3) try { Thread.sleep(300L * (attempt + 1)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("abgebrochen", e); }
            }
            throw failure != null ? failure : new IOException("File download failed");
        }

        @Override
        public byte[] fetch(String url, long off, long len) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                HttpURLConnection c = null;
                try {
                    c = response(url,off,len);
                    int code = c.getResponseCode();
                    if (code >= 400) {
                        HttpError e = new HttpError(code);
                        if (code == 401 || code == 403 || code == 404 || code == 410) throw e;   // Wiederholen hilft hier nicht
                        throw (IOException) e;
                    }
                    try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                        byte[] data = readAll(in, len);
                        return cut(data, off, len, code);
                    }
                } catch (HttpError e) {
                    if (e.code == 401 || e.code == 403 || e.code == 404 || e.code == 410) throw e;
                    last = e;
                } catch (IOException e) {
                    last = e;
                } finally {
                    if (c != null) c.disconnect();
                }
                try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { throw new IOException("abgebrochen"); }
            }
            throw last != null ? last : new IOException(Tr.t("Download fehlgeschlagen", "Download failed"));
        }

        @Override
        public long size(String url) throws IOException {
            HttpURLConnection c = response(url,0,1);
            try {
                int code = c.getResponseCode();
                if (code >= 400) throw new HttpError(code);
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try { return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); } catch (Exception ignored) {}
                }
                return code == 200 ? c.getContentLengthLong() : -1;
            } finally {
                c.disconnect();
            }
        }
    }

    /** Datei in Stücken zu je 4 MB laden (für den Weg über den Browser) */
    static java.io.File toFile(Fetcher net, String url, java.io.File out, HlsCore.Listener l) throws Exception {
        long size = -1;
        try { size = net.size(url); } catch (HttpError e) { throw e; } catch (Exception ignored) {}
        try (java.io.OutputStream o = new java.io.FileOutputStream(out)) {
            if (size <= 0) {
                o.write(net.fetch(url, -1, -1));
                if (l != null) l.progress(1, 1);
                return out;
            }
            long part = 4L << 20;
            int total = (int) ((size + part - 1) / part);
            for (int i = 0; i < total; i++) {
                if (l != null && l.cancelled()) throw new IOException("abgebrochen");
                long off = i * part, len = Math.min(part, size - off);
                o.write(net.fetch(url, off, len));
                if (l != null) l.progress(i + 1, total);
            }
        }
        return out;
    }

    class HttpError extends IOException {   // (öffentlich: auch für Tests)
        final int code;
        HttpError(int code) { super("HTTP " + code); this.code = code; }
    }

    static byte[] readAll(InputStream in, long hint) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(hint > 0 ? (int) Math.min(hint, 64 << 20) : 1 << 16);
        byte[] buf = new byte[64 << 10];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Server ignoriert den Bereich (200 statt 206)? Dann selbst ausschneiden */
    static byte[] cut(byte[] data, long off, long len, int code) {
        if (off >= 0 && len > 0 && code == 200 && data.length > len && data.length >= off + len) {
            byte[] c = new byte[(int) len];
            System.arraycopy(data, (int) off, c, 0, (int) len);
            return c;
        }
        return data;
    }

    /**
     * Über den Browser: Der Helfer stellt Anfragen in eine Warteschlange, das Userscript holt sie ab (/pull),
     * lädt sie mit der vollen Browser-Sitzung (Cookies, Anmeldung) und liefert die Daten zurück (POST /push).
     * So klappt es auch bei Servern, die nur dem eingeloggten Browser etwas geben.
     */
    class Proxy implements Fetcher {
        public static class Req {
            public final int id; public final String url; public final long off, len; public final boolean sizeOnly;
            volatile byte[] body; volatile int status; volatile long total = -1;
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Req(int id, String url, long off, long len, boolean sizeOnly) { this.id = id; this.url = url; this.off = off; this.len = len; this.sizeOnly = sizeOnly; }
        }
        private final AtomicInteger ids = new AtomicInteger();
        private final LinkedBlockingQueue<Req> waiting = new LinkedBlockingQueue<>();
        private final Map<Integer, Req> open = new HashMap<>();
        volatile long lastPull = System.currentTimeMillis();
        volatile boolean closed = false;

        @Override
        public byte[] fetch(String url, long off, long len) throws IOException {
            Req r = ask(url, off, len, false);
            if (r.status >= 400 || r.body == null) throw new HttpError(r.status > 0 ? r.status : 502);
            return cut(r.body, off, len, r.status);
        }

        @Override
        public long size(String url) throws IOException {
            Req r = ask(url, 0, 1, true);
            if (r.status >= 400) throw new HttpError(r.status);
            return r.total;
        }

        private Req ask(String url, long off, long len, boolean sizeOnly) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                Req r = new Req(ids.incrementAndGet(), url, off, len, sizeOnly);
                synchronized (open) { open.put(r.id, r); }
                waiting.add(r);
                try {
                    // warten, bis das Skript liefert; ist die Seite zu (kein Abholen mehr), aufgeben
                    while (!r.done.await(2, TimeUnit.SECONDS)) {
                        if (closed) throw new IOException("abgebrochen");
                        if (System.currentTimeMillis() - lastPull > 45000) throw new IOException(Tr.t("Seite geschlossen – Download über den Browser braucht die offene Seite", "Page closed – downloading via the browser needs the page to stay open"));
                    }
                } catch (InterruptedException e) {
                    throw new IOException("abgebrochen");
                } finally {
                    synchronized (open) { open.remove(r.id); }
                }
                if (r.status > 0 && r.status < 400 && r.body != null) return r;
                if (r.status == 401 || r.status == 403 || r.status == 404) return r;
                if (sizeOnly && r.status > 0 && r.status < 400) return r;
                last = new HttpError(r.status > 0 ? r.status : 502);
            }
            throw last;
        }

        /** Für das Skript: bis zu max offene Anfragen (wartet kurz, wenn gerade keine da ist) */
        public List<Req> pull(int max, long waitMs) {
            lastPull = System.currentTimeMillis();
            List<Req> out = new ArrayList<>();
            try {
                Req first = waiting.poll(waitMs, TimeUnit.MILLISECONDS);
                if (first != null) out.add(first);
            } catch (InterruptedException ignored) {}
            waiting.drainTo(out, Math.max(0, max - out.size()));
            lastPull = System.currentTimeMillis();
            return out;
        }

        public boolean push(int id, int status, long total, byte[] body) {
            Req r;
            synchronized (open) { r = open.get(id); }
            if (r == null) return false;
            r.status = status;
            r.total = total;
            r.body = body;
            r.done.countDown();
            return true;
        }

        public void close() {
            closed = true;
        }
    }
}
