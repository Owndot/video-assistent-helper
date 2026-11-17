#!/usr/bin/env python3
"""Schreibt tools/delivery/lock.py byte-genau in den Inline-Block des Jobs "gate" eines Lieferworkflows.

Aufruf:  embed.py WORKFLOW
Der Block steht zwischen der Zeile "python3 -I - <<'PY'" und der Zeile "PY" des Schritts mit id: lock.
tests/delivery/test_lock.py prüft, dass beide übereinstimmen; dieses Werkzeug ist nur der Weg dorthin.
"""
import os
import re
import sys

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
path = sys.argv[1]
src = open(os.path.join(root, 'tools', 'delivery', 'lock.py'), encoding='utf-8').read()
text = open(path, encoding='utf-8').read()
m = re.search(r"(?m)^( *)python3 -I - <<'PY'\n(.*?)^\1PY\n", text, re.S)
if not m:
    sys.exit('no inline lock block in %s' % path)
ind = m.group(1)
body = ''.join((ind + l if l.strip() else '\n') if l.endswith('\n') else ind + l for l in src.splitlines(True))
text = text[:m.start(2)] + body + text[m.end(2):]
open(path, 'w', encoding='utf-8').write(text)
print('embedded %d lines into %s' % (src.count('\n'), path))
