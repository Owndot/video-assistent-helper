package de.evp.helfer.dl;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Ablage für direkte Downloads (Android: MediaStore-Pending-Eintrag bzw. Teil-Datei im Download-Ordner; in Tests ein
 * Ordner). Ohne Netzwerk: die Ablage schreibt nur, was die Engine ihr gibt.
 */
public interface DownloadStore {
    /** Freier Platz am Zielort in Bytes. */
    long freeBytes() throws IOException;

    /**
     * false: die Ablage ist nicht beschreibbar (Android 8–9: Speicherberechtigung fehlt). Die Engine prüft das vor jedem
     * Netzwerkzugriff und beendet den Auftrag dann mit "storage_permission".
     */
    boolean writable();

    /**
     * Nach einem Neustart: ist das Ziel mit diesem Verweis ({@link Target#ref()}) vollständig veröffentlicht? Nur
     * true, wenn die Datei sichtbar ist und genau {@code total} Byte hat.
     */
    boolean isPublished(String ref, long total) throws IOException;

    /** Neues, noch nicht sichtbares Ziel. */
    Target create(String displayName, String mime) throws IOException;

    /** Unveröffentlichtes Ziel: entweder {@link #commit()} oder {@link #abort()} (räumt vollständig auf). */
    interface Target {
        OutputStream out() throws IOException;
        /** Stabiler Verweis für das Journal (Android: Uri bzw. Dateipfad), vor und nach {@link #commit()}; nie die URL. */
        String ref();
        /**
         * Veröffentlicht die Datei; liefert den Verweis der Ablage (Android: Uri). Danach kein abort mehr. Die Engine hat
         * {@link #out()} vorher geschlossen: was beim Schließen geschehen muss (z. B. fsync), gehört in dessen close().
         */
        Object commit() throws IOException;
        /** Schließt den Stream und entfernt alles Unfertige; darf mehrfach und nach Fehlern aufgerufen werden. */
        void abort();
    }

    /** Auftragsjournal (null, wenn noch keines existiert). */
    byte[] loadJournal() throws IOException;

    /** Schreibt das Journal atomar; wirft, wenn das nicht gelang. */
    void saveJournal(byte[] data) throws IOException;
}
