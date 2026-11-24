package de.evp.helfer.dash;

/**
 * S3a: feste Grenzen für das Lesen eines MPD und für jeden daraus gebauten Plan. Strukturgrenzen wie in Kadri
 * (src/038 DASH_LIMITS); Plan- und Zeitgrenzen wie beim HLS-Kern (HlsPlaylist/HlsPlan). Nur nach Messung ändern.
 */
public final class DashLimits {
    private DashLimits() {}

    /** Größe des MPD in Bytes (Kadri: 4e6 Zeichen; ein MPD ist ASCII-nah, Bytes ≥ Zeichen). */
    public static final int MAX_MANIFEST_BYTES = 4_000_000;
    /** Alle Elemente, auch fremde Namensräume, S und SegmentURL. */
    public static final int MAX_ELEMENTS = 200_000;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_ATTRS = 64;
    public static final int MAX_ATTR_CHARS = 8192;
    /** Textinhalt (BaseURL, Label). */
    public static final int MAX_TEXT_CHARS = 4096;
    public static final int MAX_PERIODS = 64;
    public static final int MAX_SETS = 1024;
    public static final int MAX_REPS = 4096;
    /** S-Einträge aller SegmentTimelines zusammen. */
    public static final int MAX_TIMELINE_ENTRIES = 100_000;
    /** SegmentURL-Einträge aller SegmentLists zusammen. */
    public static final int MAX_LIST_ENTRIES = 100_000;
    /**
     * Über alle Darstellungen eines MPD: neu aufzulösende SegmentURL-Einträge (eine geerbte Liste je (Element, Basis)
     * nur einmal) und auszuwertende Stücke (gleiche Stückfolge nur einmal). Darüber "limits" statt Speichererschöpfung.
     */
    public static final int MAX_RESOLVED_LIST_ENTRIES = 200_000;
    public static final long MAX_EXPANDED_SEGMENTS = 1_000_000;
    /** Stücke je Spur (wie HlsPlaylist.MAX_SEGMENTS). Begrenzt auch jede Expansion (r, r=-1, @duration). */
    public static final int MAX_SEGMENTS_PER_TRACK = 20_000;
    public static final double MAX_SEGMENT_SECONDS = 3600;
    public static final double MAX_TOTAL_SECONDS = 48 * 3600;
    /** Abweichung der abgedeckten Zeit von der Periodendauer (Rundung des Packagers). */
    public static final double COVERAGE_TOLERANCE_SECONDS = 1.0;
    /** Ganze Zahlen höchstens 2^53−1: in Kadri (JavaScript) exakt darstellbar, damit beide gleich rechnen. */
    public static final long MAX_SAFE = 9_007_199_254_740_991L;
    /** @timescale ist im Standard ein vorzeichenloses 32-Bit-Feld. */
    public static final long MAX_TIMESCALE = 4_294_967_295L;
    public static final long MAX_BANDWIDTH = 1_000_000_000L;
    /** S4b-6: BaseURL-Alternativen je Element (MPD, Periode, AdaptationSet, Representation). */
    public static final int MAX_BASEURLS_PER_ELEMENT = 8;
    /** S4b-6: Kombinationen der Alternativen über die Kette je Darstellung (vor dem Zusammenfassen gleicher Adressen). */
    public static final int MAX_BASE_COMBINATIONS = 16;
    /** Breite in %0[width]d. */
    public static final int MAX_FORMAT_WIDTH = 20;
    /** Auswahlmöglichkeiten (Video × Audio) eines Plans. */
    public static final int MAX_OPTIONS = 256;
    /** Geschätzte Mediendaten je Option (wie HlsPlan.MAX_MEDIA_BYTES). */
    public static final long MAX_MEDIA_BYTES = 16L << 30;
}
