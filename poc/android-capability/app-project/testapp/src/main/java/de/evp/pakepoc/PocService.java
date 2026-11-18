package de.evp.pakepoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

import poc.Core;

/**
 * Foreground service hosting the loopback test server. Not exported: only
 * this app's own activity (and its own instrumentation) can control it.
 * All deadlines use SystemClock.elapsedRealtime() (review R3).
 *
 * Registry persistence (reviews F1, F4): every change is written through one
 * {@link SnapshotWriter}, which exports and writes under a single lock and
 * only when the exported bytes changed. A revocation is reported as durable
 * only after its snapshot is on disk; if that fails the server is stopped.
 */
public final class PocService extends Service implements PocServer.Host {
    static final String A_START = "start", A_OPEN = "open", A_REVOKE = "revoke", A_ROGUE_ON = "rogue-on",
        A_ROGUE_OFF = "rogue-off", A_RESET_IDENTITY = "reset-identity", A_STOP = "stop";
    static final String WRAP_ALIAS = "pakepoc-identity-wrap-v1";
    private static final String CHANNEL = "pakepoc";

    static volatile PocService instance;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private PocServer server;
    private IdentityStore identityStore;
    private RegistryStore registryStore;
    SnapshotWriter writer;
    volatile String identityState = "not loaded";
    volatile String helperPublicPrefix = "";
    volatile String code;            // shown on screen only, never logged
    volatile long codeExpiresAt;
    volatile boolean rogue;
    volatile String serverState = "stopped";

    static void log(String line) {
        synchronized (LOG) {
            LOG.addLast(String.format(Locale.ROOT, "%.1fs %s", SystemClock.elapsedRealtime() / 1000.0, line));
            while (LOG.size() > 40) LOG.removeFirst();
        }
    }

    static String logText() {
        synchronized (LOG) { return String.join("\n", LOG); }
    }

    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public void event(String line) { log(line); }

