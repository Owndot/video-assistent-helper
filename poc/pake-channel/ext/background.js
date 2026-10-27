// ISOLATED FEASIBILITY PROOF – throwaway test extension, not Kadri.
// All cryptography runs in the Rust core compiled to WASM. This file only
// moves opaque bytes, persists the pin and enforces one-request-at-a-time.
import init, * as core from './pkg/kadri_pairing_poc.js';

const wasmReady = init({ module_or_path: new URL('./pkg/kadri_pairing_poc_bg.wasm', import.meta.url) });
const MAX_RESPONSE = 65535 + 21;
const PAIR_TTL_MS = 60_000;
const hex = (u8) => Array.from(u8, (b) => b.toString(16).padStart(2, '0')).join('');
const unhex = (s) => new Uint8Array(s.match(/../g).map((h) => parseInt(h, 16)));

async function settings() {
  const s = await chrome.storage.local.get(['port', 'identity', 'pin']);
  return { port: s.port || 47899, identity: s.identity ? unhex(s.identity) : null, pin: s.pin || null };
}

async function identity() {
  await wasmReady;
  const s = await settings();
  if (s.identity) return s.identity;
  const id = core.identity_generate();
  await chrome.storage.local.set({ identity: hex(id) });
  return id;
}

// Bounded binary POST to the literal loopback endpoint. No redirects,
// no credentials, no retries, no alternative transport.
async function post(path, body) {
  const { port } = await settings();
  const res = await fetch('http://127.0.0.1:' + port + path, {
    method: 'POST', body, headers: { 'Content-Type': 'application/octet-stream' },
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

const trace = [];
function log(e) { trace.push(e); if (trace.length > 200) trace.shift(); }

async function pair(code) {
  const id = await identity();
  const flow = new core.KadriPairingFlow(code, id.subarray(32), Date.now(), PAIR_TTL_MS);
  try {
    const m1 = await post('/p1/pair/m0', flow.m0());
    const m2 = flow.on_m1(m1, Date.now());
    const m3 = await post('/p1/pair/m2', m2);
    const pin = flow.on_m3(m3, Date.now());       // throws unless the helper proved the code
    await chrome.storage.local.set({ pin: { reg: hex(pin.subarray(0, 16)), helper: hex(pin.subarray(16)) } });
    log('paired');
    return { ok: true, reg: hex(pin.subarray(0, 16)) };
  } finally {
    flow.free();
  }
}

let session = null;    // { ch, sid } – in memory only; never persisted
let chain = Promise.resolve();

async function handshake() {
  const s = await settings();
  if (!s.pin || !s.identity) throw new Error('not paired');
  const hs = new core.KadriHandshake(s.identity, unhex(s.pin.helper), unhex(s.pin.reg));
  try {
    const hs2 = await post('/p1/ch/hs', hs.hs1());
    const ch = hs.finish(hs2);                     // throws unless the pinned helper key answered
    session = { ch, sid: hex(hs2.subarray(5, 21)) };
    log('handshake ' + session.sid);
    return session.sid;
  } finally {
    hs.free();
  }
}

function drop() { if (session) { try { session.ch.free(); } catch (e) {} session = null; } }

// Strictly one outstanding record per channel: Noise transport is ordered.
function request(obj) {
  const run = async () => {
    if (!session) throw new Error('no session');
    try {
      const rec = session.ch.seal_control(new TextEncoder().encode(JSON.stringify(obj)));
      const reply = await post('/p1/ch/rec', rec);
      return session.ch.open(reply);
    } catch (e) { drop(); throw e; }
  };
  const p = chain.then(run, run);
  chain = p.catch(() => {});
  return p;
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

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  // Only the extension's own options page may start pairing.
  if (sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL('options.html') || !m || m.t !== 'pair') return;
  pair(String(m.code || '')).then(reply, (e) => reply({ ok: false, error: String(e && e.message || e) }));
  return true;
});

// Test hooks for the automated runner (PoC only).
globalThis.poc = {
  ready: () => wasmReady.then((w) => ({ memory: w.memory.buffer.byteLength })),
  setPort: (port) => chrome.storage.local.set({ port }),
  pair, handshake, request: async (o) => { const r = await request(o); return { kind: r[0], body: new TextDecoder().decode(r.subarray(1)) }; },
  transfer, drop, trace: () => trace.slice(),
  memory: async () => (await wasmReady).memory.buffer.byteLength,
  pin: async () => (await settings()).pin,
  corruptPin: async (helperHex) => { const s = await settings(); await chrome.storage.local.set({ pin: { reg: s.pin.reg, helper: helperHex } }); },
  forget: () => chrome.storage.local.remove(['pin']),
};
