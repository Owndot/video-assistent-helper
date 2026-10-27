'use strict';
// Isolation probe (PoC only): runs as an ordinary content script on the test
// web page and reports what it can reach. It must not obtain the identity,
// the pin, a pairing or the test hooks.
(async () => {
  if (location.port !== '47900') return;
  const api = globalThis.browser || globalThis.chrome;
  const out = {};
  const ask = async (m) => { try { return await api.runtime.sendMessage(m); } catch (e) { return 'error:' + (e && e.message); } };
  try { out.storageLocal = await api.storage.local.get(null); } catch (e) { out.storageLocal = 'error:' + e.message; }
  out.pair = await ask({ t: 'pair', code: '12345678' });
  out.test = await ask({ t: 'test', op: 'pin' });
  out.idb = await new Promise((res) => {
    const r = indexedDB.open('kadri-pake-poc');
    r.onsuccess = () => res(Array.from(r.result.objectStoreNames));
    r.onerror = () => res('error');
  });
  document.documentElement.setAttribute('data-kadri-probe', JSON.stringify(out));
})();
