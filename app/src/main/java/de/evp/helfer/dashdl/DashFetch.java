package de.evp.helfer.dashdl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import de.evp.helfer.dash.DashException;
import de.evp.helfer.dash.DashLimits;
import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsUri;
import de.evp.helfer.net.NetBlockedException;
import de.evp.helfer.net.RequestContext;
import de.evp.helfer.net.SafeHttpClient;
import de.evp.helfer.net.SafeResponse;

/**
 * S3b: Abruf eines MPD über den bestehenden gehärteten {@link SafeHttpClient} – wie {@code HlsFetch.manifest}
 * (geprüfte Weiterleitungen, keine Cookies/Authorization, Accept-Encoding identity, kein Cache, kein Retry), aber mit
 * der MPD-Grenze ({@link DashLimits#MAX_MANIFEST_BYTES}). Init- und Medien-Stücke lädt der Koordinator unverändert über
 * {@code HlsFetch.resourceVerified}/{@code unchanged} (Länge, SHA-256, fsync, starker ETag).
 */
final class DashFetch {
    static final long MANIFEST_TIMEOUT_MS = 30_000;

    private DashFetch() {}

    static final class Text {
        final byte[] body;
        final String finalUrl;
        Text(byte[] body, String finalUrl) { this.body = body; this.finalUrl = finalUrl; }
    }

    static Text manifest(SafeHttpClient http, SafeHttpClient.Cancellation cancel, RequestContext ctx, String url) throws IOException, DashException {
        try { HlsUri.check(url, "manifest"); } catch (HlsException e) { throw new DashException(e.reason); }
        try (SafeResponse r = http.get(url, -1, -1, null, DashLimits.MAX_MANIFEST_BYTES + 1L, MANIFEST_TIMEOUT_MS, cancel, ctx)) {
            if (r.code() != 200) throw new DashException("http_" + r.code());
            if (r.contentEncoding() != null && !"identity".equalsIgnoreCase(r.contentEncoding())) throw new DashException("encoding");
            if (r.contentLength() > DashLimits.MAX_MANIFEST_BYTES) throw new DashException("manifest_size");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            try (InputStream in = r.body()) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > DashLimits.MAX_MANIFEST_BYTES) throw new DashException("manifest_size");
                }
            } catch (NetBlockedException e) {
                throw new DashException(e.reason == NetBlockedException.Reason.BODY_TOO_LARGE ? "manifest_size" : "io");
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new DashException("cancelled");
                throw new DashException("io");
            }
            return new Text(out.toByteArray(), r.url());
        } catch (NetBlockedException e) {
            if (e.reason == NetBlockedException.Reason.BODY_TOO_LARGE) throw new DashException("manifest_size");
            throw e;
        }
    }
}
