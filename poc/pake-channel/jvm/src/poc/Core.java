package poc;

/** JNI view of the Rust core. All failures throw SecurityException. */
final class Core {
    static { System.load(System.getProperty("poc.lib")); }
    private Core() {}

    static native byte[] identityGenerate();
    static native String generateCode();
    static native long windowOpen(String code, byte[] helperPublic, long nowMs, long ttlMs);
    static native byte[] windowOnM0(long window, byte[] m0, long nowMs);
    /** registration_id(16) || kadri_public(32) || M3 */
    static native byte[] windowOnM2(long window, byte[] m2, long nowMs);
    static native void windowFree(long window);
    static native byte[] hs1RegistrationId(byte[] hs1);
    /** pinnedKadriPublic may be empty for unknown/revoked registrations: fails closed. */
    static native long respond(byte[] identity, byte[] hs1, byte[] pinnedKadriPublic);
    static native byte[] channelHs2(long channel);
    static native byte[] channelSessionId(long channel);
    static native byte[] channelOpenControl(long channel, byte[] record);
    static native byte[] channelSealControl(long channel, byte[] json);
    static native byte[] channelSealMedia(long channel, int transfer, long offset, byte[] data);
    static native byte[] channelSealEnd(long channel, int transfer, long total);
    static native void channelFree(long channel);
}
