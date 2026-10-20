import pwshim  # BROWSER=firefox → Tests in Firefox
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; ok = []
def check(n, c, i=''): ok.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for tag, vw, page in [('hoch', {'width': 400, 'height': 800}, 'hls2.html'), ('quer', {'width': 860, 'height': 400}, 'hls2.html')]:
        ctx = b.new_context(viewport=vw, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/' + page)
        pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=20000)
        time.sleep(1.2)
        for menu in ['Bildformat', 'Qualität']:
            pg.evaluate('(l) => { %s.querySelector(".vp").classList.add("ctl"); %s.querySelector("button[aria-label=" + l + "]").click(); }' % (R, R), menu); time.sleep(0.5)
            m = pg.evaluate('''() => { const r = %s; const mb = r.querySelector(".vmenu").getBoundingClientRect();
                const rows = Array.from(r.querySelectorAll(".vmenu .vmi")).map(b => { const s = b.querySelector("small"); return s ? Math.round(b.getBoundingClientRect().right - s.getBoundingClientRect().right) : null; }).filter(x => x !== null);
                const lab = Array.from(r.querySelectorAll(".vmenu .vmi")).map(b => { const rg = document.createRange(); rg.selectNodeContents(b.firstChild); return Math.round(rg.getBoundingClientRect().left - b.getBoundingClientRect().left); });
                return { oben: Math.round(mb.top), unten: Math.round(innerHeight - mb.bottom), links: Math.round(mb.left), rechts: Math.round(innerWidth - mb.right), rechtsText: Array.from(new Set(rows)), linksText: Array.from(new Set(lab)) }; }''' % R)
            pg.screenshot(path=OUT + '/msym_%s_%s.png' % (tag, 'q' if menu == 'Qualität' else 'f'))
            sym_text = len(m['rechtsText']) == 1 and len(m['linksText']) == 1 and abs(m['rechtsText'][0] - m['linksText'][0]) <= 1
            if tag == 'quer':
                check('%s %s: oben/unten gleich, Texte links/rechts gleicher Abstand' % (tag, menu), abs(m['oben'] - m['unten']) <= 1 and sym_text, m)
            else:
                check('%s %s: links/rechts gleich, Texte bündig' % (tag, menu), abs(m['links'] - m['rechts']) <= 1 and sym_text, m)
            pg.evaluate('() => %s.querySelector(".vgest").dispatchEvent(new PointerEvent("pointerup"))' % R)
            pg.mouse.click(30, 30) if False else None
            pg.evaluate('() => { const m = %s.querySelector(".vmenu"); m.classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R)); time.sleep(0.3)
        ctx.close()
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
