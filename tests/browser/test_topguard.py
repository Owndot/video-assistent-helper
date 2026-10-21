import pwshim  # BROWSER=firefox → Tests in Firefox
exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    page = ctx.new_page(); errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
    page.goto('http://127.0.0.1:8775/direct.html')
    page.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    page.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    page.locator('button[aria-label="Im Videoplayer öffnen"]').click(); time.sleep(1.5)
    vol = lambda: page.evaluate('() => %s.querySelector(".vv").volume' % R)
    dim = lambda: page.evaluate('() => [...%s.querySelectorAll("*")].filter(e => e.className && /dim/.test(e.className)).map(e => +getComputedStyle(e).opacity)[0]' % R)
    def swipe(x, y0, y1):
        # Leisten ausblenden: auf einem Knopf startet absichtlich keine Geste
        page.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.25)
        page.mouse.move(x, y0); page.mouse.down(); page.mouse.move(x, y1, steps=8); page.mouse.up(); time.sleep(0.5)
    for (W, H) in ((400, 800), (800, 400)):
        page.set_viewport_size({'width': W, 'height': H}); time.sleep(0.8)
        zone = max(H * 0.15, 48)
        v0 = vol(); swipe(W - 70, zone - 20, zone + 140)
        check('%dx%d: oben (%d px) runterziehen – Lautstärke bleibt' % (W, H, zone - 20), abs(vol() - v0) < 0.001, [v0, vol()])
        d0 = dim(); swipe(70, 10, 160)
        check('%dx%d: ganz oben links runterziehen – Helligkeit bleibt' % (W, H), abs(dim() - d0) < 0.001, [d0, dim()])
        v0 = vol(); swipe(W - 70, zone + 30, zone + 160)
        check('%dx%d: knapp darunter – Lautstärke ändert sich' % (W, H), vol() < v0 - 0.05, [v0, vol()])
        # unten (Home-Wischgeste): Hochwischen vom unteren Rand ändert nichts
        v0 = vol(); swipe(W - 70, H - 15, H - 15 - 140)
        check('%dx%d: unten (%d px) hochwischen – Lautstärke bleibt' % (W, H, zone), abs(vol() - v0) < 0.001, [v0, vol()])
        swipe(70, zone + 20, zone + 120)                      # erst etwas abdunkeln …
        d0 = dim(); swipe(70, H - 10, H - 10 - 140)           # … dann vom unteren Rand hoch
        check('%dx%d: unten links hochwischen – Helligkeit bleibt' % (W, H), abs(dim() - d0) < 0.001 and d0 > 0.01, [d0, dim()])
        print('   Maße:', page.evaluate('(p) => { const w = %s.querySelector(".vp"); const e = %s.elementFromPoint ? %s.elementFromPoint(p[0], p[1]) : null; return [w.clientHeight, innerHeight, w.getBoundingClientRect().top, e ? e.className || e.tagName : null]; }' % (R, R, R), [W - 70, H - zone - 30]))
        page.evaluate('''() => { window.__pl = []; const g = %s.querySelector(".vgest"); ["pointerdown","pointermove","pointerup","pointercancel"].forEach((t) => g.addEventListener(t, (e) => { if (window.__pl.length < 30) window.__pl.push(t[7] + Math.round(e.clientY) + (e.buttons ? "b" : "")); }, true)); }''' % R)
        v0 = vol(); swipe(W - 70, H - zone - 30, H - zone - 130)
        print('   Ereignisse:', page.evaluate('window.__pl.join(" ")'), '| Anzeige:', page.evaluate('() => [%s.querySelector(".vside").className, %s.querySelector(".vside b").textContent, %s.querySelector(".vind").textContent]' % (R, R, R)))
        check('%dx%d: knapp darüber – Lautstärke ändert sich' % (W, H), vol() > v0 + 0.05, [v0, vol()])
        # zurück auf voll
        swipe(W - 70, H - zone - 10, zone + 10)
        swipe(70, H - zone - 10, zone + 10)
    print('Fehler der Seite:', errs)
    print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
    b.close()
