import pwshim  # BROWSER=firefox → Tests in Firefox
# Sieht man wirklich etwas? Ambient-Licht in den Rändern und das Bild der Spul-Vorschau – gemessen an den Pixeln
# (die anderen Tests prüfen nur, ob die Teile eingeschaltet sind).
import time, json, sys
from PIL import Image, ImageStat
exec(open('test_v8.py').read().split("results = []")[0])
SCRIPT = SCRIPT.replace("seekPreview: 'live'", "seekPreview: 'thumb'")   # dieser Test prüft das kleine Vorschaubild
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
STATS = '''(el) => { const c = document.createElement('canvas'); c.width = 48; c.height = 27; const g = c.getContext('2d');
  try { g.drawImage(el, 0, 0, 48, 27); } catch (e) { return ['Fehler ' + e.name]; }
  let d; try { d = g.getImageData(0, 0, 48, 27).data; } catch (e) { return ['gesperrt ' + e.name]; }
  let s = 0, s2 = 0, n = d.length / 4; for (let i = 0; i < d.length; i += 4) { const y = (d[i] + d[i+1] + d[i+2]) / 3; s += y; s2 += y * y; }
  const m = s / n; return [Math.round(m), Math.round(Math.sqrt(Math.max(0, s2 / n - m * m)))]; }'''
GRID = '''(el) => { const c = document.createElement('canvas'); c.width = 16; c.height = 9; const g = c.getContext('2d');
  try { g.drawImage(el, 0, 0, 16, 9); return Array.from(g.getImageData(0, 0, 16, 9).data).filter((_, i) => i % 4 === 1); } catch (e) { return []; } }'''
