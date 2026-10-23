import pwshim  # BROWSER=firefox → Tests in Firefox
# Teilen in Facebook-Reels: Die Adresse bleibt beim ersten Reel stehen, gespielt wird aber ein anderes →
# geteilt wird das laufende Reel (seine ID aus dem eigenen Kasten), nicht die Seitenadresse.
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
CLIP = open(WEB + '/clip.webm', 'rb').read()
A, B = '111111111111111', '222222222222222'
PAGE = '''<!doctype html><html><head><title>Reels</title></head><body style="margin:0">
<div class="reel" style="height:0;overflow:hidden"><a href="/reel/%s/">Reel A</a><video id=a muted playsinline src="/data/a.webm"></video></div>
<div class="reel" data-store='{"videoID":"%s"}'><a href="/reel/%s/?s=1">Reel B</a><video id=v muted autoplay playsinline src="/data/b.webm" style="width:100%%;display:block"></video></div>
</body></html>''' % (A, B, B)
def serve(route):
    u = route.request.url
    if '/reel/' in u: route.fulfill(status=200, content_type='text/html', body=PAGE); return
    if '/data/' in u: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'}); return
    route.fulfill(status=404, body='')
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.route('https://www.facebook.com/**', serve)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto('https://www.facebook.com/reel/%s/' % A)   # wie nach dem Weiterwischen: Adresse noch beim ersten Reel
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => !!%s.querySelector(".vv")' % R, timeout=15000); time.sleep(0.8)
    pg.evaluate('() => { window.__shared = []; Object.defineProperty(navigator, "share", { configurable: true, value: (d) => { window.__shared.push(d); return Promise.resolve(); } }); }')
    pg.evaluate('() => { const r = %s; r.querySelector(".vp").classList.add("ctl"); r.querySelector("button[aria-label=Mehr]").click(); }' % R); time.sleep(0.4)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu.on .vmi")).find(b => b.firstChild.textContent === "Link teilen").click()' % R); time.sleep(0.6)
    sh = pg.evaluate('() => window.__shared')
    check('Reels: geteilt wird das laufende Reel (B), nicht die Seitenadresse (A)', len(sh) == 1 and ('/reel/%s/' % B) in sh[0]['url'] and A not in sh[0]['url'], sh)
    # passt die Adresse zum Video, bleibt es die Seitenadresse
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.6)
    pg.goto('https://www.facebook.com/reel/%s/' % B)
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => !!%s.querySelector(".vv")' % R, timeout=15000); time.sleep(0.8)
    pg.evaluate('() => { window.__shared = []; Object.defineProperty(navigator, "share", { configurable: true, value: (d) => { window.__shared.push(d); return Promise.resolve(); } }); }')
    pg.evaluate('() => { const r = %s; r.querySelector(".vp").classList.add("ctl"); r.querySelector("button[aria-label=Mehr]").click(); }' % R); time.sleep(0.4)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu.on .vmi")).find(b => b.firstChild.textContent === "Link teilen").click()' % R); time.sleep(0.6)
    sh2 = pg.evaluate('() => window.__shared')
    check('Adresse passt zum Video: die Seitenadresse', len(sh2) == 1 and sh2[0]['url'] == 'https://www.facebook.com/reel/%s/' % B, sh2)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
