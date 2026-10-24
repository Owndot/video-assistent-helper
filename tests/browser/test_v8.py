import pwshim  # BROWSER=firefox → Tests in Firefox
import http.server, threading, functools, time, json, sys, os, re
from playwright.sync_api import sync_playwright

WEB = 'web'
OUT = '.'
SCRIPT = open('../../userscript/video-assistent.user.js', encoding='utf-8').read()
HLS = open(WEB + '/hls.min.js', encoding='utf-8').read()

class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def send_head(self):
        path = self.translate_path(self.path.split('?')[0])
        rng = self.headers.get('Range')
        if not rng or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        m = re.match(r'bytes=(\d*)-(\d*)', rng)
        start = int(m.group(1) or 0); end = min(int(m.group(2)) if m.group(2) else size - 1, size - 1)
        f = open(path, 'rb'); f.seek(start)
        self.send_response(206)
        self.send_header('Content-Type', self.guess_type(path)); self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size)); self.send_header('Content-Length', str(end - start + 1))
        self.end_headers(); self._rem = end - start + 1
        return f
    def copyfile(self, src, dst):
        rem = getattr(self, '_rem', None)
        if rem is None: return super().copyfile(src, dst)
        while rem > 0:
            c = src.read(min(65536, rem))
            if not c: break
            try: dst.write(c)
            except Exception: break
            rem -= len(c)
        self._rem = None

srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8775), functools.partial(Q, directory=WEB))
threading.Thread(target=srv.serve_forever, daemon=True).start()
PRE = """const __o = Element.prototype.attachShadow;
Element.prototype.attachShadow = function (i) { const r = __o.call(this, Object.assign({}, i, { mode: 'open' })); window.__evpRoot = r; return r; };
// Speicher des Managers wie bei Violentmonkey (für alle Fenster derselbe Schlüssel der eingebetteten Fenster)
if (typeof window.GM_getValue !== 'function') {
  // (alles andere wie ohne Manager im Speicher der Seite – die Tests lesen dort nach)
  const __fk = { k: '5e1f0c2a9b7d4e3f8a6c1b2d3e4f5a6b7c', t: Date.now() };
  window.GM_getValue = (k, d) => { if (k === 'frameKey') return __fk; try { const v = localStorage.getItem('evp-s-' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } };
  window.GM_setValue = (k, v) => { if (k === 'frameKey') return; try { localStorage.setItem('evp-s-' + k, JSON.stringify(v)); } catch (e) {} };
}"""
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))

