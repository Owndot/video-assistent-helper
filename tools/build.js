#!/usr/bin/env node
// Baut das Userscript aus den Teilen in src/ (der Reihe nach, wie sie heißen: 010-…, 020-…).
//
// Die Teile sind Abschnitte EINER Funktion (das Skript läuft als Ganzes in einer Hülle) – einzeln sind sie kein
// gültiges JavaScript. Der Bau ergänzt die geprüfte hls.js-Kopie im erzeugten Userscript. So bleibt das Verhalten exakt
// gleich, während man an kleinen, übersichtlichen Dateien arbeitet.
//
//   node tools/build.js           → userscript/video-assistent.user.js neu schreiben
//   node tools/build.js --check   → nur prüfen, ob die Datei zu src/ passt (für die CI)
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const OUT = path.join(ROOT, 'userscript', 'video-assistent.user.js');

const parts = fs.readdirSync(SRC).filter((f) => /^\d{3}-[\w-]+\.js$/.test(f)).sort();
if (!parts.length) { console.error('Keine Teile in src/'); process.exit(1); }
let text = parts.map((f) => fs.readFileSync(path.join(SRC, f), 'utf8')).join('');

// Execute exactly the reviewed vendored bytes; no manager-specific @require/SRI behavior.
const vendor = path.join(ROOT, 'vendor');
const lock = JSON.parse(fs.readFileSync(path.join(vendor, 'hls.lock.json'), 'utf8'));
const hls = fs.readFileSync(path.join(vendor, 'hls.min.js'), 'utf8');
if (require('crypto').createHash('sha256').update(hls).digest('hex') !== lock.sha256 ||
    require(path.join(vendor, 'hls.min.js')).version !== lock.version) {
  throw new Error('hls.js bytes/version differ from the reviewed lock');
}
const anchor = '  const HIDE_NO_HELPER = IS_KADRI;';
if (text.split(anchor).length !== 2) throw new Error('hls.js bundle anchor changed');
text = text.replace(anchor, () => anchor + '\n  // Bundled hls.js ' + lock.version + ' (Apache-2.0; vendor/hls.LICENSE).\n' +
  '  let Hls;\n  function loadBundledHls() { if (Hls) return; Hls = (() => { const module = { exports: {} }; const exports = module.exports; const define = undefined;\n' +
  hls.replace(/\n?\/\/# sourceMappingURL=.*$/, '') + '\n    return module.exports;\n  })(); }\n');

// Syntax prüfen (ganze Datei)
try { new vm.Script(text, { filename: OUT }); }
catch (e) { console.error('Syntaxfehler im zusammengesetzten Skript: ' + e.message); process.exit(1); }

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
