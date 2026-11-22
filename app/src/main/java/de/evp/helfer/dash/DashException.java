package de.evp.helfer.dash;

/** S3a: kontrollierte Ablehnung mit neutralem, festem Grundcode (keine Adresse, kein Manifestinhalt in der Meldung). */
public final class DashException extends Exception {
    public final String reason;

    public DashException(String reason) {
        super(reason);
        this.reason = reason;
    }
}
