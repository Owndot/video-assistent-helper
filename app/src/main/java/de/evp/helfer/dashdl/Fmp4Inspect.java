package de.evp.helfer.dashdl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.evp.helfer.dash.DashException;

/**
 * S3b: Prüfung heruntergeladener fMP4-Ressourcen (ISO/IEC 14496-12) vor dem Zusammenbau – ohne Netz, ohne Decoder,
 * nur Box-Struktur. Fail-closed: jede unbekannte Box an einer geprüften Stelle, jede Abweichung der Größen und jeder
 * Hinweis auf Verschlüsselung führt zur Ablehnung mit festem Grund.
 *
 * <ul>
 *   <li>Init: oben nur ftyp, moov, free, skip; in moov genau ein trak und mvex (fragmentiert); hdlr passt zur Spur
 *       (vide/soun); stsd mit genau einem Eintrag avc1/avc3 (Video) bzw. mp4a (Ton). Der Codec muss zum MPD passen:
 *       avcC (Profil, Kompatibilität, Level) = die sechs Hexziffern aus {@code avc1.PPCCLL}. Ton nur AAC-LC: MPD
 *       genau {@code mp4a.40.2}, esds mit objectTypeIndication 0x40 und einer AudioSpecificConfig mit
 *       audioObjectType 2, Kanalkonfiguration 1…7 und ohne jede SBR-/PS-Signalisierung (weder explizit als
 *       audioObjectType 5/29 noch rückwärtskompatibel über die Synchronisationserweiterung 0x2b7). HE-AAC und
 *       HE-AACv2 ({@code mp4a.40.5}/{@code .29}) sind damit fail-closed ausgeschlossen: implizit signalisiertes SBR
 *       steckt nur im Bitstrom und lässt sich ohne Decoder nicht gegen das MPD prüfen.</li>
 *   <li>Zeit (für die Vollständigkeitsprüfung des Koordinators): Zeitmaßstab aus mdhd, Standard-Probendauer aus
 *       trex; je Medienstück die Summe der Probendauern aus trun (bzw. tfhd/trex-Standard), {@link #duration}.</li>
 *   <li>Verschlüsselung ("encrypted"): encv/enca, sinf, frma, schm, schi, tenc, pssh, senc, saiz, saio, sgpd/sbgp mit
 *       Gruppe "seig", PIFF-uuid für Probenverschlüsselung – an jeder Stelle, an der sie vorkommen können.</li>
 *   <li>Segment: oben nur styp, sidx, ssix, prft, emsg, moof, mdat, free, skip; mindestens ein moof und ein mdat; in moof
 *       nur mfhd, traf (pssh → encrypted); in traf nur tfhd, tfdt, trun, sdtp, sbgp, sgpd, subs (Verschlüsselungs-
 *       boxen → encrypted).</li>
 * </ul>
 * Die Samples selbst prüft danach der Remuxer (verschlüsselte Samples → "encrypted-sample").
 */
public final class Fmp4Inspect {
    /** Init-Ressourcen sind klein; größer → nicht geprüft und abgelehnt. */
    public static final long MAX_INIT_BYTES = 1L << 20;
    static final int MAX_BOXES = 4096;
    static final int MAX_DEPTH = 12;

    private Fmp4Inspect() {}

    private static final Set<String> ENCRYPTION = new HashSet<>(Arrays.asList("encv", "enca", "sinf", "frma", "schm", "schi", "tenc", "pssh",
        "senc", "saiz", "saio"));
    /** PIFF: SampleEncryptionBox / TrackEncryptionBox / ProtectionSystemSpecificHeaderBox als uuid. */
    private static final Set<String> PIFF = new HashSet<>(Arrays.asList("a2394f525a9b4f14a2446c427c648df4", "8974dbce7be74c5184f97148f9882554",
        "d08a4f1810f34a82b6c832d8aba183d3"));

    static final class Box {
        final String type;
        final long start, header, size;    // absolute Position, Kopflänge, Gesamtlänge
        Box(String type, long start, long header, long size) { this.type = type; this.start = start; this.header = header; this.size = size; }
        long body() { return start + header; }
        long end() { return start + size; }
    }

