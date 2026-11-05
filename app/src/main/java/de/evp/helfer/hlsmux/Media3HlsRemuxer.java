package de.evp.helfer.hlsmux;

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
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsRemux;

/**
 * HLS → MP4 ohne Neukodierung (P4a-Weg "Media3", in P4b übernommen). Liest nur die übergebenen, bereits verifizierten
 * Dateien – kein Netzwerk, keine Medienquelle der Bibliothek. TS: {@link TsExtractor} im Modus {@code MODE_SINGLE_PMT} (MODE_HLS gibt
 * die letzte Zugriffseinheit nicht aus) mit {@code TimestampAdjuster.MODE_NO_OFFSET} (rohe 90-kHz-Zeiten beider
 * Renditionen); fMP4: {@link FragmentedMp4Extractor} plus {@link Fmp4Edits} (zwei-Eintrags-Editlisten). Danach EIN
 * gemeinsamer Abzug (kleinster erster Zeitstempel), nie je Spur einzeln. Ausgabe: {@link Mp4Muxer} (ein Index über die
 * ganze Datei; die fragmentierte Ausgabe ist auf API 26/29 nicht lesbar).
 *
 * <p>Streng: genau eine H.264-Spur und genau eine Tonspur (AAC; Opus nur, wenn das Gerät es nachweislich kann);
 * verschlüsselte Samples, fremde Codecs, Codecwechsel, fehlender Ton → Abbruch ohne Ausgabe. Speicher: Sample ≤ 8 MiB,
 * Warteschlange je Spur ≤ 32 MiB, Sample-Budget je Auftrag (der Index von Mp4Muxer wächst je Sample), und bei
 * Speicherdruck Abbruch mit "memory" statt eines Absturzes. Abbruch wird zwischen jedem Leseschritt geprüft.
 */
public final class Media3HlsRemuxer implements HlsRemux {
    public static final int MAX_SAMPLE_BYTES = 8 << 20;
    public static final long MAX_QUEUE_BYTES = 32L << 20;
    static final int MAX_SEEKS = 16;
    /** Unter so viel freiem Heap (Anteil am Maximum) wird abgebrochen: der Rest bleibt für Muxer-Index und App. */
    static final double MIN_FREE_HEAP_FRACTION = 0.15;
    static final int MEMORY_CHECK_EVERY = 2048;

    private final boolean opusAllowed;
    private final double minFreeHeapFraction;

    public Media3HlsRemuxer(boolean opusAllowed) { this(opusAllowed, MIN_FREE_HEAP_FRACTION); }

    /** {@code minFreeHeapFraction}: unter diesem freien Anteil des Java-Heaps bricht der Remux mit "memory" ab. */
    Media3HlsRemuxer(boolean opusAllowed, double minFreeHeapFraction) {
        this.opusAllowed = opusAllowed;
        this.minFreeHeapFraction = minFreeHeapFraction;
    }

    /** Messwert: Java-Heap in Byte, der nach diesem Lauf noch belegt war (nur Diagnose). */
    public volatile long lastHeapUsed;

    static final class Sample {
        final long timeUs;
        final int flags;
        final byte[] data;
        Sample(long timeUs, int flags, byte[] data) { this.timeUs = timeUs; this.flags = flags; this.data = data; }
    }

    /** Fester Abbruchgrund (neutral, ohne Inhalt). */
    static final class Reject extends RuntimeException {
        final String reason;
        Reject(String reason) { super(reason, null, false, false); this.reason = reason; }
    }

    /** Sammelt die Samples genau einer Spur. */
    static final class Collector implements TrackOutput {
        final int type;
        final long corrUs;
        Format format;
        byte[] buf = new byte[64 * 1024];
        int len;
        final ArrayDeque<Sample> queue = new ArrayDeque<>();
        long queued, samples, minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE;

        Collector(int type, long corrUs) { this.type = type; this.corrUs = corrUs; }

        @Override public void format(Format f) {
            if (format != null && !sameCodec(format, f)) throw new Reject("codec-change");
            format = f;
        }

        static boolean sameCodec(Format a, Format b) {
            return String.valueOf(a.sampleMimeType).equals(String.valueOf(b.sampleMimeType)) && a.initializationData.size() == b.initializationData.size();
        }

