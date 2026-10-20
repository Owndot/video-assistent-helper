package de.evp.helfer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HLS-Stream (m3u8) herunterladen – ohne Android-Teile, damit es sich auch auf dem Rechner testen lässt.
 *
 * Kann: Haupt-Playlist mit Qualitätsstufen (nimmt die beste), getrennte Tonspur (EXT-X-MEDIA), fMP4 (EXT-X-MAP),
 * AES-128-Verschlüsselung, Byte-Bereiche (EXT-X-BYTERANGE). Lädt mehrere Teile gleichzeitig und setzt sie der Reihe
 * nach zu einer Datei zusammen (TS-Teile → .ts, fMP4-Teile → fragmentierte .mp4).
 * Nicht: Livestreams, SAMPLE-AES/DRM.
 */
public class HlsCore {
    public interface Listener {
        void progress(int done, int total);
        boolean cancelled();
    }

    public static class Result {
        public File video;       // Bild (und meist Ton)
        public File audio;       // nur bei getrennter Tonspur
        public boolean fmp4;     // Teile sind fMP4 (sonst MPEG-TS)
        public String quality = "";
    }

    static class Key { String method = "NONE"; String uri; byte[] iv; }
    static class Seg { String url; long off = -1, len = -1; Key key; long seq; boolean init; }
    static class Media { List<Seg> segs = new ArrayList<>(); Seg init; boolean ended; boolean fmp4; }

    private final String referer, userAgent;
    private final File work;
    private final Map<String, byte[]> keys = new HashMap<>();
    private final int threads;

    public HlsCore(String referer, String userAgent, File workDir, int threads) {
        this.referer = referer;
        this.userAgent = userAgent;
        this.work = workDir;
        this.threads = Math.max(1, threads);
    }