    /** Liest die Kinder im Bereich [from, to) – Größen müssen den Bereich exakt füllen. */
    static List<Box> boxes(RandomAccessFile f, long from, long to, int[] budget) throws IOException, DashException {
        List<Box> out = new ArrayList<>();
        long p = from;
        while (p < to) {
            if (--budget[0] < 0) throw new DashException("mp4_boxes");
            if (to - p < 8) throw new DashException("mp4_structure");
            f.seek(p);
            long size = f.readInt() & 0xffffffffL;
            byte[] t = new byte[4];
            f.readFully(t);
            String type = fourcc(t);
            long header = 8;
            if (size == 1) {
                if (to - p < 16) throw new DashException("mp4_structure");
                size = f.readLong();
                header = 16;
            } else if (size == 0) {
                size = to - p;   // bis zum Ende des umgebenden Bereichs
            }
            if (type.equals("uuid")) {
                if (size < header + 16) throw new DashException("mp4_structure");
                byte[] u = new byte[16];
                f.readFully(u);
                StringBuilder sb = new StringBuilder();
                for (byte b : u) sb.append(String.format("%02x", b & 0xff));
                if (PIFF.contains(sb.toString())) throw new DashException("encrypted");
                type = "uuid";
                header += 16;
            }
            if (size < header || p + size > to) throw new DashException("mp4_structure");
            out.add(new Box(type, p, header, size));
            p += size;
        }
        return out;
    }

