package de.evp.pakepoc;

import java.util.Arrays;

/**
 * Serialises "export registry snapshot" and "write it to disk" (review F1).
 *
 * Without this, a request thread could export a snapshot, lose the CPU, and
 * write it after a newer snapshot (e.g. a revocation) was already saved, so
 * the revoked registration would come back after a restart. Here export and
 * write happen under one lock, so files are written in export order and the
 * last write is always the newest export.
 *
 * Writes happen only when the exported bytes differ from the last durable
 * write (review F4): session and transport state is not part of the export,
 * so ordinary records cause no disk I/O. A failed write throws and leaves
 * {@code lastDurable} unchanged, so the next call retries; callers must not
 * report success for a durable change when this throws.
 *
 * Plain Java (no Android types) so the ordering is also tested on the JVM.
 * Lock order: this writer, then the core lock inside {@code Source}. Nothing
 * may call into the writer while holding the core lock.
 */
final class SnapshotWriter {
    interface Source { byte[] export() throws Exception; }   // takes the core lock itself
    interface Sink { void write(byte[] snapshot) throws Exception; }
    interface Hook { void beforeWrite(byte[] snapshot) throws Exception; }

    private final Sink sink;
    private byte[] lastDurable;      // null: nothing written by this process yet
    volatile Hook testHook;          // instrumentation/JVM tests only; never set by app code

    SnapshotWriter(Sink sink) { this.sink = sink; }

    /** Returns true if a write happened; throws if the write failed. */
    synchronized boolean persist(Source source) throws Exception {
        byte[] snapshot = source.export();
        if (snapshot == null) return false;              // core not owned by the caller
        if (Arrays.equals(snapshot, lastDurable)) return false;
        Hook h = testHook;
        if (h != null) h.beforeWrite(snapshot);
        sink.write(snapshot);
        lastDurable = snapshot;
        return true;
    }

    interface Action { void run() throws Exception; }

    /** Deletes the durable state (identity reset) in order with all writes. */
    synchronized void delete(Action delete) throws Exception {
        lastDurable = null;
        delete.run();
    }
}
