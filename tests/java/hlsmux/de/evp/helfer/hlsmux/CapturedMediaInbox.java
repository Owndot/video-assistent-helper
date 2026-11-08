package de.evp.helfer.hlsmux;

import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsRemux;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** TEST-ONLY prototype: bounded private staging, no network/URL/header API, no public output.
 * Not included in the app. No channel authorization, Android durability or restart/resume claim.
 */
final class CapturedMediaInbox {
    static final int CHUNK = 16 * 1024, MAX_PARTS = 2048;
    static final long MAX_PART = 8L << 20, MAX_BYTES = 64L << 20;
    static final class Part {
        final long length;
        final byte[] hash;
        Part(long length, byte[] hash) throws IOException {
            if (length <= 0 || length > MAX_PART || hash == null || hash.length != 32) throw new IOException("metadata");
            this.length = length; this.hash = hash.clone();
        }
    }
    private final File dir;
    private final List<Part> parts;
    private final double seconds;
    private volatile boolean cancelled;
    private String state = "receiving", reason = "";
    private int index;
    private long offset;
    private FileOutputStream stream;
    private MessageDigest digest;
    private boolean verifying;

    CapturedMediaInbox(File root, List<Part> declared, double seconds) throws IOException {
        if (declared == null || declared.isEmpty() || declared.size() > MAX_PARTS ||
                !Double.isFinite(seconds) || seconds <= 0 || seconds > 86400) throw new IOException("metadata");
        long sum = 0;
        List<Part> copy = new ArrayList<>();
        for (Part p : declared) {
            if (p == null || p.length <= 0 || p.length > MAX_PART || p.hash.length != 32 || p.length > MAX_BYTES-sum)
                throw new IOException("metadata");
            sum += p.length; copy.add(new Part(p.length, p.hash));
        }
        this.parts = Collections.unmodifiableList(copy); this.seconds = seconds;
        dir = Files.createTempDirectory(root.toPath(), "capture-").toFile();
    }
    private CapturedMediaInbox(File dir) throws IOException {
        this.dir = dir; this.parts = Collections.emptyList(); this.seconds = 0;
        this.cancelled = new File(dir, "cancelled").isFile();
        this.state = cancelled ? "cancelled" : "interrupted";
        this.reason = cancelled ? "cancelled" : "restart_unsupported";
        eraseMedia(); // Fail closed after restart; never revive an incomplete/uncommitted inbox.
    }
    static CapturedMediaInbox recoverForTest(File ownedDirectory) throws IOException { return new CapturedMediaInbox(ownedDirectory); }
    static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    File directoryForTest() { return dir; }
    File outputForTest() { return new File(dir, "validated.mp4"); }
    synchronized String state() { return state; }
    synchronized String reason() { return reason; }
    private File resource(int n) { return new File(dir, String.format(Locale.ROOT, "%04d.bin", n)); }
    private File partial() { return new File(dir, "receiving.part"); }
    private void receiving() throws IOException {
        if (cancelled) throw new IOException("cancelled");
        if (!state.equals("receiving")) throw new IOException(reason.isEmpty() ? "state" : reason);
    }
    private void eraseMedia() throws IOException {
        if (stream != null) { try { stream.close(); } finally { stream = null; } }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir.toPath())) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.matches("[0-9]{4}\\.bin") || name.equals("receiving.part") || name.equals("validated.mp4"))
                    Files.deleteIfExists(p);
            }
        }
    }
    private void reject(String why) throws IOException {
        if (!cancelled) { state = "rejected"; reason = why; }
        eraseMedia();
    }
    synchronized void accept(int part, long at, byte[] data) throws IOException {
        receiving();
        if (index >= parts.size() || part != index || at != offset || data == null || data.length == 0 ||
                data.length > CHUNK || data.length > parts.get(index).length-offset) {
            reject("sequence_or_size"); throw new IOException("sequence_or_size");
        }
        try {
            if (stream == null) {
                if (!partial().createNewFile()) throw new IOException("staging_exists");
                stream = new FileOutputStream(partial()); digest = sha();
            }
            byte[] stable = data.clone(); // caller cannot mutate between disk write and digest
            stream.write(stable); digest.update(stable); offset += stable.length;
            if (offset == parts.get(index).length) {
                stream.getFD().sync(); stream.close(); stream = null;
                if (!MessageDigest.isEqual(digest.digest(), parts.get(index).hash)) {
                    reject("integrity"); throw new IOException("integrity");
                }
                Files.move(partial().toPath(), resource(index).toPath(), StandardCopyOption.ATOMIC_MOVE);
                index++; offset = 0;
            }
        } catch (IOException e) {
            if (!state.equals("rejected")) reject("storage");
            throw e;
        }
    }
    void cancel() throws IOException {
        synchronized (this) {
            cancelled = true; state = "cancelled"; reason = "cancelled";
            // Marker is retained when media is erased. Failure still cancels this process; recovery never resumes.
            try (FileOutputStream f = new FileOutputStream(new File(dir, "cancelled"))) { f.write(1); f.getFD().sync(); }
            if (!verifying) eraseMedia();
        }
    }
    void verify(HlsRemux remux) throws IOException, HlsException {
        List<File> files = new ArrayList<>();
        synchronized (this) {
            receiving();
            if (index != parts.size() || offset != 0) { reject("incomplete"); throw new IOException("incomplete"); }
            state = "verifying"; verifying = true;
            for (int i=0; i<parts.size(); i++) files.add(resource(i));
        }
        boolean ok = false;
        try {
            byte[] buffer = new byte[CHUNK];
            for (int i=0; i<files.size(); i++) {
                MessageDigest hash = sha(); long size = 0;
                try (InputStream in = new FileInputStream(files.get(i))) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (cancelled) throw new IOException("cancelled");
                        size += n; if (size > parts.get(i).length) throw new IOException("integrity");
                        hash.update(buffer, 0, n);
                    }
                }
                if (size != parts.get(i).length || !MessageDigest.isEqual(hash.digest(), parts.get(i).hash)) throw new IOException("integrity");
            }
            HlsRemux.Result r = remux.remux(new HlsRemux.Input(files, Collections.emptyList(), true, 1_000_000), outputForTest(), () -> cancelled);
            remux.verify(outputForTest(), r, seconds, seconds, 0.25, () -> cancelled);
            synchronized (this) {
                if (cancelled) throw new IOException("cancelled");
                state = "verified"; ok = true; // PRIVATE candidate only; no MediaStore/publication operation exists.
            }
        } finally {
            synchronized (this) {
                verifying = false;
                if (!ok || cancelled) reject(cancelled ? "cancelled" : "verification");
            }
        }
    }
}
