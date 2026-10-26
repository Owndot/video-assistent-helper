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

    // Byte-array API is for bounded metadata/windows. Media streams use disk.
    int MAX_ARRAY_BYTES = 4 << 20;

    default java.io.File download(String url, long off, long len, java.io.File out, HlsCore.Listener listener) throws IOException {
        // Preserve existing custom/Browser-Proxy implementations and wire format.
        byte[] data = fetch(url, off, len);
        checkCancelled(listener);
        try (java.io.OutputStream file = new java.io.FileOutputStream(out)) { file.write(data); }
        return out;
    }

    static void checkCancelled(HlsCore.Listener listener) throws IOException {
        if (Thread.currentThread().isInterrupted() || listener != null && listener.cancelled()) throw new IOException("abgebrochen");
    }

    class ResponseError extends IOException {
        ResponseError(String message) { super(message); }
    }

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

        /** Never copy page credentials/referrer from HTTPS into cleartext HTTP. */
        static boolean pageHeadersFor(String referer, String url) {
            try {
                URL page = new URL(referer), target = new URL(url);
                String scheme = page.getProtocol();
                return ("http".equals(scheme) || "https".equals(scheme)) &&
                    (!"https".equals(scheme) || "https".equals(target.getProtocol()));
            } catch (Exception ignored) { return false; }
        }

        /** strict-origin-when-cross-origin; preserve raw same-origin path/query only. */
        static String referrerFor(String referer, String url) {
            if (!pageHeadersFor(referer, url)) return null;
            try {
                URL page = new URL(referer), target = new URL(url);
                if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol())) return null;
                boolean same = page.getProtocol().equalsIgnoreCase(target.getProtocol()) &&
                    page.getHost().equalsIgnoreCase(target.getHost()) &&
                    (page.getPort() < 0 ? page.getDefaultPort() : page.getPort()) ==
                    (target.getPort() < 0 ? target.getDefaultPort() : target.getPort());
                return new URL(page.getProtocol(), page.getHost(), page.getPort(),
                    same ? (page.getFile().isEmpty() ? "/" : page.getFile()) : "/").toExternalForm();
            } catch (Exception ignored) { return null; }
        }

        /** Cookies der Seite nur an deren eigene Server – nie an fremde Adressen aus einer Playlist */
        boolean cookieFor(String url) {
            if (cookie == null || cookie.isEmpty() || cookieBase.isEmpty() || !pageHeadersFor(referer, url)) return false;
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
            String pageReferrer = referrerFor(referer, url);
            if (pageReferrer != null) {
                c.setRequestProperty("Referer", pageReferrer);
                try { URL r = new URL(pageReferrer); c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority()); } catch (Exception ignored) {}
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
                if(!credentials) {
                    c.setRequestProperty("Cookie","");
                    c.setRequestProperty("Referer","");
                    c.setRequestProperty("Origin","");
                }
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

        private static void validateRange(long off, long len) throws ResponseError {
            if (off >= 0 && len > 0 && off > Long.MAX_VALUE - len) throw new ResponseError("Range overflow");
        }

        private static List<String> headerValues(HttpURLConnection c, String name) {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, List<String>> field : c.getHeaderFields().entrySet()) {
                if (name.equalsIgnoreCase(field.getKey())) values.addAll(field.getValue());
            }
            String value = c.getHeaderField(name);
            if (values.isEmpty() && value != null) values.add(value);
            return values;
        }

        /** Validate exposed framing before trusting a length or writing body bytes.
         * HttpURLConnection can decode chunked while still reporting the raw CL.
         */
        private static long responseLength(HttpURLConnection c) throws ResponseError {
            List<String> lengths = headerValues(c, "Content-Length");
            List<String> transfers = headerValues(c, "Transfer-Encoding");
            if (!transfers.isEmpty()) {
                if (!lengths.isEmpty()) throw new ResponseError("Conflicting Transfer-Encoding and Content-Length");
                if (transfers.size() != 1 || !"chunked".equalsIgnoreCase(transfers.get(0).trim()))
                    throw new ResponseError("Unsupported media Transfer-Encoding");
                return -1;
            }
            long body = -1;
            for (String declared : lengths) {
                long value;
                try { value = Long.parseLong(declared.trim()); }
                catch (NumberFormatException e) { throw new ResponseError("Invalid Content-Length"); }
                if (value < 0) throw new ResponseError("Negative Content-Length");
                if (body >= 0 && body != value) throw new ResponseError("Conflicting Content-Length values");
                body = value;
            }
            return body;
        }

        /** Copy one validated representation/window with a fixed 64KiB buffer.
         * A 200 response to Range is sliced while reading, never materialized.
         */
        private void copyResponse(HttpURLConnection c, long off, long len, java.io.OutputStream out,
                                  HlsCore.Listener listener, boolean array) throws IOException {
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code);
            if (code != 200 && code != 206) throw new ResponseError("Unexpected media HTTP status " + code);
            boolean ranged = off >= 0 && len > 0;
            if (ranged && off > Long.MAX_VALUE - len) throw new ResponseError("Range overflow");
            String encoding = c.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new ResponseError("Unexpected media Content-Encoding");
            long body = responseLength(c);
            long expected = body, skip = 0;
            if (code == 206) {
                String raw = c.getHeaderField("Content-Range");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matcher(raw == null ? "" : raw);
                if (!ranged || !m.matches()) throw new ResponseError("Invalid media Content-Range");
                try {
                    long start = Long.parseLong(m.group(1)), end = Long.parseLong(m.group(2));
                    long total = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
                    if (start != off || end < start || end >= off + len || total >= 0 && end >= total)
                        throw new ResponseError("Mismatched media Content-Range");
                    if (end != off + len - 1 && (total < 0 || end != total - 1))
                        throw new ResponseError("Incomplete requested media range");
                    expected = end - start + 1;
                    if (body >= 0 && body != expected) throw new ResponseError("Content-Length/Range mismatch");
                } catch (NumberFormatException e) { throw new ResponseError("Media Content-Range overflow"); }
            } else if (ranged) {
                skip = off;
                if (body >= 0 && off >= body) throw new ResponseError("Range starts past EOF");
                expected = body < 0 ? len : Math.min(len, body - off);
            }
            if (array && (expected > MAX_ARRAY_BYTES || ranged && len > MAX_ARRAY_BYTES))
                throw new ResponseError("Response exceeds byte-array budget; use media download stream");
            byte[] buffer = new byte[64 << 10];
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                while (skip > 0) {
                    checkCancelled(listener);
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, skip));
                    if (n < 0) throw new java.io.EOFException("Incomplete ignored-Range prefix");
                    if (n == 0) continue;
                    skip -= n;
                }
                long received = 0;
                while (expected < 0 || received < expected) {
                    checkCancelled(listener);
                    int want = (int) Math.min(buffer.length, expected < 0 ? buffer.length : expected - received);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) break;
                    if (n == 0) continue;
                    if (array && received + n > MAX_ARRAY_BYTES) throw new ResponseError("Response exceeds byte-array budget");
                    out.write(buffer, 0, n); // Synchronous disk write supplies backpressure.
                    received += n;
                }
                // Unknown-length 200 Range windows may end at EOF before len.
                if (expected >= 0 && received != expected && !(code == 200 && ranged && body < 0))
                    throw new java.io.EOFException("Incomplete media body");
                if (code == 200 && ranged) c.disconnect(); // Stop an ignored-Range body before stream close can drain it.
                // A decoded stream must also agree with a declared full-body length.
                // Intentional windows from an ignored Range stop early instead.
                if (!(code == 200 && ranged) && expected >= 0 && in.read() != -1)
                    throw new ResponseError("Media body exceeds declared length/range");
            }
        }

        @Override
        public byte[] fetch(String url, long off, long len) throws IOException {
            validateRange(off, len);
            if (len > MAX_ARRAY_BYTES) throw new ResponseError("Requested byte array exceeds budget; use media download stream");
            IOException last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                HttpURLConnection c = null;
                try {
                    c = response(url, off, len, null, true);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    copyResponse(c, off, len, out, null, true);
                    return out.toByteArray();
                } catch (ResponseError e) { throw e; }
                catch (HttpError e) {
                    if (e.code == 401 || e.code == 403 || e.code == 404 || e.code == 410) throw e;
                    last = e;
                } catch (IOException e) { last = e; }
                finally { if (c != null) c.disconnect(); }
                if (attempt < 3) try { Thread.sleep(600L * (attempt + 1)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("abgebrochen", e); }
            }
            throw last != null ? last : new IOException("Media request failed");
        }

        @Override
        public java.io.File download(String url, long off, long len, java.io.File out, HlsCore.Listener listener) throws IOException {
            validateRange(off, len);
            IOException last = null;
            boolean complete = false;
            try {
                for (int attempt = 0; attempt < 4; attempt++) {
                    checkCancelled(listener);
                    HttpURLConnection c = null;
                    try {
                        c = response(url, off, len, null, true);
                        try (java.io.OutputStream file = new java.io.FileOutputStream(out)) {
                            copyResponse(c, off, len, file, listener, false);
                        }
                        checkCancelled(listener);
                        complete = true;
                        return out;
                    } catch (ResponseError e) { throw e; }
                    catch (HttpError e) {
                        if (e.code == 401 || e.code == 403 || e.code == 404 || e.code == 410) throw e;
                        last = e;
                    } catch (IOException e) { checkCancelled(listener); last = e; }
                    finally { if (c != null) c.disconnect(); }
                    if (attempt < 3) try { Thread.sleep(600L * (attempt + 1)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("abgebrochen", e); }
                }
                throw last != null ? last : new IOException("Media download failed");
            } finally { if (!complete && out.exists()) out.delete(); }
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
        if (size <= 0) {
            java.io.File result = net.download(url, -1, -1, out, l);
            if (l != null) l.progress(1, 1);
            return result;
        }
        try (java.io.OutputStream o = new java.io.FileOutputStream(out)) {
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
        long limit = hint > 0 ? Math.min(hint, MAX_ARRAY_BYTES) : MAX_ARRAY_BYTES;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[64 << 10];
        int n;
        while ((n = in.read(buf)) != -1) {
            checkCancelled(null);
            if ((long) bo.size() + n > limit) throw new ResponseError("Response exceeds byte-array budget");
            bo.write(buf, 0, n);
        }
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
            volatile java.io.File file; volatile int status; volatile long total = -1;
            volatile boolean cancelled; boolean receiving;
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Req(int id, String url, long off, long len, boolean sizeOnly) { this.id=id; this.url=url; this.off=off; this.len=len; this.sizeOnly=sizeOnly; }
        }
        private final AtomicInteger ids = new AtomicInteger();
        private final LinkedBlockingQueue<Req> waiting = new LinkedBlockingQueue<>();
        private final Map<Integer, Req> open = new HashMap<>();
        private final java.io.File spool;
        private final long timeoutMs;
        volatile long lastPull = System.currentTimeMillis();
        volatile boolean closed;

        public Proxy() { this(new java.io.File(System.getProperty("java.io.tmpdir"), "evp-proxy-"+System.nanoTime())); }
        public Proxy(java.io.File spool) { this(spool, 120000); }
        Proxy(java.io.File spool, long timeoutMs) { this.spool=spool; this.timeoutMs=timeoutMs; }
        private static void discard(Req r) { if (r.file != null) r.file.delete(); }
        private void active(Req r, HlsCore.Listener l) throws IOException {
            checkCancelled(l);
            if (closed || r.cancelled) throw new IOException("abgebrochen");
        }
        private long start(Req r) throws IOException {
            if (r.status != 200 && r.status != 206 || r.file == null) throw new HttpError(r.status > 0 ? r.status : 502);
            if (r.off >= 0 && r.len > 0 && r.status == 200) {
                if (r.off >= r.file.length()) throw new ResponseError("Range starts past EOF");
                return r.off;
            }
            return 0;
        }
        private long length(Req r, long start) {
            long available=r.file.length()-start;
            return r.off>=0 && r.len>0 ? Math.min(r.len,available) : available;
        }
        @Override public byte[] fetch(String url, long off, long len) throws IOException {
            if (len > MAX_ARRAY_BYTES) throw new ResponseError("Proxy byte array exceeds budget; use media download stream");
            Req r=ask(url,off,len,false,null);
            try {
                long start=start(r), count=length(r,start);
                if(count>MAX_ARRAY_BYTES) throw new ResponseError("Proxy response exceeds byte-array budget");
                byte[] data=new byte[(int)count];
                try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(r.file,"r")) { in.seek(start); in.readFully(data); }
                active(r,null);
                return data;
            } finally { discard(r); }
        }
        @Override public java.io.File download(String url,long off,long len,java.io.File out,HlsCore.Listener l) throws IOException {
            Req r=null; boolean complete=false;
            try {
                r=ask(url,off,len,false,l);
                active(r,l);
                long start=start(r), count=length(r,start);
                if(start==0 && count==r.file.length() && r.file.renameTo(out)) {
                    active(r,l); complete=true; return out;
                }
                try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(r.file,"r"); java.io.OutputStream file=new java.io.FileOutputStream(out)) {
                    in.seek(start); byte[] buffer=new byte[64<<10];
                    while(count>0) {
                        active(r,l); int n=in.read(buffer,0,(int)Math.min(count,buffer.length));
                        if(n<0) throw new java.io.EOFException("Incomplete proxy spool");
                        file.write(buffer,0,n); count-=n;
                    }
                }
                active(r,l); complete=true; return out;
            } finally { if(r!=null)discard(r); if(!complete) out.delete(); }
        }
        @Override public long size(String url) throws IOException {
            Req r=ask(url,0,1,true,null);
            try { if(r.status>=400) throw new HttpError(r.status); return r.total; }
            finally { discard(r); }
        }
        private Req ask(String url,long off,long len,boolean sizeOnly,HlsCore.Listener l) throws IOException {
            if(off>=0 && len>0 && off>Long.MAX_VALUE-len) throw new ResponseError("Range overflow");
            IOException last=null;
            for(int attempt=0;attempt<3;attempt++) {
                checkCancelled(l);
                Req r=new Req(ids.incrementAndGet(),url,off,len,sizeOnly); boolean delivered=false;
                synchronized(open) { if(closed)throw new IOException("abgebrochen"); open.put(r.id,r); waiting.add(r); }
                long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                try {
                    while(true) {
                        active(r,l);
                        long left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                        if(left<=0)throw new java.net.SocketTimeoutException("Proxy response timeout");
                        if(r.done.await(Math.min(2000,left),TimeUnit.MILLISECONDS))break;
                        if(System.currentTimeMillis()-lastPull>45000)throw new IOException("Page closed – downloading via the browser needs the page to stay open");
                    }
                    active(r,l);
                    if(r.status==200 || r.status==206 || r.status>=400 && r.status<500) { delivered=true; return r; }
                    last=new HttpError(r.status>0?r.status:502);
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("abgebrochen",e); }
                finally {
                    synchronized(open) { open.remove(r.id); r.cancelled=!delivered; }
                    waiting.remove(r);
                    if(!delivered)discard(r);
                }
            }
            throw last;
        }
        public List<Req> pull(int max,long waitMs) {
            lastPull=System.currentTimeMillis(); List<Req> result=new ArrayList<>();
            try {
                Req r=waiting.poll(waitMs,TimeUnit.MILLISECONDS);
                while(r!=null) {
                    synchronized(open) { if(open.get(r.id)==r && !r.cancelled)result.add(r); }
                    if(result.size()>=max)break;
                    r=waiting.poll();
                }
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            lastPull=System.currentTimeMillis(); return result;
        }
        /** Legacy Java callers; production HTTP ingress uses pushStream without a body array. */
        public boolean push(int id,int status,long total,byte[] body) {
            if(body==null && status<400)status=502;
            try { return pushStream(id,status,total,new java.io.ByteArrayInputStream(body==null?new byte[0]:body),body==null?0:body.length); }
            catch(IOException e) { return false; }
        }
        public boolean pushStream(int id,int status,long total,InputStream in,long bytes) throws IOException {
            Req r;
            synchronized(open) {
                r=open.get(id);
                if(r==null || closed || r.cancelled || r.receiving || r.done.getCount()==0)return false;
                r.receiving=true;
            }
            java.io.File file=null;
            try {
                active(r,null);
                if(bytes<0)throw new ResponseError("Invalid proxy body length");
                if(status!=200 && status!=206 && status<400)throw new ResponseError("Unexpected proxy status");
                if(!r.sizeOnly && status<400) {
                    if(!spool.isDirectory() && !spool.mkdirs())throw new IOException("No proxy spool directory");
                    file=java.io.File.createTempFile("proxy-",".part",spool);
                    try(java.io.OutputStream out=new java.io.FileOutputStream(file)) {
                        byte[] buffer=new byte[64<<10]; long remaining=bytes;
                        while(remaining>0) {
                            active(r,null);
                            int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));
                            if(n<0)throw new java.io.EOFException("Incomplete proxy upload");
                            out.write(buffer,0,n); remaining-=n;
                        }
                    }
                    if(status==206 && (r.off<0 || r.len<=0 || bytes>r.len || bytes<r.len && (total<0 || r.off+bytes!=total)))
                        throw new ResponseError("Mismatched proxy range length");
                    if(status==200 && total>=0 && bytes!=total)throw new ResponseError("Mismatched proxy total length");
                }
                synchronized(open) {
                    if(open.get(id)!=r || closed || r.cancelled)return false;
                    r.file=file; file=null; r.status=status; r.total=total; r.done.countDown();
                }
                return true;
            } catch(IOException e) {
                synchronized(open) {
                    if(open.get(id)==r && !r.cancelled) { r.status=e instanceof ResponseError?422:502; r.done.countDown(); }
                }
                throw e;
            } finally {
                if(file!=null)file.delete();
                synchronized(open) { r.receiving=false; }
            }
        }
        public void close() {
            synchronized(open) {
                closed=true;
                for(Req r:open.values()) { r.cancelled=true; discard(r); r.done.countDown(); }
                open.clear(); waiting.clear();
            }
        }
    }
}
