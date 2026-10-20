import pwshim  # BROWSER=firefox → Tests in Firefox
exec(open('test_v8.py').read().split("results = []")[0])
import urllib.parse
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
SHIM = """window.GM_xmlhttpRequest = function (o) {
  if (!/^http:\\/\\/127\\.0\\.0\\.1:47811\\//.test(o.url)) { setTimeout(() => o.onerror && o.onerror({}), 0); return; }
  window.__helperReq(o.url, (o.headers || {})['X-EVP-Key'] || '').then(
    (t) => t === null ? (o.onerror && o.onerror({})) : o.onload({ status: 200, responseText: t }),
    () => o.onerror && o.onerror({}));
};
window.__intents = [];
const __ac = HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click = function () { if (/^intent:/.test(this.href)) { window.__intents.push(this.href); return; } return __ac.call(this); };"""
state = {'on': True, 'calls': []}
def helper_req(url, key):
    if not state['on'] or key != 'evp-1': return None
    u = urllib.parse.urlparse(url); q = dict(urllib.parse.parse_qsl(u.query))
    state['calls'].append((u.path, q))
    if u.path == '/download': return json.dumps({'ok': True, 'id': 101})
    return json.dumps({'ok': True, 'dl': True, 'proxy': True, 'dash': True, 'overlay': True, 'vol': 0.5, 'volMax': 15, 'bright': 0.5})
def calls(path): return [c[1] for c in state['calls'] if c[0] == path]
XV = '<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Testclip XV</title></head><body style="margin:0"><video id=v src="/clip.webm" autoplay playsinline loop style="width:100%"></video><p style="height:1200px">x</p></body></html>'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.expose_function('__helperReq', helper_req)
    ctx.add_init_script(SHIM); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    CLIP = open(WEB + '/clip.webm', 'rb').read()
    ctx.route('https://www.xvideos.com/**', lambda r: r.fulfill(status=200, content_type='video/webm', body=CLIP) if r.request.url.endswith('.webm') else r.fulfill(status=200, content_type='text/html', body=XV))
    errors = []
    page = ctx.new_page(); page.on('pageerror', lambda e: errors.append(str(e)))
    def openp(url, vid='v', ready='document.getElementById("v").currentTime > 1'):
        page.goto(url)
        page.wait_for_function(ready, timeout=20000)
        page.evaluate('() => { const v = document.querySelector("video"); v.muted = false; }'); page.wait_for_timeout(900)
        page.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        page.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=20000)
        page.wait_for_timeout(800)
    def showCtl():
        if not page.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
            page.mouse.click(200, 300); page.wait_for_timeout(350)
    def tapDl():
        showCtl()
        vis = page.evaluate('() => { const b = %s.querySelector(".vp .vb[aria-label^=Herunterladen]"); return b && !b.hidden; }' % R)
        if vis: page.locator('.vp .vb[aria-label^="Herunterladen"]').click(); page.wait_for_timeout(700)
        return vis
    # 1. Videodatei + Helfer: Helfer bekommt Datei mit Referer und Titel
    openp('http://127.0.0.1:8775/direct.html')
    vis = tapDl(); d = calls('/download')
    check('Datei + Helfer: Knopf da, Helfer bekommt die Datei', vis and d and d[-1]['k'] == 'file' and d[-1]['u'].endswith('clip.webm'), d[-1:] if d else vis)
    check('… mit Seite als Referer und Titel', d and d[-1]['ref'] == 'http://127.0.0.1:8775/direct.html' and d[-1]['t'] == 'Testvideo direkt' and 'Mozilla' in d[-1]['ua'], d[-1:] if d else '')
    # 2. Langes Drücken: alle Wege
    showCtl()
    page.locator('.vp .vb[aria-label^="Herunterladen"]').dispatch_event('contextmenu'); page.wait_for_timeout(500)
    items = page.evaluate('() => [...%s.querySelectorAll(".vmenu.on .vmi")].map(e => e.textContent)' % R)
    check('Lange drücken: Menü mit allen Wegen', any('Mit dem Helfer' in x for x in items) and any('Im Browser speichern' in x for x in items) and any('Teilen' in x for x in items), items)
    page.screenshot(path=OUT + '/dl_menu.png')
    # 3. HLS-Stream + Helfer: k=hls mit der m3u8
    state['calls'] = []
    openp('http://127.0.0.1:8775/hls.html')
    vis = tapDl(); d = calls('/download')
    check('Stream + Helfer: Helfer bekommt die m3u8', vis and d and d[-1]['k'] == 'hls' and '.m3u8' in d[-1]['u'], d[-1:] if d else vis)
    # 4. HLS ohne Helfer: kein Download-Knopf (nichts, was klappen würde)
    state['on'] = False; state['calls'] = []
    openp('http://127.0.0.1:8775/hls.html')
    vis = tapDl()
    check('Stream ohne Helfer: kein Download-Knopf', not vis, vis)
    # 5. yt-dlp-Seite: direkt an YTDLnis (ohne Teilen-Menü)
    openp('https://www.xvideos.com/video12345/test_clip')
    tapDl()
    it = page.evaluate('() => window.__intents')
    check('yt-dlp-Seite: direkt an YTDLnis', it and 'package=com.deniscerri.ytdl' in it[-1] and it[-1].startswith('intent://www.xvideos.com/video12345/test_clip#Intent;scheme=https;action=android.intent.action.VIEW;type=video/*'), it[-1:] if it else it)
    print('Fehler der Seite:', errors)
    print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
    b.close()
