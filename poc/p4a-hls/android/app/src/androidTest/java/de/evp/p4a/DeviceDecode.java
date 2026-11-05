package de.evp.p4a;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * P4a: "abspielbar" auf genau diesem Android – jede Spur der Ausgabe wird mit dem Plattform-Extractor gelesen und mit
 * dem Plattform-Decoder vollständig dekodiert (ohne Surface). Ergebnis: Spuren, Samples, dekodierte Einheiten, Fehler.
 */
final class DeviceDecode {
    private DeviceDecode() {}

    static void check(File f, Result r) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            r.put("device_tracks", ex.getTrackCount());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                String k = mime != null && mime.startsWith("video/") ? "device_video" : "device_audio";
                r.put(k + "_mime", mime);
                try {
                    int[] n = decode(f, i, fmt);
                    r.put(k + "_samples", n[0]).put(k + "_decoded", n[1]);
                } catch (Throwable t) {
                    r.put(k + "_decode_error", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            r.put("device_extract_error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            ex.release();
        }
    }

    /** {Samples gelesen, Ausgabepuffer dekodiert}; höchstens 120 s. */
    static int[] decode(File f, int track, MediaFormat fmt) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEnd = false, outEnd = false;
            int samples = 0, decoded = 0;
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (!outEnd) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("decode-timeout");
                if (!inEnd) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEnd = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            samples++;
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    if (info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) decoded++;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEnd = true;
                    codec.releaseOutputBuffer(out, false);
                }
            }
            return new int[] {samples, decoded};
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            ex.release();
        }
    }
}
