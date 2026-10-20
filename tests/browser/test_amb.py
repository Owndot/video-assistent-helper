import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'
ok = []
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 740}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=15000)
    time.sleep(0.6)
    def center():
        return pg.evaluate('''() => { const v = %s.querySelector(".vv"), w = %s.querySelector(".vp"); const vr = v.getBoundingClientRect(), wr = w.getBoundingClientRect();
            const sc = Math.min(vr.width / v.videoWidth, vr.height / v.videoHeight); const ch = v.videoHeight * sc;
            return [Math.round(vr.top + vr.height / 2 - (wr.top + wr.height / 2)), Math.round(vr.left + vr.width / 2 - (wr.left + wr.width / 2)), Math.round(wr.height)]; }''' % (R, R))
    c1 = center()
    pg.set_viewport_size({'width': 400, 'height': 800}); time.sleep(0.5)   # Adressleiste verschwindet: 60 px mehr Höhe
    c2 = center()
    print('Abstand zur Mitte (senkrecht, waagrecht, Höhe):', c1, '-> nach Größenänderung:', c2)
    ok.append(abs(c1[0]) <= 1 and abs(c1[1]) <= 1 and abs(c2[0]) <= 1 and abs(c2[1]) <= 1 and c2[2] == 800)
    amb = pg.evaluate('() => { const a = %s.querySelector(".vamb"); return [%s.querySelector(".vp").classList.contains("amb"), getComputedStyle(a).opacity, a.width + "x" + a.height]; }' % (R, R))
    time.sleep(1.0)
    print('Ambient:', amb); ok.append(amb[0])
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.pause(); v.currentTime = 21; }' % R); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.4)
    pg.screenshot(path=OUT + '/amb_port.png')
    pg.set_viewport_size({'width': 860, 'height': 400}); time.sleep(0.8)
    c3 = center(); print('Querformat Abstand zur Mitte:', c3); ok.append(abs(c3[0]) <= 1 and abs(c3[1]) <= 1)
    pg.screenshot(path=OUT + '/amb_land.png')
    # Ausschalten über das Format-Menü (wird gemerkt)
    pg.set_viewport_size({'width': 400, 'height': 800}); time.sleep(0.4)
    pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R)
    pg.evaluate('() => %s.querySelector("button[aria-label=Bildformat]").click()' % R); time.sleep(0.3)
    items = pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(b => b.firstChild.textContent)' % R)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "Ambient-Modus").click()' % R); time.sleep(0.3)
    off = pg.evaluate('() => [!%s.querySelector(".vp").classList.contains("amb"), localStorage.getItem("evp-ambient")]' % R)
    print('Menü:', items, '| aus + gemerkt:', off); ok.append(off == [True, '0'])
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
