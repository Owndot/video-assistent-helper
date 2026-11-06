#!/usr/bin/env python3
# P4b: Mutationsproben (App-Kern de.evp.helfer.hls) für Parser, URI-Auflösung, Plan, Abruf, Journal und Koordinator. Jede abgeschwächte Kopie muss die JVM-Tests rot machen.
#   python3 -I mutation.py FIXTURES_DIR [CACHE_DIR]
import os, shutil, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, '..', '..', '..'); src = os.path.join(root, 'app', 'src', 'main', 'java')
M = [
    ('de/evp/helfer/hls/HlsPlaylist.java', 'if (!"NONE".equals(enumerated(a, "METHOD")) || a.size() != 1) throw new HlsException("encrypted");', ''),
    ('de/evp/helfer/hls/HlsPlaylist.java', 'if (!endList) throw new HlsException("live");', ''),
    ('de/evp/helfer/hls/HlsPlaylist.java', 'REJECTED.put("EXT-X-DISCONTINUITY", "discontinuity");', ''),
    ('de/evp/helfer/hls/HlsPlaylist.java', 'throw new HlsException(MASTER_TAGS.contains(tag) ? "mixed-playlist" : "unknown-tag");', 'break;'),
    ('de/evp/helfer/hls/HlsPlaylist.java', 'if (body.length > MAX_MANIFEST_BYTES) throw new HlsException("manifest-size");', ''),
    ('de/evp/helfer/hls/HlsUri.java', 't.query = r.query != null ? r.query : b.query;', 't.query = b.query;'),
    ('de/evp/helfer/hls/HlsUri.java', '                    t.query = r.query;\n                }\n                t.authority', '                    t.query = r.query != null ? r.query : b.query;\n                }\n                t.authority'),
    ('de/evp/helfer/hls/HlsUri.java', 'if (!"http".equals(p.scheme) && !"https".equals(p.scheme)) throw new HlsException("uri-scheme");', ''),
    ('de/evp/helfer/hls/HlsPlan.java', 'if (n > MAX_PLAN_SEGMENTS) throw new HlsException("segments-limit");', ''),
    ('de/evp/helfer/hls/HlsPlan.java', 'if (Math.abs(video.totalSeconds - audio.totalSeconds) > tol) throw new HlsException("av-duration-mismatch");', ''),
    ('de/evp/helfer/hls/HlsFetch.java', 'if (total > maxBytes) throw new HlsException("segment-size");', ''),
    ('de/evp/helfer/hls/HlsFetch.java', 'return new Text(out.toByteArray(), r.url());', 'return new Text(out.toByteArray(), url);'),
    # P4b-3/5: Koordinator, Journal, Wiederaufnahme
    ('de/evp/helfer/hls/HlsEngine.java', 'try { ok = fetch.unchanged(url, known.length, known.etag); }', 'try { ok = true; }'),
    ('de/evp/helfer/hls/HlsEngine.java', 'seg.length() == known.length && known.sha256.equals(fileSha(seg));', 'seg.length() == known.length;'),
    ('de/evp/helfer/hls/HlsEngine.java', '            j.journal.cancelRequested = true;\n            try { j.journal.save(j.dir, hooks); }', '            try { j.journal.save(j.dir, hooks); }'),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (j.cancelRequested && j.state != HlsJournal.State.DONE && j.state != HlsJournal.State.CANCELLED) {', 'if (false) {'),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (n.endsWith(".seg") && j.resources.containsKey(n.substring(0, n.length() - 4)) && f.length() == j.resources.get(n.substring(0, n.length() - 4)).length) continue;', 'if (n.endsWith(".seg")) continue;'),
    ('de/evp/helfer/hls/HlsEngine.java', '|| !Arrays.equals(plan.peer, peer) ', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'plan.expires <= clock.nowMs() || ', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (!src.sha.equals(p.masterSha)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (c.plan.estimatedBytes > 0 && free() < 2 * c.plan.estimatedBytes + SPACE_RESERVE_BYTES) throw new Stop(HlsJournal.State.FAILED, "no_space");', ''),
    ('de/evp/helfer/hls/HlsEngine.java', '        if (free() < rest + sum + SPACE_RESERVE_BYTES / 2) throw new Stop(HlsJournal.State.FAILED, "no_space");', ''),
    ('de/evp/helfer/hls/HlsEngine.java', '            acquireRemuxer(job);\n', '            remuxer.tryAcquire();\n'),
    ('de/evp/helfer/hls/HlsEngine.java', 'public static final int FETCH_PER_JOB = 2;', 'public static final int FETCH_PER_JOB = 3;'),
    ('de/evp/helfer/hls/HlsEngine.java', 'boolean published = j.ref != null && j.total > 0 && isPublished(j.ref, j.total);', 'boolean published = j.ref != null;'),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (state != HlsJournal.State.INTERRUPTED) {      // fertig', 'if (state == HlsJournal.State.CANCELLED) {      // fertig'),
    ('de/evp/helfer/hls/HlsEngine.java', '            else if (job.stopRequested) { state = HlsJournal.State.INTERRUPTED; reason = "stopped"; }', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'plans.remove(planId);              // ein Plan', '// ein Plan'),
    ('de/evp/helfer/hls/HlsEngine.java', 'while (mine.size() >= MAX_PLANS_PER_PEER) plans.remove(mine.remove(0));', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (!store.writable()) throw new Stop(HlsJournal.State.FAILED, "storage_permission");', ''),
    ('de/evp/helfer/hls/HlsEngine.java', '|| r.equals("http_403") || r.equals("http_404")', ''),
    ('de/evp/helfer/hls/HlsJournal.java', 'if (sp < 0 || !crc(line.substring(0, sp)).equals(line.substring(sp + 1))) throw new IOException("log checksum");', 'if (sp < 0) throw new IOException("log checksum");'),
    ('de/evp/helfer/hls/HlsJournal.java', 'if (nl >= raw.length) return start;', 'if (nl >= raw.length) throw new IOException("torn");'),
    ('de/evp/helfer/hls/HlsJournal.java', 'if (etag != null && !de.evp.helfer.net.SafeHttpClient.isStrongEtag(etag)) throw new IOException("log etag");', ''),
    ('de/evp/helfer/hls/HlsFetch.java', '            if (r.code() != 206) return false;', ''),
    ('de/evp/helfer/hls/HlsFetch.java', '            if (Long.parseLong(m.group(1)) != length) return false;', ''),
    ('de/evp/helfer/hls/HlsFetch.java', 'return new Fetched(total, HlsPlan.hex(sha.digest()), SafeHttpClient.isStrongEtag(etag) ? etag : null);', 'return new Fetched(total, HlsPlan.hex(sha.digest()), etag);'),
    # P4c: automatische Wiederaufnahme
    ('de/evp/helfer/hls/HlsEngine.java', 'if (o.state != HlsJournal.State.INTERRUPTED || !de.evp.helfer.dl.Retry.transientReason(o.reason)', 'if (true || !de.evp.helfer.dl.Retry.transientReason(o.reason)'),
    ('de/evp/helfer/hls/HlsEngine.java', 'attempt = reached > best ? 1 : attempt + 1;', 'attempt = 1;'),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (job.userCancelled()) { o = new Outcome(HlsJournal.State.CANCELLED, "", null); break; }', ''),
    ('de/evp/helfer/hls/HlsEngine.java', 'if (job.stopRequested) { o = new Outcome(HlsJournal.State.INTERRUPTED, "stopped", null); break; }', ''),
    ('de/evp/helfer/dl/Retry.java', 'case "io": case "network":', 'case "http_403": case "io": case "network":'),
    ('de/evp/helfer/dl/Retry.java', 'if (!online()) {', 'if (false) {'),
]
fx = sys.argv[1]; cache = sys.argv[2] if len(sys.argv) > 2 else ''
survived = []
for i, (f, a, b) in enumerate(M):
    with tempfile.TemporaryDirectory() as t:
        shutil.copytree(src, os.path.join(t, 'java'))
        p = os.path.join(t, 'java', f); s = open(p).read()
        assert s.count(a) == 1, ('anchor', f, a[:60])
        open(p, 'w').write(s.replace(a, b))
        r = subprocess.run(['bash', os.path.join(here, 'run.sh'), fx] + ([cache] if cache else []), env=dict(os.environ, APP_SRC=os.path.join(t, 'java')),
                           capture_output=True, text=True)
        killed = r.returncode != 0
        print(('killed   ' if killed else 'SURVIVED ') + f.split('/')[-1] + ': ' + a[:70].replace('\n', ' '))
        if not killed: survived.append(a)
if survived: print('FAIL: %d mutation(s) survived' % len(survived)); sys.exit(1)
print('PASS: all %d mutations killed' % len(M))
