import pwshim  # BROWSER=firefox → Tests in Firefox
# Wechsel zu einem anderen Video ohne Neuladen (ARD-Mediathek): schnell hintereinander gewechselt und gleich
# geöffnet → der Player spielt das NEUE Video (nicht den Stream des vorigen), und geteilt wird die neue Seitenadresse
# (nicht ein Link neben dem Player auf das vorige Video).
import time, os
src = open('test_v8.py').read().split("results = []")[0]
if os.environ.get('EVP_SCRIPT'): src = src.replace("open('../../userscript/video-assistent.user.js'", "open(os.environ['EVP_SCRIPT']")
exec(src)
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
B = 'http://127.0.0.1:8775/'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    reqs = []; pg.on('request', lambda r: reqs.append(r.url))
    pg.goto(B + 'spa.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(2.0)
    pg.evaluate('() => switchTo()')   # schnell zum nächsten Video
    pg.wait_for_function('() => { const v = document.getElementById("v"); return v.currentTime > 0.3 && v.videoWidth > 0; }', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.6)
    n0 = len(reqs)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000)
    time.sleep(0.5)
    after = [u.replace(B, '/') for u in reqs[n0:] if '.m3u8' in u]
    check('schneller Wechsel ohne Neuladen: Player lädt den Stream des NEUEN Videos', any('/hls2/' in u for u in after) and not any('/hls/' in u for u in after), after)
    pg.evaluate('() => { window.__shared = []; Object.defineProperty(navigator, "share", { configurable: true, value: (d) => { window.__shared.push(d); return Promise.resolve(); } }); }')
    pg.evaluate('() => { const r = %s; r.querySelector(".vp").classList.add("ctl"); r.querySelector("button[aria-label=Mehr]").click(); }' % R); time.sleep(0.4)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu.on .vmi")).find(b => b.firstChild.textContent === "Link teilen").click()' % R); time.sleep(0.6)
    sh = pg.evaluate('() => window.__shared')
    check('… geteilt wird die neue Seitenadresse, nicht der Link aufs vorige Video', len(sh) == 1 and sh[0]['url'].endswith('/video/jetzt-folge/b-22222'), sh)
    # zurück zum ersten Video (A → B → A): dessen Stream wurde früher schon geladen, gilt aber jetzt wieder
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    pg.evaluate('() => switchBack()')
    pg.wait_for_function('() => { const v = document.getElementById("v"); return v.currentTime > 0.3 && v.videoWidth > 0; }', timeout=20000)
    pg.evaluate('() => { const v = document.getElementById("v"); v.muted = false; v.play(); }'); time.sleep(0.8)
    n1 = len(reqs)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000)
    time.sleep(0.5)
    after2 = [u.replace(B, '/') for u in reqs[n1:] if '.m3u8' in u]
    check('zurück zum ersten Video: Player lädt wieder dessen Stream', any('/hls/' in u for u in after2) and not any('/hls2/' in u for u in after2), after2)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
