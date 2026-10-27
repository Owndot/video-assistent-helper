'use strict';
// The code is typed here, in a privileged extension page, and handed only to
// the extension's own background. It never touches a web page or the port.
document.getElementById('pair').addEventListener('click', async () => {
  const out = document.getElementById('out');
  out.textContent = '…';
  const code = document.getElementById('code').value;
  document.getElementById('code').value = '';
  const r = await chrome.runtime.sendMessage({ t: 'pair', code });
  out.textContent = r && r.ok ? 'Gekoppelt' : 'Fehlgeschlagen: ' + (r && r.error);
});
