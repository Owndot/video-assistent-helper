import pwshim  # BROWSER=firefox → Tests in Firefox
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; ok = []
def check(n, c, i=''): ok.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e))); pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=15000)
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 20; %s.querySelector(".vp").classList.add("ctl"); }' % (R, R)); time.sleep(0.5)
    st = lambda: pg.evaluate('''() => { const r = %s; const f = r.querySelector("button[aria-label='10 s vor']"), k = r.querySelector("button[aria-label='10 s zurück']"), pl = r.querySelector(".vplay");
        return { knopf: f.classList.contains("go"), zahl: f.querySelector("text").textContent, scheibe: r.querySelector(".vsfx.r").classList.contains("on"),
                 gedimmt: r.querySelector(".vp").classList.contains("skipfx"), pause: getComputedStyle(pl).opacity, zurueck: getComputedStyle(k).opacity, vor: getComputedStyle(f).opacity }; }''' % R)
    # Doppeltipp rechts neben dem Knopf, Leisten sichtbar
    pg.mouse.click(360, 250); time.sleep(0.08); pg.mouse.click(360, 250); time.sleep(0.3)
    a = st(); pg.screenshot(path=OUT + '/sfx2_ctl.png')
    check('Leisten sichtbar: +10-Knopf selbst animiert, keine zweite Scheibe', a['knopf'] and not a['scheibe'] and a['zahl'] == '10', a)
    time.sleep(0.25); a = st()
    check('nichts wird abgedunkelt (Pause und −10 normal sichtbar)', float(a['pause']) > 0.95 and float(a['zurueck']) > 0.95, a)
    time.sleep(1.0)
    a = st(); check('danach wieder normal, Zahl zurück auf 10', not a['gedimmt'] and a['zahl'] == '10' and float(a['pause']) > 0.9, a)
    # +10-Knopf zweimal schnell
    pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R)
    t0 = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    # so schnell wie ein Finger (Playwrights .click() wartet sonst, bis der Knopf nicht mehr federt – dann ist die Serie vorbei)
    tap = lambda: pg.locator('button[aria-label="10 s vor"]').click(force=True, no_wait_after=True)
    tap(); time.sleep(0.15); tap(); time.sleep(0.15)
    a = st(); t1 = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    check('+10-Knopf zweimal schnell: zählt 20, springt 20 s', a['zahl'] == '20' and a['knopf'] and abs(t1 - t0 - 20) < 1.5, [a['zahl'], round(t1 - t0, 1)])
    time.sleep(1.0)
    # Leisten ausgeblendet: freie Scheibe wie bisher
    pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.4)
    pg.mouse.click(330, 300); time.sleep(0.08); pg.mouse.click(330, 300); time.sleep(0.12)
    a = st(); check('Leisten aus: freie Glasscheibe', a['scheibe'] and not a['knopf'], a)
    # dünne Linie ganz unten
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 22; v.play(); %s.querySelector(".vp").classList.remove("ctl"); }' % (R, R)); time.sleep(1.0)
    m = pg.evaluate('''() => { const r = %s, el = r.querySelector(".vmini"), v = r.querySelector(".vv"); const fr = el.querySelector("i").getBoundingClientRect(), wr = el.getBoundingClientRect();
        return [getComputedStyle(el).opacity, Math.round(fr.width / wr.width * 100), Math.round(v.currentTime / v.duration * 100), Math.round(r.querySelector(".vp").getBoundingClientRect().bottom - wr.bottom), Math.round(wr.height * 10) / 10, innerHeight, Math.round(r.querySelector(".vp").getBoundingClientRect().bottom), !!document.fullscreenElement]; }''' % R)
    check('Leisten aus: Linie ganz unten sichtbar, Stand stimmt', float(m[0]) > 0.95 and abs(m[1] - m[2]) <= 2 and m[3] == 6, m)
    pg.screenshot(path=OUT + '/mini.png')
    # angehalten, damit kein noch laufender Zeitgeber die Leisten genau beim Messen wieder ausblendet (langsame Rechner)
    pg.evaluate('() => { %s.querySelector(".vv").pause(); %s.querySelector(".vp").classList.add("ctl"); }' % (R, R)); time.sleep(0.9)
    o = pg.evaluate('() => getComputedStyle(%s.querySelector(".vmini")).opacity' % R)
    check('Leisten an: Linie ausgeblendet', float(o) < 0.05, o)
    print('Fehler:', errs[:3])
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
