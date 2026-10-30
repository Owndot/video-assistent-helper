package de.evp.helfer.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

/** Eine Anfrage wurde von der Netzwerkrichtlinie abgelehnt (nie stillschweigend, nie mit Ausweichen). */
public final class NetBlockedException extends IOException {
    public enum Reason {
        BAD_URL, SCHEME, PORT, CREDENTIALS_IN_URL, HOST_NAME, ADDRESS, NO_ADDRESS, PEER,
        REDIRECT_WITHOUT_LOCATION, REDIRECT_TARGET, DOWNGRADE, TOO_MANY_REDIRECTS,
        CREDENTIAL_HEADER, AUTOMATIC_RETRY, HEADERS_TOO_LARGE, BODY_TOO_LARGE, BAD_ARGUMENT
    }

    public final Reason reason;

    NetBlockedException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    NetBlockedException(Reason reason, String detail, Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = reason;
    }

    /** Gemeinsame Markierung, damit Ablehnungen aus DNS und Socket-Aufbau durch OkHttp hindurch erkennbar bleiben. */
    interface Blocked { Reason reason(); }

    /** Ablehnung in der DNS-Auflösung (OkHttp erwartet dort UnknownHostException). */
    static final class Host extends UnknownHostException implements Blocked {
        private final Reason reason;
        Host(Reason reason, String detail) { super(reason + ": " + detail); this.reason = reason; }
        @Override public Reason reason() { return reason; }
    }

    /** Ablehnung unmittelbar vor dem Verbindungsaufbau (TCP-SYN wird nie gesendet). */
    static final class Connect extends ConnectException implements Blocked {
        private final Reason reason;
        Connect(Reason reason, String detail) { super(reason + ": " + detail); this.reason = reason; }
        @Override public Reason reason() { return reason; }
    }

    /** Ablehnung in einem Interceptor (OkHttp reicht IOException unverändert durch). */
    static final class Intercepted extends IOException implements Blocked {
        private final Reason reason;
        Intercepted(Reason reason, String detail) { super(reason + ": " + detail); this.reason = reason; }
        @Override public Reason reason() { return reason; }
    }

    /** Findet eine Ablehnung in der Ursachenkette; null, wenn es kein Richtlinienfehler ist. */
    static NetBlockedException find(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NetBlockedException) return (NetBlockedException) c;
            if (c instanceof Blocked) return new NetBlockedException(((Blocked) c).reason(), c.getMessage(), t);
            for (Throwable s : c.getSuppressed()) {
                NetBlockedException b = find(s);
                if (b != null) return b;
            }
        }
        return null;
    }
}
