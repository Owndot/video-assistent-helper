package de.evp.helfer.hlsmux;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsRemux;

/**
 * P4b-4: der App-Remuxer und seine Prüfung gegen alle synthetischen Fixtures (JVM, echte Media3-Klassen). Jede Ausgabe
 * wird zusätzlich mit analyze.py (ffprobe/ffmpeg) gegen die Quelle geprüft: Bildanzahl, Dauer, A/V-Versatz über
 * Blitz/Ton-Marken. Dazu Ablehnungen (Opus ohne Nachweis, fehlender Ton, Sample-Budget, Speicherdruck), Abbruch und
 * manipulierte Ausgaben, die die Prüfung erkennen muss. Gerätemessungen (ART-Heap, Dekodierung) laufen im Emulator.
 */
public final class TestMedia3Remux {
    static int passed, failed;
    static File fx, outDir;
    static String analyzer;

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    static List<File> res(File dir, String list) throws IOException {
        List<File> out = new ArrayList<>();
        for (String l : Files.readAllLines(new File(dir, list).toPath())) {
            if (l.startsWith("#EXT-X-MAP:URI=\"")) out.add(new File(dir, l.substring(16, l.indexOf('"', 16))));
            else if (!l.startsWith("#") && !l.isEmpty()) out.add(new File(dir, l));
        }
        return out;
    }

    static double dur(File dir, String list) throws IOException {
        double t = 0;
        for (String l : Files.readAllLines(new File(dir, list).toPath())) if (l.startsWith("#EXTINF:")) t += Double.parseDouble(l.substring(8, l.indexOf(',')));
        return t;
    }

    static final class Case {
        final String name, audioList;
        final File dir;
        Case(String name) {
            this.name = name;
            dir = new File(fx, name);
            audioList = name.contains("muxed") ? null : name.equals("ts_multi") ? "v2.m3u8" : "v1.m3u8";
        }
        boolean fmp4() { return name.startsWith("fmp4"); }
        List<File> video() throws IOException { return res(dir, "v0.m3u8"); }
        List<File> audio() throws IOException { return audioList == null ? new ArrayList<>() : res(dir, audioList); }
        double vs() throws IOException { return dur(dir, "v0.m3u8"); }
        double as() throws IOException { return audioList == null ? vs() : dur(dir, audioList); }
    }

