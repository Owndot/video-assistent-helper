import pwshim  # BROWSER=firefox → Tests in Firefox
# Facebook-Reels zählen am Ende selbst herunter („Next reel in 3 … Cancel“) und springen dann weiter – auch wenn das
# Video angehalten ist (18.3). Solange der Player offen ist, tippt das Skript sofort auf „Cancel“.
# Gegenprobe: Ein anderer Dialog mit „Cancel“ (ohne Countdown) wird NICHT angetippt.
import time, os
src = open('test_v8.py').read().split("results = []")[0]
if os.environ.get('EVP_SCRIPT'): src = src.replace("open('../../userscript/video-assistent.user.js'", "open(os.environ['EVP_SCRIPT']")
exec(src)
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
CLIP = open(WEB + '/clip.webm', 'rb').read()
PAGE = '''<!doctype html><html><head><title>Reels</title></head><body style="margin:0">
<video id=v muted autoplay playsinline src="/data/reel1.webm" style="width:100%;display:block"></video>
<div id=dlg></div>
<script>
// wie Facebook: fragt die Zeit selbst ab (keine Ereignisse) und zählt am Ende herunter – egal ob angehalten
const v = document.getElementById('v'); window.__advanced = 0; window.__cancelled = 0; window.__dlgCancel = 0; let cd = null;
setInterval(() => {
  if (cd || !v.src.endsWith('reel1.webm') || !(v.duration - v.currentTime < 1.5)) return;
  cd = document.createElement('div'); cd.setAttribute('role', 'dialog');
  cd.innerHTML = '<div><span>↓ </span><span>Next reel in <b>3</b></span></div><div role="button" tabindex="0"><span>Cancel</span></div>';
  document.body.append(cd);
  let n = 3;
  const iv = setInterval(() => { n--; if (!cd.isConnected) { clearInterval(iv); return; } if (n <= 0) { clearInterval(iv); cd.remove(); window.__advanced++; v.src = '/data/reel2.webm'; v.play().catch(() => {}); return; } cd.querySelector('b').textContent = n; }, 700);
  cd.querySelector('[role=button]').addEventListener('click', () => { window.__cancelled++; clearInterval(iv); cd.remove(); setTimeout(() => { cd = null; }, 400); });
}, 200);
window.showDialog = () => { const d = document.getElementById('dlg'); d.innerHTML = '<div><span>Video löschen?</span><div role="button">Cancel</div></div>'; d.querySelector('[role=button]').addEventListener('click', () => window.__dlgCancel++); };
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
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && (v.srcObject || v.currentSrc); }' % R, timeout=15000)
    time.sleep(0.5)
    pg.evaluate('() => showDialog()'); time.sleep(0.5)
    pg.evaluate('() => { const v = document.getElementById("v"); v.currentTime = v.duration - 3; }')
    time.sleep(6)
    s = pg.evaluate('() => { const v = document.getElementById("v"); return { adv: window.__advanced, cancelled: window.__cancelled, dlg: window.__dlgCancel, src: v.src.split("/").pop(), open: !!%s.querySelector(".vp") }; }' % R)
    check('Countdown „Next reel in 3“ bei offenem Player: sofort abgebrochen, bleibt beim Reel', s['adv'] == 0 and s['cancelled'] >= 1 and s['src'] == 'reel1.webm' and s['open'], s)
    check('… anderer Dialog mit „Cancel“ (ohne Countdown) wird nicht angetippt', s['dlg'] == 0, s)
    # auch kurz nach dem Schließen (Seite steht am Ende)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(8)
    s2 = pg.evaluate('() => { const v = document.getElementById("v"); return { adv: window.__advanced, src: v.src.split("/").pop() }; }')
    check('nach dem Schließen am Ende: Seite springt nicht weiter', s2['adv'] == 0 and s2['src'] == 'reel1.webm', s2)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
