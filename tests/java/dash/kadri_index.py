#!/usr/bin/env python3
# S3a: erzeugt tests/java/dash/fixtures/kadri-index.tsv – was Kadris Erkennungsindex (src/038 dashSegmentIndex,
# dashMatchSegment, dashManifestSummary) zu jeder Adresse aus TestDash --dump-keys sagt. Läuft Kadris unveränderten
# Quelltext im echten DOMParser von Chromium; kein Netz. Die TSV wird eingecheckt; TestDash vergleicht sie mit dem Plan.
#   python3 -I kadri_index.py KADRI_ROOT FIXTURES_DIR KEYS.tsv OUT.tsv
import hashlib, os, subprocess, sys
from playwright.sync_api import sync_playwright

kadri, fx, keys, out = sys.argv[1:5]
src_path = os.path.join(kadri, 'src', '038-dash-manifest.js')
src = open(src_path, encoding='utf-8').read()
commit = subprocess.run(['git', '-C', kadri, 'rev-parse', 'HEAD'], capture_output=True, text=True, check=True).stdout.strip()
rows = [l.rstrip('\n').split('\t') for l in open(keys, encoding='utf-8') if l.strip() and not l.startswith('#')]
fixtures = []
for f, u, k in rows:
    if (f, u) not in fixtures: fixtures.append((f, u))
lines = ['# kadri=%s src038=%s – Kadris Erkennungsindex je Adresse: "-" kein Treffer, "rep,idx" Stück, "rep,init" Init; addr: Kadri-Adressierung je Darstellung'
         % (commit[:7], hashlib.sha256(src.encode('utf-8')).hexdigest())]
with sync_playwright() as p:
    br = p.chromium.launch(executable_path=os.environ.get('KADRI_CHROMIUM') or None)
    pg = br.new_page()
    pg.set_content('<!doctype html><title>idx</title>')
    pg.add_script_tag(content=src + '''
window.__run = (t, u, keys) => { const parsed = dashParse(t); const ix = dashSegmentIndex(parsed, u); const sum = dashSummaryFromParsed(parsed);
  return { addr: sum.tracks.map((x) => x.addressing), m: keys.map((k) => { const r = dashMatchSegment(ix, dashUrlKey(k)); return !r ? '-' : r.init ? r.rep + ',init' : r.rep + ',' + r.idx; }) }; };''')
    for f, u in fixtures:
        ks = [r[2] for r in rows if r[0] == f and r[1] == u]
        res = pg.evaluate('([t, u, k]) => window.__run(t, u, k)', [open(os.path.join(fx, f), encoding='utf-8').read(), u, ks])
        for a in res['addr']: lines.append('addr\t%s\t%s' % (f, a))
        for k, m in zip(ks, res['m']): lines.append('%s\t%s\t%s\t%s' % (f, u, k, m))
    br.close()
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('%d Zeilen, Kadri %s' % (len(lines), commit[:7]))
