package de.evp.p4a;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsPlan;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

/**
 * P4a auf Wegwerf-Emulatoren (API 26/29/34): jede Fixture über den strengen Parser/Plan in Ressourcen zerlegt, dann
 * über drei Wege umgepackt (Media3+Mp4Muxer, Media3+FragmentedMp4Muxer, Plattform MediaExtractor+MediaMuxer); jede
 * Ausgabe wird auf diesem Gerät vollständig dekodiert. Dazu Abbruch, Speicher (10-min-Fixture) und ein Vergleich mit
 * Media3s HlsPlaylistParser. Ergebnisse als JSON-Zeilen unter files/p4a/ (vom CI-Job per run-as abgeholt und mit
 * tools/analyze.py gegen die Quelle geprüft). Befunde werden protokolliert, nicht verschluckt; der Test schlägt nur
 * fehl, wenn der Hauptweg (Media3+Mp4Muxer) eine Fixture nicht erzeugen oder hier nicht dekodieren kann.
 */
@RunWith(AndroidJUnit4.class)
public class RemuxDeviceTest {
    static final String[] FIXTURES = {"ts_muxed", "ts_sep", "ts_sep_offset", "ts_multi", "fmp4_muxed", "fmp4_sep", "fmp4_sep_offset",
        "fmp4_sep_noedts", "fmp4_opus_sep"};

