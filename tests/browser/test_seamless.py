import pwshim  # BROWSER=firefox → Tests in Firefox
# Nahtloser Start (alle Seiten): Beim Öffnen zeigt der Player sofort das LAUFENDE Bild der Seite (gespiegelt, Ton von
# der Seite), bis die eigene Kopie an derselben Stelle läuft – dann wird unmerklich umgeschaltet. Die sichtbare Zeit
# springt dabei weder zurück noch vor. Auch bei langsamem Server. Stand das Video, bleibt es beim Standbild.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
SAMPLE = '''() => { const R = %s, o = document.getElementById("v"); window.__seen = []; window.__swapAt = 0;
  const iv = setInterval(() => { const v = R.querySelector(".vv"); if (!v) return; const br = R.querySelector(".vbridge");
    if (!br && !window.__swapAt) window.__swapAt = performance.now();
    const sp = R.querySelector(".vspin");
    window.__seen.push([performance.now(), br ? o.currentTime : v.currentTime, !!br, o.paused, o.muted, v.muted, !!(sp && sp.classList.contains("on") && getComputedStyle(sp).opacity !== "0")]); }, 20);
  setTimeout(() => clearInterval(iv), 9000); }''' % R
def jumps(seen):
    back = fwd = 0
    for a, b in zip(seen, seen[1:]):
        dt = (b[0] - a[0]) / 1000; dv = b[1] - a[1]
        back = max(back, -dv); fwd = max(fwd, dv - dt)
    return round(back, 2), round(fwd, 2)
def run(pg, slow_ms):
    pg.goto(B + 'direct.html'); pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    SLOW[0] = slow_ms
    pg.evaluate('() => { %s; %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click(); }' % ('(' + SAMPLE + ')()', R))
    time.sleep(0.15)
    early = pg.evaluate('() => { const br = %s.querySelector(".vbridge"), o = document.getElementById("v"); return [!!br, !o.paused, !o.muted]; }' % R)
    pg.wait_for_function('() => !%s.querySelector(".vbridge")' % R, timeout=15000); time.sleep(0.8)
    seen = pg.evaluate('() => window.__seen')
    after = pg.evaluate('() => { const v = %s.querySelector(".vv"), o = document.getElementById("v"); return [!v.paused, !v.muted, o.paused, o.muted, Math.round(v.currentTime * 10) / 10]; }' % R)
    SLOW[0] = 0
    return early, seen, after
SLOW = [0]
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    ctx.route('**/clip.webm*', lambda r: (time.sleep(SLOW[0] / 1000) if SLOW[0] else None, r.continue_())[1])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    for slow, tag in ((0, 'normal'), (1500, 'langsamer Server')):
        early, seen, after = run(pg, slow)
        check('%s: gleich das laufende Bild der Seite, Ton weiter von der Seite' % tag, early == [True, True, True], early)
        bk, fw = jumps(seen)
        nb = sum(1 for s in seen if s[2])
        check('%s: umgeschaltet ohne Sprung (zurück %.2f s, vor %.2f s)' % (tag, bk, fw), bk < 0.2 and fw < 0.35 and nb > 2, [bk, fw, nb, len(seen)])
        spun = sum(1 for s in seen if s[6])
        check('%s: kein Ladekreis (das Bild läuft ja)' % tag, spun == 0, [spun, len(seen)])
        dbl = sum(1 for s in seen if not s[2] and not s[3] and not s[4] and not s[5])
        check('%s: danach eigenes Video mit Ton, Seite still (kein doppelter Ton)' % tag, after[0] and after[1] and after[2] and after[3] and dbl == 0, [after, dbl])
        ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    # Video stand beim Öffnen: Standbild wie bisher (nichts zu überbrücken)
    pg.goto(B + 'direct.html'); pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => document.getElementById("v").pause()'); time.sleep(0.3)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R); time.sleep(0.15)
    st = pg.evaluate('() => [!!%s.querySelector(".vbridge"), !!%s.querySelector(".vposter")]' % (R, R))
    check('Video stand: kein Spiegeln, Standbild', not st[0] and st[1], st)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    # Schließen mitten im Übergang: Seite läuft an ihrer Stelle weiter (kein Zurückspringen)
    SLOW[0] = 3000
    pg.goto(B + 'direct.html'); SLOW[0] = 0
    pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    SLOW[0] = 3000
    t0 = pg.evaluate('() => { const o = document.getElementById("v"); %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click(); return o.currentTime; }' % R)
    time.sleep(0.8)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.8)
    SLOW[0] = 0
    o = pg.evaluate('() => { const o = document.getElementById("v"); return [!o.paused, !o.muted, o.currentTime]; }')
    check('mitten im Übergang geschlossen: Seite läuft mit Ton an ihrer Stelle weiter', o[0] and o[1] and o[2] >= t0 + 1, [t0, o])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
