import pwshim  # BROWSER=firefox → Tests in Firefox
# Ruhiger Start (wie Samsung): Beim Öffnen nur das Bild, die Bedienung erst nach dem ersten Antippen.
# Einstellung „Beim Öffnen: Mit Bedienung“ zeigt sie gleich. Läuft das Video nach 2 s nicht, kommt die Bedienung trotzdem.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
OPEN = "%s.querySelector(\"button[aria-label='Im Videoplayer öffnen']\").click()" % R
CTL = '() => %s.querySelector(".vp").classList.contains("ctl")' % R

def page(b, pre=''):
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    if pre: ctx.add_init_script(pre)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto(B + 'direct.html'); pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }')
    pg.wait_for_function("() => %s && %s.querySelector(\"button[aria-label='Im Videoplayer öffnen']\")" % (R, R), timeout=20000)
    return ctx, pg, errs

def playing(pg):
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3 && !v.paused; }' % R, timeout=20000)

with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    # 1) Standard „Nur Bild“
    ctx, pg, errs = page(b)
    pg.evaluate('() => ' + OPEN); playing(pg); time.sleep(2.4)
    c = pg.evaluate(CTL)
    check('Standard: nach dem Öffnen nur das Bild (keine Bedienung, auch nach 2 s)', not c, c)
    op = pg.evaluate('() => getComputedStyle(%s.querySelector(".vchrome .vrow")).opacity' % R)
    check('… Bedienleiste unsichtbar', float(op) == 0, op)
    pg.touchscreen.tap(200, 400); time.sleep(0.6)
    c = pg.evaluate(CTL)
    check('erstes Antippen zeigt die Bedienung', c, c)
    tr = pg.evaluate('() => getComputedStyle(%s.querySelector(".vchrome .vrow")).transitionDuration' % R)
    check('… mit kurzer, weicher Einblendung (0,16 s)', tr.startswith('0.16'), tr)
    # 2) Video läuft nach dem Öffnen nicht (angehalten) → Bedienung kommt nach 2 s trotzdem
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    pg.evaluate('() => ' + OPEN); playing(pg)
    pg.evaluate('() => %s.querySelector(".vv").pause()' % R); time.sleep(0.5)
    a = pg.evaluate(CTL)
    time.sleep(2.0)
    c = pg.evaluate(CTL)
    check('angehaltenes Video: Bedienung erscheint von selbst', c, [a, c])
    check('keine Fehler (Nur Bild)', not errs, errs)
    ctx.close()
    # 3) Einstellung „Mit Bedienung“
    ctx, pg, errs = page(b, "try { localStorage.setItem('evp-s-settings', JSON.stringify({ openUi: 'controls' })); } catch (e) {}")
    pg.evaluate('() => ' + OPEN); time.sleep(0.3)
    c = pg.evaluate(CTL)
    check('„Mit Bedienung“: Bedienung gleich beim Öffnen', c, c)
    pg.evaluate('() => { const r = %s; r.querySelector("button[aria-label=Mehr]").click(); const b = Array.from(r.querySelectorAll(".vmenu .vmi")).find(x => x.firstChild.textContent === "Einstellungen"); if (b) b.click(); }' % R); time.sleep(0.5)
    m = pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(x => x.firstChild.textContent + "=" + (x.querySelector("small") ? x.querySelector("small").textContent : "")).filter(t => t.startsWith("Beim Öffnen"))' % R)
    check('Einstellungen: „Beim Öffnen = Mit Bedienung“', m == ['Beim Öffnen=Mit Bedienung'], m)
    check('keine Fehler (Mit Bedienung)', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
