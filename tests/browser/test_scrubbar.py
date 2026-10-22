import pwshim  # BROWSER=firefox → Tests in Firefox
# Spulen auf der Leiste (19.4/19.5): antippen = hinspringen · ziehen = von der aktuellen Stelle aus (kein Sprung zum
# Finger), Leiste wird dick, Abstand zur alten Stelle steht da („+18 s“) · nach oben weg = abbrechen (Hinweis,
# loslassen = zurück) · zurück nach unten = weiter · langsam ziehen = fein · zurück an die alte Stelle rastet ein.
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
    def state(): return pg.evaluate('() => { const w = %s.querySelector(".vp"); return [w.classList.contains("thick"), w.classList.contains("scrubx"), w.classList.contains("scrubfine"), %s.querySelector(".vprev b").textContent, w.classList.contains("scrubsnap")]; }' % (R, R))
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R)
    # 1) antippen = hinspringen
    setT(10); x, y, W = geo()
    pg.mouse.click(x + 7 + 0.7 * W, y); time.sleep(0.6)
    check('kurz antippen: springt an diese Stelle (70 %)', abs(t() - 0.7 * D) < 1.5, [t(), round(0.7 * D, 1)])
    # 2) ziehen von woanders: von der aktuellen Stelle aus, Leiste dick
    setT(30); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.2 * W, y); pg.mouse.down(); pg.mouse.move(x + 7 + 0.3 * W, y, steps=3); time.sleep(0.3)
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
    # 5) langsam ziehen = fein (wie ein Mauszeiger): gleiche Strecke, viel kleinerer Sprung
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W; n = int(0.3 * W / 2)
    pg.mouse.move(fx, y); pg.mouse.down()
    for i in range(n): pg.mouse.move(fx + 2 * (i + 1), y); time.sleep(0.04)   # ~50 px/s
    s5 = state(); pg.mouse.up(); time.sleep(0.6)
    fine = min(D / W, max(D / W / 8, 0.08)) * 2 * n
    check('langsam ziehen: fein (deutlich weniger als grob)', s5[2] and abs(t() - (20 + fine)) < 2 and t() - 20 < 0.6 * 0.3 * D, [s5, t(), round(20 + fine, 1), 'grob wäre', round(20 + 0.3 * D, 1)])
    check('… Abstand zur alten Stelle steht da', '+' in s5[3], s5)
    # 6) weg und zurück an die alte Stelle: rastet ein („± 0“), loslassen = nichts ändern
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=6); time.sleep(0.15)
    s6a = state(); pg.mouse.move(fx + 0.005 * W, y, steps=6); time.sleep(0.2)
    s6 = state(); pg.mouse.up(); time.sleep(0.6)
    check('beim Ziehen: Abstand „+18 s“', ('+%d s' % round(0.3 * D)) in s6a[3] or ('+%d s' % (round(0.3 * D) - 1)) in s6a[3], s6a)
    check('zurück an der alten Stelle: rastet ein (± 0)', s6[4] and '± 0' in s6[3], s6)
    check('… loslassen dort: bleibt bei 20 s', abs(t() - 20) < 0.5, t())
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
