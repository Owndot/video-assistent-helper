package de.evp.helfer.hlsmux;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verifizierte Ressourcen einer Spur (Init + Segmente) als EIN logischer Datenstrom, ohne sie zu einer zweiten
 * Datei zusammenzukopieren (kein doppelter Speicherplatz). Wahlfreier Zugriff mit festen, kleinen Puffern.
 */
final class Concat implements AutoCloseable {
    private final List<File> files;
    private final long[] starts;
    private final long total;
    private final RandomAccessFile[] open;

    Concat(List<File> files) {
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        starts = new long[files.size() + 1];
        long t = 0;
        for (int i = 0; i < files.size(); i++) { starts[i] = t; t += files.get(i).length(); }
        starts[files.size()] = t;
        total = t;
        open = new RandomAccessFile[files.size()];
    }

    public long length() { return total; }

    public File first() { return files.get(0); }

    /** Liest ab {@code position}; -1 am Ende. */
    public synchronized int readAt(long position, byte[] buf, int off, int len) throws IOException {
        if (position >= total) return -1;
        if (len == 0) return 0;
        int i = 0;
        while (starts[i + 1] <= position) i++;
        if (open[i] == null) open[i] = new RandomAccessFile(files.get(i), "r");
        long inFile = position - starts[i];
        int n = (int) Math.min(len, files.get(i).length() - inFile);
        open[i].seek(inFile);
        open[i].readFully(buf, off, n);
        return n;
    }

    @Override public synchronized void close() {
        for (int i = 0; i < open.length; i++) {
            if (open[i] != null) { try { open[i].close(); } catch (IOException ignored) { } open[i] = null; }
        }
    }
}
