package de.evp.pakepoc;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

import poc.Core;

/**
 * Per-installation X25519 test identity, stored encrypted under an AES-256-GCM
 * key that lives in the Android Keystore.
 *
 * What this does NOT provide: the X25519 private key itself is not a Keystore
 * key and is not hardware-isolated or non-exportable. snow needs the raw 32
 * bytes, so they are decrypted into process memory when the server starts and
 * stay inside the Rust core (zeroized on drop) for as long as the host runs.
 * The Java copy is wiped right after it is handed to the core.
 *
 * File: {@code identity.v1} = "KPI1" | iv(12) | ciphertext(64 + 16 tag), written
 * atomically. GCM AAD binds the format version and the package name.
 *
 * States (review F2); nothing is regenerated or deleted implicitly:
 * <pre>
 *  file    | Keystore key | result
 *  missing | missing      | CREATED (new identity) – only if the caller allows a first start
 *  missing | missing      | ERROR_INCONSISTENT     – if it does not (registrations exist)
 *  present | present      | OK (GCM authenticated) or ERROR_CORRUPT
 *  present | missing      | ERROR_KEY_MISSING
 *  missing | present      | ERROR_INCONSISTENT
 *  bad format / GCM fail  | ERROR_CORRUPT
 * </pre>
 * A new identity otherwise only comes from the explicit {@link #reset()}.
 */
public final class IdentityStore {
    public enum State { OK, CREATED, ERROR_KEY_MISSING, ERROR_INCONSISTENT, ERROR_CORRUPT }

    public static final class Result {
        public final State state;
        public final byte[] identity;   // private(32) || public(32); caller must wipe
        public final String detail;
        Result(State state, byte[] identity, String detail) { this.state = state; this.identity = identity; this.detail = detail; }
    }

    private static final byte[] MAGIC = "KPI1".getBytes(StandardCharsets.US_ASCII);
    private final File file;
    private final String alias;
    private final byte[] aad;

    public IdentityStore(File dir, String alias, String packageName) {
        this.file = new File(dir, "identity.v1");
        this.alias = alias;
        this.aad = ("kadri-pakepoc-identity/1|" + packageName).getBytes(StandardCharsets.UTF_8);
    }

    private static KeyStore keystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    private SecretKey existingKey() throws Exception {
        KeyStore ks = keystore();
        return ks.containsAlias(alias) ? (SecretKey) ks.getKey(alias, null) : null;
    }

    private SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build());
        return kg.generateKey();
    }

    /**
     * Loads the identity. {@code allowFirstStart}: whether a missing file and
     * missing key may be treated as a first installation (the caller says no
     * when registrations are stored, because they belong to a lost identity).
     */
    public synchronized Result load(boolean allowFirstStart) {
        try {
            SecretKey key = existingKey();
            boolean present = file.exists() || new File(file.getPath() + ".bak").exists();
            if (!present && key == null) {
                if (!allowFirstStart) return new Result(State.ERROR_INCONSISTENT, null, "registrations present but no identity");
                return create();
            }
            if (!present) return new Result(State.ERROR_INCONSISTENT, null, "Keystore key present but identity file missing");
            if (key == null) return new Result(State.ERROR_KEY_MISSING, null, "identity file present but Keystore key missing");
            byte[] blob = new AtomicFile(file).readFully();
            if (blob.length != 4 + 12 + 64 + 16 || !Arrays.equals(Arrays.copyOf(blob, 4), MAGIC)) {
                return new Result(State.ERROR_CORRUPT, null, "unexpected identity file format");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 4, 12));
            c.updateAAD(aad);
            byte[] id;
            try { id = c.doFinal(blob, 16, blob.length - 16); }
            catch (Exception e) { return new Result(State.ERROR_CORRUPT, null, "identity authentication failed"); }
            return new Result(State.OK, id, null);
        } catch (Exception e) {
            return new Result(State.ERROR_CORRUPT, null, e.getClass().getSimpleName());
        }
    }

    private Result create() throws Exception {
        SecretKey key = createKey();
        byte[] id = Core.identityGenerate();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key); // Keystore chooses a random IV
        c.updateAAD(aad);
        byte[] ct = c.doFinal(id);
        byte[] iv = c.getIV();
        if (iv.length != 12) throw new IllegalStateException("unexpected IV length");
        byte[] blob = new byte[4 + 12 + ct.length];
        System.arraycopy(MAGIC, 0, blob, 0, 4);
        System.arraycopy(iv, 0, blob, 4, 12);
        System.arraycopy(ct, 0, blob, 16, ct.length);
        writeAtomically(file, blob);
        return new Result(State.CREATED, id, null);
    }

    /** Explicit user action only: deletes identity file and Keystore key. */
    public synchronized void reset() throws Exception {
        new AtomicFile(file).delete();
        KeyStore ks = keystore();
        if (ks.containsAlias(alias)) ks.deleteEntry(alias);
    }

    /** For the report: where the wrapping key lives (not the X25519 key). */
    public String wrappingKeyInfo() {
        try {
            SecretKey key = existingKey();
            if (key == null) return "no wrapping key";
            KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore").getKeySpec(key, KeyInfo.class);
            String level = android.os.Build.VERSION.SDK_INT >= 31 ? String.valueOf(info.getSecurityLevel()) : (info.isInsideSecureHardware() ? "secure-hardware" : "software");
            return "AES-" + info.getKeySize() + "-GCM wrapping key, security level " + level;
        } catch (Exception e) {
            return "key info unavailable: " + e.getClass().getSimpleName();
        }
    }

    static void writeAtomically(File f, byte[] data) throws Exception {
        AtomicFile af = new AtomicFile(f);
        FileOutputStream out = af.startWrite();
        try {
            out.write(data);
            af.finishWrite(out);
        } catch (Exception e) {
            af.failWrite(out);
            throw e;
        }
    }
}
