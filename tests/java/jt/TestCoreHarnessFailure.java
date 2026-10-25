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
        }
        if (!rejected) throw new AssertionError("Core harness swallowed an unexpected transport failure");
        System.out.println("PASS: core harness propagates unexpected transport failure");
    }
}
