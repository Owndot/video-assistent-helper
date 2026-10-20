import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
CODE, MID = 'DesKisByGjb', '4011627732282140891'
MP4 = 'https://scontent-ber1-1.cdninstagram.com/o1/v/t16/f2/m86/' + MID + '_720.mp4?efg=x'
PAGE = '''<!doctype html><html><head><title>Instagram</title></head><body style="margin:0">
<div><video id=v muted playsinline style="width:100%;display:block"></video></div><p>Reels</p>
<script>
(async () => {
  const v = document.getElementById('v');
  const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
  await new Promise(r => ms.addEventListener('sourceopen', r, { once: true }));
  const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
  const add = async (sb, url) => { const buf = await (await fetch(url)).arrayBuffer(); await new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf); }); };
  await add(sv, '/data/mse_v.webm'); await add(sa, '/data/mse_a.webm'); try { ms.endOfStream(); } catch (e) {}
  v.play().catch(() => {});
  window.__ready = true;
})();
</script></body></html>'''
REC = """const __ac = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () {
  if (/^intent:/.test(this.href)) { (window.__intents = window.__intents || []).push(this.href); return; }
  return __ac.call(this); };"""
def run(api_duration):
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        api = []
        def ig(route):
            u = route.request.url
            if '/reels/' + CODE in u: route.fulfill(status=200, content_type='text/html', body=PAGE)
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1], 'rb').read())
            elif '/api/v1/media/' + MID + '/info/' in u:
                api.append(route.request.headers.get('x-ig-app-id'))
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'items': [{'code': CODE, 'video_duration': api_duration,
                    'video_versions': [{'url': MP4.replace('_720', '_480'), 'width': 480, 'height': 854}, {'url': MP4, 'width': 720, 'height': 1280}]}]}))
            else: route.fulfill(status=404, body='')
        ctx.route('https://www.instagram.com/**', ig)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(REC); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('https://www.instagram.com/reels/' + CODE + '/')
        pg.wait_for_function('window.__ready && document.getElementById("v").currentTime > 1', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => window.__evpRoot.querySelector(".vv")', timeout=15000)
        time.sleep(1.5)
        if not pg.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")'):
            pg.mouse.click(200, 300); time.sleep(0.4)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'In externer App öffnen\']").click()')
        time.sleep(1.5)
        r = pg.evaluate('() => [window.__intents || [], window.__evpRoot.querySelector(".toast").textContent]')
        b.close()
    return r, api
results = []
r, api = run(30)
print('Länge passt:   ', 'an VLC:', (r[0][0][:95] + '…') if r[0] else '-', '| Hinweis:', r[1], '| X-IG-App-ID:', api[:1])
results.append(bool(r[0]) and ('scontent-ber1-1.cdninstagram.com/o1/v/t16/f2/m86/' + MID + '_720.mp4') in r[0][0])
r, api = run(95)
print('Länge falsch:  ', 'an VLC:', r[0][0][:60] if r[0] else '-', '| Hinweis:', r[1])
results.append(not r[0] and 'Länge passt nicht' in r[1])
srv.shutdown()
print('ERGEBNIS:', sum(results), 'von', len(results))
