import pwshim  # BROWSER=firefox → Tests in Firefox
# Spul-Vorschau beim Spiegeln (Facebook und Seiten ohne Adresse): keine zweite Quelle → ruhig nur die Zeit.
import time, json
exec(open('test_v8.py').read().split("results = []")[0])
SCRIPT = SCRIPT.replace("seekPreview: 'live'", "seekPreview: 'thumb'")   # dieser Test prüft das kleine Vorschaubild
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
CLIP = open(WEB + '/clip.webm', 'rb').read()        # 60 s – das laufende Video
CLIP2 = open(WEB + '/clip_mse.webm', 'rb').read()   # 30 s – ein anderes Video
A = '123456789012345'
def page(u):
    return '''<!doctype html><html><head><title>Facebook Video</title></head><body style="margin:0">
<script type="application/json">{"video":{"id":"%s","playable_url":"%s"}}</script>
<video id=v muted autoplay playsinline src="/data/clip.webm" style="width:100%%;display:block"></video>
</body></html>''' % (A, u)
HITS = [0]; FLICK = [None]
def fmt(t): t = int(t); return '%d:%02d' % (t // 60, t % 60)
def run(cdn_body, tag):
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        hits = []
        def fb(route):
            u = route.request.url
            if '/watch/' in u: route.fulfill(status=200, content_type='text/html', body=page('https://video.test.fbcdn.net/v/t42/' + A + '_360.mp4?oh=1'))
            elif '/data/clip.webm' in u: route.fulfill(status=200, content_type='video/webm', body=CLIP)
            else: route.fulfill(status=404, body='')
        def cdn(route):   # wie echte Server: Teilabrufe (Range) – sonst kann ein Video nicht springen
            hits.append(route.request.url)
            rg = route.request.headers.get('range', '')
            n = len(cdn_body)
            if rg.startswith('bytes='):
                a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
                route.fulfill(status=206, content_type='video/webm', body=cdn_body[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
            else: route.fulfill(status=200, content_type='video/webm', body=cdn_body, headers={'Accept-Ranges': 'bytes'})
        ctx.route('https://www.facebook.com/**', fb); ctx.route('https://video.test.fbcdn.net/**', cdn)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.goto('https://www.facebook.com/watch/?v=' + A)
        pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.srcObject; }' % R, timeout=15000)
        pg.evaluate('() => { document.getElementById("v").currentTime = 2; }'); time.sleep(4.5)   # 2 s bis ~6 s angeschaut
        mirror = pg.evaluate('() => !!%s.querySelector(".vv").srcObject' % R)
        br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        y = br[1] + br[3] / 2; X = lambda f: br[0] + 7 + f * (br[2] - 14)
        def drag(f):
            pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.2)
            # (am Punkt greifen)
            f0 = pg.evaluate('() => { const v = document.getElementById("v"); return v.currentTime / v.duration; }')
            pg.mouse.move(X(f0), y); pg.mouse.down(); pg.mouse.move(X(f), y, steps=2); time.sleep(2.0)   # (zügig = wie die Leiste)
            r = pg.evaluate('() => { const p = %s.querySelector(".vprev"); return [p.classList.contains("img"), p.dataset.t ? parseFloat(p.dataset.t) : null, p.querySelector("b").textContent]; }' % R)
            pg.mouse.up(); time.sleep(0.8)
            pg.evaluate('() => { document.getElementById("v").currentTime = 6; }'); time.sleep(0.5)
            return r
        dur = pg.evaluate('() => document.getElementById("v").duration')
        far = drag(0.7); seen = drag(4.0 / dur)
        # über Angeschautes und Neues hinweg ziehen: Vorschau darf nicht zwischen Bild und Zeit wechseln (Flackern)
        pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.2)
        pg.mouse.move(X(0.02), y); pg.mouse.down(); looks = set()
        for k in range(40):
            pg.mouse.move(X(0.02 + 0.6 * k / 40), y); time.sleep(0.03)
            looks.add(pg.evaluate('() => { const p = %s.querySelector(".vprev"); return p.classList.contains("img") + "/" + p.style.width; }' % R))
        pg.mouse.up(); time.sleep(0.5)
        FLICK[0] = sorted(looks)
        b.close()
    print('   %s: Spiegeln %s · weit %s · gesehen %s · Abrufe %d' % (tag, mirror, far, seen, len(hits)))
    HITS[0] = len(hits)
    return mirror, far, seen, dur, errs
m, far, seen, dur, errs = run(CLIP, 'Seitendaten mit Datei')
check('Spiegeln aktiv (Facebook)', m)
check('nie gesehene Stelle: nur die Zeit', not far[0] and far[2].split(' · ')[0] == fmt(0.7 * dur), far)
check('angeschaute Stelle: ebenso nur die Zeit (gleich aussehend)', not seen[0] and seen[2].split(' · ')[0] in ('0:03', '0:04'), seen)
check('über Angeschautes und Neues ziehen: kein Flackern (immer gleich)', len(FLICK[0]) == 1 and FLICK[0][0].startswith('false'), FLICK[0])
check('Datei aus den Seitendaten wird nicht geladen', HITS[0] == 0, HITS)
check('keine Fehler', not errs, errs)
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
