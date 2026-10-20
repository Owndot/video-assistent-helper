exec(open('test_v8.py').read().split("results = []")[0])
import urllib.parse
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
# Nachgebauter Helfer: GM_xmlhttpRequest geht an Python statt an die echte App
SHIM = """window.GM_xmlhttpRequest = function (o) {
  if (!/^http:\\/\\/127\\.0\\.0\\.1:47811\\//.test(o.url)) { setTimeout(() => o.onerror && o.onerror({}), 0); return; }
  window.__helperReq(o.url, (o.headers || {})['X-EVP-Key'] || '').then(
    (t) => t === null ? (o.onerror && o.onerror({})) : o.onload({ status: 200, responseText: t }),
    () => o.onerror && o.onerror({}));
};"""
state = {'on': True, 'vol': 0.4, 'max': 15, 'bright': 0.6, 'calls': []}
def helper_req(url, key):
    if not state['on'] or key != 'evp-1': return None
    u = urllib.parse.urlparse(url); q = dict(urllib.parse.parse_qsl(u.query))
    state['calls'].append((u.path, q.get('v')))
    if u.path == '/vol' and 'v' in q: state['vol'] = round(float(q['v']) * state['max']) / state['max']
    if u.path == '/bright' and 'v' in q: state['bright'] = float(q['v'])
    return json.dumps({'ok': True, 'overlay': True, 'write': False, 'vol': state['vol'], 'volMax': state['max'], 'bright': state['bright']})
def calls(path): return [c for c in state['calls'] if c[0] == path]