    private static String fourcc(byte[] t) throws DashException {
        for (byte b : t) if (b < 0x20 || b > 0x7e) throw new DashException("mp4_structure");
        return new String(t, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static Box one(List<Box> bs, String type) throws DashException {
        Box found = null;
        for (Box b : bs) if (b.type.equals(type)) { if (found != null) throw new DashException("mp4_structure"); found = b; }
        if (found == null) throw new DashException("mp4_structure");
        return found;
    }

    private static void only(List<Box> bs, Set<String> allowed, String unknown) throws DashException {
        for (Box b : bs) {
            if (ENCRYPTION.contains(b.type)) throw new DashException("encrypted");
            if (!allowed.contains(b.type)) throw new DashException(unknown);
        }
    }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    /** Zeitangaben einer geprüften Init-Ressource. */
    public static final class Info {
        public final long timescale;            // mdhd
        public final long defaultDuration;      // trex default_sample_duration (0: keine)
        Info(long timescale, long defaultDuration) { this.timescale = timescale; this.defaultDuration = defaultDuration; }
    }

    /**
     * Init-Ressource einer Spur prüfen. {@code kind}: "video" oder "audio"; {@code codecs}: @codecs aus dem MPD
     * (z. B. "avc1.4d400c", "mp4a.40.2").
     */
    public static Info init(File file, String kind, String codecs) throws IOException, DashException {
        if (file.length() <= 0 || file.length() > MAX_INIT_BYTES) throw new DashException("init_size");
        int[] budget = {MAX_BOXES};
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            List<Box> top = boxes(f, 0, f.length(), budget);
            only(top, set("ftyp", "moov", "free", "skip"), "init_box");
            if (!top.get(0).type.equals("ftyp")) throw new DashException("init_box");
            one(top, "ftyp");
            Box moov = one(top, "moov");
            List<Box> mk = boxes(f, moov.body(), moov.end(), budget);
            only(mk, set("mvhd", "trak", "mvex", "udta", "meta", "iods", "free", "skip"), "init_box");
            one(mk, "mvhd");
            Box mvex = one(mk, "mvex");
            Box trak = one(mk, "trak");
            deepScan(f, moov, budget, 0);
            List<Box> tk = boxes(f, trak.body(), trak.end(), budget);
            Box mdia = one(tk, "mdia");
            List<Box> md = boxes(f, mdia.body(), mdia.end(), budget);
            Box hdlr = one(md, "hdlr");
            f.seek(hdlr.body() + 8);   // version/flags (4), pre_defined (4)
            byte[] h = new byte[4];
            f.readFully(h);
            String handler = fourcc(h);
            if (!handler.equals(kind.equals("video") ? "vide" : "soun")) throw new DashException("init_track");
            Info info = new Info(timescale(f, one(md, "mdhd")), trexDuration(f, one(boxes(f, mvex.body(), mvex.end(), budget), "trex")));
            Box minf = one(md, "minf");
            Box stbl = one(boxes(f, minf.body(), minf.end(), budget), "stbl");
            Box stsd = one(boxes(f, stbl.body(), stbl.end(), budget), "stsd");
            f.seek(stsd.body() + 4);
            long n = f.readInt() & 0xffffffffL;
            if (n != 1) throw new DashException("init_track");
            List<Box> entries = boxes(f, stsd.body() + 8, stsd.end(), budget);
            if (entries.size() != 1) throw new DashException("init_track");
            Box e = entries.get(0);
            if (ENCRYPTION.contains(e.type)) throw new DashException("encrypted");   // encv/enca
            String c = codecs == null ? "" : codecs.trim();
            if (kind.equals("video")) {
                if (!e.type.equals("avc1") && !e.type.equals("avc3")) throw new DashException("codec_mismatch");
                if (!c.toLowerCase(Locale.ROOT).startsWith(e.type + ".") || c.length() != 11) throw new DashException("codec_mismatch");
                long kidsFrom = e.body() + 78;   // VisualSampleEntry
                if (kidsFrom > e.end()) throw new DashException("mp4_structure");
                List<Box> vk = boxes(f, kidsFrom, e.end(), budget);
                for (Box b : vk) if (ENCRYPTION.contains(b.type)) throw new DashException("encrypted");
                Box avcC = one(vk, "avcC");
                if (avcC.size - avcC.header < 7) throw new DashException("mp4_structure");
                f.seek(avcC.body());
                int version = f.readUnsignedByte(), prof = f.readUnsignedByte(), compat = f.readUnsignedByte(), level = f.readUnsignedByte();
                if (version != 1) throw new DashException("codec_mismatch");
                String got = String.format("%02x%02x%02x", prof, compat, level);
                if (!got.equals(c.substring(5).toLowerCase(Locale.ROOT))) throw new DashException("codec_mismatch");
            } else {
                if (!e.type.equals("mp4a")) throw new DashException("codec_mismatch");
                if (!c.equals("mp4a.40.2")) throw new DashException("codec_mismatch");   // nur AAC-LC
                long kidsFrom = e.body() + 28;   // AudioSampleEntry (Version 0)
                if (kidsFrom > e.end()) throw new DashException("mp4_structure");
                f.seek(e.body() + 8);
                int ver = f.readUnsignedShort();
                if (ver != 0) throw new DashException("codec_mismatch");
                List<Box> ak = boxes(f, kidsFrom, e.end(), budget);
                for (Box b : ak) if (ENCRYPTION.contains(b.type)) throw new DashException("encrypted");
                Box esds = one(ak, "esds");
                if (oti(f, esds) != 0x40) throw new DashException("codec_mismatch");
                aacLc(f, esds);
            }
            return info;
        }
    }

    private static long timescale(RandomAccessFile f, Box mdhd) throws IOException, DashException {
        if (mdhd.size - mdhd.header < 24) throw new DashException("mp4_structure");
        f.seek(mdhd.body());
        int version = f.readUnsignedByte();
        if (version > 1 || (version == 1 && mdhd.size - mdhd.header < 36)) throw new DashException("mp4_structure");
        f.seek(mdhd.body() + (version == 1 ? 20 : 12));
        long ts = f.readInt() & 0xffffffffL;
        if (ts == 0) throw new DashException("mp4_structure");
        return ts;
    }

    private static long trexDuration(RandomAccessFile f, Box trex) throws IOException, DashException {
        if (trex.size - trex.header < 24) throw new DashException("mp4_structure");
        f.seek(trex.body() + 12);   // version/flags, track_ID, default_sample_description_index
        return f.readInt() & 0xffffffffL;
    }

    /**
     * Nach objectTypeIndication: Rest des DecoderConfigDescriptor, dann DecoderSpecificInfo (Tag 0x05) mit der
     * AudioSpecificConfig (ISO/IEC 14496-3 1.6.2.1). Angenommen wird nur AAC-LC ohne SBR/PS-Signalisierung.
     */
    private static void aacLc(RandomAccessFile f, Box esds) throws IOException, DashException {
        long end = esds.end();
        if (f.getFilePointer() + 12 >= end) throw new DashException("codec_mismatch");
        f.skipBytes(12);                               // streamType/upStream (1), bufferSizeDB (3), maxBitrate, avgBitrate
        if (f.readUnsignedByte() != 0x05) throw new DashException("codec_mismatch");
        long n = 0;
        for (int i = 0; ; i++) {
            if (i == 4 || f.getFilePointer() >= end) throw new DashException("mp4_structure");
            int b = f.readUnsignedByte();
            n = (n << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) break;
        }
        if (n < 2 || n > 64 || f.getFilePointer() + n > end) throw new DashException("codec_mismatch");
        byte[] asc = new byte[(int) n];
        f.readFully(asc);
        Bits r = new Bits(asc);
        int aot = r.read(5);
        if (aot == 31) aot = 32 + r.read(6);
        if (aot != 2) throw new DashException("codec_mismatch");       // 5 = SBR (HE-AAC), 29 = PS (HE-AACv2), sonst kein LC
        if (r.read(4) == 15) r.read(24);                               // samplingFrequencyIndex / explizite Frequenz
        int ch = r.read(4);
        if (ch < 1 || ch > 7) throw new DashException("codec_mismatch"); // 0 = program_config_element: nicht geprüft
        r.read(1);                                                      // frameLengthFlag
        if (r.read(1) == 1) r.read(14);                                 // dependsOnCoreCoder → coreCoderDelay
        if (r.read(1) == 1) throw new DashException("codec_mismatch");  // extensionFlag: nicht für AOT 2 (nur ER-Typen)
        // Rückwärtskompatible Signalisierung (14496-3 1.6.5.2): syncExtensionType 0x2b7 + extensionAudioObjectType 5 +
        // sbrPresentFlag. Angenommen nur „ausdrücklich ohne SBR“ (Flag 0); jede andere Erweiterung → abgelehnt.
        if (r.left() >= 16 && r.peek(11) == 0x2b7) {
            r.read(11);
            if (r.read(5) != 5 || r.read(1) != 0) throw new DashException("codec_mismatch");
        }
        while (r.left() > 0) if (r.read(1) != 0) throw new DashException("codec_mismatch");   // nur Füllbits 0
    }

    static final class Bits {
        final byte[] b;
        int pos;
        Bits(byte[] b) { this.b = b; }
        int left() { return b.length * 8 - pos; }
        int peek(int n) throws DashException { int p0 = pos; int v = read(n); pos = p0; return v; }
        int read(int n) throws DashException {
            if (n > left()) throw new DashException("codec_mismatch");
            int v = 0;
            for (int i = 0; i < n; i++, pos++) v = (v << 1) | ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
            return v;
        }
    }

    /** Alle Boxen unter moov (bekannte Container) auf Verschlüsselungs- und Gruppierungsboxen durchsuchen. */
    private static final Set<String> CONTAINERS = set("moov", "trak", "mdia", "minf", "stbl", "mvex", "edts", "dinf", "udta", "moof", "traf");

    private static void deepScan(RandomAccessFile f, Box b, int[] budget, int depth) throws IOException, DashException {
        if (depth > MAX_DEPTH) throw new DashException("mp4_structure");
        for (Box k : boxes(f, b.body(), b.end(), budget)) {
            if (ENCRYPTION.contains(k.type)) throw new DashException("encrypted");
            if (k.type.equals("sgpd") || k.type.equals("sbgp")) seig(f, k);
            if (CONTAINERS.contains(k.type)) deepScan(f, k, budget, depth + 1);
        }
    }

    /** sgpd/sbgp mit grouping_type "seig" (CENC-Schlüsselwechsel) → verschlüsselt. */
    private static void seig(RandomAccessFile f, Box b) throws IOException, DashException {
        if (b.size - b.header < 8) throw new DashException("mp4_structure");
        f.seek(b.body() + 4);
        byte[] g = new byte[4];
        f.readFully(g);
        if (fourcc(g).equals("seig")) throw new DashException("encrypted");
    }

    /** objectTypeIndication aus esds (ES_Descriptor → DecoderConfigDescriptor). */
    private static int oti(RandomAccessFile f, Box esds) throws IOException, DashException {
        long p = esds.body() + 4, end = esds.end();   // version/flags
        f.seek(p);
        if (f.readUnsignedByte() != 0x03) throw new DashException("codec_mismatch");
        len(f, end);
        f.skipBytes(2);                                // ES_ID
        int flags = f.readUnsignedByte();
        if ((flags & 0x80) != 0) f.skipBytes(2);       // dependsOn_ES_ID
        if ((flags & 0x40) != 0) f.skipBytes(f.readUnsignedByte());   // URL
        if ((flags & 0x20) != 0) f.skipBytes(2);       // OCR_ES_ID
        if (f.getFilePointer() >= end || f.readUnsignedByte() != 0x04) throw new DashException("codec_mismatch");
        len(f, end);
        if (f.getFilePointer() >= end) throw new DashException("mp4_structure");
        return f.readUnsignedByte();
    }

    private static void len(RandomAccessFile f, long end) throws IOException, DashException {
        for (int i = 0; i < 4; i++) {
            if (f.getFilePointer() >= end) throw new DashException("mp4_structure");
            if ((f.readUnsignedByte() & 0x80) == 0) return;
        }
        throw new DashException("mp4_structure");
    }

    /**
     * Summe der Probendauern eines bereits mit {@link #segment} geprüften Medienstücks im Zeitmaßstab der Init
     * (ISO/IEC 14496-12 8.8.7/8.8.8: Dauer je Probe aus trun, sonst tfhd-Standard, sonst trex-Standard). Ohne Quelle für
     * die Dauer → "segment_timing".
     */
    public static long duration(File file, Info init) throws IOException, DashException {
        int[] budget = {MAX_BOXES};
        long ticks = 0;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            for (Box b : boxes(f, 0, f.length(), budget)) {
                if (!b.type.equals("moof")) continue;
                for (Box t : boxes(f, b.body(), b.end(), budget)) {
                    if (!t.type.equals("traf")) continue;
                    List<Box> tk = boxes(f, t.body(), t.end(), budget);
                    Box tfhd = one(tk, "tfhd");
                    if (tfhd.size - tfhd.header < 8) throw new DashException("mp4_structure");
                    f.seek(tfhd.body());
                    int flags = f.readInt() & 0xffffff;
                    long need = 8 + ((flags & 0x01) != 0 ? 8 : 0) + ((flags & 0x02) != 0 ? 4 : 0) + ((flags & 0x08) != 0 ? 4 : 0);
                    if (tfhd.size - tfhd.header < need) throw new DashException("mp4_structure");
                    f.skipBytes(4);                                              // track_ID
                    if ((flags & 0x01) != 0) f.skipBytes(8);                     // base_data_offset
                    if ((flags & 0x02) != 0) f.skipBytes(4);                     // sample_description_index
                    long def = (flags & 0x08) != 0 ? f.readInt() & 0xffffffffL : init.defaultDuration;
                    for (Box run : tk) if (run.type.equals("trun")) ticks = add(ticks, trun(f, run, def));
                }
            }
        }
        return ticks;
    }

