package de.evp.helfer.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NAT64-Präfix ohne Plattform-API (Android 8–10, API 26–29): Erkennung nach RFC 7050 über die von DNS64 synthetisierten
 * AAAA-Antworten für {@code ipv4only.arpa} (eingebettet 192.0.0.170/171, Positionen nach RFC 6052).
 *
 * <p>Sicher fehlschlagen: Lässt sich ein mögliches NAT64 nicht eindeutig bestimmen, wird jedes IPv6-Ziel gesperrt
 * ({@link #ALL_IPV6}, Präfix ::/0 ohne Dekodierung), statt eine netzspezifisch übersetzte Adresse mit eingebettetem
 * privatem IPv4 als öffentlich zu behandeln. IPv4-Ziele bleiben nach den IPv4-Regeln zulässig.
 */
final class Nat64Discovery {
    static final String NAME = "ipv4only.arpa";
    private static final byte[][] WELL_KNOWN_V4 = {{(byte) 192, 0, 0, (byte) 170}, {(byte) 192, 0, 0, (byte) 171}};
    /** RFC 6052 2.2: Präfixlängen, kürzeste zuerst (bei Mehrdeutigkeit gilt die kürzere = größere Sperre). */
    private static final int[] LENGTHS = {32, 40, 48, 56, 64, 96};

    /** Jede IPv6-Adresse liegt in ::/0; Länge ≠ 96 heißt: gesperrt, nicht dekodiert (siehe AddressPolicy). */
    static final List<AddressPolicy.Nat64Prefix> ALL_IPV6 =
        Collections.singletonList(new AddressPolicy.Nat64Prefix(new byte[16], 0));

    private Nat64Discovery() {}

    /**
     * Entscheidung für das aktive Netz.
     *
     * @param networkHasIpv4 das Netz hat eine eigene IPv4-Adresse (kein reines IPv6-Netz)
     * @param answers        Antworten für {@link #NAME}; null, wenn die Abfrage fehlschlug
     */
    static List<AddressPolicy.Nat64Prefix> decide(boolean networkHasIpv4, List<InetAddress> answers) {
        if (answers == null) return ALL_IPV6;                              // Abfrage gescheitert: unbekannt
        List<AddressPolicy.Nat64Prefix> prefixes = new ArrayList<>();
        boolean anyV6 = false;
        for (InetAddress a : answers) {
            if (a instanceof Inet4Address) continue;
            if (!(a instanceof Inet6Address)) return ALL_IPV6;
            byte[] b = a.getAddress();
            if (isMapped(b)) continue;                                     // ::ffff:192.0.0.170 ist eine IPv4-Antwort
            anyV6 = true;
            AddressPolicy.Nat64Prefix p = prefixOf(b);
            if (p == null) return ALL_IPV6;                                // AAAA ohne erkennbare Einbettung
            prefixes.add(p);
        }
        if (!anyV6) {
            // Kein DNS64. In einem reinen IPv6-Netz kann trotzdem NAT64 (464XLAT) bestehen – Präfix unbekannt.
            return networkHasIpv4 ? Collections.emptyList() : ALL_IPV6;
        }
        return prefixes;
    }

    /** RFC 6052-Präfix, in dem {@code b} eine der bekannten Adressen 192.0.0.170/171 einbettet; sonst null. */
    static AddressPolicy.Nat64Prefix prefixOf(byte[] b) {
        for (int bits : LENGTHS) {
            if (bits < 96 && b[8] != 0) continue;                          // Bits 64–71 ("u") müssen 0 sein
            byte[] v4 = embedded(b, bits);
            for (byte[] known : WELL_KNOWN_V4) {
                if (Arrays.equals(v4, known)) {
                    byte[] net = new byte[16];
                    System.arraycopy(b, 0, net, 0, bits / 8);
                    return new AddressPolicy.Nat64Prefix(net, bits);
                }
            }
        }
        return null;
    }

    private static byte[] embedded(byte[] b, int bits) {
        switch (bits) {
            case 32: return new byte[] {b[4], b[5], b[6], b[7]};
            case 40: return new byte[] {b[5], b[6], b[7], b[9]};
            case 48: return new byte[] {b[6], b[7], b[9], b[10]};
            case 56: return new byte[] {b[7], b[9], b[10], b[11]};
            case 64: return new byte[] {b[9], b[10], b[11], b[12]};
            default: return new byte[] {b[12], b[13], b[14], b[15]};
        }
    }

    private static boolean isMapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
