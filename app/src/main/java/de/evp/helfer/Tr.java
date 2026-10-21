package de.evp.helfer;

import java.util.Locale;

/**
 * Zweisprachig: Deutsch oder Englisch – nach Sprache des Handys oder von Hand in der App gewählt
 * (ohne Android-Teile – auch am Rechner testbar).
 */
final class Tr {
    private Tr() {}

    /** "de", "en" oder null = wie das Handy */
    static volatile String force = null;

    static boolean de() {
        String f = force;
        if (f != null) return "de".equals(f);
        return "de".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    static String t(String de, String en) { return de() ? de : en; }
}