    private static long trun(RandomAccessFile f, Box run, long def) throws IOException, DashException {
        if (run.size - run.header < 8) throw new DashException("mp4_structure");
        f.seek(run.body());
        int flags = f.readInt() & 0xffffff;
        long count = f.readInt() & 0xffffffffL;
        long p = run.body() + 8 + ((flags & 0x01) != 0 ? 4 : 0) + ((flags & 0x04) != 0 ? 4 : 0);
        int rec = 4 * (((flags & 0x100) != 0 ? 1 : 0) + ((flags & 0x200) != 0 ? 1 : 0) + ((flags & 0x400) != 0 ? 1 : 0) + ((flags & 0x800) != 0 ? 1 : 0));
        if (p > run.end() || (run.end() - p) < count * rec) throw new DashException("mp4_structure");
        if ((flags & 0x100) == 0) {
            if (count > 0 && def == 0) throw new DashException("segment_timing");
            try { return Math.multiplyExact(count, def); } catch (ArithmeticException e) { throw new DashException("mp4_structure"); }
        }
        long sum = 0;
        byte[] buf = new byte[rec * 4096];
        f.seek(p);
        for (long done = 0; done < count; ) {
            int k = (int) Math.min(4096, count - done);
            f.readFully(buf, 0, k * rec);
            for (int i = 0; i < k; i++) {
                int o = i * rec;
                sum = add(sum, ((buf[o] & 0xffL) << 24) | ((buf[o + 1] & 0xffL) << 16) | ((buf[o + 2] & 0xffL) << 8) | (buf[o + 3] & 0xffL));
            }
            done += k;
        }
        return sum;
    }

