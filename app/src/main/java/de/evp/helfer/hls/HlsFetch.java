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
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = r.body(); OutputStream out = new FileOutputStream(dest)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > maxBytes) throw new HlsException("segment-size");
                    out.write(buf, 0, n);
                }
            } catch (NetBlockedException e) {
                // SafeResponse begrenzt den Rumpf selbst (maxBytes + 1): mehr Daten = Segment zu groß
                throw new HlsException(e.reason == NetBlockedException.Reason.BODY_TOO_LARGE ? "segment-size" : "io");
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new HlsException("cancelled");
                throw new HlsException("io");
            }
            if (declared >= 0 && declared != total) throw new HlsException("length-mismatch");
            if (total == 0) throw new HlsException("empty-segment");
            return total;
        }
    }
}
