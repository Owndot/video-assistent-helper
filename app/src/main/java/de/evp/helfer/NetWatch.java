package de.evp.helfer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import de.evp.helfer.dl.Retry;

/**
 * P4c: Verbindungsüberwachung für die automatische Wiederaufnahme der Downloads. Meldet nur „Netz mit Internet ja/nein“
 * (Standardnetz des Geräts) und weckt wartende Aufträge bei jeder Änderung; keine Adressen, keine Anfragen.
 */
final class NetWatch implements Retry.Network {
    private static NetWatch instance;
    private final ConnectivityManager cm;
    private volatile Retry retry;
    private volatile boolean online = true;

    private NetWatch(Context app) {
        cm = app.getSystemService(ConnectivityManager.class);
        online = current();
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { update(); }
                @Override public void onLost(Network n) { update(); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { update(); }
                @Override public void onBlockedStatusChanged(Network n, boolean blocked) { update(); }
            });
        } catch (RuntimeException e) {
            // ohne Rückruf: online() fragt jedes Mal den aktuellen Stand ab (Warten prüft alle 250 ms)
        }
    }

    /** Eine Instanz je Prozess; das {@link Retry} ist mit MP4 und HLS geteilt. */
    static synchronized Retry retry(Context c) {
        if (instance == null) {
            instance = new NetWatch(c.getApplicationContext());
            instance.retry = new Retry(instance);
        }
        return instance.retry;
    }

    private void update() {
        online = current();
        Retry r = retry;
        if (r != null) r.networkChanged();
    }

    private boolean current() {
        if (cm == null) return true;
        try {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true;      // im Zweifel versuchen (die Versuche sind ohnehin begrenzt)
        }
    }

    @Override public boolean online() {
        boolean now = current();      // Rückruf kann verspätet sein: der aktuelle Stand zählt
        online = now;
        return now;
    }
}
