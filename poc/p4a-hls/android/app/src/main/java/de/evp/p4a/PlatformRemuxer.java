package de.evp.p4a;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P4a, Weg "Plattform": android.media.MediaExtractor über einen MediaDataSource (dieselbe {@link Concat}-Sicht, keine
 * Kopie) und android.media.MediaMuxer (MPEG-4). Gleiche Regeln wie der Media3-Weg: genau eine H.264-Spur, genau eine
 * Tonspur, ein gemeinsamer Zeitabzug, Abbruch zwischen jedem Sample, keine unvollständige Ausgabe.
 */
public final class PlatformRemuxer {
    static final int MAX_SAMPLE_BYTES = 8 << 20;

    static final class Track {
        final MediaExtractor ex = new MediaExtractor();
        int index = -1;
        MediaFormat format;
        long samples, maxSample, firstUs = Long.MIN_VALUE, minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE;
        boolean done;
    }

    static MediaDataSource source(Concat c) {
        return new MediaDataSource() {
            @Override public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
                return c.readAt(position, buffer, offset, size);
            }
            @Override public long getSize() { return c.length(); }
            @Override public void close() { }
        };
    }

    static Track open(Concat c, String prefix) throws IOException {
        Track t = new Track();
        t.ex.setDataSource(source(c));
        for (int i = 0; i < t.ex.getTrackCount(); i++) {
            MediaFormat f = t.ex.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) {
                if (t.index >= 0) throw new IllegalStateException("multiple-" + prefix);
                t.index = i;
                t.format = f;
            }
        }
        if (t.index < 0) throw new IllegalStateException("no-" + prefix.replace("/", ""));
        t.ex.selectTrack(t.index);
        return t;
    }

    public static Result run(Concat videoData, Concat audioData, File out, AtomicBoolean cancel, boolean allowOpus) throws Exception {
        Result res = new Result().put("path", "platform");
        long t0 = System.nanoTime();
        Track v = null, a = null;
        MediaMuxer muxer = null;
        boolean ok = false, started = false;
        try {
            v = open(videoData, "video/");
            a = open(audioData != null ? audioData : videoData, "audio/");
            String vm = v.format.getString(MediaFormat.KEY_MIME), am = a.format.getString(MediaFormat.KEY_MIME);
            if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(vm)) throw new IllegalStateException("video-codec:" + vm);
            boolean aac = MediaFormat.MIMETYPE_AUDIO_AAC.equals(am), opus = MediaFormat.MIMETYPE_AUDIO_OPUS.equals(am);
            if (!aac && !(opus && allowOpus)) throw new IllegalStateException("audio-codec:" + am);
            res.tracks.add("video " + v.format);
            res.tracks.add("audio " + a.format);
            long vFirst = v.ex.getSampleTime(), aFirst = a.ex.getSampleTime();
            if (vFirst < 0 || aFirst < 0) throw new IllegalStateException("empty-track");
            long base = Math.min(vFirst, aFirst);
            res.put("video_first_us", vFirst).put("audio_first_us", aFirst).put("base_us", base);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = muxer.addTrack(v.format), at = muxer.addTrack(a.format);
            muxer.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long written = 0;
            while (true) {
                if (cancel.get()) throw new CancellationException();
                long tv = v.done ? -1 : v.ex.getSampleTime(), ta = a.done ? -1 : a.ex.getSampleTime();
                if (tv < 0) v.done = true;
                if (ta < 0) a.done = true;
                if (v.done && a.done) break;
                Track t = a.done || (!v.done && tv <= ta) ? v : a;
                buf.clear();
                int size = t.ex.readSampleData(buf, 0);
                if (size < 0) { t.done = true; continue; }
                if (size > MAX_SAMPLE_BYTES) throw new IllegalStateException("sample-size");
                long ts = t.ex.getSampleTime();
                int flags = (t.ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                if ((t.ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IllegalStateException("encrypted-sample");
                long rel = ts - base;
                if (rel < 0) throw new IllegalStateException("negative-timestamp");
                info.set(0, size, rel, flags);
                muxer.writeSampleData(t == v ? vt : at, buf, info);
                written += size;
                t.samples++;
                t.maxSample = Math.max(t.maxSample, size);
                t.minUs = Math.min(t.minUs, ts);
                t.maxUs = Math.max(t.maxUs, ts);
                t.ex.advance();
            }
            muxer.stop();
            muxer.release();
            muxer = null;
            ok = true;
            res.put("video_samples", v.samples).put("audio_samples", a.samples).put("video_span_us", v.maxUs - v.minUs)
                .put("audio_span_us", a.maxUs - a.minUs).put("max_sample", Math.max(v.maxSample, a.maxSample)).put("bytes_written", written)
                .put("out_bytes", out.length()).put("ms", (System.nanoTime() - t0) / 1_000_000);
            return res;
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Exception ignored) { }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (v != null) v.ex.release();
            if (a != null) a.ex.release();
            if (!ok) out.delete();
        }
    }
}
