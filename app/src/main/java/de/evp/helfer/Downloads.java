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
    // File transfers must not wait behind DownloadManager watchers (up to 25s each).
    // Two bounded streams; idle worker stacks are released after 30 seconds.
    private static final java.util.concurrent.ThreadPoolExecutor credentialedFiles =
        new java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>());
    static { credentialedFiles.allowCoreThreadTimeOut(true); }
    private static final Map<Integer, Task> active = new ConcurrentHashMap<>();
    private static final Object fileNames = new Object();
    private static boolean initialized, stopped;
    private static final class Task {
        final int id; volatile boolean cancelled; boolean published; volatile Thread worker;
        Task(int id) { this.id=id; }
        void check() throws java.io.IOException {
            if(cancelled || Thread.currentThread().isInterrupted())throw new java.io.IOException("abgebrochen");
        }
        void committed() { published=true; state.put(id,"fertig"); }
    }
    static void cancelJob(int id) {
        Task task=active.get(id); if(task==null)return;
        synchronized(task) {
            if(task.published)return;
            task.cancelled=true; state.put(id,"abgebrochen");
            if(task.worker!=null)task.worker.interrupt();
        }
        Fetcher.Proxy proxy=proxies.get(id);if(proxy!=null)proxy.close();
    }
    static synchronized void stopAll() {
        stopped=true;
        for(Integer id:active.keySet())cancelJob(id);
    }
    static synchronized void serviceStarted(Context c) throws Exception {
        initialize(c);
        stopped=false;
    }

    /** Once per process, before accepting transfers: discard only owned unfinished work.
     * In-process service restart must not delete still-unwinding worker files.
     */
    static synchronized void initialize(Context c) throws Exception {
        if(initialized)return;
        File[] old=c.getCacheDir().listFiles();
        if(old!=null)for(File f:old)if(f.getName().matches("dl-?[0-9]+")) {
            deleteTree(f); if(f.exists())throw new java.io.IOException("Cannot remove interrupted download work");
        }
        File external=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] parts=external==null?null:new File(external,FOLDER).listFiles();
        if(parts!=null)for(File f:parts)if(f.getName().startsWith(".evp-pending-")&&f.getName().endsWith(".part")) {
            if(!f.delete()&&f.exists())throw new java.io.IOException("Cannot remove interrupted download staging");
        }
        if(Build.VERSION.SDK_INT>=29) {
            ContentResolver cr=c.getContentResolver();
            String folder=Environment.DIRECTORY_DOWNLOADS+"/"+FOLDER;
            try(android.database.Cursor rows=cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    "owner_package_name=? AND is_pending=1 AND (relative_path=? OR relative_path=?)",
                    new String[]{c.getPackageName(),folder+"/",folder},null)) {
                if(rows==null)throw new java.io.IOException("Cannot inspect interrupted pending downloads");
                while(rows.moveToNext())cr.delete(android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,rows.getLong(rows.getColumnIndexOrThrow(MediaStore.Downloads._ID))),null,null);
            }
        }
        initialized=true;
    }
    static final Map<Integer, String> state = new ConcurrentHashMap<>();
    static final Map<Integer, Fetcher.Proxy> proxies = new ConcurrentHashMap<>();

    static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(Tr.t("Fortschritt beim Herunterladen von Videos", "Progress of video downloads"));
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
    static synchronized int start(Context ctx, String url, String referer, String ua, String cookie, String title, String kind, boolean proxy) throws java.io.IOException {
        if(stopped)throw new java.io.IOException("Helper service is stopping");
        final Context c = ctx.getApplicationContext();
        try { initialize(c); } catch(Exception e) { throw new java.io.IOException("Interrupted download cleanup failed",e); }
        channel(c);
        final int id = JobIds.reserve(new File(c.getFilesDir(), "download-job-ids-v1"), directory -> {
            // Explicit directory fsync: a read-only FileChannel.force may be a no-op on a provider.
            java.io.FileDescriptor descriptor = null;
            try {
                descriptor = android.system.Os.open(directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
                // O_DIRECTORY is hidden from the public Android SDK. Check the opened descriptor instead.
                if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))
                    throw new java.io.IOException("Job ID sync target is not a directory");
                android.system.Os.fsync(descriptor);
            } catch (android.system.ErrnoException e) { throw new java.io.IOException("Job ID directory sync failed", e); }
            finally {
                if (descriptor != null) try { android.system.Os.close(descriptor); }
                catch (android.system.ErrnoException e) { throw new java.io.IOException("Job ID directory close failed", e); }
            }
        });
        String path = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        if (kind == null || kind.isEmpty() || "auto".equals(kind)) kind = path.endsWith(".m3u8") ? "hls" : path.endsWith(".mpd") ? "dash" : "file";
        final Fetcher net;
        if (proxy) {
            Fetcher.Proxy px = new Fetcher.Proxy(new File(c.getCacheDir(), "dl" + id));
            proxies.put(id, px);
            net = px;
        } else net = new Fetcher.Http(referer, ua, cookie);
        // DownloadManager replays manual headers across origin redirects.
        // Cookie/referrer-bearing files use the existing native hop policy and a bounded stream.
        boolean carriesCookie = !proxy && ((Fetcher.Http) net).cookieFor(url);
        boolean carriesReferrer = !proxy && Fetcher.Http.referrerFor(referer, url) != null;
        if ("file".equals(kind) && !proxy && !carriesCookie && !carriesReferrer) {
            state.put(id, "Download-Manager");
            long dmId = fileDownload(c, url, referer, ua, title);
            watch(c, id, dmId);
            return id;
        }
        active.put(id,new Task(id));
        state.put(id, "startet");
        final String k = kind;
        ExecutorService queue = "file".equals(k) && !proxy ? credentialedFiles : jobs;
        queue.execute(() -> streamJob(c, id, url, title, k, net));
        return id;
    }

    // ---------- Videodatei: Androids Download-Manager ----------
    private static long fileDownload(Context c, String url, String referer, String ua, String title) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        String name = safeName(title, extOf(url, "mp4"));
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        // DownloadManager replays custom headers across redirects, including TLS downgrades.
        // Referrer-bearing transfers use Fetcher.Http.streamToFile; never attach one here.
        if (ua != null && !ua.isEmpty()) r.addRequestHeader("User-Agent", ua);
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
        Task task=active.computeIfAbsent(id,Task::new);task.worker=Thread.currentThread();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        File work = new File(c.getCacheDir(), "dl" + id);
        final long[] lastShown = {0};
        String shortTitle = title == null || title.isEmpty() ? "Video" : (title.length() > 60 ? title.substring(0, 60) + "…" : title);
        try {
            task.check();
            if (!work.exists() && !work.mkdirs()) throw new Exception(Tr.t("Kein Arbeitsordner", "No working folder"));
            progressNote(c, nm, id, shortTitle, 0, 0, net instanceof Fetcher.Proxy ? Tr.t("Lädt über den Browser – Seite offen lassen", "Loading via the browser – keep the page open") : Tr.t("Wird vorbereitet …", "Preparing …"));
            HlsCore.Listener l = new HlsCore.Listener() {
                @Override public void progress(int done, int total) {
                    long now = System.currentTimeMillis();
                    synchronized(task) { if(task.cancelled||task.published)return;state.put(id, done + "/" + total); }
                    if (now - lastShown[0] < 700 && done < total) return;
                    lastShown[0] = now;
                    progressNote(c, nm, id, shortTitle, done, total, null);
                }
                @Override public boolean cancelled() { return task.cancelled; }
            };
            if ("file".equals(kind)) {
                File target = new File(work, "datei." + extOf(url, "mp4"));
                File f = net instanceof Fetcher.Http ? ((Fetcher.Http) net).streamToFile(url, target, l)
                    : Fetcher.toFile(net, url, target, l);
                String ext = extOf(url, "mp4");
                Uri saved = save(c, f, safeName(title, ext), "video/" + ("mov".equals(ext) ? "quicktime" : "mkv".equals(ext) ? "x-matroska" : ext),task);
                state.put(id, "fertig");
                doneNote(c, nm, id, shortTitle, saved, "video/*", null);
                return;
            }
            task.check();
            HlsCore.Result r = "dash".equals(kind) ? new DashCore(net, work, 4).run(url, l) : new HlsCore(net, work, 4).run(url, l);
            task.check();
            progressNote(c, nm, id, shortTitle, 0, 0, Tr.t("Wird zu MP4 zusammengesetzt …", "Joining into MP4 …"));
            synchronized(task) { task.check();state.put(id,"zusammensetzen"); }
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
            Uri saved = save(c, out, safeName(title + (r.quality.isEmpty() ? "" : " (" + r.quality + ")"), ext), mime,task);
            state.put(id, "fertig");
            doneNote(c, nm, id, shortTitle, saved, mime, null);
        } catch (Exception e) {
            if(task.published)return; // notification failure cannot undo an already committed file
            boolean cancelled = task.cancelled;
            state.put(id, cancelled ? "abgebrochen" : "Fehler: " + e.getMessage());
            if (cancelled) nm.cancel(id);
            else doneNote(c, nm, id, shortTitle, null, null, e.getMessage());
        } finally {
            task.worker=null;active.remove(id,task);
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
                .addAction(new Notification.Action.Builder(null, Tr.t("Abbrechen", "Cancel"), stop).build());
        if (total > 0) {
            int pct = (int) (100L * done / total);
            b.setProgress(total, done, false).setContentText(pct + " % · " + done + Tr.t(" von ", " of ") + total + Tr.t(" Teilen", " parts"));
        } else {
            b.setProgress(0, 0, true).setContentText(text != null ? text : Tr.t("Lädt …", "Loading …"));
        }
        nm.notify(id, b.build());
    }

    private static void doneNote(Context c, NotificationManager nm, int id, String title, Uri file, String mime, String error) {
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setContentTitle(title)
                .setAutoCancel(true);
        if (file != null) {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText(Tr.t("Gespeichert in Download/", "Saved in Download/") + FOLDER);
            if (!"content".equals(file.getScheme())) { nm.notify(id, b.build()); return; }   // Android 8/9: nur Hinweis
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(file, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_error).setContentText(Tr.t("Download fehlgeschlagen: ", "Download failed: ") + error)
                    .setStyle(new Notification.BigTextStyle().bigText(Tr.t("Download fehlgeschlagen: ", "Download failed: ") + error));
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
        return save(c,f,name,mime,null);
    }
    private static void check(Task task) throws java.io.IOException {
        if(task!=null)task.check();else Fetcher.checkCancelled(null);
    }
    private static Uri save(Context c,File f,String name,String mime,Task task) throws Exception {
        check(task);
        Object lock=task==null?new Object():task;
        if(Build.VERSION.SDK_INT>=29) {
            ContentResolver cr=c.getContentResolver();ContentValues cv=new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/"+FOLDER+"/");
            cv.put(MediaStore.Downloads.IS_PENDING,1);
            Uri uri=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
            if(uri==null)throw new java.io.IOException("Saving not possible");
            boolean committed=false;
            try {
                try(InputStream in=new FileInputStream(f);OutputStream out=cr.openOutputStream(uri)) {
                    if(out==null)throw new java.io.IOException("Saving not possible");
                    copy(in,out,task);out.flush();
                }
                synchronized(lock) {
                    check(task);cv.clear();cv.put(MediaStore.Downloads.IS_PENDING,0);
                    if(cr.update(uri,cv,null,null)!=1)throw new java.io.IOException("Pending download was not published");
                    committed=true;if(task!=null)task.committed();
                }
                return uri;
            } finally { if(!committed)cr.delete(uri,null,null); }
        }
        // Stage in the destination directory, close/sync first, then move without replacement.
        File external=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(external==null)throw new java.io.IOException("Download storage unavailable");
        File dir=new File(external,FOLDER);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("No download directory");
        File part=File.createTempFile(".evp-pending-",".part",dir);
        try {
            try(InputStream in=new FileInputStream(f);FileOutputStream out=new FileOutputStream(part)) {
                copy(in,out,task);out.getFD().sync();
            }
            synchronized(lock) {
                check(task);
                synchronized(fileNames) {
                    for(int n=0;n<10000;n++) {
                        int dot=name.lastIndexOf('.');String suffix=n==0?"":" ("+n+")";
                        String candidate=n==0?name:dot>0?name.substring(0,dot)+suffix+name.substring(dot):name+suffix;
                        File dst=new File(dir,candidate);
                        try { java.nio.file.Files.move(part.toPath(),dst.toPath()); }
                        catch(java.nio.file.FileAlreadyExistsException exists) { continue; }
                        if(task!=null)task.committed();return Uri.fromFile(dst);
                    }
                }
                throw new java.io.IOException("No unused download filename");
            }
        } finally { part.delete(); }
    }
    private static void copy(InputStream in,OutputStream out,Task task) throws Exception {
        byte[] buffer=new byte[64<<10];int n;
        while(true) {
            check(task);n=in.read(buffer);if(n<0)break;
            out.write(buffer,0,n);
        }
        check(task);
    }
    private static void deleteTree(File f) {
        if(f==null||!f.exists())return;
        File[] children=f.listFiles();if(children!=null)for(File x:children)deleteTree(x);
        f.delete();
    }
}
