import pwshim  # BROWSER=firefox → Tests in Firefox
# App verlassen und Bild-in-Bild (16.6):
#  - Wechsel in eine andere App beendet das Vollbild → Player bleibt offen (nur die Zurück-Taste schließt ihn)
#  - zurück im Browser: Hinweis „Antippen für Vollbild“, ein Tipp holt das Vollbild zurück
#  - Einstellung „Beim Verlassen“: Weiter (läuft) / Anhalten
#  - Bild-in-Bild mit ✕ geschlossen = anhalten · „Vollbild“ im kleinen Fenster = zurück in den Vollbild-Player
#  - läuft Bild-in-Bild und man geht zum Startbildschirm: läuft weiter
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
# Sichtbarkeit und Fokus der Seite nachstellen (Playwright kann keine App wechseln)
SIM = '''() => { if (window.__sim) return; window.__sim = { hidden: false, focus: true };
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__sim.hidden });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => window.__sim.hidden ? 'hidden' : 'visible' });
  document.hasFocus = () => window.__sim.focus; }'''
def hide(pg, h):
    pg.evaluate('(h) => { window.__sim.hidden = h; window.__sim.focus = !h; if (h) window.dispatchEvent(new Event("blur")); document.dispatchEvent(new Event("visibilitychange")); }', h)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    def opened(): return pg.evaluate('() => !!%s.querySelector(".vp")' % R)
    def fs(): return pg.evaluate('() => !!document.fullscreenElement')
    def vstate(): return pg.evaluate('() => { const v = %s.querySelector(".vv"); return v ? [v.paused, Math.round(v.currentTime * 10) / 10] : null; }' % R)
    def start():
        open_player(pg, 'subs.html'); time.sleep(0.8); pg.evaluate(SIM)
        if not fs():   # Vollbild wie beim echten Öffnen (mit Tipp)
            pg.mouse.click(200, 300); time.sleep(0.6)
    # 1) App-Wechsel: erst Fokus weg, dann endet das Vollbild, dann ist die Seite verdeckt
    start()
    fs0 = fs()
    pg.evaluate('() => { window.__sim.focus = false; window.dispatchEvent(new Event("blur")); }')
    pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.2)
    hide(pg, True); time.sleep(1.2)
    check('App-Wechsel (Fokus weg → Vollbild aus → verdeckt): Player bleibt offen', fs0 and opened(), [fs0, opened()])
    check('… und wird dabei nicht unsichtbar gemacht', not pg.evaluate('() => %s.querySelector(".vp").classList.contains("vgone")' % R))
    check('… Standard „Weiter“: Video läuft weiter', vstate() and not vstate()[0], vstate())
    hide(pg, False); time.sleep(0.7)
    t = pg.evaluate('() => %s.querySelector(".toast").textContent' % R)
    check('zurück im Browser: Hinweis „Antippen für Vollbild“', t == 'Antippen für Vollbild', t)
    pg.mouse.click(200, 300); time.sleep(0.7)
    check('… ein Tipp holt das Vollbild zurück', fs() and opened(), [fs(), opened()])
    # 2) App-Wechsel, bei dem die Meldung „verdeckt“ erst spät kommt (ohne vorherigen Fokusverlust)
    pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.55)
    hide(pg, True); time.sleep(1.0)
    check('späte Meldung „verdeckt“ (0,55 s nach Vollbild-Ende): Player bleibt offen', opened())
    hide(pg, False); time.sleep(0.3)
    vg2 = pg.evaluate('() => { const w = %s.querySelector(".vp"); return [w.classList.contains("vgone"), %s.querySelector(".vv").muted]; }' % (R, R))
    check('… danach wieder sichtbar und mit Ton', not vg2[0] and not vg2[1], vg2)
    time.sleep(0.2); pg.mouse.click(200, 300); time.sleep(0.7)
    # 3) Zurück-Taste (kein Fokusverlust, Seite bleibt sichtbar): Player schließt
    pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.15)
    vg = pg.evaluate('() => { const w = %s.querySelector(".vp"); return w ? [w.classList.contains("vgone"), getComputedStyle(w).opacity, %s.querySelector(".vv").muted] : null; }' % (R, R))
    check('Zurück-Geste: Player sofort unsichtbar und stumm (man sieht gleich die Seite, 17.8)', vg and vg[0] and float(vg[1]) < 0.5 and vg[2], vg)
    time.sleep(1.15)
    check('Zurück-Taste (Vollbild aus, Seite sichtbar): Player schließt', not opened())
    # 4) Einstellung „Anhalten“
    start()
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.3)
    lab = [x for x in items(pg) if x[0] == 'Beim Verlassen']
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    pg.evaluate('() => %s.querySelector(".vv").play()' % R); time.sleep(0.4)
    hide(pg, True); time.sleep(0.5)
    check('Einstellung „Beim Verlassen: Anhalten“: Video hält an', lab and lab[0][1] == 'Anhalten' and vstate()[0], [lab, vstate()])
    hide(pg, False); time.sleep(0.4)
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)   # zurück auf „Weiter“
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    # 5) Bild-in-Bild mit ✕ geschlossen (Seite im Hintergrund) → anhalten
    pg.evaluate('() => %s.querySelector(".vv").play()' % R); time.sleep(0.4)
    hide(pg, True)
    pg.evaluate('() => %s.querySelector(".vv").dispatchEvent(new Event("leavepictureinpicture"))' % R); time.sleep(0.6)
    check('Bild-in-Bild mit ✕ geschlossen: Video hält an, Player bleibt', vstate()[0] and opened(), [vstate(), opened()])
    hide(pg, False); time.sleep(0.4)
    # 6) „Vollbild“ im kleinen Fenster (Seite wieder sichtbar) → Vollbild-Player (oder ein Tipp holt es)
    if fs(): pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.3)
    pg.evaluate('() => %s.querySelector(".vv").play()' % R); time.sleep(0.3)
    pg.evaluate('() => { window.__st = 1; %s.querySelector(".vv").dispatchEvent(new Event("leavepictureinpicture")); }' % R); time.sleep(0.9)
    t = pg.evaluate('() => %s.querySelector(".toast").textContent' % R)
    ok6 = fs() or t == 'Antippen für Vollbild'
    if not fs(): pg.mouse.click(200, 300); time.sleep(0.7)
    check('Bild-in-Bild → Vollbild: Player im Vollbild, Video läuft', ok6 and fs() and opened() and not vstate()[0], [ok6, fs(), vstate(), t])
    # 7) Bild-in-Bild läuft, dann zum Startbildschirm (Seite verdeckt): läuft weiter – auch bei „Anhalten“
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    pg.evaluate('() => { const r = %s, v = r.querySelector(".vv"); Object.defineProperty(r, "pictureInPictureElement", { configurable: true, get: () => v }); v.play(); }' % R); time.sleep(0.5)
    hide(pg, True); time.sleep(0.6)
    check('Bild-in-Bild läuft + Startbildschirm: Video läuft weiter (auch bei „Anhalten“)', not vstate()[0], vstate())
    hide(pg, False)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
