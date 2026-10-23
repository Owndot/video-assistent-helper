import pwshim  # BROWSER=firefox → Tests in Firefox
# Spulen auf der Leiste (19.4–19.7): antippen = hinspringen · ziehen = Stelle unter dem Finger, Leiste wird dick,
# Abstand zur alten Stelle steht da („+18 s“) · nach oben weg = abbrechen (Hinweis, loslassen = zurück) · zurück nach
# unten = weiter · zurück an die alte Stelle rastet ein · Enden der Leiste = Anfang/Ende ·
# Lupe (19.8, wie iPhone): Finger beim Ziehen unter die Leiste = „Lupe“, tiefer = „Fein“, wieder hoch = normal; Leiste
# zeigt den Ausschnitt, Grenzen links/rechts; am Ende der Leiste wandert er mit; nur bei Videos ab 1,5 min ·
# großer Punkt nur beim Ziehen, unter dem Finger (kein Punkt für die alte Stelle) · Zeit und Punkt im Glas des Designs.
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
    def sec(tx): return int(tx.split(':')[0]) * 60 + int(tx.split(':')[1].split(',')[0])
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
    def lv_y(y, k):   # Höhe für Stufe k (wie im Skript: Platz unter der Leiste)
        room = max(60, 800 - y - 8); return y + [0, min(42, room * 0.32) + 14, min(92, room * 0.68) + 14][k]
    # 5) großer Punkt nur beim Ziehen, unter dem Finger; kurzes Video: nach unten → keine Lupe
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    k0 = pg.evaluate('() => [getComputedStyle(%s.querySelector(".vknob")).opacity, %s.querySelector(".vp").classList.contains("ctl")]' % (R, R))
    check('Bedienung sichtbar, ohne Ziehen: kein großer Punkt (überdeckt keine Zeit)', float(k0[0]) < 0.05 and k0[1], k0)
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.2 * W, y, steps=3); time.sleep(0.2)
    kn = pg.evaluate('() => { const r = %s, k = r.querySelector(".vknob").getBoundingClientRect(), n = r.querySelectorAll(".vbarmk").length; return [k.left + k.width / 2, k.width, getComputedStyle(r.querySelector(".vknob")).opacity, n]; }' % R)
    check('großer Punkt beim Ziehen unter dem Finger', abs(kn[0] - (fx + 0.2 * W)) < 3 and kn[1] >= 30 and float(kn[2]) > 0.9, [kn, fx + 0.2 * W])
    check('… kein zweiter Punkt für die alte Stelle', kn[3] == 0, kn)
    pg.mouse.move(fx + 0.2 * W, lv_y(y, 2), steps=4); time.sleep(0.2)
    s5 = state(); pg.mouse.up(); time.sleep(0.6)
    check('kurzes Video: nach unten → keine Lupe', not s5[2] and abs(t() - at(x, fx + 0.2 * W, W)) < 1, [s5, t()])
    check('… Abstand zur alten Stelle steht da', '+' in s5[3], s5)
    # 6) weg und zurück an die alte Stelle: kein Einrasten (19.11) – der Punkt folgt nur dem Finger („+0 s“)
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    xr = fx + 0.3 * W; r1 = at(x, xr, W)
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(xr, y, steps=6); time.sleep(0.15)
    s6a = state(); pg.mouse.move(fx, y, steps=6); time.sleep(0.2)
    s6 = state(); pg.mouse.up(); time.sleep(0.6)
    check('beim Ziehen: Abstand zur laufenden Stelle („+… s“)', any(('+%d s' % (round(r1 - 20) + k)) in s6a[3] for k in (-1, 0, 1)), [s6a, round(r1 - 20)])
    check('zurück an der alten Stelle: kein Einrasten, „+0 s“', not s6[4] and '+0 s' in s6[3] and 'abbrechen' not in s6[3], s6)
    check('… loslassen dort: bei 20 s', abs(t() - 20) < 0.6, t())
    # 6b) Video läuft weiter; Abbrechen nach oben zeigt die LAUFENDE Stelle (Zeit zählt mit), keine kleine Vorschau;
    #     loslassen = nichts ändern (kein Sprung zurück)
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 10; v.play(); }' % R); time.sleep(0.6)
    x, y, W = geo(); tl = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R); fx = x + 7 + (tl / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=4); time.sleep(0.3)
    pg.mouse.move(fx + 0.3 * W, y - 130, steps=4); time.sleep(0.3)
    p1 = pg.evaluate('() => { const r = %s; return [r.querySelector(".vp").classList.contains("scrubx"), r.querySelector(".vprev b").textContent, getComputedStyle(r.querySelector(".vpbox")).display, r.querySelector(".vtime").textContent, r.querySelector(".vv").currentTime]; }' % R)
    time.sleep(1.5)
    p2 = pg.evaluate('() => { const r = %s; return [r.querySelector(".vtime").textContent, r.querySelector(".vv").currentTime]; }' % R)
    pg.mouse.up(); time.sleep(0.4)
    after = pg.evaluate('() => { const v = %s.querySelector(".vv"); return [v.currentTime, v.paused]; }' % R)
    check('läuft weiter: Abbrechen nach oben → „Loslassen = abbrechen“, keine kleine Vorschau, laufende Stelle', p1[0] and 'abbrechen' in p1[1] and p1[2] == 'none' and abs(sec(p1[3]) - p1[4]) <= 1.2, p1)
    check('… Zeit läuft mit dem Video mit', sec(p2[0]) >= sec(p1[3]) + 1, [p1, p2])
    check('… loslassen: kein Sprung zurück, Video läuft weiter', not after[1] and after[0] >= p2[1] - 0.3, [p2, after])
    pg.evaluate('() => %s.querySelector(".vv").pause()' % R)
    # 7) bis an den rechten Rand = Ende, an den linken = Anfang (egal, wo der Finger anfängt)
    setT(20); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.6 * W, y); pg.mouse.down(); pg.mouse.move(x + W + 30, y, steps=4); time.sleep(0.2)
    s7 = state(); pg.mouse.up(); time.sleep(0.6)
    check('bis zum rechten Ende der Leiste: Ende', t() > D - 1.5 and s7[3].startswith('1:00'), [t(), s7])
    setT(40); x, y, W = geo()
    pg.mouse.move(x + 7 + 0.3 * W, y); pg.mouse.down(); pg.mouse.move(x - 10, y, steps=4); time.sleep(0.2)
    pg.mouse.up(); time.sleep(0.6)
    check('bis zum linken Ende: Anfang', t() < 0.5, t())
    # ---------- Sicherheit: nichts bleibt hängen ----------
    def stuck(): return pg.evaluate('() => { const w = %s.querySelector(".vp"); return [w.classList.contains("thick"), w.classList.contains("scrub"), w.classList.contains("zoom"), w.classList.contains("scrubx")]; }' % R)
    # S1) Finger „verloren“ (ohne Loslassen) → sauber abbrechen, Stelle bleibt
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=4); time.sleep(0.2)
    pg.evaluate('() => { const h = %s.querySelector(".vbarhit"); try { h.releasePointerCapture(1); } catch (e) {} h.dispatchEvent(new PointerEvent("lostpointercapture", { pointerId: 1, bubbles: true })); }' % R); time.sleep(0.3)
    k1 = stuck(); pg.mouse.up(); time.sleep(0.4)
    check('Finger verloren: sauber abgebrochen, nichts hängt, Stelle bleibt', not any(k1) and abs(t() - 20) < 0.6, [k1, t()])
    # S2) App gewechselt während des Ziehens → abbrechen
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=4); time.sleep(0.2)
    pg.evaluate('() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => true }); document.dispatchEvent(new Event("visibilitychange")); }'); time.sleep(0.3)
    k2 = stuck()
    pg.evaluate('() => { delete document.hidden; document.dispatchEvent(new Event("visibilitychange")); }'); pg.mouse.up(); time.sleep(0.4)
    check('App gewechselt beim Ziehen: abgebrochen, Stelle bleibt', not any(k2) and abs(t() - 20) < 0.6, [k2, t()])
    # S3) anderes Video beginnt mitten im Spulen → abbrechen (Hinweis), kein Sprung ins neue Video
    setT(20); x, y, W = geo(); fx = x + 7 + (20 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.3 * W, y, steps=4); time.sleep(0.2)
    pg.evaluate('(u) => { const v = %s.querySelector(".vv"); v.src = u; }' % R, B + 'long.webm'); time.sleep(1.0)
    pg.mouse.move(fx + 0.35 * W, y, steps=2); time.sleep(0.3)
    k3 = stuck(); ind = pg.evaluate('() => (%s.querySelector(".vind") || {}).textContent || ""' % R)
    pg.mouse.up(); time.sleep(0.5)
    check('anderes Video beim Spulen: abgebrochen mit Hinweis, kein Sprung', not any(k3) and 'gewechselt' in ind and t() < 5, [k3, ind, t()])
    # ---------- Lupe (langes Video, 4 min → Lupe 40 s, Fein 10 s) ----------
    open_player(pg, 'long.html'); time.sleep(0.4)
    D = pg.evaluate('() => %s.querySelector(".vv").duration' % R); Z1 = min(max(D / 6, 20), 60); Z2 = max(5, Z1 / 4)
    def times(): return pg.evaluate('() => { const r = %s; return [...r.querySelectorAll(".vtime")].map(e => [e.textContent, e.classList.contains("vtz"), e.classList.contains("g")]); }' % R)
    def mmss(v): v = int(v); return '%d:%02d' % (v // 60, v % 60)
    def sec(tx): return int(tx.split(':')[0]) * 60 + int(tx.split(':')[1].split(',')[0])
    def bar_t(): return pg.evaluate('() => parseFloat(%s.querySelector(".vprev").dataset.t || "-1")' % R)
    # Z1) ziehen, dann unter die Leiste → Lupe: Schild „Lupe“, Grenzen des Ausschnitts links/rechts im Glas
    setT(100); x, y, W = geo(); fx = x + 7 + (100 / D) * W; x1 = fx + 0.1 * W; t1 = at(x, x1, W)
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(x1, y, steps=3); time.sleep(0.1); pg.mouse.move(x1, lv_y(y, 1), steps=4); time.sleep(0.3)
    z1 = state(); tz = times(); f = (x1 - x - 7) / W; ws = min(max(t1 - f * Z1, 0), D - Z1)
    check('Lupe: Finger unter die Leiste → Lupe an (Schild „Lupe“)', z1[2] and z1[3].startswith('Lupe'), z1)
    check('… links/rechts die Grenzen des Ausschnitts im Glas', tz[0][1] and tz[1][1] and tz[0][2] and abs(sec(tz[0][0]) - ws) <= 1 and abs(sec(tz[1][0]) - (ws + Z1)) <= 1, [tz, mmss(ws), mmss(ws + Z1)])
    # Z2) in der Lupe: 20 % der Leiste = 20 % des Ausschnitts
    pg.mouse.move(x1 + 0.2 * W, lv_y(y, 1), steps=4); time.sleep(0.3)
    zt = sec(state()[3].split(' · ')[1])
    check('… in der Lupe fein: 20 % der Leiste = 20 % des Ausschnitts', abs(zt - (t1 + 0.2 * Z1)) <= 1.2, [zt, round(t1 + 0.2 * Z1, 1)])
    # Z3) noch tiefer → Fein (Zehntelsekunden), 20 % der Leiste = 20 % von 10 s
    pg.mouse.move(x1 + 0.2 * W, lv_y(y, 2), steps=4); time.sleep(0.3)
    z3 = state(); pg.mouse.move(x1 + 0.4 * W, lv_y(y, 2), steps=4); time.sleep(0.3); z3b = state()
    check('… noch tiefer: „Fein“ mit Zehntelsekunden', z3[3].startswith('Fein') and ',' in z3[3].split(' · ')[1], z3)
    check('… in „Fein“: 20 %% der Leiste = 20 %% von %d s' % Z2, abs(sec(z3b[3].split(' · ')[1]) - (zt + 0.2 * Z2)) <= 1.2, [z3b, zt + 0.2 * Z2])
    # Z4) wieder hoch auf die Leiste → normal (keine Lupe, Zeiten normal), Stelle bleibt; weiterziehen → wieder unter dem Finger
    tz4 = sec(z3b[3].split(' · ')[1])
    pg.mouse.move(x1 + 0.4 * W, y, steps=5); time.sleep(0.3)
    z4 = state(); tm4 = times()
    check('wieder hoch: zurück zu normal, Stelle bleibt', not z4[2] and not z4[3].startswith(('Lupe', 'Fein')) and abs(sec(z4[3].split(' · ')[0]) - tz4) <= 1 and not tm4[1][1], [z4, tz4, tm4])
    pg.mouse.move(x1 + 0.6 * W, y, steps=6); time.sleep(0.1); pg.mouse.move(x1 + 0.45 * W, y, steps=6); time.sleep(0.3)
    pg.mouse.up(); time.sleep(0.6)
    check('… weiterziehen: Stelle gleitet wieder unter den Finger', abs(t() - at(x, x1 + 0.45 * W, W)) < 1.5, [t(), round(at(x, x1 + 0.45 * W, W), 1)])
    tz5 = times()
    check('… nach dem Loslassen Zeiten normal (Länge rechts, ohne Schild)', tz5[1][0] == mmss(D) and not tz5[0][1], tz5)
    # Z5) Lupe am rechten Ende der Leiste → Ausschnitt wandert mit
    setT(60); x, y, W = geo(); fx = x + 7 + (60 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 12, y, steps=2); pg.mouse.move(fx + 12, lv_y(y, 1), steps=4); time.sleep(0.2)
    pg.mouse.move(x + 7 + W - 2, lv_y(y, 1), steps=4); time.sleep(0.2); a4 = times(); time.sleep(1.2); b4 = times()
    pg.mouse.up(); time.sleep(0.6)
    check('Lupe am Ende der Leiste: Ausschnitt wandert mit', sec(b4[0][0]) > sec(a4[0][0]) + 5 and t() > sec(a4[0][0]) + Z1, [a4, b4, t()])
    # Z5b) Einstellung „Spul-Lupe: Aus“ → nach unten keine Lupe
    more(pg); pick(pg, 'Einstellungen'); lab = [i for i in items(pg) if i[0] in ('Spul-Lupe', 'Vibration')]
    pick(pg, 'Spul-Lupe'); pg.evaluate('() => { const r = %s; r.querySelector(".vmenu").classList.remove("on"); r.querySelector(".vp").classList.remove("menu"); }' % R); time.sleep(0.3)
    setT(100); x, y, W = geo(); fx = x + 7 + (100 / D) * W
    pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 20, y, steps=2); pg.mouse.move(fx + 20, lv_y(y, 2), steps=4); time.sleep(0.3)
    z5b = state(); pg.mouse.up(); time.sleep(0.5)
    check('Einstellungen „Spul-Lupe“ und „Vibration“ da; Lupe aus → nach unten keine Lupe', len(lab) == 2 and not z5b[2], [lab, z5b])
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Spul-Lupe'); pg.evaluate('() => { const r = %s; r.querySelector(".vmenu").classList.remove("on"); r.querySelector(".vp").classList.remove("menu"); }' % R); time.sleep(0.3)
    # Z6) Design: Zeit über der Vorschau im selben Glas wie das Vorschaubild – bei „Glas“ und bei „Schlicht“ (Milchglas)
    for flat in (False, True):
        pg.evaluate('(f) => %s.host.classList.toggle("flat", f)' % R, flat)
        setT(100); x, y, W = geo(); fx = x + 7 + (100 / D) * W
        pg.mouse.move(fx, y); pg.mouse.down(); pg.mouse.move(fx + 0.1 * W, y, steps=3); time.sleep(0.3)
        bg = pg.evaluate('() => [".vprev b", ".vpbox", ".vknob"].map(q => { const c = getComputedStyle(%s.querySelector(q)); return [c.backgroundColor, c.backdropFilter || c.webkitBackdropFilter]; })' % R)
        pg.mouse.up(); time.sleep(0.5)
        if flat: check('Schlicht (Milchglas): Zeit und Punkt im selben Milchglas wie das Vorschaubild', bg[0] == bg[1] == bg[2], bg)
        else: check('Glas: Zeit und Punkt im Glas (Lichtbrechung wie die Knöpfe)', all(b[1].startswith('url(') for b in bg), bg)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
