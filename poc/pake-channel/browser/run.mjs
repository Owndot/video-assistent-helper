// End-to-end PoC run: real browser extension background (one shared WASM
// core) against the JVM helper stand-in (JNI core) over real loopback HTTP.
// The same scenario runs in Chromium (Playwright) and Firefox (Selenium +
// geckodriver). Throwaway identities, artificial codes, non-production ports.
//
//   node browser/run.mjs chromium <OUT> <LIB> [--nodelay]
//   node browser/run.mjs firefox  <OUT> <LIB> [--nodelay]   (needs FIREFOX_BIN, GECKODRIVER, SELENIUM_DIR)
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const [BROWSER, OUT, LIB, ...flags] = process.argv.slice(2);
const NODELAY = flags.includes('--nodelay');
const PORT = 47899, WEB_PORT = 47900;
const state = mkdtempSync(join(tmpdir(), 'kadri-poc-state-'));
const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok: !!ok }); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')); };

// ---------------------------------------------------------------- helper process
function startHelper(mode) {
  const jvm = ['-Xmx32m', '-Dpoc.lib=' + LIB, ...(NODELAY ? ['-Dsun.net.httpserver.nodelay=true'] : []), '-cp', join(OUT, 'jvm'), 'poc.HelperStandIn', String(PORT), mode, state];
  const p = spawn('java', jvm, { stdio: ['pipe', 'pipe', 'inherit'] });
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
  const helper = {
    lines,
    send: (cmd) => p.stdin.write(cmd + '\n'),
    wait: (re, ms = 10000, from = 0) => {
      const hit = lines.slice(from).find((l) => re.test(l));
      if (hit) return Promise.resolve(hit);
      return new Promise((res, rej) => { const w = { re, res }; waiters.push(w); setTimeout(() => rej(new Error('timeout ' + re)), ms); });
    },
    stop: () => new Promise((res) => { p.on('exit', res); p.stdin.write('quit\n'); setTimeout(() => p.kill('SIGKILL'), 3000); }),
  };
  return helper.wait(/^LISTENING/).then(() => helper);
}

// ---------------------------------------------------------------- web page (untrusted origin)
const ATTACK = `<!doctype html><meta charset="utf-8"><title>web page</title><body><script>
(async () => {
  const r = {}, u = 'http://127.0.0.1:${PORT}/p1/pair/m0', body = new Uint8Array(37);
  try { await fetch(u, { method: 'POST', mode: 'no-cors', body }); r.simple = 'sent'; } catch (e) { r.simple = 'blocked:' + e.name; }
  try { const x = await fetch(u, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream', 'X-Kadri-Helper': '2' }, body }); r.preflighted = 'status:' + x.status; }
  catch (e) { r.preflighted = 'blocked:' + e.name; }
  r.extensionGlobals = typeof window.poc + '/' + typeof window.op;
  document.body.setAttribute('data-attack', JSON.stringify(r));
})();
</script></body>`;
const web = createServer((req, res) => { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(ATTACK); });
await new Promise((r) => web.listen(WEB_PORT, '127.0.0.1', r));

// ---------------------------------------------------------------- browser drivers
async function chromiumDriver() {
  const { chromium } = require('playwright');
  const ext = join(OUT, 'ext-chrome');
  const context = await chromium.launchPersistentContext(mkdtempSync(join(tmpdir(), 'kadri-poc-profile-')), {
    channel: 'chromium', headless: true, args: ['--disable-extensions-except=' + ext, '--load-extension=' + ext],
  });
  let [sw] = context.serviceWorkers();
  if (!sw) sw = await context.waitForEvent('serviceworker');
  const base = 'chrome-extension://' + new URL(sw.url()).host;
  const testPage = await context.newPage();
  await testPage.goto(base + '/test.html');
  return {
    version: context.browser() ? context.browser().version() : (await testPage.evaluate(() => navigator.userAgent)),
    op: (name, arg) => testPage.evaluate(([n, a]) => window.op(n, a), [name, arg ?? null]),
    async optionsPair(code) {
      const page = await context.newPage();
      await page.goto(base + '/options.html');
      await page.fill('#code', code);
      await page.click('#pair');
      await page.waitForFunction(() => document.getElementById('out').textContent !== '…', null, { timeout: 20000 });
      const text = await page.textContent('#out');
      await page.close();
      return text;
    },
    async visitWeb() {
      const page = await context.newPage();
      await page.goto(`http://127.0.0.1:${WEB_PORT}/attack.html`);
      await page.waitForFunction(() => document.body.dataset.attack && document.documentElement.dataset.kadriProbe, null, { timeout: 20000 });
      const v = await page.evaluate(() => ({ attack: JSON.parse(document.body.dataset.attack), probe: JSON.parse(document.documentElement.dataset.kadriProbe) }));
      await page.close();
      return v;
    },
    close: () => context.close(),
  };
}

