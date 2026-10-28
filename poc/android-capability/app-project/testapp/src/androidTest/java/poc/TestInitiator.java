package poc;

/**
 * Kadri side of the protocol, for instrumentation tests only (Rust feature
 * jni-test-initiator). Both this class and its native library ship only in
 * the instrumentation APK; the installable test app contains neither. It is
 * callable only from code in this process and has no IPC or network entry.
 */
public final class TestInitiator {
    static { System.loadLibrary("kadri_pairing_poc_testinit"); }   // instrumentation APK only
    private TestInitiator() {}

    public static native long pairStart(byte[] identity, String code, long nowMs, long ttlMs);
    public static native byte[] m0(long h);
    public static native byte[] onM1(long h, byte[] m1, long nowMs);
    /** registration_id(16) || helper_public(32) */
    public static native byte[] onM3(long h, byte[] m3, long nowMs);
    public static native long handshakeStart(byte[] identity, byte[] helperPublic, byte[] registrationId);
    public static native byte[] hs1(long h);
    /** Returns the session id; the handle becomes the channel. */
    public static native byte[] finish(long h, byte[] hs2);
    public static native byte[] sealControl(long h, byte[] json);
    /** kind(1) || body */
    public static native byte[] open(long h, byte[] record);
    public static native void free(long h);
}
