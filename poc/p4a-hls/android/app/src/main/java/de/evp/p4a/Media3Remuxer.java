package de.evp.p4a;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Muxer;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P4a, Weg "Media3": TsExtractor (MODE_HLS) bzw. FragmentedMp4Extractor lesen die verifizierten Ressourcen je Spur
 * (Video-Variante, optional getrennte Ton-Rendition) aus {@link Concat}; die Samples gehen unverändert (keine
 * Neukodierung) in androidx.media3.muxer.Mp4Muxer. Zeitbasis: TS mit TimestampAdjuster.MODE_NO_OFFSET (rohe PTS, beide
 * Renditionen auf derselben 90-kHz-Uhr), fMP4 mit den tfdt-/Editlisten-Zeiten des Extractors; danach EIN gemeinsamer
 * Abzug (kleinster erster Zeitstempel), nie je Spur einzeln. Ausgabe streng: genau eine H.264-Spur und genau eine
 * Tonspur (AAC, Opus nur mit {@code allowOpus}); verschlüsselte Samples, fremde Codecs, fehlender Ton → Abbruch.
 * Speicher: feste Puffer, Sample ≤ 8 MiB, Warteschlangen je Spur ≤ 32 MiB. Abbruch zwischen jedem Leseschritt/Sample.
 */
public final class Media3Remuxer {
    public static final int MAX_SAMPLE_BYTES = 8 << 20;
    public static final long MAX_QUEUE_BYTES = 32L << 20;
    /** MODE_HLS gibt am Ende der verketteten Segmente die letzte H.264-Zugriffseinheit nicht aus (P4a-Befund, 499/500
     *  Bilder); MODE_SINGLE_PMT schließt sie ab. Das verkettete Ganze ist ein normaler TS-Strom mit einer PMT. */
    static int TS_MODE = TsExtractor.MODE_SINGLE_PMT;
    static final int MAX_SEEKS = 16;
    /** Nur Messung (P4a): wird alle 10 000 geschriebenen Samples mit der Anzahl aufgerufen. */
    public static volatile java.util.function.LongConsumer probe;

    static final class Sample {
        final long timeUs;
        final int flags;
        final byte[] data;
        Sample(long timeUs, int flags, byte[] data) { this.timeUs = timeUs; this.flags = flags; this.data = data; }
    }

    /** Sammelt die Samples genau einer Spur (Video oder Ton). */
    static final class Collector implements TrackOutput {
        final int type;
        Format format;
        byte[] buf = new byte[64 * 1024];
        int len;
        final ArrayDeque<Sample> queue = new ArrayDeque<>();
        long queued, samples, maxSample, firstUs = Long.MIN_VALUE, lastUs = Long.MIN_VALUE, minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE;

        final long corrUs;
        Collector(int type, long corrUs) { this.type = type; this.corrUs = corrUs; }

        @Override public void format(Format f) {
            if (format != null && !sameCodec(format, f)) throw new IllegalStateException("codec-change");
            format = f;
        }

        static boolean sameCodec(Format a, Format b) {
            return String.valueOf(a.sampleMimeType).equals(String.valueOf(b.sampleMimeType)) && a.initializationData.size() == b.initializationData.size();
        }

        private void ensure(int more) {
            if ((long) len + more > 2L * MAX_SAMPLE_BYTES) throw new IllegalStateException("sample-size");
            if (len + more > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(len + more, Math.min(buf.length * 2, 2 * MAX_SAMPLE_BYTES)));
        }

        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part) throws IOException {
            if (part != SAMPLE_DATA_PART_MAIN) throw new IllegalStateException("sample-part");
            ensure(length);
            int r = input.read(buf, len, length);
            if (r == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            len += r;
            return r;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            if (part != SAMPLE_DATA_PART_MAIN) throw new IllegalStateException("sample-part");
            ensure(length);
            data.readBytes(buf, len, length);
            len += length;
        }

        @Override public void sampleMetadata(long timeUs0, int flags, int size, int offset, CryptoData cryptoData) {
            long timeUs = timeUs0 + corrUs;
            if (cryptoData != null) throw new IllegalStateException("encrypted-sample");
            if (size < 0 || size > MAX_SAMPLE_BYTES) throw new IllegalStateException("sample-size");
            int start = len - offset - size;
            if (start < 0) throw new IllegalStateException("sample-bounds");
            byte[] d = java.util.Arrays.copyOfRange(buf, start, start + size);
            System.arraycopy(buf, start + size, buf, 0, len - start - size);
            len -= start + size;
            queue.add(new Sample(timeUs, flags & C.BUFFER_FLAG_KEY_FRAME, d));
            queued += size;
            samples++;
            maxSample = Math.max(maxSample, size);
            if (firstUs == Long.MIN_VALUE) firstUs = timeUs;
            lastUs = timeUs;
            minUs = Math.min(minUs, timeUs);
            maxUs = Math.max(maxUs, timeUs);
            if (queued > MAX_QUEUE_BYTES) throw new IllegalStateException("interleave-queue");
        }

        Sample poll() { Sample s = queue.poll(); if (s != null) queued -= s.data.length; return s; }
    }

