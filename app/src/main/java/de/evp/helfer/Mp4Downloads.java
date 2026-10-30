package de.evp.helfer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.net.SafeHttp;
import de.evp.helfer.net.SafeHttpClient;

/**
 * Android-Ablage der direkten MP4-Downloads (Phase C2): Netzwerk nur über {@link SafeHttp} (in
 * {@link DirectDownloads}); hier nur Speicher, Journal und Benachrichtigungen.
 *
 * <ul>
 *   <li>Android 10+ (API 29): Datei wird direkt in einen MediaStore-Eintrag {@code Download/Video-Assistent}
 *       geschrieben, der bis zum Ende "pending" (unsichtbar) bleibt. Dieselbe Besitz-Markierung wie
 *       {@link Downloads} (Markierungsdatei vor dem Einfügen, Name {@code evp-publish-N.pending--…}): nach einem
 *       Prozessende räumt {@link Downloads#initialize} den Eintrag beim nächsten Start ab.</li>
 *   <li>Android 8–9 (API 26–28, kein MediaStore.Downloads): Teil-Datei {@code .evp-pending-*.part} im
 *       app-eigenen Download-Ordner, danach Umbenennen ohne Ersetzen und Eintrag in den MediaStore über den
 *       Medienscanner. Reste räumt ebenfalls {@link Downloads#initialize} ab.</li>
 * </ul>
 * Benachrichtigungen zeigen nur den Anzeigenamen und Prozent, nie die URL.
 */
final class Mp4Downloads implements DownloadStore, DirectDownloads.Listener {
    static final String ACTION_CANCEL = "dlcancel";
    static final String EXTRA_JOB = "job";
    private static final int NOTE_ID = 0x4d50;     // je Auftrag eigener Tag ("mp4-<id>")
    private static final int MAX_JOURNAL_BYTES = 256 << 10;
    private static Mp4Downloads instance;

    final DirectDownloads engine;
    private final Context app;
    private final File journal;

    /** {@code http}: produktiv immer {@link SafeHttp#client}; Gerätetests geben einen Client mit Testregeln (eigenes Journal). */
    Mp4Downloads(Context app, String journalName, SafeHttpClient http) throws IOException {
        this.app = app;
        this.journal = new File(app.getFilesDir(), journalName);
        Downloads.channel(app);
        this.engine = new DirectDownloads(http, this, this);
    }

    /** Eine Instanz je Prozess (Journal und laufende Aufträge überdauern einen Dienst-Neustart im selben Prozess). */
    static synchronized Mp4Downloads get(Context c) throws IOException {
        if (instance == null) {
            Context app = c.getApplicationContext();
            instance = new Mp4Downloads(app, "mp4-jobs-v1", SafeHttp.client(app));
        }
        return instance;
    }

    static boolean validJobId(String id) { return id != null && id.matches("[0-9a-f]{32}"); }

    // ------------------------------------------------------------------ DownloadStore

    @Override public long freeBytes() throws IOException {
        File vol = app.getExternalFilesDir(null);              // gleiches Volume wie Download/ (primärer Speicher)
        if (vol == null) throw new IOException("storage unavailable");
        return new StatFs(vol.getPath()).getAvailableBytes();
    }

    @Override public Target create(String displayName, String mime) throws IOException {
        return Build.VERSION.SDK_INT >= 29 ? new PendingEntry(displayName, mime) : new PartFile(displayName, mime);
    }

    /** MediaStore-Eintrag, bis zum Ende pending. */
    private final class PendingEntry implements Target {
        private final ContentResolver cr = app.getContentResolver();
        private final String name;
        private final File proof;
        private final Uri uri;
        private final OutputStream out;
        private boolean finished;

        PendingEntry(String name, String mime) throws IOException {
            this.name = name;
            try { proof = Downloads.pendingProof(app); }
            catch (Exception e) { throw new IOException("pending ownership", e); }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, proof.getName() + "--" + name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Downloads.FOLDER + "/");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri u = null;
            OutputStream o = null;
            try {
                u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new IOException("insert");
                o = cr.openOutputStream(u, "w");
                if (o == null) throw new IOException("open");
            } catch (IOException | RuntimeException e) {
                if (u != null && cr.delete(u, null, null) == 1) Downloads.removeProof(proof);
                else if (u == null) Downloads.removeProof(proof);
                throw e instanceof IOException ? (IOException) e : new IOException("insert", e);
            }
            uri = u;
            out = o;
        }

        @Override public OutputStream out() { return out; }

        @Override public synchronized Object commit() throws IOException {
            out.close();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (cr.update(uri, cv, null, null) != 1) throw new IOException("publish");
            finished = true;
            Downloads.removeProof(proof);
            return uri;
        }

