package de.evp.helfer;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.evp.helfer.dl.SourceVault;

/**
 * P4c-3 (P2): AES-256-GCM mit einem nicht exportierbaren Schlüssel im Android-Keystore (eigener Alias, nur für die
 * verschlüsselte Downloadquelle). IV vom Keystore (zufällig je Verschlüsselung, randomizedEncryptionRequired), Tag 128 Bit.
 * Ausgabe: IV(12) ‖ Chiffrat ‖ Tag. Kein Software-Ausweichweg: ohne Keystore gibt es keine gespeicherte Quelle.
 */
final class KeystoreSealer implements SourceVault.Sealer {
    static final String ALIAS = "helfer-source-vault-v1";

    private SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
            return g.generateKey();
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("keystore", e);
        }
    }

    /** Schlüssel unbrauchbar (z. B. nach Wiederherstellung auf anderem Gerät): löschen; die Einträge sind dann wertlos. */
    static void dropKey() {
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
    }

    @Override public byte[] seal(byte[] aad, byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        if (iv == null || iv.length != 12) throw new GeneralSecurityException("iv");
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain), out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    @Override public byte[] open(byte[] aad, byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < 12 + 16) throw new GeneralSecurityException("short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, sealed, 0, 12));
        c.updateAAD(aad);
        return c.doFinal(sealed, 12, sealed.length - 12);
    }
}
