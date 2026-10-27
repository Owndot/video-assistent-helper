#!/usr/bin/env python3
"""Checks core/Cargo.lock against a local RustSec advisory-db checkout.

Dependency-free stand-in for `cargo audit --no-fetch` (test tooling only):
  python3 -I tools/advisory_check.py core/Cargo.lock <advisory-db-dir>
Reports every advisory whose patched/unaffected ranges do not cover the
locked version. Informational advisories (unmaintained, unsound, notice)
are listed separately. Withdrawn advisories are ignored.
"""
import pathlib, re, sys, tomllib


def ver(s):
    core = s.split('-')[0].split('+')[0]
    parts = [int(x) for x in core.split('.')] + [0, 0, 0]
    return tuple(parts[:3]), '-' in s


def match(req, v):
    for clause in req.split(','):
        clause = clause.strip()
        m = re.fullmatch(r'(>=|<=|>|<|=|\^|~)?\s*([0-9][0-9A-Za-z.+-]*)', clause)
        if not m:
            raise ValueError(clause)
        op, (r, _) = m.group(1) or '^', ver(m.group(2))
        x, _ = v
        ok = {
            '>=': x >= r, '<=': x <= r, '>': x > r, '<': x < r, '=': x == r,
            '~': x >= r and x[:2] == r[:2],
        }.get(op)
        if op == '^':
            if r[0] > 0: ok = x >= r and x[0] == r[0]
            elif r[1] > 0: ok = x >= r and x[:2] == r[:2]
            else: ok = x == r
        if not ok:
            return False
    return True


def main(lock, db):
    pkgs = tomllib.loads(pathlib.Path(lock).read_text())['package']
    hits, info = [], []
    for p in pkgs:
        d = pathlib.Path(db) / 'crates' / p['name']
        if not d.is_dir():
            continue
        v = ver(p['version'])
        for f in sorted(d.glob('*.md')):
            text = f.read_text()
            front, body = text.split('```toml', 1)[1].split('```', 1)
            a = tomllib.loads(front)
            title = next((l[2:].strip() for l in body.splitlines() if l.startswith('# ')), '?')
            adv, rng = a['advisory'], a.get('versions', {})
            if adv.get('withdrawn'):
                continue
            covered = any(match(r, v) for r in rng.get('patched', []) + rng.get('unaffected', []))
            if covered:
                continue
            row = f"{p['name']} {p['version']}: {adv['id']} {adv.get('informational') or 'vulnerability'} — {title}"
            (info if adv.get('informational') else hits).append(row)
    print(f'{len(pkgs)} locked packages checked')
    for r in hits:
        print('VULNERABLE', r)
    for r in info:
        print('INFO', r)
    print('ADVISORY RESULT', 'FAIL' if hits else 'PASS')
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:3]))