        private void ensure(int more) {
            if ((long) len + more > 2L * MAX_SAMPLE_BYTES) throw new Reject("sample-size");
            if (len + more > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(len + more, Math.min(buf.length * 2, 2 * MAX_SAMPLE_BYTES)));
        }

        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part) throws IOException {
            if (part != SAMPLE_DATA_PART_MAIN) throw new Reject("sample-part");
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
            if (part != SAMPLE_DATA_PART_MAIN) throw new Reject("sample-part");
            ensure(length);
            data.readBytes(buf, len, length);
            len += length;
        }

        @Override public void sampleMetadata(long timeUs0, int flags, int size, int offset, CryptoData cryptoData) {
            if (cryptoData != null) throw new Reject("encrypted-sample");
            if (size < 0 || size > MAX_SAMPLE_BYTES) throw new Reject("sample-size");
            int start = len - offset - size;
            if (start < 0) throw new Reject("sample-bounds");
            long timeUs = timeUs0 + corrUs;
            byte[] d = java.util.Arrays.copyOfRange(buf, start, start + size);
            System.arraycopy(buf, start + size, buf, 0, len - start - size);
            len -= start + size;
            queue.add(new Sample(timeUs, flags & C.BUFFER_FLAG_KEY_FRAME, d));
            queued += size;
            samples++;
            minUs = Math.min(minUs, timeUs);
            maxUs = Math.max(maxUs, timeUs);
            if (queued > MAX_QUEUE_BYTES) throw new Reject("interleave-queue");
        }