    private static long add(long a, long b) throws DashException {
        try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw new DashException("mp4_structure"); }
    }

    /** Medien-Segment prüfen (Struktur, keine Verschlüsselungshinweise). */
    public static void segment(File file) throws IOException, DashException {
        if (file.length() <= 0) throw new DashException("segment_box");
        int[] budget = {MAX_BOXES};
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            List<Box> top = boxes(f, 0, f.length(), budget);
            only(top, set("styp", "sidx", "ssix", "prft", "emsg", "moof", "mdat", "free", "skip"), "segment_box");
            boolean moof = false, mdat = false;
            for (Box b : top) {
                if (b.type.equals("mdat")) mdat = true;
                if (!b.type.equals("moof")) continue;
                moof = true;
                List<Box> mk = boxes(f, b.body(), b.end(), budget);
                only(mk, set("mfhd", "traf"), "segment_box");
                for (Box t : mk) {
                    if (!t.type.equals("traf")) continue;
                    List<Box> tk = boxes(f, t.body(), t.end(), budget);
                    only(tk, set("tfhd", "tfdt", "trun", "sdtp", "sbgp", "sgpd", "subs", "uuid"), "segment_box");
                    for (Box k : tk) if (k.type.equals("sgpd") || k.type.equals("sbgp")) seig(f, k);
                }
            }
            if (!moof || !mdat) throw new DashException("segment_box");
        }
    }
}
