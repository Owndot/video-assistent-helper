import pwshim  # BROWSER=firefox → Tests in Firefox
# Spulen „im Bild“ (wie Samsung Internet): Beim Ziehen springt das Video selbst mit – auch beim Spiegeln
# (dann das Video der Seite). Hochwischen über die Leiste = kein Spulen → zurück an die alte Stelle.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
SCRIPT = SCRIPT.replace("seekPreview: 'thumb'", "seekPreview: 'live'")   # Einstellung „Spul-Vorschau: Im Bild“
def bar_rect(pg): return pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
def cur(pg, sel): return pg.evaluate('(s) => { const v = s === "orig" ? document.getElementById("v") : %s.querySelector(".vv"); return Math.round(v.currentTime * 10) / 10; }' % R, sel)
def drag(pg, frac_from, frac_to, which='vv', hold_check=True):
    x, y, w, h = bar_rect(pg); cy = y + h / 2
    pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R)
    f0 = pg.evaluate('(s) => { const v = s === "orig" ? document.getElementById("v") : %s.querySelector(".vv"); return v.currentTime / v.duration; }' % R, which)
    frac_to = f0 + (frac_to - frac_from); frac_from = f0   # am Punkt greifen (Ziehen spult von der aktuellen Stelle aus)
    pg.mouse.move(x + 7 + frac_from * (w - 14), cy); pg.mouse.down()
    pg.mouse.move(x + 7 + frac_to * (w - 14), cy, steps=12); time.sleep(0.9)
    during = cur(pg, which)
    box = pg.evaluate('() => { const p = %s.querySelector(".vprev"); return [p.classList.contains("on"), p.classList.contains("img"), (p.querySelector("b,span,.vpt") || p).textContent.trim(), getComputedStyle(p.querySelector(".vpbox")).display]; }' % R)
    pg.mouse.up(); time.sleep(0.6)
    drag.target = frac_to
    return during, box
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'subs.html'); time.sleep(0.5)
    pg.evaluate('() => %s.querySelector(".vv").pause()' % R); time.sleep(0.3)
    d = pg.evaluate('() => %s.querySelector(".vv").duration' % R)
    # 1) Leiste ziehen: Video steht schon WÄHREND des Ziehens an der Fingerstelle (kein kleines Bild)
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 6; }' % R); time.sleep(0.4)
    during, box = drag(pg, 0.1, 0.6)
    tg = drag.target * d
    check('Leiste ziehen: Video springt schon beim Ziehen mit (%.0f s)' % tg, abs(during - tg) < 3, [during, round(tg, 1)])
    check('… dabei nur die Zeit über der Leiste, kein kleines Vorschaubild (auch kein leerer Kasten)', box[0] and not box[1] and box[3] == 'none', box)
    after = cur(pg, 'vv')
    check('… nach dem Loslassen genau dort', abs(after - tg) < 1.5, [after, round(tg, 1)])
    # 2) Hochwischen über die Leiste: kein Spulen → zurück an die alte Stelle
    t0 = cur(pg, 'vv')
    x, y, w, h = bar_rect(pg); cy = y + h / 2; fx = x + 7 + (t0 / d) * (w - 14)
    pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R)
    pg.mouse.move(fx, cy); pg.mouse.down(); pg.mouse.move(fx + 40, cy, steps=4); time.sleep(0.5)
    pg.mouse.move(fx + 40, cy - 110, steps=6); time.sleep(0.3); pg.mouse.up(); time.sleep(0.8)
    t1 = cur(pg, 'vv')
    check('Hochwischen über die Leiste: zurück an die alte Stelle', abs(t1 - t0) < 1.5, [t0, t1])
    # 3) Wischen übers Bild (seitlich): Bild springt mit
    pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.3)
    s0 = cur(pg, 'vv')
    pg.mouse.move(100, 400); pg.mouse.down(); pg.mouse.move(300, 405, steps=12); time.sleep(0.9)
    sw = cur(pg, 'vv'); pg.mouse.up(); time.sleep(0.5)
    check('seitlich übers Bild wischen: Video springt beim Wischen mit', sw - s0 > 20, [s0, sw])
    # 4) Spiegeln (Facebook): das Video der Seite springt mit
    CLIP = open(WEB + '/clip.webm', 'rb').read()
    FBP = '<!doctype html><html><body style="margin:0"><video id=v muted autoplay playsinline src="/data/c.webm" style="width:100%;display:block"></video></body></html>'
    def fb(route):
        u = route.request.url
        if '/data/' in u:
            rg = route.request.headers.get('range', ''); n = len(CLIP)
            if rg.startswith('bytes='):
                a, _, z = rg[6:].partition('-'); a = int(a or 0); z = min(int(z) if z else n - 1, n - 1)
                route.fulfill(status=206, content_type='video/webm', body=CLIP[a:z + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, z, n), 'Accept-Ranges': 'bytes'})
            else: route.fulfill(status=200, content_type='video/webm', body=CLIP, headers={'Accept-Ranges': 'bytes'})
        else: route.fulfill(status=200, content_type='text/html', body=FBP)
    ctx.route('https://www.facebook.com/**', fb)
    pg.goto('https://www.facebook.com/reel/111222333444555/')
    pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.srcObject; }' % R, timeout=15000); time.sleep(0.8)
    dm = pg.evaluate('() => document.getElementById("v").duration')
    during, box = drag(pg, 0.1, 0.5, 'orig')
    check('gespiegelt: das Video der Seite springt beim Ziehen mit', abs(during - drag.target * dm) < 3, [during, round(drag.target * dm, 1)])
    check('… und auch hier kein Vorschau-Kasten, nur die Zeit', box[0] and box[3] == 'none', box)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
