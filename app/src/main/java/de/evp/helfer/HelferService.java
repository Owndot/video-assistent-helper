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
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

import poc.Core;

/**
 * Lokaler Empfänger für Kadri – ausschließlich über den sicheren Kanal (SPAKE2-Kopplung, Noise-KK, geprüfter
 * Rust-Kern) auf 127.0.0.1:47899 ({@link ChannelServer}). Kein Klartext-Weg, kein fester Schlüssel, kein alter Port.
 *
 * Steuerbefehle ({@link ControlCommands}): status, bright (Helligkeit, solange der Player offen ist), vol
 * (Medienlautstärke), beat (Lebenszeichen, sonst Helligkeit nach 30 s zurück), end (Helligkeit sofort zurück).
 * Downloads über den Helper sind in dieser Stufe nicht erreichbar (kommen später über denselben Kanal).
 *
 * Kopplung, Widerruf und Identitäts-Reset nur über die eigene Oberfläche (MainActivity); der Dienst ist nicht
 * exportiert. Registrierungen werden über {@link SnapshotWriter} geschrieben (nur bei Änderung, in Reihenfolge).
 */
public class HelferService extends Service implements ChannelServer.Host, ControlCommands.Device {
    static final int PORT = ChannelServer.PORT;
    static final String CHANNEL = "helfer";
    static final String WRAP_ALIAS = "helfer-channel-identity-wrap-v1";
    static volatile boolean running = false;
    static volatile String startupError = null;
    static volatile HelferService instance;
    /** Kopplungscode nur zur Anzeige (nie protokolliert) und sein Ablauf (elapsedRealtime). */
    volatile String code;
    volatile long codeExpiresAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ChannelServer server;
    private Mp4Downloads mp4;   // null: direkte Downloads nicht verfügbar (Kanal lehnt dl-Befehle ab)
    private IdentityStore identityStore;
    private RegistryStore registryStore;
    private SnapshotWriter writer;
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
        Keys.applyLang(this);   // Sprache aus der App (auch nach dem Einschalten des Handys)
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        startInForeground();
        startupError = null;
        try { Downloads.serviceStarted(this); }
        catch(Exception e) { startupError="Interrupted download cleanup failed: "+e.getMessage();stopSelf();return; }
        // Phase C2: direkte MP4-Downloads. Unlesbares Journal → keine Downloads (sonst wären Doppelstarts möglich).
        try { mp4 = Mp4Downloads.get(this); mp4.engine.serviceStarted(); }
        catch (Exception e) { mp4 = null; }
        String problem = startChannel();
        if (problem != null) {
            startupError = problem;
            android.widget.Toast.makeText(this, startupError, android.widget.Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        instance = this;
        running = true;
        main.postDelayed(watchdog, 10000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) return START_NOT_STICKY;
        if (intent != null && "cancel".equals(intent.getAction())) {
            // "Abbrechen" in der Download-Benachrichtigung
            int cancelledId = intent.getIntExtra("id", -1);
            Downloads.cancelJob(cancelledId);
            return START_STICKY;
        }
        if (intent != null && Mp4Downloads.ACTION_CANCEL.equals(intent.getAction())) {
            // "Abbrechen" in der Benachrichtigung eines direkten MP4-Downloads
            String job = intent.getStringExtra(Mp4Downloads.EXTRA_JOB);
            if (mp4 != null && Mp4Downloads.validJobId(job)) mp4.engine.cancel(job);
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
        stopChannel();   // zuerst: Port frei, bevor „beendet“ sichtbar wird (sonst „Port belegt“ beim schnellen Neustart)
        running = false;
        instance = null;
        main.removeCallbacks(watchdog);
        Downloads.stopAll();
        if (mp4 != null) mp4.engine.stopAll();
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
                .setContentTitle(Tr.t("Video-Assistent Helper", "Video Assistant Helper"))
                .setContentText(Tr.t("Bereit für Helligkeit und Lautstärke", "Ready for brightness and volume"))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---------- sicherer Kanal ----------
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public void event(String line) { /* keine Protokollierung von Kanalereignissen in der App */ }

    @Override public void persist(ChannelServer from) throws Exception {
        writer.persist(() -> {
            synchronized (ChannelServer.CORE) {
                return ChannelServer.coreOwner == from ? Core.hostExportRegistrations() : null;
            }
        });
    }

    private synchronized void stopChannel() {
        if (server != null) server.stop();
        synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = null; }   // laufende Anfragen erreichen den Kern nicht mehr
        server = null;
        code = null;
    }

    /** Lädt Identität und Registrierungen und bindet den Kanal; null = bereit, sonst der Grund (kein Ausweichen). */
    private synchronized String startChannel() {
        stopChannel();
        try {
            if (identityStore == null) {
                identityStore = new IdentityStore(getFilesDir(), WRAP_ALIAS, getPackageName());
                registryStore = new RegistryStore(getFilesDir(), Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1));
                writer = new SnapshotWriter(registryStore::save);
            }
            // Eine neue Identität nur bei der Erstinstallation: gespeicherte Registrierungen ohne Identität sind
            // ein Widerspruch, kein Erststart (keine stille Neu-Identität).
            IdentityStore.Result id = identityStore.load(!registryStore.exists());
            if (id.identity == null) return Tr.t("Kanal-Identität nicht lesbar (" + id.state + ") – in der App „Identität zurücksetzen“ und neu koppeln.",
                "Channel identity unreadable (" + id.state + ") – use “Reset identity” in the app and pair again.");
            ChannelServer s = new ChannelServer(this, this, mp4 == null ? null : mp4.engine);
            try {
                RegistryStore.Loaded regs = registryStore.load();
                synchronized (ChannelServer.CORE) {
                    Core.hostInit(id.identity, regs.blob, now(), regs.sameBoot);
                    ChannelServer.coreOwner = s;
                }
                persist(s);
            } finally {
                java.util.Arrays.fill(id.identity, (byte) 0);
            }
            if (!s.start()) {
                synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = null; }
                return Tr.t("Port " + PORT + " ist belegt. Stoppe den anderen Helper (z. B. Test- oder Debug-Version) und starte diese App erneut.",
                    "Port " + PORT + " is busy. Stop the other helper (e.g. test or debug version) and retry this app.");
            }
            server = s;
            return null;
        } catch (Throwable e) {   // auch fehlende native Bibliothek (UnsatisfiedLinkError): Kanal aus, kein Ausweichen
            synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = null; }
            return Tr.t("Sicherer Kanal nicht verfügbar (" + e.getClass().getSimpleName() + ").", "Secure channel unavailable (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Öffnet das einzige Kopplungsfenster (120 s, ein Versuch) und liefert den Code nur zur Anzeige. */
    synchronized String openPairingWindow() {
        if (server == null) return null;
        synchronized (ChannelServer.CORE) {
            if (ChannelServer.coreOwner != server) return null;
            code = Core.windowOpen(null, now(), ChannelServer.PAIR_TTL_MS);
        }
        codeExpiresAt = now() + ChannelServer.PAIR_TTL_MS;
        return code;
    }

    /** Registrierungen (öffentliche Schlüssel und IDs, keine Geheimnisse) für die Anzeige. */
    synchronized java.util.List<Registration> registrations() {
        java.util.List<Registration> out = new java.util.ArrayList<>();
        if (server == null) return out;
        byte[] b;
        synchronized (ChannelServer.CORE) {
            if (ChannelServer.coreOwner != server) return out;
            b = Core.hostExportRegistrations();
        }
        for (int at = 0; at + 57 <= b.length; at += 57) out.add(new Registration(java.util.Arrays.copyOfRange(b, at, at + 16), b[at + 48] == 1));
        return out;
    }

    static final class Registration {
        final byte[] id; final boolean confirmed;
        Registration(byte[] id, boolean confirmed) { this.id = id; this.confirmed = confirmed; }
        /** Erste 8 Hex-Zeichen – dieselbe Kurzform zeigt Kadri in den Optionen. */
        String shortId() { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 4; i++) sb.append(String.format(Locale.ROOT, "%02x", id[i] & 0xff)); return sb.toString(); }
    }

    /**
     * Widerruft eine Registrierung (id) oder alle (id == null), beendet deren Sitzungen und schließt ein offenes
     * Kopplungsfenster beim Gesamtwiderruf. true erst, wenn der Widerruf gespeichert ist; scheitert das Speichern,
     * wird der Kanal beendet (die Datei könnte die alten Registrierungen noch enthalten).
     */
    synchronized boolean revoke(byte[] id) {
        if (server == null) return false;
        ChannelServer s = server;
        synchronized (ChannelServer.CORE) {
            if (ChannelServer.coreOwner != s) return false;
            if (id == null) Core.hostRevokeAll(); else Core.hostRevoke(id);
        }
        try { persist(s); }
        catch (Exception e) {
            stopChannel();
            startupError = revokeFailedText();
            return false;
        }
        return true;
    }

    /** Fehlgeschlagener Widerruf: die gespeicherte Registrierung kann nach einem Neustart weiter gelten. */
    static String revokeFailedText() {
        return Tr.t("Trennen fehlgeschlagen. Die bisherige Kopplung kann nach einem Neustart weiterhin bestehen. Bitte erneut trennen und den Erfolg überprüfen.",
            "Disconnect failed. The previous pairing may still exist after a restart. Please disconnect again and check that it worked.");
    }

    /** Ausdrückliche Nutzeraktion: neue Helper-Identität, alle Registrierungen gelöscht; alle Browser koppeln neu. */
    synchronized String resetIdentity() {
        stopChannel();
        try {
            // Erst die Registrierungen: ein unterbrochener Reset hinterlässt die alte Identität ohne Registrierungen
            // (stimmig), nie umgekehrt.
            writer.delete(registryStore::delete);
            identityStore.reset();
        } catch (Exception e) {
            return Tr.t("Zurücksetzen fehlgeschlagen.", "Reset failed.");
        }
        String problem = startChannel();
        startupError = problem;
        return problem;
    }

    // ---------- Steuerbefehle (ControlCommands.Device) ----------
    @Override public void bright(float level) {
        lastBeat = System.currentTimeMillis();
        main.post(() -> setBright(level));
    }
    @Override public void vol(float level) { setVol(level); }
    @Override public void beat() { lastBeat = System.currentTimeMillis(); }
    @Override public void end() { main.post(this::clearBright); }

    @Override public String status() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean writeOk = Settings.System.canWrite(this);
        float b = curBright >= 0 ? curBright : systemBright();
        return ControlCommands.statusJson(appVersion(), overlayOk, writeOk, cur, max, b);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
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
        if (!running) startupError = null;
        Intent i = new Intent(c, HelferService.class);
        c.startForegroundService(i);
    }
}
