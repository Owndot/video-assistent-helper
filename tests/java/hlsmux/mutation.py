#!/usr/bin/env python3
# P4b-4: Mutationsproben für den App-Remuxer (de.evp.helfer.hlsmux). Jede abgeschwächte Kopie muss tests/java/hlsmux/run.sh
# rot machen (Analyse gegen die Quelle, Ablehnungen, Prüfung der Ausgabe).
#   python3 -I mutation.py FIXTURES_DIR [CACHE_DIR]
import os, shutil, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, '..', '..', '..'); src = os.path.join(root, 'app', 'src', 'main', 'java')
R = 'de/evp/helfer/hlsmux/Media3HlsRemuxer.java'
M = [
    (R, 'new TsExtractor(TsExtractor.MODE_SINGLE_PMT,', 'new TsExtractor(TsExtractor.MODE_HLS,'),
    (R, 'videoCorrUs = c.getOrDefault("vide", 0L);', 'videoCorrUs = 0;'),
    (R, 'audioCorrUs = c.getOrDefault("soun", 0L);', 'audioCorrUs = 0;'),
    (R, 'long t = s.timeUs - base;', 'long t = s.timeUs - (takeVideo ? vc.minUs : ac.minUs);'),
    (R, 'if (!MimeTypes.AUDIO_AAC.equals(am) && !(MimeTypes.AUDIO_OPUS.equals(am) && opusAllowed)) throw new HlsException("audio-codec");',
        'if (!MimeTypes.AUDIO_AAC.equals(am) && !MimeTypes.AUDIO_OPUS.equals(am)) throw new HlsException("audio-codec");'),
    (R, 'if (++count > in.maxSamples) throw new HlsException("samples-limit");', '++count;'),
    (R, 'if (free < rt.maxMemory() * minFreeHeapFraction) throw new HlsException("memory");', ''),
    (R, 'if (ac == null || ac.format == null || ac.queue.isEmpty()) throw new HlsException("no-audio");', 'if (ac == null) throw new HlsException("remux");'),
    (R, 'if (!ok) out.delete();', ''),
    (R, 'if (v.samples != expected.videoSamples || a.samples != expected.audioSamples) throw new HlsException("verify-samples");', ''),
    (R, 'if (Math.abs(spanSeconds(v) - videoSeconds) > toleranceSeconds) throw new HlsException("verify-duration");', ''),
    (R, 'if (v == null || a == null || v.format == null || a.format == null) throw new HlsException("verify-tracks");', 'if (v == null) throw new HlsException("verify-tracks");'),
]
fx = sys.argv[1]; cache = sys.argv[2] if len(sys.argv) > 2 else ''
survived = []
for f, a, b in M:
    with tempfile.TemporaryDirectory() as t:
        shutil.copytree(src, os.path.join(t, 'java'))
        p = os.path.join(t, 'java', f); s = open(p).read()
        assert s.count(a) == 1, ('anchor', f, a[:60])
        open(p, 'w').write(s.replace(a, b))
        r = subprocess.run(['bash', os.path.join(here, 'run.sh'), fx] + ([cache] if cache else []), env=dict(os.environ, APP_SRC=os.path.join(t, 'java')),
                           capture_output=True, text=True)
        killed = r.returncode != 0
        print(('killed   ' if killed else 'SURVIVED ') + a[:90])
        if not killed: survived.append(a)
if survived: print('FAIL: %d mutation(s) survived' % len(survived)); sys.exit(1)
print('PASS: all %d mutations killed' % len(M))
