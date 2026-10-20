import time, json, sys, base64
exec(open('test_v8.py').read().split("results = []")[0])
CLIP = open(WEB + '/clip.webm', 'rb').read()
CLIP2 = open(WEB + '/clip_mse.webm', 'rb').read()   # anderes Video, andere Länge (30 s statt 60 s)
A, B = '111111111111111', '222222222222222'
def efg(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip('=')
def prog(a, q): return 'https://video.test.fbcdn.net/v/t42/' + a + '_' + q + '.mp4?efg=' + efg({'vencode_tag': 'xpv_progressive.FEED..C3.%s.progressive_h264_%sp' % (q, q), 'xpv_asset_id': a})
def plain(a, q): return 'https://video.test.fbcdn.net/v/t42/' + a + '_' + q + 'plain.mp4?oh=1'   # ohne Kennung, wie manche HD-Adressen
def seg(a, kind, n): return 'https://video.test.fbcdn.net/v/t42/' + a + '_' + kind + '.mp4?bytestart=%d&efg=' % (n * 1000) + efg({'vencode_tag': 'dash_h264_' + kind, 'xpv_asset_id': a})
PAGE = '''<!doctype html><html><head><title>Reels</title></head><body style="margin:0">
<script type="application/json">{"a":{"id":"%s","playable_url":"%s"},"b":{"id":"%s","playable_url":"%s"}}</script>
<div><a href="/reel/%s/">nächstes Reel</a><a href="/reel/%s/">A</a><video id=v muted playsinline loop style="width:100%%;display:block"></video></div>
<script>
const nc = (u) => fetch(u, { mode: 'no-cors' }).catch(() => {});
const SEG = %s;
(async () => {
  const w = (ms) => new Promise(r => setTimeout(r, ms));
  const v = document.getElementById('v');
  const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
  ms.addEventListener('sourceopen', async () => {
    const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
    const add = async (sb, url) => { const buf = await (await fetch(url)).arrayBuffer(); await new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf); }); };
    await add(sv, '/data/mse_v.webm'); await add(sa, '/data/mse_a.webm'); try { ms.endOfStream(); } catch (e) {}
  }, { once: true });
  v.play().catch(()=>{});
  for (let i = 0; i < 8; i++) { nc(SEG[i]); await w(350); }   // A spielt und lädt laufend nach
  window.__ready = true;
})();
</script></body></html>''' % (A, prog(A, '360'), B, plain(B, '360'), B, A, json.dumps([seg(A, 'video' if i % 2 else 'audio', i) for i in range(8)]))
# Nachgeladene Reel-Seite: enthält A (nur SD) UND das nächste Reel B in HD – B-Adresse ohne Kennung
REEL = '<html><script>{"x":{"id":"%s","playable_url":"%s"},"y":{"id":"%s","playable_url_quality_hd":"%s","playable_url":"%s"}}</script></html>' % (A, prog(A, '360'), B, plain(B, '1080'), plain(B, '360'))
def run(path):
    S = open(path, encoding='utf-8').read().replace('fbMirror: true', 'fbMirror: false')
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        def fb(route):
            u = route.request.url
            if u.rstrip('/').endswith('/test'): route.fulfill(status=200, content_type='text/html', body=PAGE)
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1], 'rb').read())
            elif '/reel/' in u: route.fulfill(status=200, content_type='text/html', body=REEL)
            else: route.fulfill(status=404, body='')
        def cdn(route):
            route.fulfill(status=200, content_type='video/webm', body=CLIP2 if B in route.request.url else CLIP, headers={'Access-Control-Allow-Origin': '*'})
        ctx.route('https://www.facebook.com/**', fb); ctx.route('https://video.test.fbcdn.net/**', cdn)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto('https://www.facebook.com/test')
        pg.wait_for_function('window.__ready && document.getElementById("v").currentTime > 0.5', timeout=25000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => { const v = window.__evpRoot && window.__evpRoot.querySelector(".vv"); return v && v.currentSrc; }', timeout=15000)
        first = pg.evaluate('() => window.__evpRoot.querySelector(".vv").currentSrc')
        time.sleep(6)   # HD-Suche im Hintergrund abwarten
        later = pg.evaluate('() => window.__evpRoot.querySelector(".vv") ? window.__evpRoot.querySelector(".vv").currentSrc : "zu"')
        menu = pg.evaluate('() => { const b = window.__evpRoot.querySelector("button[aria-label=Qualität]"); return b ? [b.hidden, b.textContent] : null; }')
        b.close()
    who = lambda u: 'A (richtig)' if A in u else ('B (FALSCHES Video)' if B in u else u[:40])
    return who(first), who(later), menu
ok = True
for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
    f, l, m = run(path)
    print('%-8s geöffnet: %-12s | nach HD-Suche: %s' % (label, f, l))
    if label.startswith('neu') and not (f.startswith('A') and l.startswith('A')): ok = False
srv.shutdown()
print('ERGEBNIS neu:', 'bestanden' if ok else 'FEHLER')
