import pwshim  # BROWSER=firefox → Tests in Firefox
import http.server, threading, functools, time, json, sys
from playwright.sync_api import sync_playwright

WEB = 'web'
SCRIPT = open('../../userscript/video-assistent.user.js', encoding='utf-8').read()
# alte Tests: ohne Ton-Regel und mit Button im Video (wie bisher)
SCRIPT = SCRIPT.replace("onlyWithSound: true", "onlyWithSound: false").replace("buttonAt: 'below'", "buttonAt: 'video'")

import os, re
class Quiet(http.server.SimpleHTTPRequestHandler):
    """Testserver mit Unterstützung für Sprünge (Range), wie echte Webseiten."""
    def log_message(self, *a): pass
    def send_head(self):
        path = self.translate_path(self.path.split('?')[0])
        rng = self.headers.get('Range')
        if not rng or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        m = re.match(r'bytes=(\d*)-(\d*)', rng)
        start = int(m.group(1) or 0); end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        f = open(path, 'rb'); f.seek(start)
        self.send_response(206)
        self.send_header('Content-Type', self.guess_type(path))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
        self.send_header('Content-Length', str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f
    def copyfile(self, src, dst):
        rem = getattr(self, '_remaining', None)
        if rem is None: return super().copyfile(src, dst)
        while rem > 0:
            chunk = src.read(min(65536, rem))
            if not chunk: break
            try: dst.write(chunk)
            except Exception: break
            rem -= len(chunk)
        self._remaining = None
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8765), functools.partial(Quiet, directory=WEB))
threading.Thread(target=srv.serve_forever, daemon=True).start()

PRE = """
// Test-Hilfen: Shadow DOM offen machen, damit der Test hineinschauen kann; Menü-Befehl abgreifen
const __o = Element.prototype.attachShadow;
Element.prototype.attachShadow = function (i) { const r = __o.call(this, Object.assign({}, i, { mode: 'open' })); window.__evpRoot = r; return r; };
window.GM_registerMenuCommand = (n, f) => { window.__diag = f; };
"""

results = []
def check(name, ok, info=''):
    results.append((name, bool(ok), info))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info else ''))

