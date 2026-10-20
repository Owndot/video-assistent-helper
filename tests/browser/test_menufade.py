import pwshim  # BROWSER=firefox → Tests in Firefox
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; ok = []
def check(n, c, i=''): ok.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for tag, vw in [('hoch', {'width': 400, 'height': 800}), ('quer', {'width': 860, 'height': 400})]:
        ctx = b.new_context(viewport=vw, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/hls2.html')
        pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1; }' % R, timeout=20000); time.sleep(1)
        for menu in ['Qualität', 'Bildformat']:
            pg.evaluate('(l) => { %s.querySelector(".vp").classList.add("ctl"); %s.querySelector("button[aria-label=" + l + "]").click(); }' % (R, R), menu); time.sleep(0.4)
            st = lambda: pg.evaluate('() => { const l = %s.querySelector(".vmlist"); return [l.classList.contains("mt"), l.classList.contains("mb"), l.scrollHeight > l.clientHeight + 1]; }' % R)
            a = st()
            check('%s %s ganz oben: oben KEIN Auslaufen' % (tag, menu), not a[0] and (a[1] == a[2]), a)
            if a[2]:
                pg.evaluate('() => { const l = %s.querySelector(".vmlist"); l.scrollTop = l.scrollHeight; }' % R); time.sleep(0.2)
                a2 = st(); check('%s %s ganz unten: oben Auslaufen, unten nicht' % (tag, menu), a2[0] and not a2[1], a2)
            pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R)); time.sleep(0.3)
        ctx.close()
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