        Sample poll() { Sample s = queue.poll(); if (s != null) queued -= s.data.length; return s; }
    }

    /** Eine Rendition (Video-Variante oder getrennter Ton) aus den verketteten Ressourcen. */
    static final class Source implements ExtractorOutput {
        final Concat data;
        final Extractor extractor;
        final boolean wantVideo, wantAudio;
        Collector video, audio;
        ExtractorInput input;
        final PositionHolder seek = new PositionHolder();
        boolean done;
        int seeks;
        long videoCorrUs, audioCorrUs;

        Source(Concat data, boolean fmp4, boolean wantVideo, boolean wantAudio) throws IOException {
            this.data = data;
            this.wantVideo = wantVideo;
            this.wantAudio = wantAudio;
            if (fmp4) {
                java.util.Map<String, Long> c = Fmp4Edits.corrections(data.first());
                videoCorrUs = c.getOrDefault("vide", 0L);
                audioCorrUs = c.getOrDefault("soun", 0L);
                extractor = new FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                    null, null, Collections.emptyList(), null);
            } else {
                extractor = new TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED,
                    new TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET),
                    new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM, Collections.emptyList()),
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
            }
            extractor.init(this);
            input = newInput(data, 0);
        }

        @Override public TrackOutput track(int id, int type) {
            if (type == C.TRACK_TYPE_VIDEO && wantVideo) {
                if (video != null) throw new Reject("multiple-video");
                return video = new Collector(type, videoCorrUs);
            }
            if (type == C.TRACK_TYPE_AUDIO && wantAudio) {
                if (audio != null) throw new Reject("multiple-audio");
                return audio = new Collector(type, audioCorrUs);
            }
            return new DiscardingTrackOutput();   // ungewählter Ton in der Variante, Metadaten, Untertitel
        }

        @Override public void endTracks() { }
        @Override public void seekMap(SeekMap seekMap) { }

        void step() throws IOException {
            if (done) return;
            int r = extractor.read(input, seek);
            if (r == Extractor.RESULT_SEEK) {
                // nur die Dauerbestimmung von TsExtractor (Ende lesen, zurück an den Anfang); begrenzt
                if (++seeks > MAX_SEEKS || seek.position < 0 || seek.position > data.length()) throw new Reject("seek-limit");
                input = newInput(data, seek.position);
            } else if (r == Extractor.RESULT_END_OF_INPUT) {
                done = true;
            }
        }
    }

    static ExtractorInput newInput(Concat data, long pos) {
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

    @Override public Result remux(Input in, File out, BooleanSupplier cancelled) throws IOException, HlsException {
        if (in.video.isEmpty()) throw new HlsException("no-video");
        Concat vData = new Concat(in.video), aData = in.audio.isEmpty() ? null : new Concat(in.audio);
        List<Source> sources = new ArrayList<>();
        Mp4Muxer muxer = null;
        FileOutputStream file = null;
        boolean ok = false;
        try {
            Source v = new Source(vData, in.fmp4, true, aData == null);
            sources.add(v);
            Source a = aData == null ? null : new Source(aData, in.fmp4, false, true);
            if (a != null) sources.add(a);
            while (!ready(v, a)) {
                check(cancelled);
                boolean progressed = false;
                for (Source s : sources) if (!s.done) { s.step(); progressed = true; }
                if (!progressed) break;
            }
            Collector vc = v.video, ac = a != null ? a.audio : v.audio;
            if (vc == null || vc.format == null || vc.queue.isEmpty()) throw new HlsException("no-video");
            if (ac == null || ac.format == null || ac.queue.isEmpty()) throw new HlsException("no-audio");
            if (!MimeTypes.VIDEO_H264.equals(vc.format.sampleMimeType)) throw new HlsException("video-codec");
            String am = ac.format.sampleMimeType;
            if (!MimeTypes.AUDIO_AAC.equals(am) && !(MimeTypes.AUDIO_OPUS.equals(am) && opusAllowed)) throw new HlsException("audio-codec");
            long base = Math.min(vc.queue.peek().timeUs, ac.queue.peek().timeUs);
            file = new FileOutputStream(out);
            muxer = new Mp4Muxer.Builder(file).build();
            int vt = muxer.addTrack(vc.format), at = muxer.addTrack(ac.format);
            Source vs = v, as = a != null ? a : v;
            long count = 0;
            Runtime rt = Runtime.getRuntime();
            while (true) {
                while (vc.queue.isEmpty() && !vs.done) vs.step();
                while (ac.queue.isEmpty() && !as.done) as.step();
                Sample sv = vc.queue.peek(), sa = ac.queue.peek();
                if (sv == null && sa == null) break;
                boolean takeVideo = sa == null || (sv != null && sv.timeUs <= sa.timeUs);
                Sample s = takeVideo ? vc.poll() : ac.poll();
                long t = s.timeUs - base;
                if (t < 0) throw new HlsException("negative-timestamp");
                if (++count > in.maxSamples) throw new HlsException("samples-limit");
                muxer.writeSampleData(takeVideo ? vt : at, ByteBuffer.wrap(s.data), new BufferInfo(t, s.data.length, s.flags));
                if (count % MEMORY_CHECK_EVERY == 1) {     // auch vor dem ersten Schreiben
                    check(cancelled);
                    long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
                    if (free < rt.maxMemory() * minFreeHeapFraction) {
                        System.gc();
                        free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
                        if (free < rt.maxMemory() * minFreeHeapFraction) throw new HlsException("memory");
                    }
                }
            }
            check(cancelled);
            muxer.close();          // schreibt den Index und schließt den Strom
            muxer = null;
            file = null;
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(out.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);     // dauerhaft auf dem Speicher, bevor die Prüfung und Veröffentlichung folgen
            }
            lastHeapUsed = rt.totalMemory() - rt.freeMemory();
            ok = true;
            return new Result(vc.samples, ac.samples, vc.maxUs - vc.minUs, ac.maxUs - ac.minUs);
        } catch (Reject r) {
            throw new HlsException(r.reason);
        } catch (OutOfMemoryError e) {
            throw new HlsException("memory");
        } catch (HlsException | IOException e) {
            throw e;
        } catch (androidx.media3.muxer.MuxerException | RuntimeException e) {
            throw new HlsException("remux");      // Formatfehler der Bibliothek: neutraler Grund, nie ein Teil-Ergebnis
        } finally {
            if (muxer != null) { try { muxer.close(); } catch (Exception ignored) { } }
            if (file != null) { try { file.close(); } catch (IOException ignored) { } }
            for (Source s : sources) { try { s.extractor.release(); } catch (Exception ignored) { } }
            vData.close();
            if (aData != null) aData.close();
            if (!ok) out.delete();   // nie eine unvollständige Ausgabe stehen lassen
        }
    }

    private static boolean ready(Source v, Source a) {
        Collector vc = v.video, ac = a != null ? a.audio : v.audio;
        return vc != null && vc.format != null && !vc.queue.isEmpty() && ac != null && ac.format != null && !ac.queue.isEmpty();
    }

    private static void check(BooleanSupplier cancelled) throws HlsException {
        if (cancelled.getAsBoolean()) throw new HlsException("cancelled");
    }

    // ------------------------------------------------------------------ Prüfung der Ausgabe

    /** Zählt je Spur Samples und Zeitspanne; die Daten selbst werden gelesen und verworfen. */
    static final class Counter implements TrackOutput {
        final int type;
        Format format;
        long samples, minUs = Long.MAX_VALUE, maxUs = Long.MIN_VALUE, lastUs = Long.MIN_VALUE;
        boolean decreasing, negative;
        final byte[] scratch = new byte[64 * 1024];
        Counter(int type) { this.type = type; }
        @Override public void format(Format f) { format = f; }
        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part) throws IOException {
            int r = input.read(scratch, 0, Math.min(length, scratch.length));
            if (r == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            return r;
        }
        @Override public void sampleData(ParsableByteArray data, int length, int part) { data.skipBytes(length); }
        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            if (cryptoData != null) throw new Reject("verify-encrypted");
            samples++;
            if (timeUs < 0) negative = true;
            if (type == C.TRACK_TYPE_AUDIO && timeUs < lastUs) decreasing = true;
            lastUs = timeUs;
            minUs = Math.min(minUs, timeUs);
            maxUs = Math.max(maxUs, timeUs);
        }
    }

    @Override public void verify(File out, Result expected, double videoSeconds, double audioSeconds, double toleranceSeconds, BooleanSupplier cancelled)
            throws IOException, HlsException {
        if (!out.isFile() || out.length() < 16) throw new HlsException("verify-missing");
        List<Counter> tracks = new ArrayList<>();
        Mp4Extractor ex = new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
        try (RandomAccessFile f = new RandomAccessFile(out, "r")) {
            long length = f.length();
            ex.init(new ExtractorOutput() {
                @Override public TrackOutput track(int id, int type) { Counter c = new Counter(type); tracks.add(c); return c; }
                @Override public void endTracks() { }
                @Override public void seekMap(SeekMap seekMap) { }
            });
            PositionHolder seek = new PositionHolder();
            ExtractorInput input = fileInput(f, 0, length);
            long steps = 0;
            while (true) {
                if ((++steps & 1023) == 0) check(cancelled);
                int r = ex.read(input, seek);
                if (r == Extractor.RESULT_END_OF_INPUT) break;
                if (r == Extractor.RESULT_SEEK) {
                    if (seek.position < 0 || seek.position > length) throw new HlsException("verify-structure");
                    input = fileInput(f, seek.position, length);
                }
            }
        } catch (Reject r) {
            throw new HlsException(r.reason);
        } catch (OutOfMemoryError e) {
            throw new HlsException("memory");
        } catch (HlsException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new HlsException("verify-structure");
        } finally {
            ex.release();
        }
        Counter v = null, a = null;
        for (Counter c : tracks) {
            if (c.type == C.TRACK_TYPE_VIDEO) { if (v != null) throw new HlsException("verify-tracks"); v = c; }
            else if (c.type == C.TRACK_TYPE_AUDIO) { if (a != null) throw new HlsException("verify-tracks"); a = c; }
            else throw new HlsException("verify-tracks");
        }
        if (v == null || a == null || v.format == null || a.format == null) throw new HlsException("verify-tracks");
        if (!MimeTypes.VIDEO_H264.equals(v.format.sampleMimeType)) throw new HlsException("verify-codec");
        String am = a.format.sampleMimeType;
        if (!MimeTypes.AUDIO_AAC.equals(am) && !(MimeTypes.AUDIO_OPUS.equals(am) && opusAllowed)) throw new HlsException("verify-codec");
        if (v.samples != expected.videoSamples || a.samples != expected.audioSamples) throw new HlsException("verify-samples");
        if (v.negative || a.negative || a.decreasing) throw new HlsException("verify-timestamps");
        // Spanne = letzter minus erster Zeitstempel; die Plandauer enthält zusätzlich die Dauer des letzten Samples.
        if (Math.abs(spanSeconds(v) - videoSeconds) > toleranceSeconds) throw new HlsException("verify-duration");
        if (Math.abs(spanSeconds(a) - audioSeconds) > toleranceSeconds) throw new HlsException("verify-duration");
        // Gemeinsame Zeitachse: beide Spuren beginnen nahe beieinander (kein verlorener Versatz)
        if (Math.abs(v.minUs - a.minUs) > toleranceSeconds * 1_000_000L) throw new HlsException("verify-sync");
    }

    static double spanSeconds(Counter c) { return c.samples == 0 ? 0 : (c.maxUs - c.minUs) / 1e6; }

    static ExtractorInput fileInput(RandomAccessFile f, long pos, long length) {
        DataReader r = new DataReader() {
            long p = pos;
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (p >= length) return C.RESULT_END_OF_INPUT;
                f.seek(p);
                int n = f.read(b, off, (int) Math.min(len, length - p));
                if (n < 0) return C.RESULT_END_OF_INPUT;
                p += n;
                return n;
            }
        };
        return new DefaultExtractorInput(r, pos, length);
    }
}
