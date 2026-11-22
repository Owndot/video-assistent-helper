#!/usr/bin/env python3
"""P4-Abnahme der FERTIG SIGNIERTEN Test-APK in Termux (ohne zipalign/llvm-readelf). Fail-closed: nur
"P4-TERMUX-VERIFY PASS" mit Exit 0 gilt, alles andere ist REJECT (Exit 1).

Aufruf:  python3 p4-termux-verify.py SIGNED.apk PIN_CERT_SHA256 VERSION_CODE SOURCE_SHA
         python3 p4-termux-verify.py --selftest
Werkzeuge im PATH: apksigner, aapt2 (Termux: pkg install apksigner aapt2).
Prüft dasselbe wie verify-p4/p4-local-verify: Paket, versionCode, Name *-p4test, Bezeichnung, ABIs, targetSdk 34,
nicht debugfähig/testOnly, genau die 12 Berechtigungen, keine eigene, allowBackup=false, dataExtractionRules,
v2+v3, genau ein Signierer = Festlegung (nie ec17fab1…), DN "durable", genau die beiden .so, Quell-Commit;
16 KB: jede .so unkomprimiert und auf 16384 Byte ausgerichtet, jedes ELF-PT_LOAD p_align >= 0x4000."""
import hashlib, re, struct, subprocess, sys, zipfile

PKG, LABEL = 'de.evp.helfer.p4test', 'P4 TEST Helfer'
DN = 'CN=Helper P4 TEST ONLY - durable'
BAD = 'ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5'
LIBS = ['lib/arm64-v8a/libkadri_pairing_poc.so', 'lib/x86_64/libkadri_pairing_poc.so']
PERMS = sorted([
    "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'",
    "uses-permission: name='android.permission.FOREGROUND_SERVICE'",
    "uses-permission: name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE'",
    "uses-permission: name='android.permission.INTERNET'",
    "uses-permission: name='android.permission.POST_NOTIFICATIONS'",
    "uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='28'",
    "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'",
    "uses-permission: name='android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'",
    "uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'",
    "uses-permission: name='android.permission.WAKE_LOCK'",
    "uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'",
    "uses-permission: name='android.permission.WRITE_SETTINGS'"])


class Reject(Exception):
    pass


def need(cond, why):
    if not cond:
        raise Reject(why)


def data_offset(path, info):
    """Beginn der Nutzdaten eines Eintrags (lokaler Kopf: 30 Byte + Name + Extra)."""
    with open(path, 'rb') as f:
        f.seek(info.header_offset)
        h = f.read(30)
    need(len(h) == 30 and h[:4] == b'PK\x03\x04', 'lokaler ZIP-Kopf fehlt: ' + info.filename)
    n, e = struct.unpack('<HH', h[26:30])
    return info.header_offset + 30 + n + e


def elf_load_aligns(data):
    """p_align aller PT_LOAD-Segmente (ELF32/64, little endian)."""
    need(data[:4] == b'\x7fELF', 'keine ELF-Datei')
    need(data[5] == 1, 'ELF nicht little endian')
    if data[4] == 2:
        phoff, = struct.unpack_from('<Q', data, 0x20); phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
        rec = lambda o: (struct.unpack_from('<I', data, o)[0], struct.unpack_from('<Q', data, o + 0x30)[0])
    elif data[4] == 1:
        phoff, = struct.unpack_from('<I', data, 0x1C); phentsize, phnum = struct.unpack_from('<HH', data, 0x2A)
        rec = lambda o: (struct.unpack_from('<I', data, o)[0], struct.unpack_from('<I', data, o + 0x1C)[0])
    else:
        raise Reject('unbekannte ELF-Klasse')
    need(phnum > 0 and phoff + phentsize * phnum <= len(data), 'ELF-Programmköpfe ungültig')
    out = [rec(phoff + i * phentsize)[1] for i in range(phnum) if rec(phoff + i * phentsize)[0] == 1]
    need(out, 'kein PT_LOAD-Segment')
    return out


def check_native(path):
    z = zipfile.ZipFile(path)
    names = z.namelist()
    sos = sorted(n for n in names if n.endswith('.so'))
    need(sos == LIBS, 'unerwartete native Bibliotheken: %s' % sos)
    need(not [n for n in names if 'testinit' in n.lower()], 'Testbibliothek in der APK')
    for n in LIBS:
        i = z.getinfo(n)
        need(i.compress_type == zipfile.ZIP_STORED, '%s ist komprimiert (16 KB nicht möglich)' % n)
        off = data_offset(path, i)
        need(off % 16384 == 0, '%s nicht auf 16 KB ausgerichtet (Offset %d)' % (n, off))
        al = elf_load_aligns(z.read(n))
        need(all(a >= 0x4000 for a in al), '%s: PT_LOAD-Ausrichtung %s < 0x4000' % (n, [hex(a) for a in al]))
    return z


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    need(p.returncode == 0, '%s fehlgeschlagen: %s' % (cmd[0], (p.stderr or p.stdout).strip()[:300]))
    return p.stdout


