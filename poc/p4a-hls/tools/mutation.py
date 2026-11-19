#!/usr/bin/env python3
# P4a: Mutationsproben für Parser, URI-Auflösung, Plan und Abruf. Jede abgeschwächte Kopie muss die JVM-Tests rot machen.
#   python3 -I mutation.py FIXTURES_DIR [CACHE_DIR]
import os, shutil, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__)); src = os.path.join(here, '..', 'src')
M = [
    ('main/java/de/evp/helfer/hls/HlsPlaylist.java', 'if (!"NONE".equals(enumerated(a, "METHOD")) || a.size() != 1) throw new HlsException("encrypted");', ''),
    ('main/java/de/evp/helfer/hls/HlsPlaylist.java', 'if (!endList) throw new HlsException("live");', ''),
    ('main/java/de/evp/helfer/hls/HlsPlaylist.java', 'REJECTED.put("EXT-X-DISCONTINUITY", "discontinuity");', ''),
    ('main/java/de/evp/helfer/hls/HlsPlaylist.java', 'throw new HlsException(MASTER_TAGS.contains(tag) ? "mixed-playlist" : "unknown-tag");', 'break;'),
    ('main/java/de/evp/helfer/hls/HlsPlaylist.java', 'if (body.length > MAX_MANIFEST_BYTES) throw new HlsException("manifest-size");', ''),
    ('main/java/de/evp/helfer/hls/HlsUri.java', 't.query = r.query != null ? r.query : b.query;', 't.query = b.query;'),
    ('main/java/de/evp/helfer/hls/HlsUri.java', '                    t.query = r.query;\n                }\n                t.authority', '                    t.query = r.query != null ? r.query : b.query;\n                }\n                t.authority'),
    ('main/java/de/evp/helfer/hls/HlsUri.java', 'if (!"http".equals(p.scheme) && !"https".equals(p.scheme)) throw new HlsException("uri-scheme");', ''),
    ('main/java/de/evp/helfer/hls/HlsPlan.java', 'if (n > MAX_PLAN_SEGMENTS) throw new HlsException("segments-limit");', ''),
    ('main/java/de/evp/helfer/hls/HlsPlan.java', 'if (Math.abs(video.totalSeconds - audio.totalSeconds) > tol) throw new HlsException("av-duration-mismatch");', ''),
    ('jvm/java/de/evp/helfer/hls/HlsFetch.java', 'if (total > maxBytes) throw new HlsException("segment-size");', ''),
    ('jvm/java/de/evp/helfer/hls/HlsFetch.java', 'return new Text(out.toByteArray(), r.url());', 'return new Text(out.toByteArray(), url);'),
]
fx = sys.argv[1]; cache = sys.argv[2] if len(sys.argv) > 2 else ''
survived = []
for i, (f, a, b) in enumerate(M):
    with tempfile.TemporaryDirectory() as t:
        shutil.copytree(src, os.path.join(t, 'src'))
        p = os.path.join(t, 'src', f); s = open(p).read()
        assert s.count(a) == 1, ('anchor', f, a[:60])
        open(p, 'w').write(s.replace(a, b))
        r = subprocess.run(['bash', os.path.join(here, 'run-jvm.sh'), fx] + ([cache] if cache else []), env=dict(os.environ, P4A_SRC=os.path.join(t, 'src')),
                           capture_output=True, text=True)
        killed = r.returncode != 0
        print(('killed   ' if killed else 'SURVIVED ') + f.split('/')[-1] + ': ' + a[:70].replace('\n', ' '))
        if not killed: survived.append(a)
if survived: print('FAIL: %d mutation(s) survived' % len(survived)); sys.exit(1)
print('PASS: all %d mutations killed' % len(M))
