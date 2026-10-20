import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys, base64
exec(open('test_v8.py').read().split("results = []")[0])
CLIP = open(WEB + '/clip.webm', 'rb').read()
R4 = ['4492197337662979', '2124988348408949', '1589423489311856', '1021815134245564']   # A = R4[0] läuft
A = R4[0]
def efg(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip('=')
def prog(a): return 'https://scontent-ber1-1.xx.fbcdn.net/o1/v/t2/f2/m367/' + a + '.mp4?efg=' + efg({'vencode_tag': 'xpv_progressive.FACEBOOK..C3.720.progressive_h264-basic-gen2_720p', 'xpv_asset_id': a})
def seg(a, n): return 'https://scontent-ber1-1.xx.fbcdn.net/o1/v/t2/f2/m69/' + a + '_%d.mp4?bytestart=%d&efg=' % (n, n * 1000) + efg({'vencode_tag': 'dash_h264_' + ('video' if n % 2 else 'audio'), 'xpv_asset_id': a})
def page(order):
    reels = ''.join('<div class="reel" data-video-url="%s" data-store=\'{"videoID":"%s"}\'></div>' % (prog(a).replace('&', '&amp;'), a) for a in R4)
    return '''<!doctype html><html><head><title>Reels</title></head><body style="margin:0">
<div id="list">%s</div>
<div id="player"><video id=v muted playsinline style="width:100%%;display:block"></video></div>
<script>
const nc = (u) => fetch(u, { mode: 'no-cors' }).catch(() => {});
const R4 = %s, SEG = {}; R4.forEach((a) => SEG[a] = %s[a]);
const w = (ms) => new Promise(r => setTimeout(r, ms));
(async () => {
  const v = document.getElementById('v');
  const ms = new MediaSource(); v.src = URL.createObjectURL(ms);
  await new Promise(r => ms.addEventListener('sourceopen', r, { once: true }));
  const sv = ms.addSourceBuffer('video/webm; codecs="vp8"'), sa = ms.addSourceBuffer('audio/webm; codecs="opus"');
  const V = new Uint8Array(await (await fetch('/data/mse_v.webm')).arrayBuffer()), Au = new Uint8Array(await (await fetch('/data/mse_a.webm')).arrayBuffer());
  const N = 8; let k = 0;
  const app = (sb, buf, i) => new Promise((r) => { sb.addEventListener('updateend', r, { once: true }); sb.appendBuffer(buf.slice(Math.floor(buf.length * i / N), Math.floor(buf.length * (i + 1) / N))); });
  const feed = async () => { await app(sv, V, k); await app(sa, Au, k); k++; };   // ein Stück in DIESES Video einspeisen
  await feed(); v.play().catch(() => {});
  const idx = {}; R4.forEach((a) => idx[a] = 0);
  for (const st of %s) {
    if (st[0] === 'wait') { await w(st[1]); continue; }
    const a = R4[st[0]];
    for (let i = 0; i < st[1]; i++) { nc(SEG[a][idx[a]++]); await w(st[2]); }
    if (st[3]) { await w(250); await feed(); }   // Stück kam an -> wird abgespielt
  }
  window.__ready = true;
})();
</script></body></html>''' % (reels, json.dumps(R4), json.dumps({a: [seg(a, n) for n in range(20)] for a in R4}), json.dumps(order))
# Ablauf: alle vier Reels werden im Schwall vorgeladen (D sogar mit 7 Stücken), A spielt und lädt Stück für Stück nach
ORDER = [[1, 6, 25, 0], [2, 6, 25, 0], [3, 7, 25, 0], ['wait', 2200],
         [0, 1, 10, 1], ['wait', 450], [3, 2, 30, 0], ['wait', 450], [0, 1, 10, 1], ['wait', 300], [1, 2, 30, 0], ['wait', 600], [0, 1, 10, 1], ['wait', 500], [3, 2, 30, 0], ['wait', 400],
         [0, 1, 10, 1], ['wait', 350], [2, 1, 30, 0], ['wait', 550], [0, 1, 10, 1], ['wait', 420], [3, 1, 30, 0], ['wait', 500]]
def run(path):
    S = open(path, encoding='utf-8').read().replace('fbMirror: true', 'fbMirror: false')
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        def fb(route):
            u = route.request.url
            if '/reel/' in u and u.count('/') <= 5: route.fulfill(status=200, content_type='text/html', body=page(ORDER))
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1], 'rb').read())
            else: route.fulfill(status=404, body='')
        ctx.route('https://m.facebook.com/**', fb)
        ctx.route('https://scontent-ber1-1.xx.fbcdn.net/**', lambda r: r.fulfill(status=200, content_type='video/mp4', body=CLIP[:4000] if '/m69/' in r.request.url else CLIP))
        ctx.add_init_script(PRE); ctx.add_init_script('window.GM_registerMenuCommand = (n, f) => { (window.__cmds = window.__cmds || {})[n] = f; };'); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto('https://m.facebook.com/reel/1874944457203372/')
        pg.wait_for_function('window.__ready', timeout=40000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.6)
        pg.evaluate('() => window.__cmds["Diagnose anzeigen"]()'); time.sleep(0.4)
        z = pg.evaluate('() => { const t = window.__evpRoot.querySelector(".diag textarea"); return t ? t.value.split("\\n").filter(l => /^(Zuordnung|Puffer)/.test(l)).join(" ## ") : ""; }')
        pg.evaluate('() => { const d = window.__evpRoot.querySelector(".diag"); if (d) d.remove(); }')
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && v.currentSrc; }', timeout=15000)
        src = pg.evaluate('() => window.__evpRoot.querySelector(".vv").currentSrc')
        b.close()
    who = next((('richtig (A)' if a == A else 'FALSCH (Nachbar)') for a in R4 if a in src), src[:50])
    return who, z
ok = True
for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
    who, z = run(path)
    print('%-8s öffnet: %-16s | %s' % (label, who, z[-260:]))
    if label.startswith('neu') and not who.startswith('richtig'): ok = False
srv.shutdown()
print('ERGEBNIS neu:', 'bestanden' if ok else 'FEHLER')
