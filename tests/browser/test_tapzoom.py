import pwshim  # BROWSER=firefox → Tests in Firefox
# Doppeltipp in der Mitte (wie Telegram): 3× um die getippte Stelle (hochkant mindestens bildschirmfüllend), zum Finger
# hin; noch einmal = ganzes Bild. Im Zoom verschiebt ein Finger das Bild (Gummiband an der Kante, federt zurück).
import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; results = []
def check(n, c, i=''): results.append(bool(c)); print(('OK   ' if c else 'FAIL ') + n + ('  – ' + str(i) if i != '' else ''))
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto('http://127.0.0.1:8775/direct.html')
    pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 1 && v.videoWidth > 0; }' % R, timeout=15000)
    time.sleep(0.5)
    def dtap(x, y):
        pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.3)
        pg.mouse.click(x, y); time.sleep(0.08); pg.mouse.click(x, y); time.sleep(0.7)
    state = lambda: pg.evaluate('''() => { const v = %s.querySelector(".vv"), w = %s.querySelector(".vp"); const r = v.getBoundingClientRect(), wr = w.getBoundingClientRect();
        const sc = Math.min(r.width / v.videoWidth, r.height / v.videoHeight);   // sichtbares Bild im (vergrößerten) Element
        const ch = v.videoHeight * sc, cw = v.videoWidth * sc;
        return { paused: v.paused, top: Math.round(r.top + (r.height - ch) / 2 - wr.top), bottom: Math.round(r.top + (r.height + ch) / 2 - wr.top),
                 left: Math.round(r.left + (r.width - cw) / 2 - wr.left), right: Math.round(r.left + (r.width + cw) / 2 - wr.left), H: Math.round(wr.height), W: Math.round(wr.width) }; }''' % (R, R))
    s0 = state(); print('   vorher:', s0)
    dtap(240, 420)                                   # rechts der Mitte
    s1 = state(); print('   Zoom:', s1)
    check('hochkant: Doppeltipp Mitte – Bild füllt die Höhe', s1['top'] <= 1 and s1['bottom'] >= s1['H'] - 1, s1)
    check('läuft weiter (kein Play/Pause)', not s1['paused'], s1['paused'])
    check('zum Finger hin: rechte Seite des Bildes sichtbar', (s1['left'] + s1['right']) / 2 < s1['W'] / 2 - 40, [s1['left'], s1['right']])
    check('keine schwarzen Ränder seitlich', s1['left'] <= 0 and s1['right'] >= s1['W'], [s1['left'], s1['right']])
    dtap(200, 400)
    s2 = state(); print('   zurück:', s2)
    check('noch einmal: ganzes Bild', abs(s2['top'] - s0['top']) <= 1 and abs(s2['left'] - s0['left']) <= 1, s2)
    dtap(160, 400)
    s3 = state()
    pg.set_viewport_size({'width': 800, 'height': 400}); time.sleep(0.9)
    s4 = state(); print('   gedreht:', s4)
    check('gedreht: Doppeltipp-Zoom aufgehoben', s3['bottom'] - s3['top'] > 700 and s4['left'] >= 0 and s4['right'] <= s4['W'] and s4['top'] >= -1, [s3, s4])
    pg.set_viewport_size({'width': 640, 'height': 360}); time.sleep(0.9)   # gleiches Seitenverhältnis wie das Video
    dtap(320, 180)
    s5 = state(); print('   quer:', s5)
    check('quer: Doppeltipp Mitte = 3× Zoom, läuft weiter', abs((s5['right'] - s5['left']) / s5['W'] - 3) < 0.05 and not s5['paused'], s5)
    vol0 = pg.evaluate('() => %s.querySelector(".vv").volume' % R)
    # ein Finger verschiebt (nach links ziehen = mehr von rechts sehen), Lautstärke bleibt
    pg.mouse.move(320, 200); pg.mouse.down(); pg.mouse.move(220, 200, steps=10); time.sleep(0.25); pg.mouse.up(); time.sleep(0.6)
    s6 = state(); print('   verschoben:', s6)
    check('Zoom: ein Finger verschiebt das Bild', abs((s6['left'] - s5['left']) + 100) < 15, [s5['left'], s6['left']])
    check('Zoom: Verschieben ändert die Lautstärke nicht', pg.evaluate('() => %s.querySelector(".vv").volume' % R) == vol0)
    # weit über die Kante ziehen: gibt nach, federt beim Loslassen zurück (keine schwarzen Ränder)
    pg.mouse.move(30, 180); pg.mouse.down(); pg.mouse.move(620, 180, steps=10); time.sleep(0.25); pg.mouse.up(); time.sleep(0.6)   # bis an den linken Rand
    pre = state()
    pg.mouse.move(30, 180); pg.mouse.down(); pg.mouse.move(330, 180, steps=10)                                        # und weiter, über die Kante
    mid = state(); free = pre['left'] + 300   # so weit wäre es ohne Widerstand
    print('   ohne Widerstand wären es', free, 'px, tatsächlich', mid['left']); time.sleep(0.25); pg.mouse.up(); time.sleep(0.7)
    s7 = state(); print('   über die Kante:', mid, '->', s7)
    check('über die Kante: gibt mit Widerstand nach (⅓)', free > 30 and abs(mid['left'] - free / 3) < 8, [free, mid['left']])
    check('beim Loslassen zurück an die Kante', s7['left'] <= 0 and s7['top'] <= 0 and s7['right'] >= s7['W'] and s7['bottom'] >= s7['H'], s7)
    # Schwung: schnell wischen und loslassen – gleitet weiter als der Finger
    f0 = state()['left']
    pg.mouse.move(400, 180); pg.mouse.down()
    for x in (380, 360, 340, 320, 300): pg.mouse.move(x, 180); time.sleep(0.016)
    pg.mouse.up(); time.sleep(0.6)
    f1 = state()['left']; print('   Schwung: Finger 100 px, Bild', f0 - f1, 'px')
    check('Schwung: gleitet weiter als der Finger', f0 - f1 > 130, [f0, f1])
    dtap(320, 180)
    s8 = state()
    check('noch einmal: ganzes Bild', s8['left'] >= -1 and s8['right'] <= s8['W'] + 1, s8)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