        @Override public synchronized void abort() {
            if (finished) return;
            finished = true;
            try { out.close(); } catch (IOException ignored) { }
            try {
                // Markierung nur nach bestätigtem Löschen entfernen; sonst räumt der nächste Prozessstart auf.
                if (cr.delete(uri, null, null) == 1) Downloads.removeProof(proof);
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    /** Android 8–9: Teil-Datei im app-eigenen Download-Ordner. */
    private final class PartFile implements Target {
        private final String name, mime;
        private final File dir, part;
        private final FileOutputStream file;
        private final OutputStream out;
        private boolean finished;

        PartFile(String name, String mime) throws IOException {
            this.name = name;
            this.mime = mime;
            File external = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (external == null) throw new IOException("storage unavailable");
            dir = new File(external, Downloads.FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir");
            part = File.createTempFile(".evp-pending-", ".part", dir);
            file = new FileOutputStream(part);
            // Die Engine schließt den Stream vor commit(): fsync gehört deshalb in close() (einmalig), nicht in commit().
            out = new java.io.FilterOutputStream(file) {
                private boolean closed;
                @Override public void write(byte[] b, int off, int len) throws IOException { file.write(b, off, len); }
                @Override public synchronized void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { file.getFD().sync(); } finally { file.close(); }
                }
            };
        }

        @Override public OutputStream out() { return out; }

        @Override public synchronized Object commit() throws IOException {
            out.close();
            File dst = null;
            synchronized (Downloads.fileNames) {
                int dot = name.lastIndexOf('.');
                for (int n = 0; n < 10000 && dst == null; n++) {
                    String candidate = n == 0 ? name : dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")";
                    File f = new File(dir, candidate);
                    try { Files.move(part.toPath(), f.toPath()); dst = f; }
                    catch (java.nio.file.FileAlreadyExistsException exists) { /* nächster Name */ }
                }
            }
            if (dst == null) throw new IOException("no unused file name");
            finished = true;
            return scan(dst);
        }

        /** Eintrag in den MediaStore (Medienscanner); ohne Ergebnis binnen 10 s bleibt es bei der Datei. */
        private Uri scan(File f) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Uri> result = new AtomicReference<>();
            MediaScannerConnection.scanFile(app, new String[] {f.getPath()}, new String[] {mime}, (path, u) -> { result.set(u); done.countDown(); });
            try { done.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return result.get() != null ? result.get() : Uri.fromFile(f);
        }

        @Override public synchronized void abort() {
            if (finished) return;
            finished = true;
            try { file.close(); } catch (IOException ignored) { }
            part.delete();
        }
    }

    // ------------------------------------------------------------------ Journal (atomar ersetzt)

    @Override public synchronized byte[] loadJournal() throws IOException {
        if (!journal.exists()) return null;
        long len = journal.length();
        if (len > MAX_JOURNAL_BYTES) throw new IOException("journal too large");
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(journal)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("journal truncated");
                off += n;
            }
        }
        return b;
    }

    @Override public synchronized void saveJournal(byte[] data) throws IOException {
        File tmp = new File(journal.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ Benachrichtigungen (ohne URL)

    private NotificationManager nm() { return app.getSystemService(NotificationManager.class); }

    private static String tag(String id) { return "mp4-" + id; }

    @Override public void progress(String id, String title, long bytes, long total) {
        Intent stop = new Intent(app, HelferService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, id)
            .setData(Uri.parse("evp-mp4:" + id));                // je Auftrag eigener PendingIntent
        PendingIntent cancel = PendingIntent.getService(app, id.hashCode(), stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int pct = total > 0 ? (int) (100L * bytes / total) : 0;
        Notification n = new Notification.Builder(app, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(pct + " % · " + (bytes >> 20) + Tr.t(" von ", " of ") + (total >> 20) + " MB")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(new Notification.Action.Builder(null, Tr.t("Abbrechen", "Cancel"), cancel).build())
            .build();
        NotificationManager m = nm();
        if (m != null) m.notify(tag(id), NOTE_ID, n);
    }

    @Override public void finished(String id, String title, String state, String reason, Object saved) {
        NotificationManager m = nm();
        if (m == null) return;
        if ("cancelled".equals(state)) { m.cancel(tag(id), NOTE_ID); return; }
        Notification.Builder b = new Notification.Builder(app, Downloads.CHANNEL).setContentTitle(title).setAutoCancel(true);
        if ("done".equals(state)) {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText(Tr.t("Gespeichert in Download/", "Saved in Download/") + Downloads.FOLDER);
            if (saved instanceof Uri && "content".equals(((Uri) saved).getScheme())) {
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType((Uri) saved, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(app, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentText(Tr.t("Download fehlgeschlagen: ", "Download failed: ") + reasonText(reason));
        }
        m.notify(tag(id), NOTE_ID, b.build());
    }

    /** Nur feste Texte bzw. der Grundcode – nie Server-Text oder URL. */
    static String reasonText(String reason) {
        if (reason == null) return "";
        if (reason.equals("no_space")) return Tr.t("zu wenig Speicher", "not enough storage");
        if (reason.equals("network")) return Tr.t("Netzwerk unterbrochen", "network interrupted");
        if (reason.equals("not_mp4") || reason.equals("type")) return Tr.t("keine MP4-Datei", "not an MP4 file");
        if (reason.equals("too_large")) return Tr.t("Datei zu groß", "file too large");
        if (reason.startsWith("blocked_")) return Tr.t("Ziel nicht erlaubt", "target not allowed");
        return reason;
    }
}
