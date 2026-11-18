#!/usr/bin/env python3
"""Die Originalbelege von Lauf 44281951152 bleiben byte-gleich (Codex-NO-GO 1, Befund 8): gzip-Joblog, sein
unkomprimierter Inhalt und die wörtlichen Bewertungszeilen mit dem ursprünglichen Ergebnis DATA_LOSS.
Aufruf: python3 -I tests/bootscan/test_archive_integrity.py
"""
import gzip
import hashlib
import os
import sys

D = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), 'evidence', 'bootscan-diagnosis', 'run44281951152')
GZ = 'run44281951152-attempt1-job134724661171-api34.log.gz'
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


sha = lambda b: hashlib.sha256(b).hexdigest()
raw = open(os.path.join(D, GZ), 'rb').read()
check(sha(raw) == '84d1f31dc88748925b0c0398a7c65d47bfee5bbe8405171349d8a5568232c6f7', 'archived job log (gzip) unchanged')
check(sha(gzip.decompress(raw)) == '8a2f478138b940c13932592b6c54024bf8bfd3b290e95ded1cd40b4ab2d7b9c8', 'job log content unchanged (as returned by the GitHub API)')
lines = open(os.path.join(D, 'original-verdict-lines.txt'), 'rb').read()
check(sha(lines) == '9c5f3524f6bf5d10317497ac7ca7d4a784f144ce5601913c803fc4f2389440d0', 'original verdict lines unchanged')
check(lines.decode().splitlines()[-1] == 'BOOTSCAN_RESULT DATA_LOSS', 'original result stays DATA_LOSS')
print('ARCHIVE_INTEGRITY %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', 4, len(fails)))
sys.exit(1 if fails else 0)
