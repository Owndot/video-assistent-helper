import pwshim  # BROWSER=firefox → Tests in Firefox
# Spulen auf der Leiste (19.4–19.7): antippen = hinspringen · ziehen = Stelle unter dem Finger, Leiste wird dick,
# Abstand zur alten Stelle steht da („+18 s“) · nach oben weg = abbrechen (Hinweis, loslassen = zurück) · zurück nach
# unten = weiter · zurück an die alte Stelle rastet ein · Enden der Leiste = Anfang/Ende ·
# Lupe (19.7): beim Ziehen ½ s stillhalten (oder Leiste gedrückt halten) → Leiste zeigt einen Ausschnitt (fein),
# Grenzen links/rechts; am Ende der Leiste wandert der Ausschnitt mit; nur bei Videos ab 1,5 min · Zeit im Glas des Designs.
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
    def state(): return pg.evaluate('() => { const w = %s.querySelector(".vp"); return [w.classList.contains("thick"), w.classList.contains("scrubx"), w.classList.contains("zoom"), %s.querySelector(".vprev b").textContent, w.classList.contains("scrubsnap")]; }' % (R, R))
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R)
    def at(x, X, W): return min(max((X - x - 7) / W, 0), 1) * D   # Stelle unter dem Finger
    # 1) antippen = hinspringen
    setT(10); x, y, W = geo()
    pg.mouse.click(x + 7 + 0.7 * W, y); time.sleep(0.6)
    check('kurz antippen: springt an diese Stelle (70 %)', abs(t() - 0.7 * D) < 1.5, [t(), round(0.7 * D, 1)])
    # 2) ziehen von woanders: von der aktuellen Stelle aus, Leiste dick
    setT(30); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.2 * W, y); pg.mouse.down(); pg.mouse.move(x + 7 + 0.3 * W, y, steps=3); time.sleep(0.3)
    s2 = state(); pg.mouse.up(); time.sleep(0.6)
    check('ziehen (Finger von 20 % nach 30 %, Video bei 30 s): Stelle unter dem Finger (30 %)', abs(t() - 0.3 * D) < 1, [t(), 0.3 * D])
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
    check('nach oben und zurück: spult doch (unter dem Finger)', abs(t() - at(x, fx + 0.2 * W, W)) < 1, [t(), round(at(x, fx + 0.2 * W, W), 1)])
    # 5) kurzes Video (1 min): stillhalten beim Ziehen → keine Lupe (die Leiste reicht)
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.2 * W, y, steps=3); time.sleep(0.9)
    s5 = state(); pg.mouse.up(); time.sleep(0.6)
    check('kurzes Video: stillhalten → keine Lupe', not s5[2] and abs(t() - at(x, fx + 0.2 * W, W)) < 1, [s5, t()])
    check('… Abstand zur alten Stelle steht da', '+' in s5[3], s5)
    # 6) weg und zurück an die alte Stelle: rastet ein („± 0“), loslassen = nichts ändern
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    xr = fx + 0.3 * W; r1 = at(x, xr, W); back = fx + 0.003 * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(xr, y, steps=6); time.sleep(0.15)
    s6a = state(); pg.mouse.move(back, y, steps=6); time.sleep(0.2)
    s6 = state(); pg.mouse.up(); time.sleep(0.6)
    check('beim Ziehen: Abstand zur alten Stelle („+… s“)', any(('+%d s' % (round(r1 - 20) + k)) in s6a[3] for k in (-1, 0, 1)), [s6a, round(r1 - 20)])
    check('zurück an der alten Stelle: rastet ein (± 0)', s6[4] and '± 0' in s6[3], s6)
    check('… loslassen dort: bleibt bei 20 s', abs(t() - 20) < 0.5, t())
    # 7) bis an den rechten Rand = Ende, an den linken = Anfang (egal, wo der Finger anfängt)
    setT(20); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.6 * W, y); pg.mouse.down(); pg.mouse.move(x + W + 30, y, steps=4); time.sleep(0.2)
    s7 = state(); pg.mouse.up(); time.sleep(0.6)
    check('bis zum rechten Ende der Leiste: Ende', t() > D - 1.5 and s7[3].startswith('1:00'), [t(), s7])
    setT(40); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.3 * W, y); pg.mouse.down(); pg.mouse.move(x - 10, y, steps=4); time.sleep(0.2)
    pg.mouse.up(); time.sleep(0.6)
    check('bis zum linken Ende: Anfang', t() < 0.5, t())
    # ---------- Lupe (langes Video, 4 min → Ausschnitt 30 s) ----------
    open_player(pg, 'long.html'); time.sleep(0.4)
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R); Z = min(max(D / 8, 20), 60)
    def times(): return pg.evaluate('() => { const r = %s; return [...r.querySelectorAll(".vtime")].map(e => [e.textContent, e.classList.contains("vtz"), e.classList.contains("g")]); }' % R)
    def mmss(v): v = int(v); return '%d:%02d' % (v // 60, v % 60)
    # Z1) ziehen, dann stillhalten → Lupe: Leiste violett-Schild, Grenzen des Ausschnitts links/rechts
    setT(100); x, y, W = geo(); fx = x + 7 + (100 / D) * W; x1 = fx + 0.1 * W; t1 = at(x, x1, W)
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(x1, y, steps=3); time.sleep(0.8)
    z1 = state(); tz = times(); f = (x1 - x - 7) / W; ws = min(max(t1 - f * Z, 0), D - Z)
    check('Lupe: ziehen, ½ s stillhalten → Lupe an', z1[2], z1)
    check('… links/rechts die Grenzen des Ausschnitts im Glas', tz[0][1] and tz[1][1] and tz[0][2] and abs(int(tz[0][0].split(':')[0]) * 60 + int(tz[0][0].split(':')[1]) - ws) <= 1 and abs(int(tz[1][0].split(':')[0]) * 60 + int(tz[1][0].split(':')[1]) - (ws + Z)) <= 1, [tz, mmss(ws), mmss(ws + Z)])
    # Z2) mit Lupe weiter: 20 % der Leiste = 20 % des Ausschnitts (6 s statt 48 s)
    pg.mouse.move(x1 + 0.2 * W, y, steps=4); time.sleep(0.3)
    pg.mouse.up(); time.sleep(0.6)
    check('… mit Lupe fein: 20 % der Leiste = 20 % des Ausschnitts', abs(t() - (t1 + 0.2 * Z)) < 1.2, [t(), round(t1 + 0.2 * Z, 1), 'ohne Lupe', round(t1 + 0.2 * D, 1)])
    tz2 = times()
    check('… danach Zeiten wieder normal (Länge rechts, ohne Schild)', tz2[1][0] == mmss(D) and not tz2[0][1] and not tz2[1][1], tz2)
    # Z3) Leiste gedrückt halten (ohne Ziehen) → Lupe um die aktuelle Stelle; kleiner Zug = kleiner Sprung
    setT(150); x, y, W = geo(); fx = x + 7 + 0.3 * W
    pg.mouse.move(fx, y); pg.mouse.down(); time.sleep(0.8)
    z3 = state(); pg.mouse.move(fx + 0.1 * W, y, steps=3); time.sleep(0.3); pg.mouse.up(); time.sleep(0.6)
    check('Leiste gedrückt halten: Lupe um die aktuelle Stelle', z3[2] and abs(t() - (150 + 0.1 * Z)) < 1.2, [z3, t(), 150 + 0.1 * Z])
    # Z4) mit Lupe ans rechte Ende der Leiste → Ausschnitt wandert mit
    setT(60); x, y, W = geo(); fx = x + 7 + (60 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); time.sleep(0.8)
    pg.mouse.move(x + 7 + W - 2, y, steps=4); time.sleep(0.2); a4 = times(); time.sleep(1.2); b4 = times()
    pg.mouse.up(); time.sleep(0.6)
    wsec = lambda tt: int(tt[0][0].split(':')[0]) * 60 + int(tt[0][0].split(':')[1])
    check('Lupe am Ende der Leiste: Ausschnitt wandert mit', wsec(b4) > wsec(a4) + 5 and t() > wsec(a4) + Z, [a4, b4, t()])
    # Z5) Design: Zeit über der Vorschau im selben Glas wie das Vorschaubild – bei „Glas“ und bei „Schlicht“ (Milchglas)
    for flat in (False, True):
        pg.evaluate('(f) => %s.host.classList.toggle("flat", f)' % R, flat)
        setT(100); x, y, W = geo(); fx = x + 7 + (100 / D) * W
        pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.1 * W, y, steps=3); time.sleep(0.3)
        bg = pg.evaluate('() => [".vprev b", ".vpbox"].map(q => { const c = getComputedStyle(%s.querySelector(q)); return [c.backgroundColor, c.backdropFilter || c.webkitBackdropFilter]; })' % R)
        pg.mouse.up(); time.sleep(0.5)
        if flat: check('Schlicht (Milchglas): Zeit über der Vorschau im selben Milchglas wie das Vorschaubild', bg[0] == bg[1], bg)
        else: check('Glas: Zeit über der Vorschau im Glas (Lichtbrechung wie die Knöpfe)', bg[0][1].startswith('url(') and bg[1][1].startswith('url('), bg)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
