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
        List<Map<String, String>> rows = rows(new File(g, "gate-v1-vectors.tsv"));
        Set<String> files = new TreeSet<>();
        for (File f : g.listFiles()) if (f.getName().endsWith(".mpd")) files.add(f.getName());
        Set<String> named = new TreeSet<>();
        for (Map<String, String> r : rows) named.add(r.get("datei"));
        check("dash-gate/1: " + rows.size() + " Vektoren, jede Fixture genau einmal", rows.size() == 41 && named.equals(files), files);
        for (Map<String, String> r : rows) {
            byte[] body = Files.readAllBytes(new File(g, r.get("datei")).toPath());
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(body));
            Map<String, String> got = new LinkedHashMap<>();
            if (!sha.equals(r.get("sha256"))) { check(r.get("datei") + ": Fixture unverändert (SHA-256)", false, sha); continue; }
            String want = r.get("code");
            DashManifest m;
            try { m = DashManifest.parse(body, DOC); }
            catch (DashException e) {
                got.put("code", "mpd_" + wire(e.reason));
                check(r.get("datei") + " → " + want + " (Lesen)", got.get("code").equals(want), got);
                continue;
            }
            DashProfile.Verdict v = DashProfile.check(m);
            String code = "ok", spur = "-", detail = "-";
            try { DashPlan.build(m); }
            catch (DashException e) {
                code = wire(e.reason);
                if (!v.ok && (v.reason.equals("dash-codec") || v.reason.equals("dash-addressing") || v.reason.equals("dash-unsupported"))) {
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
            if (!r.get("adressierung").equals("-")) {
                String ad = "?";
                for (DashManifest.Representation rep : m.reps) if (DashProfile.kindOf(m.sets.get(rep.set), rep.mimeType).equals("video")) { ad = rep.addressing.name(); break; }
                got.put("adressierung", ad);
                ok &= ad.equals(r.get("adressierung"));
            }
            check(r.get("datei") + " → " + want + (engine ? " (Plan gelingt, Ablehnung bei den Optionen)" : "") + (r.get("detail").equals("-") ? "" : " (" + r.get("spur") + ": " + r.get("detail") + ")"), ok, got + " erwartet " + r);
        }
        System.out.println("ERGEBNIS: " + passed + " von " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
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
