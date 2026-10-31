package de.evp.helfer.net;

import java.net.InetAddress;
import java.util.Collections;

/** NUR TESTS (nicht in der App): SafeHttpClient mit den Testregeln, die genau einen lokalen Port zulassen. */
public final class LocalTestClients {
    private LocalTestClients() {}

    /** Alle Prüfschichten aktiv; einzig erlaubtes Ziel ist 127.0.0.1:port, Namen lösen nur zu 127.0.0.1 auf. */
    public static SafeHttpClient loopback(int port) throws Exception {
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        return new SafeHttpClient(NetRules.forLocalTests(null, loop, port), host -> Collections.singletonList(loop), null);
    }

    /** Produktive (strenge) Regeln: lokale Ziele sind gesperrt. */
    public static SafeHttpClient strict() {
        return SafeHttpClient.strict(Collections::emptyList);
    }

    /** Test-only scheduling/observation at callStart, after the engine's eviction and before connection acquisition.
     * The copied builder preserves every guard, timeout, retry policy and the original pool.
     */
    public static void observe(SafeHttpClient http, okhttp3.EventListener listener) throws Exception {
        java.lang.reflect.Field f = SafeHttpClient.class.getDeclaredField("client");
        f.setAccessible(true);
        okhttp3.OkHttpClient client = (okhttp3.OkHttpClient) f.get(http);
        f.set(http, client.newBuilder().eventListener(listener).build());
    }
}
