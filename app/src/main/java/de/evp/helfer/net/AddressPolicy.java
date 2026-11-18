package de.evp.helfer.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Zulässige Zieladressen für ausgehende HTTP-Verbindungen: ausschließlich global erreichbare Unicast-Adressen.
 *
 * IPv4: Sperrliste aller Blöcke der IANA-Special-Purpose-Registry, die nicht global erreichbar sind (privat, lokal,
 * Loopback, CGNAT, Link-Local, Dokumentation, Benchmark, Multicast, reserviert, Broadcast).
 * IPv6: nur 2000::/3 (Global Unicast), davon ausgenommen 2001::/23 (IETF-Protokolle inkl. Teredo und ORCHID),
 * 2001:db8::/32 und 3fff::/20 (Dokumentation) sowie 2002::/16 (6to4). Adressen mit eingebetteter IPv4-Adresse werden
 * nach dieser IPv4-Adresse beurteilt: IPv4-mapped (::ffff:0:0/96) und NAT64 (64:ff9b::/96 sowie bekannte
 * netzspezifische /96-Präfixe). Alle anderen Formen (IPv4-kompatibel ::/96, ::ffff:0:0:0/96, 64:ff9b:1::/48, ULA,
 * Link-/Site-Local, Multicast, Discard-Only) sind gesperrt.
 *
 * Zustandslos und threadsicher.
 */
final class AddressPolicy {
    /** IPv4-Blöcke (Netz, Präfixlänge), die nie als Ziel zulässig sind. */
    private static final int[][] BLOCKED_V4 = {
        {0x00000000, 8},    // 0.0.0.0/8       "dieses Netz"
        {0x0A000000, 8},    // 10.0.0.0/8      privat
        {0x64400000, 10},   // 100.64.0.0/10   Carrier-Grade NAT
        {0x7F000000, 8},    // 127.0.0.0/8     Loopback
        {0xA9FE0000, 16},   // 169.254.0.0/16  Link-Local (u. a. Cloud-Metadaten 169.254.169.254)
        {0xAC100000, 12},   // 172.16.0.0/12   privat
        {0xC0000000, 24},   // 192.0.0.0/24    IETF-Protokollzuweisungen (inkl. 192.0.0.170/171 NAT64-Erkennung)
        {0xC0000200, 24},   // 192.0.2.0/24    Dokumentation
        {0xC0586300, 24},   // 192.88.99.0/24  6to4-Relay (abgekündigt)
        {0xC0A80000, 16},   // 192.168.0.0/16  privat
        {0xC6120000, 15},   // 198.18.0.0/15   Benchmark
        {0xC6336400, 24},   // 198.51.100.0/24 Dokumentation
        {0xCB007100, 24},   // 203.0.113.0/24  Dokumentation
        {0xE0000000, 4},    // 224.0.0.0/4     Multicast
        {0xF0000000, 4},    // 240.0.0.0/4     reserviert, inkl. 255.255.255.255
    };

    /** Bekanntes NAT64-Präfix 64:ff9b::/96 (RFC 6052). */
    static final byte[] NAT64_WELL_KNOWN = {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};

    private AddressPolicy() {}

    /**
     * true nur für eine global erreichbare Unicast-Adresse. {@code networkNat64} sind netzspezifische NAT64-Präfixe
     * des aktuellen Netzes (je Netz-Bytes + Länge in Bits): /96-Präfixe werden wie 64:ff9b::/96 dekodiert, Adressen in
     * Präfixen anderer Länge gesperrt (die eingebettete IPv4-Adresse ist dann nicht sicher bestimmbar).
     */
    static boolean allowed(InetAddress a, List<Nat64Prefix> networkNat64) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address && b.length == 4) return allowedV4(b);
        if (a instanceof Inet6Address && b.length == 16) return allowedV6(b, networkNat64 == null ? Collections.emptyList() : networkNat64);
        return false;
    }

    /** Netzspezifisches NAT64-Präfix (RFC 6052); {@code bits} 32, 40, 48, 56, 64 oder 96. */
    static final class Nat64Prefix {
        final byte[] network;
        final int bits;

        Nat64Prefix(byte[] network, int bits) {
            if (network == null || network.length != 16 || bits < 0 || bits > 128) throw new IllegalArgumentException("prefix");
            this.network = network.clone();
            this.bits = bits;
        }

        boolean contains(byte[] a) {
            int full = bits / 8, rest = bits % 8;
            for (int i = 0; i < full; i++) if (a[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (a[full] & mask) == (network[full] & mask);
        }
    }

    private static int v4(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static boolean allowedV4(byte[] b) {
        int ip = v4(b, b.length - 4);
        for (int[] net : BLOCKED_V4) {
            int mask = net[1] == 0 ? 0 : -1 << (32 - net[1]);
            if ((ip & mask) == net[0]) return false;
        }
        return true;
    }

    private static boolean allowedV6(byte[] b, List<Nat64Prefix> networkNat64) {
        // Eingebettetes IPv4: IPv4-mapped ::ffff:a.b.c.d
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return allowedV4(b);
        // NAT64: bekanntes Präfix und netzspezifische /96-Präfixe nach der eingebetteten IPv4-Adresse beurteilen
        if (Arrays.equals(Arrays.copyOf(b, 12), NAT64_WELL_KNOWN)) return allowedV4(b);
        for (Nat64Prefix p : networkNat64) {
            if (p.contains(b)) return p.bits == 96 && allowedV4(b);
        }
        int first = b[0] & 0xff;
        if ((first & 0xe0) != 0x20) return false;                          // außerhalb 2000::/3 (inkl. ::, ::1, ULA, fe80::, ff00::, 64:ff9b:1::)
        int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        if (w0 == 0x2001 && w1 < 0x0200) return false;                     // 2001::/23 IETF (Teredo, ORCHID, Benchmark …)
        if (w0 == 0x2001 && w1 == 0x0db8) return false;                    // 2001:db8::/32 Dokumentation
        if (w0 == 0x2002) return false;                                    // 2002::/16 6to4
        if (w0 == 0x3fff && (w1 & 0xf000) == 0) return false;              // 3fff::/20 Dokumentation
        return true;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }
}
