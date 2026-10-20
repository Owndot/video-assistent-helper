package de.evp.helfer;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads für den Videoplayer im Browser.
 *  - Videodateien (MP4/WebM …): über Androids Download-Manager, mit der Herkunftsseite (Referer) wie im Browser.
 *  - Streams (HLS/m3u8): Teile laden (HlsCore), dann zu einer normalen MP4 zusammensetzen.
 * Gespeichert wird in Download/Video-Assistent. Fortschritt und "Abbrechen" stehen in der Benachrichtigung.
 */
public class Downloads {
    static final String CHANNEL = "downloads";
    static final String FOLDER = "Video-Assistent";
    private static final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private static final AtomicInteger ids = new AtomicInteger(100);
    static final Map<Integer, Boolean> cancel = new ConcurrentHashMap<>();
    static final Map<Integer, String> state = new ConcurrentHashMap<>();
    static final Map<Integer, Fetcher.Proxy> proxies = new ConcurrentHashMap<>();

    static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Fortschritt beim Herunterladen von Videos");
        nm.createNotificationChannel(ch);
    }

    static String safeName(String title, String ext) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        if (t.isEmpty()) t = "Video";
        return t + "." + ext;
    }

    static String extOf(String url, String def) {
        String p = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        for (String e : new String[]{"mp4", "webm", "mkv", "mov", "m4v", "mp3", "m4a"}) if (p.endsWith("." + e)) return e;
        return def;
    }

    /**
     * Startet einen Download; Rückgabe: Kennung (für Fortschritt / Abholen).
     * kind: file | hls | dash | auto · proxy: über den Browser laden (das Skript holt die Daten mit seiner Sitzung)
     */
    static int start(Context ctx, String url, String referer, String ua, String cookie, String title, String kind, boolean proxy) {
        final Context c = ctx.getApplicationContext();
        channel(c);
        final int id = ids.incrementAndGet();
        String path = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        if (kind == null || kind.isEmpty() || "auto".equals(kind)) kind = path.endsWith(".m3u8") ? "hls" : path.endsWith(".mpd") ? "dash" : "file";
        final Fetcher net;
        if (proxy) {
            Fetcher.Proxy px = new Fetcher.Proxy();
            proxies.put(id, px);
            net = px;
        } else net = new Fetcher.Http(referer, ua, cookie);
        if ("file".equals(kind) && !proxy) {
            state.put(id, "Download-Manager");
            long dmId = fileDownload(c, url, referer, ua, cookie, title);
            watch(c, id, dmId);
            return id;
        }
        state.put(id, "startet");
        final String k = kind;
        jobs.execute(() -> streamJob(c, id, url, title, k, net));
        return id;
    }

    // ---------- Videodatei: Androids Download-Manager ----------
    private static long fileDownload(Context c, String url, String referer, String ua, String cookie, String title) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        String name = safeName(title, extOf(url, "mp4"));
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        if (referer != null && !referer.isEmpty()) r.addRequestHeader("Referer", referer);
        if (ua != null && !ua.isEmpty()) r.addRequestHeader("User-Agent", ua);
        if (cookie != null && !cookie.isEmpty()) r.addRequestHeader("Cookie", cookie);
        r.setTitle(name);
        r.setDescription("Video-Assistent");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FOLDER + "/" + name);
        r.setAllowedOverMetered(true);
        r.setAllowedOverRoaming(true);
        return dm.enqueue(r);
    }

    /** Download-Manager beobachten: lehnt der Server ab (z. B. 403), meldet der Zustand das – das Skript lädt dann über den Browser */
    private static void watch(Context c, int id, long dmId) {
        jobs.execute(() -> {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            long until = System.currentTimeMillis() + 25000;
            while (System.currentTimeMillis() < until) {
                try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
                try (android.database.Cursor q = dm.query(new DownloadManager.Query().setFilterById(dmId))) {
                    if (q == null || !q.moveToFirst()) { state.put(id, "Fehler: abgebrochen"); return; }
                    int st = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    if (st == DownloadManager.STATUS_FAILED) {
                        state.put(id, reason >= 400 && reason < 600 ? "Fehler: HTTP " + reason : "Fehler: " + reason);
                        dm.remove(dmId);
                        return;
                    }
                    if (st == DownloadManager.STATUS_SUCCESSFUL) { state.put(id, "fertig"); return; }
                    long got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    if (st == DownloadManager.STATUS_RUNNING && got > 0) state.put(id, "läuft");
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    // ---------- Stream (HLS/DASH) oder Datei über den Browser: laden, zusammensetzen, speichern ----------
    private static void streamJob(Context c, int id, String url, String title, String kind, Fetcher net) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        File work = new File(c.getCacheDir(), "dl" + id);
        final long[] lastShown = {0};
        String shortTitle = title == null || title.isEmpty() ? "Video" : (title.length() > 60 ? title.substring(0, 60) + "…" : title);
        try {
            if (!work.exists() && !work.mkdirs()) throw new Exception("Kein Arbeitsordner");
            progressNote(c, nm, id, shortTitle, 0, 0, net instanceof Fetcher.Proxy ? "Lädt über den Browser – Seite offen lassen" : "Wird vorbereitet …");
            HlsCore.Listener l = new HlsCore.Listener() {
                @Override public void progress(int done, int total) {
                    long now = System.currentTimeMillis();
                    state.put(id, done + "/" + total);
                    if (now - lastShown[0] < 700 && done < total) return;
                    lastShown[0] = now;
                    progressNote(c, nm, id, shortTitle, done, total, null);
                }
                @Override public boolean cancelled() { return Boolean.TRUE.equals(cancel.get(id)); }
            };
            if ("file".equals(kind)) {
                File f = Fetcher.toFile(net, url, new File(work, "datei." + extOf(url, "mp4")), l);
                String ext = extOf(url, "mp4");
                Uri saved = save(c, f, safeName(title, ext), "video/" + ("mov".equals(ext) ? "quicktime" : "mkv".equals(ext) ? "x-matroska" : ext));
                state.put(id, "fertig");
                doneNote(c, nm, id, shortTitle, saved, "video/*", null);
                return;
            }
            HlsCore.Result r = "dash".equals(kind) ? new DashCore(net, work, 4).run(url, l) : new HlsCore(net, work, 4).run(url, l);
            progressNote(c, nm, id, shortTitle, 0, 0, "Wird zu MP4 zusammengesetzt …");
            state.put(id, "zusammensetzen");
            File mp4 = new File(work, "fertig.mp4");
            File out;
            String mime;
            if (mux(r.video, r.audio, mp4)) { out = mp4; mime = "video/mp4"; }
            else {
                // Zusammensetzen ging nicht (seltenes Format): Datei so speichern, wie sie geladen wurde
                out = r.video;
                mime = r.fmp4 ? "video/mp4" : "video/mp2t";
            }
            String ext = out.getName().endsWith(".ts") ? "ts" : "mp4";
            Uri saved = save(c, out, safeName(title + (r.quality.isEmpty() ? "" : " (" + r.quality + ")"), ext), mime);
            state.put(id, "fertig");
            doneNote(c, nm, id, shortTitle, saved, mime, null);
        } catch (Exception e) {
            boolean cancelled = Boolean.TRUE.equals(cancel.get(id));
            state.put(id, cancelled ? "abgebrochen" : "Fehler: " + e.getMessage());
            if (cancelled) nm.cancel(id);
            else doneNote(c, nm, id, shortTitle, null, null, e.getMessage());
        } finally {
            cancel.remove(id);
            Fetcher.Proxy px = proxies.remove(id);
            if (px != null) px.close();
            deleteTree(work);
        }
    }

    private static void progressNote(Context c, NotificationManager nm, int id, String title, int done, int total, String text) {
        PendingIntent stop = PendingIntent.getService(c, id, new Intent(c, HelferService.class).setAction("cancel").putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Abbrechen", stop).build());
        if (total > 0) {
            int pct = (int) (100L * done / total);
            b.setProgress(total, done, false).setContentText(pct + " % · " + done + " von " + total + " Teilen");
        } else {
            b.setProgress(0, 0, true).setContentText(text != null ? text : "Lädt …");
        }
        nm.notify(id, b.build());
    }

    private static void doneNote(Context c, NotificationManager nm, int id, String title, Uri file, String mime, String error) {
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setContentTitle(title)
                .setAutoCancel(true);
        if (file != null) {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("Gespeichert in Download/" + FOLDER);
            if (!"content".equals(file.getScheme())) { nm.notify(id, b.build()); return; }   // Android 8/9: nur Hinweis
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(file, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_error).setContentText("Download fehlgeschlagen: " + error)
                    .setStyle(new Notification.BigTextStyle().bigText("Download fehlgeschlagen: " + error));
        }
        nm.notify(id, b.build());
    }

    // ---------- Zu MP4 zusammensetzen (Bild + Ton, ohne neu zu kodieren) ----------
    static boolean mux(File video, File audio, File out) {
        List<MediaExtractor> exs = new ArrayList<>();
        MediaMuxer mx = null;
        try {
            mx = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            List<int[]> map = new ArrayList<>();          // {extractor, srcTrack, dstTrack}
            File[] files = audio != null ? new File[]{video, audio} : new File[]{video};
            int maxIn = 2 << 20;
            for (int e = 0; e < files.length; e++) {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(files[e].getPath());
                exs.add(ex);
                for (int t = 0; t < ex.getTrackCount(); t++) {
                    MediaFormat f = ex.getTrackFormat(t);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    boolean isV = mime.startsWith("video/"), isA = mime.startsWith("audio/");
                    if (!isV && !isA) continue;
                    if (audio != null && e == 0 && isA) continue;   // Ton kommt aus der eigenen Spur
                    if (audio != null && e == 1 && isV) continue;
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = Math.max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    ex.selectTrack(t);
                    map.add(new int[]{e, t, mx.addTrack(f)});
                }
            }
            if (map.isEmpty()) return false;
            // gemeinsamer Startzeitpunkt (Streams beginnen oft nicht bei 0)
            long base = Long.MAX_VALUE;
            for (MediaExtractor ex : exs) { long t0 = ex.getSampleTime(); if (t0 >= 0) base = Math.min(base, t0); }
            if (base == Long.MAX_VALUE) base = 0;
            mx.start();
            ByteBuffer buf = ByteBuffer.allocate(Math.max(maxIn, 8 << 20));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int samples = 0;
            for (int e = 0; e < exs.size(); e++) {
                MediaExtractor ex = exs.get(e);
                while (true) {
                    buf.clear();
                    int n = ex.readSampleData(buf, 0);
                    if (n < 0) break;
                    int src = ex.getSampleTrackIndex();
                    int dst = -1;
                    for (int[] m : map) if (m[0] == e && m[1] == src) { dst = m[2]; break; }
                    if (dst >= 0) {
                        info.offset = 0;
                        info.size = n;
                        info.presentationTimeUs = Math.max(0, ex.getSampleTime() - base);
                        info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mx.writeSampleData(dst, buf, info);
                        samples++;
                    }
                    ex.advance();
                }
            }
            mx.stop();
            return samples > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            for (MediaExtractor ex : exs) { try { ex.release(); } catch (Throwable ignored) {} }
            if (mx != null) { try { mx.release(); } catch (Throwable ignored) {} }
        }
    }

    // ---------- Speichern in Download/Video-Assistent ----------
    static Uri save(Context c, File f, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("Speichern nicht möglich");
            try (InputStream in = new FileInputStream(f); OutputStream o = cr.openOutputStream(uri)) {
                if (o == null) throw new Exception("Speichern nicht möglich");
                copy(in, o);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        // Android 8/9: in den eigenen Ordner der App (Android/data/de.evp.helfer/files/Download)
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(dst)) { copy(in, o); }
        return Uri.fromFile(dst);
    }

    private static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] b = new byte[256 << 10];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] ch = f.listFiles();
        if (ch != null) for (File x : ch) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
