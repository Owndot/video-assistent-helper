package de.evp.helfer.hls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * P4a: Auswahl und Downloadplan. Der Helfer hält Playlists und Adressen; nach außen (Kanal → Kadri) gehen nur Optionen
 * mit undurchsichtiger ID, Höhe, Bandbreite, Tonsprache/-name (bereinigt) und Dauer. Ein Plan bindet genau eine Option
 * an die tatsächlich geladenen Medienlisten (Fingerabdruck über alle Adressen/Dauern/Init) und prüft die Budgets.
 */
public final class HlsPlan {
    public static final int MAX_PLAN_SEGMENTS = 20_000;            // inkl. Audio
    public static final long MAX_SEGMENT_BYTES = 128L << 20;       // je Segment/Init
    public static final long MAX_MEDIA_BYTES = 16L << 30;          // je Job (Mediendaten)
    public static final int MAX_OPTIONS = 64;

    private HlsPlan() {}

    /** Eine auswählbare Kombination aus Video-Variante und Tonspur. audio == null: Ton steckt in der Variante. */
    public static final class Option {
        public final String id;
        public final HlsPlaylist.Variant variant;
        public final HlsPlaylist.Rendition audio;
        public final String codecClass;
        Option(String id, HlsPlaylist.Variant variant, HlsPlaylist.Rendition audio, String codecClass) {
            this.id = id; this.variant = variant; this.audio = audio; this.codecClass = codecClass;
        }
        /** Was über den Kanal darf: keine Adresse, keine Gruppen-IDs, Texte begrenzt und bereinigt. */
        public String describe() {
            return "{\"id\":\"" + id + "\",\"height\":" + variant.height + ",\"width\":" + variant.width + ",\"bandwidth\":" + variant.bandwidth
                + ",\"audio\":" + (audio == null ? "\"muxed\"" : "{\"lang\":\"" + clean(audio.language, 16) + "\",\"name\":\"" + clean(audio.name, 32) + "\"}")
                + ",\"codecs\":\"" + codecClass + "\"}";
        }
    }

    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') b.append(c);
        }
        return b.toString();
    }

    /**
     * Optionen einer Masterliste. Varianten mit unzulässigen Codecs fallen weg (Grund bleibt intern). Eine Variante mit
     * AUDIO-Gruppe erzeugt je Tonspur mit eigener Adresse eine Option; Tonspuren ohne URI bedeuten "Ton in der Variante".
     * {@code salt} ist pro Plan-Generation zufällig: IDs sind nicht über Generationen hinweg wiederverwendbar.
     */
    public static List<Option> options(HlsPlaylist.Master m, byte[] salt, boolean opusAllowed) throws HlsException {
        List<Option> out = new ArrayList<>();
        List<HlsPlaylist.Variant> vs = new ArrayList<>(m.variants);
        vs.sort(Comparator.comparingInt((HlsPlaylist.Variant v) -> -v.height).thenComparingInt(v -> -v.bandwidth));
        for (HlsPlaylist.Variant v : vs) {
            String cc = HlsPlaylist.codecClass(v.codecs, opusAllowed);
            if (!"ok".equals(cc) && !"unknown".equals(cc)) continue;
            if (v.audioGroup == null) { add(out, salt, v, null, cc); continue; }
            boolean any = false;
            for (HlsPlaylist.Rendition r : m.audio) {
                if (!r.groupId.equals(v.audioGroup)) continue;
                add(out, salt, v, r.uri == null ? null : r, cc);
                any = true;
            }
            if (!any) throw new HlsException("audio-group");
        }
        if (out.isEmpty()) throw new HlsException("no-supported-variant");
        return Collections.unmodifiableList(out);
    }

    private static void add(List<Option> out, byte[] salt, HlsPlaylist.Variant v, HlsPlaylist.Rendition r, String cc) throws HlsException {
        for (Option o : out) if (o.variant == v && o.audio == r) return;   // gleiche Kombination nur einmal
        if (out.size() >= MAX_OPTIONS) throw new HlsException("options-limit");
        out.add(new Option(hex(sha256(salt, ("opt:" + v.index + ":" + (r == null ? -1 : r.index)).getBytes(StandardCharsets.US_ASCII))).substring(0, 24), v, r, cc));
    }

    /** Fertiger Plan: Ressourcen in Abrufreihenfolge (Init zuerst), Budgets geprüft, Fingerabdruck. */
    public static final class Plan {
        public final List<String> videoResources, audioResources;   // jeweils [Init] + Segmente
        public final double videoSeconds, audioSeconds;
        public final long estimatedBytes;
        public final String fingerprint;
        public final boolean separateAudio;
        Plan(List<String> v, List<String> a, double vs, double as, long est, String fp) {
            videoResources = Collections.unmodifiableList(v); audioResources = Collections.unmodifiableList(a);
            videoSeconds = vs; audioSeconds = as; estimatedBytes = est; fingerprint = fp; separateAudio = !a.isEmpty();
        }
        public int resourceCount() { return videoResources.size() + audioResources.size(); }
    }

    /**
     * @param bandwidth Bits/s der Variante (0 = unbekannt; dann nur die Segmentzahl-/Einzelgrenzen beim Laden)
     * @param audio null, wenn der Ton in den Videosegmenten steckt
     */
    public static Plan build(HlsPlaylist.Media video, HlsPlaylist.Media audio, int bandwidth) throws HlsException {
        int n = video.segments.size() + (audio == null ? 0 : audio.segments.size());
        if (n > MAX_PLAN_SEGMENTS) throw new HlsException("segments-limit");
        if (audio != null) {
            double tol = Math.max(1.0, 2.0 * Math.max(video.targetDuration, audio.targetDuration));
            if (Math.abs(video.totalSeconds - audio.totalSeconds) > tol) throw new HlsException("av-duration-mismatch");
            if ((video.mapUri == null) != (audio.mapUri == null)) throw new HlsException("container-mismatch");
        }
        long est = bandwidth > 0 ? (long) Math.ceil(bandwidth / 8.0 * Math.max(video.totalSeconds, audio == null ? 0 : audio.totalSeconds) * 1.25) : 0;
        if (est > MAX_MEDIA_BYTES) throw new HlsException("size-limit");
        List<String> v = resources(video), a = audio == null ? new ArrayList<>() : resources(audio);
        StringBuilder fp = new StringBuilder("hls-plan-v1\n");
        canon(fp, "V", video);
        if (audio != null) canon(fp, "A", audio);
        return new Plan(v, a, video.totalSeconds, audio == null ? 0 : audio.totalSeconds, est,
            hex(sha256(new byte[0], fp.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private static List<String> resources(HlsPlaylist.Media m) {
        List<String> r = new ArrayList<>();
        if (m.mapUri != null) r.add(m.mapUri);
        for (HlsPlaylist.Segment s : m.segments) r.add(s.uri);
        return r;
    }

    private static void canon(StringBuilder b, String k, HlsPlaylist.Media m) {
        b.append(k).append(" target=").append(m.targetDuration).append(" map=").append(m.mapUri == null ? "-" : m.mapUri).append('\n');
        for (HlsPlaylist.Segment s : m.segments) b.append(String.format(Locale.ROOT, "%.6f", s.duration)).append(' ').append(s.uri).append('\n');
    }

    static byte[] sha256(byte[] salt, byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(salt);
            d.update(data);
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    /**
     * Grobe Einordnung einer erkannten Medienadresse/-antwort ohne XML-Parser (DASH wird nur erkannt, nicht geladen):
     * "hls", "dash" oder "unknown". Nur die ersten Bytes und der Content-Type zählen.
     */
    public static String classify(String contentType, byte[] head) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        String h = head == null ? "" : new String(head, 0, Math.min(head.length, 512), StandardCharsets.ISO_8859_1);
        if (h.startsWith("#EXTM3U")) return "hls";
        if (ct.equals("application/dash+xml")) return "dash";
        String t = h.replaceFirst("^\\xEF\\xBB\\xBF", "").trim();
        if (t.startsWith("<?xml") || t.startsWith("<MPD") || t.startsWith("<!--")) return t.contains("<MPD") ? "dash" : "unknown";
        if (ct.equals("application/vnd.apple.mpegurl") || ct.equals("application/x-mpegurl") || ct.equals("audio/mpegurl")) return "unknown";
        return "unknown";
    }
}