    @Override public void persist(PocServer from) throws Exception {
        if (from.rogue) return;   // the rogue host's ephemeral state is never saved
        try {
            writer.persist(() -> {
                synchronized (PocServer.CORE) {
                    return PocServer.coreOwner == from ? Core.hostExportRegistrations() : null;
                }
            });
        } catch (Exception e) {
            log("PERSIST_FAILED " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TEST PAKE-PoC", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("TEST PAKE-PoC – kein Helper")
            .setContentText("Testserver nur auf 127.0.0.1:47899")
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        identityStore = new IdentityStore(getFilesDir(), WRAP_ALIAS, getPackageName());
        registryStore = new RegistryStore(getFilesDir(), Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1));
        writer = new SnapshotWriter(registryStore::save);
        startGenuine();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? A_START : String.valueOf(intent.getAction());
        switch (a) {
            case A_OPEN: openWindow(); break;
            case A_REVOKE: revokeAll(); break;
            case A_ROGUE_ON: rogueOn(); break;
            case A_ROGUE_OFF: rogueOff(); break;
            case A_RESET_IDENTITY: resetIdentity(); break;
            case A_STOP: stopSelf(); break;
            default: break;
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopServer();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private byte[] probeHtml() {
        try (InputStream in = getAssets().open("kadri-poc-probe.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    private synchronized void stopServer() {
        if (server != null) server.stop();
        synchronized (PocServer.CORE) { PocServer.coreOwner = null; }   // in-flight requests can no longer touch the core
        server = null;
        serverState = "stopped";
    }

    synchronized void startGenuine() {
        stopServer();
        rogue = false;
        // A first identity may only be created on a fresh install: registrations
        // on disk without an identity are an inconsistency, not a first start (F2).
        IdentityStore.Result id = identityStore.load(!registryStore.exists());
        if (id.identity == null) {
            identityState = id.state + (id.detail == null ? "" : ": " + id.detail);
            log("IDENTITY " + id.state + " – server not started; explicit reset required");
            return;
        }
        PocServer s = new PocServer(this, false, probeHtml());
        try {
            RegistryStore.Loaded regs = registryStore.load();
            String pub;
            synchronized (PocServer.CORE) {
                pub = Core.hostInit(id.identity, regs.blob, now(), regs.sameBoot);
                PocServer.coreOwner = s;
            }
            helperPublicPrefix = pub.substring(0, 8);
            identityState = id.state + (regs.sameBoot ? "" : " (after reboot: pending registrations dropped)");
            persist(s);
        } catch (Exception e) {
            synchronized (PocServer.CORE) { PocServer.coreOwner = null; }
            identityState = "registry error: " + e.getClass().getSimpleName() + " – server not started";
            log("REGISTRY_ERROR " + e.getClass().getSimpleName());
            return;
        } finally {
            Arrays.fill(id.identity, (byte) 0);
        }
        server = s;
        serverState = server.start() ? "listening 127.0.0.1:47899 (+ probe page :47900)" : "port 47899 busy";
        log("SERVER " + serverState);
    }

    /** Opens the single pairing window and returns the code (display only). */
    synchronized String openWindow() {
        if (rogue || server == null) return null;
        synchronized (PocServer.CORE) {
            if (PocServer.coreOwner != server) return null;
            code = Core.windowOpen(null, now(), PocServer.PAIR_TTL_MS);
        }
        codeExpiresAt = now() + PocServer.PAIR_TTL_MS;
        log("WINDOW_OPEN 120s");
        return code;
    }

    /**
     * Revokes every registration, closes all sessions and any open pairing
     * window (so a pairing started before cannot complete after it), then
     * saves. Returns true only once the revocation is on disk. If saving
     * fails, the server is stopped and the failure is shown: the file may
     * still hold the old registrations, so nothing claims durability.
     */
    synchronized boolean revokeAll() {
        if (rogue || server == null) {
            log("REVOKE_SKIPPED server not running");
            return false;
        }
        PocServer s = server;
        synchronized (PocServer.CORE) {
            if (PocServer.coreOwner != s) return false;
            Core.hostRevokeAll();
        }
        try {
            persist(s);
        } catch (Exception e) {
            stopServer();
            serverState = "stopped: revocation NOT saved – stored registrations may still be valid";
            log("REVOKE_NOT_DURABLE " + e.getClass().getSimpleName());
            return false;
        }
        log("REVOKED_ALL (saved)");
        return true;
    }

    synchronized void rogueOn() {
        stopServer();
        rogue = true;
        byte[] eph = Core.identityGenerate();
        PocServer s = new PocServer(this, true, null);
        synchronized (PocServer.CORE) {
            Core.hostInit(eph, new byte[0], now(), true);
            PocServer.coreOwner = s;
        }
        Arrays.fill(eph, (byte) 0);
        server = s;
        serverState = server.start() ? "ROGUE listener on 127.0.0.1:47899" : "port 47899 busy";
        log("ROGUE_ON");
    }

    synchronized void rogueOff() {
        String summary = server != null && rogue ? server.rogueSeen + " plaintextHits=" + server.roguePlaintextHits : "";
        log("ROGUE_OFF " + summary);
        startGenuine();
    }

    synchronized void resetIdentity() {
        stopServer();
        try {
            // Registrations first: an interrupted reset then leaves the old
            // identity without registrations (consistent), never the reverse.
            writer.delete(registryStore::delete);
            identityStore.reset();
            log("IDENTITY_RESET");
        } catch (Exception e) {
            log("IDENTITY_RESET_FAILED " + e.getClass().getSimpleName());
        }
        startGenuine();
    }

    String status() {
        String stats;
        try { synchronized (PocServer.CORE) { stats = rogue ? "rogue" : Core.hostStats(now()); } }
        catch (Exception e) { stats = "unavailable"; }
        return "Server: " + serverState
            + "\nIdentität: " + identityState + (helperPublicPrefix.isEmpty() ? "" : " (Helper-Schlüssel " + helperPublicPrefix + "…)")
            + "\nSchutz: " + identityStore.wrappingKeyInfo()
            + "\nRegister: " + stats;
    }
}
