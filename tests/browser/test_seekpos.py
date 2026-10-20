import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys, re
exec(open('test_v8.py').read().split("results = []")[0])
SCRIPT2 = re.sub(r'downloadEverywhere:\s*false', 'downloadEverywhere: true', SCRIPT)
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok))); print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for tag, vw in [('port', {'width': 400, 'height': 800}), ('land', {'width': 860, 'height': 400})]:
        ctx = b.new_context(viewport=vw, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT2)
        ctx.add_init_script("navigator.share = (d) => { window.__shared = d; return Promise.resolve(); };")
        errors = []
        pg = ctx.new_page(); pg.on('pageerror', lambda e: errors.append(str(e)))
        pg.goto('http://127.0.0.1:8775/direct.html')
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && v.videoWidth > 0; }' % R, timeout=15000)
        time.sleep(0.5)
        W, H = vw['width'], vw['height']
        y = int(H * 0.75) if tag == 'port' else int(H * 0.3)
        pg.mouse.move(W * 0.12, y); pg.mouse.down(); pg.mouse.move(W * 0.42, y, steps=8)
        info = pg.evaluate('''() => { const i = %s.querySelector(".vseek"); const r = i.getBoundingClientRect(); const v = %s.querySelector(".vv").getBoundingClientRect();
            return [i.classList.contains("on"), Math.round(r.top + r.height / 2 - (v.top + v.height / 2)), Math.round(r.left + r.width / 2 - (v.left + v.width / 2)), i.querySelector("b").textContent, getComputedStyle(%s.querySelector(".vchrome")).opacity]; }''' % (R, R, R))
        pg.screenshot(path=OUT + '/seek_%s.png' % tag)
        pg.mouse.up(); time.sleep(0.8)
        gone = not pg.evaluate('() => %s.querySelector(".vseek").classList.contains("on")' % R)
        check(tag + ': Spul-Anzeige mitten im Video, Leisten ausgeblendet', info[0] and abs(info[1]) <= 3 and abs(info[2]) <= 3 and ':' in info[3] and float(info[4]) < 0.25, info)
        check(tag + ': nach dem Loslassen wieder weg', gone)
        if not pg.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
            pg.mouse.click(W / 2, H * 0.3); time.sleep(0.4)
        order = pg.evaluate('() => Array.from(%s.querySelectorAll(".vcluster button")).map(b => b.getAttribute("aria-label"))' % R)
        check(tag + ': Download oben rechts bei Teilen und 1×', order[0].startswith('Herunterladen') and 'Geschwindigkeit' in order, order)
        pg.locator('button[aria-label="Herunterladen (z. B. mit YTDLnis)"]').last.click(); time.sleep(0.6)
        sh = pg.evaluate('() => [window.__shared ? window.__shared.url : null, !!%s.querySelector(".vp")]' % R)
        print('   Hinweis:', pg.evaluate('() => %s.querySelector(".toast").textContent' % R))
        check(tag + ': Download öffnet Teilen, Player bleibt offen', sh[1] and (sh[0] or pg.evaluate('() => %s.querySelector(".toast").classList.contains("on")' % R)), sh)
        if errors: print('Fehler:', errors[:3])
        ctx.close()
    b.close()
srv.shutdown()
f = [r for r in results if not r[1]]; print('ERGEBNIS:', len(results)-len(f), 'von', len(results)); sys.exit(1 if f else 0)
