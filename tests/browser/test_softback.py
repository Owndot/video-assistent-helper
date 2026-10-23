import pwshim  # BROWSER=firefox → Tests in Firefox
# Nahtlos zurück zur Seite: Beim Schließen läuft das eigene Video (mit Ton) noch kurz genau über dem der Seite weiter,
# bis die Seite an der Stelle bereit ist – dann übernimmt sie ohne Pause, ohne Sprung, ohne doppelten Ton.
# Lädt die Seite zu langsam: wie früher (Standbild, dann an deiner Stelle). Vorwärmen: Hältst du an, steht die Seite
# danach schon an deiner Stelle (lädt dort vor).
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
SAMPLE = '''() => { const R = %s, o = document.getElementById("v"); window.__s = [];
  const iv = setInterval(() => { const v = R.querySelector(".vv") || document.querySelector("video.vv"); const cover = !!(v && v.isConnected && !v.closest(".vp"));
    const ownOn = !!(v && v.isConnected && !v.paused && !v.muted && (cover || (v.closest(".vp") && v.closest(".vp").style.display !== "none")));
    const pageOn = !o.paused && !o.muted;
    window.__s.push([performance.now(), cover ? v.currentTime : (ownOn ? v.currentTime : o.currentTime), cover, ownOn, pageOn]); }, 20);
  setTimeout(() => clearInterval(iv), 6000); }''' % R
def analyse(s):
    back = fwd = still = 0; run = 0
    for a, b in zip(s, s[1:]):
        dt = (b[0] - a[0]) / 1000; dv = b[1] - a[1]
        back = max(back, -dv); fwd = max(fwd, dv - dt)
        run = run + dt if abs(dv) < 0.005 else 0
        still = max(still, run)
    silent = sum(1 for x in s if not x[3] and not x[4]); double = sum(1 for x in s if x[3] and x[4])
    return round(back, 2), round(fwd, 2), round(still, 2), silent, double
