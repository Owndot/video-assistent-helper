package de.evp.helfer.dash;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * S4b-5: gemeinsame Golden-Vektoren dash-gate/1 (fixtures/gate, byte-gleich in Kadri tests/web/dash/gate). Die Erwartungen
 * stehen von Hand in gate-v1-vectors.tsv. Hier die Kernebene des Helfers: Lesen (DashManifest), S3-Profil (DashProfile) und
 * Plan (DashPlan) – Grund, blockierende Spur und ihre Gründe, Stücke/Abdeckung je Darstellung, Adressierung. Die Gründe, die
 * erst bei den Optionen des Koordinators entstehen (audio_codec, dash_no_audio, segments_limit), prüft der dashplan-Weg
 * (tests/java/dashdl TestDashEngine) – hier muss der Plan dafür gelingen.
 */
public final class TestDashGate {
    static int passed, failed;
    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }
    static final String DOC = "https://media.example/s4b5/manifest.mpd";
    static final Set<String> ENGINE = new HashSet<>(Arrays.asList("audio_codec", "dash_no_audio", "segments_limit"));

    static String wire(String r) { return r.replace('-', '_'); }

    public static void main(String[] args) throws Exception {
        File g = new File(args[0], "gate");
        // S4b-6: Querprüfung des Strukturfingerabdrucks über alle Alternativen (BaseSelection.FIRST) mit Kadri – Werte vom
        // Helfer berechnet (gate-v1-fp.tsv); --write-fp schreibt sie neu (nur bewusst, nach Formatänderung)
        fpTable(g, args.length > 1 && args[1].equals("--write-fp"));
        List<Map<String, String>> rows = rows(new File(g, "gate-v1-vectors.tsv"));
        Set<String> files = new TreeSet<>();
        for (File f : g.listFiles()) if (f.getName().endsWith(".mpd")) files.add(f.getName());
        Set<String> named = new TreeSet<>();
        for (Map<String, String> r : rows) named.add(r.get("datei"));
        check("dash-gate/1: " + rows.size() + " Vektoren über " + files.size() + " Fixtures, jede Fixture mindestens einmal", rows.size() == 69 && files.size() == 56 && named.equals(files), files);
        for (Map<String, String> r : rows) {
            byte[] body = Files.readAllBytes(new File(g, r.get("datei")).toPath());
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(body));
            Map<String, String> got = new LinkedHashMap<>();
            if (!sha.equals(r.get("sha256"))) { check(r.get("datei") + ": Fixture unverändert (SHA-256)", false, sha); continue; }
            String want = r.get("code"), alt = r.get("auswahl"), name = r.get("datei") + (alt.equals("-") ? "" : " [" + alt + "]");
            DashManifest m;
            // wie dashplan: v1 ohne Auswahl; v2 erst das ganze MPD (Abdruck, alle Alternativen), dann die gewählte Projektion
            try {
                if (alt.equals("-")) m = DashManifest.parse(body, DOC);
                else { DashManifest.parse(body, DOC, BaseSelection.FIRST); m = DashManifest.parse(body, DOC, BaseSelection.parse(alt)); }
            }
            catch (DashException e) {
                got.put("code", "mpd_" + wire(e.reason));
                check(name + " → " + want + " (Lesen)", got.get("code").equals(want), got);
                continue;
            }
            catch (RuntimeException e) {   // ein Laufzeitfehler beim Lesen ist nie ein erwartetes Ergebnis
                check(name + " → " + want + " (Lesen ohne Laufzeitfehler)", false, e);
                continue;
            }
            DashProfile.Verdict v = DashProfile.check(m);
            String code = "ok", spur = "-", detail = "-";
            try { DashPlan.build(m); }
            catch (DashException e) {
                code = wire(e.reason);
                if (!v.ok && (v.reason.equals("dash-codec") || v.reason.equals("dash-addressing") || v.reason.equals("dash-unsupported") || v.reason.equals("dash-baseurl-undetermined"))) {
                    spur = v.video.isEmpty() ? "video" : "audio";
                    TreeSet<String> d = new TreeSet<>();
                    for (Map.Entry<Integer, String> x : v.excluded.entrySet()) {
                        DashManifest.Representation rep = m.reps.get(x.getKey());
                        if (DashProfile.kindOf(m.sets.get(rep.set), rep.mimeType).equals(spur)) d.add(x.getValue());
                    }
                    detail = String.join(",", d);
                }
            }
            // Koordinator-Gründe: hier muss der Plan gelingen, die Ablehnung kommt erst bei den Optionen (dashplan)
            boolean engine = ENGINE.contains(want);
            got.put("code", code);
            boolean ok = engine ? code.equals("ok") : code.equals(want);
            if (!engine) { ok &= spur.equals(r.get("spur")) && detail.equals(r.get("detail")); got.put("spur", spur); got.put("detail", detail); }
            if (!r.get("abdeckung").equals("-")) {
                List<String> vals = new ArrayList<>();
                for (DashManifest.Representation rep : m.reps) {
                    String kind = DashProfile.kindOf(m.sets.get(rep.set), rep.mimeType);
                    if (!kind.equals("video") && !kind.equals("audio")) continue;
                    vals.add(rep.id + "=" + (rep.excluded != null ? "!" + rep.excluded : rep.count + "/" + plain(rep.covered)));
                }
                String a = String.join(",", vals);
                if (r.get("abdeckung").startsWith("*=")) {
                    Set<String> u = new HashSet<>();
                    for (String x : vals) u.add(x.substring(x.indexOf('=') + 1));
                    if (u.size() == 1) a = "*=" + u.iterator().next();
                }
                got.put("abdeckung", a);
                ok &= a.equals(r.get("abdeckung"));
            }
            if (!r.get("basen").equals("-")) {
                List<String> vals = new ArrayList<>();
                for (DashManifest.Representation rep : m.reps) {
                    String kind = DashProfile.kindOf(m.sets.get(rep.set), rep.mimeType);
                    if (kind.equals("video") || kind.equals("audio")) vals.add(rep.id + "=" + rep.baseAlternatives);
                }
                got.put("basen", String.join(",", vals));
                ok &= got.get("basen").equals(r.get("basen"));
            }
            if (!r.get("adressierung").equals("-")) {
                String ad = "?";
                for (DashManifest.Representation rep : m.reps) if (DashProfile.kindOf(m.sets.get(rep.set), rep.mimeType).equals("video")) { ad = rep.addressing.name(); break; }
                got.put("adressierung", ad);
                ok &= ad.equals(r.get("adressierung"));
            }
            check(name + " → " + want + (engine ? " (Plan gelingt, Ablehnung bei den Optionen)" : "") + (r.get("detail").equals("-") ? "" : " (" + r.get("spur") + ": " + r.get("detail") + ")"), ok, got + " erwartet " + r);
        }
        bigAlternatives();
        System.out.println("ERGEBNIS: " + passed + " von " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    static void fpTable(File g, boolean write) throws Exception {
        StringBuilder out = new StringBuilder("# dash-gate/1: Strukturfingerabdruck kadri-dash-fp/1 je Fixture über das ganze MPD (alle BaseURL-Alternativen)\n"
            + "# Werte vom Helfer berechnet (DashFingerprint.hex(DashManifest.parse(…, BaseSelection.FIRST))); Kadri muss sie exakt treffen.\n");
        TreeSet<String> names = new TreeSet<>();
        for (File f : g.listFiles()) if (f.getName().endsWith(".mpd")) names.add(f.getName());
        for (String n : names) {
            String v;
            try { v = DashFingerprint.hex(DashManifest.parse(Files.readAllBytes(new File(g, n).toPath()), DOC, BaseSelection.FIRST)); }
            catch (DashException e) { v = "ERROR:" + e.reason; }
            out.append(n).append('\t').append(v).append('\n');
        }
        File t = new File(g, "gate-v1-fp.tsv");
        if (write) { Files.write(t.toPath(), out.toString().getBytes(StandardCharsets.UTF_8)); System.out.println("geschrieben: " + t); return; }
        check("dash-gate/1: Fingerabdrücke aller Fixtures (alle Alternativen) unverändert gegenüber gate-v1-fp.tsv",
            t.isFile() && new String(Files.readAllBytes(t.toPath()), StandardCharsets.UTF_8).equals(out.toString()), t);
    }

    /** S4b-6: Grenzen und Aufwand bei großen MPDs mit Alternativen (64 MB Heap; wie Kadri test_dash_alt.py). */
    static void bigAlternatives() throws Exception {
        String T = "<SegmentTemplate timescale=\"12800\" initialization=\"$RepresentationID$/i.mp4\" media=\"$RepresentationID$/$Number$.m4s\"><SegmentTimeline><S t=\"0\" d=\"51200\" r=\"4\"/></SegmentTimeline></SegmentTemplate>";
        StringBuilder reps = new StringBuilder();
        for (int i = 0; i < 4095; i++) {
            reps.append("<Representation id=\"v").append(i).append("\" bandwidth=\"").append(90000 + i).append("\" width=\"320\" height=\"180\">");
            for (int j = 0; j < 8; j++) reps.append("<BaseURL>https://cdn-").append(j).append(".example/x/</BaseURL>");
            reps.append("</Representation>");
        }
        String big = "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT20S\"><Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" codecs=\"avc1.4d401e\">"
            + T + reps + "</AdaptationSet><AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" codecs=\"mp4a.40.2\">" + T + "<Representation id=\"a\" bandwidth=\"48000\"/></AdaptationSet></Period></MPD>";
        byte[] b = big.getBytes(StandardCharsets.UTF_8);
        long t0 = System.nanoTime();
        DashManifest all = DashManifest.parse(b, DOC, BaseSelection.FIRST);
        String fp = DashFingerprint.hex(all);
        DashManifest sel = DashManifest.parse(b, DOC, BaseSelection.parse("r0:1"));
        String code;
        try { DashPlan.build(sel); code = "ok"; } catch (DashException e) { code = e.reason; }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("Groß: 4096 Darstellungen × 8 BaseURL (" + b.length / 1000 + " kB): Lesen mit allen Alternativen, Abdruck und Projektion einer Auswahl in " + ms + " ms (< 5 s, 64 MB Heap)",
            fp.length() == 64 && "ok".equals(code) && ms < 5000 && all.reps.get(0).baseAlternatives == 8, code + " " + ms);
        String over = big.replaceFirst("<BaseURL>https://cdn-0.example/x/</BaseURL>", "<BaseURL>https://cdn-0.example/x/</BaseURL><BaseURL>https://cdn-9.example/x/</BaseURL>");
        String got;
        try { DashManifest.parse(over.getBytes(StandardCharsets.UTF_8), DOC, BaseSelection.FIRST); got = "accepted"; } catch (DashException e) { got = e.reason; }
        check("… mit 9 BaseURL an einer Darstellung → kontrolliert baseurl-limits", "baseurl-limits".equals(got), got);
    }

    static String plain(BigDecimal b) { return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString(); }

    static List<Map<String, String>> rows(File f) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        String[] head = null;
        for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] c = l.split("\t", -1);
            if (head == null) { head = c; continue; }
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < head.length; i++) m.put(head[i], c[i]);
            out.add(m);
        }
        return out;
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
}
