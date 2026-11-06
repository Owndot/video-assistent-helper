package de.evp.helfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import de.evp.helfer.hls.HlsEngine;
import de.evp.helfer.hlsmux.Media3HlsRemuxer;
import poc.Core;
import poc.TestInitiator;

/**
 * P4c-3 (P0): ECHTER Prozess-Neustart. tools/run-channel-instrumentation.sh startet Phase A, beendet den Prozess
 * (Variante 1: „am force-stop“; Variante 2: der Start der nächsten Instrumentierung beendet den alten Prozess) und
 * startet Phase B in einem neuen Prozess. Im normalen Testlauf überspringen sich beide Methoden (Argument restartPhase).
 *
 * <p>Phase A: echter HelferService + Kopplung (Noise KK) + ein laufender HLS-Download auf eigenen Journalen
 * (Download-Engines mit Testregeln, denn der echte Dienst lädt aus Sicherheitsgründen nie von Loopback). Prozess endet
 * mitten im Download.
 * <p>Phase B (neuer Prozess): alter Auftrag „interrupted“ (nie „running“), Plätze frei, neue HLS- und MP4-Downloads
 * werden fertig, der alte lässt sich fortsetzen; der echte Dienst lauscht wieder, die gespeicherte Kopplung handelt eine
 * neue Sitzung aus, Download-Befehle antworten sofort; danach dasselbe nach einem Dienst-Stopp im selben Prozess.
 */
@RunWith(AndroidJUnit4.class)
public class ProcessRestartInstrumentedTest {
    static final String TAG = "RESTART_TEST";

