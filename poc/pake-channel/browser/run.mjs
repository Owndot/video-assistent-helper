// End-to-end PoC run: real Chromium extension service worker (WASM core)
// against the JVM helper stand-in (JNI core) over real loopback HTTP.
// Throwaway identities, artificial codes, a non-production port.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const ROOT = resolve(new URL('..', import.meta.url).pathname);
const [JVM_OUT, LIB] = process.argv.slice(2);
const PORT = 47899;
const state = mkdtempSync(join(tmpdir(), 'kadri-poc-state-'));
const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')); };

function startHelper(mode) {
  const p = spawn('java', ['-Xmx32m', '-Dpoc.lib=' + LIB, '-cp', JVM_OUT, 'poc.HelperStandIn', String(PORT), mode, state], { stdio: ['pipe', 'pipe', 'inherit'] });
  const lines = [];
  const waiters = [];
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
  const helper = {
    lines, proc: p,
    send: (cmd) => p.stdin.write(cmd + '\n'),
    wait: (re, ms = 10000) => {
      const hit = lines.find((l) => re.test(l));
      if (hit) return Promise.resolve(hit);
      return new Promise((res, rej) => { const w = { re, res }; waiters.push(w); setTimeout(() => rej(new Error('timeout ' + re)), ms); });
    },
    since: (n) => lines.slice(n),
    stop: () => new Promise((res) => { p.on('exit', res); p.stdin.write('quit\n'); setTimeout(() => p.kill('SIGKILL'), 3000); }),
  };
  return helper.wait(/^READY/).then(() => helper);
}

const userData = mkdtempSync(join(tmpdir(), 'kadri-poc-profile-'));
const ext = join(ROOT, 'ext');
const context = await chromium.launchPersistentContext(userData, {
  channel: 'chromium', headless: true,
  args: ['--disable-extensions-except=' + ext, '--load-extension=' + ext],
});
let [sw] = context.serviceWorkers();
if (!sw) sw = await context.waitForEvent('serviceworker');
const extId = new URL(sw.url()).host;
const bg = (fn, arg) => sw.evaluate(fn, arg);
const attempt = async (fn, arg) => { try { return { ok: true, value: await bg(fn, arg) }; } catch (e) { return { ok: false, error: String(e.message || e).split('\n')[0] }; } };
console.log('browser', context.browser() ? context.browser().version() : '(persistent)', 'extension', extId);
const memInitial = (await bg(() => poc.ready())).memory;
check('WASM core instantiates in MV3 service worker under extension CSP', memInitial > 0, `initial linear memory ${memInitial} bytes`);
await bg((p) => poc.setPort(p), PORT);

let helper = await startHelper('genuine');

// ---- A. Pairing through the trusted options page
helper.send('open');
const code = (await helper.wait(/^CODE /)).split(' ')[1];
const page = await context.newPage();
await page.goto(`chrome-extension://${extId}/options.html`);
await page.fill('#code', code);
await page.click('#pair');
await page.waitForFunction(() => document.getElementById('out').textContent !== '…', null, { timeout: 15000 });
const shown = await page.textContent('#out');
check('pairing via options page with code from helper', shown === 'Gekoppelt', shown);
await helper.wait(/^PAIRED /);
const pin = await bg(() => poc.pin());
const helperPub = helper.lines.find((l) => l.startsWith('READY')).split('pub=')[1];
check('Kadri pinned exactly the helper identity', pin && pin.helper === helperPub);
console.log('    WASM linear memory after pairing (Argon2id m=8 MiB high-water mark):', await bg(() => poc.memory()), 'bytes');

// ---- B. Channel
const memBefore = await bg(() => poc.memory());
check('handshake with pinned identities', (await attempt(() => poc.handshake())).ok);
const ping = await attempt(() => poc.request({ op: 'ping' }));
check('authenticated control round trip', ping.ok && ping.value.body.includes('genuine-standin'));
const marker = { op: 'download', u: 'https://media.invalid/SECRET-URL-MARKER-7f3a', c: 'COOKIE-MARKER-9c1e=1' };
const dl = await attempt((m) => poc.request(m), marker);
await helper.wait(/^APP_DOWNLOAD /);
check('sensitive parameters reach only the authenticated helper', dl.ok && helper.lines.some((l) => l.includes('COOKIE-MARKER-9c1e')));
const t0 = Date.now();
const par = await attempt(() => Promise.all([poc.transfer(1, 3_000_000), poc.transfer(2, 1_234_567)]));
const secs = (Date.now() - t0) / 1000;
check('two parallel bounded media transfers, byte-exact, <=60 KiB per record',
  par.ok && par.value[0].bytes === 3_000_000 && par.value[1].bytes === 1_234_567 && par.value.every((r) => r.peakRecord === 61440),
  par.ok ? `${(4_234_567 / 1048576 / secs).toFixed(1)} MiB/s over sequential request/response` : par.error);
