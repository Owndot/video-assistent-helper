package de.evp.helfer.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.OkHttp;

/**
 * Android-Einstieg in die sichere Netzwerkschicht: ein gemeinsamer {@link SafeHttpClient} mit strengen Regeln.
 * Setzt den Anwendungs-Context für OkHttp (die AndroidX-Startkomponente ist entfernt) und liefert das NAT64-Präfix
 * des aktiven Netzes (ab Android 11 von der Plattform; Android 8–10 per RFC 7050, bei Unklarheit IPv6 gesperrt).
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

    private static Network cachedNetwork;
    private static long cachedUntil;
    private static List<AddressPolicy.Nat64Prefix> cached;

    static List<AddressPolicy.Nat64Prefix> networkNat64(Context app) {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network n;
        try {
            n = cm == null ? null : cm.getActiveNetwork();
        } catch (SecurityException e) {
            throw e;   // fehlende Berechtigung ACCESS_NETWORK_STATE ist ein Fehler, kein stiller Verzicht auf die Prüfung
        }
        if (Build.VERSION.SDK_INT >= 30) return platformNat64(cm, n);
        return discoveredNat64(cm, n);
    }

    private static List<AddressPolicy.Nat64Prefix> platformNat64(ConnectivityManager cm, Network n) {
        try {
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            IpPrefix p = lp == null ? null : lp.getNat64Prefix();
            if (p == null) return Collections.emptyList();
            InetAddress a = p.getAddress();
            if (!(a instanceof Inet6Address)) return Collections.emptyList();
            return Collections.singletonList(new AddressPolicy.Nat64Prefix(a.getAddress(), p.getPrefixLength()));
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            // Ohne Präfix gilt nur 64:ff9b::/96 als NAT64; netzspezifische Präfixe bleiben dann gewöhnliche IPv6-Adressen.
            return Collections.emptyList();
        }
    }

    /**
     * Android 8–10: keine Plattform-API für das NAT64-Präfix. Erkennung nach RFC 7050 im aktiven Netz, je Netz 60 s
     * zwischengespeichert (nach einem Fehlschlag 10 s); jede Unklarheit sperrt alle IPv6-Ziele (siehe
     * {@link Nat64Discovery}).
     */
    static synchronized List<AddressPolicy.Nat64Prefix> discoveredNat64(ConnectivityManager cm, Network n) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cached != null && n != null && n.equals(cachedNetwork) && now < cachedUntil) return cached;
        if (n == null) return Nat64Discovery.ALL_IPV6;                       // kein Netz: nichts zu erreichen
        boolean hasIpv4 = false;
        List<InetAddress> answers = null;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) if (la.getAddress() instanceof Inet4Address) hasIpv4 = true;
            answers = Arrays.asList(n.getAllByName(Nat64Discovery.NAME));
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            answers = null;                                                  // Abfrage gescheitert: sicher fehlschlagen
        }
        List<AddressPolicy.Nat64Prefix> result = Nat64Discovery.decide(hasIpv4, answers);
        cachedNetwork = n;
        cached = result;
        cachedUntil = now + (answers == null ? 10_000 : 60_000);
        return result;
    }
}