def same_frame(pg, _t=None):
    # Liefert das Vorschau-Video an seiner Stelle das richtige Bild? (Hauptvideo angehalten dorthin; die gemerkten
    # Bilder in Chrome tragen nur ungefähre Zeiten, darum das Video selbst – in Firefox-Handy ist es das gezeigte Bild)
    # Robust: erst vergleichen, wenn das Vorschau-Video wirklich ein Bild an seiner Stelle hat (nicht mehr springt,
    # Bilddaten da, Zeit steht still). Weicht es dann ab, noch EIN zweiter Blick nach kurzer Pause – ein falsches Bild
    # bleibt falsch, ein noch ladendes ist dann da.
    READY = """() => { const v = %s.querySelector(".vprev video"); if (!v || v.seeking || v.readyState < 2) return false;
        const t = v.currentTime; if (window.__pvT !== t) { window.__pvT = t; window.__pvAt = performance.now(); return false; }
        return performance.now() - window.__pvAt > 250; }""" % R
    d = 999
    for attempt in range(2):
        try: pg.wait_for_function(READY, timeout=15000, polling=100)
        except Exception: pass
        a, t = pg.evaluate('() => { const v = %s.querySelector(".vprev video"); return [(%s)(v), v.currentTime]; }' % (R, GRID))
        pg.evaluate('(t) => new Promise((res) => { const v = %s.querySelector(".vv"); v.pause(); v.addEventListener("seeked", () => setTimeout(res, 150), { once: true }); v.currentTime = t; setTimeout(res, 3000); })' % R, t)
        b = pg.evaluate('() => (%s)(%s.querySelector(".vv"))' % (GRID, R))
        d = round(sum(abs(x - y) for x, y in zip(a, b)) / len(a), 1) if a and b else 999
        if d < 12: return d
        if attempt == 0: print('   (Abweichung %s – Vorschau lud wohl noch, zweiter Blick)' % d); time.sleep(1.0)
    return d
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
        at = pg.evaluate('() => %s.querySelector(".vamb").tagName' % R)
        print('   %s Ambient-Element: %s' % (tag, at))
        import os
        if os.environ.get('BROWSER', '').lower() == 'firefox':
            check(tag + ': Firefox nutzt den Handy-Weg (Ambient als Video)', at == 'VIDEO', at)
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
        pg.mouse.move(br[0] + br[2] * 0.1, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.6, y, steps=3)
        # (bis das richtige Bild da ist – solange es lädt, ist ein gemerktes Bild abgedunkelt zu sehen)
        try: pg.wait_for_function('() => { const p = %s.querySelector(".vprev"); return p.classList.contains("img") && !p.classList.contains("stale"); }' % R, timeout=8000)
        except Exception: pass
        time.sleep(1.2)
        pv = pg.evaluate('() => { const pr = %s.querySelector(".vprev"), v = pr.querySelector("video"); return [v.readyState, v.currentTime.toFixed(1), v.videoWidth, pr.classList.contains("img"), (%s)(v), (%s)(pr.classList.contains("live") ? v : pr.querySelector("canvas"))]; }' % (R, STATS, STATS))
        pg.screenshot(path=OUT + '/pix_prev_%s.png' % tag)
        d1 = same_frame(pg, float(pv[1]))
        pg.mouse.up()
        print('   %s Vorschau: Abweichung zum echten Bild an der Stelle %s' % (tag, d1))
        check(tag + ': Vorschaubild passt zur Stelle', d1 < 12, d1)
        pg.evaluate('() => %s.querySelector(".vv").play()' % R)
        print('   %s Vorschau: Video bereit %s, Zeit %s, Breite %s, Bild-Flag %s, Vorschau-Video %s, gezeigtes Bild %s' % (tag, *pv))
        if os.environ.get('BROWSER', '').lower() == 'firefox':
            check(tag + ': Firefox zeigt das Vorschau-Video direkt', pg.evaluate('() => %s.querySelector(".vprev").classList.contains("live")' % R))
        check(tag + ': Vorschau zeigt ein echtes Bild', pv[3] and len(pv[5]) == 2 and pv[5][0] > 8 and pv[5][1] > 4, pv[5])
        # Nach längerem Zuschauen (Firefox hält unsichtbare Videos nach ~10 s an): noch einmal an eine neue Stelle
        time.sleep(13)
        a2 = pg.evaluate('() => (%s)(%s.querySelector(".vamb"))' % (STATS, R))
        pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R); time.sleep(0.3)
        # (zurück ziehen: Ziehen spult von der aktuellen Stelle aus – vorwärts landete es am Ende, wo der Bildvergleich wackelt)
        pg.mouse.move(br[0] + br[2] * 0.33, y); pg.mouse.down(); pg.mouse.move(br[0] + br[2] * 0.15, y, steps=3)
        try: pg.wait_for_function('() => { const p = %s.querySelector(".vprev"); return p.classList.contains("img") && !p.classList.contains("stale"); }' % R, timeout=8000)
        except Exception: pass
        time.sleep(1.5)
        pv2 = pg.evaluate('() => { const pr = %s.querySelector(".vprev"), v = pr.querySelector("video"); return [v.readyState, v.currentTime.toFixed(1), pr.dataset.t, (%s)(v), (%s)(pr.classList.contains("live") ? v : pr.querySelector("canvas"))]; }' % (R, STATS, STATS))
        want2 = pg.evaluate('() => %s.querySelector(".vbar").value / 1000 * %s.querySelector(".vv").duration' % (R, R))
        pg.screenshot(path=OUT + '/pix_prev_spaet_%s.png' % tag)
        d2 = same_frame(pg, float(pv2[1]))
        pg.mouse.up()
        print('   %s nach 13 s: Abweichung zum echten Bild %s' % (tag, d2))
        check(tag + ': nach 13 s Vorschaubild passt zur Stelle', d2 < 12, d2)
        print('   %s nach 13 s: Ambient %s · Vorschau bereit %s, Zeit %s, gezeigt bei %s, Vorschau-Video %s, gezeigtes Bild %s' % (tag, a2, *pv2))
        check(tag + ': nach 13 s Ambient-Bild hat Inhalt', len(a2) == 2 and a2[0] > 8, a2)
        check(tag + ': nach 13 s Vorschau an der neuen Stelle', pv2[2] is not None and abs(float(pv2[2]) - float(pv2[1])) < 3 and abs(float(pv2[2]) - want2) < 3 and len(pv2[4]) == 2 and pv2[4][1] > 4, pv2)
        ctx.close()
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(results), 'von', len(results))
