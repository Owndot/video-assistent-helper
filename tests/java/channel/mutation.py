#!/usr/bin/env python3
"""Mutationsproben für die Kanal-Prüfungen: jede Probe baut eine absichtlich geschwächte Kopie der App-Quellen;
ChannelChecks muss dann fehlschlagen. Überlebt eine Probe, ist die Prüfung zu schwach (Exit-Code 1).
Aufruf: tests/java/channel/mutation.py OUT   (OUT wie bei build.sh; der Rust-Build wird wiederverwendet)"""
import os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
APP = os.path.join(ROOT, 'app', 'src', 'main', 'java')
CC = 'de/evp/helfer/ControlCommands.java'
CS = 'de/evp/helfer/ChannelServer.java'
MUTANTS = [
    # P4b: HLS-Befehle
    ('HLS ohne Versionsprüfung', CC, 'if (!hasValue || value != 1.0 || bind == null) throw new IllegalArgumentException("hls fields");', 'if (bind == null) throw new IllegalArgumentException("hls fields");'),
    ('HLS ohne Kanal-Sitzung', CC, 'if (hls == null || downloads == null || peer == null) return REJECTED;', 'if (hls == null || downloads == null) return REJECTED;'),
    ('HLS übernimmt MP4-Kennung', CC, 'if (!downloads.status(r.id).contains("\\"state\\":\\"unknown\\"")) return', 'if (false) return'),
    ('dlcancel nicht zu HLS', CC, 'case "dlcancel": return isHls ? hls.cancel(r.id) : downloads.cancel(r.id);', 'case "dlcancel": return downloads.cancel(r.id);'),
    ('dlstart startet MP4 mit HLS-Kennung', CC, 'case "dlstart": return isHls ? hls.status(r.id) : downloads.start(r.id, r.url, r.name);', 'case "dlstart": return downloads.start(r.id, r.url, r.name);'),
    ('hlsstart mit URL', CC, 'if (url != null || id == null || planId == null || option == null) throw new IllegalArgumentException("hls fields");', 'if (id == null || planId == null || option == null) throw new IllegalArgumentException("hls fields");'),
    ('Bindung mit Zusatzfeld', CC, 'else throw new IllegalArgumentException("bind field");', 'else { if (peek() == \'"\') token(64); else integer(); }'),
    ('Bindungs-Player beliebig', CC, "if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) throw new IllegalArgumentException(\"token\");", ''),
    ('Plan-Kennung beliebiger Länge', CC, 'if (pos - start != n) throw new IllegalArgumentException("hex length");', ''),
    ('HLS-Felder bei MP4', CC, 'if (planId != null || option != null || bind != null) throw new IllegalArgumentException("hls field");', ''),
    ('hlsstart ohne Größengrenze', CC, 'if (raw.length > 1024) throw new IllegalArgumentException("size");', ''),
    ('Sitzung nicht an HLS', CS, 'device, downloads, hls, sid)', 'device, downloads, hls, new byte[16])'),
    ('fremdes Feld wird ignoriert', CC, '} else throw new IllegalArgumentException("field");', '} else { p.string(); }'),
    ('doppeltes op erlaubt', CC, 'if (op != null) throw new IllegalArgumentException("duplicate op");', ''),
    ('Helligkeit ohne Untergrenze', CC, 'value >= 0.004 && value <= 1.0', 'value <= 1.0'),
    ('Status nimmt v an', CC, 'if (hasValue) throw new IllegalArgumentException("unexpected v");', ''),
    ('Restdaten erlaubt', CC, 'if (p.pos != s.length()) throw new IllegalArgumentException("trailing");', ''),
    ('Statusfeld dl true', CC, '\\"dl\\":false', '\\"dl\\":true'),
    ('Download-Felder bei Steuerbefehl erlaubt', CC, 'if (!download && (id != null || url != null || name != null)) throw new IllegalArgumentException("download field");', ''),
    ('dl-Befehl ohne Download-Steuerung', CC, 'if (downloads == null) return REJECTED;', 'if (downloads == null) return "{\\"ok\\":true}";'),
    ('URL-Zeichen nicht geprüft', CC, 'if (!ok) throw new IllegalArgumentException("url char");', ''),
    ('URL-Länge nicht geprüft', CC, 'if (pos - start >= MAX_URL) throw new IllegalArgumentException("url length");', ''),
    ('Kennung beliebiger Länge', CC, 'if (pos - start != 32) throw new IllegalArgumentException("id length");', ''),
    ('dlstatus mit URL', CC, 'if (op.equals("dlstart") ? url == null : (url != null || name != null)) throw new IllegalArgumentException("download fields");', ''),
    ('dlstatus bis 4096 Byte', CC, 'if (!op.equals("dlstart") && raw.length > MAX_REQUEST) throw new IllegalArgumentException("size");', ''),
    ('Name mit Steuerzeichen', CC, "if (c == '\\\\' || c < 0x20 ||", "if (c == '\\\\' ||"),
    ('Origin nicht geprüft', CS, 'if (origin != null && !EXT_ORIGIN.matcher(origin).matches()) return "origin";', ''),
    ('Host nicht geprüft', CS, 'if (!("127.0.0.1:" + PORT).equals(r.headers.get("host"))) return "host";', ''),
    ('Eigener Header nicht geprüft', CS, 'if (!"2".equals(r.headers.get("x-kadri-helper"))) return "custom-header";', ''),
    ('M3 ohne Speichern', CS, 'host.persist(this);   // kein M3', '// host.persist(this);   // kein M3'),
    ('veralteter Server darf an den Kern', CS, 'if (coreOwner != this) throw new SecurityException("StaleServer");', ''),
]

def run(out, src):
    env = dict(os.environ, APP_SRC=src)
    b = subprocess.run(['bash', os.path.join(HERE, 'build.sh'), out], env=env, capture_output=True, text=True)
    if b.returncode != 0:
        return 'build'
    try:
        r = subprocess.run(['java', '-Djava.library.path=' + os.path.join(out, 'lib'), '-cp', os.path.join(out, 'classes'),
                            'de.evp.helfer.ChannelChecks'], capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        return 'timeout'
    return 'pass' if r.returncode == 0 else 'fail'

def main():
    out = sys.argv[1]
    if run(out, APP) != 'pass':
        print('Ausgangszustand besteht nicht'); return 1
    survivors = 0
    for name, rel, old, new in MUTANTS:
        tmp = tempfile.mkdtemp()
        try:
            shutil.copytree(APP, os.path.join(tmp, 'java'))
            path = os.path.join(tmp, 'java', rel)
            text = open(path, encoding='utf-8').read()
            if text.count(old) != 1:
                print('PROBE VERALTET: %s' % name); survivors += 1; continue
            open(path, 'w', encoding='utf-8').write(text.replace(old, new))
            res = run(out, os.path.join(tmp, 'java'))
            ok = res in ('fail', 'timeout')
            survivors += 0 if ok else 1
            print('%s %-36s -> %s' % ('ERKANNT ' if ok else 'ÜBERLEBT', name, res))
        finally:
            shutil.rmtree(tmp)
    run(out, APP)   # Build-Ausgabe wieder auf den echten Stand
    print('%d/%d Mutanten erkannt' % (len(MUTANTS) - survivors, len(MUTANTS)))
    return 1 if survivors else 0

sys.exit(main())