SLOW = [0]
def openp(pg, page='direct.html'):
    pg.goto(B + page); pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
    pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && !v.paused && v.currentTime > 0.5 && !%s.querySelector(".vbridge"); }' % (R, R), timeout=20000)
    time.sleep(0.5)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    ctx.route('**/clip.webm*', lambda r: (time.sleep(SLOW[0] / 1000) if SLOW[0] else None, r.continue_())[1])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    # 1) ✕ weit weg von der Stelle der Seite: nahtlos zurück
    for how in ('✕', 'Zurück-Geste'):
        openp(pg)
        pg.evaluate('() => { %s.querySelector(".vv").currentTime = 30; }' % R); time.sleep(1.5)
        o0 = pg.evaluate('() => document.getElementById("v").currentTime')
        pg.evaluate('(%s)()' % SAMPLE)
        if how == '✕': pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R)
        else: pg.evaluate('() => document.exitFullscreen()')
        time.sleep(3.5)
        s = pg.evaluate('() => window.__s')
        bk, fw, still, silent, double = analyse(s)
        cov = sum(1 for x in s if x[2])
        end = pg.evaluate('() => { const o = document.getElementById("v"); return [!o.paused, !o.muted, Math.round(o.currentTime * 10) / 10, !%s.querySelector(".vp"), !%s.querySelector(".vv") && !document.querySelector("video.vv")]; }' % (R, R))
        check('%s: dein Video läuft kurz über der Seite weiter (Seite stand bei %.0f s)' % (how, o0), cov >= 3, [cov, len(s)])
        check('%s: ohne Pause, Sprung, Stille oder doppelten Ton' % how, bk < 0.2 and fw < 0.35 and still < 0.25 and silent <= 2 and double <= 1, [bk, fw, still, silent, double])
        check('%s: danach spielt die Seite mit Ton an deiner Stelle, Player ganz weg' % how, end[0] and end[1] and 31 <= end[2] <= 36 and end[3] and end[4], end)
    # 1c) Seite braucht 0,8 s zum Laden (wie im Mobilnetz): früher eine sichtbare Pause – jetzt läuft das Bild durch
    openp(pg)
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 30; }' % R); time.sleep(1.5)
    pg.evaluate('(%s)()' % SAMPLE)
    SLOW[0] = 800
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(4.0)
    SLOW[0] = 0
    s = pg.evaluate('() => window.__s')
    bk, fw, still, silent, double = analyse(s)
    check('Seite lädt 0,8 s: keine Pause beim Zurückkommen (Stillstand %.2f s)' % still, still < 0.25 and bk < 0.2 and silent <= 2 and double <= 1, [bk, fw, still, silent, double])
    # 1d) Video in einem Kasten, der es abschneidet, Knöpfe der Seite darüber (wie Instagram): dein Video liegt beim
    #     Zurück genauso abgeschnitten an seinem Platz, die Knöpfe der Seite bleiben darüber sichtbar
    openp(pg, 'clipped.html')
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 30; }' % R); time.sleep(1.5)
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.2)
    k = pg.evaluate('''() => { const v = document.querySelector("video.vv"); if (!v) return null;
        const b = document.getElementById("box").getBoundingClientRect(), o = document.getElementById("v");
        v.style.setProperty("pointer-events", "auto", "important");   // (nur für die Prüfung: was liegt wo oben?)
        const top = document.elementFromPoint(200, b.top + 20), ui = document.elementFromPoint(200, b.bottom - 20), out = document.elementFromPoint(200, b.top - 30);
        v.style.setProperty("pointer-events", "none", "important");
        return [v.previousElementSibling === o, top === v, ui && ui.id, out !== v, getComputedStyle(v).objectFit]; }''')
    check('wie Instagram: dein Video liegt an seinem Platz in der Seite, abgeschnitten wie dort, Knöpfe der Seite darüber', k and k[0] and k[1] and k[2] == 'ui' and k[3] and k[4] == 'cover', k)
    time.sleep(3.0)
    end = pg.evaluate('() => { const o = document.getElementById("v"); return [!o.paused, !o.muted, !document.querySelector("video.vv")]; }')
    check('… danach spielt die Seite selbst weiter, dein Video ist weg', end[0] and end[1] and end[2], end)
    # 2) Seite lädt zu langsam: wie früher – Standbild, dann an deiner Stelle
    openp(pg)
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 40; }' % R); time.sleep(1.5)
    SLOW[0] = 4500
    pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(8.0)
    SLOW[0] = 0
    end = pg.evaluate('() => { const o = document.getElementById("v"); return [!o.paused, !o.muted, Math.round(o.currentTime * 10) / 10, !%s.querySelector(".vv") && !document.querySelector("video.vv")]; }' % R)
    check('Seite lädt zu langsam: trotzdem an deiner Stelle weiter (mit Ton), Player weg', end[0] and end[1] and 40 <= end[2] <= 59 and end[3], end)
    # 3) Vorwärmen: anhalten → Seite steht danach schon an deiner Stelle
    openp(pg)
    pg.evaluate('() => { %s.querySelector(".vv").currentTime = 25; }' % R); time.sleep(1.2)
    pg.evaluate('() => { %s.querySelector(".vp").classList.add("ctl"); }' % R); time.sleep(0.2)
    pg.evaluate('() => %s.querySelector("button[aria-label=Wiedergabe]").click()' % R); time.sleep(1.2)
    w = pg.evaluate('() => { const o = document.getElementById("v"), v = %s.querySelector(".vv"); return [o.paused, o.muted, Math.round(o.currentTime), Math.round(v.currentTime)]; }' % R)
    check('Anhalten: Seite still an deine Stelle vorgewärmt (angehalten, stumm)', w[0] and w[1] and abs(w[2] - w[3]) <= 2, w)
    ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
