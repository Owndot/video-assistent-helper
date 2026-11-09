package de.evp.helfer.hls;

import de.evp.helfer.net.NetBlockedException;
import de.evp.helfer.net.SafeHttpClient;
import de.evp.helfer.net.SafeResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P4b (sicherer Abruf): jeder Abruf – Master, Medienliste, Audio, Init, Segment – ist ein eigener GET über
 * den bestehenden SafeHttpClient (URL-/Port-, DNS-, Socket-, Peer-, Weiterleitungs-, TLS- und Downgrade-Prüfung je
 * Sprung, keine Cookies/Authorization/Referer, kein Cache, keine Wiederholung). Hier keine eigene Netzwerklogik, kein
 * Retry, kein Ausweichweg. Relative Adressen der Playlist werden gegen {@link Text#finalUrl} aufgelöst.
 */
public final class HlsFetch {
    public static final long MANIFEST_TIMEOUT_MS = 30_000;
    public static final long SEGMENT_TIMEOUT_MS = 10 * 60_000;

    private final SafeHttpClient http;
    private final SafeHttpClient.Cancellation cancel;

    public HlsFetch(SafeHttpClient http, SafeHttpClient.Cancellation cancel) {
        this.http = http;
        this.cancel = cancel;
    }

    /** Playlisttext und tatsächliche finale Adresse (nach geprüften Weiterleitungen). */
    public static final class Text {
        public final byte[] body;
        public final String finalUrl;
        Text(byte[] body, String finalUrl) { this.body = body; this.finalUrl = finalUrl; }
    }

    public Text manifest(String url) throws IOException, HlsException {
        HlsUri.check(url, "manifest");
        try (SafeResponse r = http.get(url, -1, -1, HlsPlaylist.MAX_MANIFEST_BYTES + 1L, MANIFEST_TIMEOUT_MS, cancel)) {
            if (r.code() != 200) throw new HlsException("http_" + r.code());
            if (r.contentEncoding() != null && !"identity".equalsIgnoreCase(r.contentEncoding())) throw new HlsException("encoding");
            if (r.contentLength() > HlsPlaylist.MAX_MANIFEST_BYTES) throw new HlsException("manifest-size");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            try (InputStream in = r.body()) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > HlsPlaylist.MAX_MANIFEST_BYTES) throw new HlsException("manifest-size");
                }
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new HlsException("cancelled");
                throw new HlsException("manifest-size".equals(e.getMessage()) ? "manifest-size" : "io");
            }
            return new Text(out.toByteArray(), r.url());
        }
    }

    /** Lädt eine Init-/Segmentressource vollständig in {@code dest} (Teil-Datei bleibt bei Fehler/Abbruch unvollständig). */
    public long resource(String url, File dest, long maxBytes) throws IOException, HlsException {
        return resourceVerified(url, dest, maxBytes).length;
    }

    /** Ergebnis eines verifizierten Ressourcenabrufs (keine Adresse). */
    public static final class Fetched {
        public final long length;
        public final String sha256;
        /** Starker ETag der Antwort oder null (dann ist diese Ressource später nie ohne Neuladen wiederverwendbar). */
        public final String strongEtag;
        Fetched(long length, String sha256, String strongEtag) { this.length = length; this.sha256 = sha256; this.strongEtag = strongEtag; }
    }

    /**
     * Wie {@link #resource}, zusätzlich: SHA-256 über genau die geschriebenen Bytes, fsync vor der Rückkehr und der
     * starke ETag (falls vorhanden). {@code dest} ist eine Teil-Datei; der Aufrufer benennt sie erst danach um.
     */
    public Fetched resourceVerified(String url, File dest, long maxBytes) throws IOException, HlsException {
        HlsUri.check(url, "resource");
        if (maxBytes <= 0 || maxBytes > HlsPlan.MAX_SEGMENT_BYTES) throw new HlsException("limits");
        SafeResponse resp;
        try {
            resp = http.get(url, -1, -1, maxBytes + 1, SEGMENT_TIMEOUT_MS, cancel);
        } catch (NetBlockedException e) {
            if (e.reason == NetBlockedException.Reason.BODY_TOO_LARGE) throw new HlsException("segment-size");
            throw e;
        }
        try (SafeResponse r = resp) {
            if (r.code() != 200) throw new HlsException("http_" + r.code());
            if (r.contentEncoding() != null && !"identity".equalsIgnoreCase(r.contentEncoding())) throw new HlsException("encoding");
            long declared = r.contentLength();
            if (declared > maxBytes) throw new HlsException("segment-size");
            MessageDigest sha = sha256();
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = r.body(); FileOutputStream out = new FileOutputStream(dest)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > maxBytes) throw new HlsException("segment-size");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                }
                out.getFD().sync();
            } catch (NetBlockedException e) {
                throw new HlsException(e.reason == NetBlockedException.Reason.BODY_TOO_LARGE ? "segment-size" : "io");
            } catch (HlsException e) {
                throw e;
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new HlsException("cancelled");
                throw new HlsException("io");
            }
            if (declared >= 0 && declared != total) throw new HlsException("length-mismatch");
            if (total == 0) throw new HlsException("empty-segment");
            String etag = r.etag();
            return new Fetched(total, HlsPlan.hex(sha.digest()), SafeHttpClient.isStrongEtag(etag) ? etag : null);
        }
    }

    private static final Pattern FIRST_BYTE = Pattern.compile("bytes 0-0/(\\d{1,19})");

    /**
     * Ist die Ressource unverändert dieselbe Darstellung wie beim früheren Abruf? Nur mit starkem ETag: GET mit
     * {@code Range: bytes=0-0} und {@code If-Range: <etag>} (RFC 9110 13.1.5, starker Vergleich beim Server). Nur 206
     * mit exakt {@code bytes 0-0/<length>} und zeichengleichem starkem ETag gilt als unverändert; 200 (Darstellung
     * geändert oder kein Range), jeder andere Status, andere Länge, fehlender/schwacher ETag → false (neu laden).
     */
    public boolean unchanged(String url, long length, String strongEtag) throws IOException, HlsException {
        HlsUri.check(url, "resource");
        if (!SafeHttpClient.isStrongEtag(strongEtag) || length <= 0) return false;
        try (SafeResponse r = http.get(url, 0, 0, strongEtag, 1, MANIFEST_TIMEOUT_MS, cancel)) {
            if (r.code() != 206) return false;
            if (r.contentEncoding() != null && !"identity".equalsIgnoreCase(r.contentEncoding())) return false;
            String cr = r.contentRange();
            Matcher m = cr == null ? null : FIRST_BYTE.matcher(cr.trim());
            if (m == null || !m.matches()) return false;
            if (Long.parseLong(m.group(1)) != length) return false;
            if (r.contentLength() != 1) return false;
            return strongEtag.equals(r.etag());
        } catch (NetBlockedException e) {
            if (e.reason == NetBlockedException.Reason.BODY_TOO_LARGE) return false;
            throw e;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
