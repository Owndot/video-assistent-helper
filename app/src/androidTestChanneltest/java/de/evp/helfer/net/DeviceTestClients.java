package de.evp.helfer.net;

import java.net.InetAddress;
import java.util.Collections;

/** NUR GERÄTETESTS: SafeHttpClient mit Testregeln (einziges erlaubtes Ziel 127.0.0.1:port, alle Prüfschichten aktiv). */
public final class DeviceTestClients {
    private DeviceTestClients() {}

    public static SafeHttpClient loopback(int port) throws Exception {
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        return new SafeHttpClient(NetRules.forLocalTests(NetRules.NO_NETWORK_NAT64, loop, port), host -> Collections.singletonList(loop), null);
    }
}
