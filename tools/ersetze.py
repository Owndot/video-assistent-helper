#!/usr/bin/env python3
"""Hilfe zum Ändern: ersetzt Text in genau einem Teil unter src/ (findet den Teil selbst). Aufruf aus Python:
   from ersetze import rep; rep(alt, neu)   – bricht ab, wenn der Text nicht genau einmal vorkommt."""
import glob, os
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src')
def rep(a, b, count=1):
    hits = [(f, open(f, encoding='utf-8').read()) for f in sorted(glob.glob(os.path.join(SRC, '*.js')))]
    hits = [(f, s) for f, s in hits if a in s]
    n = sum(s.count(a) for _, s in hits)
    if n != count: raise SystemExit('Text %d× gefunden (erwartet %d): %r' % (n, count, a[:90]))
    for f, s in hits:
        open(f, 'w', encoding='utf-8').write(s.replace(a, b))
    return [os.path.basename(f) for f, _ in hits]
