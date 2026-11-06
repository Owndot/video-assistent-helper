package de.evp.helfer.dl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * P4c-3 (P2, vom Eigentümer freigegeben): verschlüsselte Hauptadresse eines ausdrücklich gestarteten Downloads, damit
 * er nach einem Prozessende ohne die Webseite fortgesetzt werden kann – höchstens {@link #MAX_AGE_MS} (6 Stunden).
 *
 * <ul>
 *   <li>Nur die Hauptadresse (HLS-Master bzw. MP4-Datei) und eine Bindung (z. B. Hash der Liste, Option,
 *       Fingerabdruck) – keine Cookies, Header, Referer oder Sitzungsdaten (die gibt es im Helfer gar nicht).</li>
 *   <li>AES-256-GCM über {@link Sealer} (App: nicht exportierbarer Android-Keystore-Schlüssel). Auftragskennung, Art,
 *       Format-Version, Erstell- und Ablaufzeit sind als AAD gebunden: ein vertauschter, veränderter oder verlängerter
 *       Eintrag lässt sich nicht öffnen.</li>
 *   <li>Ablage app-privat (Aufrufer: {@code noBackupFilesDir}, kein Backup), eine Datei je Auftrag, atomar geschrieben.</li>
 *   <li>Jeder Fehler (beschädigt, abgelaufen, falscher Schlüssel, Entschlüsselung scheitert) → Eintrag gelöscht, kein
 *       Fortsetzen; nie ein Absturz, nie ein Klartext-Ausweichweg.</li>
 * </ul>
 */
public final class SourceVault {
    /** Verschlüsselung mit Authentisierung; seal liefert alles Nötige (z. B. IV ‖ Chiffrat ‖ Tag). */
    public interface Sealer {
        byte[] seal(byte[] aad, byte[] plain) throws GeneralSecurityException;
        byte[] open(byte[] aad, byte[] sealed) throws GeneralSecurityException;
    }

    public static final long MAX_AGE_MS = 6L * 3600_000L;
    static final String MAGIC = "EVPSV1";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern KIND = Pattern.compile("hls|mp4");
    private static final int MAX_RECORD = 16 << 10;

    public static final class Entry {
        public final String kind, url, binding;
        public final long createdMs, expiresMs;
        Entry(String kind, String url, String binding, long createdMs, long expiresMs) {
            this.kind = kind; this.url = url; this.binding = binding; this.createdMs = createdMs; this.expiresMs = expiresMs;
        }
    }

    private final File dir;
    private final Sealer sealer;
    private final LongSupplier clock;

    public SourceVault(File dir, Sealer sealer, LongSupplier clock) throws IOException {
        this.dir = dir; this.sealer = sealer; this.clock = clock;
        Files.createDirectories(dir.toPath());
    }

    private File file(String id) { return new File(dir, id + ".src"); }

    static byte[] aad(String id, String kind, long created, long expires) {
        return ("evp-source-vault/v1|" + id + "|" + kind + "|" + created + "|" + expires).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Merkt die Quelle eines laufenden Auftrags. Gibt es schon einen gültigen Eintrag mit derselben Adresse, bleibt dessen
     * Ablaufzeit (ein Fortsetzen verlängert die 6 Stunden nie). false = nicht gespeichert (dann eben ohne Fortsetzen).
     */
    public synchronized boolean put(String id, String kind, String url, String binding) {
        if (!ID.matcher(id).matches() || !KIND.matcher(kind).matches() || url == null || binding == null || binding.indexOf('\n') >= 0) return false;
        long now = clock.getAsLong();
        long created = now, expires = now + MAX_AGE_MS;
        Entry old = get(id);
        if (old != null && old.url.equals(url) && old.kind.equals(kind)) { created = old.createdMs; expires = old.expiresMs; }
        try {
            byte[] sealed = sealer.seal(aad(id, kind, created, expires), (binding + "\n" + url).getBytes(StandardCharsets.UTF_8));
            byte[] rec = (MAGIC + "\n" + kind + "\n" + created + "\n" + expires + "\n" + Base64.getEncoder().encodeToString(sealed) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
            File tmp = new File(dir, id + ".tmp");
            Files.write(tmp.toPath(), rec);
            Files.move(tmp.toPath(), file(id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            delete(id);
            return false;
        }
    }

    /** Gültiger Eintrag oder null; alles Ungültige wird dabei gelöscht. */
    public synchronized Entry get(String id) {
        if (!ID.matcher(id).matches()) return null;
        File f = file(id);
        if (!f.isFile()) return null;
        try {
            if (f.length() > MAX_RECORD) throw new IOException("size");
            String[] l = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).split("\n", -1);
            if (l.length != 6 || !MAGIC.equals(l[0]) || !KIND.matcher(l[1]).matches() || !l[5].isEmpty()) throw new IOException("format");
            long created = Long.parseLong(l[2]), expires = Long.parseLong(l[3]), now = clock.getAsLong();
            // abgelaufen, länger als erlaubt gültig oder aus der Zukunft (Uhr verstellt): nie verwenden
            if (expires - created != MAX_AGE_MS || now >= expires || created > now + 60_000L) throw new IOException("expired");
            byte[] plain = sealer.open(aad(id, l[1], created, expires), Base64.getDecoder().decode(l[4]));
            String s = new String(plain, StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            if (nl < 0 || nl == s.length() - 1) throw new IOException("content");
            return new Entry(l[1], s.substring(nl + 1), s.substring(0, nl), created, expires);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            f.delete();
            return null;
        }
    }

    public synchronized void delete(String id) {
        if (!ID.matcher(id).matches()) return;
        file(id).delete();
        new File(dir, id + ".tmp").delete();
    }

    /** Beim Dienststart: Abgelaufenes, Beschädigtes und Fremdes entfernen; liefert die Zahl gültiger Einträge. */
    public synchronized int sweep() {
        File[] fs = dir.listFiles();
        int ok = 0;
        if (fs == null) return 0;
        for (File f : fs) {
            String n = f.getName();
            if (n.length() == 36 && n.endsWith(".src") && ID.matcher(n.substring(0, 32)).matches()) { if (get(n.substring(0, 32)) != null) ok++; }
            else f.delete();
        }
        return ok;
    }

    /** Ordner (nur zum Aufräumen fremder Einträge durch den Eigentümer der Aufträge). */
    public File dirForCleanup() { return dir; }

    /** Schlüssel ungültig (z. B. nach Gerätewechsel): alles verwerfen. */
    public synchronized void clear() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
