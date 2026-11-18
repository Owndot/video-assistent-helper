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
    # P1 (Codex-Folgereview): jeder Auftrag endet, Freigaben laufen trotz Aufräumfehlern, Ursache bleibt vorn
    ('journal RuntimeException leaves the job reserved', DL, '} catch (IOException | RuntimeException e) {          // P1: auch eine RuntimeException darf nichts reserviert lassen', '} catch (IOException e) {'),
    ('response close failure skips the end of the job', DL, 'Throwable c1 = quietly(response == null ? null : response::close);', 'if (response != null) response.close(); Throwable c1 = null;'),
    ('abort failure skips the end of the job', DL, 'Throwable c2 = quietly(target == null ? null : target::abort);', 'if (target != null) target.abort(); Throwable c2 = null;'),
    ('wake lock failure escapes the slot release', 'de/evp/helfer/dl/JobSlots.java', 'try { activity.active(any); }\n        catch (RuntimeException e) { lastActivityFailure = (any ? "acquire " : "release ") + e.getClass().getSimpleName(); }', 'activity.active(any);'),
    ('finish listener failure escapes the worker', DL, 'try { listener.finished(job.id, job.title, state.name().toLowerCase(Locale.ROOT), reason, saved, state == State.DONE ? job.bytes : -1); }\n        catch (RuntimeException e) { secondary(job, "listener", e); }', 'listener.finished(job.id, job.title, state.name().toLowerCase(Locale.ROOT), reason, saved, state == State.DONE ? job.bytes : -1);'),
    ('failure after publish turns a published file into a failure', DL, 'try { job.ref = published.ref(); } catch (RuntimeException e) { secondary(job, "ref", e); }', 'job.ref = published.ref();'),
    ('Error skips the end of the job', DL, '            fatal = e;\n            if (state != State.DONE) { reason = "internal"; job.cause = where(e); }', '            if (true) throw e;'),
    ('secondary cleanup failure masks the primary cause', DL, 'job.cause = job.cause.isEmpty() ? "cleanup " + note : job.cause + "; cleanup " + note;', 'job.cause = "cleanup " + note;'),
    # P4c-3 (Review B): Fehlerklassen unterscheidbar – Ablage, Anzeige und Netz nicht pauschal "network"
    ('commit RuntimeException reported as network', DL, 'try { out.close(); saved = target.commit(); } catch (IOException | RuntimeException e) { throw new Fail("storage", e); }', 'try { out.close(); saved = target.commit(); } catch (IOException e) { throw new Fail("storage", e); }'),
    ('listener failure reported as network', DL, 'catch (RuntimeException e) { throw new Fail("internal", e, true); }', 'catch (RuntimeException e) { throw new Fail("network", e, true); }'),
    ('unexpected runtime failure reported as network', DL, '            reason = "internal";\n            job.cause = where(e);', '            reason = "network";\n            job.cause = where(e);'),
    # P4c-3 (P2): MP4-Quelle nur für dieselbe Datei, nie nach Abbruch
    ('resume from vault ignores a changed file', DL, 'if (job.expectTotal >= 0 && (total != job.expectTotal || etag == null || !etag.equals(job.expectEtag))) throw new Fail("changed");', ''),
    ('vault kept after cancel', DL, '        forget(id);   // P2: nach ausdrücklichem Abbruch nie wieder automatisch fortsetzen\n', ''),
    ('vault kept after success', DL, 'Throwable v = quietly(() -> forget(job.id));', 'Throwable v = null;'),
    # P4c-3 (P1): Prozent nur aus exaktem Zähler/Nenner (kein Abschneiden, keine Schätzung)
    ('percent beyond the exact size', 'de/evp/helfer/dl/Progress.java', 'if (phase != Phase.FETCH || total <= 0 || bytes > total) return -1;', 'if (phase != Phase.FETCH || total <= 0) return -1;'),
    ('percent clipped at 100', 'de/evp/helfer/dl/Progress.java', 'if (phase != Phase.FETCH || total <= 0 || bytes > total) return -1;', 'if (phase != Phase.FETCH || total <= 0) return -1;\n        if (bytes > total) return 100;'),
    ('parts beyond all parts shown', 'de/evp/helfer/dl/Progress.java', 'public boolean partsKnown() { return phase == Phase.FETCH && of > 0 && done <= of; }', 'public boolean partsKnown() { return phase == Phase.FETCH && of > 0; }'),
    ('job slots never report idle', 'de/evp/helfer/dl/JobSlots.java', 'private void freed() { if (--used == 0) report(false); }', 'private void freed() { --used; }'),
    ('job slots report every acquire', 'de/evp/helfer/dl/JobSlots.java', 'private void occupied() { if (++used == 1) report(true); }', 'private void occupied() { ++used; report(true); }'),
    ('late listener not told about running job', 'de/evp/helfer/dl/JobSlots.java', 'if (used > 0) report(true);', ''),
    ('status not checked', DL, 'if (response.code() != 200) throw new Fail("http_" + response.code());', ''),
    ('content-length optional', DL, 'if (total <= 0) throw new Fail("no_length");', 'if (total <= 0) total = Long.MAX_VALUE;'),
    ('content type not checked', DL, 'if (!acceptedType(response.contentType())) throw new Fail("type");', ''),
    ('mp4 signature not checked', DL, 'if (h < 8 || !mp4Signature(head)) throw new Fail("not_mp4");', 'if (h < 8) throw new Fail("not_mp4");'),
    ('free space not checked before', DL, 'if (freeBytes() < total + SPACE_RESERVE_BYTES) throw new Fail("no_space");', ''),
    ('free space not rechecked', DL, 'if (freeBytes() < total - job.bytes + SPACE_RESERVE_BYTES / 2) throw new Fail("no_space");', ''),
    # Nicht als Probe: 'if (n < 0) throw new IOException("short body")' und der long_body-Test sind über OkHttp nicht
    # erreichbar (FixedLengthSource wirft bei vorzeitigem Ende ProtocolException und liefert genau Content-Length);
    # sie bleiben als zweite Absicherung. Vorzeitiges Ende prüfen 'truncated body …' und 'at most 3 resumes'.
    # Review P3: die folgenden vier Proben waren nicht übersetzbar und galten trotzdem als "erkannt"; jetzt gültig, gleiche Absicht
    ('write error resumed', DL, 'private static void write(OutputStream out, byte[] b, int n) throws Fail {\n        try { out.write(b, 0, n); } catch (IOException | RuntimeException e) { throw new Fail("storage", e); }',
     'private static void write(OutputStream out, byte[] b, int n) throws IOException {\n        out.write(b, 0, n);'),
    ('resume without strong ETag', DL, 'if (resumes >= MAX_RESUMES || etag == null) throw new Fail("network", interrupted, true);', 'if (resumes >= MAX_RESUMES) throw new Fail("network", interrupted, true);'),
    ('weak or invalid etag accepted', DL, 'return SafeHttpClient.isStrongEtag(etag) ? etag : null;', 'return etag;'),
    ('If-Range not sent', DL, 'response = http.get(finalUrl, job.bytes, total - 1, etag, total, callTimeoutMs, job.cancel);',
     'response = http.get(finalUrl, job.bytes, total - 1, null, total, callTimeoutMs, job.cancel);'),
    ('multipart 206 accepted', DL, 'if (type != null && type.trim().toLowerCase(Locale.ROOT).startsWith("multipart/")) throw new Fail("range_multipart");', ''),
    ('content-encoding unchecked', DL, 'if (!identityEncoding(response.contentEncoding())) throw new Fail("encoding");', ''),
    ('content-encoding unchecked on resume', DL, 'if (!identityEncoding(r.contentEncoding())) throw new Fail("encoding");', ''),
    ('stale idle connection reused', DL, 'this.http = http.withoutConnectionReuse();', 'this.http = http;'),
    ('storage permission not checked', DL, 'if (!store.writable()) throw new Fail("storage_permission");', ''),
    ('ref not journaled before writing', DL, 'try { saveJournal(); } catch (IOException e) { throw new Fail("storage", e); }', ''),
    ('published file restored as interrupted', DL, 'st = j.ref != null && published(j.ref, j.total) ? State.DONE : State.INTERRUPTED;', 'st = State.INTERRUPTED;'),
    ('unchecked restore as done', DL, 'try { return total > 0 && store.isPublished(ref, total); }', 'try { return total > 0 && (store.isPublished(ref, total) || true); }'),
    ('unlimited resumes', DL, 'resumes >= MAX_RESUMES || ', ''),
    ('206 not required', DL, 'if (r.code() != 206) throw new Fail(r.code() == 200 ? "range_ignored" : "range_http_" + r.code());', ''),
    ('content-range not compared', DL, 'if (s != start || e != total - 1 || t != total) throw new Fail("range_mismatch");', ''),
    ('range length not compared', DL, 'if (r.contentLength() != total - start) throw new Fail("range_length");', ''),
    ('changed etag ignored', DL, 'if (etag == null || !etag.equals(strongEtag(r.etag()))) throw new Fail("changed");', ''),
    ('duplicate start allowed', DL, 'if (known != null) return json(known);           // doppelter', 'if (known != null && known.state != State.RUNNING) return json(known);           // doppelter'),
    ('start before journal', DL, 'saveJournal();                                    // erst vermerkt', 'if (false) saveJournal();                                    // erst vermerkt'),
    ('parallel limit removed', DL, 'if (running.get() >= MAX_PARALLEL || !slots.tryAcquireWithConnection()) return busy(id);', 'slots.tryAcquireWithConnection();'),
    ('running survives restart', DL, 'if (st == State.RUNNING) {', 'if (false) {'),
    ('no cleanup on failure', DL, 'Throwable c2 = quietly(target == null ? null : target::abort);', 'Throwable c2 = null;'),
    ('cancel does not reach the call', NET, 'if (cancellation != null) cancellation.attach(call);', ''),
    ('corrupt journal accepted', DL, 'if (fields < 0) throw new IOException("corrupt download journal");', 'if (fields < 0) return;'),
    # P4c: automatische Wiederaufnahme (Pause, Netz, Abbruch, endgültige Fehler)
    ('resume without pause', DL, 'pause(job, resumes);       // P4c', '// P4c'),
    ('403 treated as transient', 'de/evp/helfer/dl/Retry.java', 'return code == 408 || code == 429', 'return code == 403 || code == 408 || code == 429'),
    ('offline wait unbounded', 'de/evp/helfer/dl/Retry.java', 'if (now - offlineSince > MAX_OFFLINE_MS / scale) return false;', ''),
    ('cancel ignored while waiting', 'de/evp/helfer/dl/Retry.java', 'if (stop.getAsBoolean()) return false;', ''),
    ('offline treated as online', 'de/evp/helfer/dl/Retry.java', 'if (!online()) {', 'if (false) {'),
    ('transient 5xx on resume not retried', DL, 'if (Retry.transientStatus(response.code())) continue;', ''),
    ('first request not retried', DL, 'if (!Retry.transientStatus(r.code()) || attempt >= MAX_RESUMES) return r;', 'if (true) return r;'),
]


