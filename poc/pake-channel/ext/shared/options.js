'use strict';
// The code is typed here, in a privileged extension page, and handed only to
// the extension's own background. It never touches a web page or the port.
const api = globalThis.browser || globalThis.chrome;
document.getElementById('pair').addEventListener('click', async () => {
  const out = document.getElementById('out');
  out.textContent = '…';
  const code = document.getElementById('code').value;
  document.getElementById('code').value = '';
  let r;
  try { r = await api.runtime.sendMessage({ t: 'pair', code }); } catch (e) { r = { ok: false, error: String(e && e.message || e) }; }
  out.textContent = r && r.ok ? 'Gekoppelt' : 'Fehlgeschlagen: ' + (r && r.error);
});
