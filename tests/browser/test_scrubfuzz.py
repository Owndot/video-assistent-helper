import pwshim  # BROWSER=firefox → Tests in Firefox
# Zufalls-Test fürs Spulen auf der Leiste: viele zufällige Gesten – ziehen, hoch (abbrechen), runter (Lupe),
# an die Enden, antippen, Finger „verloren“, App gewechselt, anderes Video mitten im Ziehen. Nach jeder Geste muss alles
# sauber sein: nichts hängt (Leiste nicht mehr dick, keine Lupe, kein Abbrechen-Zustand, Zeiten normal), das Video steht
# an einer gültigen Stelle, keine Fehler. Fester Startwert – ein Fehler lässt sich genau so wiederholen (FUZZ_SEED=…).
import time, random, os
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
SEED = int(os.environ.get('FUZZ_SEED', '1911')); N = int(os.environ.get('FUZZ_N', '90'))
rnd = random.Random(SEED)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'long.html'); time.sleep(0.5)
    def clean():
        return pg.evaluate('''() => { const r = %s, w = r.querySelector(".vp"), v = r.querySelector(".vv");
            const bad = ["thick", "scrub", "zoom", "scrubx"].filter(c => w.classList.contains(c));
            if ([...r.querySelectorAll(".vtime")].some(e => e.classList.contains("vtz"))) bad.push("vtz");
            if (getComputedStyle(r.querySelector(".vknob")).opacity !== "0") bad.push("knob");
            if (!(v.currentTime >= 0 && v.currentTime <= v.duration + 0.5)) bad.push("time " + v.currentTime);
            return bad; }''' % R)
    bad, log = [], []
    for k in range(N):
        if rnd.random() < 0.3: pg.evaluate('() => { const v = %s.querySelector(".vv"); if (v.paused) v.play(); else v.pause(); }' % R)
        ctl(pg)
        x, y, W = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top + r.height / 2, r.width - 14]; }' % R)
        kind = rnd.choice(['drag'] * 6 + ['tap', 'lost', 'hide', 'swap'])
        log.append(kind)
        pg.mouse.move(x + 7 + rnd.random() * W, y + rnd.uniform(-8, 8)); pg.mouse.down()
        if kind != 'tap':
            for i in range(rnd.randint(2, 7)):
                tx = rnd.choice([x + 7 + rnd.random() * W, x - 20, x + W + 30, x + 7 + rnd.random() * W])
                ty = y + rnd.choice([0, 0, rnd.uniform(-20, 20), rnd.uniform(25, 140), rnd.uniform(-200, -80)])
                pg.mouse.move(tx, ty, steps=rnd.randint(1, 8))
                if rnd.random() < 0.3: time.sleep(rnd.uniform(0.05, 0.6))
            if kind == 'lost':
                pg.evaluate('() => %s.querySelector(".vbarhit").dispatchEvent(new PointerEvent("lostpointercapture", { pointerId: 1, bubbles: true }))' % R)
            elif kind == 'hide':
                pg.evaluate('() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => true }); document.dispatchEvent(new Event("visibilitychange")); delete document.hidden; document.dispatchEvent(new Event("visibilitychange")); }')
            elif kind == 'swap':   # anderes Video beginnt (gleiche Datei neu – andere Adresse)
                pg.evaluate('(u) => { const v = %s.querySelector(".vv"); const t = v.currentTime; v.src = u + "?n=" + Math.random(); v.currentTime = t; v.play(); }' % R, B + 'long.webm'); time.sleep(0.6)
                pg.mouse.move(x + 7 + rnd.random() * W, y, steps=2)
        pg.mouse.up(); time.sleep(0.45)
        c = clean()
        if c: bad.append((k, kind, c))
    print('   Gesten:', {g: log.count(g) for g in set(log)}, '· Startwert', SEED)
    check('%d zufällige Gesten: nach jeder alles sauber (nichts hängt, gültige Stelle)' % N, not bad, bad[:5])
    check('keine Fehler', not errs, errs[:3])
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
