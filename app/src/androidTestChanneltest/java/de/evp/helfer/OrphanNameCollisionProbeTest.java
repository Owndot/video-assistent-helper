package de.evp.helfer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.net.DeviceTestClients;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Codex Release-Readiness 2, Punkt 7 – NUR Wegwerf-Emulator, NUR synthetische Daten. Läuft nie im normalen Gerätelauf;
 * aufgerufen von tools/run-orphan-collision-probe.sh, das VORHER eine verwaiste Enddatei (Datei ohne MediaStore-Zeile,
 * wie nach "publish rows=0" beobachtet) mit eindeutigem Namen und bekanntem SHA-256 anlegt und NACHHER Datei, Hash und
 * Verzeichnis prüft.
 *
 * Der Test lädt über die echte Engine ({@link DirectDownloads}) und die echte App-Ablage ({@link Mp4Downloads}) einen
 * anderen, bekannten Inhalt unter demselben Titel und protokolliert vorher/nachher alle MediaStore-Zeilen mit diesem
 * Namensanfang (ID, Name, Größe, SHA-256 des Inhalts) sowie den Abschlussstatus. Er löscht danach nur die Zeilen, die
 * während des Tests NEU entstanden sind und den erwarteten neuen Inhalt haben; die verwaiste Datei rührt er nie an.
 * Bewertet wird auf dem Host (Überschreiben/Datenverlust); hier wird nur die Integrität des neuen Inhalts geprüft.
 */
@RunWith(AndroidJUnit4.class)
public class OrphanNameCollisionProbeTest {
    private static final String TAG = "MP4_TEST";
    static final int SIZE = 256 << 10;

    /** Gültiger MP4-Anfang (ftyp) wie in den übrigen Gerätetests; sonst bricht die Engine vor der Ablage ab (not_mp4). */
    static byte[] newContent() {
        byte[] b = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) b[i] = (byte) Mp4DownloadsInstrumentedTest.at(i);
        return b;
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String sha(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }

    /** Alle sichtbaren und ausstehenden Zeilen, deren Name mit dem Titel beginnt: "id|name|size|sha" (sha nur lesbar bei eigenen Zeilen). */
    static List<String> rows(ContentResolver cr, String title, List<Long> ids) {
        List<String> out = new ArrayList<>();
        Uri base = Build.VERSION.SDK_INT >= 29 ? MediaStore.Downloads.EXTERNAL_CONTENT_URI : MediaStore.Files.getContentUri("external");
        if (Build.VERSION.SDK_INT >= 29) base = MediaStore.setIncludePending(base);
        try (Cursor c = cr.query(base, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[] {"%" + title + "%"}, MediaStore.MediaColumns._ID)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                Uri u = ContentUris.withAppendedId(base, id);
                String h;
                try (InputStream in = cr.openInputStream(u)) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                    h = hex(md.digest());
                } catch (Exception e) {
                    h = "unreadable:" + e.getClass().getSimpleName();
                }
                ids.add(id);
                out.add(id + "|" + c.getString(1) + "|" + c.getLong(2) + "|" + h);
            }
        }
        return out;
    }

    @Test public void downloadOverAnOrphanedFinalName() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String title = args.getString("orphanTitle");
        assertNotNull("only run by tools/run-orphan-collision-probe.sh (argument orphanTitle)", title);
        assertTrue("synthetic probe name only", title.startsWith("C2 OrphanProbe "));
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver cr = ctx.getContentResolver();
        byte[] body = newContent();
        String want = sha(body);
        List<Long> before = new ArrayList<>(), after = new ArrayList<>();
        List<String> rowsBefore = rows(cr, title, before);
        String status = "";
        try (Mp4DownloadsInstrumentedTest.Server srv = new Mp4DownloadsInstrumentedTest.Server((line, h, out, i) -> {
            Mp4DownloadsInstrumentedTest.head(out, "200 OK", "Content-Type: video/mp4", "Content-Length: " + SIZE, "ETag: \"orphan-probe\"");
            out.write(body);
        })) {
            Mp4Downloads store = new Mp4Downloads(ctx, "mp4-jobs-orphan-probe-" + SystemClock.elapsedRealtimeNanos(),
                DeviceTestClients.loopback(srv.ss.getLocalPort()), () -> Mp4Downloads.legacyWritePermitted(ctx));
            try {
                String id = Mp4DownloadsInstrumentedTest.newId();
                store.engine.start(id, srv.url("/v.mp4"), title);
                long end = SystemClock.elapsedRealtime() + 60_000;
                do {
                    status = store.engine.status(id);
                    if (status.contains("\"state\":\"done\"") || status.contains("\"state\":\"failed\"") || status.contains("\"state\":\"cancelled\"")) break;
                    Thread.sleep(50);
                } while (SystemClock.elapsedRealtime() < end);
            } finally {
                store.engine.stopAll();
            }
        }
        List<String> rowsAfter = rows(cr, title, after);
        String state = status.replaceAll(".*\"state\":\"([a-z]+)\".*", "$1");
        String reason = status.replaceAll(".*\"reason\":\"([^\"]*)\".*", "$1");
        Log.i(TAG, "ORPHAN_PROBE sdk=" + Build.VERSION.SDK_INT + " state=" + state + " reason=" + reason + " newSha=" + want
            + " rowsBefore=" + rowsBefore + " rowsAfter=" + rowsAfter.toString().replace(title, "<title>"));
        // Aufräumen: nur in diesem Test neu entstandene Zeilen mit genau dem neuen Inhalt (nie die verwaiste Datei)
        for (String r : rowsAfter) {
            String[] f = r.split("\\|");
            long id = Long.parseLong(f[0]);
            if (!before.contains(id) && f[3].equals(want)) {
                Uri base = Build.VERSION.SDK_INT >= 29 ? MediaStore.Downloads.EXTERNAL_CONTENT_URI : MediaStore.Files.getContentUri("external");
                cr.delete(ContentUris.withAppendedId(base, id), null, null);
            }
        }
        // Die Sonde ist nur aussagekräftig, wenn der Download die Veröffentlichung tatsächlich erreicht hat.
        assertTrue("probe must reach the publication step (done or a storage failure), got " + state + "/" + reason,
            state.equals("done") || (state.equals("failed") && (reason.equals("storage") || reason.equals("internal"))));
        if (state.equals("done")) {
            boolean found = false;
            for (String r : rowsAfter) found |= r.endsWith("|" + SIZE + "|" + want);
            assertTrue("a completed download must be readable with exactly the served content: " + rowsAfter, found);
        }
    }
}
