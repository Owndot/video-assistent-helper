// ISOLATED ANDROID CAPABILITY TEST – throwaway test extension, not Kadri.
// Chromium MV3 service worker (target: Vivaldi Android). The Rust core runs
// as WASM; this file only moves opaque bytes, keeps identity and pin in the
// extension-origin IndexedDB and serialises requests.
//
// Deliberately absent (review R5): test hooks, a test bridge, any message
// that could set or delete a pin from outside the extension's own pages.
// Pairing is accepted only from options.html, the non-destructive self test
// only from selftest.html.
import init, * as core from './pkg/kadri_pairing_poc.js';

const api = globalThis.chrome;
const wasmReady = init({ module_or_path: new URL('./pkg/kadri_pairing_poc_bg.wasm', import.meta.url) });
const PORT = 47899;
const MAX_RESPONSE = 65535 + 21;
const PAIR_TTL_MS = 60_000;
const WORKER_ID = crypto.getRandomValues(new Uint32Array(1))[0].toString(16);
const hex = (u8) => Array.from(u8, (b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (s) => new Uint8Array(s.match(/../g).map((h) => parseInt(h, 16)));

// ---- Storage: IndexedDB of the extension origin only (not storage.local,
// which the extension's content scripts could read).
let dbp = null;
function db() {
  if (!dbp) dbp = new Promise((res, rej) => {
    const r = indexedDB.open('kadri-android-poc', 1);
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

// Counts service-worker starts persistently, so restarts are observable.
const workerStart = kvGet('workerStarts').then((n) => kvSet('workerStarts', (n || 0) + 1)).catch(() => {});

async function identity() {
  await wasmReady;
  const stored = await kvGet('identity');
  if (stored) return unhex(stored);
  const id = core.identity_generate();
  await kvSet('identity', hex(id));
  return id;
}

// Bounded binary POST to the literal loopback endpoint. No redirects,
// no credentials, no retries, no alternative transport, no legacy bearer.
async function post(path, body) {
  const res = await fetch('http://127.0.0.1:' + PORT + path, {
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

// ---- Channel: one in-memory session per worker; never persisted.
let session = null;
let chain = Promise.resolve();
const drop = () => { if (session) { try { session.ch.free(); } catch (e) {} session = null; } };

async function handshakeWith(pin) {
  drop();
  const hs = new core.KadriHandshake(await identity(), unhex(pin.helper), unhex(pin.reg));
  try {
    const hs2 = await post('/p1/ch/hs', hs.hs1());
    session = { ch: hs.finish(hs2), sid: hex(hs2.subarray(5, 21)), pin: pin.reg };
  } finally {
    hs.free();
  }
}

// Strictly one outstanding record; every error is terminal for the session.
function request(obj) {
  const run = async () => {
    if (!session) throw new Error('no session');
    const s = session;
    try {
      return s.ch.open(await post('/p1/ch/rec', s.ch.seal_control(new TextEncoder().encode(JSON.stringify(obj)))));
    } catch (e) {
      if (session === s) drop();
      throw e;
    }
  };
  const p = chain.then(run, run);
  chain = p.catch(() => {});
  return p;
}

// Uses the stored pin; reconnects with a fresh handshake when needed (after
// a worker restart there is no session). Never re-pins on failure.
async function ensureSession() {
  if (session) return;
  const pin = await kvGet('pin');
  if (!pin) throw new Error('not paired');
  await handshakeWith(pin);
}

// ---- Pairing (review R4): a new pin replaces the old one only after the
// first authenticated round trip with it succeeded. Any failure keeps the
// previously working pin and reports that explicitly.
let pairing = false;
async function pair(code) {
  if (pairing) throw new Error('pairing already running');
  pairing = true;
  const previous = await kvGet('pin');
  try {
    const id = await identity();
    // Flow deadlines on the worker's monotonic clock, not the wall clock (R3).
    const flow = new core.KadriPairingFlow(code, id.subarray(32), performance.now(), PAIR_TTL_MS);
    let candidate;
    try {
      const m1 = await post('/p1/pair/m0', flow.m0());
      const m3 = await post('/p1/pair/m2', flow.on_m1(m1, performance.now()));
      const pin = flow.on_m3(m3, performance.now());
      candidate = { reg: hex(pin.subarray(0, 16)), helper: hex(pin.subarray(16)) };
    } finally {
      flow.free();
    }
    try {
      await handshakeWith(candidate);
      const r = await request({ op: 'ping' });
      if (r[0] !== 1) throw new Error('unexpected confirmation reply');
    } catch (e) {
      drop();
      throw new Error('confirmation failed (' + (e && e.message) + '); ' + (previous ? 'previous pairing kept' : 'no pairing stored'));
    }
    await kvSet('pin', candidate);
    await kvSet('pinChangedAt', Date.now());
    return { ok: true, reg: candidate.reg.slice(0, 8), replacedPrevious: !!previous };
  } finally {
    pairing = false;
  }
}

// ---- Non-destructive self test (no pin changes, no pairing).
async function selftest(kind) {
  await workerStart;
  const out = { worker: WORKER_ID, workerStarts: await kvGet('workerStarts'), ua: navigator.userAgent };
  const t0 = performance.now();
  const w = await wasmReady;
  out.wasm = { ok: true, ms: Math.round(performance.now() - t0), linearMemory: w.memory.buffer.byteLength };
  try { (0, eval)('1'); out.evalBlocked = false; } catch (e) { out.evalBlocked = e instanceof EvalError || /unsafe-eval|Content Security/i.test(String(e)); }
  const r1 = crypto.getRandomValues(new Uint8Array(32)), r2 = crypto.getRandomValues(new Uint8Array(32));
  out.rng = { distinct: hex(r1) !== hex(r2), coreKeypair: core.identity_generate().length === 64 };
  out.identityStored = !!(await kvGet('identity'));
  out.paired = !!(await kvGet('pin'));
  if (kind !== 'helper') return out;
  const s = performance.now();
  try {
    await ensureSession();
    out.handshakeMs = Math.round(performance.now() - s);
    const p = await request({ op: 'ping' });
    out.ping = p[0] === 1 ? new TextDecoder().decode(p.subarray(1)) : 'unexpected';
    // Bounded binary dummy records (no real media): 1 MiB in <= 60 KiB records.
    let offset = 0, records = 0;
    const tt = performance.now();
    for (;;) {
      const r = await request({ op: 'dummy', transfer: 1, size: 1048576, offset });
      const dv = new DataView(r.buffer, r.byteOffset, r.byteLength);
      if (r[0] === 3) { if (Number(dv.getBigUint64(5)) !== offset) throw new Error('bad end'); break; }
      if (r[0] !== 2 || Number(dv.getBigUint64(5)) !== offset || r.length - 13 > 61440) throw new Error('bad record');
      for (let i = 13; i < r.length; i++) if (r[i] !== ((offset + i - 13) * 31 + 8) % 256) throw new Error('bad byte');
      offset += r.length - 13; records++;
    }
    out.dummy = { bytes: offset, records, ms: Math.round(performance.now() - tt) };
    out.linearMemoryAfter = w.memory.buffer.byteLength;
  } catch (e) {
    out.helperError = String(e && e.message || e);
  }
  return out;
}

api.runtime.onMessage.addListener((m, sender, reply) => {
  if (!m || sender.id !== api.runtime.id) return;
  const from = (page) => sender.url && sender.url.split('?')[0] === api.runtime.getURL(page);
  let p;
  if (m.t === 'pair' && from('options.html')) p = pair(String(m.code || ''));
  else if (m.t === 'status' && (from('options.html') || from('selftest.html'))) p = kvGet('pin').then((pin) => ({ paired: !!pin, reg: pin ? pin.reg.slice(0, 8) : null }));
  else if (m.t === 'selftest' && from('selftest.html')) p = selftest(m.kind === 'helper' ? 'helper' : 'browser');
  else { reply({ ok: false, error: 'refused' }); return; }
  p.then((v) => reply({ ok: true, value: v }), (e) => reply({ ok: false, error: String(e && e.message || e) }));
  return true;
});
