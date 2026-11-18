package de.evp.helfer.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * Prüfregeln für Ziele: Schema, Port, Zugangsdaten in der URL, Hostname, Adresse. Dieselben Regeln gelten für die
 * Ausgangs-URL, jede Weiterleitung, die DNS-Antwort, den Verbindungsaufbau und die tatsächlich verbundene Gegenstelle.
 * Produktiv nur über {@link #strict}; {@link #forLocalTests} existiert ausschließlich für Tests gegen lokale
 * Testserver und lässt genau eine Adresse und einen Port zusätzlich zu (CI prüft, dass Produktivcode es nie aufruft).
 */
final class NetRules {
    /** Liefert die netzspezifischen NAT64-Präfixe des aktuellen Netzes (kann sich mit dem Netz ändern). */
    interface Nat64Source { List<AddressPolicy.Nat64Prefix> current(); }

    static final Nat64Source NO_NETWORK_NAT64 = Collections::emptyList;
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Nat64Source nat64;
    private final InetAddress testAddress;   // nur forLocalTests
    private final int testPort;              // nur forLocalTests

    private NetRules(Nat64Source nat64, InetAddress testAddress, int testPort) {
        this.nat64 = nat64;
        this.testAddress = testAddress;
        this.testPort = testPort;
    }

    static NetRules strict(Nat64Source nat64) { return new NetRules(nat64 == null ? NO_NETWORK_NAT64 : nat64, null, -1); }

    /** NUR TESTS: zusätzlich genau {@code address}:{@code port} (z. B. ein Testserver auf 127.0.0.1) zulassen. */
    static NetRules forLocalTests(Nat64Source nat64, InetAddress address, int port) {
        if (address == null || port <= 0 || port > 65535) throw new IllegalArgumentException("test target");
        return new NetRules(nat64 == null ? NO_NETWORK_NAT64 : nat64, address, port);
    }

    private boolean isTestTarget(InetAddress a, int port) {
        return testAddress != null && testAddress.equals(a) && port == testPort;
    }

    boolean portAllowed(int port) { return port == 80 || port == 443 || (testAddress != null && port == testPort); }

    boolean addressAllowed(InetAddress a) {
        return (testAddress != null && testAddress.equals(a)) || AddressPolicy.allowed(a, nat64.current());
    }

    /** Verbindungsziel (Adresse und Port), vor dem Verbindungsaufbau und für die verbundene Gegenstelle. */
    void checkEndpoint(InetAddress a, int port, boolean connected) throws NetBlockedException.Connect {
        NetBlockedException.Reason r = connected ? NetBlockedException.Reason.PEER : NetBlockedException.Reason.ADDRESS;
        if (a == null) throw new NetBlockedException.Connect(r, "no address");
        if (isTestTarget(a, port)) return;
        if (port != 80 && port != 443) throw new NetBlockedException.Connect(NetBlockedException.Reason.PORT, String.valueOf(port));
        if (!AddressPolicy.allowed(a, nat64.current())) throw new NetBlockedException.Connect(r, a.getHostAddress());
    }

    /** Hostnamen, die nie über DNS aufgelöst werden sollen (lokale und private Namensräume). */
    static boolean hostNameBlocked(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
            || h.endsWith(".internal") || h.endsWith(".home.arpa") || h.endsWith(".lan") || !h.contains(".");
    }

    static boolean isIpLiteral(String host) { return host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches(); }

    /**
     * Hosts, deren letztes Label eine Zahl ist (dezimal, oktal, hexadezimal), behandelt der WHATWG-URL-Standard als
     * IPv4-Adresse (z. B. 2130706433, 0x7f.1, 127.1). Zulässig ist davon nur die strikte Punktschreibweise a.b.c.d.
     */
    static boolean endsInNumber(String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String last = h.substring(h.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (last.isEmpty()) return false;
        if (last.startsWith("0x")) return last.substring(2).matches("[0-9a-f]*");
        return last.matches("[0-9]+");
    }

    /** Ausgangs-URL und jedes Weiterleitungsziel. */
    void checkUrl(HttpUrl url) throws NetBlockedException {
        String scheme = url.scheme();
        if (!scheme.equals("http") && !scheme.equals("https")) throw new NetBlockedException(NetBlockedException.Reason.SCHEME, scheme);
        if (!url.username().isEmpty() || !url.password().isEmpty()) throw new NetBlockedException(NetBlockedException.Reason.CREDENTIALS_IN_URL, url.host());
        if (!portAllowed(url.port())) throw new NetBlockedException(NetBlockedException.Reason.PORT, String.valueOf(url.port()));
        String host = url.host();   // von OkHttp normalisiert (z. B. 2130706433 -> 127.0.0.1, IPv6 ohne Klammern)
        if (isIpLiteral(host)) {
            InetAddress a;
            try { a = InetAddress.getByName(host); } catch (UnknownHostException e) { throw new NetBlockedException(NetBlockedException.Reason.BAD_URL, host); }
            if (!addressAllowed(a) || !(isTestTarget(a, url.port()) || url.port() == 80 || url.port() == 443)) {
                throw new NetBlockedException(NetBlockedException.Reason.ADDRESS, host);
            }
        } else if (endsInNumber(host)) {
            throw new NetBlockedException(NetBlockedException.Reason.ADDRESS, "non-canonical IPv4 " + host);
        } else if (hostNameBlocked(host)) {
            throw new NetBlockedException(NetBlockedException.Reason.HOST_NAME, host);
        }
    }

    /** DNS-Antwort: alle Adressen müssen zulässig sein, sonst wird der ganze Name abgelehnt (keine Mischantworten). */
    void checkResolved(String host, List<InetAddress> addresses) throws NetBlockedException.Host {
        if (addresses == null || addresses.isEmpty()) throw new NetBlockedException.Host(NetBlockedException.Reason.NO_ADDRESS, host);
        for (InetAddress a : addresses) {
            if (!addressAllowed(a)) throw new NetBlockedException.Host(NetBlockedException.Reason.ADDRESS, host + " -> " + a.getHostAddress());
        }
    }
}
