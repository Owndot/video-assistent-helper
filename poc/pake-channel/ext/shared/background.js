// ISOLATED FEASIBILITY PROOF – throwaway test extension, not Kadri.
// Shared by Chromium (MV3 service worker) and Firefox (MV2 background page).
// All cryptography runs in the one Rust core compiled to WASM; this file only
// moves opaque bytes, keeps identity/pin in extension-origin IndexedDB and
// enforces one pairing flow and one outstanding request at a time.
import init, * as core from './pkg/kadri_pairing_poc.js';

const api = globalThis.browser || globalThis.chrome;
const wasmReady = init({ module_or_path: new URL('./pkg/kadri_pairing_poc_bg.wasm', import.meta.url) });
const MAX_RESPONSE = 65535 + 21;
const PAIR_TTL_MS = 60_000;
const hex = (u8) => Array.from(u8, (b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (s) => new Uint8Array(s.match(/../g).map((h) => parseInt(h, 16)));

// ---- Storage: IndexedDB of the extension origin. Content scripts run with
// the web page's origin and cannot open this database; storage.local, by
// contrast, is readable by the extension's content scripts in both browsers.
let dbp = null;
function db() {
  if (!dbp) dbp = new Promise((res, rej) => {
    const r = indexedDB.open('kadri-pake-poc', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => res(r.result);
    r.onerror = () => { dbp = null; rej(r.error); };
  });
  return dbp;
}
async function kv(mode, fn) {
  const d = await db();
  return new Promise((res, rej) => {
    const tx = d.transaction('kv', mode), st = tx.objectStore('kv');
    const r = fn(st);
    tx.oncomplete = () => res(r && r.result);
    tx.onerror = tx.onabort = () => rej(tx.error);
  });
}
const kvGet = (k) => kv('readonly', (s) => s.get(k));
const kvSet = (k, v) => kv('readwrite', (s) => s.put(v, k));
const kvDel = (k) => kv('readwrite', (s) => s.delete(k));

async function identity() {
  await wasmReady;
  const stored = await kvGet('identity');
  if (stored) return unhex(stored);
  const id = core.identity_generate();
  await kvSet('identity', hex(id));
  return id;
}

// Bounded binary POST to the literal loopback endpoint. No redirects,
// no credentials, no retries, no alternative transport.
async function post(path, body) {
  const port = (await kvGet('port')) || 47899;
  const res = await fetch('http://127.0.0.1:' + port + path, {
    method: 'POST', body,
    headers: { 'Content-Type': 'application/octet-stream', 'X-Kadri-Helper': '2' },
    credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer',
    signal: AbortSignal.timeout(5000),
  });
  if (res.status !== 200) throw new Error('HTTP ' + res.status);
  const declared = Number(res.headers.get('content-length'));
  if (!Number.isSafeInteger(declared) || declared > MAX_RESPONSE) throw new Error('response too large');
  const reader = res.body.getReader(), out = new Uint8Array(declared);
  let at = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (at + value.length > declared) { reader.cancel(); throw new Error('framing'); }
    out.set(value, at); at += value.length;
  }
  if (at !== declared) throw new Error('truncated');
  return out;
}

let session = null;    // { ch, sid } – in memory only; never persisted
let chain = Promise.resolve();
let pairing = false;

function drop() { if (session) { try { session.ch.free(); } catch (e) {} session = null; } }

async function handshake() {
  drop();
  const id = await identity();
  const pin = await kvGet('pin');
  if (!pin) throw new Error('not paired');
  const hs = new core.KadriHandshake(id, unhex(pin.helper), unhex(pin.reg));
  try {
    const hs2 = await post('/p1/ch/hs', hs.hs1());
    const ch = hs.finish(hs2);                     // throws unless the pinned helper key answered
    session = { ch, sid: hex(hs2.subarray(5, 21)) };
    return session.sid;
  } finally {
    hs.free();
  }
}

// Strictly one outstanding record per channel: Noise transport is ordered.
// Any error is terminal for the session; there is no retry and no fallback.
function request(obj) {
  const run = async () => {
    if (!session) throw new Error('no session');
    const s = session;
    try {
      const reply = await post('/p1/ch/rec', s.ch.seal_control(new TextEncoder().encode(JSON.stringify(obj))));
      return s.ch.open(reply);
    } catch (e) {
      if (session === s) drop();
      throw e;
    }
  };
  const p = chain.then(run, run);
  chain = p.catch(() => {});
  return p;
}

async function pair(code) {
  if (pairing) throw new Error('pairing already running');
  pairing = true;
  try {
    const id = await identity();
    const flow = new core.KadriPairingFlow(code, id.subarray(32), Date.now(), PAIR_TTL_MS);
    try {
      const m1 = await post('/p1/pair/m0', flow.m0());
      const m3 = await post('/p1/pair/m2', flow.on_m1(m1, Date.now()));
      const pin = flow.on_m3(m3, Date.now());     // throws unless the helper proved the code
      // Only a successful pairing replaces an existing pin.
      await kvSet('pin', { reg: hex(pin.subarray(0, 16)), helper: hex(pin.subarray(16)) });
      // Complete the pairing with one authenticated round trip; this
      // confirms the helper's pending registration.
      await handshake();
      await request({ op: 'ping' });
      return { ok: true, reg: hex(pin.subarray(0, 16)) };
    } finally {
      flow.free();
    }
  } finally {
    pairing = false;
  }
}

async function transfer(t, size) {
  let offset = 0, peak = 0;
  for (;;) {
    const r = await request({ op: 'media', transfer: t, size, offset });
    const dv = new DataView(r.buffer, r.byteOffset, r.byteLength);
    if (r[0] === 3) {
      if (dv.getUint32(1) !== t || Number(dv.getBigUint64(5)) !== offset || offset !== size) throw new Error('bad end');
      return { t, bytes: offset, peakRecord: peak };
    }
    if (r[0] !== 2 || dv.getUint32(1) !== t || Number(dv.getBigUint64(5)) !== offset) throw new Error('bad media framing');
    const data = r.subarray(13);
    for (let i = 0; i < data.length; i++) if (data[i] !== ((offset + i) * 31 + 7 + t) % 256) throw new Error('bad media byte');
    peak = Math.max(peak, data.length);
    offset += data.length;
    if (offset > size) throw new Error('overrun');
  }
}

// PoC test hooks, reachable only from this extension's own test page.
const hooks = {
  ready: async () => ({ memory: (await wasmReady).memory.buffer.byteLength }),
  setPort: (port) => kvSet('port', port),
  pair: (code) => pair(code),
  pairConcurrently: async (code) => (await Promise.allSettled([pair(code), pair(code)])).map((r) => (r.status === 'fulfilled' ? { ok: true } : { ok: false, error: String(r.reason && r.reason.message || r.reason) })),
  handshake: () => handshake(),
  request: async (o) => { const r = await request(o); return { kind: r[0], body: new TextDecoder().decode(r.subarray(1)) }; },
  transfers: (list) => Promise.all(list.map(([t, n]) => transfer(t, n))),
  hasSession: () => !!session,
  drop: () => drop(),
  memory: async () => (await wasmReady).memory.buffer.byteLength,
  pin: () => kvGet('pin'),
  setPinHelper: async (helperHex) => { const p = await kvGet('pin'); await kvSet('pin', { reg: p.reg, helper: helperHex }); },
  forget: () => kvDel('pin'),
  randomPublic: async () => { await wasmReady; return hex(core.identity_generate().subarray(32)); },
  openOptions: () => api.runtime.openOptionsPage(),
  storageLocalKeys: async () => Object.keys(await api.storage.local.get(null)),
};
globalThis.poc = hooks;

api.runtime.onMessage.addListener((m, sender, reply) => {
  if (!m || sender.id !== api.runtime.id) return;
  const from = (page) => sender.url === api.runtime.getURL(page);
  let p = null;
  // Pairing only from the options page; test hooks only from the test page.
  if (m.t === 'pair' && from('options.html')) p = pair(String(m.code || ''));
  else if (m.t === 'test' && from('test.html') && Object.hasOwn(hooks, m.op)) p = Promise.resolve().then(() => hooks[m.op](m.arg));
  else { reply({ ok: false, error: 'refused' }); return; }
  p.then((v) => reply({ ok: true, value: v === undefined ? null : v }), (e) => reply({ ok: false, error: String(e && e.message || e) }));
  return true;
});
