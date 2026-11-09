package de.evp.helfer.dl;

import java.util.Locale;

/**
 * P4c-3 (P1): Fortschritt eines Downloads als Zähler/Nenner, die beide verlässlich definiert sind – ohne Android-Teile,
 * damit die Anzeige am Rechner geprüft werden kann.
 *
 * <ul>
 *   <li>MP4: {@code bytes} von {@code total}; {@code total} ist die exakte Länge des Servers (Content-Length bzw.
 *       Content-Range – ohne sie startet kein MP4-Download). Prozent nur, wenn 0 ≤ bytes ≤ total; sonst nur „n MB geladen“
 *       (ein Widerspruch wird nicht auf 100 % abgeschnitten, sondern als unbekannt gezeigt).</li>
 *   <li>HLS: {@code done} von {@code of} Teilen (Video- und Tonspur zusammen, jede Ressource genau einmal) und die bisher
 *       geladenen Bytes. Eine Gesamtgröße gibt es vor dem Zusammensetzen nicht – die Schätzung aus der Bitrate des Plans
 *       wird nie als Größe oder Prozent gezeigt.</li>
 *   <li>Phasen nach dem Laden (zusammensetzen, prüfen, speichern) ohne Zahl.</li>
 * </ul>
 */
public final class Progress {
    public enum Phase { FETCH, MUX, VERIFY, SAVE }

    public final Phase phase;
    public final long bytes;
    /** Exakte Gesamtgröße oder -1 (unbekannt). */
    public final long total;
    /** HLS: fertige Teile und Anzahl Teile (0 = unbekannt); MP4: beide 0. */
    public final int done, of;

    private Progress(Phase phase, long bytes, long total, int done, int of) {
        this.phase = phase; this.bytes = Math.max(0, bytes); this.total = total > 0 ? total : -1; this.done = Math.max(0, done); this.of = Math.max(0, of);
    }

    public static Progress mp4(long bytes, long exactTotal) { return new Progress(Phase.FETCH, bytes, exactTotal, 0, 0); }

    public static Progress hls(Phase phase, int done, int of, long bytes) { return new Progress(phase, bytes, -1, done, of); }

    /** Prozent (0–100) nur aus exakter Größe und passendem Zähler, sonst -1. */
    public int percent() {
        if (phase != Phase.FETCH || total <= 0 || bytes > total) return -1;
        return (int) (100L * bytes / total);
    }

    /** Anteil der Teile, nur wenn Zähler ≤ Nenner (sonst ist einer von beiden falsch: keine Zahl). */
    public boolean partsKnown() { return phase == Phase.FETCH && of > 0 && done <= of; }

    /** Fortschrittsbalken: {max, wert}; max = 0 heißt unbestimmter Balken. */
    public int[] bar() {
        int p = percent();
        if (p >= 0) return new int[] {100, p};
        if (partsKnown()) return new int[] {of, done};
        return new int[] {0, 0};
    }

    /** Fester Text für die Benachrichtigung (nie Server-Text oder Adresse). */
    public String text(boolean de) {
        switch (phase) {
            case MUX: return de ? "Wird zusammengesetzt …" : "Assembling …";
            case VERIFY: return de ? "Wird geprüft …" : "Verifying …";
            case SAVE: return de ? "Wird gespeichert …" : "Saving …";
            default: break;
        }
        int p = percent();
        if (p >= 0) return p + " % · " + mb(bytes, de) + (de ? " von " : " of ") + mb(total, de) + " MB";
        String loaded = mb(bytes, de) + (de ? " MB geladen" : " MB loaded");
        if (partsKnown()) return done + "/" + of + (de ? " Teile · " : " parts · ") + loaded;
        return loaded;
    }

    /** Exakte Endgröße einer veröffentlichten Datei. */
    public static String size(long bytes, boolean de) { return mb(bytes, de) + " MB"; }

    /** MiB mit einer Nachkommastelle unter 100 MB (abgerundet, nie aufgerundet über den echten Wert). */
    static String mb(long n, boolean de) {
        if (n >= 100L << 20) return Long.toString(n >> 20);
        long tenths = n * 10 / (1L << 20);
        String s = (tenths / 10) + "." + (tenths % 10);
        return de ? s.replace('.', ',') : s;
    }

    @Override public String toString() { return String.format(Locale.ROOT, "%s %d/%d %d/%d", phase, done, of, bytes, total); }
}
