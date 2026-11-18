#!/usr/bin/env python3
"""Rekonstruiert aus dem archivierten Job-Log von Lauf 44281951152 die Eingaben von tools/bootscan_verdict.py.
Aufruf: python3 -I reconstruct.py JOBLOG OUTDIR (JOBLOG unkomprimiert, z. B. <(gzip -dc …log.gz)). Nur lesend."""
import re, sys, os
src, out = sys.argv[1], sys.argv[2]
f = {k: [] for k in ('logcat', 'instr-during', 'instr-after', 'instr-recheck', 'foreign-before', 'foreign-after')}
for l in open(src, encoding='utf-8', errors='replace').read().splitlines():
    # GitHub-Zeitstempel und Byte-Order-Marks an Chunk-Grenzen des Log-Downloads entfernen (keine Belegzeilen)
    l = re.sub(r'^\ufeff?(\d{4}-\d\d-\d\dT[\d:.]+Z )?', '', l)
    if l.startswith('BOOTSCAN_LOG '):
        f['logcat'].append(l[len('BOOTSCAN_LOG '):])
    m = re.match(r'^BOOTSCAN_INSTR (instr-\w+) ?(.*)$', l)
    if m:
        f[m.group(1)].append(m.group(2))
    m = re.match(r'^BOOTSCAN_LIST (foreign-\w+) (.*)$', l)
    if m:
        f[m.group(1)].append(m.group(2))
for k, v in f.items():
    open(os.path.join(out, k + '.txt'), 'w').write('\n'.join(v) + '\n')
    print(k, len(v))