    Context test() { return InstrumentationRegistry.getInstrumentation().getContext(); }
    File root() { File d = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "p4a"); d.mkdirs(); return d; }

    synchronized void record(Result r) throws IOException {
        r.v.put("api", Build.VERSION.SDK_INT);
        try (Writer w = new java.io.OutputStreamWriter(new FileOutputStream(new File(root(), "results.jsonl"), true), StandardCharsets.UTF_8)) {
            w.write(r.json() + "\n");
        }
        android.util.Log.i("P4A", r.json());
    }

    /** Fixture aus den Test-Assets in den App-Speicher (wie verifizierte, heruntergeladene Ressourcen). */
    File copy(String name) throws IOException {
        File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "fx/" + name);
        dir.mkdirs();
        AssetManager am = test().getAssets();
        for (String f : am.list(name)) {
            try (InputStream in = am.open(name + "/" + f); OutputStream out = new FileOutputStream(new File(dir, f))) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
        }
        return dir;
    }

    /** Plan über den strengen Parser; Adressen auf lokale Dateien abgebildet. {video, audio(oder null)} */
    static List<List<File>> plan(File dir, String name) throws Exception {
        String base = "https://fixture.invalid/" + name + "/";
        de.evp.helfer.hls.HlsPlaylist.Master m = de.evp.helfer.hls.HlsPlaylist.parse(Files.readAllBytes(new File(dir, "master.m3u8").toPath()),
            base + "master.m3u8").master;
        HlsPlan.Option o = HlsPlan.options(m, new byte[] {7}, true).get(0);
        de.evp.helfer.hls.HlsPlaylist.Media v = de.evp.helfer.hls.HlsPlaylist.parse(Files.readAllBytes(new File(dir, o.variant.uri.substring(base.length())).toPath()), o.variant.uri).media;
        de.evp.helfer.hls.HlsPlaylist.Media a = o.audio == null ? null
            : de.evp.helfer.hls.HlsPlaylist.parse(Files.readAllBytes(new File(dir, o.audio.uri.substring(base.length())).toPath()), o.audio.uri).media;
        HlsPlan.Plan p = HlsPlan.build(v, a, o.variant.bandwidth);
        List<List<File>> out = new ArrayList<>();
        out.add(files(dir, base, p.videoResources));
        out.add(a == null ? null : files(dir, base, p.audioResources));
        return out;
    }

    static List<File> files(File dir, String base, List<String> uris) {
        List<File> f = new ArrayList<>();
        for (String u : uris) f.add(new File(dir, u.substring(base.length())));
        return f;
    }

    static long javaUsed() { Runtime rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory(); }

    @Test
    public void remuxAllFixtures() throws Exception {
        File out = new File(root(), "out");
        out.mkdirs();
        List<String> mainFailures = new ArrayList<>();
        for (String name : FIXTURES) {
            File dir = copy(name);
            List<List<File>> p = plan(dir, name);
            // Opus nur, wo es nachweislich abspielbar ist (P4a: API 26 kann Opus-in-MP4 nicht lesen, ab API 29 schon)
            boolean fmp4 = name.startsWith("fmp4"), opus = name.contains("opus") && Build.VERSION.SDK_INT >= 29;
            for (String path : new String[] {"media3", "media3-fragmented", "platform"}) {
                File o = new File(out, name + "--" + path + "--api" + Build.VERSION.SDK_INT + ".mp4");
                Result r;
                long n0 = Debug.getNativeHeapAllocatedSize();
                try (Concat v = new Concat(p.get(0)); Concat a = p.get(1) == null ? null : new Concat(p.get(1))) {
                    if (path.equals("platform")) r = PlatformRemuxer.run(v, a, o, new AtomicBoolean(), opus);
                    else r = Media3Remuxer.run(v, a, fmp4, o, new AtomicBoolean(), opus, path.equals("media3-fragmented"));
                    r.put("native_heap_delta_kb", (Debug.getNativeHeapAllocatedSize() - n0) / 1024);
                    DeviceDecode.check(o, r);
                } catch (Throwable t) {
                    r = new Result().put("path", path).put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                r.put("test", "remux").put("fixture", name).put("file", o.getName());
                record(r);
                boolean expectedRejection = name.contains("opus") && !opus && String.valueOf(r.v.get("error")).contains("audio-codec");
                r.put("expected_rejection", expectedRejection);
                if (path.equals("media3") && !expectedRejection && (r.v.containsKey("error") || r.v.containsKey("device_video_decode_error")
                        || r.v.containsKey("device_audio_decode_error") || r.v.containsKey("device_extract_error")))
                    mainFailures.add(name + ": " + r.json());
            }
        }
        assertTrue("Hauptweg Media3+Mp4Muxer: " + mainFailures, mainFailures.isEmpty());
    }

    @Test
    public void cancellationStopsPromptlyAndLeavesNoOutput() throws Exception {
        File dir = copy("ts_sep");
        List<List<File>> p = plan(dir, "ts_sep");
        for (String path : new String[] {"media3", "platform"}) {
            File o = new File(root(), "cancel-" + path + ".mp4");
            AtomicBoolean cancel = new AtomicBoolean();
            Result r = new Result().put("test", "cancel").put("path", path);
            try (Concat v = new Concat(p.get(0)); Concat a = new Concat(p.get(1))) {
                v.throttleNanosPerRead = 20_000_000;   // langsame Quelle (~20 ms je Lesezugriff): Abbruch fällt mitten in den Lauf
                a.throttleNanosPerRead = 20_000_000;
                Thread t = new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } cancel.set(true); });
                long t0 = System.nanoTime();
                t.start();
                String outcome;
                try {
                    if (path.equals("platform")) PlatformRemuxer.run(v, a, o, cancel, false);
                    else Media3Remuxer.run(v, a, false, o, cancel, false);
                    outcome = "completed";
                } catch (CancellationException e) {
                    outcome = "cancelled";
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                r.put("outcome", outcome).put("ms_total", ms).put("ms_after_cancel", ms - 150).put("output_left", o.exists());
            }
            record(r);
            assertTrue(path + ": " + r.json(), "cancelled".equals(r.v.get("outcome")) && !o.exists());
        }
    }

    @Test
    public void memoryGrowthPerSample() throws Exception {
        File dir = copy("ts_long600");
        List<List<File>> p = plan(dir, "ts_long600");
        for (String path : new String[] {"media3", "media3-fragmented", "platform"}) {
            File o = new File(root(), "long-" + path + ".mp4");
            StringBuilder probe = new StringBuilder();
            Media3Remuxer.probe = n -> { Runtime.getRuntime().gc(); probe.append(n).append(':').append(javaUsed() / 1024).append("K "); };
            Runtime.getRuntime().gc();
            long j0 = javaUsed(), n0 = Debug.getNativeHeapAllocatedSize();
            Result r;
            try (Concat v = new Concat(p.get(0)); Concat a = new Concat(p.get(1))) {
                r = path.equals("platform") ? PlatformRemuxer.run(v, a, o, new AtomicBoolean(), false)
                    : Media3Remuxer.run(v, a, false, o, new AtomicBoolean(), false, path.equals("media3-fragmented"));
                r.put("native_heap_delta_kb", (Debug.getNativeHeapAllocatedSize() - n0) / 1024).put("java_before_kb", j0 / 1024);
            } catch (Throwable t) {
                r = new Result().put("path", path).put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                Media3Remuxer.probe = null;
            }
            r.put("test", "memory").put("heap_probe", probe.toString()).put("max_heap_mb", Runtime.getRuntime().maxMemory() >> 20);
            record(r);
            o.delete();
        }
    }

    /** Media3s HlsPlaylistParser hinter begrenztem Stream: was er annimmt, das unser strenger Parser ablehnt. */
    @Test
    public void media3ParserComparison() throws Exception {
        String head = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n";
        String[][] cases = {
            {"live", head + "#EXTINF:4,\na.ts\n"},
            {"aes128", head + "#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"sample-aes", head + "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"unknown-method", head + "#EXT-X-KEY:METHOD=FOO,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"discontinuity", head + "#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n"},
            {"byterange", head + "#EXT-X-BYTERANGE:100@0\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"ll-hls-part", head + "#EXT-X-PART-INF:PART-TARGET=1\n#EXT-X-PART:DURATION=1,URI=\"p.ts\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"file-uri", head + "#EXTINF:4,\nfile:///sdcard/x.ts\n#EXT-X-ENDLIST\n"},
            {"unknown-tag", head + "#EXT-X-CUE-OUT:30\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"},
            {"extinf-over-target", head + "#EXTINF:9,\na.ts\n#EXT-X-ENDLIST\n"},
        };
        for (String[] c : cases) {
            Result r = new Result().put("test", "parser").put("case", c[0]);
            try {
                HlsPlaylist p = new HlsPlaylistParser().parse(Uri.parse("https://fixture.invalid/x/l.m3u8"),
                    new ByteArrayInputStream(c[1].getBytes(StandardCharsets.UTF_8)));
                String desc = p.getClass().getSimpleName();
                if (p instanceof HlsMediaPlaylist) {
                    HlsMediaPlaylist m = (HlsMediaPlaylist) p;
                    desc += " segments=" + m.segments.size() + " endList=" + m.hasEndTag + " protection=" + (m.protectionSchemes != null)
                        + " keyUri0=" + (m.segments.isEmpty() ? "-" : (m.segments.get(0).fullSegmentEncryptionKeyUri != null));
                }
                r.put("media3", "accepted " + desc);
            } catch (Throwable t) {
                r.put("media3", "rejected " + t.getClass().getSimpleName());
            }
            try {
                de.evp.helfer.hls.HlsPlaylist.parse(c[1].getBytes(StandardCharsets.UTF_8), "https://fixture.invalid/x/l.m3u8");
                r.put("strict", "accepted");
            } catch (HlsException e) {
                r.put("strict", "rejected " + e.reason);
            }
            record(r);
        }
    }

    static String list(String[] a) { return Arrays.toString(a); }
}
