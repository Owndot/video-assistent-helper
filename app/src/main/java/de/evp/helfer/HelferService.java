package de.evp.helfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lokaler Empfänger für das Userscript "Video-Assistent".
 *
 * Lauscht NUR auf 127.0.0.1 (von außen und aus dem WLAN nicht erreichbar). Jede Anfrage braucht den Kopf
 * "X-EVP-Key" – normale Webseiten können den nicht mitschicken (der Browser würde vorher nachfragen und
 * wir antworten darauf nicht), nur das Userscript über Violentmonkey kann es.
 *
 *   GET /ping            Zustand (Rechte, aktuelle Lautstärke/Helligkeit)
 *   GET /bright?v=0..1   Helligkeit setzen – solange der Player offen ist
 *   GET /vol?v=0..1      Medienlautstärke setzen (ohne v: nur abfragen)
 *   GET /beat            "Player ist noch offen" (sonst wird die Helligkeit nach 30 s zurückgegeben)
 *   GET /end             Player zu: Helligkeit sofort zurück wie vorher
 */
public class HelferService extends Service {
    static final int PORT = 47811;
    static final String KEY = "evp-1";
    static final String CHANNEL = "helfer";
    static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private ServerSocket server;
    private Thread acceptThread;
    private WindowManager wm;
    private AudioManager am;

    // Helligkeit
    private View overlay;
    private WindowManager.LayoutParams lp;
    private volatile float curBright = -1f;     // -1 = nicht übernommen (System wie immer)
    private boolean wroteSettings = false;
    private int savedBright = -1, savedMode = -1;
    private volatile long lastBeat = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        startInForeground();
        startServer();
        running = true;
        main.postDelayed(watchdog, 10000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "cancel".equals(intent.getAction())) {
            // "Abbrechen" in der Download-Benachrichtigung
            Downloads.cancel.put(intent.getIntExtra("id", -1), true);
            return START_STICKY;
        }
        if (intent != null && "test".equals(intent.getAction())) {
            // Knopf "Ausprobieren" in der App: 3 Sekunden dunkel, dann zurück
            lastBeat = System.currentTimeMillis();
            main.post(() -> setBright(0.05f));
            main.postDelayed(this::clearBright, 3000);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(watchdog);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        clearBright();
        super.onDestroy();
    }

