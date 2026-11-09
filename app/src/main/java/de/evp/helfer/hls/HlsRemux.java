package de.evp.helfer.hls;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Remux verifizierter HLS-Ressourcen zu einer MP4-Datei ohne Neukodierung, und die Prüfung der Ausgabe. Die
 * Android-Implementierung (Paket {@code de.evp.helfer.hlsmux}: Extractor + Mp4Muxer der Medienbibliothek) hat keinen
 * Netzwerkzugriff; sie liest nur die übergebenen Dateien.
 */
public interface HlsRemux {
    /** Was gelesen wird: je Spur Init (falls vorhanden) und Segmente in Abrufreihenfolge. audio leer: Ton in der Variante. */
    final class Input {
        public final List<File> video, audio;
        public final boolean fmp4;
        public final long maxSamples;
        public Input(List<File> video, List<File> audio, boolean fmp4, long maxSamples) {
            this.video = video; this.audio = audio; this.fmp4 = fmp4; this.maxSamples = maxSamples;
        }
    }

    /** Messwerte des Remux (nur Zahlen, keine Adressen). */
    final class Result {
        public final long videoSamples, audioSamples, videoSpanUs, audioSpanUs;
        public Result(long videoSamples, long audioSamples, long videoSpanUs, long audioSpanUs) {
            this.videoSamples = videoSamples; this.audioSamples = audioSamples; this.videoSpanUs = videoSpanUs; this.audioSpanUs = audioSpanUs;
        }
    }

    /**
     * Schreibt {@code out} vollständig oder gar nicht (bei jedem Fehler/Abbruch ist {@code out} gelöscht).
     * @throws HlsException mit neutralem Grund ("cancelled", "memory", "samples-limit", "no-audio", "video-codec" …)
     */
    Result remux(Input in, File out, BooleanSupplier cancelled) throws IOException, HlsException;

    /**
     * Liest {@code out} erneut: genau eine H.264- und eine Tonspur, Sample-Anzahlen wie {@code expected}, Zeitstempel
     * je Spur nicht fallend, Dauer je Spur ≈ Plan (± {@code toleranceSeconds}). Sonst {@link HlsException} "verify-…".
     */
    void verify(File out, Result expected, double videoSeconds, double audioSeconds, double toleranceSeconds, BooleanSupplier cancelled)
        throws IOException, HlsException;
}
