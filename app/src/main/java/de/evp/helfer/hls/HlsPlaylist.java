package de.evp.helfer.hls;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P4a: strenger, begrenzter HLS-Playlistparser (RFC 8216) für das erste Profil "unverschlüsseltes, abgeschlossenes
 * VOD". Er ist das Sicherheitstor: Was er nicht ausdrücklich kennt, wird mit neutralem Grund abgelehnt (fail-closed),
 * statt still ignoriert zu werden – Verschlüsselung (KEY/SESSION-KEY außer METHOD=NONE), Live/Event ohne ENDLIST,
 * LL-HLS (PART, PRELOAD-HINT, SERVER-CONTROL, SKIP, RENDITION-REPORT), Discontinuities, Byte-Ranges, Lücken,
 * Interstitials/DATERANGE, Variablen (DEFINE), Content-Steering, I-Frame-only-Listen, Init-Wechsel, unbekannte Tags.
 * Alle Adressen werden gegen die finale Antwortadresse aufgelöst (HlsUri). Der Parser lädt nichts selbst.
 */
public final class HlsPlaylist {
    /** Grenzen aus dem C3/P4-Auftrag; nur nach Messung zu ändern. */
    public static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    public static final int MAX_LINE_CHARS = 16 * 1024;
    public static final int MAX_VARIANTS = 64;
    public static final int MAX_RENDITIONS = 64;
    public static final int MAX_SEGMENTS = 20_000;   // je Medienliste; der Plan prüft die Summe inkl. Audio
    public static final double MAX_SEGMENT_SECONDS = 3600;
    public static final double MAX_TOTAL_SECONDS = 48 * 3600;

    private HlsPlaylist() {}

    public static final class Variant {
        public final int index, bandwidth, averageBandwidth, width, height;
        public final String codecs, audioGroup, uri;
        Variant(int index, int bandwidth, int averageBandwidth, int width, int height, String codecs, String audioGroup, String uri) {
            this.index = index; this.bandwidth = bandwidth; this.averageBandwidth = averageBandwidth; this.width = width;
            this.height = height; this.codecs = codecs; this.audioGroup = audioGroup; this.uri = uri;
        }
    }

    public static final class Rendition {
        public final int index;
        public final String groupId, name, language, uri, channels;
        public final boolean isDefault, autoselect;
        Rendition(int index, String groupId, String name, String language, String uri, String channels, boolean isDefault, boolean autoselect) {
            this.index = index; this.groupId = groupId; this.name = name; this.language = language; this.uri = uri;
            this.channels = channels; this.isDefault = isDefault; this.autoselect = autoselect;
        }
    }

    public static final class Segment {
        public final String uri;
        public final double duration;
        Segment(String uri, double duration) { this.uri = uri; this.duration = duration; }
    }

    public static final class Master {
        public final List<Variant> variants;
        public final List<Rendition> audio;
        Master(List<Variant> v, List<Rendition> a) { variants = Collections.unmodifiableList(v); audio = Collections.unmodifiableList(a); }
    }

    public static final class Media {
        public final int targetDuration;
        public final String mapUri;   // null = keine Init-Ressource (TS)
        public final List<Segment> segments;
        public final double totalSeconds;
        Media(int targetDuration, String mapUri, List<Segment> segments) {
            this.targetDuration = targetDuration; this.mapUri = mapUri; this.segments = Collections.unmodifiableList(segments);
            double t = 0; for (Segment s : segments) t += s.duration; this.totalSeconds = t;
        }
    }

    /** Ergebnis: genau eines von master/media ist gesetzt. */
    public static final class Parsed {
        public final Master master;
        public final Media media;
        Parsed(Master master, Media media) { this.master = master; this.media = media; }
    }

