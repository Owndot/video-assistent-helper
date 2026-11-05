package de.evp.helfer.dl;

import java.util.function.BooleanSupplier;

/**
 * Gemeinsame Grenzen aller Download-Arten (direkte MP4 und HLS) in einem Prozess:
 * <ul>
 *   <li>höchstens {@link #max} aktive Aufträge; ein Platz wird beim Start belegt und erst freigegeben, wenn der
 *       Auftrag vollständig aufgeräumt hat;</li>
 *   <li>höchstens {@link #maxHttp} gleichzeitige HTTP-Anfragen. Ein MP4-Auftrag hält für seine ganze Dauer genau eine
 *       (er hat nie mehr als eine Anfrage offen); HLS belegt je Anfrage eine und gibt sie danach zurück.</li>
 * </ul>
 */
public final class JobSlots {
    public final int max, maxHttp;
    private int used, http;

    public JobSlots(int max) { this(max, 2 * max); }

    public JobSlots(int max, int maxHttp) {
        if (max < 1 || maxHttp < max) throw new IllegalArgumentException("limits");
        this.max = max;
        this.maxHttp = maxHttp;
    }

    /** Auftragsplatz ohne Verbindung (HLS: Anfragen einzeln über {@link #acquireHttp}). false: alle belegt. */
    public synchronized boolean tryAcquire() {
        if (used >= max) return false;
        used++;
        return true;
    }

    /** Auftragsplatz und eine Verbindung für die ganze Dauer (MP4). false: eines davon ist belegt. */
    public synchronized boolean tryAcquireWithConnection() {
        if (used >= max || http >= maxHttp) return false;
        used++;
        http++;
        return true;
    }

    public synchronized void release() {
        if (used <= 0) throw new IllegalStateException("release without acquire");
        used--;
        notifyAll();
    }

    public synchronized void releaseWithConnection() {
        if (used <= 0 || http <= 0) throw new IllegalStateException("release without acquire");
        used--;
        http--;
        notifyAll();
    }

    /**
     * Wartet auf eine freie Verbindung, höchstens {@code timeoutMs}, und bricht ab, sobald {@code cancelled} true
     * liefert (Prüfung mindestens alle 50 ms). true: belegt (danach {@link #releaseHttp}).
     */
    public synchronized boolean acquireHttp(long timeoutMs, BooleanSupplier cancelled) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (http >= maxHttp) {
            if (cancelled.getAsBoolean()) return false;
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return false;
            wait(Math.min(left, 50));
        }
        if (cancelled.getAsBoolean()) return false;
        http++;
        return true;
    }

    public synchronized void releaseHttp() {
        if (http <= 0) throw new IllegalStateException("release without acquire");
        http--;
        notifyAll();
    }

    public synchronized int inUse() { return used; }

    public synchronized int httpInUse() { return http; }
}
