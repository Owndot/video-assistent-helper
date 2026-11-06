package de.evp.helfer;

import android.content.Context;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import java.io.File;
import java.io.IOException;

import de.evp.helfer.hls.HlsEngine;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import de.evp.helfer.net.SafeHttp;
import de.evp.helfer.net.SafeHttpClient;

/**
 * Android-Anbindung der HLS-Downloads (P4b). Netzwerk nur über {@link SafeHttp} (in {@link HlsEngine}); Ablage und
 * Benachrichtigungen wie bei MP4 ({@link Mp4Downloads}: MediaStore-Pending bzw. Teil-Datei, atomare Veröffentlichung).
 * Auftragsdaten (Journal, verifizierte Segmente) liegen app-privat in {@code noBackupFilesDir/hls-jobs-v1} – nie in
 * einer Sicherung, nie mit Adresse. Grenzen mit MP4 geteilt ({@link Mp4Downloads#SLOTS}: 2 Aufträge, 4 Anfragen).
 */
final class HlsDownloads {
    /** Angenommener Java-Heap je Sample im Index des Muxers (gemessen ≈ 58 B auf ART, P4a) mit Sicherheitsfaktor. */
    static final long HEAP_BYTES_PER_SAMPLE = 128;
    static final long MAX_SAMPLES = 1_000_000;
    private static HlsDownloads instance;

    final HlsEngine engine;
    final boolean opus;

    HlsDownloads(Context app, String dirName, SafeHttpClient http, Mp4Downloads store) throws IOException {
        this.opus = opusSupported();
        this.engine = new HlsEngine(http, store, store, new File(app.getNoBackupFilesDir(), dirName), new Media3HlsRemuxer(opus),
            Mp4Downloads.SLOTS, opus, sampleBudget(Runtime.getRuntime().maxMemory()));
        this.engine.setRetry(NetWatch.retry(app));       // P4c: begrenzte Wiederaufnahme mit Verbindungsüberwachung
        Mp4Downloads.resumable = id -> engine.canResumeLocal(id) || engine.canResumeStored(id);   // P2: auch aus dem Tresor
    }

    /** Eine Instanz je Prozess, nach {@link Mp4Downloads#get} (gleiche Ablage). */
    /** P4c-3 (P2): verschlüsselte Quelle (Keystore-Schlüssel, app-privat in noBackupFilesDir, höchstens 6 h). */
    void attachVault(Context c) throws java.io.IOException {
        engine.setVault(new de.evp.helfer.dl.SourceVault(new java.io.File(c.getNoBackupFilesDir(), "source-vault"), new KeystoreSealer(), System::currentTimeMillis));
    }

    static synchronized HlsDownloads get(Context c, Mp4Downloads store) throws IOException {
        if (instance == null) {
            Context app = c.getApplicationContext();
            instance = new HlsDownloads(app, "hls-jobs-v1", SafeHttp.client(app), store);
        }
        return instance;
    }

    /**
     * Sample-Budget je Auftrag aus dem verfügbaren Heap: höchstens die Hälfte des Heaps für den Muxer-Index, höchstens
     * 1 000 000 Samples. Darüber bricht der Remux mit "samples_limit" ab; bei Speicherdruck vorher mit "memory".
     */
    static long sampleBudget(long maxHeapBytes) {
        return Math.max(10_000, Math.min(MAX_SAMPLES, maxHeapBytes / 2 / HEAP_BYTES_PER_SAMPLE));
    }

    /** Opus nur ab Android 10 und nur, wenn das Gerät einen Opus-Decoder meldet (Abspielbarkeit der Ausgabe). */
    static boolean opusSupported() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2);
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
