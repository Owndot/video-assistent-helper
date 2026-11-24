package de.evp.helfer.dash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S3a: ein gelesenes MPD mit allen Darstellungen, vollständig ausgewertet: Vererbung MPD → Period → AdaptationSet →
 * Representation, BaseURL-Kette, Adressierung (Template mit $Number$/$Time$, SegmentTimeline, SegmentList) und die
 * Stückfolge je Darstellung innerhalb ihrer Periode. Der Parser lädt nichts selbst.
 *
 * Zwei Arten von Ablehnung:
 *  - fatal (DashException beim Lesen): alles Mehrdeutige oder Sicherheitsrelevante – DTD/Entitäten, XInclude, XLink,
 *    fremde Namensräume für DASH-Namen, Grenzen, ungültige Zahlen/Dauern, mehrere BaseURLs auf einer Ebene,
 *    unbekannte BaseURL-Attribute, gemischte oder mehrfache Adressierung, ungültige Vorlagen und Adressen,
 *    gleichzeitiges @duration und SegmentTimeline, unbekannte S-/SegmentURL-Attribute;
 *  - je Darstellung (Representation.excluded): wohldefinierte, aber (noch) nicht unterstützte Formen – SegmentBase
 *    und Byte-Bereiche, $Time$ ohne Zeitleiste, Lücken/Überlappungen der Zeitleiste, unbekannte Periodendauer bei
 *    offener Wiederholung, Überschreiten der Stückgrenze, Abdeckung ≠ Periodendauer, Überlauf über 2^53−1.
 * Was die Darstellung für den Download annehmbar macht (Codec, Schutz, Typ, Perioden), entscheidet DashProfile.
 */
public final class DashManifest {
    public final String type;                   // Rohwert von MPD@type ("static" wenn fehlend)
    public final String typeRaw;                // null = Attribut fehlt
    public final BigDecimal mediaPresentationDuration;
    public final String documentUrl, base;      // kanonisch
    public final List<Period> periods;
    public final List<AdaptationSet> sets;
    public final List<Representation> reps;
    /** ContentProtection irgendwo im MPD (jede Ebene und Platzierung, auch Text-/Bild-Sets) – S3-Profil: dash-protected. */
    public final int contentProtectionCount;
    /** Davon außerhalb der direkten Kinder von MPD, AdaptationSet und Representation (nicht standardkonform oder geschachtelt). */
    public final int contentProtectionNested;
    final DashXml.El root;
    /** S4b-6: Roh-Texte mehrerer BaseURL auf MPD-Ebene (null: höchstens eine) – für den Fingerabdruck. */
    public List<String> baseAlts;

    public static final class Prop {
        public final String scheme, value, extra;
        Prop(String scheme, String value, String extra) { this.scheme = scheme; this.value = value; this.extra = extra; }
    }

    public static final class Period {
        public final int index;
        public final String id;
        public final BigDecimal start, duration;   // Rohwerte (null = fehlt)
        /** Wirksame Dauer in Sekunden (aus @duration, nächstem @start oder MPD-Dauer); null = unbekannt. */
        public BigDecimal effectiveDuration;
        public BigDecimal effectiveStart;
        public final String base;
        public List<String> baseAlts;   // S4b-6: mehrere BaseURL (Roh-Texte), sonst null
        final DashXml.El el;
        Period(int index, String id, BigDecimal start, BigDecimal duration, String base, DashXml.El el) {
            this.index = index; this.id = id; this.start = start; this.duration = duration; this.base = base; this.el = el;
        }
    }

    public static final class AdaptationSet {
        public final int index, period;
        public final String id, group, contentType, mimeType, codecs, lang, label, base;
        public final List<Prop> contentProtection, essential, supplemental, roles, accessibility, audioChannels;
        public final int contentComponents;
        public List<String> baseAlts;   // S4b-6
        final DashXml.El el;
        AdaptationSet(int index, int period, DashXml.El el, String label, String base, List<Prop> cp, List<Prop> ess, List<Prop> sup,
                      List<Prop> roles, List<Prop> acc, List<Prop> ach) {
            this.index = index; this.period = period; this.el = el;
            this.id = el.attr("id"); this.group = el.attr("group"); this.contentType = el.attr("contentType");
            this.mimeType = el.attr("mimeType"); this.codecs = el.attr("codecs"); this.lang = el.attr("lang");
            this.label = label; this.base = base; this.contentProtection = cp; this.essential = ess; this.supplemental = sup;
            this.roles = roles; this.accessibility = acc; this.audioChannels = ach;
            this.contentComponents = el.all("ContentComponent").size();
        }
    }

    public enum Addressing { NUMBER_DURATION, NUMBER_TIMELINE, TIME_TIMELINE, LIST_DURATION, LIST_TIMELINE, LIST_SINGLE, SEGMENT_BASE, SINGLE, NONE }

