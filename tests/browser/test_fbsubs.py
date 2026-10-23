import pwshim  # BROWSER=firefox → Tests in Firefox
# Untertitel der Seite, wenn auf einer Seite viele Videos stehen (Facebook-Startseite): Nur die Untertitel, die zu
# DIESEM Video gehören (seine Video-ID steht nah bei der Adresse), kommen ins Menü. Und Untertitel, die deutlich länger
# laufen als das Video, gehören zu einem anderen Video und werden verworfen (auch ohne Video-IDs).
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
def subs_menu(pg):
    subsm(pg)
    try: pg.wait_for_function('() => !Array.from(%s.querySelectorAll(".vmenu.on .vmi")).some(b => /Suche Untertitel/.test(b.textContent))' % R, timeout=12000)
    except Exception: pass
    time.sleep(0.3); return items(pg)
def vtt(tag, last):
    out = 'WEBVTT\n\n'
    for t in range(2, int(last), 4): out += '00:00:%02d.000 --> 00:00:%02d.500\n%s Zeile %d\n\n' % (t % 60, (t + 3) % 60, tag, t) if t < 60 else '00:%02d:%02d.000 --> 00:%02d:%02d.500\n%s Zeile %d\n\n' % (t // 60, t % 60, (t + 3) // 60, (t + 3) % 60, tag, t)
    return out
CLIP = open(WEB + '/clip.webm', 'rb').read()   # 60 s
MINE, OTHER = '111111111111111', '222222222222222'
PAD = 'x' * 2500   # Abstand zwischen den Beiträgen in den Seitendaten
FB = '''<!doctype html><html><head><title>Facebook</title></head><body style="margin:0">
<div data-store='{"videoID":"%s"}'><video id=v muted autoplay playsinline src="/data/clip.webm" style="width:100%%;display:block"></video></div>
<script type="application/json">{"a":{"video_id":"%s","captions_url":"https://www.facebook.com/data/subA.vtt"},"pad":"%s",
"b":{"video_id":"%s","captions_url":"https://www.facebook.com/data/subB.vtt"}}</script>
</body></html>''' % (MINE, MINE, PAD, OTHER)
def clip(route):
    rg = route.request.headers.get('range', ''); n = len(CLIP)
    if rg.startswith('bytes='):
        a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
        route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
    else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
def fb(route):
    u = route.request.url
    if '/watch/' in u: route.fulfill(status=200, content_type='text/html', body=FB)
    elif u.endswith('/data/subA.vtt'): route.fulfill(status=200, content_type='text/vtt', body=vtt('A', 58))
    elif u.endswith('/data/subB.vtt'): route.fulfill(status=200, content_type='text/vtt', body=vtt('B', 58))
    elif '/data/clip.webm' in u: clip(route)
    else: route.fulfill(status=404, body='')
LONG = '''<!doctype html><html><head><title>Zwei Untertitel</title></head><body style="margin:0">
<video id=v muted autoplay playsinline src="/clip.webm" style="width:100%%;display:block"></video>
<script type="application/json">{"subs":["%s/ut/kurz.vtt","%s/ut/lang.vtt"]}</script></body></html>'''
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    ctx.route('https://www.facebook.com/**', fb)
    H = 'https://untertitel.test'
    def long(route):
        u = route.request.url
        if u.endswith('/seite'): route.fulfill(status=200, content_type='text/html', body=LONG % (H, H))
        elif u.endswith('/ut/kurz.vtt'): route.fulfill(status=200, content_type='text/vtt', body=vtt('Kurz', 58))
        elif u.endswith('/ut/lang.vtt'): route.fulfill(status=200, content_type='text/vtt', body=vtt('Lang', 300))
        elif u.endswith('/clip.webm'): clip(route)
        else: route.fulfill(status=404, body='')
    ctx.route(H + '/**', long)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    # 1) Facebook: zwei Untertitel in den Seitendaten – nur der bei der ID dieses Videos
    pg.goto('https://www.facebook.com/watch/?v=' + MINE)
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && (v.srcObject || v.readyState >= 1); }' % R, timeout=20000); time.sleep(0.8)
    m = subs_menu(pg)
    page = [x for x in m if x[1] == 'von der Seite']
    check('Facebook: nur EIN Untertitel von der Seite (der dieses Videos)', len(page) == 1, m)
    if page: pick(pg, page[0][0]); time.sleep(0.6)
    t = pg.evaluate('() => { const v = document.getElementById("v"); v.currentTime = 10.3; return new Promise((r) => setTimeout(() => r(%s.querySelector(".vsub").textContent), 700)); }' % R)
    check('… und zwar der richtige (A)', t == 'A Zeile 10', t)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.6)
    # 2) ohne Video-IDs: Untertitel, die bis 5:00 laufen, passen nicht zu einem 1-Minuten-Video
    pg.goto(H + '/seite')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && isFinite(v.duration); }' % R, timeout=20000); time.sleep(0.6)
    m = subs_menu(pg)
    page = [x for x in m if x[1] == 'von der Seite']
    check('Untertitel länger als das Video: verworfen (nur einer bleibt)', len(page) == 1, m)
    if page: pick(pg, page[0][0]); time.sleep(0.5)
    t = sub_at(pg, 14.3)
    check('… der passende bleibt', t == 'Kurz Zeile 14', t)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
