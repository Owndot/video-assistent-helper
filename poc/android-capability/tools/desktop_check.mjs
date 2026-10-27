// Desktop pre-check of the Vivaldi Android test extension in Chromium against
// the JVM/JNI helper stand-in. This is NOT evidence for Vivaldi Android; it
// only checks the extension logic before it goes to a real device.
//   node desktop_check.mjs <OUT with ext-vivaldi> <libkadri_pairing_poc.so> <jvm classes dir> <probe html>
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const [OUT, LIB, JVM, PROBE] = process.argv.slice(2);
const PORT = 47899;
const state = mkdtempSync(join(tmpdir(), 'kadri-android-poc-state-'));
const results = [];
const check = (name, ok, detail = '') => { results.push(!!ok); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')); };

function startHelper(mode) {
  const p = spawn('java', ['-Xmx32m', '-Dpoc.lib=' + LIB, '-Dsun.net.httpserver.nodelay=true', '-cp', JVM, 'poc.HelperStandIn', String(PORT), mode, state], { stdio: ['pipe', 'pipe', 'inherit'] });
  const lines = [], waiters = [];
  let buf = '';
  p.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      lines.push(line); console.log('    [' + mode + '] ' + line);
      for (const w of waiters.slice()) if (w.re.test(line)) { waiters.splice(waiters.indexOf(w), 1); w.res(line); }
    }
  });
  const h = {
    lines,
    send: (c) => p.stdin.write(c + '\n'),
    wait: (re, ms = 10000, from = 0) => {
      const hit = lines.slice(from).find((l) => re.test(l));
      return hit ? Promise.resolve(hit) : new Promise((res, rej) => { waiters.push({ re, res }); setTimeout(() => rej(new Error('timeout ' + re)), ms); });
    },
    stop: () => new Promise((res) => { p.on('exit', res); p.stdin.write('quit\n'); setTimeout(() => p.kill('SIGKILL'), 3000); }),
  };
  return h.wait(/^LISTENING/).then(() => h);
}

