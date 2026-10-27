'use strict';
// Isolation probe: runs as an ordinary content script only on the static
// probe page, served from a different origin (port 47900) than the helper and writes what it could reach into the page. It must not
// obtain the identity, the pin, a pairing or a self test.
(async () => {
  if (location.port !== '47900' || location.pathname !== '/kadri-poc-probe.html') return;
  const out = {};
  const ask = async (m) => { try { return await chrome.runtime.sendMessage(m); } catch (e) { return 'error:' + (e && e.message); } };
  out.storageApi = typeof chrome.storage;
  out.pair = await ask({ t: 'pair', code: '12345678' });
  out.selftest = await ask({ t: 'selftest', kind: 'helper' });
  out.status = await ask({ t: 'status' });
  out.idb = await new Promise((res) => {
    const r = indexedDB.open('kadri-android-poc');
    r.onsuccess = () => { const names = Array.from(r.result.objectStoreNames); r.result.close(); res(names); };
    r.onerror = () => res('error');
  });
  document.documentElement.setAttribute('data-kadri-probe', JSON.stringify(out));
  const el = document.getElementById('probe');
  if (el) el.textContent = JSON.stringify(out, null, 2);
})();
