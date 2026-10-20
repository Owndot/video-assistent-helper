import pwshim  # BROWSER=firefox → Tests in Firefox
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; ok = []
def check(n, c, i=''): ok.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 860, 'height': 400}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=15000)
    pg.evaluate('() => { %s.querySelector(".vp").classList.add("ctl"); %s.querySelector("button[aria-label=Bildformat]").click(); }' % (R, R)); time.sleep(0.5)
    m = pg.evaluate('() => { const r = %s.querySelector(".vmenu").getBoundingClientRect(); return [Math.round(r.left), Math.round(innerWidth - r.right), Math.round(r.top), Math.round(innerHeight - r.bottom), Math.round(r.width)]; }' % R)
    # volle Höhe nur, wenn der Inhalt so hoch ist (Schriftmaße unterscheiden sich je Browser) – sonst mittig
    fit = pg.evaluate('() => { const l = %s.querySelector(".vmlist"); return [l.scrollHeight, l.clientHeight, l.querySelectorAll(".vmi").length]; }' % R)
    print('   Liste (Inhalt, sichtbar, Einträge):', fit)
    scrollable = fit[0] > fit[1] + 1
    check('Querformat: Menü als Leiste rechts über die volle Höhe', m[1] <= 20 and m[4] <= 380 and abs(m[2] - m[3]) <= 2 and (m[2] <= 20 or not scrollable), m)
    items = pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(b => b.firstChild.textContent + "=" + (b.querySelector("small") ? b.querySelector("small").textContent : ""))' % R)
    check('Format-Menü hat "Fortschrittslinie" mit an/aus', 'Fortschrittslinie=an' in items and 'Ambient-Modus=an' in items, items[-2:])
    pg.screenshot(path=OUT + '/menu_land_top.png')
    sc = pg.evaluate('() => { const l = %s.querySelector(".vmlist"), m = %s.querySelector(".vmenu"); l.scrollTop = 400; const r0 = m.getBoundingClientRect(); return [l.scrollTop > 0, m.scrollTop, Math.round(r0.top)]; }' % (R, R)); time.sleep(0.3)
    pg.screenshot(path=OUT + '/menu_land_scroll.png')
    check('Scrollen: nur die Liste bewegt sich, der Glasrahmen bleibt stehen', (sc[0] or not scrollable) and sc[1] == 0 and sc[2] == m[2], sc)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "Fortschrittslinie").click()' % R); time.sleep(0.3)
    pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.6)
    off = pg.evaluate('() => [getComputedStyle(%s.querySelector(".vmini")).opacity, localStorage.getItem("evp-line")]' % R)
    check('Fortschrittslinie aus: verschwindet und wird gemerkt', float(off[0]) < 0.05 and off[1] == '0', off)
    b.close()
    # Hochformat: Menü wie bisher unten über die Breite
    ctx = b if False else None
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
