import pwshim  # BROWSER=firefox → Tests in Firefox
# Reels-Seiten springen beim Ende des Videos von selbst zum nächsten (17.2): Der Player hält das gespiegelte
# Original KURZ VOR dem Ende an – die Seite bekommt „ended“ nie, man bleibt auf demselben Reel.
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
CLIP = open(WEB + '/clip.webm', 'rb').read()
PAGE = '''<!doctype html><html><head><title>Reels</title></head><body style="margin:0">
<video id=v muted autoplay playsinline src="/data/reel1.webm" style="width:100%;display:block"></video>
<script>
// wie Facebook: am Ende automatisch das nächste Reel
const v = document.getElementById('v'); window.__advanced = 0;
v.addEventListener('ended', () => { window.__advanced++; v.src = '/data/reel2.webm'; v.play().catch(() => {}); });
</script></body></html>'''
def serve(route):
    u = route.request.url
    if '/reel/' in u: route.fulfill(status=200, content_type='text/html', body=PAGE); return
    if '/data/reel' not in u: route.fulfill(status=404, body=''); return
    rg = route.request.headers.get('range', ''); n = len(CLIP)
    if rg.startswith('bytes='):
        a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
        route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
    else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.route('https://www.facebook.com/**', serve)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto('https://www.facebook.com/reel/111222333444555/')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.srcObject; }' % R, timeout=15000)
    time.sleep(0.5)
    pg.evaluate('() => { const v = document.getElementById("v"); v.currentTime = v.duration - 3; }')
    time.sleep(5)
    s = pg.evaluate('''() => { const v = document.getElementById("v"); return { adv: window.__advanced, src: v.src.split("/").pop(), paused: v.paused,
        left: Math.round((v.duration - v.currentTime) * 100) / 100, open: !!%s.querySelector(".vp"), play: %s.querySelector(".vplay").getAttribute("aria-label") }; }''' % (R, R))
    print('   am Ende:', s)
    check('gespiegelt: kurz vor dem Ende angehalten (Seite springt NICHT zum nächsten Reel)', s['adv'] == 0 and s['src'] == 'reel1.webm' and s['paused'] and 0 < s['left'] < 0.6, s)
    check('… Player bleibt offen und zeigt „Play“', s['open'] and s['play'] in ('Wiedergabe', 'Play'), s)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.2)
    s2 = pg.evaluate('() => { const v = document.getElementById("v"); return { adv: window.__advanced, src: v.src.split("/").pop() }; }')
    check('nach dem Schließen: noch dasselbe Reel', s2['adv'] == 0 and s2['src'] == 'reel1.webm', s2)
    # Eigener Player (Seite wird nicht gespiegelt): am Ende schließen → Original bleibt kurz vor dem Ende stehen
    ctx.route('https://reels.example.com/**', serve)
    pg.goto('https://reels.example.com/reel/1/')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentSrc && v.readyState >= 2; }' % R, timeout=15000)
    own = pg.evaluate('() => !%s.querySelector(".vv").srcObject' % R)
    pg.evaluate('() => { const v = %s.querySelector(".vv"); v.currentTime = v.duration - 2; v.play(); }' % R); time.sleep(3.5)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.5)
    s3 = pg.evaluate('() => { const v = document.getElementById("v"); return { adv: window.__advanced, src: v.src.split("/").pop(), paused: v.paused, left: Math.round((v.duration - v.currentTime) * 100) / 100 }; }')
    check('eigener Player am Ende geschlossen: Seite bleibt beim Reel (angehalten kurz vor dem Ende)', own and s3['adv'] == 0 and s3['src'] == 'reel1.webm' and s3['paused'] and 0 < s3['left'] < 1, [own, s3])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
