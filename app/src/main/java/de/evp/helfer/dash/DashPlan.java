package de.evp.helfer.dash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S3a: vollständiger, begrenzter Downloadplan für ein MPD, das das S3-Profil besteht (DashProfile). Anders als
 * Kadris Erkennungsindex (dashSegmentIndex, nur für die Bindung, darf Ausschnitte enthalten) beschreibt der Plan
 * JEDES Stück jeder angebotenen Spur innerhalb der Periode – mit Ordinalzahl, Nummer, Zeit, Dauer und Adresse.
 *
 * Zwei angebotene Spuren dürfen keine Stück- oder Init-Adresse teilen ("track-collision", fail-closed).
 * Grenzen je Plan: Stücke je Spur (DashLimits.MAX_SEGMENTS_PER_TRACK), Stückdauer, Gesamtdauer, geschätzte Größe je
 * Option (Bandbreite × Dauer, DashLimits.MAX_MEDIA_BYTES), Zahl der Optionen. Speicher: die Stückfolgen bleiben als
 * Läufe (t, d, Anzahl) bzw. als die ohnehin gelesene Liste gespeichert; Adressen entstehen erst beim Abruf eines
 * Stücks (segment(i)). Der Bedarf ist damit durch die Größe des MPD begrenzt, nicht durch die Zahl der Stücke.
 * Der Plan lädt nichts und bindet nichts: er ist keine Bindungsevidenz.
 */
public final class DashPlan {
    public static final class Segment {
        /** Position in der vollständigen Adressfolge der Darstellung (Kadri: idx). */
        public final int ordinal;
        public final long number, time, duration;
        public final String url;
        Segment(int ordinal, long number, long time, long duration, String url) {
            this.ordinal = ordinal; this.number = number; this.time = time; this.duration = duration; this.url = url;
        }
    }

    public static final class Track {
        public final String kind;                 // video | audio
        public final DashManifest.Representation rep;
        public final DashManifest.AdaptationSet set;
        Track(String kind, DashManifest.Representation rep, DashManifest.AdaptationSet set) { this.kind = kind; this.rep = rep; this.set = set; }
        public int count() { return rep.count; }
        public Segment segment(int i) { return rep.segment(i); }
        public String initUrl() { return rep.initUrl; }
        public long timescale() { return rep.timescale == null ? 1 : rep.timescale; }
        public BigDecimal seconds() { return rep.covered; }
        public String lang() { return set.lang; }
    }

    public static final class Option {
        public final Track video, audio;          // audio null: das MPD hat keinen Ton
        public final long estimatedBytes;
        Option(Track video, Track audio, long estimatedBytes) { this.video = video; this.audio = audio; this.estimatedBytes = estimatedBytes; }

        public String describe() {
            String h = video.rep.height == null ? "?" : video.rep.height;
            return h + "p " + video.rep.codecs + (audio == null ? "" : " + " + audio.rep.codecs + (audio.lang() == null ? "" : " (" + audio.lang() + ")"));
        }
    }

    public final DashManifest manifest;
    public final List<Track> video, audio;
    public final List<Option> options;
    public final BigDecimal seconds;

    private DashPlan(DashManifest m, List<Track> video, List<Track> audio, List<Option> options, BigDecimal seconds) {
        this.manifest = m; this.video = Collections.unmodifiableList(video); this.audio = Collections.unmodifiableList(audio);
        this.options = Collections.unmodifiableList(options); this.seconds = seconds;
    }

    /** Plan nach dem S3-Profil; wirft mit dem festen Grund des Profils oder einer Plangrenze. */
    public static DashPlan build(DashManifest m) throws DashException {
        DashProfile.Verdict v = DashProfile.check(m);
        if (!v.ok) throw new DashException(v.reason);
        BigDecimal dur = m.periods.get(0).effectiveDuration;
        List<Track> video = new ArrayList<>(), audio = new ArrayList<>();
        for (DashManifest.Representation r : v.video) video.add(track("video", m, r));
        for (DashManifest.Representation r : v.audio) audio.add(track("audio", m, r));
        long pairs = (long) video.size() * Math.max(1, audio.size());
        if (pairs > DashLimits.MAX_OPTIONS) throw new DashException("options-limit");
        List<Track> all = new ArrayList<>(video);
        all.addAll(audio);
        if (collides(all)) throw new DashException("track-collision");
        List<Option> options = new ArrayList<>();
        for (Track vt : video) {
            if (audio.isEmpty()) { Option o = option(vt, null, dur); if (o != null) options.add(o); continue; }
            for (Track at : audio) { Option o = option(vt, at, dur); if (o != null) options.add(o); }
        }
        if (options.isEmpty()) throw new DashException("size-limit");
        return new DashPlan(m, video, audio, options, dur);
    }