    // ---------- Netz ----------
    byte[] fetch(String url, long off, long len) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (userAgent != null && !userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
                if (referer != null && !referer.isEmpty()) {
                    c.setRequestProperty("Referer", referer);
                    try { URL r = new URL(referer); c.setRequestProperty("Origin", r.getProtocol() + "://" + r.getAuthority()); } catch (Exception ignored) {}
                }
                c.setRequestProperty("Accept", "*/*");
                if (off >= 0 && len > 0) c.setRequestProperty("Range", "bytes=" + off + "-" + (off + len - 1));
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(len > 0 ? (int) Math.min(len, 64 << 20) : 1 << 16);
                    byte[] buf = new byte[64 << 10];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] data = bo.toByteArray();
                    // Server ignoriert den Bereich (200 statt 206)? Dann selbst ausschneiden
                    if (off >= 0 && len > 0 && code == 200 && data.length > len && data.length >= off + len) {
                        byte[] cut = new byte[(int) len];
                        System.arraycopy(data, (int) off, cut, 0, (int) len);
                        return cut;
                    }
                    return data;
                }
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { throw new IOException("abgebrochen"); }
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("Download fehlgeschlagen");
    }

    String text(String url) throws IOException {
        return new String(fetch(url, -1, -1), StandardCharsets.UTF_8);
    }

    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel.trim()).toString(); } catch (Exception e) { return rel.trim(); }
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        int c = line.indexOf(':');
        Matcher x = ATTR.matcher(c >= 0 ? line.substring(c + 1) : line);
        while (x.find()) {
            String v = x.group(2);
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
            m.put(x.group(1), v);
        }
        return m;
    }

    static byte[] hex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        while (s.length() < 32) s = "0" + s;
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // ---------- Playlisten ----------
    static class Variant { String url; long bw; int h; String audioGroup; }

    /** Haupt-Playlist: beste Stufe (+ passende Tonspur) wählen. null = ist schon eine Medien-Playlist. */
    String[] pickVariant(String url, String body, Result r) {
        if (!body.contains("#EXT-X-STREAM-INF")) return null;
        List<Variant> vs = new ArrayList<>();
        Map<String, String> audioByGroup = new HashMap<>();
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                Map<String, String> a = attrs(l);
                Variant v = new Variant();
                try { v.bw = Long.parseLong(a.getOrDefault("BANDWIDTH", "0")); } catch (Exception ignored) {}
                String res = a.get("RESOLUTION");
                if (res != null && res.contains("x")) { try { v.h = Integer.parseInt(res.substring(res.indexOf('x') + 1)); } catch (Exception ignored) {} }
                v.audioGroup = a.get("AUDIO");
                for (int j = i + 1; j < lines.length; j++) {
                    String u = lines[j].trim();
                    if (!u.isEmpty() && !u.startsWith("#")) { v.url = resolve(url, u); break; }
                }
                if (v.url != null) vs.add(v);
            } else if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                Map<String, String> a = attrs(l);
                String g = a.get("GROUP-ID"), u = a.get("URI");
                if (g == null || u == null) continue;
                boolean def = "YES".equalsIgnoreCase(a.get("DEFAULT"));
                if (def || !audioByGroup.containsKey(g)) audioByGroup.put(g, resolve(url, u));
            }
        }
        if (vs.isEmpty()) return null;
        Variant best = vs.get(0);
        for (Variant v : vs) if (v.h > best.h || (v.h == best.h && v.bw > best.bw)) best = v;
        r.quality = best.h > 0 ? best.h + "p" : (best.bw > 0 ? (best.bw / 1000) + " kbit/s" : "");
        String audio = best.audioGroup != null ? audioByGroup.get(best.audioGroup) : null;
        return new String[]{best.url, audio};
    }

    Media parseMedia(String url, String body) throws IOException {
        Media m = new Media();
        Key key = new Key();
        long seq = 0;
        long nextOff = 0;
        long pendLen = -1, pendOff = -1;
        boolean sawInf = false;
        String[] lines = body.split("\\r?\\n");
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(22).trim()); } catch (Exception ignored) {}
            } else if (l.startsWith("#EXT-X-KEY")) {
                Map<String, String> a = attrs(l);
                Key k = new Key();
                k.method = a.getOrDefault("METHOD", "NONE");
                if (a.containsKey("URI")) k.uri = resolve(url, a.get("URI"));
                if (a.containsKey("IV")) k.iv = hex(a.get("IV"));
                if (k.method.startsWith("SAMPLE-AES")) throw new IOException("Dieser Stream ist kopiergeschützt (SAMPLE-AES)");
                key = k;
            } else if (l.startsWith("#EXT-X-MAP")) {
                Map<String, String> a = attrs(l);
                Seg s = new Seg();
                s.url = resolve(url, a.get("URI"));
                s.init = true;
                s.key = key;
                String br = a.get("BYTERANGE");
                if (br != null) {
                    String[] p = br.split("@");
                    s.len = Long.parseLong(p[0].trim());
                    s.off = p.length > 1 ? Long.parseLong(p[1].trim()) : 0;
                }
                m.init = s;
                m.fmp4 = true;
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                String[] p = l.substring(17).trim().split("@");
                pendLen = Long.parseLong(p[0].trim());
                pendOff = p.length > 1 ? Long.parseLong(p[1].trim()) : nextOff;
            } else if (l.startsWith("#EXTINF")) {
                sawInf = true;
            } else if (l.startsWith("#EXT-X-ENDLIST")) {
                m.ended = true;
            } else if (!l.startsWith("#")) {
                if (!sawInf) continue;
                Seg s = new Seg();
                s.url = resolve(url, l);
                s.key = key;
                s.seq = seq++;
                if (pendLen > 0) { s.len = pendLen; s.off = pendOff; nextOff = pendOff + pendLen; }
                pendLen = -1; pendOff = -1;
                sawInf = false;
                m.segs.add(s);
            }
        }
        if (!m.fmp4 && !m.segs.isEmpty()) {
            String p = m.segs.get(0).url.split("[?#]")[0].toLowerCase();
            if (p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".cmfv")) m.fmp4 = true;
        }
        return m;
    }

    // ---------- Laden ----------
    private byte[] decrypt(Seg s, byte[] data) throws Exception {
        if (s.key == null || !"AES-128".equals(s.key.method) || s.key.uri == null) return data;
        byte[] k;
        synchronized (keys) {
            k = keys.get(s.key.uri);
            if (k == null) { k = fetch(s.key.uri, -1, -1); keys.put(s.key.uri, k); }
        }
        byte[] iv = s.key.iv;
        if (iv == null) {
            iv = new byte[16];
            long q = s.seq;
            for (int i = 15; i >= 8; i--) { iv[i] = (byte) (q & 0xff); q >>= 8; }
        }
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, 0, 16, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private void downloadMedia(Media m, File out, String tag, Listener l, AtomicInteger done, int total) throws Exception {
        List<Seg> all = new ArrayList<>();
        if (m.init != null) all.add(m.init);
        all.addAll(m.segs);
        File[] parts = new File[all.size()];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                final int idx = i;
                final Seg s = all.get(i);
                fs.add(pool.submit(() -> {
                    if (l != null && l.cancelled()) throw new IOException("abgebrochen");
                    byte[] d = fetch(s.url, s.off, s.len);
                    d = decrypt(s, d);
                    File f = new File(work, tag + "_" + idx);
                    try (OutputStream o = new FileOutputStream(f)) { o.write(d); }
                    parts[idx] = f;
                    int n = done.incrementAndGet();
                    if (l != null) l.progress(n, total);
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                if (l != null && l.cancelled()) { pool.shutdownNow(); throw new IOException("abgebrochen"); }
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        // der Reihe nach zusammensetzen (init zuerst)
        byte[] buf = new byte[256 << 10];
        try (OutputStream o = new FileOutputStream(out)) {
            for (File p : parts) {
                try (InputStream in = new FileInputStream(p)) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                p.delete();
            }
        }
    }

    public Result run(String url, Listener l) throws Exception {
        if (!work.exists() && !work.mkdirs()) throw new IOException("Kein Arbeitsordner");
        Result r = new Result();
        String body = text(url);
        String videoUrl = url, audioUrl = null;
        String[] pick = pickVariant(url, body, r);
        if (pick != null) {
            videoUrl = pick[0];
            audioUrl = pick[1];
            body = text(videoUrl);
        }
        Media vm = parseMedia(videoUrl, body);
        if (!vm.ended) throw new IOException("Livestreams lassen sich nicht herunterladen");
        if (vm.segs.isEmpty()) throw new IOException("Leere Playlist");
        Media am = null;
        if (audioUrl != null && !audioUrl.equals(videoUrl)) {
            am = parseMedia(audioUrl, text(audioUrl));
            if (am.segs.isEmpty()) am = null;
        }
        int total = vm.segs.size() + (vm.init != null ? 1 : 0) + (am == null ? 0 : am.segs.size() + (am.init != null ? 1 : 0));
        AtomicInteger done = new AtomicInteger();
        r.fmp4 = vm.fmp4;
        r.video = new File(work, "video" + (vm.fmp4 ? ".mp4" : ".ts"));
        downloadMedia(vm, r.video, "v", l, done, total);
        if (am != null) {
            r.audio = new File(work, "audio" + (am.fmp4 ? ".mp4" : ".ts"));
            downloadMedia(am, r.audio, "a", l, done, total);
        }
        return r;
    }
}