def verify(apk, pin, code, src):
    need(re.fullmatch(r'[0-9a-f]{64}', pin or ''), 'Festlegung ist kein SHA-256')
    need(pin != BAD, 'kompromittierter alter Signierer als Festlegung')
    need(re.fullmatch(r'[0-9]+', code or ''), 'versionCode ungültig')
    need(re.fullmatch(r'[0-9a-f]{40}', src or ''), 'Quell-Commit ungültig')
    b = run(['aapt2', 'dump', 'badging', apk])
    need(re.search(r"^package: name='%s' versionCode='%s' versionName='[^']*-p4test'" % (re.escape(PKG), code), b, re.M),
         'falsches Paket, falscher versionCode oder Name')
    need("application-label:'%s'" % LABEL in b, 'unerwartete Bezeichnung')
    need("native-code: 'arm64-v8a' 'x86_64'" in b, 'unerwartete ABIs')
    need(re.search(r"^targetSdkVersion:'34'", b, re.M), 'targetSdk ist nicht 34')
    need('application-debuggable' not in b, 'debugfähig')
    perms = sorted(l for l in b.splitlines() if l.startswith('uses-permission:'))
    need(perms == PERMS, 'Berechtigungen weichen ab: %s' % sorted(set(perms) ^ set(PERMS)))
    need(not re.search(r'^permission:', b, re.M), 'eigene Berechtigung deklariert')
    m = run(['aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk])
    need('testOnly' not in m, 'testOnly')
    need(re.search(r'allowBackup.*=false', m), 'allowBackup nicht false')
    need('dataExtractionRules' in m, 'dataExtractionRules fehlt')
    need('InitializationProvider' not in m, 'androidx-Startup-Provider vorhanden')
    c = run(['apksigner', 'verify', '--verbose', '--print-certs', apk])
    need('Verified using v2 scheme (APK Signature Scheme v2): true' in c, 'keine v2-Signatur')
    need('Verified using v3 scheme (APK Signature Scheme v3): true' in c, 'keine v3-Signatur')
    need(len(re.findall(r'^Signer #\d+ certificate DN', c, re.M)) == 1, 'nicht genau ein Signierer')
    need(re.search(r'^Signer #1 certificate DN: %s$' % re.escape(DN), c, re.M), 'Signierer-DN ist nicht "durable"')
    got = (re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', c, re.M) or [None, ''])[1]
    need(got == pin, 'Signierer %s != Festlegung %s' % (got, pin))
    z = check_native(apk)
    need(src in z.read('META-INF/version-control-info.textproto').decode('utf-8', 'replace'), 'nicht aus %s gebaut' % src)
    h = hashlib.sha256(open(apk, 'rb').read()).hexdigest()
    return got, h


def selftest():
    import io, os, tempfile
    def elf64(align):   # minimaler ELF64-Kopf mit einem PT_LOAD
        hdr = bytearray(64); hdr[:4] = b'\x7fELF'; hdr[4] = 2; hdr[5] = 1
        struct.pack_into('<Q', hdr, 0x20, 64); struct.pack_into('<HH', hdr, 0x36, 56, 1)
        ph = bytearray(56); struct.pack_into('<I', ph, 0, 1); struct.pack_into('<Q', ph, 0x30, align)
        return bytes(hdr + ph)
    def apk(path, align_ok, elf_align, stored=True):
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('AndroidManifest.xml', b'x')
            for n in LIBS:
                zi = zipfile.ZipInfo(n); zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                # Extra-Feld so polstern, dass die Daten (nicht) auf 16 KB liegen
                pos = z.fp.tell(); base = pos + 30 + len(n.encode())
                pad = (-base) % 16384 if align_ok else ((-base) % 16384) + 8
                if pad and pad < 4: pad += 16384
                zi.extra = (struct.pack('<HH', 0xD935, pad - 4) + b'\0' * (pad - 4)) if pad else b''
                z.writestr(zi, elf64(elf_align))
    d = tempfile.mkdtemp(); ok = 0
    cases = [('ausgerichtet, 0x4000', True, 0x4000, True, True), ('nicht ausgerichtet', False, 0x4000, True, False),
             ('PT_LOAD 0x1000', True, 0x1000, True, False), ('komprimiert', True, 0x4000, False, False)]
    for name, al, ea, st, want in cases:
        p = os.path.join(d, name.replace(' ', '_') + '.apk'); apk(p, al, ea, st)
        try: check_native(p); res = True
        except Reject: res = False
        print('%s selftest: %s -> %s' % ('PASS' if res == want else 'FAIL', name, 'akzeptiert' if res else 'abgelehnt'))
        ok += res == want
    print('SELFTEST %s %d/%d' % ('PASS' if ok == len(cases) else 'FAIL', ok, len(cases)))
    return 0 if ok == len(cases) else 1


if __name__ == '__main__':
    if sys.argv[1:] == ['--selftest']:
        sys.exit(selftest())
    if len(sys.argv) == 3 and sys.argv[1] == '--native-only':   # Vorprüfung einer (auch unsignierten) APK
        try: check_native(sys.argv[2]); print('P4-TERMUX-NATIVE PASS (16 KB, PT_LOAD >= 0x4000, genau die beiden .so)')
        except Reject as e: print('P4-TERMUX-NATIVE REJECT: %s' % e); sys.exit(1)
        sys.exit(0)
    if len(sys.argv) != 5:
        print(__doc__); sys.exit(2)
    try:
        cert, h = verify(*sys.argv[1:])
        print('P4-TERMUX-VERIFY PASS package=%s versionCode=%s cert_sha256=%s apk_sha256=%s' % (PKG, sys.argv[3], cert, h))
    except Reject as e:
        print('P4-TERMUX-VERIFY REJECT: %s' % e); sys.exit(1)