sys.path.insert(0, os.path.dirname(HERE))
import mutation_verdict as mv  # noqa: E402

# "erkannt" nur, wenn eine Prüfung sichtbar fehlschlug (TestDirectDownloads bzw. TestProgress)
KILLED = [r'^FAIL ', r'dl tests: \d+ passed, [1-9]\d* failed', r'RESULT passed=\d+ failed=[1-9]']


def main():
    cache = sys.argv[1] if len(sys.argv) > 1 else None
    run = ['bash', os.path.join(HERE, 'run.sh')] + ([cache] if cache else [])
    env = dict(os.environ, DL_JAVA_OPTS='-Ddl.large=67108864')
    base = subprocess.run(run, env=dict(env, APP_SRC=APP), capture_output=True, text=True)
    if base.returncode != 0:
        print(base.stdout[-3000:], base.stderr[-3000:])
        print('RESULT dl-mutations FAIL (unmodified tree fails)')
        return 1
    tally = mv.Tally('dl-mutations')
    for name, rel, old, new in MUTATIONS:
        work = tempfile.mkdtemp()
        try:
            shutil.copytree(APP, os.path.join(work, 'java'))
            path = os.path.join(work, 'java', rel)
            text = open(path, encoding='utf-8').read()
            if text.count(old) != 1:
                tally.add('%s (fragment found %d times)' % (name, text.count(old)), 'STALE')
                continue
            open(path, 'w', encoding='utf-8').write(text.replace(old, new))
            v, out = mv.run(run, dict(env, APP_SRC=os.path.join(work, 'java')), 600, KILLED)
            tally.add(name, v, out)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    return tally.finish()


if __name__ == '__main__':
    sys.exit(main())
