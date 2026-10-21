import pwshim  # BROWSER=firefox → Tests in Firefox
# Messung (kein Bestehen/Durchfallen): Wie lange nach dem Anhalten des Fingers zeigt die Vorschau die richtige Stelle
# (±1,5 s)? Langsames Netz (jede Video-Anfrage 250 ms). Fälle:
#   neu    – Stelle, die noch nie geladen wurde (Raster läuft vielleicht gerade)
#   gesehen – Stelle, die man schon angeschaut hat
#   zurück – nochmal an eine schon gezeigte Stelle
# Aufruf: python3 test_prevlag.py [alt.js]  – mit einer zweiten Skriptdatei zum Vergleich
import time, sys
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'
WAIT = '''(want) => new Promise((ok) => { const t0 = performance.now(); const p = %s.querySelector(".vprev");
  const f = () => { const t = p.dataset.t != null ? parseFloat(p.dataset.t) : NaN;
    if (p.classList.contains("img") && Math.abs(t - want) < 1.5) ok(Math.round(performance.now() - t0));
    else if (performance.now() - t0 > 6000) ok(-1); else requestAnimationFrame(f); }; f(); })''' % R
def measure(path, page):
    S = open(path, encoding='utf-8').read()
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        ctx.route('**/*', lambda r: (time.sleep(float(__import__('os').environ.get('LAG', '0.25'))) if ('.ts' in r.request.url or '.webm' in r.request.url) else None, r.continue_())[1])
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/' + page)
        pg.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=30000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && isFinite(v.duration); }' % R, timeout=30000)
        pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 2; }' % R)
        time.sleep(5)   # 2 s bis ~7 s angeschaut
        dur = pg.evaluate('() => %s.querySelector(".vv").duration' % R)
        br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        y = br[1] + br[3] / 2
        X = lambda f: br[0] + 7 + f * (br[2] - 14)
        res = {}
        for name, a, z in [('neu', 0.30, 0.72), ('gesehen', 0.72, 4.5 / dur), ('zurück', 0.2, 0.72)]:
            pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.2)
            pg.mouse.move(X(a), y); pg.mouse.down()
            for k in range(1, 7): pg.mouse.move(X(a + (z - a) * k / 6), y); time.sleep(0.04)
            want = pg.evaluate('() => { const v = %s.querySelector(".vv"), b = %s.querySelector(".vbar"); return b.value / 1000 * v.duration; }' % (R, R))
            res[name] = pg.evaluate(WAIT, want)
            pg.mouse.up(); time.sleep(1.2)
            pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 7; }' % R); time.sleep(0.8)
        b.close()
    return res
paths = [('aktuell', '../../userscript/video-assistent.user.js')] + ([('vergleich', sys.argv[1])] if len(sys.argv) > 1 else [])
for page in ['direct.html', 'hls2.html']:
    for label, path in paths:
        r = measure(path, page)
        print('%-7s %-9s richtige Stelle nach dem Anhalten: neu %5s ms | gesehen %5s ms | zurück %5s ms' % (page.split('.')[0], label, r['neu'], r['gesehen'], r['zurück']))
srv.shutdown()
