#!/usr/bin/env python3
"""Independent cross-check of the RFC 9382 P-256 vectors.

Test tooling only, never product code: textbook affine P-256 arithmetic with
Python integers (not constant time) plus hashlib/hmac. It shares no code with
pakery or snow. It recomputes pA, pB, K (from both sides), the transcript TT
(8-byte little-endian length prefixes), Hash(TT), Ke/Ka, the HKDF confirmation
keys and both confirmation MACs, and regenerates M and N with the seed
procedure of RFC 9382 section 6 from the curve OID.
"""
import hashlib, hmac, json, sys

p = 2**256 - 2**224 + 2**192 + 2**96 - 1
a = p - 3
b = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
n = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)


def add(P, Q):
    if P is None: return Q
    if Q is None: return P
    if P[0] == Q[0] and (P[1] + Q[1]) % p == 0: return None
    if P == Q: l = (3 * P[0] * P[0] + a) * pow(2 * P[1], -1, p) % p
    else: l = (Q[1] - P[1]) * pow(Q[0] - P[0], -1, p) % p
    x = (l * l - P[0] - Q[0]) % p
    return (x, (l * (P[0] - x) - P[1]) % p)


def mul(k, P):
    R = None
    while k:
        if k & 1: R = add(R, P)
        P = add(P, P); k >>= 1
    return R


def neg(P): return (P[0], (-P[1]) % p)


def on_curve(P): return (P[1] ** 2 - (P[0] ** 3 + a * P[0] + b)) % p == 0


def decompress(s):
    if len(s) != 33 or s[0] not in (2, 3): raise ValueError
    x = int.from_bytes(s[1:], 'big')
    if x >= p: raise ValueError
    y2 = (x ** 3 + a * x + b) % p
    y = pow(y2, (p + 1) // 4, p)
    if y * y % p != y2: raise ValueError
    if y & 1 != s[0] & 1: y = p - y
    return (x, y)


def uncompressed(P): return b'\x04' + P[0].to_bytes(32, 'big') + P[1].to_bytes(32, 'big')


def decode(h):
    s = bytes.fromhex(h)
    if s[0] == 4:
        P = (int.from_bytes(s[1:33], 'big'), int.from_bytes(s[33:], 'big'))
        assert on_curve(P)
        return P
    return decompress(s)


def gen_point(seed):
    # RFC 9382 section 6: iterated SHA-256 of the seed, first valid compressed point.
    def iterated(s, i):
        for _ in range(i): s = hashlib.sha256(s).digest()
        return s
    for i in range(1, 1000):
        k = -(-33 // 32)
        h = b''.join(iterated(seed, j) for j in range(i, i + k))[:33]
        h = bytes([h[0] & 1 | 2]) + h[1:]
        try:
            P = decompress(h)
        except ValueError:
            continue
        if mul(n, P) is None:
            return h.hex()
    raise RuntimeError


def lp(x): return len(x).to_bytes(8, 'little') + x


def hkdf(ikm, info, length):
    prk = hmac.new(b'\x00' * 32, ikm, hashlib.sha256).digest()
    out, t, c = b'', b'', 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([c]), hashlib.sha256).digest(); out += t; c += 1
    return out[:length]


def main(path):
    data = json.load(open(path))
    failures = 0

    def check(label, got, want):
        nonlocal failures
        ok = got == want
        failures += not ok
        print(('PASS ' if ok else 'FAIL ') + label)

    oid = b'1.2.840.10045.3.1.7'
    check('M regenerated from seed', gen_point(oid + b' point generation seed (M)'), data['M'])
    check('N regenerated from seed', gen_point(oid + b' point generation seed (N)'), data['N'])
    M, N = decode(data['M']), decode(data['N'])
    aad = bytes.fromhex(data['aad'])
    for i, v in enumerate(data['vectors'], 1):
        w, x, y = (int(v[k], 16) for k in ('w', 'x', 'y'))
        pA = add(mul(x, G), mul(w, M)); pB = add(mul(y, G), mul(w, N))
        check(f'v{i} pA', uncompressed(pA).hex(), v['pa'])
        check(f'v{i} pB', uncompressed(pB).hex(), v['pb'])
        kA = mul(x, add(pB, neg(mul(w, N)))); kB = mul(y, add(pA, neg(mul(w, M))))
        check(f'v{i} K (A side)', uncompressed(kA).hex(), v['k'])
        check(f'v{i} K (B side)', uncompressed(kB).hex(), v['k'])
        tt = b''.join(lp(z) for z in (v['A'].encode(), v['B'].encode(), uncompressed(pA), uncompressed(pB),
                                      uncompressed(kA), w.to_bytes(32, 'big')))
        h = hashlib.sha256(tt).digest()
        check(f'v{i} Hash(TT)', h.hex(), v['hash_tt'])
        check(f'v{i} Ke', h[:16].hex(), v['ke'])
        kc = hkdf(h[16:], b'ConfirmationKeys' + aad, 32)
        if 'kca' in v:
            check(f'v{i} KcA', kc[:16].hex(), v['kca']); check(f'v{i} KcB', kc[16:].hex(), v['kcb'])
        check(f'v{i} cA', hmac.new(kc[:16], tt, hashlib.sha256).hexdigest(), v['mac_a'])
        check(f'v{i} cB', hmac.new(kc[16:], tt, hashlib.sha256).hexdigest(), v['mac_b'])
    print('RESULT', 'FAIL' if failures else 'PASS', f'({failures} failures)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
