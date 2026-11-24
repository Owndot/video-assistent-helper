package de.evp.helfer.dash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S3a: das S3-Profil „unverschlüsseltes, statisches DASH-VOD, eine Periode, fMP4 mit H.264/AAC“. Entscheidet, ob ein
 * gelesenes MPD überhaupt einen Plan bekommt und welche Darstellungen als Spuren in Frage kommen. Fail-closed: ein
 * Merkmal, das das Ergebnis verändern könnte und hier nicht verstanden wird, schließt aus – nie still ignorieren.
 *
 * Feste Gründe (für Kadri-Diagnose und Protokoll, keine Adressen):
 *   dash-live, dash-type, dash-multiperiod, dash-protected, dash-duration-unknown, dash-duration-limit,
 *   dash-no-video, dash-codec, dash-addressing, dash-unsupported
 * Gründe je Darstellung (excluded): die von DashManifest (Adressierung, Zeitleiste …) und
 *   set-kind, set-essential, rep-essential, subrepresentation, muxed, codec, container, bandwidth, init-missing.
 * Text-, Bild- und sonstige Sets werden nicht angeboten (ignored); ihr Schutz zählt trotzdem (dash-protected).
 */
public final class DashProfile {
    private static final Pattern AVC = Pattern.compile("(avc1|avc3)\\.[0-9A-Fa-f]{6}");
    private static final Pattern AAC = Pattern.compile("mp4a\\.40\\.(2|5|29)");

    public static final class Verdict {
        public final boolean ok;
        public final String reason;                       // null wenn ok
        public final List<DashManifest.Representation> video, audio;
        /** Ausschlussgrund je Darstellung (Index im Manifest); fehlt = angenommen oder Set ignoriert. */
        public final Map<Integer, String> excluded;
        Verdict(String reason, List<DashManifest.Representation> video, List<DashManifest.Representation> audio, Map<Integer, String> excluded) {
            this.ok = reason == null; this.reason = reason;
            this.video = Collections.unmodifiableList(video); this.audio = Collections.unmodifiableList(audio);
            this.excluded = Collections.unmodifiableMap(excluded);
        }
    }

    private DashProfile() {}

    /** Art eines Sets aus contentType und MIME; widersprechen sie sich → "set-kind". */
    static String kindOf(DashManifest.AdaptationSet a, String repMime) {
        String ct = a.contentType == null ? null : a.contentType.trim().toLowerCase(Locale.ROOT);
        String mime = repMime == null ? "" : repMime.trim().toLowerCase(Locale.ROOT);
        String fromMime = mime.startsWith("video/") ? "video" : mime.startsWith("audio/") ? "audio" : mime.startsWith("text/") ? "text"
            : mime.startsWith("image/") ? "image" : mime.equals("application/ttml+xml") ? "text" : mime.equals("application/mp4") ? "other" : mime.isEmpty() ? null : "other";
        if (ct != null && fromMime != null && !ct.equals(fromMime) && !(ct.equals("text") && fromMime.equals("other"))) return "conflict";
        return ct != null ? ct : fromMime == null ? "other" : fromMime;
    }

