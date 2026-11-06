package de.evp.p4a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** P4a: Messwerte eines Remux-Laufs (nur Zahlen/Typen, keine Adressen). */
public final class Result {
    public final Map<String, Object> v = new LinkedHashMap<>();
    public final List<String> tracks = new ArrayList<>();

    public Result put(String k, Object x) { v.put(k, x); return this; }

    public String json() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : v.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append('"').append(e.getKey()).append("\":");
            Object x = e.getValue();
            if (x instanceof Number || x instanceof Boolean) b.append(x);
            else b.append('"').append(String.valueOf(x).replace("\\", "\\\\").replace("\"", "'")).append('"');
        }
        b.append(",\"tracks\":[");
        for (int i = 0; i < tracks.size(); i++) b.append(i > 0 ? "," : "").append('"').append(tracks.get(i).replace("\"", "'")).append('"');
        return b.append("]}").toString();
    }

    static String us(long t) { return String.format(Locale.ROOT, "%.6f", t / 1e6); }
}
