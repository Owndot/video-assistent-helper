import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
VID, USER = '7349876543210987654', 'testkanal'
HI = 'https://v16-webapp-prime.tiktok.com/video/tos/useast2a/tos-useast2a-ve-0068c001/oHI/?mime_type=video_mp4&br=2000'
LO = 'https://v16-webapp-prime.tiktok.com/video/tos/useast2a/tos-useast2a-ve-0068c001/oLO/?mime_type=video_mp4&br=600'
PAGE = '''<!doctype html><html><head><title>TikTok</title></head><body style="margin:0">
<div data-e2e="recommend-list-item-container"><div id="xgwrapper-0-%s"><video id=v muted playsinline style="width:100%%;display:block"></video></div>
  <a data-e2e="video-author-uniqueid" href="/@%s">%s</a></div>
<div data-e2e="recommend-list-item-container"><a href="/@anderer/video/7111111111111111111">nächstes</a><a href="/@anderer">anderer</a></div>
<script>
(async () => {
  const v = document.getElementById('v');
  const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
  await new Promise(r => ms.addEventListener('sourceopen', r, { once: true }));
  const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
  const add = async (sb, url) => { const buf = await (await fetch(url)).arrayBuffer(); await new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf); }); };
  await add(sv, '/data/mse_v?raw'); await add(sa, '/data/mse_a?raw'); try { ms.endOfStream(); } catch (e) {}
  v.play().catch(() => {}); window.__ready = true;
})();
</script></body></html>''' % (VID, USER, USER)
def item_page(dur):
    data = {'__DEFAULT_SCOPE__': {'webapp.video-detail': {'itemInfo': {'itemStruct': {'id': VID, 'author': {'uniqueId': USER}, 'video': {
        'duration': dur, 'playAddr': LO, 'bitrateInfo': [{'Bitrate': 600000, 'PlayAddr': {'UrlList': [LO], 'Width': 576, 'Height': 1024}}, {'Bitrate': 2000000, 'PlayAddr': {'UrlList': [HI], 'Width': 1080, 'Height': 1920}}]}}}}}}
    return '<html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">' + json.dumps(data) + '</script></body></html>'
REC = """const __ac = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () {
  if (/^intent:/.test(this.href)) { (window.__intents = window.__intents || []).push(this.href); return; }
  return __ac.call(this); };
navigator.share = (d) => { (window.__shared = window.__shared || []).push(d.url); return Promise.resolve(); };"""
def run(dur, action):
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        seen = []
        def tt(route):
            u = route.request.url
            if u.endswith('/foryou'): route.fulfill(status=200, content_type='text/html', body=PAGE)
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1].split('?')[0] + '.webm', 'rb').read())
            elif '/video/' + VID in u: seen.append(u); route.fulfill(status=200, content_type='text/html', body=item_page(dur))
            else: route.fulfill(status=404, body='')
        ctx.route('https://www.tiktok.com/**', tt)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(REC); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('https://www.tiktok.com/foryou')
        pg.wait_for_function('window.__ready && document.getElementById("v").currentTime > 1', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => window.__evpRoot.querySelector(".vv")', timeout=15000)
        time.sleep(1.5)
        pg.evaluate('() => window.__evpRoot.querySelector(".vp").classList.add("ctl")'); time.sleep(0.2)
        label = 'Herunterladen (z. B. mit YTDLnis)' if action == 'dl' else 'In externer App öffnen'
        pg.evaluate('(l) => window.__evpRoot.querySelector("button[aria-label=\'" + l + "\']").click()', label)
        time.sleep(1.5)
        r = pg.evaluate('() => [window.__intents || [], window.__shared || [], window.__evpRoot.querySelector(".toast").textContent]')
        b.close()
    return r, seen
ok = []
POST = 'https://www.tiktok.com/@%s/video/%s' % (USER, VID)
r, _ = run(30, 'dl')
print('Download  -> YTDLnis bekommt:', r[1])
ok.append(r[1] == [POST])
r, seen = run(30, 'ext')
print('Teilen    -> VLC bekommt:', (r[0][0][:80] + '…') if r[0] else '-', '| Videoseite geholt:', seen[:1])
ok.append(bool(r[0]) and 'oHI' in r[0][0])
r, _ = run(95, 'ext')
print('Länge falsch -> VLC:', r[0][:1] or '-', '| geteilt:', r[1], '|', r[2])
ok.append(not r[0] and r[1] == [POST])
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
