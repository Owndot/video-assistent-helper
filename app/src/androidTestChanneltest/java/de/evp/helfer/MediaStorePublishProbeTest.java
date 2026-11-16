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
 * Ergebnis: Logzeilen PUBLISH_PROBE; der Test scheitert, sobald eine Veröffentlichung scheitert (Befund, nicht
 * wegtesten).
 */
@RunWith(AndroidJUnit4.class)
public class MediaStorePublishProbeTest {
    private static final String TAG = "MP4_TEST";
    static final int CYCLES = 60;

    @Test public void repeatedPublicationsOfTheAppStore() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        byte[] chunk = new byte[64 << 10];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31 + 7);
        List<String> failures = new ArrayList<>();
        List<Uri> published = new ArrayList<>();
        int ok = 0;
        long maxCommitMs = 0;
        try {
            for (int c = 0; c < CYCLES; c++) {
                // Größen wie in den betroffenen Tests (8 MiB) und kleiner; Name eindeutig wie in den Tests
                long size = (c % 3 == 0) ? 8L << 20 : (c % 3 == 1) ? 1L << 20 : 64L << 10;
                String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 Probe " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
                DownloadStore.Target t = store.create(name, "video/mp4");
                OutputStream out = t.out();
                for (long w = 0; w < size; w += chunk.length) out.write(chunk, 0, (int) Math.min(chunk.length, size - w));
                out.close();
                long t0 = SystemClock.elapsedRealtime();
                try {
                    Object saved = t.commit();
                    maxCommitMs = Math.max(maxCommitMs, SystemClock.elapsedRealtime() - t0);
                    published.add((Uri) saved);
                    ok++;
                } catch (IOException e) {
                    long ms = SystemClock.elapsedRealtime() - t0;
                    String state = describe(cr, Uri.parse(t.ref()));
                    String again = retryOnceForDiagnosis(cr, Uri.parse(t.ref()), name);
                    String f = String.format(Locale.ROOT, "cycle=%d size=%d error=%s commitMs=%d state={%s} sameUpdateAgain=%s",
                        c, size, e.getClass().getSimpleName() + ": " + e.getMessage(), ms, state, again);
                    Log.i(TAG, "PUBLISH_PROBE_FAIL sdk=" + Build.VERSION.SDK_INT + " " + f);
                    failures.add(f);
                    t.abort();   // entfernt den Eintrag (auch nach der Diagnose-Wiederholung) und die Besitzmarkierung
                }
            }
        } finally {
            for (Uri u : published) { try { cr.delete(u, null, null); } catch (RuntimeException ignored) { } }
            store.engine.stopAll();
        }
        Log.i(TAG, "PUBLISH_PROBE sdk=" + Build.VERSION.SDK_INT + " cycles=" + CYCLES + " ok=" + ok + " failed=" + failures.size()
            + " maxCommitMs=" + maxCommitMs);
        assertEquals("publication failures: " + failures, 0, failures.size());
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