    public static final class Representation {
        public final int index, set, period;
        public final String id, mimeType, codecs, width, height, frameRate, audioSamplingRate, base;
        public final long bandwidth;
        public final List<Prop> contentProtection, essential, supplemental, audioChannels;
        public final int subRepresentations;
        public List<String> baseAlts;   // S4b-6
        /** S4b-6: Zahl der verschiedenen aufgelösten Basen über alle Alternativen der Kette (1 ohne Alternativen). */
        public int baseAlternatives = 1;
        /** Adressierung nach Vererbung; excluded != null: nicht als Stückfolge nutzbar (fester Grund). */
        public Addressing addressing = Addressing.NONE;
        public String excluded;
        /** Wirksame (geerbte) Werte; null = nicht angegeben. */
        public Long timescale, pto, startNumber, duration;
        public String mediaCanon, initUrl;           // initUrl kanonisch; mediaCanon mit {Number}/{Time}
        public String initRange;                     // Initialization@range (Byte-Bereich, nicht unterstützt)
        public List<String> templateExtra = Collections.emptyList();   // gesetzte, nicht unterstützte Vorlagen-Attribute
        /** Eingabe: welches Adressierungs-Element auf welcher Ebene steht ("rep:SegmentTemplate,set:-,period:-"). */
        public String segLevels;
        DashTemplate.Media media;
        /** SegmentTimeline roh (t/d/r je S, t = DashXml.NO_T wenn fehlend). */
        long[] tlRaw;
        int tlCount;
        /** SegmentList: kanonische Adressen (in Reihenfolge), Byte-Bereiche. */
        List<String> list, listRange;
        boolean listRanges;
        // ---- Stückfolge (nur wenn excluded == null): Läufe mit absoluter Zeit; Ordinalzahl = Position in der Folge ----
        long[] runT = new long[0], runD = new long[0];
        int[] runOrd = new int[0], runCnt = new int[0];
        /** Ordinalzahl des ersten und Anzahl der Stücke innerhalb des Periodenfensters. */
        public int first, count;
        /** Abgedeckte Zeit in Sekunden. */
        public BigDecimal covered = BigDecimal.ZERO;
        public long maxSegmentTicks;

        Representation(int index, int set, int period, String id, long bandwidth, String mimeType, String codecs, DashXml.El el, String base,
                       List<Prop> cp, List<Prop> ess, List<Prop> sup, List<Prop> ach) {
            this.index = index; this.set = set; this.period = period; this.id = id; this.bandwidth = bandwidth;
            this.mimeType = mimeType; this.codecs = codecs; this.base = base;
            this.width = el.attr("width"); this.height = el.attr("height"); this.frameRate = el.attr("frameRate");
            this.audioSamplingRate = el.attr("audioSamplingRate");
            this.contentProtection = cp; this.essential = ess; this.supplemental = sup; this.audioChannels = ach;
            this.subRepresentations = el.all("SubRepresentation").size();
        }

        /** Stück i des Periodenfensters (0 ≤ i < count). */
        public DashPlan.Segment segment(int i) {
            if (excluded != null || i < 0 || i >= count) throw new IndexOutOfBoundsException();
            int ord = first + i;
            int r = run(ord);
            long t = runT[r] + (long) (ord - runOrd[r]) * runD[r];
            long number = (startNumber == null ? 1 : startNumber) + ord;
            String url;
            if (list != null) url = list.get(ord);
            else url = media.url(media.kind == DashTemplate.Kind.TIME ? t : number);
            return new DashPlan.Segment(ord, number, t, runD[r], url);
        }