with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE)
    ctx.add_init_script(SCRIPT)
    page = ctx.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.on('console', lambda m: errors.append('console.' + m.type + ': ' + m.text) if m.type == 'error' else None)

    # ---------- Test 1: normales Video mit Adresse ----------
    page.goto('http://127.0.0.1:8765/direct.html')
    page.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=15000)
    time.sleep(1.0)
    pill_on = page.evaluate('() => { const r = window.__evpRoot; const p = r && r.querySelector(".pill"); return p ? p.className : null; }')
    check('Button erscheint am laufenden Video', pill_on and 'on' in pill_on, pill_on)

    page.evaluate('() => { document.getElementById("v").muted = false; }')   # Seite spielt mit Ton
    hist0 = page.evaluate('() => history.length')
    main = page.locator('button[aria-label="Im Videoplayer öffnen"]')
    cls = page.evaluate('() => window.__evpRoot.querySelector(".pill").className')
    check('Button ist gleich in voller Größe da (kein Aufklappen)', 'mini' not in cls, cls)
    main.click()   # ein Tipp: eingebauter Player
    page.wait_for_function('() => !!(window.__evpRoot && window.__evpRoot.querySelector(".vp"))', timeout=5000)
    check('Eingebauter Player öffnet sich', True)
    page.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && v.currentTime > 0.5 && !v.paused; }', timeout=15000)
    # (nahtloser Start: bis die eigene Kopie aufgeholt hat, läuft die Seite sichtbar weiter – danach still)
    page.wait_for_function('() => !window.__evpRoot.querySelector(".vbridge")', timeout=15000); time.sleep(0.2)
    st = page.evaluate('() => { const v = window.__evpRoot.querySelector(".vv"); const o = document.getElementById("v"); return { src: v.currentSrc, t: v.currentTime, paused: v.paused, origPaused: o.paused, fs: !!document.fullscreenElement }; }')
    check('Spielt eigene Quelle ab, Original pausiert', st['src'].endswith('clip.webm') and st['origPaused'], st)
    check('Startet im Vollbild', st['fs'], st['fs'])
    # Seite versucht ihr Video selbst wieder zu starten (wie Facebook): muss stumm & angehalten bleiben
    page.evaluate('() => document.getElementById("v").play().catch(() => {})'); time.sleep(0.6)
    dbl = page.evaluate('() => { const o = document.getElementById("v"); return { muted: o.muted, paused: o.paused }; }')
    check('Kein doppelter Ton: Seiten-Video bleibt stumm und angehalten', dbl['muted'] and dbl['paused'], dbl)
    hl = page.evaluate('() => history.length')
    check('Im Vollbild kein zusätzlicher Verlaufseintrag (Facebook-sicher)', hl == hist0, (hist0, hl))

    # Doppeltippen rechts = +10 s
    t0 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    page.mouse.click(350, 400); time.sleep(0.08); page.mouse.click(350, 400)
    time.sleep(0.6)
    t1 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    check('Doppeltippen rechts spult ~10 s vor', 8.5 <= (t1 - t0) <= 12.5, round(t1 - t0, 1))

    # Doppeltippen links = −10 s
    page.mouse.click(40, 400); time.sleep(0.08); page.mouse.click(40, 400)
    time.sleep(0.6)
    t2 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    check('Doppeltippen links spult ~10 s zurück', 7.5 <= (t1 - t2) <= 11.5, round(t1 - t2, 1))

    # Waagerecht wischen: ganze Breite = Länge (20–90 s) – hier 60 s, halbe Breite = +30 s
    t3 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    page.mouse.move(100, 500); page.mouse.down(); page.mouse.move(300, 500, steps=8); page.mouse.up()
    time.sleep(0.6)
    t4 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    check('Wischen nach rechts spult ~30 s (halbe Breite bei 60 s Länge)', 26 <= (t4 - t3) <= 34, round(t4 - t3, 1))

    # Senkrecht wischen rechts: Lautstärke runter
    page.mouse.move(330, 300); page.mouse.down(); page.mouse.move(330, 540, steps=8); page.mouse.up()
    vol = page.evaluate('() => window.__evpRoot.querySelector(".vv").volume')
    check('Wischen rechts runter senkt die Lautstärke', vol < 0.7, round(vol, 2))

    # Senkrecht wischen rechts nach oben: über 100 % verstärken
    page.mouse.move(330, 650); page.mouse.down(); page.mouse.move(330, 120, steps=10); page.mouse.up()
    boost = page.evaluate('() => window.__evpRoot.querySelector(".vside b").textContent')
    vv = page.evaluate('() => window.__evpRoot.querySelector(".vv").volume')
    check('Wischen rechts hoch: höchstens 100 %', boost == '100%' and vv == 1, [boost, vv])

    # Senkrecht wischen links nach oben: Bild aufhellen
    page.mouse.move(60, 650); page.mouse.down(); page.mouse.move(60, 300, steps=8); page.mouse.up()
    flt = page.evaluate('() => [window.__evpRoot.querySelector(".vv").style.filter, window.__evpRoot.querySelector(".vside b").textContent]')
    check('Wischen links hoch: Helligkeit höchstens 100 %', 'brightness' not in flt[0] and flt[1] == '100%', flt)

    # Wischen am Rand (Zurück-Geste) wird ignoriert
    tv0 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    page.mouse.move(5, 500); page.mouse.down(); page.mouse.move(200, 500, steps=8); page.mouse.up()
    time.sleep(0.4)
    tv1 = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    check('Wischen vom Rand aus wird ignoriert', abs(tv1 - tv0) < 3, round(tv1 - tv0, 1))

    # Senkrecht wischen links: Helligkeit runter
    page.mouse.move(60, 200); page.mouse.down(); page.mouse.move(60, 780, steps=12); page.mouse.up()
    dim = page.evaluate('() => parseFloat(window.__evpRoot.querySelector(".vdim").style.opacity || "0")')
    check('Wischen links runter dunkelt ab', dim > 0.2, round(dim, 2))

    # Einmal tippen: Leisten ein/aus (vorher das automatische Ausblenden abwarten, damit es nicht mitten hineinfällt)
    time.sleep(3.6)
    c1 = page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")')
    page.mouse.click(200, 650); time.sleep(0.5)
    c2 = page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")')
    check('Einmal tippen schaltet die Leisten um', c1 != c2, (c1, c2))

    # Geschwindigkeit
    if not c2:
        page.mouse.click(200, 650); time.sleep(0.5)
    # im ⋮-Menü: Geschwindigkeit → Liste
    page.locator('.vcluster button[aria-label="Mehr"]').click(); time.sleep(0.4)
    page.evaluate('() => Array.from(window.__evpRoot.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "Geschwindigkeit").click()'); time.sleep(0.4)
    sp = page.evaluate('() => Array.from(window.__evpRoot.querySelectorAll(".vmenu .vmi")).map(b => b.firstChild.textContent)')
    page.evaluate('() => Array.from(window.__evpRoot.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "1,25×").click()'); time.sleep(0.4)
    rate = page.evaluate('() => window.__evpRoot.querySelector(".vv").playbackRate')
    check('Geschwindigkeit im ⋮-Menü (Liste langsam → schnell)', rate == 1.25 and sp[0] == '0,5×' and sp[2] == '1× · Normal', [rate, sp])

    # Drehen-Knopf: Querformat, nochmal = Hochformat – beides im Vollbild, Player bleibt offen
    def show_ctl():
        if not page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")'):
            page.mouse.click(200, 650); time.sleep(0.4)
    page.evaluate('() => { try { const o = screen.orientation; const L = (x) => { window.__lockReq = x; return Promise.resolve(); }; Object.defineProperty(o, "lock", { value: L, configurable: true }); } catch (e) {} }')
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    r1 = page.evaluate('() => [!!document.fullscreenElement, window.__lockReq || ""]')
    check('Drehen-Knopf: Querformat im Vollbild', r1[0] and r1[1] == 'landscape', r1)
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    r2 = page.evaluate('() => [!!document.fullscreenElement, !!window.__evpRoot.querySelector(".vp"), window.__lockReq || ""]')
    check('Nochmal Drehen: Hochformat, bleibt im Vollbild', r2[0] and r2[1] and r2[2] == 'portrait', r2)

    # Wie auf Pornhub: Seite beendet das Vollbild selbst, kurz nachdem du auf Hochformat gedreht hast
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.5)      # -> Querformat
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.2)      # -> Hochformat
    page.evaluate('() => document.exitFullscreen()'); time.sleep(1.2)                     # Seite wirft aus dem Vollbild
    rp = page.evaluate('() => [!!window.__evpRoot.querySelector(".vp"), !!document.fullscreenElement]')
    check('Seite beendet Vollbild nach Drehen: Player bleibt und holt Vollbild zurück', rp[0] and rp[1], rp)
    time.sleep(1.4)   # Schutzfenster abwarten, damit die folgenden Tests normal laufen

    # Dritter Tipp (wieder Querformat) – genau der Fall, bei dem sich der Player früher geschlossen hat
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.9)
    r3 = page.evaluate('() => [!!document.fullscreenElement, !!window.__evpRoot.querySelector(".vp"), window.__lockReq || ""]')
    check('Drittes Drehen: wieder Querformat, Player bleibt offen', r3[0] and r3[1] and r3[2] == 'landscape', r3)
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)   # zurück ins Hochformat für die weiteren Tests

    # Handy gedreht und dabei Vollbild verloren: Player bleibt offen
    # frischer Player ohne Drehen-Sperre (freie Drehung – nur dann kann das Handy selbst drehen)
    page.locator('button[aria-label="Schließen"]').click() if page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")') else (page.mouse.click(200, 650), time.sleep(0.4), page.locator('button[aria-label="Schließen"]').click())
    time.sleep(0.8)
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => !!document.fullscreenElement', timeout=5000); time.sleep(0.5)
    page.set_viewport_size({'width': 800, 'height': 400}); time.sleep(0.05); page.evaluate('() => document.exitFullscreen()'); time.sleep(0.9)
    r4 = page.evaluate('() => !!window.__evpRoot.querySelector(".vp")')
    check('Vollbild durch Drehen verloren: Player bleibt offen', r4, r4)
    page.set_viewport_size({'width': 400, 'height': 800}); time.sleep(1.8)
    # Ein Tipp in den Player holt das Vollbild zurück
    page.mouse.click(200, 400); time.sleep(0.8)
    r6 = page.evaluate('() => !!document.fullscreenElement')
    check('Tipp holt verlorenes Vollbild zurück', r6, r6)
    # Danach Drehen in beide Richtungen: bleibt im Vollbild
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    r7 = page.evaluate('() => [!!document.fullscreenElement, window.__evpRoot.querySelector(".vind").textContent]')
    check('Nach Rückholen: Quer → Hoch bleibt im Vollbild', r7[0], r7)
    # wieder Vollbild-verloren-Zustand herstellen (frischer Player, freie Drehung), damit der Zurück-Test wie vorher läuft
    show_ctl(); page.locator('button[aria-label="Schließen"]').click(); time.sleep(0.8)
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => !!document.fullscreenElement', timeout=5000); time.sleep(0.5)
    page.set_viewport_size({'width': 800, 'height': 400}); time.sleep(0.05); page.evaluate('() => document.exitFullscreen()'); time.sleep(0.9)
    page.set_viewport_size({'width': 400, 'height': 800}); time.sleep(1.8)
    # Zurück-Taste schließt ihn dann über den Verlauf
    page.evaluate('() => history.back()'); time.sleep(0.8)
    r5 = page.evaluate('() => !!window.__evpRoot.querySelector(".vp")')
    check('Danach schließt die Zurück-Taste den Player', not r5, r5)
    # wieder öffnen und im Vollbild starten
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => !!document.fullscreenElement', timeout=5000)

    # Zurück-Taste im QUERformat: Vollbild endet, DANACH springt das Bild ins Hochformat -> Player muss schließen
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    page.set_viewport_size({'width': 800, 'height': 400}); time.sleep(1.8)          # Handy liegt quer (länger her)
    page.evaluate('() => document.exitFullscreen()'); time.sleep(0.25)
    page.set_viewport_size({'width': 400, 'height': 800}); time.sleep(1.0)          # danach zurück ins Hochformat
    r8 = page.evaluate('() => !!window.__evpRoot.querySelector(".vp")')
    check('Zurück im Querformat schließt den Player (nicht nur Vollbild)', not r8, r8)
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => !!document.fullscreenElement', timeout=5000)

    # Wie auf dem echten Handy: Chrome dreht beim Zurück im Querformat ZUERST zurück, meldet DANN das Vollbild-Ende
    show_ctl(); page.locator('button[aria-label="Drehen"]').click(); time.sleep(0.6)
    page.set_viewport_size({'width': 800, 'height': 400}); time.sleep(1.8)
    page.set_viewport_size({'width': 400, 'height': 800}); time.sleep(0.05)           # erst zurückgedreht …
    page.evaluate('() => document.exitFullscreen()'); time.sleep(1.0)                 # … dann Vollbild-Ende
    r9 = page.evaluate('() => !!window.__evpRoot.querySelector(".vp")')
    check('Zurück im Querformat (Chrome-Reihenfolge): ein Druck schließt', not r9, r9)
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => !!document.fullscreenElement', timeout=5000)

    # Vollbild verlassen (= Zurück-Taste auf Android) schließt den Player, Seite bleibt
    url_before = page.url
    hist_before = page.evaluate('() => history.length')
    page.evaluate('() => document.exitFullscreen()'); time.sleep(0.9)
    st_back = page.evaluate('() => ({ open: !!window.__evpRoot.querySelector(".vp"), url: location.href })')
    check('Vollbild verlassen schließt den Player', (not st_back['open']) and st_back['url'] == url_before, st_back)

    # wieder öffnen für den Schließen-Test (erst Video auf der Seite wieder starten – Button gibt es nur bei laufendem Video)
    page.evaluate('() => document.getElementById("v").play()'); time.sleep(1.2)
    main.click()
    page.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && v.currentTime > 0.5; }', timeout=15000)
    page.evaluate('() => { window.__evpRoot.querySelector(".vv").currentTime = 50.8; }'); time.sleep(0.6)
    if not page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")'):
        page.mouse.click(200, 650); time.sleep(0.4)

    # Schließen: Stelle wird ans Original übergeben, Button kommt zurück
    tv = page.evaluate('() => window.__evpRoot.querySelector(".vv").currentTime')
    page.locator('button[aria-label="Schließen"]').click()
    time.sleep(0.5)
    st2 = page.evaluate('() => ({ open: !!window.__evpRoot.querySelector(".vp"), ot: document.getElementById("v").currentTime })')
    check('Schließen entfernt den Player', not st2['open'], st2)
    check('Original übernimmt die Stelle', abs(st2['ot'] - tv) < 2.5, (round(tv, 1), round(st2['ot'], 1)))
    time.sleep(0.6)
    op = page.evaluate('() => { const o = document.getElementById("v"); return { paused: o.paused, t: o.currentTime }; }')
    check('Original läuft nach dem Schließen weiter', not op['paused'], op)
    mu = page.evaluate('() => document.getElementById("v").muted')
    check('Seiten-Video bekommt seinen Ton zurück', mu is False, mu)

    # Diagnose über das Menü
    page.evaluate('() => window.__diag && window.__diag()')
    time.sleep(0.3)
    diag_ok = page.evaluate('() => { const d = window.__evpRoot.querySelector(".diag textarea"); return d ? d.value.slice(0, 60) : null; }')
    check('Diagnose-Fenster über das Menü', bool(diag_ok), diag_ok)

    # ---------- Test 2: Video ohne Adresse (nur Stream im Browser) -> Bild vom Original ----------
    page2 = ctx.new_page()
    page2.on('pageerror', lambda e: errors.append('page2: ' + str(e)))
    page2.goto('http://127.0.0.1:8765/capture.html')
    page2.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    time.sleep(1.0)
    m2 = page2.locator('button[aria-label="Im Videoplayer öffnen"]')
    m2.click()
    page2.wait_for_function('() => !!(window.__evpRoot && window.__evpRoot.querySelector(".vp"))', timeout=5000)
    time.sleep(1.5)
    st3 = page2.evaluate('() => { const v = window.__evpRoot.querySelector(".vv"); return { hasStream: !!v.srcObject, playing: !v.paused, w: v.videoWidth }; }')
    check('Ohne Adresse: übernimmt das Bild vom Original', st3['hasStream'] and st3['playing'] and st3['w'] > 0, st3)
    page2.locator('button[aria-label="Schließen"]').click() if page2.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")') else page2.mouse.click(200, 650)
    time.sleep(0.3)


    # ---------- Test 3: HLS-Stream wie auf Pornhub (Seite spielt per hls.js, Adresse kommt aus dem Netzwerk) ----------
    ctx3 = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True)
    ctx3.add_init_script(PRE)
    ctx3.add_init_script(open(WEB + '/hls.min.js', encoding='utf-8').read())   # entspricht @require hls.js
    ctx3.add_init_script(SCRIPT)
    page3 = ctx3.new_page()
    page3.on('pageerror', lambda e: errors.append('page3: ' + str(e)))
    page3.goto('http://127.0.0.1:8765/hls.html')
    page3.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    time.sleep(1.0)
    m3 = page3.locator('button[aria-label="Im Videoplayer öffnen"]')
    m3.click()
    page3.wait_for_function('() => !!(window.__evpRoot && window.__evpRoot.querySelector(".vp"))', timeout=5000)
    try:
        page3.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && !v.srcObject && v.currentTime > 1 && !v.paused; }', timeout=15000)
        ok3 = True
    except Exception:
        ok3 = False
    st4 = page3.evaluate('() => { const v = window.__evpRoot.querySelector(".vv"); return { src: v.currentSrc.slice(0, 30), capture: !!v.srcObject, t: v.currentTime, w: v.videoWidth }; }')
    check('HLS-Stream: eigener Player lädt den Stream selbst (hls.js)', ok3 and st4['w'] > 0, st4)
    page3.evaluate('() => { window.__evpRoot.querySelector(".vv").currentTime = 45; }'); time.sleep(0.8)   # weit in nicht geladenen Bereich
    page3.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.9)
    if page3.evaluate('() => !!window.__evpRoot.querySelector(".vp")'):
        page3.evaluate('() => history.back()')
    time.sleep(0.6)
    t_a = page3.evaluate('() => document.getElementById("v").currentTime'); time.sleep(1.5)
    t_b = page3.evaluate('() => document.getElementById("v").currentTime')
    pz = page3.evaluate('() => document.getElementById("v").paused')
    check('Seite mit Stream: Video läuft nach dem Schließen weiter (hängt nicht)', (not pz) and t_b > t_a + 0.8, (pz, round(t_a, 1), round(t_b, 1)))
    check('Seite mit Stream: macht an DEINER Stelle weiter (~45 s)', 44 <= t_a <= 50, round(t_a, 1))

    # ---------- Test 4: Player in einem eingebetteten Fenster OHNE Adresse (about:blank, von der Seite befüllt) ----------
    page4 = ctx.new_page()
    page4.on('pageerror', lambda e: errors.append('page4: ' + str(e)))
    page4.goto('http://127.0.0.1:8765/blankframe.html')
    page4.wait_for_function('() => { const f = document.getElementById("f"); const v = f.contentDocument && f.contentDocument.getElementById("iv"); return v && v.currentTime > 1.5; }', timeout=15000)
    time.sleep(2.0)
    pill4 = page4.evaluate('() => { const r = window.__evpRoot; const p = r && r.querySelector(".pill"); return p ? [p.className, p.style.top] : null; }')
    check('Fenster ohne Adresse: Button erscheint', pill4 and 'on' in pill4[0], pill4)
    top_px = float(pill4[1].replace('px', '')) if pill4 and pill4[1] else -1
    check('Button sitzt am Video im Fenster (nicht oben an der Seite)', 80 <= top_px <= 330, top_px)
    m4 = page4.locator('button[aria-label="Im Videoplayer öffnen"]')
    m4.click()
    page4.wait_for_function('() => { const v = window.__evpRoot && window.__evpRoot.querySelector(".vv"); return v && v.currentTime > 0.5 && !v.paused; }', timeout=15000)
    check('Fenster ohne Adresse: eigener Player spielt', True)

    b.close()

srv.shutdown()
print()
print('Fehler/Meldungen der Seite:', json.dumps([e for e in errors if 'favicon' not in e][:10], ensure_ascii=False, indent=1))
failed = [r for r in results if not r[1]]
print('ERGEBNIS:', len(results) - len(failed), 'von', len(results), 'Tests bestanden')
sys.exit(1 if failed else 0)
