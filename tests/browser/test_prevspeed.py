import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'
def measure(path, page):
    S = open(path, encoding='utf-8').read()
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        # Netz bremsen wie Mobilfunk: jede Anfrage 150 ms Verzögerung
        ctx.route('**/*', lambda r: (time.sleep(0.15) if ('.ts' in r.request.url or '.webm' in r.request.url) else None, r.continue_())[1])
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/' + page)
        pg.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=30000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && isFinite(v.duration); }' % R, timeout=30000)
        time.sleep(4)   # kurz schauen (Streams holen das Raster schon jetzt)
        pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.2)
        br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        y = br[1] + br[3] / 2
        res = []
        for rnd in range(2):
            pg.evaluate('() => { const p = %s.querySelector(".vprev"); p.classList.remove("img"); window.__t0 = performance.now(); window.__ti = 0; new MutationObserver(() => { if (!window.__ti && p.classList.contains("img")) window.__ti = performance.now(); }).observe(p, { attributes: true }); }' % R)
            pg.mouse.move(br[0] + br[2] * 0.15, y); pg.mouse.down()
            for k in range(1, 9):
                pg.mouse.move(br[0] + br[2] * (0.15 + 0.08 * k), y); time.sleep(0.05)
            try: pg.wait_for_function('() => window.__ti > 0', timeout=8000)
            except Exception: pass
            ms = pg.evaluate('() => window.__ti ? Math.round(window.__ti - window.__t0) : -1')
            pg.mouse.up(); time.sleep(1.5)
            res.append(ms)
        cached = pg.evaluate('() => { const p = %s.querySelector(".vprev"); return p.dataset.t || "-"; }' % R)
        b.close()
    return res
for page in ['direct.html', 'hls2.html']:
    for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
        r = measure(path, page)
        print('%-11s %-9s erstes Bild nach: 1. Ziehen %5s ms | 2. Ziehen %5s ms' % (page.split('.')[0], label, r[0], r[1]))
srv.shutdown()
