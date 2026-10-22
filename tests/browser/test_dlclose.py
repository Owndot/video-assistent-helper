import pwshim  # BROWSER=firefox → Tests in Firefox
# Download an YTDLnis (18.7): Der Browser verliert beim Wechsel zu YTDLnis das Vollbild. Darum hält der Player an,
# und kommt man zurück, ist er zu – das Seitenvideo steht angehalten an der Stelle. Teilen-Menü abgebrochen: bleibt offen.
import time, os
src = open('test_v8.py').read().split("results = []")[0]
if os.environ.get('EVP_SCRIPT'): src = src.replace("open('../../userscript/video-assistent.user.js'", "open(os.environ['EVP_SCRIPT']")
exec(src)
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
SHIM = """window.__intents = [];
const __ac = HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click = function () { if (/^intent:/.test(this.href)) { window.__intents.push(this.href); return; } return __ac.call(this); };"""
SIM = '''() => { if (window.__sim) return; window.__sim = { hidden: false };
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__sim.hidden });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => window.__sim.hidden ? 'hidden' : 'visible' }); }'''
def hide(pg, h): pg.evaluate('(h) => { window.__sim.hidden = h; if (h) window.dispatchEvent(new Event("blur")); document.dispatchEvent(new Event("visibilitychange")); }', h)
XV = '<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Testclip XV</title></head><body style="margin:0"><video id=v src="/clip.webm" autoplay playsinline loop style="width:100%"></video><p style="height:1200px">x</p></body></html>'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(SHIM); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    CLIP = open(WEB + '/clip.webm', 'rb').read()
    def serve(route):
        if not route.request.url.endswith('.webm'): route.fulfill(status=200, content_type='text/html', body=XV); return
        rg = route.request.headers.get('range', ''); n = len(CLIP)
        if rg.startswith('bytes='):
            a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
            route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
        else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
    ctx.route('https://www.xvideos.com/**', serve)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    def openp():
        pg.goto('https://www.xvideos.com/video.abc123/test_clip')
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.5; }' % R, timeout=20000)
        time.sleep(0.5); pg.evaluate(SIM)
    # 1) Download → YTDLnis geht auf (Seite verdeckt) → zurück: Player zu, Seite angehalten an der Stelle
    openp()
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = 7; }' % R); time.sleep(0.6)
    pg.evaluate('() => { const r = %s; r.querySelector(".vp").classList.add("ctl"); r.querySelector("button[aria-label^=Herunterladen]").click(); }' % R); time.sleep(0.2)
    it = pg.evaluate('() => window.__intents')
    hide(pg, True); time.sleep(0.6)
    ps = pg.evaluate('() => %s.querySelector(".vv").paused' % R)
    check('Download an YTDLnis: Intent abgeschickt, Player hält an, solange YTDLnis vorne ist', len(it) == 1 and 'ytdlnis' in it[0].lower() and ps, [it, ps])
    hide(pg, False); time.sleep(0.9)
    st = pg.evaluate('() => { const o = document.getElementById("v"); return [!!%s.querySelector(".vp"), o.paused, Math.round(o.currentTime)]; }' % R)
    check('… zurück im Browser: Player zu, Seitenvideo angehalten an deiner Stelle', not st[0] and st[1] and 7 <= st[2] <= 9, st)
    # 2) Teilen-Menü, abgebrochen → Player bleibt offen
    pg.evaluate('''() => { Object.defineProperty(navigator, "share", { configurable: true, value: () => new Promise((res, rej) => { window.__rej = () => rej(new DOMException("abgebrochen", "AbortError")); }) }); }''')
    openp()
    pg.evaluate('() => { %s.querySelector(".vp").classList.add("ctl"); }' % R)
    pg.evaluate('() => %s.querySelector("button[aria-label^=Herunterladen]").dispatchEvent(new MouseEvent("contextmenu", { bubbles: true }))' % R); time.sleep(0.4)
    ok = pg.evaluate('() => { const b = Array.from(%s.querySelectorAll(".vmenu.on .vmi")).find(b => b.firstChild.textContent.startsWith("Teilen")); if (b) b.click(); return !!b; }' % R)
    hide(pg, True); time.sleep(0.4)
    pg.evaluate('() => window.__rej && window.__rej()'); hide(pg, False); time.sleep(0.9)
    still = pg.evaluate('() => !!%s.querySelector(".vp")' % R)
    check('Teilen-Menü abgebrochen: Player bleibt offen', ok and still, [ok, still])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
