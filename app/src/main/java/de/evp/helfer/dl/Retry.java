package de.evp.helfer.dl;

import java.util.function.BooleanSupplier;

/**
 * P4c: begrenzte automatische Wiederaufnahme nach vorübergehenden Netzwerkfehlern (MP4 und HLS gemeinsam).
 *
 * <ul>
 *   <li>Wartezeiten wachsen exponentiell ({@link #DELAYS_MS}); höchstens {@link #MAX_ATTEMPTS} Versuche je Auftrag,
 *       danach endgültig (keine Endlosschleife, keine unbegrenzten Anfragen).</li>
 *   <li>Ohne Netz ({@link Network#online()} false) wird gewartet, ohne einen Versuch zu verbrauchen und ohne eine
 *       Anfrage zu senden – höchstens {@link #MAX_OFFLINE_MS} am Stück. Kommt das Netz zurück, geht es nach
 *       {@link #SETTLE_MS} weiter (nicht erst nach der vollen Wartezeit).</li>
 *   <li>Ein Abbruch (Nutzer, Dienstende) beendet jedes Warten innerhalb von {@link #POLL_MS}.</li>
 *   <li>Nur für vorübergehende Gründe ({@link #transientReason}); nie bei Abbruch, Sicherheitsprüfung, fehlender
 *       Berechtigung (401/403), entfernter Datei (404/410), geänderter Quelle oder fehlendem Prüfmerkmal.</li>
 * </ul>
 */
public final class Retry {
    /** Wartezeit vor Versuch n (1-basiert). */
    public static final long[] DELAYS_MS = {2_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000};
    public static final int MAX_ATTEMPTS = DELAYS_MS.length;
    public static final long MAX_OFFLINE_MS = 2 * 3600_000L;
    static final long SETTLE_MS = 1_000;
    static final long POLL_MS = 250;

    /** Verbindungsstatus des Geräts (Android: ConnectivityManager; Tests: steuerbar). */
    public interface Network {
        boolean online();
        Network ALWAYS = () -> true;
    }

    /** Zeitquelle und Schlaf (Tests: beschleunigt). */
    public interface Clock {
        long nowMs();
        Clock SYSTEM = System::currentTimeMillis;
    }

    /** Was ein Auftrag während des Wartens zeigt (Benachrichtigung, Status). */
    public enum Wait { NONE, OFFLINE, RETRY }

    private final Network network;
    private final Clock clock;
    private final long scale;     // Teiler der Wartezeiten (Tests); 1 = echt
    private final Object lock = new Object();

    public Retry(Network network) { this(network, Clock.SYSTEM, 1); }

    public Retry(Network network, Clock clock, long scale) {
        this.network = network;
        this.clock = clock;
        this.scale = Math.max(1, scale);
    }

    public static final Retry DEFAULT = new Retry(Network.ALWAYS);

    /** Netzänderung (Android-Rückruf): wartende Aufträge sofort prüfen lassen. */
    public void networkChanged() { synchronized (lock) { lock.notifyAll(); } }

    public boolean online() {
        try { return network.online(); } catch (RuntimeException e) { return true; }
    }

    /** Vorübergehender Grund (neutraler Code) → automatisch erneut versuchen. */
    public static boolean transientReason(String reason) {
        if (reason == null) return false;
        switch (reason) {
            case "io": case "network": case "timeout": case "http_408": case "http_429":
            case "http_500": case "http_502": case "http_503": case "http_504":
                return true;
            default:
                return false;
        }
    }

    /** Vorübergehender HTTP-Status (Server überlastet, Zeitüberschreitung). */
    public static boolean transientStatus(int code) {
        return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /**
     * Wartet vor Versuch {@code attempt} (1-basiert). true = jetzt erneut versuchen; false = aufgeben (zu viele
     * Versuche, zu lange ohne Netz, oder {@code stop} meldet Abbruch). {@code shown} erfährt, was gerade gewartet wird.
     */
    public boolean await(int attempt, BooleanSupplier stop, java.util.function.Consumer<Wait> shown) throws InterruptedException {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) return false;
        long now = clock.nowMs();
        long due = now + DELAYS_MS[attempt - 1] / scale;
        long offlineSince = -1;
        Wait last = null;
        synchronized (lock) {
            while (true) {
                if (stop.getAsBoolean()) return false;
                now = clock.nowMs();
                Wait w;
                if (!online()) {
                    if (offlineSince < 0) offlineSince = now;
                    if (now - offlineSince > MAX_OFFLINE_MS / scale) return false;
                    w = Wait.OFFLINE;
                } else {
                    if (offlineSince >= 0) { due = Math.min(due, now + SETTLE_MS / scale); offlineSince = -1; }
                    if (now >= due) { if (shown != null) shown.accept(Wait.NONE); return true; }
                    w = Wait.RETRY;
                }
                if (w != last && shown != null) { shown.accept(w); last = w; }
                long left = w == Wait.OFFLINE ? POLL_MS : Math.min(POLL_MS, Math.max(1, due - now));
                lock.wait(left);
            }
        }
    }
}