async function firefoxDriver() {
  const sel = (m) => require(join(process.env.SELENIUM_DIR, 'node_modules', m));
  const { Builder } = sel('selenium-webdriver');
  const firefox = sel('selenium-webdriver/firefox');
  // Control channel for the extension pages (see ext/firefox/ff-bridge.js).
  const queues = { test: [], options: [] }, pollers = { test: [], options: [] }, pending = new Map();
  let nextId = 1;
  const control = createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/cmd') {
      const role = u.searchParams.get('role');
      const give = (cmd) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(cmd)); };
      if (queues[role] && queues[role].length) return give(queues[role].shift());
      if (!pollers[role]) { res.writeHead(400); return res.end(); }
      const t = setTimeout(() => { pollers[role].splice(pollers[role].indexOf(give), 1); res.writeHead(204); res.end(); }, 15000);
      pollers[role].push((cmd) => { clearTimeout(t); give(cmd); });
    } else if (u.pathname === '/result') {
      let body = '';
      req.on('data', (d) => (body += d));
      req.on('end', () => { const m = JSON.parse(body); const p = pending.get(m.id); pending.delete(m.id); if (p) p(m.result); res.writeHead(204); res.end(); });
    } else { res.writeHead(404); res.end(); }
  });
  await new Promise((r) => control.listen(47902, '127.0.0.1', r));
  const send = (role, op, arg) => new Promise((resolve, reject) => {
    const cmd = { id: nextId++, op, arg: arg ?? null };
    const timer = setTimeout(() => { pending.delete(cmd.id); reject(new Error('bridge timeout ' + op)); }, 120000);
    pending.set(cmd.id, (r) => { clearTimeout(timer); resolve(r); });
    if (pollers[role].length) pollers[role].shift()(cmd); else queues[role].push(cmd);
  });
  const opts = new firefox.Options().setBinary(process.env.FIREFOX_BIN).addArguments('-headless');
  const driver = await new Builder().forBrowser('firefox').setFirefoxOptions(opts)
    .setFirefoxService(new firefox.ServiceBuilder(process.env.GECKODRIVER)).build();
  const home = await driver.getWindowHandle();
  await driver.installAddon(join(OUT, 'ext-firefox.xpi'), true);
  const caps = await driver.getCapabilities();
  return {
    version: 'Firefox ' + caps.get('browserVersion') + ' (extension pages driven via test bridge)',
    op: (name, arg) => send('test', name, arg),
    async optionsPair(code) {
      await send('test', 'openOptions');
      const r = await send('options', 'uiPair', code);
      return r && r.ok ? r.value : 'bridge error ' + JSON.stringify(r);
    },
    async visitWeb() {
      await driver.switchTo().newWindow('tab');
      await driver.get(`http://127.0.0.1:${WEB_PORT}/attack.html`);
      try { await driver.wait(async () => driver.executeScript('return !!(document.body.dataset.attack && document.documentElement.dataset.kadriProbe)'), 20000); } catch (e) { /* report what is there */ }
      const v = await driver.executeScript('const j = (s) => s ? JSON.parse(s) : { missing: true }; return { attack: j(document.body.dataset.attack), probe: j(document.documentElement.dataset.kadriProbe) }');
      await driver.close();
      await driver.switchTo().window(home);
      return v;
    },
    close: async () => { await driver.quit(); control.close(); },
  };
}

const b = BROWSER === 'firefox' ? await firefoxDriver() : await chromiumDriver();
const op = async (name, arg) => { const r = await b.op(name, arg); if (!r || !r.ok) throw new Error((r && r.error) || 'no reply'); return r.value; };
const attempt = async (name, arg) => { const r = await b.op(name, arg); return r && r.ok ? { ok: true, value: r.value } : { ok: false, error: (r && r.error) || 'no reply' }; };
console.log('browser', b.version, NODELAY ? '(helper TCP_NODELAY on)' : '(helper TCP_NODELAY off)');

const memInitial = (await op('ready')).memory;
check('WASM core instantiates in the extension background under the extension CSP', memInitial > 0, `initial linear memory ${memInitial} bytes`);
await op('setPort', PORT);
let helper = await startHelper('genuine');
const helperPub = helper.lines.find((l) => l.startsWith('READY')).split('pub=')[1];

