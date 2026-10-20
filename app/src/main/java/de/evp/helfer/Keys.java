package de.evp.helfer;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Eigener Zufallsschlüssel der App (statt des festen "evp-1", den jede andere App kennen könnte). */
final class Keys {
    private Keys() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("helfer", Context.MODE_PRIVATE);
    }

    static synchronized String key(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("key", null);
        if (k == null || k.length() < 32) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            k = sb.toString();
            p.edit().putString("key", k).apply();
        }
        return k;
    }

    static boolean paired(Context c) { return prefs(c).getBoolean("paired", false); }

    static void markPaired(Context c) {
        if (!paired(c)) prefs(c).edit().putBoolean("paired", true).apply();
    }

    /** Neu koppeln: neuer Schlüssel, bis zur Kopplung gilt wieder der alte feste */
    static synchronized void reset(Context c) {
        prefs(c).edit().remove("key").putBoolean("paired", false).apply();
        key(c);
    }
}
