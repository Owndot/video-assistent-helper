package poc;

/** JNI view of the shared Rust core (same functions as the JVM stand-in). All failures throw SecurityException. */
public final class Core {
    static { System.loadLibrary("kadri_pairing_poc"); }
    private Core() {}

    public static native byte[] identityGenerate();
    /** Returns the helper public key as hex. nowMs is the monotonic clock of the caller. */
    public static native String hostInit(byte[] identity, byte[] registrations, long nowMs, boolean sameBoot);
    /** code == null: generate a fresh code. Returns the code for display. */
    public static native String windowOpen(String code, long nowMs, long ttlMs);
    public static native byte[] windowOnM0(byte[] m0, long nowMs);
    /** registration_id(16) || M3; registration added as pending first. */
    public static native byte[] windowOnM2(byte[] m2, long nowMs);
    public static native byte[] hostHandshake(byte[] hs1, long nowMs);
    /** session_id(16) || kind(1) || body */
    public static native byte[] hostOpen(byte[] record, long nowMs);
    public static native byte[] hostSealControl(byte[] session, byte[] json, long nowMs);
    public static native byte[] hostSealMedia(byte[] session, int transfer, long offset, byte[] data, long nowMs);
    public static native byte[] hostSealEnd(byte[] session, int transfer, long total, long nowMs);
    public static native byte[] hostExportRegistrations();
    public static native void hostRevokeAll();
    public static native String hostStats(long nowMs);
}