    /** Liest eine Rendition (Video-Variante oder Ton) aus den verkettenen Ressourcen. */
    static final class Source implements ExtractorOutput {
        final Concat data;
        final Extractor extractor;
        final boolean wantVideo, wantAudio;
        Collector video, audio;
        ExtractorInput input;
        final PositionHolder seek = new PositionHolder();
        boolean done, tracksEnded;
        long position;
        int seeks;
        long videoCorrUs, audioCorrUs;   // fMP4: Editlisten-Korrektur (Fmp4Edits)

        Source(Concat data, boolean fmp4, boolean wantVideo, boolean wantAudio) {
            this.data = data;
            this.wantVideo = wantVideo;
            this.wantAudio = wantAudio;
            if (fmp4) {
                try {
                    java.util.Map<String, Long> c = Fmp4Edits.corrections(data.first());
                    videoCorrUs = c.getOrDefault("vide", 0L);
                    audioCorrUs = c.getOrDefault("soun", 0L);
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage());
                }
                extractor = new FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                    null, null, Collections.emptyList(), null);
            } else {
                extractor = new TsExtractor(TS_MODE, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED,
                    new TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET),
                    new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM, Collections.emptyList()),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
            }
            extractor.init(this);
            input = newInput(0);
        }

        ExtractorInput newInput(long pos) {
            position = pos;
            DataReader r = new DataReader() {
                long p = pos;
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = data.readAt(p, b, off, len);
                    if (n < 0) return C.RESULT_END_OF_INPUT;
                    p += n;
                    return n;
                }
            };
            return new DefaultExtractorInput(r, pos, data.length());
        }

        @Override public TrackOutput track(int id, int type) {
            if (type == C.TRACK_TYPE_VIDEO && wantVideo) {
                if (video != null) throw new IllegalStateException("multiple-video");
                return video = new Collector(type, videoCorrUs);
            }
            if (type == C.TRACK_TYPE_AUDIO && wantAudio) {
                if (audio != null) throw new IllegalStateException("multiple-audio");
                return audio = new Collector(type, audioCorrUs);
            }
            return new DiscardingTrackOutput();   // ungewählter Ton in der Variante, Metadaten, Untertitel
        }

        @Override public void endTracks() { tracksEnded = true; }
        @Override public void seekMap(SeekMap seekMap) { }

        void step() throws IOException {
            if (done) return;
            int r = extractor.read(input, seek);
            if (r == Extractor.RESULT_SEEK) {
                // nur die Dauerbestimmung von TsExtractor (Ende lesen, zurück an den Anfang); begrenzt
                if (++seeks > MAX_SEEKS || seek.position < 0 || seek.position > data.length()) throw new IllegalStateException("seek-limit");
                input = newInput(seek.position);
            } else if (r == Extractor.RESULT_END_OF_INPUT) {
                done = true;
            }
        }
    }

    public static Result run(Concat videoData, Concat audioData, boolean fmp4, File out, AtomicBoolean cancel, boolean allowOpus) throws Exception {
        return run(videoData, audioData, fmp4, out, cancel, allowOpus, false);
    }

    /** @param fragmentedOutput true: FragmentedMp4Muxer (Index je Fragment) statt Mp4Muxer (Index über die ganze Datei) */
    public static Result run(Concat videoData, Concat audioData, boolean fmp4, File out, AtomicBoolean cancel, boolean allowOpus,
            boolean fragmentedOutput) throws Exception {
        Result res = new Result().put("path", fragmentedOutput ? "media3-fragmented" : "media3");
        long t0 = System.nanoTime();
        Source v = new Source(videoData, fmp4, true, audioData == null);
        Source a = audioData == null ? null : new Source(audioData, fmp4, false, true);
        List<Source> sources = new ArrayList<>();
        sources.add(v);
        if (a != null) sources.add(a);
        Muxer muxer = null;
        boolean ok = false;
        try {
            // Formate und je Spur das erste Sample abwarten (begrenzt durch die Warteschlangen)
            while (!ready(v, a)) {
                if (cancel.get()) throw new CancellationException();
                boolean progressed = false;
                for (Source s : sources) if (!s.done) { s.step(); progressed = true; }
                if (!progressed) break;
            }
            Collector vc = v.video, ac = a != null ? a.audio : v.audio;
            if (vc == null || vc.format == null || vc.queue.isEmpty()) throw new IllegalStateException("no-video");
            if (ac == null || ac.format == null || ac.queue.isEmpty()) throw new IllegalStateException("no-audio");
            if (!MimeTypes.VIDEO_H264.equals(vc.format.sampleMimeType)) throw new IllegalStateException("video-codec:" + vc.format.sampleMimeType);
            String am = ac.format.sampleMimeType;
            boolean aac = MimeTypes.AUDIO_AAC.equals(am), opus = MimeTypes.AUDIO_OPUS.equals(am);
            if (!aac && !(opus && allowOpus)) throw new IllegalStateException("audio-codec:" + am);
            long base = Math.min(vc.queue.peek().timeUs, ac.queue.peek().timeUs);
            res.put("video_corr_us", vc.corrUs).put("audio_corr_us", ac.corrUs);
            res.put("video_first_us", vc.queue.peek().timeUs).put("audio_first_us", ac.queue.peek().timeUs).put("base_us", base);
            muxer = fragmentedOutput ? new FragmentedMp4Muxer.Builder(new FileOutputStream(out)).build()
                : new Mp4Muxer.Builder(new FileOutputStream(out)).build();
            int vt = muxer.addTrack(vc.format), at = muxer.addTrack(ac.format);
            res.tracks.add("video " + vc.format.sampleMimeType + " " + vc.format.width + "x" + vc.format.height + " codecs=" + vc.format.codecs);
            res.tracks.add("audio " + am + " " + ac.format.sampleRate + "Hz ch=" + ac.format.channelCount + " codecs=" + ac.format.codecs);
            long written = 0, count = 0;
            while (true) {
                if (cancel.get()) throw new CancellationException();
                Source vs = v, as = a != null ? a : v;
                while (vc.queue.isEmpty() && !vs.done) vs.step();
                while (ac.queue.isEmpty() && !as.done) as.step();
                Sample sv = vc.queue.peek(), sa = ac.queue.peek();
                if (sv == null && sa == null) break;
                boolean takeVideo = sa == null || (sv != null && sv.timeUs <= sa.timeUs);
                Sample s = takeVideo ? vc.poll() : ac.poll();
                long t = s.timeUs - base;
                if (t < 0) throw new IllegalStateException("negative-timestamp");
                muxer.writeSampleData(takeVideo ? vt : at, ByteBuffer.wrap(s.data), new BufferInfo(t, s.data.length, s.flags));
                written += s.data.length;
                if (++count % 10_000 == 0 && probe != null) probe.accept(count);
            }
            muxer.close();
            muxer = null;
            ok = true;
            res.put("video_samples", vc.samples).put("audio_samples", ac.samples).put("video_span_us", vc.maxUs - vc.minUs)
                .put("audio_span_us", ac.maxUs - ac.minUs).put("max_sample", Math.max(vc.maxSample, ac.maxSample)).put("bytes_written", written)
                .put("out_bytes", out.length()).put("ms", (System.nanoTime() - t0) / 1_000_000);
            return res;
        } finally {
            if (muxer != null) { try { muxer.close(); } catch (Exception ignored) { } }
            for (Source s : sources) { try { s.extractor.release(); } catch (Exception ignored) { } }
            if (!ok) out.delete();   // nie eine unvollständige Ausgabe stehen lassen
        }
    }

    private static boolean ready(Source v, Source a) {
        Collector vc = v.video, ac = a != null ? a.audio : v.audio;
        boolean vr = vc != null && vc.format != null && !vc.queue.isEmpty();
        boolean ar = ac != null && ac.format != null && !ac.queue.isEmpty();
        return vr && ar;
    }
}
