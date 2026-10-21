package de.evp.helfer;

import java.util.Locale;

/** Zweisprachig: Deutsch auf Handys mit deutscher Sprache, sonst Englisch (ohne Android-Teile – auch am Rechner testbar). */
final class Tr {
    private Tr() {}

    static boolean de() { return "de".equalsIgnoreCase(Locale.getDefault().getLanguage()); }

    static String t(String de, String en) { return de() ? de : en; }
}
