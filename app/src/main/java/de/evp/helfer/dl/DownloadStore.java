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

    /** Neues, noch nicht sichtbares Ziel. */
    Target create(String displayName, String mime) throws IOException;

    /** Unveröffentlichtes Ziel: entweder {@link #commit()} oder {@link #abort()} (räumt vollständig auf). */
    interface Target {
        OutputStream out() throws IOException;
        /** Schließt und veröffentlicht die Datei; liefert den Verweis der Ablage (Android: Uri). Danach kein abort mehr. */
        Object commit() throws IOException;
        /** Schließt den Stream und entfernt alles Unfertige; darf mehrfach und nach Fehlern aufgerufen werden. */
        void abort();
    }

    /** Auftragsjournal (null, wenn noch keines existiert). */
    byte[] loadJournal() throws IOException;

    /** Schreibt das Journal atomar; wirft, wenn das nicht gelang. */
    void saveJournal(byte[] data) throws IOException;
}
