import pwshim  # BROWSER=firefox → Tests in Firefox
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'
# Kamera-Aussparung links im Querformat nachstellen: env(safe-area-inset-left) gibt es im Test nicht – wir ersetzen env() im Skript gezielt
for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
    S = open(path).read().replace('env(safe-area-inset-left,0px)', '48px')
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 860, 'height': 400}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/direct.html')
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => %s.querySelector(".vv")' % R, timeout=15000); time.sleep(0.6)
        pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.5)
        r = pg.evaluate('() => { const m = %s.querySelector(".vmini").getBoundingClientRect(); return [Math.round(m.left), Math.round(innerWidth - m.right)]; }' % R)
        print('%-9s Abstand links %s px | rechts %s px | %s' % (label, r[0], r[1], 'symmetrisch' if r[0] == r[1] else 'SCHIEF'))
        b.close()
srv.shutdown()
