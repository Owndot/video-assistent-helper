import pwshim  # BROWSER=firefox → Tests in Firefox
exec(open('test_v8.py').read().split("results = []")[0])
KEY = '0123456789abcdef0123456789abcdef'
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
def helper_get(path, key):
    import urllib.request, urllib.error
    q = urllib.request.Request('http://127.0.0.1:47811' + path, headers={'X-EVP-Key': key})
    try: return urllib.request.urlopen(q, timeout=5).status
    except urllib.error.HTTPError as e: return e.code
def hlog():
    import urllib.request
    q = urllib.request.Request('http://127.0.0.1:47811/log', headers={'X-EVP-Key': KEY})
    return json.loads(urllib.request.urlopen(q).read())['log']
try:
    store = {}
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        def newctx(gmv):
            ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
            ctx.add_cookies([{'name': 'sess', 'value': 'abc', 'url': 'http://127.0.0.1:8792/', 'httpOnly': True, 'sameSite': 'Lax'}])
            ctx.expose_function('__gmSet', lambda k, v: store.__setitem__(k, v))
            ctx.add_init_script('window.__GMV = ' + json.dumps(gmv) + ';')
            ctx.add_init_script(SHIM); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
            return ctx
        check('Vor dem Koppeln: alter Schlüssel geht noch', helper_get('/ping', 'evp-1') == 200)
        ctx = newctx({}); page = ctx.new_page()
        page.goto('http://127.0.0.1:47811/pair#ffffffffffffffffffffffffffffffff'); page.wait_for_timeout(2500)
        txt = page.evaluate('() => document.getElementById("evp-pair").textContent')
        check('Falscher Schlüssel: nichts gespeichert, Hinweis', 'nicht geklappt' in txt and 'helperKey' not in store, txt)
        page.goto('about:blank'); page.goto('http://127.0.0.1:47811/pair#' + KEY); page.wait_for_timeout(2500)
        txt = page.evaluate('() => [document.getElementById("evp-pair").textContent, location.href]')
        check('Koppeln: Schlüssel gespeichert, Seite bestätigt', store.get('helperKey') == KEY and 'Verbunden' in txt[0], [txt, store])
        check('… Schlüssel aus der Adresse entfernt', '#' not in txt[1], txt[1])
        check('Nach dem Koppeln: alter Schlüssel abgelehnt', helper_get('/ping', 'evp-1') == 403)
        check('… neuer Schlüssel gilt', helper_get('/ping', KEY) == 200)
        ctx.close()
        # Player mit gespeichertem Schlüssel: Helfer antwortet, Laden über den Browser schickt Cookies nur an die eigene Seite
        open(H + '/gm_log.txt', 'w').close()
        ctx = newctx({'helperKey': KEY}); page = ctx.new_page()
        n0 = hlog().count('/ping')
        page.goto('http://127.0.0.1:8775/e2e_ck.html')
        page.wait_for_function('document.getElementById("v").currentTime > 1', timeout=20000)
        page.evaluate('() => { document.getElementById("v").muted = false; }'); page.wait_for_timeout(900)
        page.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        page.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000)
        page.wait_for_timeout(1200)
        check('Player spricht mit neuem Schlüssel', hlog().count('/ping') > n0)
        if not page.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
            page.mouse.click(200, 300); page.wait_for_timeout(350)
        page.locator('.vp .vb[aria-label^="Herunterladen"]').click()
        for _ in range(50):
            page.wait_for_timeout(500)
            if any(filecmp.cmp(f, H + '/src.webm', shallow=False) for f in glob.glob(H + '/out_e2e/job*/video.*')): break
        lg = open(H + '/gm_log.txt').read().strip().split('\n')
        own = [l for l in lg if '127.0.0.1:8792' in l]; foreign = [l for l in lg if 'localhost:8792' in l]
        check('Eigene Seite: mit Sitzung (Cookie)', own and all(l.endswith('COOKIE=ja') for l in own), own[:2])
        check('Fremde Adresse aus der „Playlist“: OHNE Cookie', foreign and all(l.endswith('COOKIE=nein') for l in foreign), foreign)
        check('Video trotzdem vollständig gespeichert', any(filecmp.cmp(f, H + '/src.webm', shallow=False) for f in glob.glob(H + '/out_e2e/job*/video.*')))
        b.close()
finally:
    for pr in procs:
        try: os.killpg(pr.pid, 9)
        except Exception: pass
print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