        private int run(int ord) {
            int lo = 0, hi = runOrd.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (runOrd[mid] <= ord) lo = mid; else hi = mid - 1;
            }
            return lo;
        }
    }

    private DashManifest(DashXml.El root, String documentUrl, String base, String typeRaw, BigDecimal mpd, List<Period> periods,
                         List<AdaptationSet> sets, List<Representation> reps) {
        this.root = root; this.documentUrl = documentUrl; this.base = base; this.typeRaw = typeRaw;
        this.type = typeRaw == null ? "static" : typeRaw; this.mediaPresentationDuration = mpd;
        this.periods = Collections.unmodifiableList(periods); this.sets = Collections.unmodifiableList(sets);
        this.reps = Collections.unmodifiableList(reps);
        // S3a-Fix P1: jede ContentProtection im ganzen Baum zählt – auch an Stellen, die das Schema nicht vorsieht
        // (z. B. direkt unter Period oder in einer Vorlage). Fail-closed statt nur bekannte Ebenen abzufragen.
        int all = countAll(root, "ContentProtection");
        this.contentProtectionCount = all;
        this.contentProtectionNested = all - direct(root);
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final Set<String> BASEURL_ATTRS = new HashSet<>(Arrays.asList("serviceLocation", "availabilityTimeOffset", "availabilityTimeComplete"));
    /** Vorlagen-/Listen-Attribute, die hier verstanden werden. */
    private static final Set<String> SEG_KNOWN = new HashSet<>(Arrays.asList("media", "initialization", "timescale", "presentationTimeOffset",
        "startNumber", "duration", "availabilityTimeOffset", "availabilityTimeComplete"));
    /** Vorlagen-/Listen-Attribute, die die Stückfolge oder die Zerlegung ändern, hier aber (noch) nicht unterstützt sind. */
    private static final Set<String> SEG_UNSUPPORTED = new HashSet<>(Arrays.asList("index", "indexRange", "indexRangeExact", "bitstreamSwitching",
        "presentationDuration", "eptDelta", "pdDelta", "endNumber"));
    private static final Set<String> SEG_KIDS_UNSUPPORTED = new HashSet<>(Arrays.asList("RepresentationIndex", "BitstreamSwitching", "FailoverContent"));

    /**
     * Liest ein MPD. documentUrl: die finale Adresse der Antwort (nach Weiterleitungen) – Basis aller relativen
     * Adressen. Wirft bei jeder fatalen Abweichung.
     */
    public static DashManifest parse(byte[] body, String documentUrl) throws DashException { return parse(body, documentUrl, null); }

    /**
     * S4b-6: wie oben mit Auswahl unter mehreren BaseURL (BaseSelection). {@code sel} null: mehrere BaseURL auf einer Ebene sind
     * fatal ("baseurl-multiple", wie bisher). Sonst: je Element höchstens {@link DashLimits#MAX_BASEURLS_PER_ELEMENT}, je
     * Darstellung höchstens {@link DashLimits#MAX_BASE_COMBINATIONS} Kombinationen über die Kette MPD → Periode → Set →
     * Darstellung (vor dem Zusammenfassen gleicher Adressen; darüber "baseurl-limits"), jede Kombination muss sich auflösen.
     * Die gewählte Alternative jeder Ebene bestimmt die Basis (Projektion auf ein MPD mit je einer BaseURL). Nicht gewählt:
     * auf MPD-/Periodenebene fatal ("baseurl-undetermined"), auf Set-/Darstellungsebene ist die Darstellung ausgeschlossen
     * ("baseurl-undetermined"). Ein Eintrag der Auswahl ohne passendes Element oder mit zu großem Index: "alt-invalid".
     */
    public static DashManifest parse(byte[] body, String documentUrl, BaseSelection sel) throws DashException {
        String doc = DashUrl.canonical(stripFragment(documentUrl));
        DashXml.El root = DashXml.parse(body);
        Ctx ctx = new Ctx();
        String typeRaw = root.attr("type");
        BigDecimal mpdDur = DashNum.optDuration(root, "mediaPresentationDuration");
        List<String> mpdRaw = rawBases(root);
        String mpdBase = baseOf(root, doc, mpdRaw, "m", sel);
        if (mpdBase == null) throw new DashException("baseurl-undetermined");
        List<DashXml.El> pEls = root.all("Period");
        if (pEls.size() > DashLimits.MAX_PERIODS) throw new DashException("limits");
        List<Period> periods = new ArrayList<>();
        List<AdaptationSet> sets = new ArrayList<>();
        List<Representation> reps = new ArrayList<>();
        for (DashXml.El pe : pEls) {
            List<String> pRaw = rawBases(pe);
            String pBase = baseOf(pe, mpdBase, pRaw, "p" + periods.size(), sel);
            if (pBase == null) throw new DashException("baseurl-undetermined");
            Period p = new Period(periods.size(), pe.attr("id"), DashNum.optDuration(pe, "start"), DashNum.optDuration(pe, "duration"), pBase, pe);
            p.baseAlts = pRaw.size() > 1 ? pRaw : null;
            periods.add(p);
            segKinds(pe);
            for (DashXml.El ae : pe.all("AdaptationSet")) {
                if (sets.size() >= DashLimits.MAX_SETS) throw new DashException("limits");
                List<Prop> acp = props(ae, "ContentProtection");
                String label = null;
                List<DashXml.El> labels = ae.all("Label");
                if (!labels.isEmpty()) label = labels.get(0).trimmedText();
                List<String> aRaw = rawBases(ae);
                AdaptationSet a = new AdaptationSet(sets.size(), p.index, ae, label, baseOf(ae, p.base, aRaw, "s" + sets.size(), sel), acp, props(ae, "EssentialProperty"),
                    props(ae, "SupplementalProperty"), props(ae, "Role"), props(ae, "Accessibility"), props(ae, "AudioChannelConfiguration"));
                a.baseAlts = aRaw.size() > 1 ? aRaw : null;
                sets.add(a);
                segKinds(ae);
                for (DashXml.El re : ae.all("Representation")) {
                    if (reps.size() >= DashLimits.MAX_REPS) throw new DashException("limits");
                    List<Prop> rcp = props(re, "ContentProtection");
                    String id = re.attr("id");
                    String bwRaw = re.attr("bandwidth");
                    long bw = bwRaw == null ? 0 : DashNum.parseU(bwRaw);
                    if (bw > DashLimits.MAX_BANDWIDTH) throw new DashException("number-range");
                    String mime = re.attr("mimeType") != null ? re.attr("mimeType") : a.mimeType;
                    String codecs = re.attr("codecs") != null ? re.attr("codecs") : a.codecs;
                    List<String> rRaw = rawBases(re);
                    Representation r = new Representation(reps.size(), a.index, p.index, id, bw, mime, codecs, re, a.base == null ? null : baseOf(re, a.base, rRaw, "r" + reps.size(), sel), rcp,
                        props(re, "EssentialProperty"), props(re, "SupplementalProperty"), props(re, "AudioChannelConfiguration"));
                    if (a.base == null && rRaw.size() > 1 && sel != null && !sel.isFirst()) sel.choose("r" + reps.size(), rRaw.size());   // (Index prüfen)
                    r.baseAlts = rRaw.size() > 1 ? rRaw : null;
                    if (sel != null && (mpdRaw.size() > 1 || pRaw.size() > 1 || aRaw.size() > 1 || rRaw.size() > 1))
                        r.baseAlternatives = combinations(doc, Arrays.asList(mpdRaw, pRaw, aRaw, rRaw));
                    reps.add(r);
                    segKinds(re);
                    if (r.base == null) { r.excluded = "baseurl-undetermined"; continue; }
                    addressing(r, new DashXml.El[] {re, ae, pe}, ctx);
                }
            }
        }
        if (sel != null) sel.checkAllUsed();
        DashManifest m = new DashManifest(root, doc, mpdBase, typeRaw, mpdDur, periods, sets, reps);
        m.baseAlts = mpdRaw.size() > 1 ? mpdRaw : null;
        periodTiming(m);
        for (Representation r : reps) if (r.excluded == null) expand(m, r, ctx);
        return m;
    }

    private static String stripFragment(String u) throws DashException {
        if (u == null) throw new DashException("uri-empty");
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    static int countAll(DashXml.El e, String name) {
        int n = e.name.equals(name) ? 1 : 0;
        for (DashXml.El k : e.kids) n += countAll(k, name);
        return n;
    }

    /** ContentProtection als direkte Kinder von MPD, AdaptationSet und Representation (im Fingerabdruck einzeln geführt). */
    private static int direct(DashXml.El root) {
        int n = root.all("ContentProtection").size();
        for (DashXml.El p : root.all("Period")) for (DashXml.El a : p.all("AdaptationSet")) {
            n += a.all("ContentProtection").size();
            for (DashXml.El r : a.all("Representation")) n += r.all("ContentProtection").size();
        }
        return n;
    }

    private static List<Prop> props(DashXml.El e, String name) {
        List<Prop> out = new ArrayList<>();
        for (DashXml.El p : e.all(name)) {
            String extra = null;
            for (String k : p.attrs.keySet()) if (k.endsWith("}default_KID")) extra = p.attrs.get(k);
            out.add(new Prop(p.attr("schemeIdUri"), p.attr("value"), extra));
        }
        return out;
    }

    /** Roh-Texte aller BaseURL einer Ebene (jeder nicht leer, nur bekannte Attribute; höchstens MAX_BASEURLS_PER_ELEMENT). */
    private static List<String> rawBases(DashXml.El e) throws DashException {
        List<DashXml.El> b = e.all("BaseURL");
        if (b.size() > DashLimits.MAX_BASEURLS_PER_ELEMENT) throw new DashException("baseurl-limits");
        List<String> out = new ArrayList<>(b.size());
        for (DashXml.El x : b) {
            for (String k : x.attrs.keySet()) if (!k.startsWith("{") && !BASEURL_ATTRS.contains(k)) throw new DashException("baseurl-attr");
            String t = x.trimmedText();
            if (t.isEmpty()) throw new DashException("baseurl-empty");
            out.add(t);
        }
        return out;
    }

    /**
     * Basis einer Ebene gegen die äußere Basis: keine BaseURL → äußere; eine → aufgelöst; mehrere → ohne Auswahl fatal
     * ("baseurl-multiple"), sonst die gewählte (null = nicht bestimmt bzw. äußere Basis unbestimmt).
     */
    private static String baseOf(DashXml.El e, String outer, List<String> raw, String key, BaseSelection sel) throws DashException {
        if (raw.isEmpty()) return outer;
        if (raw.size() == 1) return outer == null ? null : DashUrl.resolve(outer, raw.get(0));
        if (sel == null) throw new DashException("baseurl-multiple");
        int i = sel.choose(key, raw.size());
        if (i < 0 || outer == null) return null;
        return DashUrl.resolve(outer, raw.get(i));
    }

    /**
     * Alle Kombinationen der Kette (MPD, Periode, Set, Darstellung) auflösen – begrenzt VOR dem Zusammenfassen (keine
     * unbeschränkte kartesische Erweiterung): höchstens MAX_BASE_COMBINATIONS, jede muss gültig sein. Ergebnis: Zahl der
     * verschiedenen kanonischen Basen.
     */
    static int combinations(String doc, List<List<String>> chain) throws DashException {
        List<String> cur = new ArrayList<>(Collections.singletonList(doc));
        for (List<String> raw : chain) {
            if (raw.isEmpty()) continue;
            if ((long) cur.size() * raw.size() > DashLimits.MAX_BASE_COMBINATIONS) throw new DashException("baseurl-limits");
            List<String> next = new ArrayList<>(cur.size() * raw.size());
            for (String b : cur) for (String t : raw) next.add(DashUrl.resolve(b, t));
            cur = next;
        }
        return new java.util.LinkedHashSet<>(cur).size();
    }

    /** Je Ebene höchstens EIN Adressierungs-Element (SegmentBase, SegmentList, SegmentTemplate). */
    private static void segKinds(DashXml.El e) throws DashException {
        int n = e.all("SegmentBase").size() + e.all("SegmentList").size() + e.all("SegmentTemplate").size();
        if (n > 1) throw new DashException("addressing-multiple");
    }

    private static DashXml.El seg(DashXml.El e) {
        for (DashXml.El k : e.kids) if (k.name.equals("SegmentBase") || k.name.equals("SegmentList") || k.name.equals("SegmentTemplate")) return k;
        return null;
    }

    /** Nächstgelegener Wert eines Attributs entlang der Kette (Darstellung → Set → Periode). */
    private static String inherit(DashXml.El[] segs, String k) {
        for (DashXml.El s : segs) if (s != null && s.attr(k) != null) return s.attr(k);
        return null;
    }

    private static void addressing(Representation r, DashXml.El[] chain, Ctx ctx) throws DashException {
        DashXml.El[] segs = new DashXml.El[chain.length];
        Set<String> kinds = new HashSet<>();
        for (int i = 0; i < chain.length; i++) { segs[i] = seg(chain[i]); if (segs[i] != null) kinds.add(segs[i].name); }
        r.segLevels = "rep:" + (segs[0] == null ? "-" : segs[0].name) + ",set:" + (segs[1] == null ? "-" : segs[1].name) + ",period:" + (segs[2] == null ? "-" : segs[2].name);
        if (kinds.size() > 1) throw new DashException("addressing-mixed");
        // Attribut-Prüfung aller beteiligten Elemente: Unbekanntes → fatal, Unterstütztes → nur merken
        List<String> extra = new ArrayList<>();
        for (DashXml.El s : segs) {
            if (s == null) continue;
            for (String k : s.attrs.keySet()) {
                if (k.startsWith("{")) continue;   // fremde Namensräume (Herstellererweiterungen) ändern nichts
                if (SEG_UNSUPPORTED.contains(k)) { if (!extra.contains(k)) extra.add(k); continue; }
                if (!SEG_KNOWN.contains(k)) throw new DashException("addressing-attr");
            }
            if (s.entryExtra) throw new DashException("addressing-attr");
            for (DashXml.El k : s.kids) if (SEG_KIDS_UNSUPPORTED.contains(k.name) && !extra.contains(k.name)) extra.add(k.name);
            if (s.attr("initialization") != null && !s.all("Initialization").isEmpty()) throw new DashException("addressing-ambiguous");
            if (s.all("Initialization").size() > 1 || s.all("SegmentTimeline").size() > 1) throw new DashException("addressing-multiple");
            for (DashXml.El ini : s.all("Initialization")) for (String k : ini.attrs.keySet())
                if (!k.startsWith("{") && !k.equals("sourceURL") && !k.equals("range")) throw new DashException("addressing-attr");
        }
        r.templateExtra = extra;
        r.timescale = numOrNull(inherit(segs, "timescale"));
        r.pto = numOrNull(inherit(segs, "presentationTimeOffset"));
        r.startNumber = numOrNull(inherit(segs, "startNumber"));
        r.duration = numOrNull(inherit(segs, "duration"));
        if (r.timescale != null && (r.timescale == 0 || r.timescale > DashLimits.MAX_TIMESCALE)) throw new DashException("number-range");
        // Init: Attribut (Vorlage) oder Initialization-Kind (nächstgelegenes)
        String initTpl = null, initSrc = null;
        for (DashXml.El s : segs) {
            if (s == null) continue;
            if (s.attr("initialization") != null) { initTpl = s.attr("initialization"); break; }
            List<DashXml.El> ini = s.all("Initialization");
            if (!ini.isEmpty()) { initSrc = ini.get(0).attr("sourceURL"); r.initRange = ini.get(0).attr("range"); break; }
        }
        // Zeitleiste: nächstgelegene
        DashXml.El tl = null;
        for (DashXml.El s : segs) if (s != null && !s.all("SegmentTimeline").isEmpty()) { tl = s.all("SegmentTimeline").get(0); break; }
        if (tl != null && r.duration != null) throw new DashException("addressing-ambiguous");
        if (tl != null) {
            for (String k : tl.attrs.keySet()) if (!k.startsWith("{")) throw new DashException("addressing-attr");
            if (tl.entryExtra) throw new DashException("addressing-attr");   // S@n, S@k und Unbekanntes
            r.tlRaw = tl.s == null ? new long[0] : tl.s;   // geteilt, nur gelesen (nicht je Darstellung kopieren)
            r.tlCount = tl.sCount;
        }
        if (kinds.isEmpty()) { r.addressing = Addressing.SINGLE; r.excluded = "addressing-single"; return; }
        String kind = kinds.iterator().next();
        if (kind.equals("SegmentBase")) { r.addressing = Addressing.SEGMENT_BASE; r.excluded = "byte-range"; return; }
        if (kind.equals("SegmentTemplate")) {
            String media = inherit(segs, "media");
            if (initTpl != null) r.initUrl = DashTemplate.init(initTpl, r.id, r.bandwidth, r.base);
            else if (initSrc != null) r.initUrl = DashUrl.resolve(r.base, initSrc);
            if (media == null) { r.excluded = "addressing-media"; return; }
            r.media = DashTemplate.media(media, r.id, r.bandwidth, r.base);
            r.mediaCanon = r.media.canon();
            if (r.media.kind == DashTemplate.Kind.TIME) {
                r.addressing = Addressing.TIME_TIMELINE;
                if (tl == null) { r.excluded = "time-without-timeline"; return; }
            } else {
                r.addressing = tl != null ? Addressing.NUMBER_TIMELINE : Addressing.NUMBER_DURATION;
                if (tl == null && r.duration == null) { r.excluded = "addressing-duration"; return; }
            }
        } else {
            // SegmentList: Einträge nur auf EINER Ebene (sonst mehrdeutig)
            DashXml.El holder = null;
            for (DashXml.El s : segs) if (s != null && s.listMedia != null) {
                if (holder != null) throw new DashException("addressing-ambiguous");
                holder = s;
            }
            if (initSrc != null) r.initUrl = DashUrl.resolve(r.base, initSrc);
            else if (initTpl != null) throw new DashException("addressing-attr");   // @initialization gibt es nur an SegmentTemplate
            if (holder == null) { r.addressing = Addressing.SINGLE; r.excluded = "addressing-single"; return; }
            // Eine geerbte Liste wird je (Element, Basis) nur einmal aufgelöst und dann von allen Darstellungen geteilt
            // (unveränderlich); neu Aufzulösendes ist über das ganze MPD begrenzt (S3a-Fix P0).
            String lk = ctx.id(holder) + "|" + r.base;
            Object[] cached = ctx.lists.get(lk);
            if (cached == null) {
                ctx.listResolved += holder.listMedia.size();
                if (ctx.listResolved > DashLimits.MAX_RESOLVED_LIST_ENTRIES) throw new DashException("limits");
                List<String> list = new ArrayList<>(holder.listMedia.size());
                Set<String> seen = new HashSet<>();
                boolean dup = false, ranges = false;
                for (int i = 0; i < holder.listMedia.size(); i++) {
                    String m = holder.listMedia.get(i);
                    if (holder.listRange.get(i) != null || m == null) { ranges = true; list.add(m == null ? r.base : DashUrl.resolve(r.base, m)); continue; }
                    String u = DashUrl.resolve(r.base, m);
                    if (!seen.add(u)) dup = true;
                    list.add(u);
                }
                cached = new Object[] {Collections.unmodifiableList(list), Collections.unmodifiableList(holder.listRange), ranges, dup};
                ctx.lists.put(lk, cached);
            }
            @SuppressWarnings("unchecked") List<String> cl = (List<String>) cached[0];
            @SuppressWarnings("unchecked") List<String> cr = (List<String>) cached[1];
            r.list = cl;
            r.listRange = cr;
            r.listRanges = (Boolean) cached[2];
            boolean dup = (Boolean) cached[3];
            r.addressing = tl != null ? Addressing.LIST_TIMELINE : r.duration != null ? Addressing.LIST_DURATION : Addressing.LIST_SINGLE;
            if (r.listRanges) { r.excluded = "byte-range"; return; }
            if (dup) { r.excluded = "list-duplicate"; return; }
            if (r.addressing == Addressing.LIST_SINGLE && r.list.size() != 1) { r.excluded = "addressing-duration"; return; }
        }
        if (r.initRange != null) { r.excluded = "byte-range"; return; }
        if (!extra.isEmpty()) { r.excluded = "addressing-attr"; return; }
    }

    private static Long numOrNull(String v) throws DashException { return v == null ? null : DashNum.parseU(v); }

    /** Wirksamer Beginn und Dauer jeder Periode (ISO/IEC 23009-1, 5.3.2.1), nur für statische MPDs vollständig. */
    private static void periodTiming(DashManifest m) {
        BigDecimal prevEnd = BigDecimal.ZERO;
        for (int i = 0; i < m.periods.size(); i++) {
            Period p = m.periods.get(i);
            BigDecimal start = p.start != null ? p.start : prevEnd;
            p.effectiveStart = start;
            BigDecimal dur = p.duration;
            if (dur == null && start != null && i + 1 < m.periods.size() && m.periods.get(i + 1).start != null) dur = m.periods.get(i + 1).start.subtract(start);
            if (dur == null && i + 1 == m.periods.size() && m.mediaPresentationDuration != null && start != null) dur = m.mediaPresentationDuration.subtract(start);
            p.effectiveDuration = dur != null && dur.signum() > 0 ? dur : null;
            prevEnd = start != null && p.effectiveDuration != null ? start.add(p.effectiveDuration) : null;
        }
    }

    /**
     * Speicher- und Rechengrenze über ALLE Darstellungen eines MPD (S3a-Fix P0): geerbte SegmentLists und
     * SegmentTimelines werden je (Element, Basis) bzw. je Stückfolge nur einmal ausgewertet und dann geteilt; was
     * dennoch neu aufgelöst oder ausgerechnet werden muss, ist insgesamt begrenzt (DashLimits.MAX_RESOLVED_LIST_ENTRIES,
     * DashLimits.MAX_EXPANDED_SEGMENTS) – darüber kontrolliert "limits", nie OutOfMemoryError.
     */
    static final class Ctx {
        private final java.util.IdentityHashMap<Object, Integer> ids = new java.util.IdentityHashMap<>();
        final java.util.Map<String, Object[]> lists = new java.util.HashMap<>();
        final java.util.Map<String, Exp> exps = new java.util.HashMap<>();
        long listResolved, expanded;

        String id(Object o) {
            if (o == null) return "-";
            Integer v = ids.get(o);
            if (v == null) { v = ids.size(); ids.put(o, v); }
            return Integer.toString(v);
        }
    }

    /** Ergebnis einer Stückfolge (geteilt zwischen Darstellungen mit gleicher Eingabe). */
    static final class Exp {
        String excluded;
        long[] runT, runD;
        int[] runOrd, runCnt;
        int first, count;
        BigDecimal covered = BigDecimal.ZERO;
        long maxD;
        static Exp excl(String why) { Exp e = new Exp(); e.excluded = why; return e; }
    }

    /** Stückfolge einer Darstellung: Läufe, Fenster der Periode, Abdeckung, Grenzen (gleiche Eingabe → geteiltes Ergebnis). */
    private static void expand(DashManifest m, Representation r, Ctx ctx) throws DashException {
        String key = ctx.id(r.tlRaw) + "|" + r.tlCount + "|" + ctx.id(r.list) + "|" + r.addressing + "|" + r.duration + "|" + r.timescale + "|"
            + r.pto + "|" + r.startNumber + "|" + r.period;
        Exp e = ctx.exps.get(key);
        if (e == null) {
            e = compute(m, r, ctx);
            ctx.exps.put(key, e);
        }
        r.excluded = e.excluded;
        if (e.excluded != null) return;
        r.runT = e.runT; r.runD = e.runD; r.runOrd = e.runOrd; r.runCnt = e.runCnt;
        r.first = e.first; r.count = e.count; r.covered = e.covered; r.maxSegmentTicks = e.maxD;
        if (r.media != null) {
            int maxLen = r.media.prefix.length() + r.media.suffix.length() + Math.max(r.media.width, 16);
            if (maxLen > de.evp.helfer.hls.HlsUri.MAX_URI_CHARS) r.excluded = "uri-length";
        }
    }

    private static Exp compute(DashManifest m, Representation r, Ctx ctx) throws DashException {
        Period p = m.periods.get(r.period);
        if (p.effectiveDuration == null) return Exp.excl("duration-unknown");
        long ts = r.timescale == null ? 1 : r.timescale;
        long pto = r.pto == null ? 0 : r.pto;
        BigDecimal pdTicks = p.effectiveDuration.multiply(BigDecimal.valueOf(ts));
        List<long[]> runs = new ArrayList<>();   // {t, d, cnt}
        int total = 0;
        if (r.tlRaw != null) {
            long end = -1;
            for (int k = 0; k < r.tlCount; k++) {
                long t = r.tlRaw[k * 3], d = r.tlRaw[k * 3 + 1], rr = r.tlRaw[k * 3 + 2];
                if (d <= 0) return Exp.excl("zero-duration");
                if (t == DashXml.NO_T) t = k == 0 ? 0 : end;
                else if (k > 0 && t != end) return Exp.excl(t > end ? "timeline-gap" : "timeline-overlap");
                long cnt;
                if (rr >= 0) {
                    cnt = rr + 1;
                } else if (k + 1 < r.tlCount) {
                    long nt = r.tlRaw[(k + 1) * 3];
                    if (nt == DashXml.NO_T || nt <= t || (nt - t) % d != 0) return Exp.excl("timeline-repeat");
                    cnt = (nt - t) / d;
                } else {
                    // offene Wiederholung bis zum Ende der Periode (in Medienzeit: pto + Dauer)
                    BigDecimal left = pdTicks.add(BigDecimal.valueOf(pto)).subtract(BigDecimal.valueOf(t));
                    if (left.signum() <= 0) return Exp.excl("timeline-repeat");
                    BigDecimal c = left.divide(BigDecimal.valueOf(d), 0, RoundingMode.CEILING);
                    if (c.compareTo(BigDecimal.valueOf(DashLimits.MAX_SEGMENTS_PER_TRACK)) > 0) return Exp.excl("segments");
                    cnt = c.longValue();
                }
                if (cnt > DashLimits.MAX_SEGMENTS_PER_TRACK || total + cnt > DashLimits.MAX_SEGMENTS_PER_TRACK) return Exp.excl("segments");
                try { end = DashNum.addExact(t, DashNum.mulExact(d, cnt)); } catch (DashException x) { return Exp.excl("number-range"); }
                runs.add(new long[] {t, d, cnt});
                total += (int) cnt;
            }
            if (total == 0) return Exp.excl("timeline-empty");
            if (r.list != null && r.list.size() != total) return Exp.excl("list-timeline");
        } else if (r.list != null && r.addressing == Addressing.LIST_SINGLE) {
            // ein einziges Stück über die ganze Periode
            BigDecimal d = pdTicks.setScale(0, RoundingMode.CEILING);
            if (d.compareTo(BigDecimal.valueOf(DashLimits.MAX_SAFE)) > 0) return Exp.excl("number-range");
            runs.add(new long[] {pto, Math.max(1, d.longValue()), 1});
            total = 1;
        } else {
            long d = r.duration == null ? 0 : r.duration;
            if (d <= 0) return Exp.excl("zero-duration");
            BigDecimal c = pdTicks.divide(BigDecimal.valueOf(d), 0, RoundingMode.CEILING);
            if (c.signum() <= 0) return Exp.excl("segments");
            if (c.compareTo(BigDecimal.valueOf(DashLimits.MAX_SEGMENTS_PER_TRACK)) > 0) return Exp.excl("segments");
            int cnt = c.intValue();
            if (r.list != null && r.list.size() != cnt) return Exp.excl("coverage-mismatch");
            try { DashNum.addExact(pto, DashNum.mulExact(d, cnt)); } catch (DashException x) { return Exp.excl("number-range"); }
            runs.add(new long[] {pto, d, cnt});
            total = cnt;
        }
        // Gesamtgrenze der Auswertung über alle Darstellungen (geteilte Ergebnisse zählen einmal)
        ctx.expanded += total;
        if (ctx.expanded > DashLimits.MAX_EXPANDED_SEGMENTS) throw new DashException("limits");
        // Stückzahlen und -werte innerhalb der Grenzen? ($Number$: startNumber + Ordinalzahl; $Time$: bereits geprüft)
        long start = r.startNumber == null ? 1 : r.startNumber;
        try { DashNum.addExact(start, total); } catch (DashException x) { return Exp.excl("number-range"); }
        Exp e = new Exp();
        e.runT = new long[runs.size()]; e.runD = new long[runs.size()]; e.runOrd = new int[runs.size()]; e.runCnt = new int[runs.size()];
        int ord = 0;
        long maxD = 0;
        for (int i = 0; i < runs.size(); i++) {
            e.runT[i] = runs.get(i)[0]; e.runD[i] = runs.get(i)[1]; e.runCnt[i] = (int) runs.get(i)[2]; e.runOrd[i] = ord;
            ord += e.runCnt[i];
            maxD = Math.max(maxD, e.runD[i]);
        }
        e.maxD = maxD;
        // Fenster der Periode: Stück k mit Präsentationsintervall [t−pto, t−pto+d) überlappt [0, Dauer).
        // Für ganze Zahlen s gilt s < Dauer ⇔ s < ⌈Dauer⌉ und e ≤ Dauer ⇔ e ≤ ⌊Dauer⌋ – so genügt long-Arithmetik.
        long ceil = pdTicks.setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
        long floor = pdTicks.setScale(0, RoundingMode.FLOOR).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
        int first = -1, last = -1;
        long full = 0;
        BigDecimal part = BigDecimal.ZERO;
        for (int i = 0; i < runs.size(); i++) {
            for (int j = 0; j < e.runCnt[i]; j++) {
                long s0 = e.runT[i] + (long) j * e.runD[i] - pto, e0 = s0 + e.runD[i];
                if (e0 <= 0 || s0 >= ceil) continue;
                int o = e.runOrd[i] + j;
                if (first < 0) first = o;
                last = o;
                long from = Math.max(s0, 0);
                if (e0 <= floor) {
                    try { full = Math.addExact(full, e0 - from); } catch (ArithmeticException x) { return Exp.excl("number-range"); }
                } else part = part.add(pdTicks.subtract(BigDecimal.valueOf(from)));
            }
        }
        if (first < 0) return Exp.excl("timeline-empty");
        e.first = first;
        e.count = last - first + 1;
        e.covered = BigDecimal.valueOf(full).add(part).divide(BigDecimal.valueOf(ts), 9, RoundingMode.HALF_EVEN);
        BigDecimal miss = p.effectiveDuration.subtract(e.covered).abs();
        if (miss.compareTo(BigDecimal.valueOf(DashLimits.COVERAGE_TOLERANCE_SECONDS)) > 0) return Exp.excl("coverage-mismatch");
        if (BigDecimal.valueOf(maxD).compareTo(BigDecimal.valueOf(DashLimits.MAX_SEGMENT_SECONDS).multiply(BigDecimal.valueOf(ts))) > 0) return Exp.excl("segment-duration");
        return e;
    }
}
