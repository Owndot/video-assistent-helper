import pwshim  # BROWSER=firefox → Tests in Firefox
# Nahtloser Start (alle Seiten): Beim Öffnen zeigt der Player sofort das LAUFENDE Bild der Seite (gespiegelt, Ton von
# der Seite), bis die eigene Kopie an derselben Stelle läuft – dann wird unmerklich umgeschaltet. Die sichtbare Zeit
# springt dabei weder zurück noch vor. Auch bei langsamem Server. Stand das Video, bleibt es beim Standbild.
import time
from playback_trace import evaluate_playback
from playback_trace_check import self_checks
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
# Native metadata/seek/playing identify initial synchronization. Stability latches
# once; later regressions cannot reset it or remove a sample from evaluation.
SAMPLE = '''() => {
  const R=%s,o=document.getElementById('v');
  const life={metadata:false,seeked:false,played:false};
  window.__seen=[];window.__stableAt=null;
  window.__traceStart=o.currentTime;window.__openedAt=performance.now();
  const event=e=>{
    if(!e.isTrusted || !e.target.matches || !e.target.matches('.vv'))return;
    if(e.type==='emptied' || e.type==='loadstart')Object.assign(life,{metadata:false,seeked:false,played:false});
    if(e.type==='loadedmetadata')life.metadata=true;
    if(e.type==='seeked')life.seeked=true;
    if(e.type==='playing')life.played=true;
  };
  const types=['emptied','loadstart','loadedmetadata','seeked','playing'];
  types.forEach(t=>R.addEventListener(t,event,true));
  const sample=()=>{
    const v=R.querySelector('.vv');if(!v)return;
    const now=performance.now(),br=R.querySelector('.vbridge'),sp=R.querySelector('.vspin');
    if(window.__stableAt===null && life.metadata && life.seeked && life.played &&
       !br && v.readyState>=2 && !v.seeking && !v.paused && v.videoWidth>0)window.__stableAt=now;
    window.__seen.push([now,br?o.currentTime:v.currentTime,!!br,o.paused,o.muted,v.muted,
      !!(sp && sp.classList.contains('on') && getComputedStyle(sp).opacity!=='0'),
      v.readyState,v.seeking,v.paused,window.__stableAt!==null,v.playbackRate]);
  };
  const iv=setInterval(sample,20);
  const limit=setTimeout(()=>clearInterval(iv),9000);
  window.__stopTrace=()=>{
    sample();clearInterval(iv);clearTimeout(limit);types.forEach(t=>R.removeEventListener(t,event,true));
    return {rows:window.__seen,start:window.__traceStart,opened:window.__openedAt};
  };
}''' % R
for name,ok in self_checks(): check('Messreihe: '+name,ok)
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
    run.amb = pg.evaluate('''() => { const a = %s.querySelector(".vamb"); if (!a || !a.getContext) return -1;
        try { const d = a.getContext("2d").getImageData(0, 0, a.width, a.height).data; let s = 0; for (let i = 0; i < d.length; i += 4) s += d[i] + d[i+1] + d[i+2] + d[i+3]; return Math.round(s / (d.length / 4)); } catch (e) { return -2; } }''' % R)
    pg.wait_for_function('window.__stableAt !== null', timeout=15000); time.sleep(0.8)
    trace = pg.evaluate('() => window.__stopTrace()')
    seen = trace['rows']; run.start = trace['start']; run.opened_at = trace['opened']
    run.verdict = evaluate_playback(seen,run.start,run.opened_at)
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
        gecko = pg.evaluate(r'() => /Gecko\/\d/.test(navigator.userAgent)')
        if gecko:
            check('%s: Firefox verwendet Standbild statt Capture-Brücke; Original still' % tag, early == [False, False, False], early)
            ambient = pg.evaluate('''() => {const a=%s.querySelector('.vamb'),v=%s.querySelector('.vv');return [a.tagName,!!a.srcObject,typeof v.captureStream==='function',a.muted];}''' % (R,R))
            check('%s: mobiler Ambient-Pfad verwendet Video; nur sichere Capture-API speist ihn' % tag, ambient[0]=='VIDEO' and ambient[1]==ambient[2] and ambient[3], ambient)
            check('%s: vollstaendiger stabiler Verlauf, positiver Fortschritt und korrekte Schlussposition' % tag, run.verdict['ok'], run.verdict)
            check('%s: Ladekreis nach Medienbereitschaft beendet' % tag, not pg.evaluate('() => %s.querySelector(".vspin").classList.contains("on")' % R))
        else:
            check('%s: gleich das laufende Bild der Seite, Ton weiter von der Seite' % tag, early == [True, True, True], early)
            check('%s: Ambient-Licht gleich beim Öffnen da (nach 0,15 s)' % tag, run.amb > 20, run.amb)
            bk, fw = jumps(seen)
            nb = sum(1 for s in seen if s[2])
            check('%s: umgeschaltet ohne Sprung (zurück %.2f s, vor %.2f s)' % (tag, bk, fw), bk < 0.2 and fw < 0.35 and nb > 2, [bk, fw, nb, len(seen)])
            spun = sum(1 for s in seen if s[6])
            check('%s: kein Ladekreis (das Bild läuft ja)' % tag, spun == 0, [spun, len(seen)])
        if not gecko: check('%s: stabiler eigener Wiedergabeverlauf' % tag, run.verdict['ok'], run.verdict)
        dbl = sum(1 for s in seen if not s[2] and not s[3] and not s[4] and not s[5])
        check('%s: danach eigenes Video mit Ton, Seite still (kein doppelter Ton)' % tag, after[0] and after[1] and after[2] and after[3] and dbl == 0, [after, dbl])
        ctl(pg); pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(1.0)
    # Video stand beim Öffnen: Standbild wie bisher (nichts zu überbrücken)
    pg.goto(B + 'direct.html'); pg.wait_for_function('document.getElementById("v").currentTime > 2', timeout=20000)
    pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.9)
    pg.evaluate('() => document.getElementById("v").pause()'); time.sleep(0.3)
    # A poster has no minimum lifetime: playing may replace it before 150ms.
    # Observe the native event without changing any player handler.
    pg.evaluate('''() => {
      const r=window.__evpRoot; window.__pausedOpenPlaying=false;
      r.addEventListener('playing',e=>{if(e.target.matches('.vv')) window.__pausedOpenPlaying=true;},true);
      window.__pausedOpenTime=document.getElementById('v').currentTime;
      window.__pausedOpenAt=performance.now();
      r.querySelector("button[aria-label='Im Videoplayer öffnen']").click();
    }'''); time.sleep(0.15)
    st = pg.evaluate('''() => {
      const r=window.__evpRoot,v=r.querySelector('.vv'),o=document.getElementById('v');
      const visible=e=>{if(!e)return false;const c=getComputedStyle(e),b=e.getBoundingClientRect();
        return e.isConnected && c.display!=='none' && c.visibility==='visible' && +c.opacity>0 && b.width>0 && b.height>0;};
      const p=r.querySelector('canvas.vposter');
      return {bridge:!!r.querySelector('.vbridge'),wrap:visible(r.querySelector('.vp')),
        poster:visible(p) && p.width>0 && p.height>0, video:visible(v),
        ready:v.readyState, seeking:v.seeking, played:window.__pausedOpenPlaying,
        width:v.videoWidth,height:v.videoHeight,time:v.currentTime,start:window.__pausedOpenTime,
        elapsed:(performance.now()-window.__pausedOpenAt)/1000,rate:v.playbackRate,
        sourceSilent:o.paused && o.muted};
    }''')
    def valid_picture(s):
        ready_video = (s['video'] and s['ready']>=2 and not s['seeking'] and s['played']
                       and s['width']>0 and s['height']>0
                       and s['start']-0.25 <= s['time'] <= s['start']+s['elapsed']*s['rate']+0.25)
        return s['wrap'] and not s['bridge'] and s['sourceSilent'] and (s['poster'] or ready_video)
    check('Video stand: sichtbares Standbild oder bereits bereites Video an derselben Stelle, keine Brücke', valid_picture(st), st)
    check('Bildprüfung lehnt leeren/verborgenen Player und falsche Videoposition ab',
          not valid_picture(dict(st,poster=False,video=False))
          and not valid_picture(dict(st,wrap=False))
          and not valid_picture(dict(st,poster=False,time=st['start']-1)), st)
    pg.wait_for_function('() => { const v=%s.querySelector(".vv"); return window.__pausedOpenPlaying && v.readyState>=2 && !v.seeking && !v.paused; }' % R, timeout=15000)
    check('nach Bereitschaft: Standbild entfernt, Video sichtbar und kein Ladekreis', pg.evaluate('''() => {
      const r=window.__evpRoot,v=r.querySelector('.vv');return !r.querySelector('.vposter') &&
        v.videoWidth>0 && getComputedStyle(v).visibility==='visible' && !r.querySelector('.vspin').classList.contains('on');
    }'''))
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
    check('mitten im Übergang geschlossen: Seite läuft mit Ton an ihrer Stelle weiter', o[0] and o[1] and o[2] >= t0 + (-0.3 if gecko else 1), [t0, o])
    check('keine Fehler', not errs, errs)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' % (sum(results), len(results)))
