import pwshim  # BROWSER=firefox → Tests in Firefox
# Wischen übers Video (wie Samsung Internet): Video hält beim Wischen an, das große Bild springt mit, die Zahlen zeigen
# genau den Sprung (am Ende begrenzt), „/ Länge“ und eine kleine Leiste; loslassen = dort weiter (lief es, läuft es
# wieder); zurück zum Ausgangspunkt = nichts ändern; zweiter Finger = abbrechen. Tempo: ganze Breite = Länge (20–90 s).
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'subs.html'); time.sleep(0.4)
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R); W = pg.evaluate('() => %s.querySelector(".vp").clientWidth' % R)
    span = min(max(D, 20), 90)
    def st(): return pg.evaluate('() => { const r = %s, v = r.querySelector(".vv"), s = r.querySelector(".vseek"); return { t: v.currentTime, paused: v.paused, on: s.classList.contains("on"), big: s.querySelector("b").textContent, rest: s.querySelector("small").textContent, delta: s.querySelector("i").textContent, fill: s.querySelector(".vseekf").style.transform, ctl: r.querySelector(".vp").classList.contains("ctl") }; }' % R)
    def play_at(x): pg.evaluate('(x) => { const v = %s.querySelector(".vv"); v.currentTime = x; v.play(); }' % R, x); time.sleep(0.6)
    def mmss(v): v = int(v); return '%d:%02d' % (v // 60, v % 60)
    # 1) wischen: Video hält an, großes Bild springt mit, Zahlen stimmen
    play_at(10); t0 = st()['t']
    pg.mouse.move(100, 450); pg.mouse.down(); pg.mouse.move(100 + 0.25 * W, 450, steps=6); time.sleep(0.8)
    s1 = st(); want = t0 + 0.25 * span
    check('beim Wischen: Video hält an', s1['paused'], s1)
    check('… das große Bild springt an die Stelle (Video steht dort)', abs(s1['t'] - want) < 1.5, [round(s1['t'], 1), round(want, 1)])
    check('… Zahlen: Stelle, „/ Länge“, Sprung, kleine Leiste', s1['big'] in (mmss(want), mmss(want + 1), mmss(want - 1)) and s1['rest'] == '/ ' + mmss(D) and s1['delta'].startswith('+') and 'scaleX' in s1['fill'], s1)
    ly = pg.evaluate('''() => { const r = %s, c = r.querySelector(".vlayer"), w = r.querySelector(".vp").getBoundingClientRect(), v = r.querySelector(".vv");
        if (!c) return null; const b = c.getBoundingClientRect(); return [getComputedStyle(c).visibility, Math.round(b.width), Math.round(b.height), Math.round(w.width), Math.round(w.height), c.width && Math.abs(c.width / c.height - v.videoWidth / v.videoHeight) < 0.02]; }''' % R)
    check('… dabei liegt die Bild-Ebene bildfüllend über dem Video (nie ein kleines Bild in der Mitte)', ly and ly[0] == 'visible' and ly[1] == ly[3] and ly[2] == ly[4] and ly[5], ly)
    pg.mouse.up(); time.sleep(0.7)
    gone = pg.evaluate('() => !%s.querySelector(".vlayer")' % R)
    check('… nach dem Loslassen ist die Bild-Ebene weg', gone, gone)
    s2 = st()
    check('loslassen: dort weiter, läuft wieder', not s2['paused'] and abs(s2['t'] - want) < 1.8, [s2, round(want, 1)])
    # 2) am Ende begrenzt: angezeigter Sprung = echter Sprung (nicht „+30 s“, wenn nur noch 5 s bleiben)
    play_at(D - 6); t0 = st()['t']
    pg.mouse.move(60, 450); pg.mouse.down(); pg.mouse.move(60 + 0.8 * W, 450, steps=6); time.sleep(0.5)
    s3 = st(); pg.mouse.up(); time.sleep(0.5)
    real = (D - 0.5) - t0
    check('am Ende: Sprung zeigt nur, was wirklich geht', s3['delta'] == '+%d s' % round(real), [s3['delta'], round(real, 1)])
    # 3) zurück zum Ausgangspunkt: nichts ändern, läuft weiter
    play_at(20); t0 = st()['t']
    pg.mouse.move(150, 450); pg.mouse.down(); pg.mouse.move(150 + 0.3 * W, 450, steps=5); time.sleep(0.3); pg.mouse.move(150, 450, steps=5); time.sleep(0.4)
    s4 = st(); pg.mouse.up(); time.sleep(0.6); s5 = st()
    check('zurück zum Ausgangspunkt: „+0 s“, loslassen = Stelle bleibt, läuft weiter', s4['delta'] == '+0 s' and abs(s5['t'] - t0) < 1.5 and not s5['paused'], [s4['delta'], round(t0, 1), s5])
    # 4) angehalten gewischt: bleibt danach angehalten
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.pause(); v.currentTime = 15; }' % R); time.sleep(0.5)
    pg.mouse.move(100, 450); pg.mouse.down(); pg.mouse.move(100 + 0.2 * W, 450, steps=5); time.sleep(0.4); pg.mouse.up(); time.sleep(0.6)
    s6 = st()
    check('angehalten gewischt: danach weiter angehalten, an der neuen Stelle', s6['paused'] and abs(s6['t'] - (15 + 0.2 * span)) < 1.5, s6)
    # 5) kurzes Video (Reel): ganze Breite = 20 s statt 90 s
    check('Tempo: ganze Breite = Länge (20–90 s) – hier %d s' % span, True)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
