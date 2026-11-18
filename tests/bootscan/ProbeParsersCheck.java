import de.evp.helfer.ProbeParsers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prüft den Java-Providerparser der Probe ohne Gerät (Codex-NO-GO 2, P1-3). Aufruf über tests/bootscan/test_probe_parsers.py.
 * Gibt je Fall "CASE <name> <status> <anzahl>" aus und am Ende die Prüfsummen für den Abgleich mit Python.
 */
public class ProbeParsersCheck {
    static String row(int n, String id, String owner, String path) {
        return "Row: " + n + " _id=" + id + ", owner_package_name=" + owner + ", is_pending=0, is_trashed=0, _data=" + path
            + ", _display_name=" + path.substring(path.lastIndexOf('/') + 1) + ", _size=262144";
    }

    static void priv(String name, String... body) {
        List<String> l = new ArrayList<>(Arrays.asList(body));
        String[] st = {"?"};
        Map<String, String> m = ProbeParsers.parsePriv(l, st);
        System.out.println("CASE " + name + " " + st[0] + " " + (m == null ? -1 : m.size()));
    }

    static void disk(String name, String... body) {
        String[] st = {"?"};
        Map<String, String> m = ProbeParsers.parseDisk(new ArrayList<>(Arrays.asList(body)), st);
        System.out.println("CASE " + name + " " + st[0] + " " + (m == null ? -1 : m.size()));
    }

    public static void main(String[] a) {
        String p1 = "/storage/emulated/0/Download/Video-Assistent/BootScanProbe-a1-1.mp4";
        String p2 = "/storage/emulated/0/Download/Video-Assistent/BootScanProbe-a1-2.mp4";
        priv("ok", "Q-BEGIN", row(0, "11", "de.evp", p1), row(1, "12", "NULL", p2), "Q-END rc=0");
        priv("empty-explicit", "Q-BEGIN", "No result found.", "Q-END rc=0");
        priv("empty-silent", "Q-BEGIN", "Q-END rc=0");
        priv("dup-id-conflict", "Q-BEGIN", row(0, "11", "de.evp", p1), row(1, "11", "NULL", p1), "Q-END rc=0");
        priv("dup-id-same", "Q-BEGIN", row(0, "11", "de.evp", p1), row(1, "11", "de.evp", p1), "Q-END rc=0");
        priv("rownum-gap", "Q-BEGIN", row(0, "11", "de.evp", p1), row(2, "12", "de.evp", p2), "Q-END rc=0");
        priv("rownum-start", "Q-BEGIN", row(1, "11", "de.evp", p1), "Q-END rc=0");
        priv("mixed", "Q-BEGIN", "No result found.", row(0, "11", "de.evp", p1), "Q-END rc=0");
        priv("frame", "Q-BEGIN", row(0, "11", "de.evp", p1), "Q-END rc=1");
        priv("bad-line", "Q-BEGIN", "Error while accessing provider:media", "Q-END rc=0");
        String h = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        disk("disk-ok", "F-BEGIN", h + " 262144 " + p1, h + " 100 " + p2, "F-END rc=0");
        disk("disk-dup-path", "F-BEGIN", h + " 262144 " + p1, h + " 100 " + p1, "F-END rc=0");
        disk("disk-frame", "F-BEGIN", h + " 262144 " + p1, "F-END rc=1");
        Map<String, String> m = new TreeMap<>();
        m.put("1000000101", "NULL|0|0|/storage/emulated/0/Download/Video-Assistent/B-2.mp4");
        m.put("1000000100", "de.evp.helfer.channeltest|1|0|/x/.pending-1-B-1.mp4");
        System.out.println("DIGEST " + ProbeParsers.digest(m));
        System.out.println("DIGEST " + ProbeParsers.digest(new TreeMap<>()));
    }
}
