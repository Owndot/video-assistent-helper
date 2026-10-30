#!/usr/bin/env python3
"""Mutationsproben der MP4-Download-Engine: jede Probe schwächt genau eine Prüfung in einer Kopie der App-Quellen ab;
tests/java/dl/run.sh muss danach fehlschlagen. Aufruf: mutation.py [CACHE_DIR]"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
APP = os.path.join(ROOT, 'app', 'src', 'main', 'java')
DL = 'de/evp/helfer/dl/DirectDownloads.java'
NET = 'de/evp/helfer/net/SafeHttpClient.java'

MUTATIONS = [
    ('status not checked', DL, 'if (response.code() != 200) throw new Fail("http_" + response.code());', ''),
    ('content-length optional', DL, 'if (total <= 0) throw new Fail("no_length");', 'if (total <= 0) total = Long.MAX_VALUE;'),
    ('content type not checked', DL, 'if (!acceptedType(response.contentType())) throw new Fail("type");', ''),
    ('mp4 signature not checked', DL, 'if (h < 8 || !mp4Signature(head)) throw new Fail("not_mp4");', 'if (h < 8) throw new Fail("not_mp4");'),
    ('free space not checked before', DL, 'if (freeBytes() < total + SPACE_RESERVE_BYTES) throw new Fail("no_space");', ''),
    ('free space not rechecked', DL, 'if (freeBytes() < total - job.bytes + SPACE_RESERVE_BYTES / 2) throw new Fail("no_space");', ''),
    # Nicht als Probe: 'if (n < 0) throw new IOException("short body")' und der long_body-Test sind über OkHttp nicht
    # erreichbar (FixedLengthSource wirft bei vorzeitigem Ende ProtocolException und liefert genau Content-Length);
    # sie bleiben als zweite Absicherung. Vorzeitiges Ende prüfen 'truncated body …' und 'at most 3 resumes'.
    ('write error resumed', DL, 'try { out.write(b, 0, n); } catch (IOException e) { throw new Fail("storage"); }', 'out.write(b, 0, n);'),
    ('resume without validator', DL, '(etag == null && lastModified == null)', 'false'),
    ('weak etag accepted', DL, 'return etag == null || etag.startsWith("W/") || etag.isEmpty() ? null : etag;', 'return etag;'),
    ('unlimited resumes', DL, 'resumes >= MAX_RESUMES || ', ''),
    ('206 not required', DL, 'if (r.code() != 206) throw new Fail(r.code() == 200 ? "range_ignored" : "range_http_" + r.code());', ''),
    ('content-range not compared', DL, 'if (s != start || e != total - 1 || t != total) throw new Fail("range_mismatch");', ''),
    ('range length not compared', DL, 'if (r.contentLength() != total - start) throw new Fail("range_length");', ''),
    ('changed etag ignored', DL, 'if (etag != null && !etag.equals(strongEtag(r.etag()))) throw new Fail("changed");', ''),
    ('duplicate start allowed', DL, 'if (known != null) return json(known);           // doppelter', 'if (known != null && known.state != State.RUNNING) return json(known);           // doppelter'),
    ('start before journal', DL, 'saveJournal();                                    // erst vermerkt', '// erst vermerkt'),
    ('parallel limit removed', DL, 'if (running.get() >= MAX_PARALLEL) return busy(id);', ''),
    ('running survives restart', DL, 'if (st == State.RUNNING) { st = State.INTERRUPTED; changed = true; }', ''),
    ('no cleanup on failure', DL, 'if (target != null) target.abort();', ''),
    ('cancel does not reach the call', NET, 'if (cancellation != null) cancellation.attach(call);', ''),
    ('corrupt journal accepted', DL, 'if (lines.length == 0 || !lines[0].equals("mp4-jobs-v1")) throw new IOException("corrupt download journal");', 'if (lines.length == 0 || !lines[0].equals("mp4-jobs-v1")) return;'),
]


def main():
    cache = sys.argv[1] if len(sys.argv) > 1 else None
    run = ['bash', os.path.join(HERE, 'run.sh')] + ([cache] if cache else [])
    env = dict(os.environ, DL_JAVA_OPTS='-Ddl.large=67108864')
    base = subprocess.run(run, env=dict(env, APP_SRC=APP), capture_output=True, text=True)
    if base.returncode != 0:
        print(base.stdout[-3000:], base.stderr[-3000:])
        print('RESULT dl-mutations FAIL (unmodified tree fails)')
        return 1
    survived = 0
    for name, rel, old, new in MUTATIONS:
        work = tempfile.mkdtemp()
        try:
            shutil.copytree(APP, os.path.join(work, 'java'))
            path = os.path.join(work, 'java', rel)
            text = open(path, encoding='utf-8').read()
            if text.count(old) != 1:
                print('STALE    %s (fragment found %d times)' % (name, text.count(old)))
                survived += 1
                continue
            open(path, 'w', encoding='utf-8').write(text.replace(old, new))
            try:
                r = subprocess.run(run, env=dict(env, APP_SRC=os.path.join(work, 'java')), capture_output=True, text=True, timeout=600)
                code = r.returncode
            except subprocess.TimeoutExpired:
                code = 'timeout'
            if code == 0:
                print('SURVIVED %s' % name)
                survived += 1
            else:
                print('killed   %s' % name)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    total = len(MUTATIONS)
    print('RESULT dl-mutations %s %d/%d killed' % ('PASS' if survived == 0 else 'FAIL', total - survived, total))
    return 0 if survived == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
