import pwshim  # BROWSER=firefox → Tests in Firefox
# Diagnose ohne Zugangsschlüssel: Werte nach „?“/„#“ und lange, zufällig aussehende Pfadteile werden ausgeblendet –
# auch in Zeilen, die nur das ENDE einer Adresse zeigen (dort stand früher der Schlüssel ohne sein „?token=“ davor).
exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
TOK = 'Zq8TkW3mPx7LrB2vN9cY4hJ6dF1sG5aE0uKoQiXw'   # 40 Zeichen: passt genau in „…letzte 40 Zeichen“
PTOK = 'Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56'           # Schlüssel im Pfad
CLIP = open(WEB + '/clip.webm', 'rb').read()
PAGE = '''<!doctype html><html><head><title>Diagnose</title></head><body style="margin:0">
<video id=v muted autoplay playsinline src="/media/%s/clip.webm?id=7&amp;token=%s" style="width:100%%;display:block"></video></body></html>''' % (PTOK, TOK)
PRE2 = PRE + "\nwindow.GM_registerMenuCommand = (n, f) => { window.__diag = f; };"
def media(route):
    u = route.request.url
    if u.endswith('/seite'): route.fulfill(status=200, content_type='text/html', body=PAGE)
    elif '/clip.webm' in u:
        rg = route.request.headers.get('range', ''); n = len(CLIP)
        if rg.startswith('bytes='):
            a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
            route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
        else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
    else: route.fulfill(status=404, body='')
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE2); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    ctx.route('https://diag.test/**', media)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto('https://diag.test/seite')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000); time.sleep(0.8)
    pg.evaluate('() => window.__diag && window.__diag()'); time.sleep(0.8)
    d = pg.evaluate('() => { const t = %s.querySelector(".diag textarea"); return t ? t.value : ""; }' % R)
    check('Diagnose da', len(d) > 200, len(d))
    parts = [TOK[i:i + 8] for i in range(0, len(TOK) - 7, 4)]
    leak = [x for x in parts if x in d]
    check('kein Stück des Schlüssels aus „?token=“ (auch nicht in gekürzten Zeilen)', not leak, leak)
    pleak = [x for x in (PTOK[i:i + 8] for i in range(4, len(PTOK) - 7, 4)) if x in d]
    check('kein Stück des Schlüssels im Pfad (nur die ersten 4 Zeichen)', not pleak, pleak)
    tail = [l for l in d.split('\n') if 'clip.webm' in l]
    check('Adressen bleiben erkennbar (Datei, Namen der Angaben)', tail and any('token=…' in l or 'clip.webm' in l for l in tail), tail[:3])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(1 for r in results if r[1]), len(results)))
