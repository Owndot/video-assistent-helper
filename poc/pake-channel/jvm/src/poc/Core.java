package poc;

/** JNI view of the Rust core. All failures throw SecurityException. */
final class Core {
    static { System.load(System.getProperty("poc.lib")); }
    private Core() {}

    static native byte[] identityGenerate();
    /** Returns the helper public key as hex. nowMs is the monotonic clock of the caller. */
    static native String hostInit(byte[] identity, byte[] registrations, long nowMs, boolean sameBoot);
    /** code == null: generate a fresh code. Returns the code for display. */
    static native String windowOpen(String code, long nowMs, long ttlMs);
    static native byte[] windowOnM0(byte[] m0, long nowMs);
    /** registration_id(16) || M3; registration added as pending first. */
    static native byte[] windowOnM2(byte[] m2, long nowMs);
    static native byte[] hostHandshake(byte[] hs1, long nowMs);
    /** session_id(16) || kind(1) || body */
    static native byte[] hostOpen(byte[] record, long nowMs);
    static native byte[] hostSealControl(byte[] session, byte[] json, long nowMs);
    static native byte[] hostSealMedia(byte[] session, int transfer, long offset, byte[] data, long nowMs);
    static native byte[] hostSealEnd(byte[] session, int transfer, long total, long nowMs);
    static native byte[] hostExportRegistrations();
    static native void hostRevokeAll();
    static native String hostStats(long nowMs);
}
