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
    /** Meldet, ob mindestens ein Auftrag aktiv ist (Übergang 0→1: true, 1→0: false); unter der Sperre aufgerufen. */
    public interface Activity { void active(boolean any); }

    public final int max, maxHttp;
    private int used, http;
    private Activity activity = any -> { };

    /** Android: CPU-Sperre während aktiver Aufträge. Ist bereits ein Auftrag aktiv, wird das sofort gemeldet. */
    public synchronized void setActivity(Activity a) {
        activity = a == null ? any -> { } : a;
        if (used > 0) activity.active(true);
    }

    private void occupied() { if (++used == 1) activity.active(true); }

    private void freed() { if (--used == 0) activity.active(false); }

    public JobSlots(int max) { this(max, 2 * max); }

    public JobSlots(int max, int maxHttp) {
        if (max < 1 || maxHttp < max) throw new IllegalArgumentException("limits");
        this.max = max;
        this.maxHttp = maxHttp;
    }

    /** Auftragsplatz ohne Verbindung (HLS: Anfragen einzeln über {@link #acquireHttp}). false: alle belegt. */
    public synchronized boolean tryAcquire() {
        if (used >= max) return false;
        occupied();
        return true;
    }

    /** Auftragsplatz und eine Verbindung für die ganze Dauer (MP4). false: eines davon ist belegt. */
    public synchronized boolean tryAcquireWithConnection() {
        if (used >= max || http >= maxHttp) return false;
        http++;
        occupied();
        return true;
    }

    public synchronized void release() {
        if (used <= 0) throw new IllegalStateException("release without acquire");
        freed();
        notifyAll();
    }

    public synchronized void releaseWithConnection() {
        if (used <= 0 || http <= 0) throw new IllegalStateException("release without acquire");
        http--;
        freed();
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
