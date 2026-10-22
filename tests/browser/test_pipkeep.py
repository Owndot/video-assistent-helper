import pwshim  # BROWSER=firefox → Tests in Firefox
# Bild-in-Bild beim Wechsel zum Startbildschirm (18.5): Hält etwas anderes als du das Video rund um den Wechsel ins
# kleine Fenster an (Seite verdeckt, Browser, „Beim Verlassen: Anhalten“), läuft es weiter.
# Gegenprobe: eigene Pause (auch im kleinen Fenster), Sleep-Timer und ✕ im kleinen Fenster halten an.
import time, os
src = open('test_extras.py').read().split("with sync_playwright() as p:")[0]
exec(src)
if os.environ.get('EVP_SCRIPT'): SCRIPT = open(os.environ['EVP_SCRIPT'], encoding='utf-8').read()   # Vergleich mit einer anderen Fassung
SIM = '''() => { if (window.__sim) return; window.__sim = { hidden: false, focus: true };
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__sim.hidden });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => window.__sim.hidden ? 'hidden' : 'visible' });
  document.hasFocus = () => window.__sim.focus; }'''
def hide(pg, h):
    pg.evaluate('(h) => { window.__sim.hidden = h; window.__sim.focus = !h; if (h) window.dispatchEvent(new Event("blur")); document.dispatchEvent(new Event("visibilitychange")); }', h)
