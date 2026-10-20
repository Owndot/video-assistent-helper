import time, json, sys, base64
exec(open('test_v8.py').read().split("results = []")[0])
CLIP = open(WEB + '/clip.webm', 'rb').read()
A, B = '111111111111111', '222222222222222'
def efg(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip('=')
def prog(a): return 'https://video.test.fbcdn.net/v/t42/' + a + '_prog.mp4?efg=' + efg({'vencode_tag': 'xpv_progressive.FEED..C3.720.progressive_h264_720p', 'xpv_asset_id': a})
def seg(a, kind, n): return 'https://video.test.fbcdn.net/v/t42/' + a + '_' + kind + '.mp4?bytestart=%d&efg=' % (n * 1000) + efg({'vencode_tag': 'dash_h264_' + kind, 'xpv_asset_id': a})
def page(with_dom_id, steps):
    attr = ' data-video-id="%s"' % A if with_dom_id else ''
    return '''<!doctype html><html><head><title>Facebook</title></head><body style="margin:0">
<script type="application/json">{"a":{"playable_url_quality_hd":"%s","id":"%s"},"b":{"playable_url_quality_hd":"%s","id":"%s"}}</script>
<div%s><video id=v muted playsinline loop style="width:100%%;display:block"></video></div><p>Feed</p>
<script>
const nc = (u) => fetch(u, { mode: 'no-cors' }).catch(() => {});
const SEG = %s; const STEPS = %s; void %s;
(async () => {
  const w = (ms) => new Promise(r => setTimeout(r, ms));
  const idx = { a: 0, b: 0 };
  const startA = () => {
    const v = document.getElementById('v');
    const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
    ms.addEventListener('sourceopen', async () => {
      const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
      const add = async (sb, url) => { const buf = await (await fetch(url)).arrayBuffer();
        await new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf); }); };
      await add(sv, '/data/mse_v.webm'); await add(sa, '/data/mse_a.webm');
      try { ms.endOfStream(); } catch (e) {}
    }, { once: true });
    v.play().catch(()=>{});
  };
  for (const st of STEPS) {
    if (st[0] === 'start') startA();
    else if (st[0] === 'wait') await w(st[1]);
    else for (let i = 0; i < st[1]; i++) { nc(SEG[st[0]][idx[st[0]]++]); await w(st[2]); }
  }
  window.__ready = true;
})();
</script></body></html>''' % (prog(A), A, prog(B), B, attr,
        json.dumps({'a': [seg(A, 'video' if i % 2 else 'audio', i) for i in range(20)], 'b': [seg(B, 'video' if i % 2 else 'audio', i) for i in range(20)]}), json.dumps(steps), json.dumps(prog(A)))
def run(script_path, with_dom_id, steps):
    S = open(script_path, encoding='utf-8').read().replace('fbMirror: true', 'fbMirror: false')
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        def fb(route):
            u = route.request.url
            if u.rstrip('/').endswith('/test'): route.fulfill(status=200, content_type='text/html', body=page(with_dom_id, steps))
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1], 'rb').read())
            else: route.fulfill(status=404, body='')
        def cdn(route):
            route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Access-Control-Allow-Origin': '*'})
        ctx.route('https://www.facebook.com/**', fb)
        ctx.route('https://video.test.fbcdn.net/**', cdn)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page()
        pg.goto('https://www.facebook.com/test')
        pg.wait_for_function('window.__ready && document.getElementById("v").currentTime > 0.5', timeout=20000)
        time.sleep(0.5)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => { const v = window.__evpRoot && window.__evpRoot.querySelector(".vv"); return v && v.currentSrc; }', timeout=15000)
        src = pg.evaluate('() => window.__evpRoot.querySelector(".vv").currentSrc')
        b.close()
    return A if A in src else (B if B in src else src[:60])
SCEN = [
  ('A vorgeladen, B-Schwall 6, A lädt langsam nach', False, [['a', 4, 40], ['wait', 300], ['start'], ['wait', 600], ['b', 6, 30], ['wait', 1800], ['a', 3, 700]]),
  ('A nicht vorgeladen, B-Schwall 6 gleichzeitig', False, [['start'], ['a', 4, 60], ['b', 6, 30], ['wait', 2200], ['a', 3, 600]]),
  ('Video-ID im Beitrag, A lädt gar nicht mehr nach', True, [['a', 6, 40], ['wait', 300], ['start'], ['wait', 2500], ['b', 8, 30]]),
  ('Video-ID im Beitrag, A lädt kaum nach', True, [['a', 4, 40], ['wait', 300], ['start'], ['wait', 600], ['b', 8, 30], ['wait', 500], ['a', 1, 10]]),
]
ok = True
for name, dom, steps in SCEN:
    line = []
    for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
        got = run(path, dom, steps)
        line.append(label + ': ' + ('richtig' if got == A else 'FALSCH (' + ('Nachbar B' if got == B else str(got)) + ')'))
        if label.startswith('neu') and got != A: ok = False
    print('%-48s | %s' % (name, ' | '.join(line)))
srv.shutdown()
print('ERGEBNIS neu:', 'bestanden' if ok else 'FEHLER')
