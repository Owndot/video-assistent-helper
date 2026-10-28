'use strict';
// D2: Die Helfer-Funktionen des Userscripts sind aus, bis es den sicheren Kanal hat. Geprüft am echten Quelltext
// (src/080-helper-app.js) und am gebauten Userscript: helper() sendet nie etwas – an keinen Port, mit keinem Schlüssel –,
// liest keinen Schlüssel, ein alter Kopplungsschlüssel wird gelöscht, die alte Koppel-Seite wird nicht mehr bedient.
// Echte Dienste auf 47811 (alter Klartext-Port) und 47899 (Kanal) zählen jede Verbindung.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(process.argv[2] || path.join(__dirname, '../..'));
const source = fs.readFileSync(path.join(root, 'src/080-helper-app.js'), 'utf8');
const built = fs.readFileSync(path.join(root, 'userscript/video-assistent.user.js'), 'utf8');

for (const [name, text] of [['src/080-helper-app.js', source], ['userscript', built]]) {
  for (const needle of ['47811', 'X-EVP-Key', "'/pair'", '/pair#', 'KADRI_HELPER_KEY']) {
    assert(!text.includes(needle), `${name} still contains ${needle}`);
  }
}

const a = source.indexOf('  // ---------- Helfer-App');
const b = source.indexOf('  // ---------- Laden über den Browser', a);
assert(a >= 0 && b > a, 'helper section not found');

function listener(port) {
  const s = net.createServer(c => { s.hits++; c.destroy(); });
  s.hits = 0;
  return new Promise((resolve, reject) => { s.once('error', reject); s.listen(port, '127.0.0.1', () => resolve(s)); });
}

(async () => {
  const servers = await Promise.all([47811, 47899].map(listener));
  try {
    const store = new Map([['helperKey', '0123456789abcdef0123456789abcdef']]), reads = [], requests = [];
    const context = {
      GM_getValue: (k, d) => { reads.push(k); return store.has(k) ? store.get(k) : d; },
      GM_setValue: (k, v) => store.set(k, v),
      gmXhr: () => o => { requests.push(o); return { abort() {} }; },
      GM_xmlhttpRequest: o => { requests.push(o); return { abort() {} }; },
      fetch: (...x) => { requests.push(x); return Promise.reject(new Error('no network in this test')); },
      CONFIG: { systemHelper: true },
      location: { origin: 'http://127.0.0.1:47811', pathname: '/pair', hash: '#0123456789abcdef0123456789abcdef' },
      setTimeout, clearTimeout,
    };
    vm.runInNewContext(source.slice(a, b) + '\nglobalThis.helper = helper;', context);
    assert.equal(store.get('helperKey'), '', 'stored legacy key was not wiped');
    const job = { cancelled: false, aborters: new Set() };
    const calls = [
      ['/ping'], ['/status'], ['/beat'], ['/end'], ['/bright?v=0.500'], ['/vol?v=0.500'],
      ['/download?u=https%3A%2F%2Fmedia.invalid%2Fa.mp4&c=SYNTHETIC-COOKIE', 1200], ['/pull?job=1', 12000, null, null, job],
      ['/push?job=1&id=1&st=200', 60000, new ArrayBuffer(4), null, job], ['/ping', 2500, null, '0123456789abcdef0123456789abcdef'],
    ];
    for (const args of calls) assert.equal(await context.helper(...args), null, 'helper answered ' + args[0]);
    // Kadri (Chrome) routes through its own channel: only the path leaves helper(), never a body, key or job.
    const routed = [];
    const kadri = Object.assign({}, context, { KADRI_HELPER: async (...a) => { routed.push(a); return a[0] === '/ping' ? { ok: true } : null; } });
    vm.runInNewContext(source.slice(a, b) + '\nglobalThis.helper = helper;', kadri);
    assert.deepEqual(await kadri.helper('/ping', 2500, null, 'SYNTHETIC-KEY'), { ok: true });
    await kadri.helper('/push?job=1', 60000, new ArrayBuffer(4), null, job);
    assert.deepEqual(routed, [['/ping'], ['/push?job=1']], 'helper passed more than the path');
    await new Promise(r => setTimeout(r, 300));
    assert.deepEqual(requests, [], 'helper issued a request');
    assert.deepEqual(reads.filter(k => k !== 'helperKey'), [], 'unexpected settings read');
    assert.equal(reads.length, 2, 'legacy key read other than once per load (only to wipe it)');
    assert.deepEqual(servers.map(s => s.hits), [0, 0], 'a local listener was contacted');
    assert.equal(job.aborters.size, 0, 'abort handles left behind');
    console.log('PASS: userscript helper disabled (D2): no request to 47811/47899, no key read or sent, stored key wiped, no pairing page');
  } finally {
    servers.forEach(s => s.close());
  }
})().catch(e => { console.error(e); process.exitCode = 1; });
