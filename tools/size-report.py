#!/usr/bin/env python3
"""NUR GRÖSSENTEST (Zweig tmp/okhttp-size-test). size-report.py LABEL=APK ... -> Größen je Kategorie und Unterschiede."""
import struct, sys, zipfile

def cat(n):
    if n.endswith('.dex'): return 'dex'
    if n.startswith('lib/'): return 'lib'
    if n == 'resources.arsc' or n.startswith('res/'): return 'res'
    if n.startswith('META-INF/'): return 'meta-inf'
    if n.startswith('kotlin/') or n.endswith('.kotlin_builtins'): return 'kotlin-res'
    if n == 'AndroidManifest.xml': return 'manifest'
    return 'other'

def info(path):
    z = zipfile.ZipFile(path)
    d = {'entries': {}, 'cat': {}, 'classes': 0, 'methods': 0, 'fields': 0}
    for i in z.infolist():
        d['entries'][i.filename] = (i.compress_size, i.file_size)
        c = d['cat'].setdefault(cat(i.filename), [0, 0, 0]); c[0] += i.compress_size; c[1] += i.file_size; c[2] += 1
        if i.filename.endswith('.dex'):
            h = z.read(i.filename)[:0x70]
            d['methods'] += struct.unpack_from('<I', h, 0x58)[0]
            d['fields'] += struct.unpack_from('<I', h, 0x50)[0]
            d['classes'] += struct.unpack_from('<I', h, 0x60)[0]
    import os
    d['size'] = os.path.getsize(path)
    return d

apks = [(a.split('=', 1)[0], info(a.split('=', 1)[1])) for a in sys.argv[1:]]
base = apks[0][1]
print('%-10s %11s %11s | %24s | %21s | %8s %8s %7s' % ('variant', 'apk_bytes', 'delta', 'dex (zip / raw)', 'lib (zip / raw)', 'classes', 'methods', 'entries'))
for label, d in apks:
    dx = d['cat'].get('dex', [0, 0, 0]); lb = d['cat'].get('lib', [0, 0, 0])
    print('%-10s %11d %+11d | %11d / %10d | %10d / %10d | %8d %8d %7d' % (label, d['size'], d['size'] - base['size'], dx[0], dx[1], lb[0], lb[1], d['classes'], d['methods'], len(d['entries'])))
print()
print('per category, compressed bytes in the APK (raw bytes in brackets):')
cats = sorted({c for _, d in apks for c in d['cat']})
for label, d in apks:
    print('  %-10s ' % label + '  '.join('%s=%d(%d)' % (c, d['cat'].get(c, [0, 0])[0], d['cat'].get(c, [0, 0])[1]) for c in cats))
for label, d in apks[1:]:
    added = sorted(set(d['entries']) - set(base['entries']))
    removed = sorted(set(base['entries']) - set(d['entries']))
    changed = sorted(n for n in set(d['entries']) & set(base['entries']) if d['entries'][n] != base['entries'][n])
    print('\n%s vs %s: %d added, %d removed, %d changed entries' % (label, apks[0][0], len(added), len(removed), len(changed)))
    for n in added: print('  + %-60s %9d (raw %d)' % (n, *d['entries'][n]))
    for n in removed: print('  - %-60s %9d' % (n, base['entries'][n][0]))
    for n in changed[:40]: print('  ~ %-60s %9d -> %9d (raw %d -> %d)' % (n, base['entries'][n][0], d['entries'][n][0], base['entries'][n][1], d['entries'][n][1]))
