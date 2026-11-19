package de.evp.helfer.hls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Auftragsjournal eines HLS-Downloads (app-privat, ein Ordner je Auftrag, versioniert). Enthält nie eine Adresse:
 * nur Kennung, Zustand, persistenten Abbruchwunsch, gesalzenen Form-Fingerabdruck und je verifizierter Ressource
 * Spur, Index, Länge, SHA-256 und (falls vorhanden) den starken ETag.
 *
 * <ul>
 *   <li>Kopf ({@code journal}): atomar ersetzt – Temp-Datei, fsync, Umbenennen (ATOMIC_MOVE), fsync des Ordners. Ein
 *       Leser sieht den alten oder den neuen Stand, nie einen halben.</li>
 *   <li>Ressourcen ({@code segments.log}): nur angehängt, je Zeile CRC-32 und fsync. Eine Ressource gilt erst als
 *       verifiziert, wenn ihre Zeile dauerhaft geschrieben ist. Nur die letzte Zeile darf nach einem Absturz
 *       unvollständig sein (wird verworfen); jede andere Abweichung → {@link IOException}.</li>
 * </ul>
 * Jede Abweichung beim Lesen (Version, Feld, Wertebereich, Duplikat, Prüfsumme) → {@link IOException}; der Aufrufer
 * verwirft dann den Auftrag vollständig.
 */
public final class HlsJournal {
    public static final String VERSION = "hls-job-v1";
    public static final int MAX_BYTES = 64 << 10;          // Kopf
    public static final long MAX_LOG_BYTES = 16L << 20;    // ≤ 2 Einträge je Ressource × 20 002 × < 300 B

    public enum State { RUNNING, INTERRUPTED, PUBLISHING, DONE, FAILED, CANCELLED }

    /** Eine verifizierte Ressource (Init oder Segment) einer Spur. */
    public static final class Res {
        public final char track;    // 'v' Video-Variante, 'a' getrennte Tonspur
        public final int index;     // Position in der Abrufreihenfolge der Spur (Init = 0, falls vorhanden)
        public final long length;
        public final String sha256;
        public final String etag;   // starker ETag oder null
        public Res(char track, int index, long length, String sha256, String etag) {
            this.track = track; this.index = index; this.length = length; this.sha256 = sha256; this.etag = etag;
        }
        public String key() { return track + Integer.toString(index); }
    }

    public String id;
    public State state = State.RUNNING;
    public boolean cancelRequested;
    public String reason = "";
    public String salt;          // 32 Hex, zufällig je Auftrag
    public String shape;         // 64 Hex: gesalzener Form-Fingerabdruck (ohne Adressen/Query)
    public int videoCount, audioCount;
    public String title = "Video";
    public String ref;           // Verweis der Ablage beim Veröffentlichen, nie die URL
    public long total = -1;      // Größe der veröffentlichten Datei
    public final java.util.LinkedHashMap<String, Res> resources = new java.util.LinkedHashMap<>();

    static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern REASON = Pattern.compile("[a-z0-9_-]{0,40}");

