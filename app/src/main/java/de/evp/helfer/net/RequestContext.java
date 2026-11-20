package de.evp.helfer.net;

import java.util.regex.Pattern;

/**
 * Anfragekontext eines einzelnen Downloadauftrags (vom Browser über den verschlüsselten Kanal): höchstens die Herkunft der
 * Seite als Referer ({@code https://host[:port]/}, ohne Pfad, Query oder Zugangsdaten) und der User-Agent des Browsers.
 * Nie Cookies oder Authorization. Streng geprüft; ungültige Werte werden abgelehnt, nicht bereinigt. Ohne OkHttp-Abhängigkeit,
 * damit der Kanal-Parser (ControlCommands) ihn prüfen kann.
 */
public final class RequestContext {
    public static final RequestContext NONE = new RequestContext(null, null);
    static final Pattern REFERER = Pattern.compile(
        "https?://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:[1-9][0-9]{0,4})?/");
    static final Pattern AGENT = Pattern.compile("Mozilla/5\\.0 \\([\\x20-\\x7e]{1,500}");
    public final String referer, userAgent;   // jeweils null = kein Referer bzw. Helfer-User-Agent
    private RequestContext(String referer, String userAgent) { this.referer = referer; this.userAgent = userAgent; }

    /** Gültiger Kontext oder {@link IllegalArgumentException}; null-Werte bedeuten „nicht übergeben“. */
    public static RequestContext of(String referer, String userAgent) {
        if (referer != null && !validReferer(referer)) throw new IllegalArgumentException("referer");
        if (userAgent != null && !AGENT.matcher(userAgent).matches()) throw new IllegalArgumentException("user-agent");
        return referer == null && userAgent == null ? NONE : new RequestContext(referer, userAgent);
    }

    static boolean validReferer(String r) {
        if (r.length() > 300 || !REFERER.matcher(r).matches()) return false;
        int c = r.indexOf(':', r.indexOf("//") + 2);
        return c < 0 || Integer.parseInt(r.substring(c + 1, r.length() - 1)) <= 65535;
    }

    /** Derselbe Kontext ohne Referer (Ziel außerhalb des Hosts der ursprünglichen Anfrage). */
    public RequestContext withoutReferer() { return referer == null ? this : of(null, userAgent); }

    /** Referer für ein Ziel: nie von einer HTTPS-Seite an ein HTTP-Ziel (wie die Browser-Voreinstellung). */
    String refererFor(boolean httpsTarget) { return referer == null || (referer.startsWith("https:") && !httpsTarget) ? null : referer; }
}
