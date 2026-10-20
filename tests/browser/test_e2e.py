exec(open('test_v8.py').read().split("results = []")[0])
import subprocess, os, filecmp, glob, shutil
H = '../java'
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
shutil.rmtree(H + '/out_e2e', ignore_errors=True)
procs = [subprocess.Popen(c, cwd=H, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True) for c in
         (['python3', 'media_srv.py'], ['python3', 'gm_proxy.py'], ['java', '-cp', 'classes', 'TestHelper'])]
time.sleep(2.5)
SHIM = r"""(() => {
  const SESSION = 'sess=abc; pub=1';   // so schickt der echte Browser es mit (sess ist HttpOnly – für Seiten-Skripte unsichtbar)
  const b64 = (buf) => { let s = ''; const u = new Uint8Array(buf); for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); };
  const unb64 = (s) => { const b = atob(s), u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return u.buffer; };
  window.GM_getValue = (k, d) => (window.__GMV && k in window.__GMV) ? window.__GMV[k] : d;
  window.GM_setValue = (k, v) => { (window.__GMV = window.__GMV || {})[k] = v; if (window.__gmSet) window.__gmSet(k, String(v)); };
  window.GM_xmlhttpRequest = function (o) {
    const toHelper = /^http:\/\/127\.0\.0\.1:47811\//.test(o.url);
    const send = async () => {
      let data = null;
      if (o.data instanceof ArrayBuffer) data = b64(o.data);
      else if (o.data && o.data.buffer) data = b64(o.data.buffer);
      else if (typeof o.data === 'string') data = btoa(o.data);
      const r = await fetch('http://127.0.0.1:8793/', { method: 'POST', body: JSON.stringify({ method: o.method || 'GET', url: o.url, headers: o.headers || {}, data: data, cookie: (toHelper || o.anonymous) ? '' : SESSION, timeout: Math.ceil((o.timeout || 30000) / 1000) }) });
      const j = await r.json();
      if (!j.status) { o.onerror && o.onerror({}); return; }
      const body = unb64(j.body);
      o.onload && o.onload({ status: j.status, responseHeaders: j.headers, response: o.responseType === 'arraybuffer' ? body : new TextDecoder().decode(body), responseText: new TextDecoder().decode(body) });
    };
    send().catch(() => o.onerror && o.onerror({}));
  };
})();"""
def hlog():
    import urllib.request
    q = urllib.request.Request('http://127.0.0.1:47811/log', headers={'X-EVP-Key': 'evp-1'})
    return json.loads(urllib.request.urlopen(q).read())['log']
try:
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_cookies([{'name': 'sess', 'value': 'abc', 'url': 'http://127.0.0.1:8792/', 'httpOnly': True, 'sameSite': 'Lax'},
                         {'name': 'pub', 'value': '1', 'url': 'http://127.0.0.1:8775/', 'httpOnly': False, 'sameSite': 'Lax'}])
        ctx.add_init_script(SHIM); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        errors = []
        page = ctx.new_page(); page.on('pageerror', lambda e: errors.append(str(e)))
        toasts = []
        def run(name):
            page.goto('http://127.0.0.1:8775/e2e_%s.html' % name)
            page.wait_for_function('document.getElementById("v").currentTime > 1', timeout=20000)
            check(name + ': Seite spielt (Browser hat die Sitzung)', True)
            page.evaluate('() => { document.getElementById("v").muted = false; }'); page.wait_for_timeout(900)
            page.locator('button[aria-label="Im Videoplayer öffnen"]').click()
            page.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000)
            page.wait_for_timeout(1200)
            if not page.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
                page.mouse.click(200, 300); page.wait_for_timeout(350)
            page.locator('.vp .vb[aria-label^="Herunterladen"]').click()
            # bis zu 25 s auf das Ergebnis warten
            for _ in range(50):
                page.wait_for_timeout(500)
                t = page.evaluate('() => { const t = %s.querySelector(".toast"); return t ? t.textContent : ""; }' % R)
                if t and (not toasts or toasts[-1] != t): toasts.append(t)
                files = glob.glob(H + '/out_e2e/job*/video.*')
                if any(filecmp.cmp(f, H + '/src.webm', shallow=False) for f in files if os.path.getsize(f) == os.path.getsize(H + '/src.webm')):
                    break
            return [f for f in glob.glob(H + '/out_e2e/job*/video.*') if filecmp.cmp(f, H + '/src.webm', shallow=False)]
        good = run('ck')
        lg = hlog()
        print('   Helfer-Protokoll:', ' | '.join(l for l in lg.split('\n') if l.startswith('/download') or l.startswith('/pull'))[:400])
        print('   Hinweise:', toasts)
        check('ck: erst direkt (abgewiesen), dann über den Browser', '/download c=pub=1' in lg and '/download p=1' in lg and '/pull' in lg, '')
        check('ck: Datei über den Browser vollständig gespeichert', len(good) == 1, good)
        check('ck: Hinweis „Server verlangt die Browser-Sitzung“', any('Browser-Sitzung' in t for t in toasts), toasts)
        shutil.rmtree(H + '/out_e2e', ignore_errors=True); toasts.clear()
        n_before = hlog().count('p=1')
        good = run('pub')
        lg = hlog()
        check('pub: Cookie der Seite reicht – direkt, ohne Umweg', len(good) == 1 and lg.count('p=1') == n_before, [good, lg.count('p=1') - n_before])
        print('Fehler der Seite:', errors)
        b.close()
finally:
    for pr in procs:
        try: os.killpg(pr.pid, 9)
        except Exception: pass
print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
