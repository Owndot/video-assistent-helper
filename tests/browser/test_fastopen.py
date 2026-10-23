import pwshim  # BROWSER=firefox → Tests in Firefox
# Schneller öffnen und schließen: Sofortbild (Bild des Seitenvideos) gleich beim Öffnen, bis die eigene Kopie läuft –
# auch wenn der Server langsam ist; Schließen: Player sofort weg, Aufräumen danach, Seite spielt weiter.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    slow = {'on': False}
    ctx.route('**/clip.webm*', lambda r: (time.sleep(2.0) if slow['on'] else None, r.continue_())[1])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto(B + 'direct.html'); pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.0)
    slow['on'] = True   # ab jetzt antwortet der Server 2 s langsam (wie ein schwaches Netz)
    r = pg.evaluate('''() => new Promise((res) => { const R = %s, t0 = performance.now();
        R.querySelector("button[aria-label='Im Videoplayer öffnen']").click();
        requestAnimationFrame(() => { const c = R.querySelector(".vposter"), v = R.querySelector(".vv");
            res([performance.now() - t0, !!c, c ? c.width : 0, v ? v.readyState : -1]); }); })''' % R)
    check('Sofortbild: gleich beim Öffnen das Bild des Seitenvideos (Server noch nicht da)', r[1] and r[2] > 0 and r[3] < 2 and r[0] < 300, r)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3 && !v.paused; }' % R, timeout=20000)
    time.sleep(0.4)
    gone = pg.evaluate('() => !%s.querySelector(".vposter")' % R)
    check('… weg, sobald das eigene Video läuft', gone, gone)
    slow['on'] = False
    time.sleep(1.0)
    c = pg.evaluate('''() => { const R = %s, w = R.querySelector(".vp"); R.querySelector("button[aria-label=Schließen]").click();
        return [w.isConnected ? getComputedStyle(w).display : 'entfernt', document.getElementById("v").muted]; }''' % R)
    check('Schließen: Player im selben Augenblick weg', c[0] in ('none', 'entfernt'), c)
    time.sleep(0.8)
    a = pg.evaluate('() => [!%s.querySelector(".vp"), !document.getElementById("v").paused, document.getElementById("v").muted]' % R)
    check('… danach aufgeräumt, Seite spielt weiter (mit Ton)', a[0] and a[1] and not a[2], a)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