with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.expose_function('__helperReq', helper_req)
    ctx.add_init_script(SHIM); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    errors = []
    page = ctx.new_page(); page.on('pageerror', lambda e: errors.append(str(e)))
    def openp():
        page.goto('http://127.0.0.1:8775/direct.html')
        page.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        page.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
        page.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        page.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.5; }' % R, timeout=15000)
        time.sleep(0.6)
    openp()
    check('Beim Öffnen beim Helfer nachgefragt', len(calls('/ping')) == 1, state['calls'])
    # rechts hoch wischen: Lautstärke des Handys steigt, ab 40 %
    page.mouse.move(330, 600); page.mouse.down(); page.mouse.move(330, 480, steps=8); page.mouse.up(); time.sleep(0.5)
    vs = [float(v) for _, v in calls('/vol')]
    vv = page.evaluate('() => %s.querySelector(".vv").volume' % R)
    check('Lautstärke geht ans Handy (ab dem aktuellen Wert)', vs and abs(vs[0] - 0.4) < 0.15 and vs[-1] > 0.65, vs)
    check('Video zwischen den Stufen etwas leiser (fein)', 0.3 < vv <= 1, vv)
    check('Nicht jede Bewegung gesendet (gedrosselt)', len(vs) <= 8, len(vs))
    side = page.evaluate('() => %s.querySelector(".vside b").textContent' % R)
    check('Anzeige in 1-%-Schritten (nicht nur Handy-Stufen)', side.endswith('%') and int(side[:-1]) % 7 != 0 or int(side[:-1]) not in (0,7,13,20,27,33,40,47,53,60,67,73,80,87,93,100), side)
    # feine Zwischenwerte: kleine Bewegung innerhalb einer Stufe ändert nur das Video ein wenig
    vals = []
    page.mouse.move(330, 600); page.mouse.down(); page.mouse.move(330, 580, steps=2)
    for i in range(8):
        page.mouse.move(330, 580 - 5 * (i + 1)); page.wait_for_timeout(60)
        vals.append(int(page.evaluate('() => %s.querySelector(".vside b").textContent' % R)[:-1]))
    page.mouse.up(); page.wait_for_timeout(300)
    d = [b - a for a, b in zip(vals, vals[1:])]
    print('   fein:', vals)
    check('Feine Schritte: je 5 Pixel 1–2 %', all(x in (0, 1, 2) for x in d) and vals[-1] > vals[0], vals)
    # links runter: Helligkeit des Handys sinkt (gefühlt gleichmäßig wie Androids Regler)
    page.mouse.move(60, 300); page.mouse.down(); page.mouse.move(60, 420, steps=8); page.mouse.up(); page.wait_for_timeout(400)
    bs = [float(v) for _, v in calls('/bright')]
    check('Helligkeit geht ans Handy und sinkt', bs and bs[0] <= 0.61 and bs[-1] < bs[0] - 0.3, bs)
    side = page.evaluate('() => %s.querySelector(".vside b").textContent' % R)
    print('   Anzeige nach 120 px:', side)
    # bis ganz unten: dunkelste Stufe, aber noch nicht Auto
    n_end = len(calls('/end'))
    page.mouse.move(60, 200); page.mouse.down(); page.mouse.move(60, 380, steps=12); page.mouse.up(); page.wait_for_timeout(400)
    side = page.evaluate('() => %s.querySelector(".vside b").textContent' % R)
    check('Ganz unten: dunkelste Stufe (1 %), noch nicht Auto', state['bright'] <= 0.01 and side == '0%' and len(calls('/end')) == n_end, [state['bright'], side])
    flt = page.evaluate('() => [...%s.querySelectorAll("*")].filter(e => e.className && /dim/.test(e.className)).map(e => getComputedStyle(e).opacity)' % R)
    check('Video wird dabei nicht zusätzlich abgedunkelt', all(float(x) == 0 for x in flt), flt)
    # noch weiter runter: Auto
    page.mouse.move(60, 200); page.mouse.down(); page.mouse.move(60, 290, steps=8)
    side = page.evaluate('() => %s.querySelector(".vside b").textContent' % R)
    page.screenshot(path=OUT + '/helper_auto.png')
    page.mouse.up(); page.wait_for_timeout(400)
    check('Noch weiter runter: Auto (Helfer gibt Helligkeit ans System)', side == 'Auto' and len(calls('/end')) == n_end + 1, [side, len(calls('/end')) - n_end])
    # wieder hoch: fest ab der aktuellen Helligkeit
    nb = len(calls('/bright'))
    page.mouse.move(60, 500); page.mouse.down(); page.mouse.move(60, 440, steps=6); page.mouse.up(); page.wait_for_timeout(400)
    side = page.evaluate('() => %s.querySelector(".vside b").textContent' % R)
    check('Aus Auto wieder hoch: Helligkeit wieder fest', len(calls('/bright')) > nb and side.endswith('%'), [side, len(calls('/bright')) - nb])
    # Lautstärketaste am Handy: Lebenszeichen holt den neuen Wert
    print('hidden?', page.evaluate('() => [document.hidden, document.visibilityState]'))
    state['vol'] = 0.2; page.wait_for_timeout(3000)
    check('Lebenszeichen alle 2,5 s', len(calls('/beat')) >= 1, len(calls('/beat')))
    page.mouse.move(330, 500); page.mouse.down(); page.mouse.move(330, 470, steps=4); page.mouse.up(); time.sleep(0.4)
    vs2 = [float(v) for _, v in calls('/vol')][-1]
    check('Nach Lautstärketaste geht es vom neuen Wert weiter', abs(vs2 - 0.27) < 0.08, vs2)
    # Schließen: Helligkeit zurück
    page.mouse.click(200, 300); time.sleep(0.4)
    page.locator('button[aria-label="Schließen"]').click(); time.sleep(0.6)
    check('Schließen: Helfer gibt Helligkeit zurück', calls('/end'), calls('/end'))
    # Ohne Helfer: wie bisher (Video wird abgedunkelt)
    state['on'] = False; state['calls'] = []
    openp()
    page.mouse.move(60, 300); page.mouse.down(); page.mouse.move(60, 560, steps=8); page.mouse.up(); time.sleep(0.5)
    flt = page.evaluate('() => [...%s.querySelectorAll("*")].filter(e => e.className && /dim/.test(e.className)).map(e => getComputedStyle(e).opacity)' % R)
    check('Ohne Helfer: Video wird wie bisher abgedunkelt', any(float(x) > 0.1 for x in flt), flt)
    print('Fehler der Seite:', errors)
    print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
    b.close()