const memAfter = await bg(() => poc.memory());
check('WASM linear memory stays bounded during transfers', memAfter <= memBefore + 2 * 1048576, `${memBefore} -> ${memAfter} bytes`);

// ---- C. Tampered record
helper.send('tamper on'); await helper.wait(/^TAMPER on/);
const tam = await attempt(() => poc.request({ op: 'ping' }));
check('manipulated record is rejected', !tam.ok && /AuthFailed/.test(tam.error), tam.error);
const after = await attempt(() => poc.request({ op: 'ping' }));
check('channel is dead after a failure (no resync, no fallback)', !after.ok && /no session/.test(after.error), after.error);
helper.send('tamper off'); await helper.wait(/^TAMPER off/);

// ---- D. Helper restart: pins persist, transport state does not
await bg(() => poc.handshake());
await helper.stop();
helper = await startHelper('genuine');
const stale = await attempt(() => poc.request({ op: 'ping' }));
check('old session is unknown after helper restart', !stale.ok && /HTTP 404/.test(stale.error), stale.error);
const fresh = await attempt(async () => { await poc.handshake(); return poc.request({ op: 'ping' }); });
check('fresh handshake after restart with persisted pins', fresh.ok);

// ---- E. Wrong pin
await bg((h) => poc.corruptPin(h), 'aa'.repeat(32));
const wrongPin = await attempt(() => poc.handshake());
check('wrong pinned helper key is rejected', !wrongPin.ok, wrongPin.error);
await bg((h) => poc.corruptPin(h), helperPub);

// ---- F. Foreign listener on the same port
await bg(() => poc.drop());
await helper.stop();
const rogue = await startHelper('rogue');
const rogueHs = await attempt(() => poc.handshake());
check('foreign listener fails the handshake', !rogueHs.ok && /AuthFailed/.test(rogueHs.error), rogueHs.error);
const noFallback = await attempt((m) => poc.request(m), marker);
check('no request is sent without an authenticated session', !noFallback.ok && /no session/.test(noFallback.error));
const pinBefore = await bg(() => poc.pin());
const roguePair = await attempt((c) => poc.pair(c), '12345678');
check('pairing against foreign listener with guessed code fails', !roguePair.ok && /ConfirmationFailed/.test(roguePair.error), roguePair.error);
check('failed pairing leaves the existing pin untouched', JSON.stringify(await bg(() => poc.pin())) === JSON.stringify(pinBefore));
await rogue.stop();
const cap = existsSync(join(state, 'rogue-capture.bin')) ? readFileSync(join(state, 'rogue-capture.bin')) : Buffer.alloc(0);
const capText = cap.toString('latin1');
const paths = [...capText.matchAll(/(\/p1\/[a-z0-9/]+)\0/g)].map((m) => m[1]);
check('foreign listener received only pairing/handshake messages', paths.every((p) => ['/p1/pair/m0', '/p1/pair/m2', '/p1/ch/hs'].includes(p)) && paths.includes('/p1/ch/hs'), paths.join(','));
check('no marker, cookie, URL, code or bearer bytes reached the foreign listener',
  !/MARKER|COOKIE|media\.invalid|12345678|X-EVP-Key/.test(capText), `${cap.length} bytes captured`);

// ---- G. Wrong code burns the window; revocation
helper = await startHelper('genuine');
helper.send('open-code 11111111'); await helper.wait(/^WINDOW_OPEN/);
const wrong = await attempt((c) => poc.pair(c), '22222222');
check('wrong code fails', !wrong.ok && /HTTP 400/.test(wrong.error), wrong.error);
const retry = await attempt((c) => poc.pair(c), '11111111');
check('window is burned after one failed attempt', !retry.ok, retry.error);
await bg(() => poc.handshake());
helper.send('revoke'); await helper.wait(/^REVOKED/);
const revokedSession = await attempt(() => poc.request({ op: 'ping' }));
check('revocation closes the running session', !revokedSession.ok, revokedSession.error);
const revokedHs = await attempt(() => poc.handshake());
check('revoked registration cannot handshake', !revokedHs.ok, revokedHs.error);
await helper.stop();
await context.close();

const failed = results.filter((r) => !r.ok).length;
console.log(`RESULT ${failed ? 'FAIL' : 'PASS'} ${results.length - failed}/${results.length}`);
process.exit(failed ? 1 : 0);
