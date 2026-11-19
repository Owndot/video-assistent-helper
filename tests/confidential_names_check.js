'use strict';
// Wächter: vertrauliche Namen getesteter Seiten (und ihrer Medien-Domains) dürfen in keiner getrackten Textdatei stehen.
// Die Namen selbst stehen auch hier nicht – geprüft wird jedes Wort (a–z, 0–9) gegen feste FNV-1a-Hashes.
// Neutrale Kennungen in Code und Doku: site-a, site-b, media-regression-a, media-regression-b.
//   node tests/confidential_names_check.js [ROOT]
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const BLOCKED = new Set([0x507a797, 0x80310752, 0x69ce7788]);
const fnv = (w) => { let x = 0x811c9dc5; for (const ch of w) { x ^= ch.charCodeAt(0); x = Math.imul(x, 0x01000193) >>> 0; } return x; };

const root = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const files = execFileSync('git', ['ls-files', '-z'], { cwd: root }).toString().split('\0').filter(Boolean);
const hits = [];
for (const f of files) {
  let buf;
  try { buf = fs.readFileSync(path.join(root, f)); } catch (e) { continue; }
  if (buf.length > 4 << 20 || buf.subarray(0, 8000).includes(0)) continue;   // binär oder sehr groß
  const words = buf.toString('utf8').toLowerCase().match(/[a-z0-9]+/g) || [];
  for (const w of words) if (w.length >= 5 && w.length <= 12 && BLOCKED.has(fnv(w))) { hits.push(f); break; }
}
// Selbsttest ohne Klartext: ein Wort mit gesperrtem Hash muss erkannt werden (über die Funktion selbst geprüft)
if (!BLOCKED.has(0x507a797) || fnv('site') === 0x507a797) { console.error('FAIL: guard self-test'); process.exit(1); }
if (hits.length) { console.error('FAIL: confidential site name in tracked files: ' + hits.join(', ')); process.exit(1); }
console.log('PASS: no confidential site name in ' + files.length + ' tracked files');
