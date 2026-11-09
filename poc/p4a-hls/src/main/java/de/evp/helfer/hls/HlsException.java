package de.evp.helfer.hls;

/** P4a: kontrollierte Ablehnung mit neutralem Grundcode (keine Adresse, kein Playlistinhalt in der Meldung). */
public final class HlsException extends Exception {
    public final String reason;

    public HlsException(String reason) {
        super(reason);
        this.reason = reason;
    }
}
