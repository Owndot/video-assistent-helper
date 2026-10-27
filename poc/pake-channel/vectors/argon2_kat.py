#!/usr/bin/env python3
"""Generates password_scalar known answers independently of pakery/argon2-rs.

Uses argon2-cffi (binding to the reference C implementation libargon2) and
Python integers for the reduction. Test tooling only.
Profile (README "Argon2-Profil"): Argon2id v0x13, salt = 16 zero bytes,
m = 8192 KiB, t = 2, p = 1, no secret, no AD, 64-byte output; the output is
a big-endian integer reduced modulo the P-256 group order n.
"""
import json, sys
from importlib.metadata import version
from argon2.low_level import hash_secret_raw, Type

N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
codes = ["40719283", "00000000", "99999999", "12345678"]
out = []
for c in codes:
    h = hash_secret_raw(c.encode('ascii'), b'\x00' * 16, time_cost=2, memory_cost=8192, parallelism=1,
                        hash_len=64, type=Type.ID, version=19)
    out.append({"code": c, "argon2id_output": h.hex(), "scalar_be": format(int.from_bytes(h, 'big') % N, '064x')})
json.dump({"source": f"argon2-cffi {version('argon2-cffi')} / argon2-cffi-bindings {version('argon2-cffi-bindings')} (libargon2 reference C)",
           "profile": {"type": "argon2id", "version": "0x13", "salt_hex": "00" * 16, "m_kib": 8192, "t": 2, "p": 1,
                       "output_len": 64, "input": "8 ASCII digits", "reduction": "big-endian 512-bit integer mod P-256 group order"},
           "vectors": out}, open(sys.argv[1], 'w'), indent=1)
