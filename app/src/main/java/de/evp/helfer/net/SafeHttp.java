package de.evp.helfer.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import okhttp3.OkHttp;

/**
 * Android-Einstieg in die sichere Netzwerkschicht: ein gemeinsamer {@link SafeHttpClient} mit strengen Regeln.
 * Setzt den Anwendungs-Context für OkHttp (die AndroidX-Startkomponente ist entfernt) und liefert das NAT64-Präfix
 * des aktiven Netzes (ab Android 11; davor nur das bekannte Präfix 64:ff9b::/96).
 */
public final class SafeHttp {
    private static SafeHttpClient shared;

    private SafeHttp() {}

    public static synchronized SafeHttpClient client(Context context) {
        if (shared == null) {
            Context app = context.getApplicationContext();
            OkHttp.INSTANCE.initialize(app);
            shared = SafeHttpClient.strict(() -> networkNat64(app));
        }
        return shared;
    }

    static List<AddressPolicy.Nat64Prefix> networkNat64(Context app) {
        if (Build.VERSION.SDK_INT < 30) return Collections.emptyList();
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            IpPrefix p = lp == null ? null : lp.getNat64Prefix();
            if (p == null) return Collections.emptyList();
            InetAddress a = p.getAddress();
            if (!(a instanceof Inet6Address)) return Collections.emptyList();
            return Collections.singletonList(new AddressPolicy.Nat64Prefix(a.getAddress(), p.getPrefixLength()));
        } catch (SecurityException e) {
            throw e;   // fehlende Berechtigung ACCESS_NETWORK_STATE ist ein Fehler, kein stiller Verzicht auf die Prüfung
        } catch (RuntimeException e) {
            // Ohne Präfix gilt nur 64:ff9b::/96 als NAT64; netzspezifische Präfixe bleiben dann gewöhnliche IPv6-Adressen.
            return Collections.emptyList();
        }
    }
}