    private static final Set<String> IGNORED_COMMON = set("EXT-X-VERSION", "EXT-X-INDEPENDENT-SEGMENTS", "EXT-X-START");
    private static final Set<String> MASTER_TAGS = set("EXT-X-STREAM-INF", "EXT-X-MEDIA", "EXT-X-I-FRAME-STREAM-INF", "EXT-X-SESSION-DATA");
    private static final Set<String> MEDIA_TAGS = set("EXT-X-TARGETDURATION", "EXT-X-MEDIA-SEQUENCE", "EXT-X-DISCONTINUITY-SEQUENCE",
        "EXT-X-ENDLIST", "EXT-X-PLAYLIST-TYPE", "EXTINF", "EXT-X-KEY", "EXT-X-MAP", "EXT-X-PROGRAM-DATE-TIME", "EXT-X-BITRATE",
        "EXT-X-ALLOW-CACHE");
    /** Bekannte, im ersten Profil ausdrücklich abgelehnte Tags → Grund. */
    private static final Map<String, String> REJECTED = new LinkedHashMap<>();
    static {
        REJECTED.put("EXT-X-SESSION-KEY", "encrypted");
        REJECTED.put("EXT-X-CONTENT-STEERING", "content-steering");
        REJECTED.put("EXT-X-DEFINE", "variables");
        REJECTED.put("EXT-X-DISCONTINUITY", "discontinuity");
        REJECTED.put("EXT-X-BYTERANGE", "byterange");
        REJECTED.put("EXT-X-GAP", "gap");
        REJECTED.put("EXT-X-DATERANGE", "daterange");
        REJECTED.put("EXT-X-I-FRAMES-ONLY", "iframes-only");
        for (String t : new String[] {"EXT-X-PART", "EXT-X-PART-INF", "EXT-X-PRELOAD-HINT", "EXT-X-SERVER-CONTROL", "EXT-X-SKIP",
                "EXT-X-RENDITION-REPORT"}) REJECTED.put(t, "low-latency");
    }

    private static Set<String> set(String... s) { return new HashSet<>(java.util.Arrays.asList(s)); }

