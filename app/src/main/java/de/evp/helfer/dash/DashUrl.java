package de.evp.helfer.dash;

import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsUri;

import java.util.Locale;

/**
 * S3a: Adressen im MPD – RFC-3986-Auflösung über den bestehenden, unveränderten HLS-Baustein HlsUri und eine
 * kanonische Form, die mit der WHATWG-URL-Auflösung in Kadri (new URL) übereinstimmt.
 *
 * Wo RFC 3986 und WHATWG verschieden auflösen oder kodieren würden, wird fail-closed abgelehnt ("uri-ambiguous"),
 * statt eine der beiden Deutungen zu wählen: Zeichen, die WHATWG prozentkodiert oder umdeutet (" < > \ ^ ` { | } ' ),
 * "#" (Fragment), "%" ohne zwei Hex-Ziffern, "%2e" (WHATWG: Punktsegment), "[" "]" (IPv6), Referenzen mit Schema
 * aber ohne "//". Hosts: nur [a-z0-9.-] (Groß→klein) oder eine kanonische IPv4-Adresse; numerische Kurzformen, die
 * WHATWG umschreibt (z. B. "127.1", "0x7f.1"), werden abgelehnt. Standardports (80/443) entfallen wie bei WHATWG.
 * Netzgrenzen (DNS, private Netze) prüft später – beim Abruf – die gehärtete Netzschicht.
 */
final class DashUrl {
    private DashUrl() {}

    private static final String BAD = "\"<>\\^`{|}'#[]";

    /** Ein Adress-Bestandteil aus dem MPD (BaseURL-Text, Vorlage, SegmentURL, sourceURL) vor der Auflösung. */
    static void checkRef(String ref) throws DashException {
        if (ref == null || ref.isEmpty()) throw new DashException("uri-empty");
        if (ref.length() > HlsUri.MAX_URI_CHARS) throw new DashException("uri-length");
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c <= 0x20 || c >= 0x7f) throw new DashException("uri-char");
            if (BAD.indexOf(c) >= 0) throw new DashException("uri-ambiguous");
            if (c == '%') {
                if (i + 2 >= ref.length() || hex(ref.charAt(i + 1)) < 0 || hex(ref.charAt(i + 2)) < 0) throw new DashException("uri-ambiguous");
                if (ref.charAt(i + 1) == '2' && (ref.charAt(i + 2) == 'e' || ref.charAt(i + 2) == 'E')) throw new DashException("uri-ambiguous");
            }
        }
        int colon = ref.indexOf(':'), slash = ref.indexOf('/'), q = ref.indexOf('?');
        int first = minPos(slash, q);
        if (colon > 0 && (first < 0 || colon < first) && ref.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*") && !ref.startsWith("//", colon + 1))
            throw new DashException("uri-ambiguous");
    }

    private static int minPos(int a, int b) { return a < 0 ? b : b < 0 ? a : Math.min(a, b); }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Auflösen (RFC 3986 5.2) und kanonisieren. base muss bereits kanonisch und absolut sein. */
    static String resolve(String base, String ref) throws DashException {
        checkRef(ref);
        String out;
        try { out = HlsUri.resolve(base, ref); } catch (HlsException e) { throw new DashException(e.reason); }
        return canonical(out);
    }

    /** Kanonische Form einer absoluten http(s)-Adresse (Schema/Host klein, ohne Standardport, ohne Fragment). */
    static String canonical(String abs) throws DashException {
        try { HlsUri.check(abs, "dash"); } catch (HlsException e) { throw new DashException(e.reason); }
        for (int i = 0; i < abs.length(); i++) if (BAD.indexOf(abs.charAt(i)) >= 0) throw new DashException("uri-ambiguous");
        int colon = abs.indexOf(':');
        String scheme = abs.substring(0, colon).toLowerCase(Locale.ROOT);
        int authStart = colon + 3, authEnd = authStart;
        while (authEnd < abs.length() && abs.charAt(authEnd) != '/' && abs.charAt(authEnd) != '?') authEnd++;
        String auth = abs.substring(authStart, authEnd).toLowerCase(Locale.ROOT), rest = abs.substring(authEnd);
        String host = auth, port = null;
        int pc = auth.lastIndexOf(':');
        if (pc >= 0) { host = auth.substring(0, pc); port = auth.substring(pc + 1); }
        if (host.isEmpty() || !host.matches("[a-z0-9.-]{1,253}") || host.startsWith(".") || host.contains("..") || host.contains("%"))
            throw new DashException("uri-host");
        String last = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        last = last.substring(last.lastIndexOf('.') + 1);
        if (!last.isEmpty() && (Character.isDigit(last.charAt(0)) || last.startsWith("0x"))) {
            // WHATWG deutet einen numerischen letzten Teil als IPv4 (auch Kurz-, Oktal- und Hexformen): nur kanonisch
            if (!host.matches("(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])){3}"))
                throw new DashException("uri-host");
        }
        if (port != null) {
            if (!port.matches("[0-9]{1,5}")) throw new DashException("uri-port");
            int p = Integer.parseInt(port);
            if (p < 1 || p > 65535) throw new DashException("uri-port");
            port = Integer.toString(p);
            if (("http".equals(scheme) && p == 80) || ("https".equals(scheme) && p == 443)) port = null;
        }
        if (rest.isEmpty()) rest = "/";
        if (rest.length() > 0 && rest.charAt(0) == '?') rest = "/" + rest;
        String out = scheme + "://" + host + (port == null ? "" : ":" + port) + rest;
        if (out.length() > HlsUri.MAX_URI_CHARS) throw new DashException("uri-length");
        return out;
    }

    /** Herkunft (Schema://Host[:Port]) einer kanonischen Adresse. */
    static String origin(String canon) {
        int s = canon.indexOf('/', canon.indexOf("://") + 3);
        int q = canon.indexOf('?', canon.indexOf("://") + 3);
        int end = minPos(s, q);
        return end < 0 ? canon : canon.substring(0, end);
    }
}
