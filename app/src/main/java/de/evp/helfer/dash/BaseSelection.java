package de.evp.helfer.dash;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S4b-6: Auswahl unter mehreren BaseURL-Alternativen eines MPD (ISO/IEC 23009-1 5.6: mehrere BaseURL auf einer Ebene sind
 * gleichwertige Alternativen, z. B. verschiedene CDN). Eine Auswahl nennt je Element mit MEHREREN BaseURL den Index der
 * gewählten (0-basiert, Dokumentreihenfolge):
 * <pre>  m:1          MPD-Ebene
 *   p0:1         Periode 0
 *   s3:0         AdaptationSet 3 (Nummer über alle Perioden, Dokumentreihenfolge)
 *   r7:1         Representation 7 (ebenso)</pre>
 * Mehrere Einträge mit ';' getrennt, aufsteigend sortiert, jeder Schlüssel höchstens einmal. Kadri leitet die Auswahl aus
 * beobachteten Stückfolgen ab; der Helfer prüft sie gegen das NEU geladene MPD: jeder Eintrag muss ein Element mit mehreren
 * BaseURL und einen vorhandenen Index treffen ("alt-invalid"), sonst Ablehnung. Ein Host kann so nie über den Parameter
 * eingeführt werden – nur eine im MPD erklärte Alternative ausgewählt werden.
 *
 * FIRST: jeweils die erste Alternative (für den Strukturfingerabdruck und Kadris Vorprüfung vor der Bindung).
 */
public final class BaseSelection {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_CHARS = 512;
    private static final Pattern ENTRY = Pattern.compile("(m|[psr](0|[1-9][0-9]{0,3})):(0|[1-9])");

    public static final BaseSelection FIRST = new BaseSelection(Collections.emptyMap(), true);

    private final Map<String, Integer> pick;
    private final boolean first;
    private final Set<String> used = new HashSet<>();

    private BaseSelection(Map<String, Integer> pick, boolean first) { this.pick = pick; this.first = first; }

    /** Streng: Form, Länge, Reihenfolge und Eindeutigkeit wie oben; sonst "alt-invalid". Leer ist ungültig. */
    public static BaseSelection parse(String alt) throws DashException {
        if (alt == null || alt.isEmpty() || alt.length() > MAX_CHARS) throw new DashException("alt-invalid");
        Map<String, Integer> m = new LinkedHashMap<>();
        String prev = null;
        for (String e : alt.split(";", -1)) {
            Matcher x = ENTRY.matcher(e);
            if (!x.matches()) throw new DashException("alt-invalid");
            String key = x.group(1);
            if (m.containsKey(key) || (prev != null && order(prev, key) >= 0)) throw new DashException("alt-invalid");
            m.put(key, Integer.parseInt(x.group(3)));
            prev = key;
            if (m.size() > MAX_ENTRIES) throw new DashException("alt-invalid");
        }
        return new BaseSelection(m, false);
    }

    /** Sortierung der Schlüssel: m, dann p, s, r jeweils numerisch. */
    static int order(String a, String b) {
        int ka = "mpsr".indexOf(a.charAt(0)), kb = "mpsr".indexOf(b.charAt(0));
        if (ka != kb) return Integer.compare(ka, kb);
        if (a.length() == 1) return 0;
        return Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1)));
    }

    boolean isFirst() { return first; }

    /** Index für ein Element mit {@code count} BaseURL: gewählt, 0 bei FIRST, -1 = nicht bestimmt. */
    int choose(String key, int count) throws DashException {
        if (first) return 0;
        Integer i = pick.get(key);
        if (i == null) return -1;
        if (i >= count) throw new DashException("alt-invalid");
        used.add(key);
        return i;
    }

    /** Jeder Eintrag muss ein Element mit mehreren BaseURL getroffen haben (kein Eintrag für Nicht-Vorhandenes). */
    void checkAllUsed() throws DashException {
        if (!first && !used.containsAll(pick.keySet())) throw new DashException("alt-invalid");
    }
}
