import pwshim  # BROWSER=firefox → Tests in Firefox
# Sieht man wirklich etwas? Ambient-Licht in den Rändern und das Bild der Spul-Vorschau – gemessen an den Pixeln
# (die anderen Tests prüfen nur, ob die Teile eingeschaltet sind).
import time, json, sys
from PIL import Image, ImageStat
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
STATS = '''(el) => { const c = document.createElement('canvas'); c.width = 48; c.height = 27; const g = c.getContext('2d');
  try { g.drawImage(el, 0, 0, 48, 27); } catch (e) { return ['Fehler ' + e.name]; }
  let d; try { d = g.getImageData(0, 0, 48, 27).data; } catch (e) { return ['gesperrt ' + e.name]; }
  let s = 0, s2 = 0, n = d.length / 4; for (let i = 0; i < d.length; i += 4) { const y = (d[i] + d[i+1] + d[i+2]) / 3; s += y; s2 += y * y; }
  const m = s / n; return [Math.round(m), Math.round(Math.sqrt(Math.max(0, s2 / n - m * m)))]; }'''
def band(path, box):
    im = Image.open(path).convert('L').crop(box); return round(ImageStat.Stat(im).mean[0], 1)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for tag, url in [('Datei', 'direct.html'), ('Stream', 'hls2.html')]:
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=1)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/' + url)
        pg.wait_for_function('document.getElementById("v").currentTime > 1.5', timeout=20000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && isFinite(v.duration); }' % R, timeout=20000)
        time.sleep(2.0)
        # --- Ambient: Rand über dem Video mit und ohne Ambient-Licht ---
        vr = pg.evaluate('() => { const r = %s.querySelector(".vv").getBoundingClientRect(); const v = %s.querySelector(".vv"); const sc = Math.min(r.width / v.videoWidth, r.height / v.videoHeight); return [Math.round((r.height - v.videoHeight * sc) / 2)]; }' % (R, R))
        top = max(10, vr[0] - 10)
        box = (40, max(0, top - 60), 360, top)   # Streifen knapp über dem Bild
        pg.screenshot(path=OUT + '/pix_amb_an_%s.png' % tag)
        a_on = band(OUT + '/pix_amb_an_%s.png' % tag, box)
        ac = pg.evaluate('() => (%s)(%s.querySelector(".vamb"))' % (STATS, R))
        pg.evaluate('() => %s.querySelector(".vp").classList.remove("amb")' % R); time.sleep(1.2)
        pg.screenshot(path=OUT + '/pix_amb_aus_%s.png' % tag)
        a_off = band(OUT + '/pix_amb_aus_%s.png' % tag, box)
        pg.evaluate('() => %s.querySelector(".vp").classList.add("amb")' % R); time.sleep(1.0)
        print('   %s Ambient: Rand hell an %s / aus %s, Ambient-Bild (Mittel, Streuung) %s, Videorand %s px' % (tag, a_on, a_off, ac, vr[0]))
        check(tag + ': Ambient-Bild hat Inhalt', len(ac) == 2 and ac[0] > 8, ac)
        check(tag + ': Ambient-Licht sichtbar im Rand', a_on - a_off > 3, [a_on, a_off])
        # --- Vorschau beim Spulen ---
        pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.3)
        br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }' % R)
        y = br[1] + br[3] / 2
        pg.mouse.move(br[0] + br[2] * 0.1, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.6, y, steps=8)
        try: pg.wait_for_function('() => %s.querySelector(".vprev").classList.contains("img")' % R, timeout=6000)
        except Exception: pass
        time.sleep(1.2)
        pv = pg.evaluate('() => { const pr = %s.querySelector(".vprev"), v = pr.querySelector("video"); return [v.readyState, v.currentTime.toFixed(1), v.videoWidth, pr.classList.contains("img"), (%s)(v), (%s)(pr.querySelector("canvas"))]; }' % (R, STATS, STATS))
        pg.screenshot(path=OUT + '/pix_prev_%s.png' % tag)
        pg.mouse.up()
        print('   %s Vorschau: Video bereit %s, Zeit %s, Breite %s, Bild-Flag %s, Vorschau-Video %s, gezeigtes Bild %s' % (tag, *pv))
        check(tag + ': Vorschau zeigt ein echtes Bild', pv[3] and len(pv[5]) == 2 and pv[5][0] > 8 and pv[5][1] > 4, pv[5])
        ctx.close()
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(results), 'von', len(results))
