package de.evp.helfer;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Registrations (public Kadri keys and ids only, no secrets), written
 * atomically together with the boot count of the device. Times inside the
 * blob are on SystemClock.elapsedRealtime(), which restarts at boot; the core
 * is told whether the stored blob is from the current boot (review R3).
 *
 * File: {@code registrations.v1} = "KPR1" | bootCount(int, BE) | core export.
 */
public final class RegistryStore {
    public static final class Loaded {
        public final byte[] blob;
        public final boolean sameBoot;
        Loaded(byte[] blob, boolean sameBoot) { this.blob = blob; this.sameBoot = sameBoot; }
    }

    private static final byte[] MAGIC = "KPR1".getBytes(StandardCharsets.US_ASCII);
    private final File file;
    private final int bootCount;

    public RegistryStore(File dir, int bootCount) {
        this.file = new File(dir, "registrations.v1");
        this.bootCount = bootCount;
    }

    /** Missing file: empty registry. Corrupt file: exception (no silent reset). */
    public synchronized Loaded load() throws Exception {
        byte[] b;
        try { b = new AtomicFile(file).readFully(); }
        catch (FileNotFoundException e) { return new Loaded(new byte[0], true); }
        if (b.length < 8 || !Arrays.equals(Arrays.copyOf(b, 4), MAGIC)) throw new IllegalStateException("corrupt registrations file");
        int stored = ByteBuffer.wrap(b, 4, 4).getInt();
        return new Loaded(Arrays.copyOfRange(b, 8, b.length), stored == bootCount);
    }

    public synchronized void save(byte[] coreExport) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(8 + coreExport.length);
        bb.put(MAGIC).putInt(bootCount).put(coreExport);
        IdentityStore.writeAtomically(file, bb.array());
    }

    /** True if a registry file (or AtomicFile's backup of one) is present. */
    public synchronized boolean exists() {
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    public synchronized void delete() throws IOException {
        new AtomicFile(file).delete();
        if (exists()) throw new IOException("registrations file could not be deleted");
    }
}
