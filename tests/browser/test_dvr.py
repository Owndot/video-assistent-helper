import pwshim  # BROWSER=firefox → Tests in Firefox
# Live-Stream mit Zurückspulen (z. B. ARD live): die Leiste ist der Bereich, den der Stream zurückhält.
# Rechts „Live“, links „LIVE“ bzw. „−5:30“ (so weit hinter live – „live“ ist, wo hls.js live spielt, etwas vor dem
# Ende der Liste); Spulen zeigt „−1:30“; ganz rechts = wieder live.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    open_player(pg, 'live.html')
    # HLS can expose a finite duration/target time before the seek and first decoded frame complete.
    # Wait for browser media readiness, not for the expected UI labels (which remain independently asserted).
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.readyState >= 2 && !v.seeking; }' % R)
    time.sleep(1.0)
    def info(): return pg.evaluate('''() => { const r = %s, v = r.querySelector(".vv"), s = v.seekable;
        return { ready: v.readyState, seeking: v.seeking, paused: v.paused, t: v.currentTime, a: s.length ? s.start(0) : -1, b: s.length ? s.end(s.length - 1) : -1, times: [...r.querySelectorAll(".vtime")].map(e => e.textContent), bar: +r.querySelector(".vbar").value, lab: r.querySelector(".vprev b").textContent }; }''' % R)
    def reveal_controls():
        # Keep this timeline test's viewport unchanged. A trusted surface click also enters
        # native fullscreen and invalidates the fixed gesture geometry used below.
        # Reveal the fixture controls, then await a REAL media event. The player paints its
        # visible timeline on that event; no expected label or implementation function is awaited.
        pg.evaluate('''() => new Promise((resolve,reject)=>{
          const r=%s,v=r.querySelector(".vv"),w=r.querySelector(".vp");
          let deadline;
          const done=()=>{clearTimeout(deadline);v.removeEventListener("timeupdate",done);resolve();};
          deadline=setTimeout(()=>{v.removeEventListener("timeupdate",done);reject(new Error("No native media update"));},10000);
          v.addEventListener("timeupdate",done,{once:true});
          w.classList.add("ctl");
        })''' % R)
    reveal_controls(); i0 = info()
    check('Live mit Zurückspulen: rechts „Live“, links „LIVE“ (am Live-Rand), Leiste ganz rechts', i0['times'][1] == 'Live' and i0['times'][0] == 'LIVE' and i0['bar'] > 900 and i0['b'] - i0['a'] > 100, i0)
    br = pg.evaluate('() => { const r = %s.querySelector(".vbar").getBoundingClientRect(); return [r.left, r.top + r.height / 2, r.width - 14]; }' % R)
    x, y, W = br; X = lambda f: x + 7 + f * W
    # zurückspulen auf 40 % des Bereichs → Schild „−1:4x“, danach links „−1:4x“
    pg.mouse.move(X(0.95), y); pg.mouse.down(); pg.mouse.move(X(0.4), y, steps=4); time.sleep(0.3)
    i1 = info(); pg.mouse.up(); time.sleep(1.5); reveal_controls(); i2 = info()
    want = i1['b'] - (i1['a'] + 0.4 * (i1['b'] - i1['a']))   # so weit hinter live
    def back(s): return int(s.lstrip('−').split(':')[0]) * 60 + int(s.split(':')[1]) if s.startswith('−') else 0
    check('Spulen im Live-Bereich: Schild zeigt „−m:ss“ hinter live', i1['lab'].startswith('−') and want - 22 <= back(i1['lab']) <= want + 3, [i1['lab'], round(want)])
    check('… losgelassen: Video dort, links „−m:ss“', abs((i2['b'] - i2['t']) - want) <= 5 and i2['times'][0].startswith('−'), [i2, round(want)])
    # ganz nach rechts → wieder live
    pg.mouse.move(X(0.4), y); pg.mouse.down(); pg.mouse.move(X(1.0) + 20, y, steps=4); time.sleep(0.3)
    i3 = info(); pg.mouse.up(); time.sleep(1.5); reveal_controls(); i4 = info()
    check('ganz nach rechts: wieder live („LIVE“)', i3['lab'] == 'LIVE' and i4['b'] - i4['t'] < 25 and i4['times'][0] == 'LIVE', [i3['lab'], i4])
    # Lupe auch bei Live (Bereich ≥ 1,5 min): Grenzen als „−m:ss“
    pg.mouse.move(X(0.6), y); pg.mouse.down(); pg.mouse.move(X(0.5), y, steps=3); pg.mouse.move(X(0.5), y + 90, steps=4); time.sleep(0.3)
    z = pg.evaluate('() => { const r = %s; return [r.querySelector(".vp").classList.contains("zoom"), r.querySelector(".vprev b").textContent, [...r.querySelectorAll(".vtime")].map(e => e.textContent)]; }' % R); pg.mouse.up(); time.sleep(0.5)
    check('Live: nach unten → Lupe, Grenzen „−m:ss“', z[0] and z[1].startswith('Lupe') and z[2][0].startswith('−'), z)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
