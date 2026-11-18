'use strict';
// Code entry in a privileged extension page; it goes only to the extension's
// own background. Results never contain keys or the code.
const send = (m) => chrome.runtime.sendMessage(m).catch((e) => ({ ok: false, error: String(e && e.message || e) }));
async function refresh() {
  const r = await send({ t: 'status' });
  document.getElementById('state').textContent = r.ok ? (r.value.paired ? 'Gekoppelt (Registrierung ' + r.value.reg + '…)' : 'Nicht gekoppelt') : 'Status nicht verfügbar';
}
document.getElementById('pair').addEventListener('click', async () => {
  const out = document.getElementById('out');
  out.textContent = '…';
  const code = document.getElementById('code').value;
  document.getElementById('code').value = '';
  const r = await send({ t: 'pair', code });
  out.textContent = r.ok ? 'Gekoppelt' + (r.value.replacedPrevious ? ' (vorherige Kopplung ersetzt)' : '') : 'Fehlgeschlagen: ' + r.error;
  refresh();
});
refresh();
