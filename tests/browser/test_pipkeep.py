import pwshim  # BROWSER=firefox → Tests in Firefox
# Bild-in-Bild beim Wechsel zum Startbildschirm (18.5): Hält etwas anderes als du das Video rund um den Wechsel ins
# kleine Fenster an (Seite verdeckt, Browser, „Beim Verlassen: Anhalten“), läuft es weiter.
# Gegenprobe: eigene Pause (auch im kleinen Fenster), Sleep-Timer und ✕ im kleinen Fenster halten an.
# Beim Spiegeln (Facebook, Instagram) gibt es kein Bild-in-Bild (18.6) – dort hält die Seite ihr Video im Hintergrund an.
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
    pp = pg.evaluate('() => { const r = %s, b = r.querySelector("button[aria-label=\'Bild-in-Bild\']"); return [b ? getComputedStyle(b).display : "fehlt", r.querySelector(".vv").disablePictureInPicture, document.getElementById("v").disablePictureInPicture]; }' % R)
    check('Facebook (gespiegelt): kein Bild-in-Bild – kein Knopf, weder Player noch Seitenvideo ins kleine Fenster (18.6)', pp[0] in ('none', 'fehlt') and pp[1] is True and pp[2] is True, pp)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.8)
    po = pg.evaluate('() => document.getElementById("v").disablePictureInPicture')
    check('… nach dem Schließen: Seitenvideo wieder wie vorher', po is False, po)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
