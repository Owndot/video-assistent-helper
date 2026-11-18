package de.evp.helfer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Sprache der App. (Früher auch der feste Kopplungsschlüssel für den alten Klartext-Weg; den gibt es nicht mehr –
 * gekoppelt wird nur noch über den sicheren Kanal, siehe {@link ChannelServer}.)
 */
final class Keys {
    private Keys() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("helfer", Context.MODE_PRIVATE);
    }

    /** Sprache der App: "auto" (wie das Handy), "de" oder "en" – gilt für Bildschirm und Benachrichtigungen */
    static String lang(Context c) { return prefs(c).getString("lang", "auto"); }

    static void applyLang(Context c) {
        String l = lang(c);
        Tr.force = "de".equals(l) || "en".equals(l) ? l : null;
    }

    static void setLang(Context c, String l) {
        prefs(c).edit().putString("lang", l).apply();
        applyLang(c);
    }

    /** Reste des alten Schlüssels aus früheren Installationen entfernen (er gilt nirgends mehr). */
    static void dropLegacyKey(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("key") || p.contains("paired")) p.edit().remove("key").remove("paired").apply();
    }
}