# kleines Fenster nachstellen: pictureInPictureElement + Ereignisse am Player-Video
PIP_ON = '() => { const r = %s, v = r.querySelector(".vv"); Object.defineProperty(r, "pictureInPictureElement", { configurable: true, get: () => window.__pip ? v : null }); window.__pip = true; v.dispatchEvent(new Event("enterpictureinpicture")); }' % R
PIP_OFF = '() => { window.__pip = false; %s.querySelector(".vv").dispatchEvent(new Event("leavepictureinpicture")); }' % R
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    if os.environ.get('EVLOG'): pg.on('console', lambda m: print('   ', m.text) if m.text.startswith('EVLOG') else None)
    def paused(): return pg.evaluate('() => %s.querySelector(".vv").paused' % R)
    def play(): pg.evaluate('() => %s.querySelector(".vv").play()' % R); time.sleep(0.4)
    open_player(pg, 'subs.html'); time.sleep(0.5); pg.evaluate(SIM)
    # 1) zum Startbildschirm: Seite verdeckt → Browser/Seite hält an → kleines Fenster geht auf
    play()
    hide(pg, True)
    pg.evaluate('() => %s.querySelector(".vv").pause()' % R)   # nicht du: der Browser bzw. die Seite
    time.sleep(0.2); pg.evaluate(PIP_ON); time.sleep(0.8)
    check('Startbildschirm: angehalten kurz VOR dem kleinen Fenster → läuft darin weiter', not paused())
    # 2) im kleinen Fenster hält die Seite/der Browser an → läuft weiter
    pg.evaluate('() => %s.querySelector(".vv").pause()' % R); time.sleep(0.6)
    check('… im kleinen Fenster von außen angehalten → läuft weiter', not paused())
    # 3) DU hältst an (Play/Pause) → bleibt angehalten
    pg.evaluate('() => %s.querySelector(".vplay").click()' % R); time.sleep(0.8)
    check('… eigene Pause im kleinen Fenster bleibt', paused())
    play(); time.sleep(0.3)
    # 4) ✕ im kleinen Fenster (Seite verdeckt) → angehalten
    pg.evaluate(PIP_OFF); time.sleep(0.8)
    check('✕ im kleinen Fenster: angehalten', paused())
    hide(pg, False); time.sleep(0.5)
    # 5) Einstellung „Beim Verlassen: Anhalten“: verdeckt hält an – geht das kleine Fenster auf, läuft es weiter
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    play()
    hide(pg, True); time.sleep(0.4)
    p0 = paused()
    pg.evaluate(PIP_ON); time.sleep(0.8)
    check('„Anhalten“-Einstellung: verdeckt angehalten, im kleinen Fenster läuft es weiter', p0 and not paused(), [p0, paused()])
    pg.evaluate(PIP_OFF); time.sleep(0.5); hide(pg, False); time.sleep(0.4)
    # 6) ohne kleines Fenster bleibt „Anhalten“ wie gehabt
    play(); hide(pg, True); time.sleep(1.2)
    check('… ohne kleines Fenster: bleibt angehalten', paused())
    hide(pg, False); time.sleep(0.4)
    # 6b) Browser nimmt das Video der SEITE ins kleine Fenster (nachdem das Vollbild-Ende erst wie „Zurück“ aussah und die
    #     Seite übernommen hat): es läuft dort weiter, der Player schließt sich still (18.6)
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)   # zurück auf „Weiter“
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    nopip = pg.evaluate('() => document.getElementById("v").disablePictureInPicture')
    check('eigener Player: Seitenvideo darf nicht ins kleine Fenster', nopip is True, nopip)
    if not pg.evaluate('() => !!document.fullscreenElement'): pg.mouse.click(200, 300); time.sleep(0.6)
    play()
    pg.evaluate('() => document.exitFullscreen().catch(() => {})'); time.sleep(0.12)
    pg.evaluate('''() => { const o = document.getElementById("v"); window.__opip = true;
      Object.defineProperty(document, "pictureInPictureElement", { configurable: true, get: () => window.__opip ? o : null }); o.dispatchEvent(new Event("enterpictureinpicture")); }''')
    hide(pg, True); time.sleep(1.4)
    st6 = pg.evaluate('() => { const o = document.getElementById("v"); return [!!%s.querySelector(".vp"), o.paused, o.muted]; }' % R)
    check('Seitenvideo im kleinen Fenster: läuft dort weiter (mit Ton), Player zu', not st6[0] and not st6[1] and not st6[2], st6)
    pg.evaluate('() => { window.__opip = false; }'); hide(pg, False); time.sleep(0.4)
    # 7) Facebook (gespiegelt): die Seite hält IHR Video an, sobald sie verdeckt ist → im kleinen Fenster weiter
    CLIP = open(WEB + '/clip.webm', 'rb').read()
    FBP = '<!doctype html><html><body style="margin:0"><video id=v muted autoplay playsinline src="/data/c.webm" style="width:100%;display:block"></video><script>document.addEventListener("visibilitychange", () => { if (document.hidden) document.getElementById("v").pause(); });</script></body></html>'
    def fb(route):
        u = route.request.url
        if '/data/' in u:
            rg = route.request.headers.get('range', ''); n = len(CLIP)
            if rg.startswith('bytes='):
                a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
                route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
            else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
        else: route.fulfill(status=200, content_type='text/html', body=FBP)
    ctx.route('https://www.facebook.com/**', fb)
    pg.goto('https://www.facebook.com/reel/111222333444555/')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.srcObject; }' % R, timeout=15000); time.sleep(0.8)
    pg.evaluate(SIM)
    hide(pg, True); time.sleep(0.15); pg.evaluate(PIP_ON); time.sleep(0.9)
    o = pg.evaluate('() => document.getElementById("v").paused')
    check('Facebook (gespiegelt): Seite hält ihr Video beim Verdecken an → läuft im kleinen Fenster weiter', not o, o)
    pg.evaluate(PIP_OFF); time.sleep(0.6)
    o2 = pg.evaluate('() => document.getElementById("v").paused')
    check('… ✕ im kleinen Fenster: angehalten', o2, o2)
    hide(pg, False)
    # 8) Facebook ohne kleines Fenster, „Weiter“: Facebook erfährt nichts vom Verdecken → sein Video läuft weiter
    time.sleep(1.2)   # (das ✕ eben gilt 1,5 s lang als „kleines Fenster geschlossen“)
    pg.evaluate('() => document.getElementById("v").play()'); time.sleep(0.4)
    hide(pg, True); time.sleep(1.0)
    o3 = pg.evaluate('() => document.getElementById("v").paused')
    check('Facebook, „Weiter“: Seite bekommt „verdeckt“ nicht mit, Video läuft weiter (auch ohne kleines Fenster)', not o3, o3)
    hide(pg, False); time.sleep(0.4)
    # 9) Gegenprobe „Anhalten“: Seite bekommt die Meldung wie gewohnt
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)
    pg.evaluate('() => { %s.querySelector(".vmenu").classList.remove("on"); %s.querySelector(".vp").classList.remove("menu"); }' % (R, R))
    pg.evaluate('() => document.getElementById("v").play()'); time.sleep(0.4)
    hide(pg, True); time.sleep(1.0)
    o4 = pg.evaluate('() => document.getElementById("v").paused')
    check('… „Anhalten“: Seite bekommt die Meldung, Video hält an', o4, o4)
    hide(pg, False); time.sleep(0.3)
    more(pg); pick(pg, 'Einstellungen'); pick(pg, 'Beim Verlassen'); time.sleep(0.2)   # zurück auf „Weiter“
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
