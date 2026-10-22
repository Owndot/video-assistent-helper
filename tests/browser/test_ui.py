import pwshim  # BROWSER=firefox → Tests in Firefox
# Neue Aufteilung (16.0): ⋮ oben rechts, Untertitel-Knopf unten, Hinweise an einer festen Stelle, Hochwischen über
# die Zeitleiste spult nicht, Oberfläche Deutsch/Englisch (automatisch nach Browsersprache oder per Einstellung).
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'subs.html')
    # Aufteilung „oben schlank“ (16.4): oben nur Download und ⋮, unten Tempo, CC, Bildformat, Bild-in-Bild, Drehen
    lay = pg.evaluate('''() => { const r = %s, vis = (b) => !b.hidden;
        return [Array.from(r.querySelectorAll(".vcluster button")).map(b => b.getAttribute("aria-label")),
                Array.from(r.querySelectorAll(".vhead .vtools:not(.solo) button")).filter(vis).map(b => b.getAttribute("aria-label")),
                (() => { const t = r.querySelector(".vtitle span"); return [t.textContent, t.scrollWidth <= t.clientWidth + 1]; })()]; }''' % R)
    want_bot = ['Untertitel', 'Wiederholen', 'Bildformat'] + (['Bild-in-Bild'] if 'Bild-in-Bild' in lay[1] else []) + ['Drehen']
    check('oben nur Download und ⋮, unten CC · Wiederholen · Format · Bild-in-Bild · Drehen', lay[0] == ['Herunterladen (z. B. mit YTDLnis)', 'Mehr'] and lay[1] == want_bot, lay)
    check('Titel ganz lesbar (nicht abgeschnitten)', lay[2][1], lay[2])
    more(pg); mi = [x[0] for x in items(pg)]
    check('⋮: Teilen, Standbild, Einstellungen (Einstellungen zuletzt)', mi[0] == 'Link teilen' and mi[-1] == 'Einstellungen' and 'Standbild speichern' in mi, mi)
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R)); time.sleep(0.3)
    subsm(pg); pick(pg, 'Deutsch'); time.sleep(0.5)
    check('Untertitel an: Knopf leuchtet', pg.evaluate('() => %s.querySelector("button[aria-label=Untertitel]").classList.contains("on")' % R))
    # Hinweise (16.3): eine Stelle UNTER dem Bild, mit Abstand – im selben Glas wie die Knöpfe
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Bildformat]").click()' % R); time.sleep(0.3)
    NOTE = '''() => { const r = %s, t = r.querySelector(".toast"), tb = t.getBoundingClientRect(), bot = r.querySelector(".vbot").getBoundingClientRect(), vid = r.querySelector(".vv");
        const vr = vid.getBoundingClientRect(), fit = getComputedStyle(vid).objectFit, sc = Math.min(vr.width / vid.videoWidth, vr.height / vid.videoHeight);
        const pb = fit === "contain" ? vr.top + (vr.height + vid.videoHeight * sc) / 2 : vr.bottom;
        return [t.parentElement.classList.contains("vp"), Math.round(tb.top), Math.round(tb.bottom), Math.round(bot.top), Math.round(pb), t.textContent, t.classList.contains("g")]; }''' % R
    pos = pg.evaluate(NOTE)
    print('   Hinweis:', pos)
    # „Füllen“: Bild füllt den Bildschirm → Hinweis knapp über der unteren Leiste
    check('Füllen: Hinweis knapp über der unteren Leiste, im Glas der Knöpfe', pos[0] and pos[6] and pos[3] - 30 <= pos[2] <= pos[3] - 10, pos)
    for _ in range(2):   # → Strecken → Original (Bild mit schwarzen Rändern)
        ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Bildformat]").click()' % R); time.sleep(0.35)
    time.sleep(0.3)
    pos2 = pg.evaluate(NOTE)
    check('Original: Hinweis mit Abstand unter der Unterkante des Bildes', pos2[5] == 'Original' and pos2[4] + 8 <= pos2[1] <= pos2[4] + 20, pos2)
    pg.evaluate('() => %s.querySelector("button[aria-label=Bildformat]").click()' % R); time.sleep(0.2)
    pg.evaluate('() => %s.querySelector("button[aria-label=Bildformat]").click()' % R); time.sleep(0.5)
    # Hochwischen über die Zeitleiste (Android-Geste) spult nicht – Ziehen zur Seite schon
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.pause(); v.currentTime = 20; }' % R); time.sleep(0.5); ctl(pg)
    br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
    y = br[1] + br[3] / 2
    pg.mouse.move(br[0] + br[2] * 0.85, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.85, y - 70, steps=8); pg.mouse.up(); time.sleep(0.6)
    t1 = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    check('Hochwischen über die Zeitleiste: Stelle bleibt', abs(t1 - 20) < 1, round(t1, 1))
    # (19.6: beim Ziehen steht die Stelle unter dem Finger)
    W = br[2] - 14
    tb = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    ctl(pg); pg.mouse.move(br[0] + br[2] * 0.3, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.6, y, steps=8); pg.mouse.up(); time.sleep(0.6)
    t2 = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    exp2 = (br[2] * 0.6 - 7) / W * 60
    check('zur Seite ziehen: spult (Stelle unter dem Finger)', abs(t2 - exp2) < 3, [round(t2, 1), round(exp2, 1)])
    # Kreis halten, Finger wackelt beim Greifen etwas nach oben, dann zur Seite → spult trotzdem (16.1: milder Schutz)
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 10; }' % R); time.sleep(0.4)
    tb = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    ctl(pg); pg.mouse.move(br[0] + br[2] * 0.3, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.3 + 3, y - 22, steps=5)
    pg.mouse.move(br[0] + br[2] * 0.8, y - 22, steps=10); pg.mouse.up(); time.sleep(0.6)
    t3 = pg.evaluate('() => %s.querySelector(".vv").currentTime' % R)
    exp3 = (br[2] * 0.8 - 7) / W * 60
    check('Halten mit Wackler nach oben, dann seitlich: spult', abs(t3 - exp3) < 3, [round(t3, 1), round(exp3, 1)])
    # wie Samsung: Zeitleiste über den Knöpfen (Knöpfe liegen zwischen Leiste und Android-Wischgeste)
    lay2 = pg.evaluate('() => { const r = %s; return [r.querySelector(".vrow").getBoundingClientRect().bottom, r.querySelector(".vhead").getBoundingClientRect().top]; }' % R)
    check('Zeitleiste über der Knopfreihe', lay2[0] <= lay2[1] + 1, lay2)
    # Teilen: Link zum Video (hier die Seite selbst), mit Titel
    pg.evaluate('() => { window.__shared = []; Object.defineProperty(navigator, "share", { configurable: true, value: (d) => { window.__shared.push(d); return Promise.resolve(); } }); }')
    more(pg); pick(pg, 'Link teilen'); time.sleep(0.5)   # über das ⋮-Menü (echter Weg)
    sh = pg.evaluate('() => window.__shared')
    check('Teilen-Knopf: teilt Link und Titel', len(sh) == 1 and sh[0]['url'].endswith('/subs.html') and sh[0].get('title'), sh)
    # ohne Teilen-Menü (oder abgelehnt): Link wird kopiert
    pg.evaluate('() => { Object.defineProperty(navigator, "share", { configurable: true, value: undefined }); window.__copied = null; navigator.clipboard.writeText = (t) => { window.__copied = t; return Promise.resolve(); }; }')
    more(pg); pick(pg, 'Link teilen'); time.sleep(0.5)
    cp = pg.evaluate('() => [window.__copied, %s.querySelector(".toast").textContent]' % R)
    check('… ohne Teilen-Menü: Link kopiert', cp[0] and cp[0].endswith('/subs.html') and cp[1] == 'Link kopiert', cp)
    # Einstellungen: Wischsperre mit Stufe in Worten und kurzer Erklärung – Hinweis ÜBER dem offenen Menü
    more(pg); pick(pg, 'Einstellungen'); time.sleep(0.3)
    pick(pg, 'Wischsperre oben'); time.sleep(0.4)
    ws = pg.evaluate('''() => { const r = %s, it = Array.from(r.querySelectorAll(".vmenu.on .vmi")).find((e) => e.textContent.startsWith("Wischsperre oben")), t = r.querySelector(".toast");
        return [it ? it.textContent : "", t.textContent, Math.round(t.getBoundingClientRect().bottom), Math.round(r.querySelector(".vmenu.on").getBoundingClientRect().top)]; }''' % R)
    check('Wischsperre oben: Stufe in Worten, Erklärung über dem Menü', ws[0] == 'Wischsperre obenBreit' and ws[1] == 'Oberste 20 %: kein Wischen für Helligkeit/Lautstärke' and ws[2] <= ws[3], ws)
    for _ in range(4): pick(pg, 'Wischsperre oben'); time.sleep(0.15)   # zurück auf Mittel (15 %)
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R)); time.sleep(0.3)
    # Sprache per Einstellung: English (gilt ab dem nächsten Öffnen)
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Sprache'); pick(pg, 'Sprache')
    check('Einstellung „Sprache“: Auto → Deutsch → English (Menü sofort englisch)', ['Language', 'English', False] in items(pg), items(pg))
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.6)
    pg.evaluate('() => %s.querySelector(".pill .pb:not(.dl)").click()' % R)   # Knopf auf der Seite (Beschriftung ab Neuladen)
    pg.wait_for_function('() => %s.querySelector(".vv")' % R, timeout=15000); time.sleep(0.8)
    ctl(pg)
    en = pg.evaluate('() => Array.from(%s.querySelectorAll(".vp button[aria-label]")).map(b => b.getAttribute("aria-label"))' % R)
    check('nach Umschalten: Knöpfe englisch', all(x in en for x in ['Close', 'More', 'Subtitles', 'Rotate', 'Aspect ratio']), en)
    pg.evaluate('() => { const r = %s; r.querySelector("button[aria-label=More]").click(); Array.from(r.querySelectorAll(".vmenu .vmi")).find(x => x.firstChild.textContent === "Settings").click(); }' % R); time.sleep(0.4)
    m = items(pg)
    check('Einstellungen englisch und kurz', [x[0] for x in m][:4] == ['Design', 'Ambient', 'Progress line', 'Full screen'] and ['Skip', '10 s', False] in m and ['Language', 'English', False] in m, m)
    # zurück auf Auto
    pick(pg, 'Language')
    ctx.close()
    # Englischer Browser: alles automatisch englisch
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2, locale='en-US')
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto(B + 'subs.html'); pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    has = pg.evaluate('() => !!%s.querySelector("button[aria-label=\'Open in video player\']")' % R)
    check('englischer Browser: Knopf auf der Seite englisch', has)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Open in video player\']").click()' % R)
    pg.wait_for_function('() => %s.querySelector(".vv") && isFinite(%s.querySelector(".vv").duration)' % (R, R), timeout=15000); time.sleep(0.6)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Subtitles]").click()' % R); time.sleep(0.35)
    m = items(pg)
    check('Untertitel-Menü englisch', [x[0] for x in m][:2] == ['Off', 'Deutsch'] and 'Load file …' in [x[0] for x in m] and ['Size', 'medium', False] in m, m)
    pick(pg, 'English'); time.sleep(0.6)
    t = pg.evaluate('() => %s.querySelector(".toast").textContent' % R)
    check('Hinweis englisch', t == 'Subtitles: English', t)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=\'Aspect ratio\']").click()' % R); time.sleep(0.3)
    check('Bildformat-Hinweis englisch', pg.evaluate('() => %s.querySelector(".toast").textContent' % R) == 'Fill')
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