    /**
     * Ressourcenidentität: können zwei angebotene Spuren dieselbe Adresse (Stück oder Init) anfordern? Ohne Stücke
     * aufzuzählen: eine Vorlagen-Adresse ist Präfix + Ziffern + Suffix. Zwei Vorlagen können nur dann gleiche Adressen
     * bilden, wenn ein Präfix Präfix des anderen und ein Suffix Suffix des anderen ist – das wird (fail-closed) schon
     * abgelehnt. Listen- und Init-Adressen werden gegen alle Vorlagen und gegeneinander geprüft. Aufwand: Spuren ≤ 257
     * (Optionsgrenze), Listeneinträge ≤ DashLimits.MAX_LIST_ENTRIES.
     */
    static boolean collides(List<Track> tracks) {
        // Init-Adressen und Listen-Stücke aller Spuren; jede Adresse darf genau einmal vorkommen – auch innerhalb
        // derselben Spur (Init == eigenes Stück, S3a-Fix P2). Eine von mehreren Spuren geteilte Liste fällt schon beim
        // ersten Eintrag der zweiten Spur auf; die Menge bleibt so durch die verschieden aufgelösten Listen begrenzt
        // (DashLimits.MAX_RESOLVED_LIST_ENTRIES).
        java.util.Set<String> fixed = new java.util.HashSet<>();
        for (Track t : tracks) if (!fixed.add(t.rep.initUrl)) return true;
        for (Track t : tracks) {
            DashManifest.Representation r = t.rep;
            if (r.list == null) continue;
            for (int i = 0; i < r.count; i++) if (!fixed.add(r.segment(i).url)) return true;
        }
        for (int a = 0; a < tracks.size(); a++) {
            DashTemplate.Media ma = tracks.get(a).rep.media;
            if (ma == null) continue;
            for (int b = a + 1; b < tracks.size(); b++) {
                DashTemplate.Media mb = tracks.get(b).rep.media;
                if (mb == null) continue;
                boolean pre = ma.prefix.startsWith(mb.prefix) || mb.prefix.startsWith(ma.prefix);
                boolean suf = ma.suffix.endsWith(mb.suffix) || mb.suffix.endsWith(ma.suffix);
                if (pre && suf) return true;
            }
            for (String u : fixed) {   // auch das eigene Init: passt es auf die eigene Vorlage, ist es mehrdeutig
                if (u.length() > ma.prefix.length() + ma.suffix.length() && u.startsWith(ma.prefix) && u.endsWith(ma.suffix)
                    && u.substring(ma.prefix.length(), u.length() - ma.suffix.length()).matches("[0-9]{1,20}")) return true;
            }
        }
        return false;
    }

    private static Track track(String kind, DashManifest m, DashManifest.Representation r) throws DashException {
        if (r.excluded != null || r.count <= 0 || r.count > DashLimits.MAX_SEGMENTS_PER_TRACK) throw new DashException("segments-limit");
        return new Track(kind, r, m.sets.get(r.set));
    }

    private static Option option(Track v, Track a, BigDecimal dur) {
        long bw = v.rep.bandwidth + (a == null ? 0 : a.rep.bandwidth);
        BigDecimal bytes = BigDecimal.valueOf(bw).multiply(dur).divide(BigDecimal.valueOf(8), 0, RoundingMode.CEILING);
        if (bytes.compareTo(BigDecimal.valueOf(DashLimits.MAX_MEDIA_BYTES)) > 0) return null;
        return new Option(v, a, bytes.longValue());
    }

    /**
     * Alle Darstellungen mit nutzbarer Stückfolge – unabhängig vom Profil (Codec, Schutz, Typ). Nur für die
     * Querprüfung mit Kadris Erkennungsindex und für Diagnosezwecke; nie Grundlage eines Downloads.
     */
    public static List<DashManifest.Representation> addressable(DashManifest m) {
        List<DashManifest.Representation> out = new ArrayList<>();
        for (DashManifest.Representation r : m.reps) if (r.excluded == null && r.count > 0) out.add(r);
        return out;
    }
}
