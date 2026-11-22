package de.evp.helfer.dashdl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dash.DashPlan;
import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsRemux;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;

/**
 * S3b: echter Zusammenbau eines DASH-VOD mit dem unveränderten App-Remuxer (Media3 1.11.1) auf dem JVM – an den
 * Dateien, die der DASH-Plan in Folge nennt (Init + Stücke je Spur), nach Fmp4Inspect. Die Ausgabe prüft der Remuxer
 * selbst (verify) und analyze.py gegen die Quelle (ffprobe/ffmpeg: Spuren, Dauer, Bilder, A/V-Versatz).
 *   java TestDashRemux FIXTURES_DIR OUT_DIR ANALYZE_PY
 */
public final class TestDashRemux {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    static List<File> files(File dir, DashPlan.Track t) {
        List<File> l = new ArrayList<>();
        l.add(new File(dir, name(t.initUrl())));
        for (int i = 0; i < t.count(); i++) l.add(new File(dir, name(t.segment(i).url)));
        return l;
    }

    static String name(String url) { return url.substring(url.lastIndexOf('/') + 1); }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0], "avc"), outDir = new File(args[1]);
        outDir.mkdirs();
        byte[] mpd = Files.readAllBytes(new File(dir, "manifest.mpd").toPath());
        DashPlan plan = DashPlan.build(DashManifest.parse(mpd, "https://cdn.example/v/manifest.mpd"));
        check("Plan aus dem ffmpeg-MPD: 2 Video- × 1 Tonspur", plan.options.size() == 2 && plan.video.size() == 2 && plan.audio.size() == 1, plan.options.size());
        for (DashPlan.Option o : plan.options) {
            List<File> v = files(dir, o.video), a = files(dir, o.audio);
            Fmp4Inspect.init(v.get(0), "video", o.video.rep.codecs);
            Fmp4Inspect.init(a.get(0), "audio", o.audio.rep.codecs);
            for (File f : v.subList(1, v.size())) Fmp4Inspect.segment(f);
            for (File f : a.subList(1, a.size())) Fmp4Inspect.segment(f);
            File out = new File(outDir, "dash-" + o.video.rep.height + ".mp4");
            Media3HlsRemuxer m = new Media3HlsRemuxer(false);
            HlsRemux.Result r = m.remux(new HlsRemux.Input(v, a, true, 1_000_000), out, () -> false);
            double vs = o.video.seconds().doubleValue(), as = o.audio.seconds().doubleValue();
            m.verify(out, r, vs, as, 4.0, () -> false);
            String an = analyze(args[2], new File(dir, "manifest.mpd"), out);
            check("Media3-Remux " + o.video.rep.height + "p + AAC: verify bestanden (" + r.videoSamples + " Bilder, " + r.audioSamples + " Tonblöcke); analyze.py OK",
                r.videoSamples == 500 && an.startsWith("PASS") && an.contains("\"fails\": []"), an.length() > 600 ? an.substring(an.length() - 600) : an);
        }
        // Negativ: Ton-Init einer anderen Spur (Video-Init) → der Remuxer lehnt ab
        DashPlan.Option o = plan.options.get(0);
        List<File> v = files(dir, o.video), wrong = new ArrayList<>(files(dir, o.audio));
        wrong.set(0, v.get(0));
        File bad = new File(outDir, "bad.mp4");
        String got;
        try { new Media3HlsRemuxer(false).remux(new HlsRemux.Input(v, wrong, true, 1_000_000), bad, () -> false); got = "accepted"; }
        catch (HlsException e) { got = e.getMessage(); }
        catch (java.io.IOException e) { got = "io:" + e.getClass().getSimpleName(); }   // DashEngine: remux_io (fehlgeschlagen)
        check("Media3-Remux: Ton-Init gegen Video-Init vertauscht → abgelehnt, keine Ausgabe (" + got + ")", !got.equals("accepted") && !bad.exists(), got);
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static String analyze(String py, File mpd, File out) throws Exception {
        Process p = new ProcessBuilder(Arrays.asList("python3", "-I", py, mpd.getPath(), out.getPath(), "--audio", "aac")).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return s;
    }
}