    static String phase() { return InstrumentationRegistry.getArguments().getString("restartPhase", ""); }
    static File stateFile() { return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getNoBackupFilesDir(), "restart-test.properties"); }

    static Mp4Downloads mp4() throws Exception {
        return new Mp4Downloads(HlsDownloadsInstrumentedTest.ctx, "mp4-jobs-restart-test", DeviceTestClients.loopback(HlsDownloadsInstrumentedTest.srv.ss.getLocalPort()),
            () -> Mp4Downloads.legacyWritePermitted(HlsDownloadsInstrumentedTest.ctx));
    }
    static HlsEngine hls(Mp4Downloads store, HlsDownloadsInstrumentedTest.Events ev) throws Exception {
        return new HlsEngine(DeviceTestClients.loopback(HlsDownloadsInstrumentedTest.srv.ss.getLocalPort()), new HlsDownloadsInstrumentedTest.Store(store), ev,
            new File(HlsDownloadsInstrumentedTest.ctx.getNoBackupFilesDir(), "hls-jobs-restart-test"), new Media3HlsRemuxer(HlsDownloads.opusSupported()),
            Mp4Downloads.SLOTS, HlsDownloads.opusSupported(), HlsDownloads.sampleBudget(Runtime.getRuntime().maxMemory()));
    }

    @Test public void phaseA_startDownloadAndPairThenTheProcessDies() throws Exception {
        assumeTrue("only in the restart run (restartPhase=A)", "A".equals(phase()));
        HlsDownloadsInstrumentedTest.setUp();
        ChannelInstrumentedTest.startApp();
        byte[] id = ChannelInstrumentedTest.kadriIdentity();
        byte[] pin = ChannelInstrumentedTest.pair(id, ChannelInstrumentedTest.openWindow());
        TestInitiator.free(ChannelInstrumentedTest.pairAndConfirm(id, pin));
        Mp4Downloads store = mp4();
        HlsEngine e = hls(store, new HlsDownloadsInstrumentedTest.Events());
        HlsDownloadsInstrumentedTest.srv.delayMs = 400;
        String job = HlsDownloadsInstrumentedTest.id();
        String plan = e.plan(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, HlsDownloadsInstrumentedTest.srv.url("ts_sep/master.m3u8"));
        String st = e.start(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, job, HlsDownloadsInstrumentedTest.field(plan, "planId"),
            HlsDownloadsInstrumentedTest.firstOption(plan), "Restart");
        assertEquals(st, "running", HlsDownloadsInstrumentedTest.field(st, "state"));
        long end = System.nanoTime() + 30_000_000_000L;
        while (HlsDownloadsInstrumentedTest.intField(e.status(job), "done") < 2 && System.nanoTime() < end) Thread.sleep(20);
        st = e.status(job);
        assertEquals(st, "running", HlsDownloadsInstrumentedTest.field(st, "state"));
        Properties p = new Properties();
        p.setProperty("job", job);
        p.setProperty("pid", String.valueOf(android.os.Process.myPid()));
        p.setProperty("id", ChannelInstrumentedTest.hex(id));
        p.setProperty("pin", ChannelInstrumentedTest.hex(pin));
        try (FileOutputStream out = new FileOutputStream(stateFile())) { p.store(out, "restart test (test identities only)"); }
        Log.i(TAG, "RESTART_A api=" + Build.VERSION.SDK_INT + " running=true done=" + HlsDownloadsInstrumentedTest.intField(st, "done"));
        // Kein Aufräumen: der Prozess wird mitten im Download beendet (Dienst läuft, Auftrag läuft).
    }

    @Test public void phaseB_afterARealProcessRestart() throws Exception {
        assumeTrue("only in the restart run (restartPhase=B)", "B".equals(phase()));
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(stateFile())) { p.load(in); }
        assertNotEquals("phase B runs in a new process", p.getProperty("pid"), String.valueOf(android.os.Process.myPid()));
        HlsDownloadsInstrumentedTest.setUp();
        HlsDownloadsInstrumentedTest.Events ev = new HlsDownloadsInstrumentedTest.Events();
        Mp4Downloads store = mp4();
        HlsEngine e = hls(store, ev);
        String old = p.getProperty("job");
        String st = e.status(old);
        assertEquals(st, "interrupted", HlsDownloadsInstrumentedTest.field(st, "state"));
        assertEquals("no job or HTTP slot held after the restart", 0, Mp4Downloads.SLOTS.inUse());
        assertEquals("no job or HTTP slot held after the restart", 0, Mp4Downloads.SLOTS.httpInUse());
        try {
            // neuer HLS-Download
            String fresh = HlsDownloadsInstrumentedTest.id();
            String plan = e.plan(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, HlsDownloadsInstrumentedTest.srv.url("ts_muxed/master.m3u8"));
            e.start(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, fresh, HlsDownloadsInstrumentedTest.field(plan, "planId"), HlsDownloadsInstrumentedTest.firstOption(plan), "Restart new");
            st = HlsDownloadsInstrumentedTest.await(e, fresh, 120_000);
            assertEquals(st, "done", HlsDownloadsInstrumentedTest.field(st, "state"));
            assertNotNull("new HLS published", ev.saved(fresh, 30_000));
            // neuer MP4-Download (Init-Segment einer fMP4-Fixture ist eine gültige MP4-Datei mit fester Länge)
            String mp4 = HlsDownloadsInstrumentedTest.id();
            store.engine.start(mp4, HlsDownloadsInstrumentedTest.srv.url("fmp4_muxed/init.mp4"), "Restart MP4");
            long end = System.nanoTime() + 60_000_000_000L;
            String ms = store.engine.status(mp4);
            while (ms.contains("\"state\":\"running\"") && System.nanoTime() < end) { Thread.sleep(50); ms = store.engine.status(mp4); }
            assertTrue(ms, ms.contains("\"state\":\"done\""));
            // der unterbrochene Auftrag lässt sich mit neuem Plan und derselben Kennung fortsetzen
            String p2 = e.plan(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, HlsDownloadsInstrumentedTest.srv.url("ts_sep/master.m3u8"));
            e.start(HlsDownloadsInstrumentedTest.PEER, HlsDownloadsInstrumentedTest.BIND, old, HlsDownloadsInstrumentedTest.field(p2, "planId"), HlsDownloadsInstrumentedTest.firstOption(p2), "Restart");
            st = HlsDownloadsInstrumentedTest.await(e, old, 120_000);
            assertEquals(st, "done", HlsDownloadsInstrumentedTest.field(st, "state"));
            assertNotNull("resumed HLS published", ev.saved(old, 30_000));
            assertEquals("slots free after the downloads", 0, Mp4Downloads.SLOTS.inUse());

            // echter Dienst: lauscht wieder, gespeicherte Kopplung handelt eine neue Sitzung aus, Download-Befehle antworten
            ChannelInstrumentedTest.startApp();
            byte[] id = unhex(p.getProperty("id")), pin = unhex(p.getProperty("pin"));
            channelWorks(id, pin, "after process restart");
            // normaler Dienst-Stopp und Neustart im selben Prozess
            ChannelInstrumentedTest.restartService();
            channelWorks(id, pin, "after service stop/start");
            Log.i(TAG, "RESTART_B api=" + Build.VERSION.SDK_INT + " oldInterrupted=true newHls=done newMp4=done resumed=done channel=ok serviceRestart=ok");
        } finally {
            for (Object s : ev.saved.values()) HlsDownloadsInstrumentedTest.delete(s);
            e.stopAll();
            store.engine.stopAll();
            deleteTree(new File(HlsDownloadsInstrumentedTest.ctx.getNoBackupFilesDir(), "hls-jobs-restart-test"));
            new File(HlsDownloadsInstrumentedTest.ctx.getFilesDir(), "mp4-jobs-restart-test").delete();
            stateFile().delete();
            if (HelferService.instance != null) HelferService.instance.revoke(null);
            HlsDownloadsInstrumentedTest.tearDown();
        }
    }

    /** Kanal steht, Kopplung gilt, Download-Befehle des echten Dienstes antworten sofort (kein Hängen). */
    static void channelWorks(byte[] id, byte[] pin, String when) throws Exception {
        long h = ChannelInstrumentedTest.connect(id, pin);
        try {
            String s = ChannelInstrumentedTest.request(h, "{\"op\":\"status\"}");
            assertTrue(when + ": " + s, s.startsWith("{\"ok\":true"));
            String job = ChannelInstrumentedTest.hex(Core.identityGenerate()).substring(0, 32);
            long t0 = System.nanoTime();
            String r = ChannelInstrumentedTest.request(h, "{\"op\":\"dlstart\",\"id\":\"" + job + "\",\"url\":\"http://127.0.0.1/x.mp4\",\"name\":\"Restart\"}");
            assertTrue(when + ": " + r, r.startsWith("{\"ok\":true,\"id\":\"" + job + "\""));
            long end = System.nanoTime() + 15_000_000_000L;
            while (r.contains("\"state\":\"running\"") && System.nanoTime() < end) { Thread.sleep(50); r = ChannelInstrumentedTest.request(h, "{\"op\":\"dlstatus\",\"id\":\"" + job + "\"}"); }
            assertTrue(when + " (MP4 engine answers, blocked target refused): " + r, r.contains("\"state\":\"failed\"") && r.contains("\"reason\":\"blocked_"));
            assertTrue(when + ": answered quickly", (System.nanoTime() - t0) / 1_000_000 < 15_000);
        } finally {
            TestInitiator.free(h);
        }
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
