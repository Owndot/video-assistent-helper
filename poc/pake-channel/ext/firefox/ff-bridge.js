'use strict';
// Firefox PoC test plumbing only, absent from any product: WebDriver cannot
// script moz-extension:// pages, so this extension page pulls commands from
// the local test runner (127.0.0.1:47902) and posts the results back.
// The options page only executes the UI pairing step on its own form.
(async () => {
  const role = location.pathname.endsWith('options.html') ? 'options' : 'test';
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  for (;;) {
    let cmd;
    try {
      const r = await fetch('http://127.0.0.1:47902/cmd?role=' + role, { cache: 'no-store' });
      if (r.status !== 200) continue;
      cmd = await r.json();
    } catch (e) { await sleep(500); continue; }
    let result;
    try {
      if (role === 'options' && cmd.op === 'uiPair') {
        document.getElementById('code').value = cmd.arg;
        document.getElementById('pair').click();
        const out = document.getElementById('out');
        while (out.textContent === '…' || out.textContent === '') await sleep(100);
        result = { ok: true, value: out.textContent };
      } else if (role === 'test') {
        result = await browser.runtime.sendMessage({ t: 'test', op: cmd.op, arg: cmd.arg });
      } else result = { ok: false, error: 'not allowed here' };
    } catch (e) { result = { ok: false, error: String(e && e.message || e) }; }
    await fetch('http://127.0.0.1:47902/result', { method: 'POST', body: JSON.stringify({ id: cmd.id, result }) });
    if (role === 'options') { window.close(); return; }
  }
})();
