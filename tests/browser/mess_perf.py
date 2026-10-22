import pwshim  # BROWSER=firefox → nur eingeschränkt (CDP-Messwerte gibt es nur in Chromium)
# Messung (kein Bestehen/Durchfallen): Rechenzeit des Hauptfadens bei 4× gedrosselter CPU (wie ein Mittelklasse-Handy).
#   leer       – Seite mit laufendem Video, OHNE Skript (Grundlast)
#   Seite      – mit Skript, Player zu (Knopf am Video)
#   Player     – Player offen, Leisten aus (normales Schauen)
#   Leisten    – Player offen, Leisten an
# je 8 s: Skript-, Layout-, Stil- und Gesamtzeit in ms; dazu Start (Skript ausführen) und Tipp → Leisten sichtbar.
# Aufruf: python3 mess_perf.py [vergleich.js]
import time, sys, json
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
WIN = 8
def metrics(cdp):
    m = {x['name']: x['value'] for x in cdp.send('Performance.getMetrics')['metrics']}
    return m
FPS0 = '''() => { window.__fr = 0; window.__frOn = true; const f = () => { if (!window.__frOn) return; window.__fr++; requestAnimationFrame(f); }; requestAnimationFrame(f);
  const v = (window.__evpRoot && window.__evpRoot.querySelector(".vv")) || document.getElementById("v"); const q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null;
  window.__dq = q ? [q.droppedVideoFrames, q.totalVideoFrames] : [0, 0]; window.__dv = v; }'''
FPS1 = '''() => { window.__frOn = false; const q = window.__dv.getVideoPlaybackQuality ? window.__dv.getVideoPlaybackQuality() : null;
  return [window.__fr, q ? q.droppedVideoFrames - window.__dq[0] : 0, q ? q.totalVideoFrames - window.__dq[1] : 0]; }'''
def window(pg, cdp, secs=WIN):
    pg.evaluate(FPS0)
    a = metrics(cdp); time.sleep(secs); z = metrics(cdp)
    fr = pg.evaluate(FPS1)
    d = lambda k: round((z.get(k, 0) - a.get(k, 0)) * 1000)
    return {'Skript': d('ScriptDuration'), 'Layout': d('LayoutDuration'), 'Stil': d('RecalcStyleDuration'), 'gesamt': d('TaskDuration'),
            'Bilder/s': round(fr[0] / secs), 'Video verworfen': '%d/%d' % (fr[1], fr[2])}
def run(script, label):
    out = {}
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE)
        if script: ctx.add_init_script(HLS); ctx.add_init_script(script)
        pg = ctx.new_page(); cdp = ctx.new_cdp_session(pg)
        cdp.send('Performance.enable'); cdp.send('Emulation.setCPUThrottlingRate', {'rate': 4})
        t0 = time.time()
        pg.goto(B + 'subs.html')
        pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=30000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.5)
        if not script:
            out['leer'] = window(pg, cdp)
        else:
            out['Seite'] = window(pg, cdp)
            t = time.time()
            pg.evaluate('() => %s.querySelector(".pill .pb:not(.dl)").click()' % R)
            pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.3; }' % R, timeout=30000)
            out['öffnen ms'] = round((time.time() - t) * 1000)
            time.sleep(4)   # Leisten blenden sich aus
            pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.5)
            out['Player'] = window(pg, cdp)
            pg.evaluate('() => %s.querySelector(".vp").classList.add("ctl")' % R)
            # Leisten dauerhaft an (Ausblenden verhindern)
            pg.evaluate('() => { const w = %s.querySelector(".vp"); window.__keep = setInterval(() => w.classList.add("ctl"), 300); }' % R)
            out['Leisten'] = window(pg, cdp)
            # dasselbe im Design „Schlicht“ (nur Milchglas, ohne Lupe)
            pg.evaluate('() => { const h = %s.host; h.classList.add("flat"); }' % R)
            out['Leisten schlicht'] = window(pg, cdp)
            pg.evaluate('() => { const h = %s.host; h.classList.remove("flat"); }' % R)
            pg.evaluate('() => clearInterval(window.__keep)')
            # Tipp → Leisten sichtbar (Zeit bis die Klasse gesetzt ist)
            pg.evaluate('() => %s.querySelector(".vp").classList.remove("ctl")' % R); time.sleep(0.8)
            pg.evaluate('''() => { const w = %s.querySelector(".vp"); window.__tapAt = 0; window.__shownAt = 0;
                new MutationObserver(() => { if (!window.__shownAt && w.classList.contains("ctl")) window.__shownAt = performance.now(); }).observe(w, { attributes: true }); }''' % R)
            pg.evaluate('() => { window.__tapAt = performance.now(); }'); pg.mouse.click(200, 300); time.sleep(1.2)
            out['Tipp→Leisten ms'] = pg.evaluate('() => window.__shownAt ? Math.round(window.__shownAt - window.__tapAt) : -1')
        b.close()
    return out
cur = open('../../userscript/video-assistent.user.js', encoding='utf-8').read()
res = {'ohne Skript': run('', 'leer'), 'aktuell': run(cur, 'aktuell')}
if len(sys.argv) > 1: res['vergleich'] = run(open(sys.argv[1], encoding='utf-8').read(), 'vergleich')
srv.shutdown()
for k, v in res.items():
    print(k)
    for a, b2 in v.items(): print('   %-18s %s' % (a, json.dumps(b2, ensure_ascii=False)))