    // ---------- Benachrichtigung (Android verlangt sie für Dienste im Hintergrund) ----------
    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Helfer bereit", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setDescription("Zeigt an, dass der Helfer für den Videoplayer bereit ist");
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Video-Assistent Helfer")
                .setContentText("Bereit für Helligkeit und Lautstärke")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---------- lokaler Mini-Server ----------
    private void startServer() {
        acceptThread = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
                while (!server.isClosed()) {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            }
        }, "evp-helfer");
        acceptThread.start();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String first = in.readLine();
            if (first == null) { s.close(); return; }
            String key = null, line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("x-evp-key")) key = line.substring(c + 1).trim();
            }
            String[] parts = first.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            if (!"GET".equals(method) || !KEY.equals(key)) { reply(s, 403, "{\"ok\":false}"); return; }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> p = parseQuery(query);
            reply(s, 200, route(path, p));
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private String route(String path, Map<String, String> p) {
        switch (path) {
            case "/ping":
                lastBeat = System.currentTimeMillis();
                return status();
            case "/bright": {
                lastBeat = System.currentTimeMillis();
                final float v = parse(p.get("v"), -1f);
                if (v >= 0) main.post(() -> setBright(v));
                return status();
            }
            case "/vol": {
                String v = p.get("v");
                if (v != null) setVol(parse(v, 0f));
                return status();
            }
            case "/beat":
                lastBeat = System.currentTimeMillis();
                return status();
            case "/download": {
                // Video speichern: u = Adresse, ref = Seite (Referer), ua = Browser-Kennung, t = Titel, k = file|hls|auto
                String u = p.get("u");
                if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) return "{\"ok\":false,\"error\":\"keine Adresse\"}";
                try {
                    int id = Downloads.start(this, u, p.get("ref"), p.get("ua"), p.get("t"), p.get("k"));
                    return "{\"ok\":true,\"id\":" + id + "}";
                } catch (Exception e) {
                    return "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
                }
            }
            case "/dlstate": {
                int id = (int) parse(p.get("id"), -1);
                String st = Downloads.state.get(id);
                return "{\"ok\":true,\"state\":\"" + (st == null ? "" : st.replace("\"", "'")) + "\"}";
            }
            case "/end":
                main.post(this::clearBright);
                return "{\"ok\":true}";
            default:
                return "{\"ok\":false,\"error\":\"unbekannt\"}";
        }
    }

    private String status() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean writeOk = Settings.System.canWrite(this);
        float b = curBright >= 0 ? curBright : systemBright();
        return String.format(Locale.US,
                "{\"ok\":true,\"v\":2,\"dl\":true,\"app\":\"%s\",\"overlay\":%b,\"write\":%b,\"vol\":%.4f,\"volStep\":%d,\"volMax\":%d,\"bright\":%.4f}",
                appVersion(), overlayOk, writeOk, max > 0 ? (float) cur / max : 0f, cur, max, b);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private static void reply(Socket s, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
        s.close();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    private static float parse(String s, float def) {
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }

    // ---------- Lautstärke ----------
    private void setVol(float f) {
        f = Math.max(0f, Math.min(1f, f));
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int idx = Math.round(f * max);
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0); } catch (Exception ignored) {}
    }

    // ---------- Helligkeit ----------
    private float systemBright() {
        try {
            ContentResolver cr = getContentResolver();
            return Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    /** Läuft auf dem Haupt-Thread. */
    private void setBright(float v) {
        v = Math.max(0.01f, Math.min(1f, v));
        curBright = v;
        if (Settings.canDrawOverlays(this)) {
            // Winziges, unsichtbares Fenster ganz oben: dessen Helligkeitswunsch gilt für den ganzen Bildschirm.
            // Entfernt man es, ist sofort wieder die normale (auch automatische) Helligkeit da – nichts wird verstellt.
            try {
                if (overlay == null) {
                    overlay = new View(this);
                    lp = new WindowManager.LayoutParams(1, 1,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.alpha = 0.5f;   // unter 0,8: Android lässt Berührungen dann sicher durch
                    lp.screenBrightness = v;
                    wm.addView(overlay, lp);
                } else {
                    lp.screenBrightness = v;
                    wm.updateViewLayout(overlay, lp);
                }
                return;
            } catch (Exception e) {
                overlay = null;
            }
        }
        if (Settings.System.canWrite(this)) {
            // Notlösung ohne Einblende-Recht: Systemeinstellung (wird beim Schließen zurückgestellt)
            ContentResolver cr = getContentResolver();
            try {
                if (!wroteSettings) {
                    savedMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                    savedBright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
                    wroteSettings = true;
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * 255)));
            } catch (Exception ignored) {}
        }
    }

    /** Helligkeit zurückgeben (Player zu, kein Lebenszeichen mehr oder Dienst beendet). */
    private void clearBright() {
        curBright = -1f;
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
        if (wroteSettings) {
            ContentResolver cr = getContentResolver();
            try {
                if (savedBright >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, savedBright);
                if (savedMode >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
            } catch (Exception ignored) {}
            wroteSettings = false;
        }
    }

    // Sicherheitsnetz: Browser abgestürzt oder Tab geschlossen, ohne "Player zu" zu melden?
    // Nach 30 Sekunden ohne Lebenszeichen gibt es die normale Helligkeit zurück.
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if ((overlay != null || wroteSettings) && System.currentTimeMillis() - lastBeat > 30000) clearBright();
            if (running) main.postDelayed(this, 5000);
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, HelferService.class);
        c.startForegroundService(i);
    }
}
