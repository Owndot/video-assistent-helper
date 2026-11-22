package de.evp.helfer.dash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * S3a: Strukturfingerabdruck eines MPD, Version 1 ("kadri-dash-fp/1"), festgelegt in DASH-S3A.md (Abschnitt 3).
 *
 * Zweck: Konsistenzprüfung – hat der Helper beim eigenen Abruf dasselbe MPD bekommen, das Kadri gebunden hat?
 * Er ist KEINE Bindungsevidenz (die entsteht nur in Kadri aus beobachteten Stückfolgen) und kein Geheimnis.
 *
 * Kanonischer Text: eine Zeile je Feld "schlüssel=wert\n" in fester Reihenfolge (Dokumentreihenfolge der Elemente),
 * fehlende Werte als "-", Zeilenumbruch/Backslash im Wert maskiert. Hash: SHA-256 über die UTF-8-Bytes, klein hex.
 * Aufgenommen sind alle Eingaben, die Ressourcenidentität, Stückfolge, Spurwahl, Zeitbasis, Container oder eine
 * Sicherheitsentscheidung ändern können; abgeleitete Werte (Fenster, Abdeckung) nicht – sie folgen aus den Eingaben.
 */
public final class DashFingerprint {
    public static final String VERSION = "kadri-dash-fp/1";

    private DashFingerprint() {}

    public static String hex(DashManifest m) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonical(m).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(DashManifest m) {
        StringBuilder sb = new StringBuilder();
        line(sb, "fp", VERSION);
        line(sb, "mpd.type", m.typeRaw);
        line(sb, "mpd.mediaPresentationDuration", dur(m.mediaPresentationDuration));
        line(sb, "mpd.base", m.base);
        line(sb, "mpd.contentProtection", Integer.toString(m.root.all("ContentProtection").size()));
        for (DashManifest.Period p : m.periods) {
            line(sb, "period", Integer.toString(p.index));
            line(sb, "p.id", p.id);
            line(sb, "p.start", dur(p.start));
            line(sb, "p.duration", dur(p.duration));
            line(sb, "p.base", p.base);
            for (DashManifest.AdaptationSet a : m.sets) {
                if (a.period != p.index) continue;
                line(sb, "set", Integer.toString(a.index));
                line(sb, "s.id", a.id);
                line(sb, "s.group", a.group);
                line(sb, "s.contentType", a.contentType);
                line(sb, "s.mimeType", a.mimeType);
                line(sb, "s.codecs", a.codecs);
                line(sb, "s.lang", a.lang);
                line(sb, "s.label", a.label);
                line(sb, "s.base", a.base);
                props(sb, "s.contentProtection", a.contentProtection);
                line(sb, "s.contentComponents", Integer.toString(a.contentComponents));
                props(sb, "s.essential", a.essential);
                props(sb, "s.supplemental", a.supplemental);
                props(sb, "s.role", a.roles);
                props(sb, "s.accessibility", a.accessibility);
                props(sb, "s.audioChannels", a.audioChannels);
                for (DashManifest.Representation r : m.reps) {
                    if (r.set != a.index) continue;
                    line(sb, "rep", Integer.toString(r.index));
                    line(sb, "r.id", r.id);
                    line(sb, "r.bandwidth", Long.toString(r.bandwidth));
                    line(sb, "r.mimeType", r.mimeType);
                    line(sb, "r.codecs", r.codecs);
                    line(sb, "r.width", r.width);
                    line(sb, "r.height", r.height);
                    line(sb, "r.frameRate", r.frameRate);
                    line(sb, "r.audioSamplingRate", r.audioSamplingRate);
                    line(sb, "r.base", r.base);
                    props(sb, "r.contentProtection", r.contentProtection);
                    props(sb, "r.essential", r.essential);
                    props(sb, "r.supplemental", r.supplemental);
                    props(sb, "r.audioChannels", r.audioChannels);
                    line(sb, "r.subRepresentations", Integer.toString(r.subRepresentations));
                    line(sb, "r.segmentElements", r.segLevels);
                    line(sb, "r.timescale", num(r.timescale));
                    line(sb, "r.presentationTimeOffset", num(r.pto));
                    line(sb, "r.startNumber", num(r.startNumber));
                    line(sb, "r.duration", num(r.duration));
                    line(sb, "r.media", r.mediaCanon);
                    line(sb, "r.init", r.initUrl);
                    line(sb, "r.initRange", r.initRange);
                    line(sb, "r.unsupported", r.templateExtra.isEmpty() ? null : String.join(",", r.templateExtra));
                    if (r.tlRaw != null) {
                        line(sb, "r.timeline", Integer.toString(r.tlCount));
                        for (int k = 0; k < r.tlCount; k++) {
                            long t = r.tlRaw[k * 3];
                            line(sb, "r.S", (t == DashXml.NO_T ? "-" : Long.toString(t)) + "," + r.tlRaw[k * 3 + 1] + "," + r.tlRaw[k * 3 + 2]);
                        }
                    }
                    if (r.list != null) {
                        line(sb, "r.list", Integer.toString(r.list.size()) + (r.listRanges ? ",ranges" : ""));
                        for (int k = 0; k < r.list.size(); k++)
                            sb.append("r.SegmentURL=").append(esc(r.list.get(k))).append('|').append(esc(r.listRange == null ? null : r.listRange.get(k))).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String dur(java.math.BigDecimal v) { return v == null ? null : DashNum.canon(v); }

    private static String num(Long v) { return v == null ? null : Long.toString(v); }

    /** Eigenschaften (ContentProtection, Essential-/SupplementalProperty, Role …): schemeIdUri|value|default_KID. */
    private static void props(StringBuilder sb, String key, List<DashManifest.Prop> ps) {
        for (DashManifest.Prop p : ps) sb.append(key).append('=').append(esc(p.scheme)).append('|').append(esc(p.value)).append('|').append(esc(p.extra)).append('\n');
    }

    private static void line(StringBuilder sb, String k, String v) { sb.append(k).append('=').append(esc(v)).append('\n'); }

    /** fehlend → "-"; wörtliches "-" → "\-"; Backslash, "|", LF, CR maskiert. */
    static String esc(String v) {
        if (v == null) return "-";
        if (v.equals("-")) return "\\-";
        StringBuilder sb = new StringBuilder(v.length() + 4);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '|') sb.append("\\|");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }
}
