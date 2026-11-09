package de.evp.helfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.net.LocalTestClients;

/**
 * TESTGERÜST – nie ausgeliefert. Die echte Download-Engine der Helper-App ({@link DirectDownloads}, Phase C2) für den
 * Desktop-Helfer: echter {@link de.evp.helfer.net.SafeHttpClient} mit den Testregeln (einziges erlaubtes Ziel
 * 127.0.0.1:Port des Testservers, alle Prüfschichten aktiv, private und lokale Ziele sonst gesperrt). Ersetzt ist nur
 * die Android-Ablage: ein Ordner im Zustandsverzeichnis (Teil-Datei, Umbenennen beim Veröffentlichen), das Journal
 * daneben (überdauert einen Neustart des Prozesses) und ein Schalter für die Speicherberechtigung (Android 8–9).
 */
final class HarnessDownloads implements DownloadStore, DirectDownloads.Listener {
    final DirectDownloads engine;
    private final File dir, journal;
    volatile boolean writable = true;

    HarnessDownloads(File stateDir, int mediaPort) throws Exception {
        dir = new File(stateDir, "downloads");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir");
        journal = new File(stateDir, "mp4-jobs");
        for (File f : list()) if (f.getName().startsWith(".part-")) f.delete();   // Reste eines beendeten Prozesses
        engine = new DirectDownloads(LocalTestClients.loopback(mediaPort), this, this, slots);
        engine.setRetry(retry);
    }

    /** P4c: Netz des „Geräts“ (Befehl dl-net on|off) und Wiederholungen 20-fach verkürzt (100 ms … 30 s). */
    final java.util.concurrent.atomic.AtomicBoolean online = new java.util.concurrent.atomic.AtomicBoolean(true);
    final de.evp.helfer.dl.Retry retry = new de.evp.helfer.dl.Retry(online::get, de.evp.helfer.dl.Retry.Clock.SYSTEM, 20);
    void net(boolean on) { online.set(on); retry.networkChanged(); }

    /** Gemeinsame Grenzen von MP4 und HLS wie in der App (2 Aufträge, 4 HTTP-Anfragen). */
    final de.evp.helfer.dl.JobSlots slots = new de.evp.helfer.dl.JobSlots(2, 4);
    /** P4b: echte HLS-Engine der App (null: aus – der Kanal lehnt hlsplan/hlsstart ab wie bei einem alten Helfer). */
    de.evp.helfer.hls.HlsEngine hls;

    /**
     * HLS an: echte {@link de.evp.helfer.hls.HlsEngine} (Pläne, Sitzungsbindung, Journal, Abruf über SafeHttpClient mit
     * den Testregeln, Wiederaufnahme, Abbruch). NUR der Remux ist hier eine Nachbildung ohne Medienbibliothek (Ressourcen
     * hintereinander, Prüfung nur der Länge): Kadris Ablauf wird Ende-zu-Ende geprüft, das echte Umpacken prüfen
     * tests/java/hlsmux und die Emulatortests.
     */
    void enableHls(File stateDir, int mediaPort) throws Exception {
        if (hls != null) return;
        hls = new de.evp.helfer.hls.HlsEngine(LocalTestClients.loopback(mediaPort), this, this, new File(stateDir, "hls-jobs"), new ConcatRemux(),
            slots, false, 1_000_000);
        hls.setRetry(retry);
    }

    /** Nur Testgerüst: verkettet die Ressourcen (kein MP4) – nie in der App. */
    static final class ConcatRemux implements de.evp.helfer.hls.HlsRemux {
        @Override public Result remux(Input in, File out, java.util.function.BooleanSupplier cancelled) throws IOException, de.evp.helfer.hls.HlsException {
            try (OutputStream o = new FileOutputStream(out)) {
                for (File f : in.video) { if (cancelled.getAsBoolean()) throw new de.evp.helfer.hls.HlsException("cancelled"); o.write(Files.readAllBytes(f.toPath())); }
                for (File f : in.audio) o.write(Files.readAllBytes(f.toPath()));
            } catch (de.evp.helfer.hls.HlsException e) {
                out.delete();
                throw e;
            }
            return new Result(in.video.size(), in.audio.size(), 0, 0);
        }
        @Override public void verify(File out, Result expected, double v, double a, double tol, java.util.function.BooleanSupplier cancelled) throws de.evp.helfer.hls.HlsException {
            if (!out.isFile() || out.length() == 0) throw new de.evp.helfer.hls.HlsException("verify-missing");
        }
    }

