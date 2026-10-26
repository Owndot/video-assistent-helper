package de.evp.helfer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;

/** Durable reservations, never a cache. Signed IDs preserve the existing int wire format.
 * Negative IDs avoid the old process-local positive namespace (which started at 101).
 * Every record reserves one ID before use; a crash may waste an ID, never reuse it.
 */
public final class JobIds {
    private JobIds() {}
    static int candidate(long bytes) throws IOException {
        if (bytes < 0 || bytes % 8 != 0) throw new IOException("Incomplete job ID journal");
        long id = -2 - bytes / 8;
        if (id < Integer.MIN_VALUE) throw new IOException("Job ID space exhausted");
        return (int) id;
    }
    public interface DirectorySync { void sync(File directory) throws IOException; }
    public static int reserve(File journal) throws IOException {
        return reserve(journal, parent -> {
            try (FileChannel directory = FileChannel.open(parent.toPath(),StandardOpenOption.READ)) { directory.force(true); }
        });
    }
    public static synchronized int reserve(File journal, DirectorySync directorySync) throws IOException {
        File parent = journal.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("No job ID directory");
        try (RandomAccessFile file = new RandomAccessFile(journal,"rw"); FileLock lock = file.getChannel().lock()) {
            long length = file.length();
            int next = candidate(length);
            if (length > 0) {
                file.seek(length - 8);
                int previous = file.readInt(), check = file.readInt();
                if (previous != (long) next + 1 || check != ~previous) throw new IOException("Corrupt job ID journal");
            }
            file.seek(length);
            file.writeInt(next); file.writeInt(~next);
            file.getFD().sync();
            // Persist first creation as well as contents before returning an identity.
            directorySync.sync(parent);
            return next;
        }
    }
}
