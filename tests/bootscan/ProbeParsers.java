package de.evp.helfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NUR DIAGNOSE. Reine Java-Parser der Boot-Scan-/A/B-Probe (ohne Android-Abhängigkeit, daher auch ohne Gerät prüfbar:
 * tests/bootscan/ProbeParsersCheck.java, tests/bootscan/test_probe_parsers.py). Wird wie MediaStoreBootScanProbeTest nur im
 * Diagnose-Job in die Test-APK kopiert.
 *
 * Fail-closed (Codex-NO-GO 2, P1-3): eine Antwort ist nur gültig mit vollständigem Rahmen, lückenlosen Zeilennummern
 * 0..n-1, eindeutigen IDs bzw. Pfaden und – wenn leer – ausdrücklichem "No result found.". Alles andere liefert null mit
 * einem Status "bad:…"; ein solcher Abzug ist kein Beleg.
 */
public final class ProbeParsers {
    private ProbeParsers() { }

    static final Pattern ROW = Pattern.compile("^Row: (\\d+) _id=(\\d+), owner_package_name=([^,]*), is_pending=(\\d+), is_trashed=(\\d+), "
        + "_data=([^,]*), _display_name=([^,]*), _size=([^,]*)$");
    static final Pattern FILE = Pattern.compile("^([0-9a-f]{64}) (\\d+) (/storage/\\S+)$");

    static String clean(String v) { return v == null ? "NULL" : v.replace(' ', '_').replace('|', '_'); }

    /** Ausgabe von bootscan-q.sh -> id -> owner|pending|trashed|path, oder null (status[0] = bad:…). */
    public static Map<String, String> parsePriv(List<String> lines, String[] status) {
        if (lines.size() < 2 || !lines.get(0).equals("Q-BEGIN") || !lines.get(lines.size() - 1).equals("Q-END rc=0")) {
            status[0] = "bad:frame-" + (lines.isEmpty() ? "empty" : clean(lines.get(lines.size() - 1)).replace('=', '-'));
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        int none = 0, rows = 0;
        for (String l : lines.subList(1, lines.size() - 1)) {
            if (l.equals("No result found.")) { none++; continue; }
            Matcher m = ROW.matcher(l);
            if (!m.matches()) { status[0] = "bad:line"; return null; }
            if (Integer.parseInt(m.group(1)) != rows) { status[0] = "bad:rownum"; return null; }   // fehlende oder doppelte Zeile
            rows++;
            if (out.put(m.group(2), clean(m.group(3)) + "|" + m.group(4) + "|" + m.group(5) + "|" + clean(m.group(6))) != null) {
                status[0] = "bad:dup-id";
                return null;
            }
        }
        if (none > 1 || (none == 1 && rows > 0)) { status[0] = "bad:mixed"; return null; }
        // leere Antwort nur, wenn der Provider ausdrücklich "No result found." meldete (sonst unvollständige Ausgabe)
        if (none == 0 && rows == 0) { status[0] = "bad:empty"; return null; }
        status[0] = "ok";
        return out;
    }

    /** Ausgabe von bootscan-f.sh -> path -> size|sha256, oder null. */
    public static Map<String, String> parseDisk(List<String> lines, String[] status) {
        if (lines.size() < 2 || !lines.get(0).equals("F-BEGIN") || !lines.get(lines.size() - 1).equals("F-END rc=0")) {
            status[0] = "bad:frame";
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        for (String l : lines.subList(1, lines.size() - 1)) {
            Matcher m = FILE.matcher(l);
            if (!m.matches()) { status[0] = "bad:line"; return null; }
            if (out.put(m.group(3), m.group(2) + "|" + m.group(1)) != null) { status[0] = "bad:dup-path"; return null; }
        }
        status[0] = "ok";
        return out;
    }

    public static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte d : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", d));
        return s.toString();
    }

    /** SHA-256 (erste 16 Hex-Zeichen) über "schlüssel|wert" je Zeile, nach Schlüssel sortiert, mit "\n" verbunden. */
    public static String digest(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(m).entrySet()) { if (b.length() > 0) b.append('\n'); b.append(e.getKey()).append('|').append(e.getValue()); }
        try { return sha(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
