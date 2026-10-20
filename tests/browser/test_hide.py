import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
SCRIPT_ = SCRIPT
DL = "Herunterladen (z. B. mit YTDLnis)"; EX = "In externer App öffnen"
def vis(pg):
    return pg.evaluate('''() => { const r = window.__evpRoot; const f = (l) => { const b = r.querySelector('button[aria-label="' + l + '"]'); return b ? !b.hidden : null; };
        return { download: f("%s"), teilen: f("%s") }; }''' % (DL, EX))
ok = []
# 1) TikTok ohne Video-ID am Player (wie bei dir, nicht eingeloggt)
src = open('test_tt.py').read()
PAGE_NOID = '''<!doctype html><html><head><title>TikTok</title></head><body style="margin:0"><div><video id=v muted playsinline style="width:100%;display:block"></video></div>
<script>(async () => { const v = document.getElementById('v'); const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
  await new Promise(r => ms.addEventListener('sourceopen', r, { once: true }));
  const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
  const add = async (sb, url) => { const buf = await (await fetch(url)).arrayBuffer(); await new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf); }); };
  await add(sv, '/data/mse_v?raw'); await add(sa, '/data/mse_a?raw'); try { ms.endOfStream(); } catch (e) {} v.play().catch(() => {}); window.__ready = true; })();</script></body></html>'''
def open_site(url, routes, script=SCRIPT_):
    p = sync_playwright().start()
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    for pat, fn in routes: ctx.route(pat, fn)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(script)
    pg = ctx.new_page(); pg.goto(url)
    pg.wait_for_function('(window.__ready || document.getElementById("v").currentTime > 1) && document.getElementById("v").currentTime > 1', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
    pg.wait_for_function('() => window.__evpRoot.querySelector(".vv")', timeout=15000)
    return p, b, pg
def data(route):
    u = route.request.url
    if '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1].split('?')[0] + '.webm', 'rb').read()); return True
    return False
def tt(route):
    if route.request.url.endswith('/foryou'): route.fulfill(status=200, content_type='text/html', body=PAGE_NOID)
    elif not data(route): route.fulfill(status=404, body='')
p, b, pg = open_site('https://www.tiktok.com/foryou', [('https://www.tiktok.com/**', tt)])
time.sleep(2); v = vis(pg); b.close(); p.stop()
print('TikTok ohne Video-ID:', v); ok.append(v == {'download': False, 'teilen': False})
# 2) Instagram: Teilen erscheint, sobald die MP4 mit Ton gefunden ist
igsrc = open('test_ig.py').read()
ns = {}
exec(igsrc.split("REC = ")[0].split("exec(open('test_v8.py')")[0] + "\n", ns)
CODE, MID = 'DesKisByGjb', '4011627732282140891'
IGPAGE = PAGE_NOID.replace('<title>TikTok</title>', '<title>Instagram</title>')
def ig(route):
    u = route.request.url
    if '/reels/' + CODE in u: route.fulfill(status=200, content_type='text/html', body=IGPAGE)
    elif '/api/v1/media/' + MID in u:
        time.sleep(0.8)
        route.fulfill(status=200, content_type='application/json', body=json.dumps({'items': [{'video_duration': 30, 'video_versions': [{'url': 'https://scontent.cdninstagram.com/v/x_720.mp4', 'width': 720, 'height': 1280}]}]}))
    elif not data(route): route.fulfill(status=404, body='')
p, b, pg = open_site('https://www.instagram.com/reels/' + CODE + '/', [('https://www.instagram.com/**', ig)])
v0 = vis(pg); time.sleep(2.5); v1 = vis(pg); b.close(); p.stop()
print('Instagram sofort:', v0, '| nach Suche:', v1); ok.append(v1['teilen'] is True and v1['download'] is True)
# 3) normale Videodatei: beides da (Download direkt, Teilen an VLC)
p, b, pg = open_site('http://127.0.0.1:8775/direct.html', [])
time.sleep(1.5); v = vis(pg); b.close(); p.stop()
print('Videodatei:', v); ok.append(v == {'download': True, 'teilen': True})
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