    private File[] list() { File[] l = dir.listFiles(); return l == null ? new File[0] : l; }

    /** Fertige Dateien: "FILES name:größe …" (Teil-Dateien nicht). */
    String files() {
        StringBuilder b = new StringBuilder("FILES");
        File[] l = list();
        Arrays.sort(l);
        for (File f : l) if (!f.getName().startsWith(".part-")) b.append(' ').append(f.getName().replace(' ', '_')).append(':').append(f.length());
        return b.toString();
    }

    int parts() { int n = 0; for (File f : list()) if (f.getName().startsWith(".part-")) n++; return n; }

    String journalText() throws IOException {
        byte[] j = loadJournal();
        return "JOURNAL " + (j == null ? "-" : new String(j, java.nio.charset.StandardCharsets.US_ASCII).replace('\n', '|'));
    }

    private final java.util.Set<String> reserved = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Erster Name aus „Name.mp4“, „Name (1).mp4“ …, der weder als Datei existiert noch reserviert ist. */
    private synchronized File free(String displayName, File release) {
        if (release != null) reserved.remove(release.getPath());
        File f = new File(dir, displayName);
        for (int n = 1; f.exists() || reserved.contains(f.getPath()); n++) f = new File(dir, displayName.replaceFirst("(\\.mp4)?$", " (" + n + ")$1"));
        reserved.add(f.getPath());
        return f;
    }

    // ------------------------------------------------------------------ DownloadStore

    @Override public long freeBytes() { return dir.getUsableSpace(); }
    @Override public boolean writable() { return writable; }

    @Override public boolean isPublished(String ref, long total) {
        File f = new File(ref);
        return dir.equals(f.getParentFile()) && f.isFile() && f.length() == total && !f.getName().startsWith(".part-");
    }

    @Override public Target create(String displayName, String mime) throws IOException {
        File part = File.createTempFile(".part-", ".tmp", dir);
        // Wie die Android-Ablage (Mp4Downloads.PublicFile): beim Anlegen einen FREIEN Namen wählen – der Journal-Verweis
        // zeigt so nie auf eine ältere Datei gleichen Namens (sonst hielte die Absturz-Wiederherstellung sie für fertig).
        File fin = free(displayName, null);
        FileOutputStream out = new FileOutputStream(part);
        return new Target() {
            File published;
            boolean finished;
            @Override public OutputStream out() { return out; }
            @Override public synchronized String ref() { return (published != null ? published : fin).getPath(); }
            @Override public synchronized Object commit() throws IOException {
                out.close();
                File dst = fin.exists() ? free(displayName, fin) : fin;
                // Dateiname im Zeichensatz des Desktops nicht darstellbar (z. B. ohne UTF-8-Locale): Ablagefehler wie auf
                // dem Gerät, nicht RuntimeException (die Engine meldete sonst "network").
                try { Files.move(part.toPath(), dst.toPath()); } catch (java.nio.file.InvalidPathException e) { throw new IOException("name", e); }
                published = dst;
                finished = true;
                reserved.remove(dst.getPath());
                return dst.getPath();
            }
            @Override public synchronized void abort() {
                if (finished) return;
                finished = true;
                try { out.close(); } catch (IOException ignored) { }
                part.delete();
                reserved.remove(fin.getPath());
            }
        };
    }

    @Override public synchronized byte[] loadJournal() throws IOException {
        return journal.exists() ? Files.readAllBytes(journal.toPath()) : null;
    }

    @Override public synchronized void saveJournal(byte[] data) throws IOException {
        File tmp = new File(journal.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ Rückmeldungen (wie die Benachrichtigung: ohne URL)

    @Override public void progress(String id, String title, long bytes, long total) { }

    @Override public void finished(String id, String title, String state, String reason, Object saved) {
        ChannelHarness.downloadEvent(String.format(Locale.ROOT, "DL %s %s %s", id, state, reason.isEmpty() ? "-" : reason));
    }
}
