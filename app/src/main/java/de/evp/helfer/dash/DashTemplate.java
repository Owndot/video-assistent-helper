package de.evp.helfer.dash;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3a: SegmentTemplate-Vorlagen (ISO/IEC 23009-1, 5.3.9.4.4). Erlaubt: $$, $RepresentationID$ (ohne Format),
 * $Bandwidth$, $Number$, $Time$ (je optional %0[width]d, width 1–20). Alles andere – $SubNumber$, unbekannte
 * Bezeichner, Format an $RepresentationID$, nicht geschlossenes "$" – wird abgelehnt ("template").
 *
 * Eine Medienvorlage enthält genau einen Zahlenplatzhalter ($Number$ oder $Time$), eine Init-Vorlage keinen.
 * Ergebnis der Auflösung: kanonischer Präfix + Zahl (mit Breite) + Suffix – aufgelöst mit einer Marke an der Stelle
 * des Platzhalters (Kadri, src/038, nutzt dafür eine eigene Marke). Marke und Zahl bestehen nur aus Ziffern und können
 * keine Punktsegmente bilden; die Auflösung einer konkreten Adresse ist daher identisch mit Präfix + Zahl + Suffix.
 * Die Marke wird je Vorlage so gewählt, dass sie mit nichts anderem verwechselt werden kann (siehe media()).
 */
final class DashTemplate {
    /** Platzhalter im eingesetzten Text vor der Auflösung (Steuerzeichen: in Adressen nie erlaubt, siehe DashUrl). */
    private static final char SLOT = '\u0001';
    private static final Pattern FMT = Pattern.compile("%0([1-9][0-9]?)d");
    /** Zeichen, die $RepresentationID$ einsetzen darf (unreserviert und unschädliche Sub-Delims, kein / ? # %). */
    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9._~=,;!*()+&:@-]{1,256}");

    enum Kind { NONE, NUMBER, TIME }

    /** Aufgelöste Medienvorlage. */
    static final class Media {
        final Kind kind;
        final int width;          // 0 = ohne Format
        final String prefix, suffix;
        Media(Kind kind, int width, String prefix, String suffix) { this.kind = kind; this.width = width; this.prefix = prefix; this.suffix = suffix; }

        String url(long value) {
            String v = Long.toString(value);
            StringBuilder sb = new StringBuilder(prefix.length() + suffix.length() + 24).append(prefix);
            for (int i = v.length(); i < width; i++) sb.append('0');
            return sb.append(v).append(suffix).toString();
        }

        /** Für den Fingerabdruck: Platzhalter als {Number} / {Number:05} / {Time} (geschweifte Klammern sind in Adressen abgelehnt). */
        String canon() {
            return prefix + "{" + (kind == Kind.NUMBER ? "Number" : "Time") + (width > 0 ? ":" + pad(width) : "") + "}" + suffix;
        }

        private static String pad(int w) { return (w < 10 ? "0" : "") + w; }
    }

    private DashTemplate() {}

    static void checkId(String id) throws DashException {
        if (id == null || !ID_OK.matcher(id).matches()) throw new DashException("rep-id");
    }

    /**
     * Setzt $$, $RepresentationID$ und $Bandwidth$ ein; $Number$/$Time$ werden zur Marke. Liefert [Text, Art, Breite].
     */
    private static Object[] expand(String tpl, String id, long bandwidth, boolean media) throws DashException {
        StringBuilder out = new StringBuilder();
        Kind kind = Kind.NONE;
        int width = 0, i = 0;
        while (i < tpl.length()) {
            char c = tpl.charAt(i);
            if (c != '$') { out.append(c); i++; continue; }
            int end = tpl.indexOf('$', i + 1);
            if (end < 0) throw new DashException("template");
            String ident = tpl.substring(i + 1, end);
            i = end + 1;
            if (ident.isEmpty()) { out.append('$'); continue; }
            String name = ident, fmt = null;
            int pc = ident.indexOf('%');
            if (pc >= 0) { name = ident.substring(0, pc); fmt = ident.substring(pc); }
            int w = 0;
            if (fmt != null) {
                Matcher m = FMT.matcher(fmt);
                if (!m.matches()) throw new DashException("template");
                w = Integer.parseInt(m.group(1));
                if (w > DashLimits.MAX_FORMAT_WIDTH) throw new DashException("template");
            }
            switch (name) {
                case "RepresentationID":
                    if (fmt != null) throw new DashException("template");
                    checkId(id);
                    out.append(id);
                    break;
                case "Bandwidth": {
                    String v = Long.toString(bandwidth);
                    for (int k = v.length(); k < w; k++) out.append('0');
                    out.append(v);
                    break;
                }
                case "Number":
                case "Time":
                    if (!media || kind != Kind.NONE) throw new DashException("template");
                    kind = name.equals("Number") ? Kind.NUMBER : Kind.TIME;
                    width = w;
                    out.append(SLOT);
                    break;
                default:
                    throw new DashException("template");
            }
        }
        if (media && kind == Kind.NONE) throw new DashException("template");
        return new Object[] {out.toString(), kind, width};
    }

    static Media media(String tpl, String id, long bandwidth, String base) throws DashException {
        Object[] e = expand(tpl, id, bandwidth, true);
        // S3a-Fix P1: Die Marke wird so gewählt, dass sie nirgends sonst vorkommen kann – weder in der Vorlage noch in
        // eingesetzten Werten ($RepresentationID$, $Bandwidth$) noch in der Basis: "8" + (längste 9er-Folge + 1) × "9" + "8".
        // Die Auflösung verbindet Basis und Referenz nur an "/" oder "?" und ändert keine Ziffern; eine 9er-Folge dieser
        // Länge kann daher nur aus der Marke stammen. Gefunden wird sie über genau diese Folge (eindeutig).
        String text = (String) e[0];
        int run = Math.max(longestNines(text), longestNines(base)) + 1;
        StringBuilder nines = new StringBuilder(run);
        for (int i = 0; i < run; i++) nines.append('9');
        String mark = "8" + nines + "8";
        String resolved = DashUrl.resolve(base, text.replace(String.valueOf(SLOT), mark));
        int n9 = resolved.indexOf(nines.toString());
        if (n9 < 1 || !resolved.startsWith(mark, n9 - 1)) throw new DashException("template");   // Invariante (nie verletzt)
        int at = n9 - 1;
        if (at < resolved.indexOf("://") + 3 || at < DashUrl.origin(resolved).length()) throw new DashException("template");   // Marke im Host
        return new Media((Kind) e[1], (Integer) e[2], resolved.substring(0, at), resolved.substring(at + mark.length()));
    }

    private static int longestNines(String s) {
        int best = 0, cur = 0;
        for (int i = 0; i < s.length(); i++) { cur = s.charAt(i) == '9' ? cur + 1 : 0; if (cur > best) best = cur; }
        return best;
    }

    static String init(String tpl, String id, long bandwidth, String base) throws DashException {
        Object[] e = expand(tpl, id, bandwidth, false);
        return DashUrl.resolve(base, (String) e[0]);
    }
}
