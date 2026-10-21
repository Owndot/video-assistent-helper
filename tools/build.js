#!/usr/bin/env node
// Baut das Userscript aus den Teilen in src/ (der Reihe nach, wie sie heißen: 010-…, 020-…).
//
// Die Teile sind Abschnitte EINER Funktion (das Skript läuft als Ganzes in einer Hülle) – einzeln sind sie kein
// gültiges JavaScript, zusammen ergeben sie genau userscript/video-assistent.user.js. So bleibt das Verhalten exakt
// gleich, während man an kleinen, übersichtlichen Dateien arbeitet.
//
//   node tools/build.js           → userscript/video-assistent.user.js neu schreiben
//   node tools/build.js --check   → nur prüfen, ob die Datei zu src/ passt (für die CI)
'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const OUT = path.join(ROOT, 'userscript', 'video-assistent.user.js');

const parts = fs.readdirSync(SRC).filter((f) => /^\d{3}-[\w-]+\.js$/.test(f)).sort();
if (!parts.length) { console.error('Keine Teile in src/'); process.exit(1); }
const text = parts.map((f) => fs.readFileSync(path.join(SRC, f), 'utf8')).join('');

// Syntax prüfen (ganze Datei)
const tmp = path.join(require('os').tmpdir(), 'evp-build-' + process.pid + '.js');
fs.writeFileSync(tmp, text);
try { execFileSync(process.execPath, ['--check', tmp], { stdio: 'pipe' }); }
catch (e) { console.error('Syntaxfehler im zusammengesetzten Skript:\n' + String(e.stderr || e.message)); process.exit(1); }
finally { try { fs.unlinkSync(tmp); } catch (e) {} }

const version = (text.match(/^\/\/ @version\s+(\S+)/m) || [])[1] || '?';
if (process.argv.includes('--check')) {
  const cur = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : '';
  if (cur !== text) {
    console.error('userscript/video-assistent.user.js passt nicht zu src/ – bitte "node tools/build.js" ausführen (oder in src/ statt in der fertigen Datei ändern).');
    process.exit(1);
  }
  console.log('OK: Userscript ' + version + ' passt zu src/ (' + parts.length + ' Teile)');
} else {
  fs.writeFileSync(OUT, text);
  console.log('Gebaut: Userscript ' + version + ' aus ' + parts.length + ' Teilen → ' + path.relative(ROOT, OUT) + ' (' + text.split('\n').length + ' Zeilen)');
}