    /** Text strikt als UTF-8 ohne BOM, ohne NUL; Größe und Zeilenlänge begrenzt. */
    static List<String> lines(byte[] body) throws HlsException {
        if (body == null || body.length == 0) throw new HlsException("empty");
        if (body.length > MAX_MANIFEST_BYTES) throw new HlsException("manifest-size");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new HlsException("encoding");
        }
        if (text.indexOf('\u0000') >= 0 || text.startsWith("﻿")) throw new HlsException("encoding");
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String l = text.substring(start, i);
                if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
                if (l.length() > MAX_LINE_CHARS) throw new HlsException("line-length");
                if (l.indexOf('\r') >= 0) throw new HlsException("encoding");
                out.add(l.trim());
                start = i + 1;
            }
        }
        if (out.isEmpty() || !"#EXTM3U".equals(out.get(0))) throw new HlsException("not-m3u8");
        return out;
    }

    /** Attributliste (RFC 8216 4.2): NAME=WERT, Komma getrennt, Strings in Anführungszeichen; Duplikate abgelehnt. */
    static Map<String, String> attributes(String s) throws HlsException {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            int eq = s.indexOf('=', i);
            if (eq <= i) throw new HlsException("attributes");
            String name = s.substring(i, eq);
            if (!name.matches("[A-Z0-9-]+")) throw new HlsException("attributes");
            int j = eq + 1;
            String value;
            if (j < n && s.charAt(j) == '"') {
                int close = s.indexOf('"', j + 1);
                if (close < 0) throw new HlsException("attributes");
                value = s.substring(j, close + 1);
                j = close + 1;
            } else {
                int comma = s.indexOf(',', j);
                if (comma < 0) comma = n;
                value = s.substring(j, comma);
                if (value.isEmpty() || value.indexOf('"') >= 0) throw new HlsException("attributes");
                j = comma;
            }
            if (m.put(name, value) != null) throw new HlsException("attributes-duplicate");
            if (j < n) {
                if (s.charAt(j) != ',') throw new HlsException("attributes");
                j++;
                if (j == n) throw new HlsException("attributes");
            }
            i = j;
        }
        return m;
    }

    static String quoted(Map<String, String> a, String k) throws HlsException {
        String v = a.get(k);
        if (v == null) return null;
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') throw new HlsException("attributes");
        return v.substring(1, v.length() - 1);
    }

    static String enumerated(Map<String, String> a, String k) throws HlsException {
        String v = a.get(k);
        if (v != null && v.startsWith("\"")) throw new HlsException("attributes");
        return v;
    }

    static int decimal(String v, String reason) throws HlsException {
        if (v == null || !v.matches("[0-9]{1,10}")) throw new HlsException(reason);
        long x = Long.parseLong(v);
        if (x > Integer.MAX_VALUE) throw new HlsException(reason);
        return (int) x;
    }

    public static Parsed parse(byte[] body, String finalUrl) throws HlsException {
        HlsUri.check(finalUrl, "final");
        List<String> ls = lines(body);
        boolean master = false, media = false;
        for (String l : ls) {
            String t = tagName(l);
            if (t == null) continue;
            if (MASTER_TAGS.contains(t)) master = true;
            if (MEDIA_TAGS.contains(t)) media = true;
        }
        if (master && media) throw new HlsException("mixed-playlist");
        return master ? new Parsed(parseMaster(ls, finalUrl), null) : new Parsed(null, parseMedia(ls, finalUrl));
    }

    /** Tagname einer Zeile ("#EXT-X-FOO:..." → "EXT-X-FOO"); null für Kommentare/URIs/Leerzeilen. */
    static String tagName(String l) {
        if (!l.startsWith("#EXT")) return null;
        int c = l.indexOf(':');
        return c < 0 ? l.substring(1) : l.substring(1, c);
    }

    private static String value(String l) {
        int c = l.indexOf(':');
        return c < 0 ? "" : l.substring(c + 1);
    }

    private static void common(String tag) throws HlsException {
        String r = REJECTED.get(tag);
        if (r != null) throw new HlsException(r);
    }

    static Master parseMaster(List<String> ls, String base) throws HlsException {
        List<Variant> variants = new ArrayList<>();
        List<Rendition> audio = new ArrayList<>();
        Set<String> audioNames = new HashSet<>();
        Map<String, String> pending = null;
        for (int i = 1; i < ls.size(); i++) {
            String l = ls.get(i);
            if (l.isEmpty()) continue;
            if (!l.startsWith("#")) {
                if (pending == null) throw new HlsException("uri-without-tag");
                if (variants.size() >= MAX_VARIANTS) throw new HlsException("variants-limit");
                Map<String, String> a = pending;
                pending = null;
                int bw = decimal(enumerated(a, "BANDWIDTH"), "bandwidth");
                int avg = a.containsKey("AVERAGE-BANDWIDTH") ? decimal(enumerated(a, "AVERAGE-BANDWIDTH"), "bandwidth") : 0;
                int w = 0, h = 0;
                String res = enumerated(a, "RESOLUTION");
                if (res != null) {
                    if (!res.matches("[0-9]{1,5}x[0-9]{1,5}")) throw new HlsException("resolution");
                    w = Integer.parseInt(res.substring(0, res.indexOf('x')));
                    h = Integer.parseInt(res.substring(res.indexOf('x') + 1));
                }
                if (a.containsKey("VIDEO")) throw new HlsException("video-rendition");
                variants.add(new Variant(variants.size(), bw, avg, w, h, quoted(a, "CODECS"), quoted(a, "AUDIO"), HlsUri.resolve(base, l)));
                continue;
            }
            String tag = tagName(l);
            if (tag == null) continue;   // Kommentar
            if (pending != null) throw new HlsException("stream-inf-without-uri");
            common(tag);
            if (IGNORED_COMMON.contains(tag) || "EXT-X-I-FRAME-STREAM-INF".equals(tag) || "EXT-X-SESSION-DATA".equals(tag)) continue;
            if ("EXT-X-STREAM-INF".equals(tag)) { pending = attributes(value(l)); continue; }
            if ("EXT-X-MEDIA".equals(tag)) {
                Map<String, String> a = attributes(value(l));
                String type = enumerated(a, "TYPE");
                if ("VIDEO".equals(type)) throw new HlsException("video-rendition");
                if (!"AUDIO".equals(type)) continue;   // SUBTITLES / CLOSED-CAPTIONS: ignoriert, nie geladen
                if (audio.size() >= MAX_RENDITIONS) throw new HlsException("renditions-limit");
                String group = quoted(a, "GROUP-ID"), name = quoted(a, "NAME");
                if (group == null || name == null || group.isEmpty()) throw new HlsException("rendition");
                if (!audioNames.add(group + '\u0000' + name)) throw new HlsException("rendition-duplicate");
                String uri = quoted(a, "URI");
                audio.add(new Rendition(audio.size(), group, name, quoted(a, "LANGUAGE"), uri == null ? null : HlsUri.resolve(base, uri),
                    quoted(a, "CHANNELS"), "YES".equals(enumerated(a, "DEFAULT")), "YES".equals(enumerated(a, "AUTOSELECT"))));
                continue;
            }
            throw new HlsException("unknown-tag");
        }
        if (pending != null) throw new HlsException("stream-inf-without-uri");
        if (variants.isEmpty()) throw new HlsException("no-variants");
        Set<String> groups = new HashSet<>();
        for (Rendition r : audio) groups.add(r.groupId);
        for (Variant v : variants) if (v.audioGroup != null && !groups.contains(v.audioGroup)) throw new HlsException("audio-group");
        return new Master(variants, audio);
    }

    static Media parseMedia(List<String> ls, String base) throws HlsException {
        int target = -1;
        boolean endList = false, sawSegment = false;
        String playlistType = null, map = null;
        double pendingDuration = -1;
        List<Segment> segs = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < ls.size(); i++) {
            String l = ls.get(i);
            if (l.isEmpty()) continue;
            if (!l.startsWith("#")) {
                if (pendingDuration < 0) throw new HlsException("uri-without-tag");
                if (endList) throw new HlsException("after-endlist");
                if (segs.size() >= MAX_SEGMENTS) throw new HlsException("segments-limit");
                segs.add(new Segment(HlsUri.resolve(base, l), pendingDuration));
                total += pendingDuration;
                if (total > MAX_TOTAL_SECONDS) throw new HlsException("duration-limit");
                pendingDuration = -1;
                sawSegment = true;
                continue;
            }
            String tag = tagName(l);
            if (tag == null) continue;
            common(tag);
            if (pendingDuration >= 0 && !"EXT-X-PROGRAM-DATE-TIME".equals(tag) && !"EXT-X-BITRATE".equals(tag))
                throw new HlsException("extinf-without-uri");
            switch (tag) {
                case "EXT-X-VERSION": case "EXT-X-INDEPENDENT-SEGMENTS": case "EXT-X-START": case "EXT-X-PROGRAM-DATE-TIME":
                case "EXT-X-BITRATE": case "EXT-X-ALLOW-CACHE":
                    break;
                case "EXT-X-TARGETDURATION":
                    if (target >= 0) throw new HlsException("targetduration");
                    target = decimal(value(l), "targetduration");
                    if (target <= 0 || target > MAX_SEGMENT_SECONDS) throw new HlsException("targetduration");
                    break;
                case "EXT-X-MEDIA-SEQUENCE": case "EXT-X-DISCONTINUITY-SEQUENCE":
                    if (sawSegment) throw new HlsException("sequence-position");
                    decimal(value(l), "sequence");
                    break;
                case "EXT-X-PLAYLIST-TYPE":
                    playlistType = value(l);
                    if (!"VOD".equals(playlistType) && !"EVENT".equals(playlistType)) throw new HlsException("playlist-type");
                    break;
                case "EXT-X-ENDLIST":
                    if (endList) throw new HlsException("endlist");
                    endList = true;
                    break;
                case "EXT-X-KEY": {
                    Map<String, String> a = attributes(value(l));
                    if (!"NONE".equals(enumerated(a, "METHOD")) || a.size() != 1) throw new HlsException("encrypted");
                    break;
                }
                case "EXT-X-MAP": {
                    if (map != null || sawSegment) throw new HlsException("init-change");
                    Map<String, String> a = attributes(value(l));
                    if (a.containsKey("BYTERANGE")) throw new HlsException("byterange");
                    if (a.size() != 1 || quoted(a, "URI") == null) throw new HlsException("map");
                    map = HlsUri.resolve(base, quoted(a, "URI"));
                    break;
                }
                case "EXTINF": {
                    String v = value(l);
                    int comma = v.indexOf(',');
                    String d = comma < 0 ? v : v.substring(0, comma);
                    if (!d.matches("[0-9]{1,6}(\\.[0-9]{1,9})?")) throw new HlsException("extinf");
                    double x = Double.parseDouble(d);
                    if (!(x > 0) || x > MAX_SEGMENT_SECONDS) throw new HlsException("extinf");
                    pendingDuration = x;
                    break;
                }
                default:
                    throw new HlsException(MASTER_TAGS.contains(tag) ? "mixed-playlist" : "unknown-tag");
            }
        }
        if (pendingDuration >= 0) throw new HlsException("extinf-without-uri");
        if (target < 0) throw new HlsException("targetduration");
        if (!endList) throw new HlsException("live");
        if (segs.isEmpty()) throw new HlsException("no-segments");
        for (Segment s : segs) if (Math.round(s.duration) > target) throw new HlsException("extinf-target");
        return new Media(target, map, segs);
    }

    /** Codec-Klasse einer CODECS-Angabe für das erste Profil: "ok", "unknown" (fehlt – nach Extraktion prüfen) oder Grund. */
    public static String codecClass(String codecs, boolean opusAllowed) {
        if (codecs == null) return "unknown";
        if (codecs.isEmpty()) return "codec";
        for (String c : codecs.split(",")) {
            String x = c.trim().toLowerCase(Locale.ROOT);
            if (x.startsWith("avc1.") && x.matches("avc1\\.[0-9a-f]{6}")) continue;
            if (x.equals("mp4a.40.2")) continue;
            if (x.equals("opus") && opusAllowed) continue;
            return "codec";
        }
        return "ok";
    }
}
