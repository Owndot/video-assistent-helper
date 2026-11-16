package de.evp.helfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.net.DeviceTestClients;

/**
 * NUR DIAGNOSE, nur Wegwerf-Emulator (API 29+): wiederholt exakt die Ablagesequenz der App für einen MP4-Download –
 * {@link Mp4Downloads#create} (MediaStore-Pending-Eintrag), schreiben, schließen, {@code commit()} – mit synthetischem
 * Inhalt und ohne Netzwerk. Anlass: auf API 34 endete die Veröffentlichung sporadisch mit {@code IOException: publish}
 * (das {@code update(IS_PENDING=0, DISPLAY_NAME)} betraf nicht genau einen Eintrag; Läufe 42240436344, 42547150112,
 * 43874560604). Bei einem Fehlschlag wird VOR dem Aufräumen der Zustand des Eintrags protokolliert (pending, Name,
 * Größe, Datei vorhanden/Länge) und nur in diesem Test einmal dasselbe Update erneut abgesetzt, um vorübergehend von
 * dauerhaft zu unterscheiden. Die App selbst wiederholt nichts; diese Sonde ändert kein Produktverhalten.
 * Varianten trennen Einflüsse: ohne Pause; 2 s Pause bei offenem Stream nach 3 MiB (wie die Fortsetzung der App);
 * Pause vor dem Schließen; Pause zwischen Schließen und Veröffentlichen; vorher abgebrochener Nachbarauftrag
 * (wie {@code interruptionFailsCleanOrResumesExactly}, das auf API 34 zweimal in Folge mit "publish" scheiterte).
 * Ergebnis: Logzeilen PUBLISH_PROBE; der Test scheitert, sobald eine Veröffentlichung scheitert (Befund, nicht
 * wegtesten).
 */
@RunWith(AndroidJUnit4.class)
public class MediaStorePublishProbeTest {
    private static final String TAG = "MP4_TEST";
    static final int CYCLES = 60;
    /** Varianten mit Pause: trennen „Pause während der Stream offen ist“ (wie die Fortsetzung) von „Alter des Eintrags“. */
    static final int PAUSE_CYCLES = 20;
    static final long PAUSE_MS = 2_000;   // erste Fortsetzungspause der App (Retry.DELAYS_MS[0])

    enum Variant { PLAIN, PAUSE_MID_WRITE, PAUSE_BEFORE_CLOSE, PAUSE_BEFORE_COMMIT, ABORTED_SIBLING_THEN_PAUSE_MID_WRITE }

