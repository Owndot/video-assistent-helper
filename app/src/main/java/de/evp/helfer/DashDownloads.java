package de.evp.helfer;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import de.evp.helfer.dashdl.DashEngine;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import de.evp.helfer.net.SafeHttp;
import de.evp.helfer.net.SafeHttpClient;

/**
 * S3b: Android-Anbindung der DASH-Downloads. Netzwerk nur über {@link SafeHttp} (in {@link DashEngine}); Zusammenbau
 * mit demselben Media3-Remuxer wie HLS (fMP4 H.264 + AAC, ohne Opus); Ablage und Benachrichtigungen wie bei MP4/HLS
 * ({@link Mp4Downloads}). Auftragsdaten app-privat in {@code noBackupFilesDir/dash-jobs-v1} – nie in einer Sicherung,
 * nie mit Adresse. Grenzen mit MP4 und HLS geteilt ({@link Mp4Downloads#SLOTS}).
 */
final class DashDownloads {
    private static DashDownloads instance;

    final DashEngine engine;

    DashDownloads(Context app, String dirName, SafeHttpClient http, Mp4Downloads store) throws IOException {
        this.engine = new DashEngine(http, store, store, new File(app.getNoBackupFilesDir(), dirName), new Media3HlsRemuxer(false),
            Mp4Downloads.SLOTS, HlsDownloads.sampleBudget(Runtime.getRuntime().maxMemory()));
        this.engine.setRetry(NetWatch.retry(app));
        // „Fortsetzen“ in der Benachrichtigung auch für DASH (nur mit Quelle im Arbeitsspeicher); HLS/MP4 unverändert
        java.util.function.Predicate<String> before = Mp4Downloads.resumable;
        Mp4Downloads.resumable = id -> before.test(id) || engine.canResumeLocal(id);
    }

    /** Eine Instanz je Prozess, nach {@link Mp4Downloads#get} und {@link HlsDownloads#get} (gleiche Ablage). */
    static synchronized DashDownloads get(Context c, Mp4Downloads store) throws IOException {
        if (instance == null) {
            Context app = c.getApplicationContext();
            instance = new DashDownloads(app, "dash-jobs-v1", SafeHttp.client(app), store);
        }
        return instance;
    }
}
