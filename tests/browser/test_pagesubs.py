import pwshim  # BROWSER=firefox → Tests in Firefox
# Untertitel, die nur die Seite kennt (Mediatheken zeichnen sie selbst): in den Seitendaten (ARD-artig, auch EBU-TT mit
# 10:00:00-Versatz), in nachgeladener Player-Konfiguration (ZDF-artig, TTML mit Ticks) und direkt vom Seiten-Player geladen.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
def subs_menu(pg):
    subsm(pg)
    try: pg.wait_for_function('() => !Array.from(%s.querySelectorAll(".vmenu.on .vmi")).some(b => /Suche Untertitel/.test(b.textContent))' % R, timeout=12000)
    except Exception: pass
    time.sleep(0.3); return items(pg)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    # ARD-artig: WebVTT + EBU-TT derselben Untertitel → einmal "Deutsch", WebVTT gewählt
    open_player(pg, 'ard.html')
    m = subs_menu(pg)
    names = [x[0] for x in m]
    check('ARD-artig: „Deutsch“ von der Seite, nur einmal', names.count('Deutsch') == 1 and ['Deutsch', 'von der Seite', False] in m, m)
    pick(pg, 'Deutsch'); time.sleep(0.5)
    t = sub_at(pg, 10.4)
    check('ARD-artig: richtige Zeile (WebVTT bevorzugt)', t == 'ARD Zeile 10', t)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.4)
    # nur EBU-TT, Zeiten ab 10:00:00
    open_player(pg, 'ard_ebutt.html')
    m = subs_menu(pg); pick(pg, 'Deutsch'); time.sleep(0.5)
    t = sub_at(pg, 20.3)
    check('EBU-TT (XML) mit 10:00:00-Versatz: richtige Zeile', t == 'EBU Zeile 20', [t, m])
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.4)
    # ZDF-artig: Konfiguration nachgeladen, TTML mit Ticks, Versatz im <div>, Zeilenumbruch
    open_player(pg, 'zdf.html')
    m = subs_menu(pg)
    check('ZDF-artig: aus der nachgeladenen Konfiguration', ['Deutsch', 'von der Seite', False] in m, m)
    pick(pg, 'Deutsch'); time.sleep(0.5)
    t = sub_at(pg, 8.5)   # div begin 1 s + p 6–8 s = 7–9 s → "ZDF oben 6"
    check('TTML: Ticks, Versatz, zwei Zeilen', t == 'ZDF oben 6\nunten', repr(t))
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.4)
    # vom Seiten-Player selbst geladen
    open_player(pg, 'netload.html')
    m = subs_menu(pg)
    check('vom Seiten-Player geladen: „English“', ['English', 'von der Seite', False] in m, m)
    pick(pg, 'English'); time.sleep(0.5)
    t = sub_at(pg, 6.2)
    check('richtige Zeile', t == 'Loaded by page 6', t)
    # Seite ohne Untertitel: nichts Falsches
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.4)
    open_player(pg, 'direct.html')
    m = subs_menu(pg)
    check('Seite ohne Untertitel: nur Aus und Datei', [x[0] for x in m][:2] == ['Aus', 'Datei laden …'], m)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
