import pwshim  # BROWSER=firefox → Tests in Firefox
# Vorschau robust: Schlägt das Laden des Vorschau-Videos einmal fehl (Server stockt kurz), versucht Kadri es einmal neu –
# statt für dieses Öffnen nur noch die Zeit zu zeigen.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
SCRIPT = SCRIPT.replace("seekPreview: 'live'", "seekPreview: 'thumb'")
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    fail = {'n': 0, 'hit': 0}
    def rt(r):
        if fail['n'] > 0 and 'long.webm' in r.request.url:
            fail['n'] -= 1; fail['hit'] += 1; r.fulfill(status=500, body='')
        else: r.continue_()
    ctx.route('**/long.webm*', rt)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'long.html'); time.sleep(2.5)   # (Vorschau hat ihr Inhaltsverzeichnis schon)
    fail['n'] = 1   # die nächste Anfrage (Vorschau springt an die Fingerstelle) schlägt fehl
    ctl(pg); x, y, w = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top + r.height / 2, r.width]; }' % R)
    pg.mouse.move(x + 0.2 * w, y); pg.mouse.down(); pg.mouse.move(x + 0.5 * w, y, steps=4)
    ok = False
    for i in range(40):
        time.sleep(0.25); pg.mouse.move(x + (0.5 + 0.003 * (i % 2)) * w, y)
        ok = pg.evaluate('() => %s.querySelector(".vprev").classList.contains("img")' % R)
        if ok: break
    pg.mouse.up()
    check('Vorschau: nach einem Ladefehler einmal neu versucht – Bild kommt', ok and fail['hit'] >= 1, [ok, fail])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
