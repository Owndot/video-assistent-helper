import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys, re
exec(open('test_v8.py').read().split("results = []")[0])
def frames(pg, ms=1200):
    return pg.evaluate('''(ms) => new Promise((res) => { const v = window.__evpRoot.querySelector(".vv"); let n = 0;
        if (!v.requestVideoFrameCallback) { res(-1); return; }
        const cb = () => { n++; v.requestVideoFrameCallback(cb); }; v.requestVideoFrameCallback(cb); setTimeout(() => res(n), ms); })''', ms)
def run(path, url):
    S = open(path, encoding='utf-8').read()
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        # Spiegeln erzwingen (wie auf Facebook): die eigene Quellensuche findet hier nichts
        def fb(route):
            u = route.request.url
            if u.endswith('/loop'): route.fulfill(status=200, content_type='text/html', body=open(WEB + '/loopreload.html').read())
            elif u.endswith('/loopmse'): route.fulfill(status=200, content_type='text/html', body=open(WEB + '/loopmse.html').read())
            elif '/data/mse_' in u: route.fulfill(status=200, content_type='video/webm', body=open(WEB + '/' + u.split('/data/')[1] + '.webm', 'rb').read())
            elif '/clip.webm' in u:
                rng = route.request.headers.get('range'); data = open(WEB + '/clip.webm', 'rb').read()
                if rng:
                    a, _, z = rng.replace('bytes=', '').partition('-'); a = int(a or 0); z = int(z) if z else len(data) - 1
                    route.fulfill(status=206, content_type='video/webm', body=data[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, len(data)), 'Accept-Ranges': 'bytes'})
                else: route.fulfill(status=200, content_type='video/webm', body=data, headers={'Accept-Ranges': 'bytes'})
            else: route.fulfill(status=404, body='')
        ctx.route('https://www.facebook.com/**', fb)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto(url)
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => window.__evpRoot.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()')
        pg.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && v.srcObject; }', timeout=15000)
        time.sleep(0.8)
        f1 = frames(pg)
        pg.evaluate('() => { const o = document.getElementById("v"); o.currentTime = o.duration - 1.2; }')
        pg.wait_for_function('() => window.__loops >= 1', timeout=10000)
        # Zeitleiste (nur im Protokoll): Zustand des Player-Videos alle 100 ms
        tl = pg.evaluate('''() => new Promise((res) => { const v = window.__evpRoot.querySelector(".vv"); const out = []; let last = null, id = 0, n = 0;
            const t0 = performance.now(); const iv = setInterval(() => { const so = v.srcObject; if (so !== last) { last = so; id++; }
              const tr = so ? so.getVideoTracks().map((t) => t.readyState[0] + (t.muted ? "m" : "")).join("") : "-";
              const o = document.getElementById("v"), w = v.closest(".vp") || v.parentElement;
              out.push(Math.round(performance.now() - t0) + ":s" + id + "/" + tr + (v.paused ? "/P" : "") + (v.ended ? "/E" : "") + "/r" + v.readyState + " o" + o.currentTime.toFixed(1) + (o.paused ? "P" : "") + " [" + ((w && w.dataset.mirror) || "") + "]");
              if (++n >= 25) { clearInterval(iv); res(out.join(" ")); } }, 100); })''')
        print('   Zeitleiste:', tl)
        f2 = frames(pg)
        bright = pg.evaluate("""() => { const hc = window.__evpRoot.querySelector(".vhold"); const v = hc && hc.isConnected ? hc : window.__evpRoot.querySelector(".vv"); const c = document.createElement('canvas'); c.width = 64; c.height = 36;
            const g = c.getContext('2d'); g.drawImage(v, 0, 0, 64, 36); const d = g.getImageData(0, 0, 64, 36).data; let s = 0; for (let i = 0; i < d.length; i += 4) s += d[i] + d[i+1] + d[i+2]; return Math.round(s / (d.length / 4) / 3); }""")
        info = pg.evaluate('() => { const v = window.__evpRoot.querySelector(".vv"); const t = v.srcObject ? v.srcObject.getVideoTracks() : []; return [t.map(x => x.readyState + (x.muted ? "/stumm" : "")), document.getElementById("v").paused]; }')
        b.close()
    return f1, f2, info, bright
for url in ['https://www.facebook.com/loop', 'https://www.facebook.com/loopmse']:
    for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
        f1, f2, info, br = run(path, url)
        print('%-8s %-9s Bilder/s vorher %3d | nach Schleife %3d | Helligkeit %3d %s | Spur %s' % (url.split('/')[-1], label, round(f1 / 1.2), round(f2 / 1.2), br, '(SCHWARZ)' if br < 8 else '', info[0]))
srv.shutdown()
