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
 *   <li>Android 8–9 (API 26–28, kein MediaStore.Downloads): öffentlicher Ordner {@code Download/Video-Assistent}
 *       mit WRITE_EXTERNAL_STORAGE (Laufzeitberechtigung, in der App erbeten; ohne sie scheitert der Auftrag vor
 *       jedem Netzwerkzugriff). Teil-Datei, Umbenennen ohne Ersetzen, Medienscanner; eigene Reste entfernt
 *       {@link #cleanupPublicLeftovers} beim Prozessstart. Die Datei bleibt nach einer Deinstallation erhalten.</li>
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
    private final java.util.function.BooleanSupplier legacyWriteAllowed;

    /**
     * {@code http}: produktiv immer {@link SafeHttp#client}; Gerätetests geben einen Client mit Testregeln (eigenes
     * Journal). {@code legacyWriteAllowed}: produktiv die echte Berechtigungsprüfung (nur Android 8–9 relevant).
     */
    Mp4Downloads(Context app, String journalName, SafeHttpClient http, java.util.function.BooleanSupplier legacyWriteAllowed) throws IOException {
        this.app = app;
        this.journal = new File(app.getFilesDir(), journalName);
        this.legacyWriteAllowed = legacyWriteAllowed;
        Downloads.channel(app);
        cleanupPublicLeftovers();              // vor dem Journal: Wiederherstellung sieht nur echte Dateien
        this.engine = new DirectDownloads(http, this, this);
    }

    /** Speicherberechtigungen für Android 8–9: beide nötig (ohne READ vergibt Android nur die Lese-/Standard-Ansicht). */
    static final String[] LEGACY_STORAGE_PERMISSIONS = {
        android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE};

    /** Echte Prüfung der Speicherberechtigungen (Android 8–9): READ und WRITE. */
    static boolean legacyWritePermitted(Context app) {
        for (String p : LEGACY_STORAGE_PERMISSIONS) {
            if (app.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Eine Instanz je Prozess (Journal und laufende Aufträge überdauern einen Dienst-Neustart im selben Prozess). */
    static synchronized Mp4Downloads get(Context c) throws IOException {
        if (instance == null) {
            Context app = c.getApplicationContext();
            instance = new Mp4Downloads(app, "mp4-jobs-v1", SafeHttp.client(app), () -> legacyWritePermitted(app));
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
        return Build.VERSION.SDK_INT >= 29 ? new PendingEntry(displayName, mime) : new PublicFile(displayName, mime);
    }

    /** Android 10+: MediaStore braucht keine Berechtigung. Android 8–9: WRITE_EXTERNAL_STORAGE (Laufzeit, in der App). */
    @Override public boolean writable() {
        return Build.VERSION.SDK_INT >= 29 || legacyWriteAllowed.getAsBoolean();
    }

    @Override public boolean isPublished(String ref, long total) {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri u = Uri.parse(ref);
            if (!"content".equals(u.getScheme())) return false;
            try (android.database.Cursor c = app.getContentResolver().query(u,
                    new String[] {MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE}, null, null, null)) {
                return c != null && c.moveToFirst() && c.getInt(0) == 0 && c.getLong(1) == total;
            }
        }
        File f = new File(ref);
        return f.getParentFile() != null && f.getParentFile().equals(publicDir()) && f.isFile() && f.length() == total
            && !f.getName().startsWith(PART_PREFIX);
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

        @Override public String ref() { return uri.toString(); }

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

    /**
     * Android 8–9: Teil-Datei {@code .evp-pending-<Markierung>.part} direkt im öffentlichen Ordner
     * {@code Download/Video-Assistent} (Berechtigung WRITE_EXTERNAL_STORAGE, nur bis API 28 angefordert). Die
     * Markierung in filesDir beweist den Besitz: nur so markierte Teil-Dateien werden nach einem Absturz entfernt.
     * Der endgültige Name wird beim Anlegen reserviert und ist der Journal-Verweis; Veröffentlichen = Umbenennen ohne
     * Ersetzen, dann Medienscanner. Punkt-Dateien nimmt der Medienscanner nicht auf.
     */
    private final class PublicFile implements Target {
        private final String name, mime;
        private final File dir, part, proof;
        private File dst;
        private final FileOutputStream file;
        private final OutputStream out;
        private boolean finished, published;

        PublicFile(String name, String mime) throws IOException {
            this.name = name;
            this.mime = mime;
            dir = publicDir();
            Files.createDirectories(dir.toPath());     // nennt den Grund (z. B. AccessDenied), anders als mkdirs()
            try { proof = Downloads.pendingProof(app); }
            catch (Exception e) { throw new IOException("pending ownership", e); }
            part = new File(dir, PART_PREFIX + proof.getName() + PART_SUFFIX);
            if (!part.createNewFile()) { Downloads.removeProof(proof); throw new IOException("part exists"); }
            synchronized (Downloads.fileNames) { dst = reserve(dir, name); }
            if (dst == null) { part.delete(); Downloads.removeProof(proof); throw new IOException("no unused file name"); }
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

        @Override public synchronized String ref() { return dst.getPath(); }

        @Override public synchronized Object commit() throws IOException {
            out.close();
            synchronized (Downloads.fileNames) {
                // Ohne Ersetzen; ist der reservierte Name inzwischen belegt (fremde Datei), den nächsten freien nehmen.
                while (!published) {
                    try { Files.move(part.toPath(), dst.toPath()); published = true; }
                    catch (java.nio.file.FileAlreadyExistsException exists) {
                        File next = reserve(dir, name);   // der alte Name bleibt bis hier reserviert, wird also übersprungen
                        RESERVED.remove(dst.getPath());
                        if (next == null) throw new IOException("no unused file name");
                        dst = next;
                    }
                }
                RESERVED.remove(dst.getPath());
            }
            finished = true;
            Downloads.removeProof(proof);   // Absturz davor: Markierung ohne Teil-Datei, beim Start entfernt
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
            synchronized (Downloads.fileNames) { RESERVED.remove(dst.getPath()); }
            // Markierung nur entfernen, wenn die Teil-Datei sicher weg ist; sonst räumt der nächste Start auf.
            try { if (part.delete() || !part.exists()) Downloads.removeProof(proof); } catch (IOException ignored) { }
        }
    }

    static final String PART_PREFIX = ".evp-pending-", PART_SUFFIX = ".part";
    /** Im Prozess reservierte endgültige Namen (Android 8–9); geschützt durch {@link Downloads#fileNames}. */
    private static final java.util.Set<String> RESERVED = new java.util.HashSet<>();

    static File publicDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Downloads.FOLDER);
    }

    /** Erster freier Name "x.mp4", "x (1).mp4" …, weder vorhanden noch reserviert; null, wenn keiner frei ist. */
    private static File reserve(File dir, String name) {
        int dot = name.lastIndexOf('.');
        for (int n = 0; n < 10000; n++) {
            String candidate = n == 0 ? name : dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")";
            File f = new File(dir, candidate);
            if (!f.exists() && RESERVED.add(f.getPath())) return f;
        }
        return null;
    }

    /**
     * Android 8–9, einmal je Prozess vor dem ersten Auftrag: eigene Teil-Dateien eines abgebrochenen Prozesses im
     * öffentlichen Ordner entfernen (nur mit eigener Markierung; fremde Dateien bleiben) und Markierungen ohne
     * Teil-Datei löschen (Absturz zwischen Umbenennen und Aufräumen: die fertige Datei bleibt).
     */
    private void cleanupPublicLeftovers() {
        if (Build.VERSION.SDK_INT >= 29 || !writable()) return;
        File proofs = new File(app.getFilesDir(), "download-pending-v1");
        File[] parts = publicDir().listFiles();
        if (parts != null) for (File f : parts) {
            String n = f.getName();
            if (!n.startsWith(PART_PREFIX) || !n.endsWith(PART_SUFFIX)) continue;
            File proof = new File(proofs, n.substring(PART_PREFIX.length(), n.length() - PART_SUFFIX.length()));
            if (!proof.isFile()) continue;                                   // nicht von uns: nie anfassen
            try { if (f.delete() || !f.exists()) Downloads.removeProof(proof); } catch (IOException ignored) { }
        }
        File[] markers = proofs.listFiles();
        if (markers != null) for (File m : markers) {
            if (!m.getName().matches("evp-publish-[0-9]+\\.pending")) continue;
            if (!new File(publicDir(), PART_PREFIX + m.getName() + PART_SUFFIX).exists()) {
                try { Downloads.removeProof(m); } catch (IOException ignored) { }
            }
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
            if ("storage_permission".equals(reason)) {   // Tippen öffnet die App, dort wird die Berechtigung erbeten
                Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(app, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
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
        if (reason.equals("storage_permission")) return Tr.t("Speicherzugriff fehlt – in der App erlauben", "storage access missing – allow it in the app");
        return reason;
    }
}
