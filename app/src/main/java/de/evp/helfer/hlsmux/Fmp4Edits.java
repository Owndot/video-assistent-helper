package de.evp.helfer.hlsmux;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * Befund P4a: ffmpeg (und andere Packager) legen in fMP4-HLS den Zeitversatz der Spuren nur in der Editliste der
 * Init-Ressource ab (leere Edit = Verzögerung, media_time = Vorlauf wie B-Frame-/AAC-Priming), tfdt beginnt bei 0.
 * Media3s FragmentedMp4Extractor wendet nur eine EINZELNE Edit an; zwei Edits ignoriert er (gemessen: Ton 60 ms bzw.
 * 360 ms versetzt). Diese Klasse liest die Editlisten (nur moov/trak/edts/elst, mdhd, mvhd, hdlr) und liefert je
 * Spurart die noch fehlende Korrektur in µs. Unterstützt werden nur die Formen "keine", "eine Edit" und "eine leere
 * Edit + eine Medien-Edit", jeweils Rate 1; alles andere wird abgelehnt (fail-closed).
 */
public final class Fmp4Edits {
    private Fmp4Edits() {}

    /** "vide"/"soun" → Korrektur in µs, die zusätzlich zu Media3s eigener Behandlung anzuwenden ist. */
    public static Map<String, Long> corrections(File init) throws IOException {
        byte[] b = new byte[(int) Math.min(init.length(), 1 << 20)];
        try (RandomAccessFile f = new RandomAccessFile(init, "r")) { f.readFully(b); }
        Map<String, Long> out = new HashMap<>();
        long movieTs = -1;
        for (int[] moov : children(b, 0, b.length)) {
            if (moov[0] != type("moov")) continue;
            for (int[] c : children(b, moov[1], moov[2])) {
                if (c[0] == type("mvhd")) movieTs = timescale(b, c[1]);
            }
            for (int[] trak : children(b, moov[1], moov[2])) {
                if (trak[0] != type("trak")) continue;
                long[][] edits = null;
                String handler = null;
                long mediaTs = -1, mediaDur = 0;
                for (int[] t : children(b, trak[1], trak[2])) {
                    if (t[0] == type("edts")) for (int[] e : children(b, t[1], t[2])) if (e[0] == type("elst")) edits = elst(b, e[1]);
                    if (t[0] == type("mdia")) for (int[] m : children(b, t[1], t[2])) {
                        if (m[0] == type("mdhd")) { mediaTs = timescale(b, m[1]); mediaDur = duration(b, m[1]); }
                        if (m[0] == type("hdlr")) handler = new String(b, m[1] + 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                    }
                }
                if (handler == null || mediaTs <= 0 || movieTs <= 0) throw new IOException("init-structure");
                if (!handler.equals("vide") && !handler.equals("soun")) continue;
                if (out.containsKey(handler)) throw new IOException("multiple-" + handler);
                out.put(handler, correction(edits, movieTs, mediaTs, mediaDur));
            }
        }
        return out;
    }

    static long correction(long[][] edits, long movieTs, long mediaTs, long mediaDur) throws IOException {
        if (edits == null || edits.length == 0) return 0;
        for (long[] e : edits) if (e[2] != 1) throw new IOException("edit-rate");
        if (edits.length == 1) {
            long d = edits[0][0], mt = edits[0][1];
            if (mt < 0) throw new IOException("edit-list");
            long dUs = d * 1_000_000L / movieTs, mtUs = mt * 1_000_000L / mediaTs, trackUs = mediaDur * 1_000_000L / mediaTs;
            boolean media3Applies = d == 0 || dUs + mtUs >= trackUs;   // FragmentedMp4Extractor#isEdtsListDurationForEntireMediaTimeline
            return media3Applies ? 0 : -mtUs;
        }
        if (edits.length == 2 && edits[0][1] == -1 && edits[1][1] >= 0) {
            return edits[0][0] * 1_000_000L / movieTs - edits[1][1] * 1_000_000L / mediaTs;
        }
        throw new IOException("edit-list");
    }

    static int type(String s) { return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3); }

    static int u32(byte[] b, int i) { return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff); }

    static long u64(byte[] b, int i) { return ((long) u32(b, i) << 32) | (u32(b, i + 4) & 0xffffffffL); }

    /** Kinder einer Box: {Typ, Inhaltsbeginn, Ende}; begrenzt und bereichsgeprüft. */
    static java.util.List<int[]> children(byte[] b, int start, int end) throws IOException {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int i = start;
        while (i + 8 <= end) {
            long n = u32(b, i) & 0xffffffffL;
            int h = 8;
            if (n == 1) { n = u64(b, i + 8); h = 16; } else if (n == 0) n = end - i;
            if (n < h || i + n > end || out.size() > 256) throw new IOException("box-bounds");
            out.add(new int[] {u32(b, i + 4), i + h, (int) (i + n)});
            i += (int) n;
        }
        return out;
    }

    static long timescale(byte[] b, int a) { return (u32(b, a + (b[a] == 1 ? 20 : 12)) & 0xffffffffL); }

    static long duration(byte[] b, int a) { return b[a] == 1 ? u64(b, a + 24) : (u32(b, a + 16) & 0xffffffffL); }

    static long[][] elst(byte[] b, int a) throws IOException {
        int v = b[a];
        int n = u32(b, a + 4);
        if (n < 0 || n > 16) throw new IOException("edit-list");
        long[][] out = new long[n][];
        int o = a + 8;
        for (int k = 0; k < n; k++) {
            long d, mt;
            if (v == 1) { d = u64(b, o); mt = u64(b, o + 8); o += 16; } else { d = u32(b, o) & 0xffffffffL; mt = u32(b, o + 4); o += 8; }
            int rate = (b[o] << 8) | (b[o + 1] & 0xff), frac = ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
            o += 4;
            out[k] = new long[] {d, mt, rate == 1 && frac == 0 ? 1 : 0};
        }
        return out;
    }
}
