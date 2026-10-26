import pwshim  # BROWSER=firefox → Tests in Firefox
# Schlüssel der eingebetteten Fenster (Nachrichten nach oben an die Hauptseite):
#  - gilt je Hauptseite: Fenster unter verschiedenen Hauptseiten schicken verschiedene Schlüssel (ein auf Seite A
#    mitgelesener Schlüssel hilft auf Seite B nicht), auch bei mehrfach verschachtelten Fenstern und wenn das Fenster
#    selbst eine neue Adresse lädt
#  - Browser ohne location.ancestorOrigins (Firefox vor 2026/ESR): GAR KEIN Schlüssel – nie der gemeinsame. Die
#    Hauptseite nimmt dann nichts von unten an; der Player bleibt im Kasten des Fensters und funktioniert dort.
exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
# Hauptseite: alle Nachrichten von unten mitschneiden (so sähe sie eine böse Hauptseite)
SPY = """if (window.top === window) { window.__keys = []; window.addEventListener('message', (e) => {
  const d = e.data; if (d && d.__evpFrame1) window.__keys.push({k:d.k || '', t:d.t, origin:e.origin}); }, true); }"""
# (location.ancestorOrigins ist in Chrome fest am Objekt – nicht löschbar. Darum im Skript selbst „nicht vorhanden“.)
assert SCRIPT.count("'ancestorOrigins' in location") == 1
NOAO_SCRIPT = SCRIPT.replace("'ancestorOrigins' in location", "false")
def keys(pg):
    # Keyless bootstrap hello messages are not authenticated authorizations.
    return pg.evaluate('() => Array.from(new Set(window.__keys.map(m => m.k).filter(Boolean)))')
def setup(b, script=None):
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(SPY); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(script or SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    return ctx, pg, errs
def inner(pg): return [f for f in pg.frames if 'emb_inner' in f.url][0]
def open_in(pg, loc, fi):
    fi.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    fi.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    loc.locator('button[aria-label="Im Videoplayer öffnen"]').click()
    fi.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.5; }' % R, timeout=15000)
    time.sleep(1.2)
def fsid(pg): return pg.evaluate('() => document.fullscreenElement ? document.fullscreenElement.id : null')
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required', '--host-resolver-rules=MAP *.test 127.0.0.1'])
    # 1) Hauptseite A (127.0.0.1) – Fenster von localhost
    ctx, pg, errs = setup(b)
    pg.goto('http://127.0.0.1:8775/emb_top.html'); time.sleep(1.5)
    before_height = pg.evaluate('document.getElementById("f").getBoundingClientRect().height')
    inner(pg).evaluate('() => parent.postMessage({__evpFrame1:1,t:"take",fs:true,k:"wrong-key"},"*")'); time.sleep(0.2)
    check('falscher Frame-Schlüssel autorisiert kein Vollbild', fsid(pg) != 'f' and abs(pg.evaluate('document.getElementById("f").getBoundingClientRect().height')-before_height)<1, fsid(pg))
    pg.evaluate('() => { window.__keys = []; }')
    open_in(pg, pg.frame_locator('#f'), inner(pg))
    ka = keys(pg)
    check('Seite A: Fenster schickt einen Schlüssel, Player im Vollbild', len(ka) == 1 and len(ka[0]) == 40 and fsid(pg) == 'f', [ka, fsid(pg)])
    raw = pg.evaluate('() => window.__keys')
    check('gemeinsamer Basisschlüssel nie gesendet; schlüssellose Nachrichten nur hello', all(m['k'] != '5e1f0c2a9b7d4e3f8a6c1b2d3e4f5a6b7c' and (m['k'] or m['t'] == 'hello') for m in raw), raw)
    # Fenster lädt selbst eine neue Adresse: Schlüssel bleibt an die Hauptseite gebunden (derselbe)
    pg.evaluate('() => document.exitFullscreen()'); time.sleep(1.2)
    pg.evaluate('() => { window.__keys = []; document.getElementById("f").src = "http://localhost:8775/emb_inner.html?nochmal"; }'); time.sleep(2.0)
    ka2 = keys(pg)
    check('Fenster neu geladen: derselbe Schlüssel (gehört zur Hauptseite, nicht zum Fenster)', ka2 == ka, [ka2, ka])
    # 2) Hauptseite B (top.test) mit doppelt verschachteltem Fenster (top.test → mid.test → localhost)
    pg.goto('http://top.test:8775/nest_top.html'); time.sleep(2.0)
    before_height = pg.evaluate('document.getElementById("f").getBoundingClientRect().height')
    inner(pg).evaluate('(key) => parent.postMessage({__evpFrame1:1,t:"take",fs:true,k:key},"*")', ka[0]); time.sleep(0.2)
    check('Schlüssel von Seite A autorisiert Seite B nicht', fsid(pg) != 'f' and abs(pg.evaluate('document.getElementById("f").getBoundingClientRect().height')-before_height)<1, fsid(pg))
    pg.evaluate('() => { window.__keys = []; }')
    open_in(pg, pg.frame_locator('#f').frame_locator('#g'), inner(pg))
    kb = keys(pg)
    check('Seite B (verschachtelt): Player im Vollbild', fsid(pg) == 'f', fsid(pg))
    check('… und ein ANDERER Schlüssel als auf Seite A', len(kb) == 1 and len(kb[0]) == 40 and kb[0] != ka[0], [kb, ka])
    check('keine Fehler (mit ancestorOrigins)', not errs, errs)
    ctx.close()
    # 3) Browser ohne ancestorOrigins: kein Schlüssel, Hauptseite lässt sich nichts sagen, Player im Kasten
    ctx, pg, errs = setup(b, NOAO_SCRIPT)
    pg.goto('http://127.0.0.1:8775/emb_top.html'); time.sleep(1.5)
    fi = inner(pg)
    open_in(pg, pg.frame_locator('#f'), fi)
    k = keys(pg)
    check('ohne ancestorOrigins: keine Nachricht trägt einen Schlüssel (auch nicht den gemeinsamen)', not k, k)
    h = pg.evaluate('() => [document.getElementById("f").getBoundingClientRect().height, document.fullscreenElement && document.fullscreenElement.id]')
    check('… die Hauptseite macht das Fenster nicht groß', abs(h[0] - 260) < 2 and h[1] != 'f', h)
    pl = fi.evaluate('() => { const v = %s.querySelector(".vv"); return [!!v, v && !v.paused, v && v.currentTime > 0.5]; }' % R)
    check('… der Player läuft im Fenster', pl == [True, True, True], pl)
    check('keine Fehler (ohne ancestorOrigins)', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(1 for r in results if r[1]), len(results)))