    public static Verdict check(DashManifest m) {
        List<DashManifest.Representation> video = new ArrayList<>(), audio = new ArrayList<>();
        Map<Integer, String> ex = new LinkedHashMap<>();
        if (m.typeRaw != null && m.typeRaw.trim().equals("dynamic")) return new Verdict("dash-live", video, audio, ex);
        if (m.typeRaw != null && !m.typeRaw.trim().equals("static")) return new Verdict("dash-type", video, audio, ex);
        if (m.periods.size() != 1) return new Verdict("dash-multiperiod", video, audio, ex);
        if (m.contentProtectionCount > 0) return new Verdict("dash-protected", video, audio, ex);
        DashManifest.Period p = m.periods.get(0);
        if (p.effectiveDuration == null) return new Verdict("dash-duration-unknown", video, audio, ex);
        if (p.effectiveDuration.doubleValue() > DashLimits.MAX_TOTAL_SECONDS) return new Verdict("dash-duration-limit", video, audio, ex);
        boolean anyVideo = false, anyAudio = false;
        List<String> videoWhy = new ArrayList<>(), audioWhy = new ArrayList<>();
        for (DashManifest.Representation r : m.reps) {
            DashManifest.AdaptationSet a = m.sets.get(r.set);
            String kind = kindOf(a, r.mimeType);
            if (!kind.equals("video") && !kind.equals("audio") && !kind.equals("conflict")) continue;   // Text/Bild/sonstiges: nicht angeboten
            String why = why(m, a, r, kind);
            if (kind.equals("video")) anyVideo = true;
            if (kind.equals("audio")) anyAudio = true;
            if (why != null) {
                ex.put(r.index, why);
                (kind.equals("audio") ? audioWhy : videoWhy).add(why);
                continue;
            }
            (kind.equals("video") ? video : audio).add(r);
        }
        if (!anyVideo) return new Verdict("dash-no-video", video, audio, ex);
        if (video.isEmpty()) return new Verdict(category(videoWhy), video, audio, ex);
        // Ton vorhanden, aber keiner nutzbar: nicht still ohne Ton anbieten
        if (anyAudio && audio.isEmpty()) return new Verdict(category(audioWhy), video, audio, ex);
        return new Verdict(null, video, audio, ex);
    }

    private static String why(DashManifest m, DashManifest.AdaptationSet a, DashManifest.Representation r, String kind) {
        if (kind.equals("conflict")) return "set-kind";
        // S4b-8: ein (bereits geprüfter, unterstützter) Annex-I-Descriptor ist verstanden – kein unbekanntes EssentialProperty
        if (unknown(a.essential)) return "set-essential";
        if (unknown(r.essential)) return "rep-essential";
        if (r.subRepresentations > 0 || a.contentComponents > 0) return "subrepresentation";
        if (r.excluded != null) return r.excluded;
        String mime = r.mimeType == null ? "" : r.mimeType.trim().toLowerCase(Locale.ROOT);
        String codecs = r.codecs == null ? "" : r.codecs.trim();
        if (codecs.contains(",")) return "muxed";
        if (kind.equals("video")) {
            if (!mime.equals("video/mp4")) return "container";
            if (!AVC.matcher(codecs).matches()) return "codec";
        } else {
            if (!mime.equals("audio/mp4")) return "container";
            if (!AAC.matcher(codecs).matches()) return "codec";
        }
        if (r.bandwidth <= 0) return "bandwidth";
        if (r.initUrl == null) return "init-missing";
        return null;
    }

    private static final java.util.Set<String> ADDRESSING = new java.util.HashSet<>(java.util.Arrays.asList(
        "addressing-single", "byte-range", "time-without-timeline", "addressing-media", "addressing-duration", "addressing-attr",
        "list-duplicate", "list-timeline", "timeline-gap", "timeline-overlap", "timeline-repeat", "timeline-empty", "zero-duration",
        "coverage-mismatch", "segments", "segment-duration", "duration-unknown", "number-range", "uri-length", "init-missing"));

    /** Zusammengefasster Grund: Codec/Container vor Adressierung vor sonstigem; S4b-6: alle nur „Alternative nicht bestimmt“. */
    private static String category(List<String> whys) {
        if (!whys.isEmpty() && whys.stream().allMatch("baseurl-undetermined"::equals)) return "dash-baseurl-undetermined";
        for (String w : whys) if (w.equals("codec") || w.equals("container") || w.equals("muxed")) return "dash-codec";
        for (String w : whys) if (ADDRESSING.contains(w)) return "dash-addressing";
        return "dash-unsupported";
    }

    private static boolean unknown(java.util.List<DashManifest.Prop> ps) {
        for (DashManifest.Prop p : ps) if (!DashUrlParam.SCHEME.equals(p.scheme)) return true;
        return false;
    }
}
