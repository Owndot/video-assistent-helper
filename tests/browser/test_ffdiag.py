import pwshim  # BROWSER=firefox → Tests in Firefox
# Untersuchung: Kommen Zeiger-Ereignisse (Tippen, Halten, Wischen) auf der Gestenfläche des Players an?
import time, json
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e))); pg.on('console', lambda m: errs.append('console: ' + m.text) if m.type in ('error', 'warning') else None)
    pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=15000)
    time.sleep(0.8)
    print('UA', pg.evaluate('navigator.userAgent'))
    print('Vollbild', pg.evaluate('() => [String(document.fullscreenElement && document.fullscreenElement.className), innerWidth, innerHeight]'))
    pg.evaluate('''() => { window.__pl = []; const g = %s.querySelector(".vgest");
        ["pointerdown","pointermove","pointerup","pointercancel","gotpointercapture","lostpointercapture","mousedown","mouseup","touchstart","click"].forEach((t) => {
          g.addEventListener(t, (e) => { if (t !== "pointermove" || window.__pl.length < 40) window.__pl.push(t + "#" + (e.pointerId ?? "") + ":" + (e.pointerType || "") + "@" + Math.round(e.clientX) + "," + Math.round(e.clientY) + (e.isPrimary === false ? " np" : "")); }, true); });
        window.addEventListener("pointerdown", (e) => window.__pl.push("WIN pointerdown -> " + (e.composedPath()[0].className || e.composedPath()[0].tagName)), true); }''' % R)
    print('Element an 110,560:', pg.evaluate('() => { const e = document.elementFromPoint(110, 560); return e && (e.id || e.tagName); }'))
    pg.mouse.move(110, 560); pg.mouse.down(); time.sleep(0.8)
    print('Halten:', pg.evaluate('() => [%s.querySelector(".vv").playbackRate, %s.querySelector(".vind").textContent]' % (R, R)))
    pg.mouse.up(); time.sleep(0.3)
    pg.mouse.move(330, 650); pg.mouse.down(); pg.mouse.move(330, 400, steps=6); pg.mouse.up(); time.sleep(0.3)
    print('Ereignisse:', json.dumps(pg.evaluate('window.__pl')))
    pg.evaluate('() => { window.__pl = []; }')
    pg.touchscreen.tap(200, 400); time.sleep(0.5)
    print('Tippen (Touch):', json.dumps(pg.evaluate('window.__pl')), pg.evaluate('() => %s.querySelector(".vp").className' % R))
    print('Fehler:', errs[:10])
    b.close()
print('ERGEBNIS: Untersuchung fertig')
srv.shutdown()