    @Test public void repeatedPublicationsOfTheAppStore() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        List<String> failures = new ArrayList<>();
        try {
            for (Variant v : Variant.values()) {
                int cycles = v == Variant.PLAIN ? CYCLES : PAUSE_CYCLES;
                int ok = 0, failed = 0;
                long maxCommitMs = 0;
                for (int c = 0; c < cycles; c++) {
                    // Größen wie in den betroffenen Tests (8 MiB) und kleiner; mit Pause immer 8 MiB wie die Fortsetzung
                    long size = v != Variant.PLAIN || c % 3 == 0 ? 8L << 20 : (c % 3 == 1) ? 1L << 20 : 64L << 10;
                    long[] commitMs = {0};
                    String f = cycle(store, cr, v, c, size, commitMs);
                    maxCommitMs = Math.max(maxCommitMs, commitMs[0]);
                    if (f == null) ok++;
                    else { failed++; failures.add(f); }
                }
                Log.i(TAG, "PUBLISH_PROBE sdk=" + Build.VERSION.SDK_INT + " variant=" + v + " cycles=" + cycles + " ok=" + ok
                    + " failed=" + failed + " maxCommitMs=" + maxCommitMs);
            }
        } finally {
            store.engine.stopAll();
        }
        assertEquals("publication failures: " + failures, 0, failures.size());
    }

    /** Ein Ablauf wie in der App; null = veröffentlicht (und wieder gelöscht), sonst Befundtext. */
    private static String cycle(Mp4Downloads store, ContentResolver cr, Variant v, int c, long size, long[] commitMs) throws Exception {
        byte[] chunk = new byte[64 << 10];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31 + 7 + c);
        if (v == Variant.ABORTED_SIBLING_THEN_PAUSE_MID_WRITE) {
            // wie der Test: ein vorheriger Auftrag ohne Validator schreibt 3 MiB, scheitert und wird abgebrochen
            DownloadStore.Target sibling = store.create(de.evp.helfer.dl.DirectDownloads.fileName(
                "C2 ProbeSibling " + Long.toHexString(SystemClock.elapsedRealtimeNanos())), "video/mp4");
            OutputStream so = sibling.out();
            for (long w = 0; w < (3L << 20); w += chunk.length) so.write(chunk, 0, chunk.length);
            sibling.abort();
        }
        String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 Probe " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
        DownloadStore.Target t = store.create(name, "video/mp4");
        long created = SystemClock.elapsedRealtime();
        OutputStream out = t.out();
        long pauseAt = v == Variant.PAUSE_MID_WRITE || v == Variant.ABORTED_SIBLING_THEN_PAUSE_MID_WRITE ? 3L << 20 : -1;   // wie der Test: Unterbrechung nach 3 MiB
        for (long w = 0; w < size; w += chunk.length) {
            if (w == pauseAt) SystemClock.sleep(PAUSE_MS);
            out.write(chunk, 0, (int) Math.min(chunk.length, size - w));
        }
        if (v == Variant.PAUSE_BEFORE_CLOSE) SystemClock.sleep(PAUSE_MS);
        out.close();
        if (v == Variant.PAUSE_BEFORE_COMMIT) SystemClock.sleep(PAUSE_MS);
        long t0 = SystemClock.elapsedRealtime();
        try {
            Uri saved = (Uri) t.commit();
            commitMs[0] = SystemClock.elapsedRealtime() - t0;
            try { cr.delete(saved, null, null); } catch (RuntimeException ignored) { }
            return null;
        } catch (IOException e) {
            long ms = SystemClock.elapsedRealtime() - t0;
            String state = describe(cr, Uri.parse(t.ref()));
            String again = retryOnceForDiagnosis(cr, Uri.parse(t.ref()), name);
            String f = String.format(Locale.ROOT, "variant=%s cycle=%d size=%d error=%s commitMs=%d ageMs=%d state={%s} sameUpdateAgain=%s",
                v, c, size, e.getClass().getSimpleName() + ": " + e.getMessage(), ms, t0 - created, state, again);
            Log.i(TAG, "PUBLISH_PROBE_FAIL sdk=" + Build.VERSION.SDK_INT + " " + f);
            t.abort();   // entfernt den Eintrag (auch nach der Diagnose-Wiederholung) und die Besitzmarkierung
            return f;
        }
    }

    /** Zustand des Eintrags nach einem gescheiterten Veröffentlichen (inklusive pending; Pfad nur des Testordners). */
    private static String describe(ContentResolver cr, Uri u) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = cr.query(MediaStore.setIncludePending(u), new String[] {MediaStore.Downloads.IS_PENDING,
                MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c == null) return "query=null";
            if (!c.moveToFirst()) return "rows=0";
            String data = c.getString(3);
            File f = data == null ? null : new File(data);
            sb.append("pending=").append(c.getInt(0)).append(" name=").append(c.getString(1)).append(" size=").append(c.getLong(2))
                .append(" fileExists=").append(f != null && f.exists()).append(" fileLength=").append(f == null ? -1 : f.length());
        } catch (RuntimeException e) {
            sb.append("query=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** Nur Diagnose: dasselbe Update wie {@code PendingEntry.commit()} einmal erneut; Ergebnis als Text. */
    private static String retryOnceForDiagnosis(ContentResolver cr, Uri u, String name) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            return "rows=" + cr.update(u, cv, null, null);
        } catch (RuntimeException e) {
            return "threw=" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