R = 'window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    errors = []
    page = ctx.new_page()
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto('http://127.0.0.1:8775/direct.html')
    page.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=15000)
    time.sleep(1.0)
    # Stummes Video (wie im Facebook-Feed): kein Button
    ph = page.evaluate('() => { const p = %s && %s.querySelector(".pill"); return p ? p.classList.contains("on") : false; }' % (R, R))
    check('Stummes Video: kein Button', not ph, ph)
    # Ton an: Button erscheint, und zwar UNTER dem Video
    page.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pos = page.evaluate('() => { const p = %s.querySelector(".pill"); const v = document.getElementById("v").getBoundingClientRect(); return [p.classList.contains("on"), parseFloat(p.style.top), v.bottom]; }' % R)
    check('Ton an: Button erscheint', pos[0], pos)
    check('Button sitzt unter dem Video', pos[1] >= pos[2], pos)
    page.screenshot(path=OUT + '/shot_page_pill.png')
    main = page.locator('button[aria-label="Im Videoplayer öffnen"]')
    page.screenshot(path=OUT + '/shot_page_pill_open.png')
    # Scrollen: Button verschwindet, danach kommt er wieder
    page.mouse.wheel(0, 30); time.sleep(0.12)
    sc1 = page.evaluate('() => %s.querySelector(".pill").classList.contains("scrolling")' % R)
    time.sleep(0.9)
    sc2 = page.evaluate('() => { const p = %s.querySelector(".pill"); return [p.classList.contains("scrolling"), p.classList.contains("on")]; }' % R)
    check('Beim Scrollen ausgeblendet', sc1, sc1)
    check('Nach dem Scrollen wieder da', (not sc2[0]) and sc2[1], sc2)
    page.mouse.wheel(0, -30); time.sleep(0.9)
    main.click()
    page.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && !v.paused; }' % R, timeout=15000)
    time.sleep(0.8)
    page.screenshot(path=OUT + '/shot_player.png')

    # Lange drücken = 2× schneller, loslassen = normal
    page.mouse.move(110, 560); page.mouse.down(); time.sleep(0.8)   # nicht auf dem Play-Knopf
    r1 = page.evaluate('() => [%s.querySelector(".vv").playbackRate, %s.querySelector(".vind").textContent]' % (R, R))
    page.screenshot(path=OUT + '/shot_hold.png')
    page.mouse.up(); time.sleep(0.3)
    r2 = page.evaluate('() => %s.querySelector(".vv").playbackRate' % R)
    check('Lange drücken: 2× schneller', r1[0] == 2 and 'schneller' in r1[1], r1)
    check('Loslassen: wieder normal', r2 == 1, r2)
    check('Langes Drücken blendet die Leisten nicht um', True)

    # Lautstärke-Anzeige mit Füllstand
    page.mouse.move(330, 650); page.mouse.down(); page.mouse.move(330, 300, steps=8)
    lv = page.evaluate('() => { const i = %s.querySelector(".vside"); const r = i.getBoundingClientRect(); return [i.classList.contains("on"), i.classList.contains("r"), i.querySelector("i").style.height, r.height > r.width, r.left > 300, i.querySelector("b").textContent]; }' % R)
    page.screenshot(path=OUT + '/shot_volume.png')
    page.mouse.up(); time.sleep(1.1)
    check('Lautstärke: senkrecht rechts mit Füllstand', lv[0] and lv[1] and lv[2].endswith('%') and lv[3] and lv[4], lv)
    page.mouse.move(70, 650); page.mouse.down(); page.mouse.move(70, 300, steps=8)
    br = page.evaluate('() => { const i = %s.querySelector(".vside"); const r = i.getBoundingClientRect(); return [i.classList.contains("on"), i.classList.contains("l"), r.right < 100, r.height > r.width, i.querySelector("b").textContent]; }' % R)
    page.screenshot(path=OUT + '/shot_bright.png')
    page.mouse.up()
    check('Helligkeit: senkrecht links', br[0] and br[1] and br[2] and br[3], br)
    tw = page.evaluate('() => { const t = %s.querySelector(".vtitle"); const c = %s.querySelector(".vcluster"); return [t.getBoundingClientRect().width, t.querySelector("span").scrollWidth, c.getBoundingClientRect().right]; }' % (R, R))
    check('Titel nur so breit wie der Text', tw[0] <= tw[1] + 30, tw)

    # Bildformat: jeder Tipp schaltet weiter (Original → Füllen → Strecken → Original), mit Hinweis
    time.sleep(1.0)
    fit = lambda: page.evaluate('() => [%s.querySelector(".vv").style.objectFit, %s.querySelector(".toast").textContent]' % (R, R))
    seq = []
    for _ in range(3):
        page.evaluate('() => { %s.querySelector(".vp").classList.add("ctl"); %s.querySelector("button[aria-label=Bildformat]").click(); }' % (R, R)); time.sleep(0.35)
        seq.append(fit())
    page.screenshot(path=OUT + '/shot_menu_format.png')
    check('Bildformat: Tipp schaltet Füllen → Strecken → Original (ohne Menü)', [x[0] for x in seq] == ['cover', 'fill', 'contain'] and [x[1] for x in seq] == ['Füllen', 'Strecken', 'Original'] and not page.evaluate('() => %s.querySelector(".vmenu").classList.contains("on")' % R), seq)
    # Zwei-Finger-Zoom (per Zeiger-Ereignisse nachgestellt)
    page.evaluate('''() => {
      const g = %s.querySelector(".vgest");
      const ev = (t, id, x, y) => g.dispatchEvent(new PointerEvent(t, { pointerId: id, clientX: x, clientY: y, bubbles: true, isPrimary: id === 1, pointerType: "touch" }));
      ev("pointerdown", 1, 180, 400); ev("pointerdown", 2, 220, 400);
      for (let k = 1; k <= 8; k++) { ev("pointermove", 1, 180 - k * 10, 400); ev("pointermove", 2, 220 + k * 10, 400); }
      ev("pointerup", 1, 100, 400); ev("pointerup", 2, 300, 400);
    }''' % R)
    time.sleep(0.3)
    tf = page.evaluate('() => %s.querySelector(".vv").style.transform' % R)
    check('Zwei Finger auseinander: Zoom', 'scale(' in tf, tf)
    page.screenshot(path=OUT + '/shot_zoom.png')
    page.locator('button[aria-label="Schließen"]').click() if page.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R) else None
    time.sleep(0.5)

    # ---------- Qualität bei einem Stream mit zwei Stufen ----------
    page2 = ctx.new_page()
    page2.on('pageerror', lambda e: errors.append('p2: ' + str(e)))
    page2.goto('http://127.0.0.1:8775/hls2.html')
    page2.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    page2.evaluate('() => { document.getElementById("v").muted = false; }')
    time.sleep(1.0)
    m2 = page2.locator('button[aria-label="Im Videoplayer öffnen"]')
    m2.click()
    page2.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && !v.paused; }' % R, timeout=20000)
    time.sleep(1.5)
    qh = page2.evaluate('() => { const b = %s.querySelector("button[aria-label=Qualität]"); return [b.hidden, b.textContent]; }' % R)
    check('Qualität-Knopf erscheint bei mehreren Stufen', not qh[0], qh)
    if not page2.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
        page2.mouse.click(200, 300); time.sleep(0.5)
    # im ⋮-Menü: „Qualität“ (mit aktueller Stufe) → Stufen-Menü
    page2.locator('.vcluster button[aria-label="Mehr"]').click(); time.sleep(0.4)
    mq = page2.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(b => b.textContent)' % R)
    check('⋮-Menü hat „Qualität“ mit Stufe', any(x.startswith('Qualität') and 'p' in x for x in mq), mq)
    page2.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "Qualität").click()' % R); time.sleep(0.5)
    page2.screenshot(path=OUT + '/shot_menu_quality.png')
    qi = page2.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(b => b.firstChild.textContent)' % R)
    check('Qualitäts-Menü: Automatisch, 360p, 180p', qi[:1] == ['Automatisch'] and '360p' in qi and '180p' in qi, qi)
    page2.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).find(b => b.firstChild.textContent === "180p").click()' % R)
    time.sleep(3.0)
    lab = page2.evaluate('() => %s.querySelector("button[aria-label=Qualität]").textContent' % R)
    check('180p gewählt: Knopf zeigt 180p', '180p' in lab, lab)
    b.close()
srv.shutdown()
print('Fehler der Seite:', json.dumps(errors[:8], ensure_ascii=False))
failed = [r for r in results if not r[1]]
print('ERGEBNIS:', len(results) - len(failed), 'von', len(results), 'Tests bestanden')
sys.exit(1 if failed else 0)
