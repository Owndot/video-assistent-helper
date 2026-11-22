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
    ('dlcancel nicht zu HLS', CC, 'case "dlcancel": return isHls ? hls.cancel(r.id) : isDash ? dash.cancel(r.id) : downloads.cancel(r.id);', 'case "dlcancel": return isDash ? dash.cancel(r.id) : downloads.cancel(r.id);'),
    ('dlstart startet MP4 mit HLS-Kennung', CC, 'case "dlstart": return isHls ? hls.status(r.id) : isDash ? dash.status(r.id) : downloads.start(r.id, r.url, r.name, r.context);', 'case "dlstart": return isDash ? dash.status(r.id) : downloads.start(r.id, r.url, r.name, r.context);'),
    ('Anfragekontext bei jedem Befehl', CC, 'if (!"dlstart".equals(op) && !"hlsplan".equals(op) && !"dashplan".equals(op)) throw new IllegalArgumentException("context field");', ''),
    ('Referer mit Pfad erlaubt', 'de/evp/helfer/net/RequestContext.java', '(:[1-9][0-9]{0,4})?/");', '(:[1-9][0-9]{0,4})?/.*");'),
    ('beliebiger User-Agent erlaubt', 'de/evp/helfer/net/RequestContext.java', r'"Mozilla/5\\.0 \\([\\x20-\\x7e]{1,500}"', r'"[\\x20-\\x7e]{1,512}"'),
    ('hlsstart mit URL', CC, 'if (url != null || id == null || planId == null || option == null) throw new IllegalArgumentException("hls fields");', 'if (id == null || planId == null || option == null) throw new IllegalArgumentException("hls fields");'),
    ('Bindung mit Zusatzfeld', CC, 'else throw new IllegalArgumentException("bind field");', 'else { if (peek() == \'"\') token(64); else integer(); }'),
    ('Bindungs-Player beliebig', CC, "if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) throw new IllegalArgumentException(\"token\");", ''),
    ('Plan-Kennung beliebiger Länge', CC, 'if (pos - start != n) throw new IllegalArgumentException("hex length");', ''),
    ('HLS-Felder bei MP4', CC, 'if (planId != null || option != null || bind != null) throw new IllegalArgumentException("hls field");', ''),
    ('hlsstart ohne Größengrenze', CC, 'throw new IllegalArgumentException("hls fields");\n                if (raw.length > 1024) throw new IllegalArgumentException("size");', 'throw new IllegalArgumentException("hls fields");'),
    ('Sitzung nicht an HLS', CS, 'device, downloads, hls, dash, sid)', 'device, downloads, hls, dash, new byte[16])'),
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

sys.path.insert(0, os.path.dirname(HERE))
import mutation_verdict as mv  # noqa: E402

KILLED = [r'AssertionError']


def run(out, src):
    """Urteil (mutation_verdict) für eine Kopie der App-Quellen; Übersetzungsfehler sind INVALID, nie erkannt."""
    env = dict(os.environ, APP_SRC=src)
    b = subprocess.run(['bash', os.path.join(HERE, 'build.sh'), out], env=env, capture_output=True, text=True)
    if b.returncode != 0:
        o = b.stdout + b.stderr
        v = mv.verdict(b.returncode, o, [])
        return ('INVALID' if v == 'UNCLASSIFIED' else v), o
    return mv.run(['java', '-Djava.library.path=' + os.path.join(out, 'lib'), '-cp', os.path.join(out, 'classes'),
                   'de.evp.helfer.ChannelChecks'], dict(os.environ), 120, KILLED)

def main():
    out = sys.argv[1]
    if run(out, APP)[0] != 'SURVIVED':
        print('Ausgangszustand besteht nicht'); return 1
    tally = mv.Tally('channel-mutations')
    for name, rel, old, new in MUTANTS:
        tmp = tempfile.mkdtemp()
        try:
            shutil.copytree(APP, os.path.join(tmp, 'java'))
            path = os.path.join(tmp, 'java', rel)
            text = open(path, encoding='utf-8').read()
            if text.count(old) != 1:
                tally.add('%s (fragment found %d times)' % (name, text.count(old)), 'STALE'); continue
            open(path, 'w', encoding='utf-8').write(text.replace(old, new))
            v, o = run(out, os.path.join(tmp, 'java'))
            tally.add(name, v, o)
        finally:
            shutil.rmtree(tmp)
    run(out, APP)   # Build-Ausgabe wieder auf den echten Stand
    return tally.finish()

sys.exit(main())
