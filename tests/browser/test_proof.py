import pwshim  # BROWSER=firefox → Tests in Firefox
# Welcher Stream läuft WIRKLICH?
#  - Seite lädt das nächste Video vor (anderer Stream, andere Länge), spielt aber dieses: Nachweis über die geladenen Stücke
#  - Datei-Videos ohne Stream-Liste: Gegenprobe der Länge im Player (falsche Länge → nächste Quelle)
import time, os
src = open('test_v8.py').read().split("results = []")[0]
if os.environ.get('EVP_SCRIPT'): src = src.replace("open('../../userscript/video-assistent.user.js'", "open(os.environ['EVP_SCRIPT']")
exec(src)
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
B = 'http://127.0.0.1:8775/'
def run(ctx, mode):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    reqs = []; pg.on('request', lambda r: reqs.append(r.url))
    pg.goto(B + 'proof.html#' + mode)
    pg.wait_for_function('document.getElementById("v").currentTime > 0.8', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.0)
    n0 = len(reqs)
    t0 = time.time()
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=30000)
    dt = time.time() - t0
    time.sleep(0.5)
    st = pg.evaluate('() => { const v = %s.querySelector(".vv"), o = document.getElementById("v"); return [Math.round(v.duration), Math.round(o.duration), %s.querySelector(".vp") ? 1 : 0]; }' % (R, R))
    after = [u.replace(B, '/') for u in reqs[n0:] if '.m3u8' in u or '.webm' in u]
    return pg, st, after, dt, errs
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg, st, after, dt, errs = run(ctx, 'preload')
    check('vorgeladenes nächstes Video: Player spielt das laufende (gleiche Länge %s s)' % st[1], st[0] == st[1] == 60, [st, after])
    check('… und lädt das andere gar nicht erst', not any('/hls3/' in u for u in after), after)
    pg.close()
    pg, st, after, dt, errs2 = run(ctx, 'file')
    check('Datei-Video, danach andere Datei geladen: Player nimmt die mit passender Länge (%s s)' % st[1], st[0] == st[1] == 30, [st, after])
    check('… schnell (unter 4 s bis es läuft)', dt < 4, round(dt, 2))
    check('keine Fehler', not errs and not errs2, errs + errs2)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
