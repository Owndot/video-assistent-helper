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

import java.util.Locale;
import java.util.Map;

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
 *   GET /download        Video speichern (Datei, HLS, DASH – direkt oder über den Browser)
 *   GET /pull, POST /push  Laden über den Browser: Anfragen abholen / Daten liefern
 *   GET /dlstate         Zustand eines Downloads
 */
public class HelferService extends Service {
    static final int PORT = 47811;
    static final String KEY = "evp-1";
    static final String CHANNEL = "helfer";
    static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MiniServer server;
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
        if (server != null) server.stop();
        clearBright();
        super.onDestroy();
    }

    // ---------- Benachrichtigung (Android verlangt sie für Dienste im Hintergrund) ----------
    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, Tr.t("Helper bereit", "Helper ready"), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setDescription(Tr.t("Zeigt an, dass der Helper für den Videoplayer bereit ist", "Shows that the helper is ready for the video player"));
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(Tr.t("Bereit für Helligkeit und Lautstärke", "Ready for brightness and volume"))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---------- lokaler Mini-Server ----------
    private void startServer() {
        // Schlüssel: nur der eigene Zufallsschlüssel dieser App (128 Bit). Den alten festen "evp-1" konnte jeder kennen –
        // er gilt nicht mehr, auch nicht vor dem Koppeln.
        server = new MiniServer(PORT, (k) -> {
            if (k.equals(Keys.key(this))) { Keys.markPaired(this); return true; }
            return false;
        }, new MiniServer.Handler() {
            @Override public String get(String path, Map<String, String> p) { return route(path, p); }
            @Override public String push(Map<String, String> p, byte[] body) { return MiniServer.push(Downloads.proxies, p, body); }
        });
        server.start();
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
                    int id = Downloads.start(this, u, p.get("ref"), p.get("ua"), p.get("c"), p.get("t"), p.get("k"), "1".equals(p.get("p")));
                    return "{\"ok\":true,\"id\":" + id + "}";
                } catch (Exception e) {
                    return "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
                }
            }
            case "/pull":
                return MiniServer.pull(Downloads.proxies, Downloads.state, p);
            case "/dlstate": {
                String st = Downloads.state.get((int) MiniServer.num(p.get("id"), -1));
                return "{\"ok\":true,\"state\":" + MiniServer.js(st) + "}";
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
                "{\"ok\":true,\"v\":3,\"dl\":true,\"dash\":true,\"proxy\":true,\"app\":\"%s\",\"overlay\":%b,\"write\":%b,\"vol\":%.4f,\"volStep\":%d,\"volMax\":%d,\"bright\":%.4f}",
                appVersion(), overlayOk, writeOk, max > 0 ? (float) cur / max : 0f, cur, max, b);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
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