// ---- A. Web page and content script cannot pair or reach secrets
helper.send('open');
const code = (await helper.wait(/^CODE /)).split(' ')[1];
const mark = helper.lines.length;
const { attack, probe } = await b.visitWeb();
await new Promise((r) => setTimeout(r, 500));
const pageReqs = helper.lines.slice(mark).filter((l) => l.startsWith('REQ '));
check('web page: preflighted request never reaches the protocol', /^blocked:/.test(attack.preflighted) && !pageReqs.some((l) => / POST .*origin=http/.test(l) && !/GATE_REJECT/.test(l)), JSON.stringify(attack));
check('web page: every request it caused was refused by the gate (DoS guard, not authentication)', pageReqs.length > 0 && pageReqs.every((l) => /GATE_REJECT/.test(l)), pageReqs.map((l) => l.replace(/^REQ /, '')).join(' | '));
check('web page sees no extension globals', attack.extensionGlobals === 'undefined/undefined');
check('content script cannot start pairing', probe.pair && probe.pair.ok === false && probe.pair.error === 'refused', JSON.stringify(probe.pair));
check('content script cannot call test hooks', probe.test && probe.test.ok === false, JSON.stringify(probe.test));
check('content script sees no identity/pin in storage.local', probe.storageLocal && typeof probe.storageLocal === 'object' && Object.keys(probe.storageLocal).length === 0, JSON.stringify(probe.storageLocal));
check('content script cannot open the extension IndexedDB', Array.isArray(probe.idb) && probe.idb.length === 0, JSON.stringify(probe.idb));

// ---- B. Pairing through the trusted options page (window survived the web page)
const shown = await b.optionsPair(code);
check('pairing via options page with code from helper', shown === 'Gekoppelt', shown);
const pin = await op('pin');
check('Kadri pinned exactly the helper identity', pin && pin.helper === helperPub);
helper.send('stats');
check('first authenticated round trip confirmed the registration', /registrations=1 half_open=0 established=1/.test(await helper.wait(/^STATS /)));
check('identity/pin are not in storage.local', (await op('storageLocalKeys')).length === 0);

// ---- C. Channel, markers, bounded media
check('handshake with pinned identities', (await attempt('handshake')).ok);
const ping = await attempt('request', { op: 'ping' });
check('authenticated control round trip', ping.ok && ping.value.body.includes('genuine-standin'));
const marker = { op: 'download', u: 'https://media.invalid/SECRET-URL-MARKER-7f3a', c: 'COOKIE-MARKER-9c1e=1' };
const dl = await attempt('request', marker);
await helper.wait(/^APP_DOWNLOAD /);
check('sensitive parameters reach only the authenticated helper', dl.ok && helper.lines.some((l) => l.includes('COOKIE-MARKER-9c1e')));
const memBefore = await op('memory');
const t0 = Date.now();
const par = await attempt('transfers', [[1, 6_000_000], [2, 2_468_000]]);
const secs = (Date.now() - t0) / 1000;
check('two parallel bounded media transfers, byte-exact, <=60 KiB per record',
  par.ok && par.value[0].bytes === 6_000_000 && par.value[1].bytes === 2_468_000 && par.value.every((r) => r.peakRecord === 61440),
  par.ok ? `THROUGHPUT ${(8_468_000 / 1048576 / secs).toFixed(1)} MiB/s` : par.error);
const memAfter = await op('memory');
check('WASM linear memory stays bounded during transfers', memAfter <= memBefore + 2 * 1048576, `${memBefore} -> ${memAfter} bytes`);

// ---- D. Tampered record
helper.send('tamper on'); await helper.wait(/^TAMPER on/);
const tam = await attempt('request', { op: 'ping' });
check('manipulated record is rejected', !tam.ok && /AuthFailed/.test(tam.error), tam.error);
const after = await attempt('request', { op: 'ping' });
check('session is dead after a failure (no resync, no fallback)', !after.ok && /no session/.test(after.error), after.error);
helper.send('tamper off'); await helper.wait(/^TAMPER off/);

// ---- E. Helper restart: pins persist, transport state does not
await op('handshake');
await helper.stop();
helper = await startHelper('genuine');
const stale = await attempt('request', { op: 'ping' });
check('old session is unknown after helper restart', !stale.ok && /HTTP 404/.test(stale.error), stale.error);
check('failed session is not reused', !(await op('hasSession')));
const fresh = await attempt('handshake');
check('fresh handshake after restart with persisted pins', fresh.ok && (await attempt('request', { op: 'ping' })).ok);

