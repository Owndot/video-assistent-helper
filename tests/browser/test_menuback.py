import pwshim  # BROWSER=firefox → Tests in Firefox
# Menüs (17.4): ⋮-Menü ohne Überschrift, zweiter Tipp auf ⋮ schließt, Untermenüs mit ‹ zurück, die Zurück-Taste
# (Vollbild-Ende bzw. Verlauf) geht bei offenem Menü nur eine Ebene zurück. Wiederholen: Aus → 1× → 5× → Aus.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'subs.html'); time.sleep(0.8)
    menu = lambda: pg.evaluate('''() => { const m = %s.querySelector(".vmenu"), h = m.querySelector(".vmh");
        return { on: m.classList.contains("on"), head: h ? h.textContent : null, back: !!m.querySelector(".vmb"), items: Array.from(m.querySelectorAll(".vmi")).map(b => b.firstChild.textContent) }; }''' % R)
    opened = lambda: pg.evaluate('() => !!%s.querySelector(".vp")' % R)
    more(pg); m = menu()
    check('⋮-Menü ohne Überschrift', m['on'] and m['head'] is None and 'Einstellungen' in m['items'], m)
    pg.evaluate('() => %s.querySelector(".vcluster button[aria-label=Mehr]").click()' % R); time.sleep(0.4)
    check('zweiter Tipp auf ⋮ schließt das Menü', not menu()['on'], menu())
    more(pg); pick(pg, 'Einstellungen'); time.sleep(0.3); m = menu()
    check('Einstellungen: Überschrift mit ‹ zurück', m['on'] and m['back'] and m['head'] == 'Einstellungen', m)
    pg.evaluate('() => %s.querySelector(".vmenu .vmb").click()' % R); time.sleep(0.4); m = menu()
    check('‹ führt zurück ins ⋮-Menü', m['on'] and m['head'] is None and 'Einstellungen' in m['items'], m)
    for name in ['Geschwindigkeit']:
        pick(pg, name); time.sleep(0.3); m = menu()
        check(name + ': auch mit ‹ zurück', m['on'] and m['back'], m)
        pg.evaluate('() => %s.querySelector(".vmenu .vmb").click()' % R); time.sleep(0.3)
    pg.evaluate('() => %s.querySelector(".vcluster button[aria-label=Mehr]").click()' % R); time.sleep(0.3)
    # Zurück-Taste im Vollbild (beendet das Vollbild) bei offenen Einstellungen: Menü eine Ebene zurück, Player bleibt
    if not pg.evaluate('() => !!document.fullscreenElement'):
        pg.mouse.click(200, 300); time.sleep(0.6)
    fs0 = pg.evaluate('() => !!document.fullscreenElement')
    more(pg); pick(pg, 'Einstellungen'); time.sleep(0.3)
    pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(1.2)
    m = menu()
    check('Zurück-Taste (Vollbild) in den Einstellungen: zurück ins ⋮-Menü, Player bleibt offen', fs0 and opened() and m['on'] and m['head'] is None, [fs0, opened(), m])
    pg.evaluate('() => history.back()'); time.sleep(0.8)
    check('nochmal Zurück (Verlauf): Menü zu, Player bleibt offen', opened() and not menu()['on'], [opened(), menu()])
    pg.evaluate('() => history.back()'); time.sleep(0.8)
    check('noch einmal Zurück: Player zu', not opened())
    # Wiederholen
    open_player(pg, 'subs.html'); time.sleep(0.8)
    rep = lambda: pg.evaluate('() => { const b = %s.querySelector("button[aria-label=Wiederholen]"); return [b.classList.contains("on"), b.querySelector(".vrepn").textContent, %s.querySelector(".toast").textContent]; }' % (R, R))
    seq = []
    for _ in range(3):
        ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Wiederholen]").click()' % R); time.sleep(0.3); seq.append(rep())
    check('Wiederholen: Aus → 1× → 5× → Aus (Zahl am Knopf)', seq[0][:2] == [True, '1'] and seq[1][:2] == [True, '5'] and seq[2][:2] == [False, ''] and seq[0][2] == 'Wiederholen: 1×' and seq[2][2] == 'Wiederholen aus', seq)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Wiederholen]").click()' % R); time.sleep(0.2)   # 1×
    vs = lambda: pg.evaluate('() => { const v = %s.querySelector(".vv"); return [v.paused, Math.round(v.currentTime * 10) / 10, v.ended]; }' % R)
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = v.duration - 1; v.play(); }' % R); time.sleep(2.2)
    a = vs(); r1 = rep()
    check('1× wiederholen: am Ende von vorn, Zahl weg', not a[0] and a[1] < 3 and r1[:2] == [False, ''], [a, r1])
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = v.duration - 1; }' % R); time.sleep(2.2)
    a2 = vs()
    check('… beim zweiten Ende: Schluss (bleibt am Ende stehen)', a2[0] and a2[1] > 50, a2)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
