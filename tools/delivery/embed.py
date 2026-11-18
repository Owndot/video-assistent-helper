#!/usr/bin/env python3
"""Schreibt tools/delivery/lock.py byte-genau in JEDEN Inline-Block mit dem Begrenzer LOCKPY eines Lieferworkflows
(Job "gate": python3 -I - <<'LOCKPY' … LOCKPY; Paketjob: cat > "$RUNNER_TEMP/delivery_lock.py" <<'LOCKPY' … LOCKPY).

Aufruf:  embed.py WORKFLOW
tests/delivery/test_lock.py prüft, dass alle Blöcke mit der Datei übereinstimmen; dieses Werkzeug ist nur der Weg dorthin.
"""
import os
import re
import sys

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
path = sys.argv[1]
src = open(os.path.join(root, 'tools', 'delivery', 'lock.py'), encoding='utf-8').read()
text = open(path, encoding='utf-8').read()
pat = re.compile(r"(?m)^( *)([^\n]*<<'LOCKPY')\n(.*?)^\1LOCKPY\n", re.S)
out, last, n = [], 0, 0
for m in pat.finditer(text):
    ind = m.group(1)
    body = ''.join((ind + l if l.strip() else '\n') for l in src.splitlines(True))
    out.append(text[last:m.start(3)] + body)
    last = m.end(3)
    n += 1
if n == 0:
    sys.exit('no LOCKPY block in %s' % path)
text = ''.join(out) + text[last:]
open(path, 'w', encoding='utf-8').write(text)
print('embedded %d lines into %d block(s) of %s' % (src.count('\n'), n, path))
