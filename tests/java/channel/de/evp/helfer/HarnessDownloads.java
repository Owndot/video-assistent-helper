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
        engine = new DirectDownloads(LocalTestClients.loopback(mediaPort), this, this);
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

    // ------------------------------------------------------------------ DownloadStore

    @Override public long freeBytes() { return dir.getUsableSpace(); }
    @Override public boolean writable() { return writable; }

    @Override public boolean isPublished(String ref, long total) {
        File f = new File(ref);
        return dir.equals(f.getParentFile()) && f.isFile() && f.length() == total && !f.getName().startsWith(".part-");
    }

    @Override public Target create(String displayName, String mime) throws IOException {
        File part = File.createTempFile(".part-", ".tmp", dir);
        File fin = new File(dir, displayName);
        FileOutputStream out = new FileOutputStream(part);
        return new Target() {
            File published;
            boolean finished;
            @Override public OutputStream out() { return out; }
            @Override public synchronized String ref() { return (published != null ? published : fin).getPath(); }
            @Override public synchronized Object commit() throws IOException {
                out.close();
                File dst = fin;
                for (int n = 1; dst.exists(); n++) dst = new File(dir, displayName.replaceFirst("(\\.mp4)?$", " (" + n + ")$1"));
                Files.move(part.toPath(), dst.toPath());
                published = dst;
                finished = true;
                return dst.getPath();
            }
            @Override public synchronized void abort() {
                if (finished) return;
                finished = true;
                try { out.close(); } catch (IOException ignored) { }
                part.delete();
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
