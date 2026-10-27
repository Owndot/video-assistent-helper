'use strict';
// Non-destructive checks only; the background refuses anything else from here.
const out = document.getElementById('out');
async function run(kind) {
  out.textContent = '…';
  const r = await chrome.runtime.sendMessage({ t: 'selftest', kind }).catch((e) => ({ ok: false, error: String(e) }));
  const page = {
    pageWasm: typeof WebAssembly === 'object',
    pageIndexedDB: typeof indexedDB === 'object',
    pageCrypto: !!(crypto && crypto.getRandomValues),
  };
  out.textContent = JSON.stringify({ at: new Date().toISOString(), kind, page, result: r }, null, 2);
}
document.getElementById('browser').addEventListener('click', () => run('browser'));
document.getElementById('helper').addEventListener('click', () => run('helper'));
document.getElementById('copy').addEventListener('click', () => navigator.clipboard.writeText(out.textContent).catch(() => {}));