    public byte[] serialize() {
        StringBuilder b = new StringBuilder(VERSION).append('\n');
        b.append("id ").append(id).append('\n');
        b.append("state ").append(state.name()).append('\n');
        b.append("cancel ").append(cancelRequested ? 1 : 0).append('\n');
        b.append("reason ").append(reason.isEmpty() ? "-" : reason).append('\n');
        b.append("salt ").append(salt).append('\n');
        b.append("shape ").append(shape).append('\n');
        b.append("counts ").append(videoCount).append(' ').append(audioCount).append('\n');
        b.append("title ").append(b64(title)).append('\n');
        b.append("ref ").append(ref == null ? "-" : b64(ref)).append('\n');
        b.append("total ").append(total).append('\n');
        b.append("end\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static HlsJournal parse(byte[] raw) throws IOException {
        try {
            String[] l = new String(raw, StandardCharsets.UTF_8).split("\n", -1);
            int i = 0;
            if (l.length < 13 || !VERSION.equals(l[i++])) throw new IOException("journal version");
            HlsJournal j = new HlsJournal();
            j.id = field(l[i++], "id");
            if (!HEX32.matcher(j.id).matches()) throw new IOException("journal id");
            j.state = State.valueOf(field(l[i++], "state"));
            String c = field(l[i++], "cancel");
            if (!c.equals("0") && !c.equals("1")) throw new IOException("journal cancel");
            j.cancelRequested = c.equals("1");
            String r = field(l[i++], "reason");
            j.reason = r.equals("-") ? "" : r;
            if (!REASON.matcher(j.reason).matches()) throw new IOException("journal reason");
            j.salt = field(l[i++], "salt");
            if (!HEX32.matcher(j.salt).matches()) throw new IOException("journal salt");
            j.shape = field(l[i++], "shape");
            if (!HEX64.matcher(j.shape).matches()) throw new IOException("journal shape");
            String[] counts = field(l[i++], "counts").split(" ", -1);
            if (counts.length != 2) throw new IOException("journal counts");
            j.videoCount = bounded(counts[0], 1, HlsPlan.MAX_PLAN_SEGMENTS + 1);
            j.audioCount = bounded(counts[1], 0, HlsPlan.MAX_PLAN_SEGMENTS + 1);
            if (j.videoCount + j.audioCount > HlsPlan.MAX_PLAN_SEGMENTS + 2) throw new IOException("journal counts");
            j.title = unb64(field(l[i++], "title"));
            String ref = field(l[i++], "ref");
            j.ref = ref.equals("-") ? null : unb64(ref);
            j.total = Long.parseLong(field(l[i++], "total"));
            if (j.total < -1) throw new IOException("journal total");
            if (i + 2 != l.length || !l[i].equals("end") || !l[i + 1].isEmpty()) throw new IOException("journal trailing");
            return j;
        } catch (IllegalArgumentException e) {   // auch NumberFormatException und State.valueOf
            throw new IOException("journal value", e);
        }
    }

    /** Eine Zeile des Ressourcen-Logs (ohne Prüfsumme). */
    static String record(Res r) {
        return "r " + r.track + ' ' + r.index + ' ' + r.length + ' ' + r.sha256 + ' ' + (r.etag == null ? "-" : b64(r.etag));
    }

    static String dropRecord(String key) { return "x " + key.charAt(0) + ' ' + key.substring(1); }

    static String crc(String line) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(line.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", c.getValue());
    }

    /** Wendet das Ressourcen-Log an; nur eine unvollständige letzte Zeile (ohne Zeilenende) wird verworfen. */
    /** @return Länge des gültigen Teils in Byte (ohne abgerissene letzte Zeile) */
    long applyLog(byte[] raw) throws IOException {
        int start = 0;
        while (start < raw.length) {
            int nl = start;
            while (nl < raw.length && raw[nl] != '\n') nl++;
            if (nl >= raw.length) return start;       // abgerissene letzte Zeile: nie bestätigt, also nie verwendet
            String line = new String(raw, start, nl - start, StandardCharsets.UTF_8);
            start = nl + 1;
            int sp = line.lastIndexOf(' ');
            if (sp < 0 || !crc(line.substring(0, sp)).equals(line.substring(sp + 1))) throw new IOException("log checksum");
            String[] f = line.substring(0, sp).split(" ", -1);
            try {
                if (f[0].equals("r") && f.length == 6) {
                    Res r = parseRes(f);
                    if (resources.put(r.key(), r) != null) throw new IOException("log duplicate");
                } else if (f[0].equals("x") && f.length == 3 && f[1].length() == 1) {
                    if (resources.remove(f[1] + Integer.parseInt(f[2])) == null) throw new IOException("log drop");
                } else {
                    throw new IOException("log record");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("log value", e);
            }
        }
        return start;
    }

    private Res parseRes(String[] f) throws IOException {
        if (f[1].length() != 1) throw new IOException("log track");
        char track = f[1].charAt(0);
        int max = track == 'v' ? videoCount : track == 'a' ? audioCount : -1;
        if (max <= 0) throw new IOException("log track");
        int index = bounded(f[2], 0, max - 1);
        long len = Long.parseLong(f[3]);
        if (len <= 0 || len > HlsPlan.MAX_SEGMENT_BYTES) throw new IOException("log length");
        if (!HEX64.matcher(f[4]).matches()) throw new IOException("log sha");
        String etag = f[5].equals("-") ? null : unb64(f[5]);
        if (etag != null && !de.evp.helfer.net.SafeHttpClient.isStrongEtag(etag)) throw new IOException("log etag");
        return new Res(track, index, len, f[4], etag);
    }

    private static String field(String line, String name) throws IOException {
        if (!line.startsWith(name + " ")) throw new IOException("journal field " + name);
        return line.substring(name.length() + 1);
    }

    private static int bounded(String s, int min, int max) throws IOException {
        int v = Integer.parseInt(s);
        if (v < min || v > max) throw new IOException("journal range");
        return v;
    }

    private static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static String unb64(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ Datei

    /** Prüfpunkte für Absturztests (JVM): wirft an der genannten Grenze; produktiv ohne Wirkung. */
    public interface Hooks {
        Hooks NONE = point -> { };
        void at(String point) throws IOException;
    }

    /** Liest das Journal {@code dir/journal}; null, wenn keines existiert. */
    public static HlsJournal load(File dir) throws IOException {
        File f = new File(dir, "journal");
        if (!f.exists()) return null;
        long len = f.length();
        if (len <= 0 || len > MAX_BYTES) throw new IOException("journal size");
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new IOException("journal truncated");
                off += n;
            }
        }
        HlsJournal j = parse(b);
        File log = new File(dir, "segments.log");
        if (log.exists()) {
            if (log.length() > MAX_LOG_BYTES) throw new IOException("log size");
            long valid = j.applyLog(Files.readAllBytes(log.toPath()));
            if (valid < log.length()) {
                // Abgerissenen Rest abschneiden, damit spätere Einträge nicht an ihn angehängt werden
                try (FileChannel ch = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
                    ch.truncate(valid);
                    ch.force(true);
                }
            }
        }
        return j;
    }

    /** Hängt eine verifizierte Ressource an (fsync); erst danach gilt sie als vorhanden. */
    public void append(File dir, Res r, Hooks hooks) throws IOException {
        if (resources.containsKey(r.key())) throw new IOException("log duplicate");
        appendLine(dir, record(r), hooks);
        resources.put(r.key(), r);
    }

    /** Vermerkt, dass eine Ressource nicht mehr gilt (z. B. bei der Wiederaufnahme nicht bestätigt). */
    public void drop(File dir, String key, Hooks hooks) throws IOException {
        if (!resources.containsKey(key)) return;
        appendLine(dir, dropRecord(key), hooks);
        resources.remove(key);
    }

    /** Alle Ressourcen verwerfen (Log löschen). Die Segmentdateien entfernt der Aufrufer vorher. */
    public void clearResources(File dir) throws IOException {
        Files.deleteIfExists(new File(dir, "segments.log").toPath());
        syncDir(dir);
        resources.clear();
    }

    private static void appendLine(File dir, String line, Hooks hooks) throws IOException {
        File log = new File(dir, "segments.log");
        if (log.length() > MAX_LOG_BYTES) throw new IOException("log size");
        byte[] b = (line + ' ' + crc(line) + '\n').getBytes(StandardCharsets.UTF_8);
        boolean fresh = !log.exists();
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write(b);
            hooks.at("log-written");
            out.getFD().sync();
        }
        if (fresh) syncDir(dir);
        hooks.at("log-synced");
    }

    /** Atomar ersetzen: {@code journal.tmp} schreiben, fsync, umbenennen, Ordner fsyncen. */
    public void save(File dir, Hooks hooks) throws IOException {
        byte[] data = serialize();
        if (data.length > MAX_BYTES) throw new IOException("journal size");
        File tmp = new File(dir, "journal.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            hooks.at("journal-written");
            out.getFD().sync();
        }
        hooks.at("journal-synced");
        Files.move(tmp.toPath(), new File(dir, "journal").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        hooks.at("journal-renamed");
        syncDir(dir);
    }

    /** fsync eines Ordners (Linux/Android: Umbenennung dauerhaft); wo das nicht geht, bleibt es beim Datei-fsync. */
    public static void syncDir(File dir) {
        try (FileChannel ch = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | RuntimeException ignored) {
            // z. B. Plattformen ohne Verzeichnis-Handles; der Inhalt selbst ist bereits synchronisiert
        }
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "HlsJournal[%s %s cancel=%b res=%d]", id, state, cancelRequested, resources.size());
    }
}