// ---- F. Wrong pin
await op('setPinHelper', 'aa'.repeat(32));
const badPin = await attempt('handshake');
check('non-canonical pinned key is refused locally', !badPin.ok && /InvalidKey/.test(badPin.error), badPin.error);
await op('setPinHelper', await op('randomPublic'));
const wrongPin = await attempt('handshake');
check('valid but wrong pinned helper key is rejected by the genuine helper', !wrongPin.ok && /HTTP 400/.test(wrongPin.error), wrongPin.error);
await op('setPinHelper', helperPub);

// ---- G. Live authenticated session, then a foreign listener takes the port
check('live authenticated session before takeover', (await attempt('handshake')).ok && (await attempt('request', { op: 'ping' })).ok && await op('hasSession'));
await helper.stop();
const rogue = await startHelper('rogue');
const leak = await attempt('request', marker);
check('request on the live session to the foreign listener fails', !leak.ok, leak.error);
check('Kadri discarded the session (no reuse)', !(await op('hasSession')));
const noFallback = await attempt('request', marker);
check('no further request is sent without a new authenticated session', !noFallback.ok && /no session/.test(noFallback.error));
const rogueHs = await attempt('handshake');
check('foreign listener fails the handshake', !rogueHs.ok && /AuthFailed/.test(rogueHs.error), rogueHs.error);
const pinBefore = await op('pin');
const roguePair = await attempt('pair', '12345678');
check('pairing against foreign listener with guessed code fails', !roguePair.ok && /ConfirmationFailed/.test(roguePair.error), roguePair.error);
check('failed pairing leaves the existing pin untouched', JSON.stringify(await op('pin')) === JSON.stringify(pinBefore));
await rogue.stop();
const cap = existsSync(join(state, 'rogue-capture.bin')) ? readFileSync(join(state, 'rogue-capture.bin')) : Buffer.alloc(0);
const capText = cap.toString('latin1');
const hdrs = existsSync(join(state, 'rogue-headers.txt')) ? readFileSync(join(state, 'rogue-headers.txt'), 'utf8') : '';
const paths = [...capText.matchAll(/(\/p1\/[a-z0-9/]+)\0/g)].map((m) => m[1]);
check('foreign listener received the live-session record (ciphertext only)', paths.includes('/p1/ch/rec'), paths.join(','));
check('no marker, cookie, URL, code or bearer bytes reached the foreign listener',
  !/MARKER|COOKIE|media\.invalid|12345678|download/.test(capText) && !/x-evp-key|^cookie:|^authorization:/im.test(hdrs), `${cap.length} body bytes captured`);

// ---- H. Wrong code, burned window, revocation, re-pairing, parallel pairing
helper = await startHelper('genuine');
helper.send('open-code 11111111'); await helper.wait(/^WINDOW_OPEN/);
const wrong = await attempt('pair', '22222222');
check('wrong code fails', !wrong.ok && /HTTP 400/.test(wrong.error), wrong.error);
const retry = await attempt('pair', '11111111');
check('window is burned after one failed attempt', !retry.ok, retry.error);
await op('handshake');
helper.send('revoke'); await helper.wait(/^REVOKED/);
const revokedSession = await attempt('request', { op: 'ping' });
check('revocation closes the running session', !revokedSession.ok && /HTTP 404/.test(revokedSession.error), revokedSession.error);
const revokedHs = await attempt('handshake');
check('revoked registration cannot handshake', !revokedHs.ok, revokedHs.error);
helper.send('open');
const code2 = (await helper.wait(/^CODE /, 10000, helper.lines.length)).split(' ')[1];
const both = await op('pairConcurrently', code2);
check('only one pairing flow at a time in Kadri', both.filter((r) => r.ok).length === 1 && both.some((r) => !r.ok && /already running/.test(r.error)), JSON.stringify(both));
const pin2 = await op('pin');
check('re-pairing after revocation yields a new registration', pin2.reg !== pin.reg && pin2.helper === helperPub && (await attempt('request', { op: 'ping' })).ok);
await helper.stop();
await b.close();
web.close();

const failed = results.filter((r) => !r.ok).length;
console.log(`RESULT ${BROWSER} ${failed ? 'FAIL' : 'PASS'} ${results.length - failed}/${results.length}`);
process.exit(failed ? 1 : 0);
