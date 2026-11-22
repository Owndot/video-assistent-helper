#!/usr/bin/env python3
# S3b: Mutationsproben für den DASH-Koordinator (de.evp.helfer.dashdl), Fmp4Inspect und die DASH-Kanalbefehle. Jede
# abgeschwächte Kopie muss die JVM-Tests rot machen (strenge Bewertung: tests/java/mutation_verdict.py).
#   python3 -I tests/java/dashdl/mutation.py FIXTURES_DIR [CACHE_DIR]
import os, shutil, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, '..', '..', '..'); src = os.path.join(root, 'app', 'src', 'main', 'java')
E = 'de/evp/helfer/dashdl/DashEngine.java'
I = 'de/evp/helfer/dashdl/Fmp4Inspect.java'
C = 'de/evp/helfer/ControlCommands.java'
M = [
    # Fingerabdruck und Sitzungsbindung
    (E, 'if (!src.fp.equals(fp)) return planFail("manifest_mismatch", phase);', ''),
    (E, 'if (!src.fp.equals(p.fp)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");', ''),
    (E, '|| !Arrays.equals(plan.peer, peer) ', ''),
    (E, ' || !plan.bind.equals(bind)) return reject(id, "plan_stale");', ') return reject(id, "plan_stale");'),
    (E, 'plan.expires <= clock.nowMs() || ', ''),
    (E, '            plans.remove(planId);\n            running.incrementAndGet();\n            if (job == null) {', '            running.incrementAndGet();\n            if (job == null) {'),
    (E, 'if (fp == null || !fp.matches("[0-9a-f]{64}")) return planFail("fingerprint_missing", null);', ''),
    # Profil-/Plangrenzen
    (E, 'if (p.audio.isEmpty()) throw new Stop(HlsJournal.State.FAILED, "dash_no_audio");', ''),
    (E, 'if (res > HlsPlan.MAX_PLAN_SEGMENTS + 2 || o.estimatedBytes > HlsPlan.MAX_MEDIA_BYTES) continue;', ''),
    # Prüfung der Ressourcen
    (E, '            Fmp4Inspect.Info vInfo = inspectInit(job, \'v\', "video", c.video.rep.codecs);\n', '            Fmp4Inspect.Info vInfo = new Fmp4Inspect.Info(12800, 0);\n'),
    (E, '            try { Fmp4Inspect.segment(part); }\n            catch (DashException e) { part.delete(); throw new Stop(HlsJournal.State.FAILED, e.reason); }', '            try { } catch (RuntimeException e) { }'),
    # Wiederaufnahme, Journal, Veröffentlichung, Abbruch
    (E, 'try { ok = fetch.unchanged(url, known.length, known.etag); }', 'try { ok = true; }'),
    (E, '                job.journal.state = HlsJournal.State.PUBLISHING;\n', ''),
    (E, 'if (j.cancelRequested && j.state != HlsJournal.State.DONE && j.state != HlsJournal.State.CANCELLED) {', 'if (false) {'),
    (E, 'boolean published = j.ref != null && j.total > 0 && isPublished(j.ref, j.total);', 'boolean published = j.ref != null;'),
    (E, '                    throw new Stop(HlsJournal.State.FAILED, "remux_io");', '                    throw e;'),
    (E, 'if (c.estimate > 0 && free() < 2 * c.estimate + SPACE_RESERVE_BYTES) throw new Stop(HlsJournal.State.FAILED, "no_space");', ''),
    (E, '|| r.startsWith("http_5") || r.equals("http_403") || r.equals("http_404") || r.equals("http_410") || r.equals("http_429")', '|| r.startsWith("http_5")'),
    # Fmp4Inspect
    (I, '            if (ENCRYPTION.contains(e.type)) throw new DashException("encrypted");   // encv/enca\n', ''),
    (I, 'if (!got.equals(c.substring(5).toLowerCase(Locale.ROOT))) throw new DashException("codec_mismatch");', ''),
    (I, 'if (!handler.equals(kind.equals("video") ? "vide" : "soun")) throw new DashException("init_track");', ''),
    (I, 'if (fourcc(g).equals("seig")) throw new DashException("encrypted");', ''),
    (I, 'if (PIFF.contains(sb.toString())) throw new DashException("encrypted");', ''),
    (I, '            if (!allowed.contains(b.type)) throw new DashException(unknown);', ''),
    (I, 'trexDuration(f, one(boxes(f, mvex.body(), mvex.end(), budget), "trex"))', '0L'),
    (I, 'for (Box b : vk) if (ENCRYPTION.contains(b.type)) throw new DashException("encrypted");', ''),
    (I, 'if (oti(f, esds) != 0x40) throw new DashException("codec_mismatch");', 'oti(f, esds);'),
    (I, 'if (file.length() <= 0 || file.length() > MAX_INIT_BYTES) throw new DashException("init_size");', ''),
    (I, 'if (!moof || !mdat) throw new DashException("segment_box");', ''),
    # Fix 1 (P1 AAC): nur AAC-LC, AudioSpecificConfig geprüft
    (I, 'if (!c.equals("mp4a.40.2")) throw new DashException("codec_mismatch");   // nur AAC-LC', 'if (!c.startsWith("mp4a.40.")) throw new DashException("codec_mismatch");'),
    (I, '                aacLc(f, esds);\n', ''),
    (I, 'if (aot != 2) throw new DashException("codec_mismatch");', ''),
    (I, 'if (ch < 1 || ch > 7) throw new DashException("codec_mismatch");', ''),
    (I, 'if (r.read(5) != 5 || r.read(1) != 0) throw new DashException("codec_mismatch");', 'r.read(5); r.read(1);'),
    (I, 'while (r.left() > 0) if (r.read(1) != 0) throw new DashException("codec_mismatch");   // nur Füllbits 0', ''),
    (E, 'if (!AAC_LC.equals(o.audio.rep.codecs.trim())) continue;', ''),
    (E, 'if (out.isEmpty() && onlyHeAac) throw new Stop(HlsJournal.State.FAILED, "audio_codec");', ''),
    # Fix 1 (P1 Dauer): Vollständigkeit vor dem Zusammenbau, enge Ausgabeprüfung
    (E, "            checkDuration(job, 'v', vActual, c.video);\n", ''),
    (E, "            checkDuration(job, 'a', aActual, c.audio);\n", ''),
    (E, 'if (actual < planned - DURATION_TOLERANCE_SECONDS || ', 'if ('),
    (E, ' || actual > planned + outsidePeriod(t) + DURATION_TOLERANCE_SECONDS) {', ') {'),
    (E, 'remux.verify(out, r, vActual, aActual, DURATION_TOLERANCE_SECONDS + slack, ', 'remux.verify(out, r, vActual, aActual, Math.max(1.0, 4.0 + slack), '),
    (E, 'double slack = Math.max(outsidePeriod(c.video), outsidePeriod(c.audio));', 'double slack = 0;'),
    (I, 'if (count > 0 && def == 0) throw new DashException("segment_timing");', ''),
    (I, 'long def = (flags & 0x08) != 0 ? f.readInt() & 0xffffffffL : init.defaultDuration;', 'long def = init.defaultDuration;'),
    # Kanalbefehle
    (C, '                fp = p.hex(64);', '                fp = p.printable(80);'),
    (C, '                for (int k = 0; k < i; k++) if (out[k] == out[i]) throw new IllegalArgumentException("indices");', ''),
    (C, 'if (!"dlstart".equals(op) && !"hlsplan".equals(op) && !"dashplan".equals(op)) throw new IllegalArgumentException("context field");', 'if (!"dlstart".equals(op) && !"hlsplan".equals(op) && !op.startsWith("dash")) throw new IllegalArgumentException("context field");'),
    (C, 'if ((fp != null || played != null) && !"dashplan".equals(op)) throw new IllegalArgumentException("dash field");', ''),
    (C, '            if (dash == null || downloads == null || peer == null) return REJECTED;', '            if (downloads == null || peer == null) return REJECTED;'),
    (C, ' || (hls != null && hls.knows(r.id)))\n', ')\n'),
    (C, '            if (dash != null && dash.knows(r.id)) return "{\\"ok\\":false,\\"id\\":\\"" + r.id + "\\",\\"state\\":\\"rejected\\",\\"reason\\":\\"id_in_use\\"}";\n', ''),
    (C, 'boolean isDash = !isHls && dash != null && dash.knows(r.id);', 'boolean isDash = false;'),
]
sys.path.insert(0, os.path.join(here, '..'))
import mutation_verdict as mv  # noqa: E402
KILLED = [r'^FAIL ', r'RESULT passed=\d+ failed=[1-9]']
fx = sys.argv[1]; cache = sys.argv[2] if len(sys.argv) > 2 else ''
tally = mv.Tally('dashdl-mutations')
for f, a, b in M:
    name = f.split('/')[-1] + ': ' + a[:70].replace('\n', ' ')
    if a == b:
        continue   # Platzhalter
    with tempfile.TemporaryDirectory() as t:
        shutil.copytree(src, os.path.join(t, 'java'))
        p = os.path.join(t, 'java', f); s = open(p).read()
        if s.count(a) != 1:
            tally.add('%s (fragment found %d times)' % (name, s.count(a)), 'STALE'); continue
        open(p, 'w').write(s.replace(a, b))
        v, out = mv.run(['bash', os.path.join(here, 'run.sh'), fx] + ([cache] if cache else []), dict(os.environ, APP_SRC=os.path.join(t, 'java')), 600, KILLED)
        tally.add(name, v, out)
sys.exit(tally.finish())
