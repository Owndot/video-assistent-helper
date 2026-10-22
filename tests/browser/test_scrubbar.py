import pwshim  # BROWSER=firefox → Tests in Firefox
# Spulen auf der Leiste (19.4): antippen = hinspringen · ziehen = von der aktuellen Stelle aus (kein Sprung zum Finger),
# Leiste wird dick · nach oben weg = abbrechen (Hinweis, loslassen = zurück) · zurück nach unten = weiter ·
# unter die Leiste = fein (fünfmal langsamer).
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'subs.html'); time.sleep(0.4)
    def t(): return pg.evaluate('() => Math.round(%s.querySelector(".vv").currentTime * 10) / 10' % R)
    def setT(x): pg.evaluate('(x) => { const v = %s.querySelector(".vv"); v.pause(); v.currentTime = x; }' % R, x); time.sleep(0.4)
    def geo():
        ctl(pg); r = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        return r[0], r[1] + r[3] / 2, r[2] - 14
    def state(): return pg.evaluate('() => { const w = %s.querySelector(".vp"); return [w.classList.contains("thick"), w.classList.contains("scrubx"), w.classList.contains("scrubfine"), %s.querySelector(".vprev b").textContent]; }' % (R, R))
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R)
    # 1) antippen = hinspringen
    setT(10); x, y, W = geo()
    pg.mouse.click(x + 7 + 0.7 * W, y); time.sleep(0.6)
    check('kurz antippen: springt an diese Stelle (70 %)', abs(t() - 0.7 * D) < 1.5, [t(), round(0.7 * D, 1)])
    # 2) ziehen von woanders: von der aktuellen Stelle aus, Leiste dick
    setT(30); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.2 * W, y); pg.mouse.down(); pg.mouse.move(x + 7 + 0.3 * W, y, steps=6); time.sleep(0.3)
    s2 = state(); pg.mouse.up(); time.sleep(0.6)
    check('ziehen (Finger bei 20 %, Video bei 30 s): +10 % von der aktuellen Stelle, kein Sprung zum Finger', abs(t() - (30 + 0.1 * D)) < 1.5, [t(), 30 + 0.1 * D])
    check('… Leiste dabei dick', s2[0], s2)
    # 3) abbrechen: zur Seite, dann weit nach oben → Hinweis; loslassen = zurück
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=6); pg.mouse.move(fx + 0.3 * W, y - 120, steps=6); time.sleep(0.3)
    s3 = state(); pg.mouse.up(); time.sleep(0.6)
    check('nach oben weg: Abbrechen-Hinweis', s3[1] and 'abbrechen' in s3[3], s3)
    check('… loslassen dort: zurück an die alte Stelle', abs(t() - 20) < 1, t())
    # 4) nach oben und wieder zurück: weiter spulen
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.2 * W, y, steps=5); pg.mouse.move(fx + 0.2 * W, y - 120, steps=5)
    pg.mouse.move(fx + 0.2 * W, y, steps=5); time.sleep(0.2); pg.mouse.up(); time.sleep(0.6)
    check('nach oben und zurück: spult doch (+20 %)', abs(t() - (20 + 0.2 * D)) < 1.5, [t(), 20 + 0.2 * D])
    # 5) fein: unter die Leiste, dann zur Seite → fünfmal langsamer
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx, y + 80, steps=5); pg.mouse.move(fx + 0.5 * W, y + 80, steps=10); time.sleep(0.3)
    s5 = state(); pg.mouse.up(); time.sleep(0.6)
    check('unter die Leiste: fein (halbe Breite = 10 % statt 50 %)', s5[2] and 'fein' in s5[3] and abs(t() - (20 + 0.1 * D)) < 1.5, [s5, t(), 20 + 0.1 * D])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
