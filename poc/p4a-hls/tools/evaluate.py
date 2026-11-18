#!/usr/bin/env python3
# P4a: wertet die Geräteergebnisse aller API-Stufen aus (results.jsonl + Ausgabedateien gegen die Fixture-Quellen).
#   python3 -I evaluate.py FIXTURES_DIR RESULTS_ROOT SUMMARY.md
# Exit 1, wenn der Hauptweg (media3 = Media3-Extractor + Mp4Muxer) bei irgendeiner Fixture/API nicht besteht oder der
# Abbruchtest scheitert. Alle anderen Wege werden nur protokolliert (Befunde für die Entscheidung).
import glob, json, os, subprocess, sys

fx, root, summary = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
rows, bad = [], []
for res in sorted(glob.glob(os.path.join(root, '*', 'files', 'files', 'p4a', 'results.jsonl'))):
    base = os.path.dirname(res)
    for line in open(res):
        r = json.loads(line)
        api = r.get('api')
        if r.get('test') == 'remux':
            out = os.path.join(base, 'out', r.get('file', ''))
            verdict, detail = 'ERROR', r.get('error', '')
            if 'error' not in r and os.path.exists(out):
                p = subprocess.run([sys.executable, '-I', os.path.join(here, 'analyze.py'), os.path.join(fx, r['fixture'], 'master.m3u8'), out,
                                    '--audio', 'opus' if 'opus' in r['fixture'] else 'aac'], capture_output=True, text=True)
                a = json.loads(p.stdout.split(' ', 2)[2]) if p.stdout.startswith(('PASS', 'FAIL')) else {'fails': ['analyzer: ' + p.stderr[-200:]]}
                dev = [k for k in r if k.endswith('_error')]
                decoded = 'v%s/%s a%s/%s' % (r.get('device_video_decoded'), r.get('device_video_samples'), r.get('device_audio_decoded'), r.get('device_audio_samples'))
                ok = not a['fails'] and not dev
                verdict = 'PASS' if ok else 'FAIL'
                o = a.get('output', {})
                detail = 'frames %s sync %s->%s ms dur %.3f; device %s %s' % (o.get('video_frames'), a.get('source', {}).get('sync_offset_ms'),
                    o.get('sync_offset_ms'), o.get('duration') or 0, decoded, '; '.join(a['fails'] + [k + '=' + str(r[k]) for k in dev]))
            if r.get('expected_rejection'):   # Richtlinie: Opus nur, wo nachweislich abspielbar (API >= 29)
                verdict, detail = 'PASS', 'abgelehnt wie vorgesehen: ' + r.get('error', '')
            rows.append((api, r['fixture'], r['path'], verdict, detail))
            if r['path'] == 'media3' and verdict != 'PASS': bad.append((api, r['fixture'], detail))
        elif r.get('test') == 'cancel':
            ok = r.get('outcome') == 'cancelled' and not r.get('output_left')
            rows.append((api, 'cancel', r['path'], 'PASS' if ok else 'FAIL', '%s after %s ms (%s ms after the cancel request), output left: %s' % (
                r.get('outcome'), r.get('ms_total'), r.get('ms_after_cancel'), r.get('output_left'))))
            if not ok: bad.append((api, 'cancel', r['path']))
        elif r.get('test') == 'memory':
            rows.append((api, 'memory', r['path'], 'INFO', 'samples v%s a%s, java probe %s, native delta %s KB, max heap %s MB %s' % (
                r.get('video_samples'), r.get('audio_samples'), r.get('heap_probe'), r.get('native_heap_delta_kb'), r.get('max_heap_mb'), r.get('error', ''))))
        elif r.get('test') == 'parser':
            rows.append((api, 'parser:' + r['case'], 'media3 vs strict', 'INFO', '%s | %s' % (r.get('media3'), r.get('strict'))))
with open(summary, 'w') as f:
    f.write('| API | Fixture/Test | Weg | Ergebnis | Details |\n|---|---|---|---|---|\n')
    for row in sorted(rows, key=lambda x: (str(x[1]), str(x[2]), str(x[0]))):
        f.write('| %s | %s | %s | %s | %s |\n' % row)
print(open(summary).read())
apis = sorted({r[0] for r in rows})
print('APIs with results:', apis)
if bad or len(apis) < 1:
    print('FAIL main path / cancel:', bad); sys.exit(1)
print('PASS: Media3 + Mp4Muxer complete, synchronous and device-decodable for every fixture and API; cancellation clean')
