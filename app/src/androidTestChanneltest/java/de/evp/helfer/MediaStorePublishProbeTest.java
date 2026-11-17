package de.evp.helfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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

    enum Variant { PLAIN, PAUSE_MID_WRITE, PAUSE_BEFORE_CLOSE, PAUSE_BEFORE_COMMIT, ABORTED_SIBLING_THEN_PAUSE_MID_WRITE,
        LARGE_DELETE_BEFORE_COMMIT, CONCURRENT_PROVIDER_LOAD }
    /** Wie viele Zyklen die Last-Varianten haben (je 64 MiB bzw. Fremdlast; Laufzeit begrenzt). */
    static final int LOAD_CYCLES = 15;

    @Test public void repeatedPublicationsOfTheAppStore() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        List<String> failures = new ArrayList<>();
        try {
            for (Variant v : Variant.values()) {
                int cycles = v == Variant.PLAIN ? CYCLES
                    : v == Variant.LARGE_DELETE_BEFORE_COMMIT || v == Variant.CONCURRENT_PROVIDER_LOAD ? LOAD_CYCLES : PAUSE_CYCLES;
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

    /**
     * Deterministische Referenz (Hypothese „Prozessstart-Bereinigung während eines laufenden Auftrags“): Ein Eintrag
     * wird angelegt und teilweise geschrieben; dann läuft genau das, was ein erneutes {@link Downloads#initialize}
     * täte (Flag zurückgesetzt wie in {@code newProcessCleanup()} der MP4-Tests). In der App läuft initialize nur einmal
     * je Prozess vor dem ersten Auftrag (HelferService.onCreate vor Mp4Downloads.get; das Flag wird in App-Code nie
     * zurückgesetzt). Ergebnis ist die Signatur, mit der reale Fehlschläge verglichen werden: Fehlertext des
     * Veröffentlichens, Zustand des Eintrags und Besitzmarkierung. Kein Produktverhalten geändert.
     */
    @Test public void processStartCleanupDuringAJobSignature() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-sig-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        byte[] chunk = new byte[64 << 10];
        String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 ProbeSig " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
        DownloadStore.Target t = store.create(name, "video/mp4");
        Uri uri = Uri.parse(t.ref());
        java.lang.reflect.Field pf = t.getClass().getDeclaredField("proof");
        pf.setAccessible(true);
        File proof = (File) pf.get(t);
        String outcome, stateAfterCleanup;
        boolean proofAfterCleanup;
        try {
            OutputStream out = t.out();
            for (int i = 0; i < 16; i++) out.write(chunk);
            java.lang.reflect.Field f = Downloads.class.getDeclaredField("initialized");
            f.setAccessible(true);
            synchronized (Downloads.class) { f.setBoolean(null, false); }
            Downloads.initialize(ctx);                                   // setzt das Flag bei Erfolg wieder auf true
            proofAfterCleanup = proof.exists();
            stateAfterCleanup = describe(cr, uri);
            String writeOutcome = "ok";
            try { for (int i = 0; i < 16; i++) out.write(chunk); out.close(); }
            catch (IOException | RuntimeException e) { writeOutcome = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            try { t.commit(); outcome = "published"; }
            catch (IOException | RuntimeException e) { outcome = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            Log.i(TAG, "PUBLISH_PROBE_SIGNATURE sdk=" + Build.VERSION.SDK_INT + " cleanupDuringJob: proofAfterCleanup="
                + proofAfterCleanup + " stateAfterCleanup={" + stateAfterCleanup + "} write=" + writeOutcome + " commit=" + outcome
                + " stateAfterCommit={" + describe(cr, uri) + "}");
        } finally {
            t.abort();
            store.engine.stopAll();
        }
        // Mechanismus belegt nur, wenn die Bereinigung den laufenden Eintrag tatsächlich entfernt hat.
        assertFalse("process-start cleanup removed the in-flight proof", proofAfterCleanup);
        assertNotEquals("in-flight entry cannot be published after the cleanup", "published", outcome);
        // Review C: die Publish-Diagnose ordnet genau diesen Ablauf eindeutig zu (eigene Buchführung, nicht Provider-Text):
        // Fehlschlag beim Veröffentlichen, Markierung weg, und die Bereinigung hat DIESEN Eintrag entfernt.
        assertTrue("publish diagnosis names the publish step: " + outcome, outcome.startsWith("IOException: publish "));
        assertTrue("publish diagnosis attributes the failure to the cleanup: " + outcome, outcome.contains(" cleanup=removed-this-entry"));
        assertTrue("publish diagnosis sees the removed proof: " + outcome, outcome.contains(" proof=gone "));
        assertTrue("publish diagnosis has the per-entry order: " + outcome,
            outcome.matches("(?s).* trail=insert#\\d+\\+\\d+ms,open#\\d+\\+\\d+ms,close#\\d+\\+\\d+ms,update.*"));
    }

    /**
     * Review P2 (deterministisch): Die Zuordnung zur Bereinigung ist konservativ. Ein fremdes Entfernen ohne jede
     * Bereinigung heißt "not-observed" (nie "removed-this-entry", nie "none"); läuft in diesem Prozess gerade eine
     * Bereinigung (hier: Zähler wie während {@link Downloads#initialize}), heißt es "overlap-observed" – auch wenn sie
     * den Eintrag (noch) nicht als entfernt vermerkt hat. Die Diagnose fragt den Provider dabei nicht ab.
     */
    @Test public void cleanupAttributionIsConservative() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-attr-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        try {
            String quiet = publishAfterForeignDelete(store, cr);
            Log.i(TAG, "PUBLISH_PROBE_ATTRIBUTION sdk=" + Build.VERSION.SDK_INT + " foreignDelete: " + quiet);
            assertTrue("foreign deletion fails the publication: " + quiet, quiet.startsWith("IOException: publish "));
            assertTrue("no cleanup ran: not observed, never attributed: " + quiet, quiet.contains(" cleanup=not-observed"));
            assertFalse("no provider query on the failure path (no entry= field): " + quiet, quiet.contains(" entry="));
            String overlap;
            Downloads.cleanupActive.incrementAndGet();      // wie während einer laufenden Bereinigung in diesem Prozess
            try { overlap = publishAfterForeignDelete(store, cr); }
            finally { Downloads.cleanupActive.decrementAndGet(); }
            Log.i(TAG, "PUBLISH_PROBE_ATTRIBUTION sdk=" + Build.VERSION.SDK_INT + " duringCleanup: " + overlap);
            assertTrue("a running cleanup is reported as overlap, not as not-observed: " + overlap, overlap.contains(" cleanup=overlap-observed("));
        } finally {
            store.engine.stopAll();
        }
    }

    /** Eintrag anlegen, schreiben, schließen; dann entfernt ein Dritter die Zeile; dann veröffentlichen. Liefert die Ausnahme. */
    private static String publishAfterForeignDelete(Mp4Downloads store, ContentResolver cr) throws Exception {
        String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 ProbeForeign " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
        DownloadStore.Target t = store.create(name, "video/mp4");
        try {
            OutputStream out = t.out();
            out.write(new byte[64 << 10]);
            out.close();
            assertEquals("foreign deletion removed the pending row", 1, cr.delete(Uri.parse(t.ref()), null, null));
            try { t.commit(); return "published"; }
            catch (IOException | RuntimeException e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
        } finally {
            t.abort();
        }
    }

    /**
     * Review P2, Hypothese H1 („Eintrag verschwindet während der Update-Folge des Providers“): kontrollierte Wettläufe –
     * ein zweiter Faden entfernt die eigene ausstehende Zeile 0–4 ms nach dem Start des Veröffentlichens. Ergebnisse
     * werden nach Art (veröffentlicht / rows=… / threw=…) und Zustand danach (Downloads, Files mit derselben ID, Endname
     * unter anderer ID, Dateien) gezählt und protokolliert (PUBLISH_RACE). Ein Wettlauf kann ein Muster nur zeigen oder
     * nicht zeigen – er beweist nicht die Ursache eines CI-Fehlers. Geprüft wird nur, dass jeder Zyklus ein eingeordnetes
     * Ergebnis hat; ein nach dem Veröffentlichen entfernter Eintrag ist eine eigene Klasse ("published-then-deleted").
     */
    @Test public void concurrentDeletionDuringPublishRace() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-race-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        java.util.Map<String, Integer> outcomes = new java.util.TreeMap<>();
        byte[] chunk = new byte[64 << 10];
        try {
            for (int c = 0; c < RACE_CYCLES; c++) {
                String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 ProbeRace " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
                DownloadStore.Target t = store.create(name, "video/mp4");
                File proof = Mp4DownloadsInstrumentedTest.proofOf(t);
                Uri uri = Uri.parse(t.ref());
                OutputStream out = t.out();
                for (int i = 0; i < 64; i++) out.write(chunk);        // 4 MiB: das Veröffentlichen verschiebt eine echte Datei
                out.close();
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                final int delayMs = c % 5;
                Thread deleter = new Thread(() -> {
                    try { go.await(); SystemClock.sleep(delayMs); cr.delete(uri, null, null); } catch (Exception ignored) { }
                }, "probe-race-deleter");
                deleter.start();
                String kind;
                go.countDown();
                Uri saved = null;
                try { saved = (Uri) t.commit(); kind = "published"; }
                catch (IOException | RuntimeException e) {
                    String m = String.valueOf(e.getMessage());
                    kind = m.startsWith("publish rows=") ? m.substring(8, m.indexOf(' ', 8) < 0 ? m.length() : m.indexOf(' ', 8))
                        : m.startsWith("publish threw=") ? "threw=" + m.substring(14, m.indexOf('(', 14) < 0 ? Math.min(m.length(), 40) : m.indexOf('(', 14))
                        : e.getClass().getSimpleName();
                }
                deleter.join(5_000);
                if (saved != null && !published(cr, saved)) kind = "published-then-deleted";
                String state = kind.startsWith("published") ? "" : " " + Mp4DownloadsInstrumentedTest.providerState(cr, uri, name, proof)
                    .replaceAll("\\(\\d+\\)", "");
                outcomes.merge(kind + state, 1, Integer::sum);
                t.abort();
                if (kind.startsWith("published")) { try { cr.delete(uri, null, null); } catch (RuntimeException ignored) { } }
                new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Downloads.FOLDER), name).delete();
            }
        } finally {
            store.engine.stopAll();
        }
        for (java.util.Map.Entry<String, Integer> e : outcomes.entrySet())
            Log.i(TAG, "PUBLISH_RACE sdk=" + Build.VERSION.SDK_INT + " count=" + e.getValue() + " outcome=" + e.getKey());
        int n = 0;
        for (int v : outcomes.values()) n += v;
        assertEquals("every race cycle has a classified outcome", RACE_CYCLES, n);
    }

    static final int RACE_CYCLES = 30;

    /**
     * Review P2, Hypothese H4 („Zielname belegt“): zweimal hintereinander unter DEMSELBEN Endnamen veröffentlichen (wie
     * ein zweiter Download desselben Titels). Auf Android 10 scheiterte das Veröffentlichen auf einen belegten Namen in CI
     * mit SQLiteConstraintException. Protokolliert wird, was jede Android-Version tut (PUBLISH_PROBE_SAMENAME); geprüft
     * wird nur, was immer gelten muss: der erste Eintrag ist veröffentlicht, und scheitert der zweite, bleibt von ihm kein
     * ausstehender Eintrag zurück. Kein Produktverhalten geändert.
     */
    @Test public void sameFinalNameTwice() throws Exception {
        assumeTrue("MediaStore pending entries exist from API 29", Build.VERSION.SDK_INT >= 29);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-probe-same-" + SystemClock.elapsedRealtimeNanos(),
            DeviceTestClients.loopback(9), () -> true);
        ContentResolver cr = ctx.getContentResolver();
        String name = de.evp.helfer.dl.DirectDownloads.fileName("C2 ProbeSame " + Long.toHexString(SystemClock.elapsedRealtimeNanos()));
        Uri first = null, second = null;
        String outcome;
        File secondProof = null;
        try {
            DownloadStore.Target t1 = store.create(name, "video/mp4");
            OutputStream o1 = t1.out();
            o1.write(new byte[64 << 10]);
            o1.close();
            first = (Uri) t1.commit();
            assertTrue("first publication is published", published(cr, first));
            DownloadStore.Target t2 = store.create(name, "video/mp4");
            secondProof = Mp4DownloadsInstrumentedTest.proofOf(t2);
            OutputStream o2 = t2.out();
            o2.write(new byte[64 << 10]);
            o2.close();
            try {
                second = (Uri) t2.commit();
                outcome = "published name=" + displayName(cr, second).replace(name.substring(0, name.length() - 4), "<title>");
            } catch (IOException | RuntimeException e) {
                outcome = "failed " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("uri=\\S+", "");
            } finally {
                t2.abort();
            }
            Log.i(TAG, "PUBLISH_PROBE_SAMENAME sdk=" + Build.VERSION.SDK_INT + " second=" + outcome);
            if (second == null && secondProof != null) {
                Uri all = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
                try (Cursor c = cr.query(all, new String[] {MediaStore.Downloads._ID}, "owner_package_name=? AND _display_name LIKE ?",
                        new String[] {ctx.getPackageName(), secondProof.getName() + "--%"}, null)) {
                    assertEquals("failed second publication leaves no pending entry", 0, c == null ? -1 : c.getCount());
                }
            }
        } finally {
            if (first != null) try { cr.delete(first, null, null); } catch (RuntimeException ignored) { }
            if (second != null) try { cr.delete(second, null, null); } catch (RuntimeException ignored) { }
            store.engine.stopAll();
        }
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? String.valueOf(c.getString(0)) : "?";
        }
    }

    private static boolean published(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {MediaStore.Downloads.IS_PENDING}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 0;
        }
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
        // Gesamtlauf-Bedingung: das @After des vorigen Tests löscht eine große, gerade veröffentlichte Datei; der
        // MediaProvider arbeitet danach weiter (Datei, Index). Hier unmittelbar vor dem Veröffentlichen.
        if (v == Variant.LARGE_DELETE_BEFORE_COMMIT) {
            Uri large = publishLarge(store, chunk, c);
            cr.delete(large, null, null);
        }
        Thread load = null;
        java.util.concurrent.atomic.AtomicBoolean stopLoad = new java.util.concurrent.atomic.AtomicBoolean();
        if (v == Variant.CONCURRENT_PROVIDER_LOAD) {
            // Gleichzeitig andere eigene Einträge anlegen/ändern/löschen (wie parallele Aufträge und Aufräumen)
            load = new Thread(() -> providerLoad(cr, stopLoad));
            load.start();
            SystemClock.sleep(50);
        }
        long t0 = SystemClock.elapsedRealtime();
        try {
            Uri saved;
            try { saved = (Uri) t.commit(); }
            finally { stopLoad.set(true); if (load != null) load.join(10_000); }
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

    /** 64 MiB über die App-Ablage veröffentlichen (Referenz für das Löschen einer großen fertigen Datei). */
    private static Uri publishLarge(Mp4Downloads store, byte[] chunk, int c) throws IOException {
        DownloadStore.Target big = store.create(de.evp.helfer.dl.DirectDownloads.fileName("C2 ProbeLarge " + c + " "
            + Long.toHexString(SystemClock.elapsedRealtimeNanos())), "video/mp4");
        OutputStream o = big.out();
        for (long w = 0; w < (64L << 20); w += chunk.length) o.write(chunk, 0, chunk.length);
        o.close();
        return (Uri) big.commit();
    }

    /** Fremdlast im MediaProvider: eigene kleine Einträge anlegen, schreiben, veröffentlichen, abfragen, löschen. */
    private static void providerLoad(ContentResolver cr, java.util.concurrent.atomic.AtomicBoolean stop) {
        byte[] b = new byte[16 << 10];
        while (!stop.get()) {
            Uri u = null;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, "C2 ProbeLoad " + Long.toHexString(SystemClock.elapsedRealtimeNanos()) + ".mp4");
                cv.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + Downloads.FOLDER + "/");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) continue;
                try (OutputStream o = cr.openOutputStream(u, "w")) { if (o != null) o.write(b); }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(u, done, null, null);
                try (Cursor q = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[] {MediaStore.Downloads._ID}, null, null, null)) {
                    if (q != null) q.getCount();
                }
            } catch (IOException | RuntimeException ignored) {
            } finally {
                if (u != null) { try { cr.delete(u, null, null); } catch (RuntimeException ignored) { } }
            }
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