    static String analyze(Case c, File out) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", "-I", analyzer, new File(c.dir, "master.m3u8").getPath(), out.getPath(), "--audio",
            c.name.contains("opus") ? "opus" : "aac"));
        // Video beginnt 0,32 s nach dem Ton: die Ausgabe umfasst die ganzen 20 s des Tons (ffmpeg liest die Quelle ab dem Video)
        if (c.name.equals("fmp4_sep_voffset")) cmd.addAll(List.of("--duration", "20.0"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return s;
    }

    public static void main(String[] args) throws Exception {
        fx = new File(args[0]);
        outDir = new File(args[1]);
        outDir.mkdirs();
        analyzer = args[2];
        String[] names = {"ts_muxed", "ts_sep", "ts_sep_offset", "ts_multi", "fmp4_muxed", "fmp4_sep", "fmp4_sep_offset", "fmp4_sep_voffset", "fmp4_sep_noedts", "fmp4_opus_sep"};
        for (String n : names) {
            Case c = new Case(n);
            File out = new File(outDir, n + ".mp4");
            Media3HlsRemuxer m = new Media3HlsRemuxer(n.contains("opus"));
            try {
                HlsRemux.Result r = m.remux(new HlsRemux.Input(c.video(), c.audio(), c.fmp4(), 1_000_000), out, () -> false);
                m.verify(out, r, c.vs(), c.as(), 4.0, () -> false);
                String a = analyze(c, out);
                check(n + ": Remux + Prüfung ok, Analyse gegen die Quelle (Bilder, Dauer, A/V-Versatz) PASS", a.startsWith("PASS"), a.length() > 600 ? a.substring(0, 600) : a);
                System.out.println("      " + n + " v=" + r.videoSamples + " a=" + r.audioSamples + " bytes=" + out.length() + " " + extract(a));
            } catch (HlsException e) {
                check(n + ": Remux + Prüfung ok", false, e.getMessage());
            }
        }
        negatives();
        longFixture();
        System.out.println("RESULT hlsmux passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static String extract(String analyzerOut) {
        int i = analyzerOut.indexOf("\"output\"");
        if (i < 0) return "";
        int j = analyzerOut.indexOf("sync_offset_ms", i);
        return j < 0 ? "" : analyzerOut.substring(j, Math.min(analyzerOut.length(), j + 24)).replaceAll("[,}].*", "");
    }

    interface Body { void run() throws Exception; }

    static void expect(String name, String reason, File out, Body b) {
        String got;
        try { b.run(); got = "kein Fehler"; } catch (HlsException e) { got = e.getMessage(); } catch (Exception e) { got = e.toString(); }
        check(name + " → " + reason + (out != null ? ", keine Ausgabe" : ""), reason.equals(got) && (out == null || !out.exists()), got + (out != null && out.exists() ? " (Ausgabe vorhanden)" : ""));
    }

    static void negatives() throws Exception {
        Case opus = new Case("fmp4_opus_sep");
        File o1 = new File(outDir, "neg-opus.mp4");
        expect("Opus ohne Gerätenachweis", "audio-codec", o1, () -> new Media3HlsRemuxer(false).remux(new HlsRemux.Input(opus.video(), opus.audio(), true, 1_000_000), o1, () -> false));
        Case sep = new Case("ts_sep");
        File o2 = new File(outDir, "neg-noaudio.mp4");
        expect("Getrennte Variante ohne ihre Tonspur (kein stiller Verlust)", "no-audio", o2,
            () -> new Media3HlsRemuxer(false).remux(new HlsRemux.Input(sep.video(), new ArrayList<>(), false, 1_000_000), o2, () -> false));
        File o3 = new File(outDir, "neg-budget.mp4");
        expect("Sample-Budget überschritten", "samples-limit", o3, () -> new Media3HlsRemuxer(false).remux(new HlsRemux.Input(sep.video(), sep.audio(), false, 100), o3, () -> false));
        File o4 = new File(outDir, "neg-memory.mp4");
        expect("Speicherdruck (freier Heap unter der Schwelle)", "memory", o4,
            () -> new Media3HlsRemuxer(false, 0.999).remux(new HlsRemux.Input(sep.video(), sep.audio(), false, 1_000_000), o4, () -> false));
        File o5 = new File(outDir, "neg-cancel.mp4");
        AtomicLong calls = new AtomicLong();
        expect("Abbruch während des Remux", "cancelled", o5,
            () -> new Media3HlsRemuxer(false).remux(new HlsRemux.Input(sep.video(), sep.audio(), false, 1_000_000), o5, () -> calls.incrementAndGet() > 3));
        File o6 = new File(outDir, "neg-mixed.mp4");
        Case fm = new Case("fmp4_sep");
        expect("TS-Video mit fMP4-Ton (falscher Container für die Spur)", "remux", o6, () -> {
            try { new Media3HlsRemuxer(false).remux(new HlsRemux.Input(sep.video(), fm.audio(), false, 1_000_000), o6, () -> false); }
            catch (HlsException e) { throw new HlsException(e.getMessage().equals("no-audio") ? "remux" : e.getMessage()); }
        });
        // Prüfung der Ausgabe: jede Abweichung erkannt
        File good = new File(outDir, "ts_sep.mp4");
        Media3HlsRemuxer m = new Media3HlsRemuxer(false);
        HlsRemux.Result r = m.remux(new HlsRemux.Input(sep.video(), sep.audio(), false, 1_000_000), good, () -> false);
        expect("Prüfung: andere Video-Sample-Anzahl", "verify-samples", null,
            () -> m.verify(good, new HlsRemux.Result(r.videoSamples + 1, r.audioSamples, 0, 0), sep.vs(), sep.as(), 4.0, () -> false));
        expect("Prüfung: andere Ton-Sample-Anzahl", "verify-samples", null,
            () -> m.verify(good, new HlsRemux.Result(r.videoSamples, r.audioSamples - 1, 0, 0), sep.vs(), sep.as(), 4.0, () -> false));
        expect("Prüfung: Dauer weicht vom Plan ab", "verify-duration", null, () -> m.verify(good, r, sep.vs() + 10, sep.as(), 4.0, () -> false));
        File cut = new File(outDir, "cut.mp4");
        byte[] all = Files.readAllBytes(good.toPath());
        Files.write(cut.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
        String got;
        try { m.verify(cut, r, sep.vs(), sep.as(), 4.0, () -> false); got = "ok"; } catch (HlsException e) { got = e.getMessage(); }
        check("Prüfung: halbe Datei wird abgelehnt", got.startsWith("verify-"), got);
        File empty = new File(outDir, "empty.mp4");
        Files.write(empty.toPath(), new byte[0]);
        expect("Prüfung: leere Datei", "verify-missing", null, () -> m.verify(empty, r, sep.vs(), sep.as(), 4.0, () -> false));
        // Ausgabe nur mit Video (Ton fehlt): eine Spur weniger
        Case mux = new Case("ts_muxed");
        File vo = new File(outDir, "video-only.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", new File(outDir, "ts_muxed.mp4").getPath(), "-an", "-c", "copy", vo.getPath()).inheritIO().start();
        if (p.waitFor() == 0) expect("Prüfung: Ausgabe ohne Tonspur", "verify-tracks", null, () -> m.verify(vo, r, mux.vs(), mux.as(), 4.0, () -> false));
        else check("ffmpeg für Video-only-Probe", false, "exit " + p.exitValue());
    }

    /** 10-min-Fixture (falls erzeugt): ~15 000 Video- + ~28 000 Ton-Samples; JVM-Heap pro Sample (nur Richtwert, ART: Emulator). */
    static void longFixture() throws Exception {
        Case c = new Case("ts_long600");
        if (!new File(c.dir, "v0.m3u8").isFile()) { System.out.println("INFO ts_long600 nicht vorhanden – übersprungen"); return; }
        File out = new File(outDir, "long.mp4");
        Media3HlsRemuxer m = new Media3HlsRemuxer(false);
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        HlsRemux.Result r = m.remux(new HlsRemux.Input(c.video(), c.audio(), false, 1_000_000), out, () -> false);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        m.verify(out, r, c.vs(), c.as(), 4.0, () -> false);
        long samples = r.videoSamples + r.audioSamples;
        check("10 min: Remux + Prüfung ok", samples > 40_000, samples);
        System.out.println("MEASURE jvm long600 samples=" + samples + " ms=" + ms + " heapAfterKiB=" + (m.lastHeapUsed - before) / 1024
            + " maxHeapMiB=" + rt.maxMemory() / (1 << 20));
        // Abbruch eines langen Laufs: endet schnell, nichts bleibt
        File out2 = new File(outDir, "long-cancel.mp4");
        AtomicBoolean stop = new AtomicBoolean();
        new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } stop.set(true); }).start();
        long t1 = System.nanoTime();
        String got;
        try { m.remux(new HlsRemux.Input(c.video(), c.audio(), false, 1_000_000), out2, stop::get); got = "ok"; } catch (HlsException e) { got = e.getMessage(); }
        long cms = (System.nanoTime() - t1) / 1_000_000 - 150;
        check("10 min: Abbruch → cancelled < 1 s nach dem Wunsch, keine Ausgabe", "cancelled".equals(got) && cms < 1000 && !out2.exists(), got + " " + cms + " ms");
    }
}
