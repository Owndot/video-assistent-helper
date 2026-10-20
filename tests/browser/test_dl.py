import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok))); print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
REC = """const __ac = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () {
  if (this.download) { window.__dl = { name: this.download, href: this.href }; fetch(this.href).then(r => r.blob()).then(b => { window.__dl.size = b.size; }); return; }
  return __ac.call(this); };"""
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(REC); ctx.add_init_script(SCRIPT)
    errors = []
    pg = ctx.new_page(); pg.on('pageerror', lambda e: errors.append(str(e)))
    pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
    nb = pg.evaluate('() => Array.from(%s.querySelectorAll(".pill button")).map(b => b.getAttribute("aria-label"))' % R)
    check('Webseite: nur der Player-Knopf', nb == ['Im Videoplayer öffnen'], nb)
    pg.locator('button[aria-label="Im Videoplayer öffnen"]').click()
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=15000)
    time.sleep(0.5)
    if not pg.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
        pg.mouse.click(200, 300); time.sleep(0.4)
    order = pg.evaluate('() => Array.from(%s.querySelectorAll(".vhead .vtools:last-child button")).filter(b => !b.hidden).map(b => b.getAttribute("aria-label"))' % R)
    check('Unten rechts: Bild-in-Bild zuerst, Drehen ganz außen', order[0] == 'Bild-in-Bild' and order[-1] == 'Drehen', order)
    vis = pg.evaluate('() => !%s.querySelector("button[aria-label=\'Herunterladen (z. B. mit YTDLnis)\']").hidden' % R)
    check('Echte Videodatei: Download-Knopf sichtbar', vis)
    pg.locator('button[aria-label="Herunterladen (z. B. mit YTDLnis)"]').click()
    pg.wait_for_function('() => window.__dl && window.__dl.size > 0', timeout=15000)
    dl = pg.evaluate('() => window.__dl')
    real = os.path.getsize(WEB + '/clip.webm')
    check('Direkter Download speichert die ganze Datei', dl['size'] == real and dl['name'].endswith('.webm'), [dl['name'], dl['size'], real])
    time.sleep(0.3)
    check('Hinweis "im Browser"', 'Browser' in pg.evaluate('() => %s.querySelector(".toast").textContent' % R))
    # Stream (HLS): keine Datei -> kein Download-Knopf
    pg2 = ctx.new_page(); pg2.goto('http://127.0.0.1:8775/hls2.html')
    pg2.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg2.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
    pg2.locator('button[aria-label="Im Videoplayer öffnen"]').click()
    pg2.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=20000)
    time.sleep(0.8)
    h2 = pg2.evaluate('() => %s.querySelector("button[aria-label=\'Herunterladen (z. B. mit YTDLnis)\']").hidden' % R)
    check('Stream ohne Datei: kein Download-Knopf', h2)
    b.close()
srv.shutdown()
print('Fehler:', json.dumps(errors[:4]))
f = [r for r in results if not r[1]]; print('ERGEBNIS:', len(results)-len(f), 'von', len(results)); sys.exit(1 if f else 0)