// The probe page lives on a different origin than the helper (as on Android,
// where the test app serves it from a separate 127.0.0.1:47900 socket).
const web = createServer((req, res) => { res.writeHead(req.url === '/kadri-poc-probe.html' ? 200 : 404, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(req.url === '/kadri-poc-probe.html' ? readFileSync(PROBE) : ''); });
await new Promise((r) => web.listen(47900, '127.0.0.1', r));
const ext = join(OUT, 'ext-vivaldi');
const context = await chromium.launchPersistentContext(mkdtempSync(join(tmpdir(), 'kadri-android-poc-profile-')), {
  channel: 'chromium', headless: true, args: ['--disable-extensions-except=' + ext, '--load-extension=' + ext],
});
let [sw] = context.serviceWorkers();
if (!sw) sw = await context.waitForEvent('serviceworker');
const base = 'chrome-extension://' + new URL(sw.url()).host;

async function selftest(kind) {
  const page = await context.newPage();
  await page.goto(base + '/selftest.html');
  await page.click('#' + kind);
  await page.waitForFunction(() => document.getElementById('out').textContent.startsWith('{'), null, { timeout: 30000 });
  const v = JSON.parse(await page.textContent('#out'));
  await page.close();
  return v.result;
}
async function optionsPair(code) {
  const page = await context.newPage();
  await page.goto(base + '/options.html');
  await page.fill('#code', code);
  await page.click('#pair');
  await page.waitForFunction(() => !['', '…'].includes(document.getElementById('out').textContent), null, { timeout: 30000 });
  const t = await page.textContent('#out');
  const s = await page.textContent('#state');
  await page.close();
  return { text: t, state: s };
}
async function status() {
  const page = await context.newPage();
  await page.goto(base + '/options.html');
  await page.waitForFunction(() => document.getElementById('state').textContent !== '…');
  const s = await page.textContent('#state');
  await page.close();
  return s;
}

const b = await selftest('browser');
check('WASM core runs in the MV3 service worker under the CSP', b.ok && b.value.wasm.ok, `linear memory ${b.value.wasm.linearMemory} B`);
check('eval stays blocked while WASM runs (no unsafe-eval)', b.ok && b.value.evalBlocked === true);
check('secure randomness (getRandomValues, core key generation)', b.ok && b.value.rng.distinct && b.value.rng.coreKeypair);

let helper = await startHelper('genuine');
helper.send('open');
const code = (await helper.wait(/^CODE /)).split(' ')[1];
const mark = helper.lines.length;
const probePage = await context.newPage();
await probePage.goto('http://127.0.0.1:47900/kadri-poc-probe.html');
await probePage.waitForFunction(() => document.body.dataset.attack && document.documentElement.dataset.kadriProbe, null, { timeout: 20000 });
const attack = JSON.parse(await probePage.evaluate(() => document.body.dataset.attack));
const probe = JSON.parse(await probePage.evaluate(() => document.documentElement.dataset.kadriProbe));
await probePage.close();
await new Promise((r) => setTimeout(r, 300));
const reqs = helper.lines.slice(mark).filter((l) => l.startsWith('REQ '));
check('web page: preflighted request blocked, every request refused by the gate', /^blocked:/.test(attack.preflighted) && reqs.length > 0 && reqs.every((l) => /GATE_REJECT/.test(l)), reqs.join(' | '));
check('content script: pairing, self test and status refused', [probe.pair, probe.selftest, probe.status].every((r) => r && r.ok === false && r.error === 'refused'));
check('content script: no extension storage API, extension IndexedDB unreachable', probe.storageApi === 'undefined' && Array.isArray(probe.idb) && probe.idb.length === 0, JSON.stringify({ s: probe.storageApi, idb: probe.idb }));

const p1 = await optionsPair(code);
check('pairing via options page (window survived the web page)', p1.text === 'Gekoppelt' && /^Gekoppelt/.test(p1.state), p1.text);
const reg1 = /Registrierung (\w+)/.exec(p1.state)[1];

const h1 = await selftest('helper');
check('authenticated ping and 1 MiB of bounded dummy records', h1.ok && !h1.value.helperError && h1.value.dummy.bytes === 1048576 && h1.value.dummy.records === 18, JSON.stringify(h1.value.dummy || h1.value.helperError));

// Service-worker kill: new worker, no session, reconnect with a fresh handshake.
const cdp = await context.newCDPSession(await context.newPage());
await cdp.send('ServiceWorker.enable');
await cdp.send('ServiceWorker.stopAllWorkers');
await new Promise((r) => setTimeout(r, 500));
const h2 = await selftest('helper');
check('worker restart: new worker instance, persisted pin, new authenticated session', h2.ok && h2.value.worker !== h1.value.worker && h2.value.workerStarts > h1.value.workerStarts && h2.value.ping && !h2.value.helperError,
  `worker ${h1.value.worker}→${h2.value.worker}, starts ${h1.value.workerStarts}→${h2.value.workerStarts}`);

// R4: a new pairing whose confirmation fails must keep the previous pin.
helper.send('open');
const code2 = (await helper.wait(/^CODE /, 10000, helper.lines.length)).split(' ')[1];
helper.send('tamper on'); await helper.wait(/^TAMPER on/);
const p2 = await optionsPair(code2);
helper.send('tamper off'); await helper.wait(/^TAMPER off/);
check('R4: failed confirmation reports and keeps the previous pairing', /previous pairing kept/.test(p2.text) && (await status()).includes(reg1), p2.text);
const h3 = await selftest('helper');
check('R4: previous pairing still works afterwards', h3.ok && h3.value.ping && !h3.value.helperError);

// Wrong code.
helper.send('open-code 11111111'); await helper.wait(/^WINDOW_OPEN/);
const p3 = await optionsPair('22222222');
check('wrong code fails and the pin is unchanged', /^Fehlgeschlagen/.test(p3.text) && (await status()).includes(reg1), p3.text);

// Live session, then a foreign listener takes the port.
await selftest('helper');
await helper.stop();
const rogue = await startHelper('rogue');
const h4 = await selftest('helper');
check('foreign listener: authenticated session cannot continue, nothing accepted', h4.ok && h4.value.helperError, h4.value.helperError);
const h5 = await selftest('helper');
check('foreign listener: fresh handshake fails, no fallback', h5.ok && /AuthFailed/.test(h5.value.helperError || ''), h5.value.helperError);
const p4 = await optionsPair('12345678');
check('foreign listener: pairing with guessed code fails, pin unchanged', /^Fehlgeschlagen/.test(p4.text) && (await status()).includes(reg1), p4.text);
await rogue.stop();

helper = await startHelper('genuine');
const h6 = await selftest('helper');
check('genuine helper back: persisted pins work again', h6.ok && h6.value.ping && !h6.value.helperError);
await helper.stop();
await context.close();
web.close();
const failed = results.filter((r) => !r).length;
console.log(`RESULT desktop-precheck ${failed ? 'FAIL' : 'PASS'} ${results.length - failed}/${results.length}`);
process.exit(failed ? 1 : 0);
