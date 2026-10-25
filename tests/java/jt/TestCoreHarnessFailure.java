import de.evp.helfer.Fetcher;
import java.io.IOException;

/** Failure injection for the test harness, not an Android/browser simulation. */
public class TestCoreHarnessFailure {
    public static void main(String[] args) {
        Fetcher broken = new Fetcher() {
            public byte[] fetch(String u, long off, long len) throws IOException {
                throw new IOException("disposable injected transport failure");
            }
            public long size(String u) throws IOException {
                throw new IOException("disposable injected transport failure");
            }
        };
        boolean rejected = false;
        try {
            TestMain.run("harness_regression", "unused", "file", broken);
        } catch (AssertionError expected) {
            rejected = expected.getMessage().startsWith("Unexpected core failure:");
        } finally {
            // The deliberately failed file transfer may leave an empty test
            // output. It is not a successful media sample for ffprobe.
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("out/harness_regression/video.mp4"));
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("out/harness_regression"));
            } catch (IOException error) {
                throw new AssertionError("Cannot clean deliberate harness-failure output", error);
            }
        }
        if (!rejected) throw new AssertionError("Core harness swallowed an unexpected transport failure");
        System.out.println("PASS: core harness propagates unexpected transport failure");
    }
}
