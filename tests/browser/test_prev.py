import pwshim  # BROWSER=firefox → Tests in Firefox
import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok))); print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for tag, page_url, vw in [('Datei', 'direct.html', {'width': 400, 'height': 800}), ('Stream', 'hls2.html', {'width': 400, 'height': 800}), ('Datei quer', 'direct.html', {'width': 860, 'height': 400})]:
        ctx = b.new_context(viewport=vw, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        errors = []
        pg = ctx.new_page(); pg.on('pageerror', lambda e: errors.append(str(e)))
        pg.goto('http://127.0.0.1:8775/' + page_url)
        pg.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && isFinite(v.duration); }' % R, timeout=20000)
        time.sleep(0.6)
        pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.3)
        br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        y = br[1] + br[3] / 2
        if tag == 'Stream':   # hängender Sprung (kein „seeked“) – der Wächter muss die Vorschau trotzdem nachziehen
            pg.evaluate('''() => { const pv = %s.querySelector(".vprev video"); let n = 0;
                pv.addEventListener("seeked", (e) => { if (n++ < 3) e.stopImmediatePropagation(); }, true); }''' % R)
        pg.mouse.move(br[0] + br[2] * 0.1, y); pg.mouse.down()
        pg.mouse.move(br[0] + br[2] * 0.4, y, steps=6); pg.mouse.move(br[0] + br[2] * 0.7, y, steps=6)
        try:
            pg.wait_for_function('() => %s.querySelector(".vprev").classList.contains("img")' % R, timeout=6000)
        except Exception: pass
        # Stream-Vorschau braucht auf langsamen Rechnern länger für das letzte Bild: warten, bis sie aufholt (max. 5 s)
        try:
            pg.wait_for_function('''() => { const pr = %s.querySelector(".vprev"), pv = pr.querySelector("video"), v = %s.querySelector(".vv"), bar = %s.querySelector(".vbar");
                const t = pr.dataset.t != null ? parseFloat(pr.dataset.t) : pv.currentTime; return Math.abs(t - bar.value / 1000 * v.duration) < 2.5; }''' % (R, R, R), timeout=5000)
        except Exception: pass
        time.sleep(0.6)
        info = pg.evaluate('''() => { const pr = %s.querySelector(".vprev"); const pv = pr.querySelector("video"); const v = %s.querySelector(".vv");
            const r = pr.getBoundingClientRect(); const bar = %s.querySelector(".vbar"); const shownT = pr.dataset.t != null ? parseFloat(pr.dataset.t) : pv.currentTime;
            return { on: pr.classList.contains("on"), img: pr.classList.contains("img"), t: shownT, want: bar.value / 1000 * v.duration,
                     cx: r.left + r.width / 2, bottom: r.bottom, barTop: bar.getBoundingClientRect().top, label: pr.querySelector("b").textContent,
                     main: v.currentTime, w: Math.round(r.width) }; }''' % (R, R, R))
        pg.screenshot(path=OUT + '/prev_%s.png' % tag.replace(' ', '_'))
        thumbx = br[0] + 7 + 0.7 * (br[2] - 14)
        check(tag + ': Vorschau erscheint mit Bild', info['on'] and info['img'], info)
        check(tag + ': Bild zeigt die Fingerstelle', abs(info['t'] - info['want']) < 2.5, [round(info['t'], 1), round(info['want'], 1), info['label']])
        check(tag + ': sitzt über dem Punkt', info['bottom'] <= info['barTop'] and abs(info['cx'] - thumbx) < 40, [round(info['cx']), round(thumbx)])
        check(tag + ': Hauptvideo springt erst beim Loslassen', abs(info['main'] - info['want']) > 3, round(info['main'], 1))
        pg.mouse.up(); time.sleep(0.5)
        aft = pg.evaluate('() => [%s.querySelector(".vprev").classList.contains("on"), %s.querySelector(".vv").currentTime]' % (R, R))
        check(tag + ': Loslassen: Vorschau weg, Video an der Stelle', (not aft[0]) and abs(aft[1] - info['want']) < 3, [aft[0], round(aft[1], 1)])
        if errors: print('Fehler:', errors[:3])
        ctx.close()
    b.close()
srv.shutdown()
f = [r for r in results if not r[1]]; print('ERGEBNIS:', len(results)-len(f), 'von', len(results)); sys.exit(1 if f else 0)
