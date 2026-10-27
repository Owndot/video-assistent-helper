import pwshim
# Desktop browser integration; visibility is simulated explicitly, never claimed as Android Home.
import json, os, time, tempfile
from pathlib import Path
exec(open('test_extras.py').read().split('with sync_playwright() as p:')[0])
OBS = r'''(() => {
  try { localStorage.setItem('evp-s-settings',JSON.stringify({startFullscreen:false,ambient:false,language:'de',leaveAction:'pause'})); } catch(e) {}
  const s=window.__life={handlers:{},intervals:new Set(),contexts:new Set(),created:0,closed:0,pops:0,hidden:false};
  addEventListener('popstate',()=>s.pops++);
  Object.defineProperty(document,'hidden',{configurable:true,get:()=>s.hidden});
  Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>s.hidden?'hidden':'visible'});
  const si=window.setInterval,ci=window.clearInterval;
  window.setInterval=(...a)=>{const id=si(...a);s.intervals.add(id);return id;};
  window.clearInterval=id=>{s.intervals.delete(id);return ci(id);};
  const ms=navigator.mediaSession;
  if(ms){const set=ms.setActionHandler.bind(ms);ms.setActionHandler=(a,f)=>{const ret=set(a,f);if(f)s.handlers[a]=f;else delete s.handlers[a];return ret;};}
  const AC=window.AudioContext;
  if(AC)window.AudioContext=class extends AC{constructor(...args){super(...args);s.created++;s.contexts.add(this);const close=this.close.bind(this);this.close=()=>close().then(v=>{if(s.contexts.delete(this))s.closed++;return v;});}};
  const watched=new Map(),add=EventTarget.prototype.addEventListener,remove=EventTarget.prototype.removeEventListener;
  const wanted=t=>t===window||t===document||(t.tagName==='VIDEO'&&t.id==='v');
  EventTarget.prototype.addEventListener=function(t,f,o){
    const ret=add.call(this,t,f,o);
    if(wanted(this)&&f&&!(o&&typeof o==='object'&&(o.once||o.signal))){
      if(!watched.has(this))watched.set(this,new Map());const m=watched.get(this),k=t+':'+!!(typeof o==='boolean'?o:o&&o.capture);
      if(!m.has(k))m.set(k,new Set());m.get(k).add(f);
    }return ret;
  };
  EventTarget.prototype.removeEventListener=function(t,f,o){const k=t+':'+!!(typeof o==='boolean'?o:o&&o.capture);watched.get(this)?.get(k)?.delete(f);return remove.call(this,t,f,o);};
  s.listenerCount=()=>[...watched.values()].reduce((a,m)=>a+[...m.values()].reduce((b,v)=>b+v.size,0),0);
})();'''
SNAP = '''() => { const r=window.__evpRoot,s=window.__life,o=document.getElementById('v');
  const media=[...document.querySelectorAll('video'),...r.querySelectorAll('video')];
  return {instrumented:!window.__extLifecycle,players:r.querySelectorAll('.vp').length,ownedVideos:r.querySelectorAll('video').length,
    potentiallyAudible:media.filter(v=>!v.paused&&!v.muted&&v.volume>0).length,
    intervals:s.intervals.size,listeners:s.listenerCount(),contexts:s.contexts.size,created:s.created,closed:s.closed,
    handlers:Object.keys(s.handlers).sort(),position:o.currentTime,originalPlaying:!o.paused}; }'''
EXTENSION=os.environ.get('EVP_EXTENSION_PATH')
results=[]
def check(name, ok, info=''):
    results.append(bool(ok)); print(('OK   ' if ok else 'FAIL ')+name+(' – '+str(info) if info!='' else ''),flush=True)
def metric(browser,ctx,page):
    if os.environ.get('BROWSER')=='firefox': return {'available':False,'reason':'No Chromium CDP process/heap API in Firefox; structural resources still checked'}
    try:
        client=ctx.new_cdp_session(page);client.send('Performance.enable')
        heap=client.send('Runtime.getHeapUsage');tasks={m['name']:m['value'] for m in client.send('Performance.getMetrics')['metrics']};client.detach()
        system=browser.new_browser_cdp_session();procs=system.send('SystemInfo.getProcessInfo')['processInfo'];system.detach()
        rows=[]
        for p in procs:
            status=Path('/proc/%d/status'%p['id'])
            rss=None
            try:
                rss=next(int(l.split()[1]) for l in status.read_text().splitlines() if l.startswith('VmRSS:'))
            except (OSError,StopIteration): pass
            rows.append({'pid':p['id'],'type':p['type'],'cpu_seconds':p['cpuTime'],'rss_kib':rss})
        return {'available':True,'heap_bytes':heap['usedSize'],'renderer_task_seconds':tasks.get('TaskDuration'),'processes':rows,'time':time.monotonic()}
    except Exception as e:return {'available':False,'reason':type(e).__name__+': '+str(e)[:180]}
with sync_playwright() as p:
    profile=None
    if EXTENSION:
        profile=tempfile.TemporaryDirectory(prefix='pip-kadri-')
        ctx=p.chromium.launch_persistent_context(profile.name,headless=False,
            args=['--disable-extensions-except='+EXTENSION,'--load-extension='+EXTENSION,'--autoplay-policy=no-user-gesture-required'],
            viewport={'width':400,'height':800},is_mobile=True,has_touch=True,device_scale_factor=2,locale='de-DE')
        worker=ctx.service_workers[0] if ctx.service_workers else ctx.wait_for_event('serviceworker',timeout=15000)
        worker.evaluate('chrome.storage.local.set({"gm:settings":{startFullscreen:false,ambient:false,language:"de",leaveAction:"pause"}})')
        browser=ctx.browser
        # Installed content scripts run in another JS realm: only real DOM/media and
        # browser metrics are asserted here; page-world API hooks cannot measure its resources.
        ext_obs=OBS.replace("Object.defineProperty(document,'hidden',{configurable:true,get:()=>s.hidden});",'').replace("Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>s.hidden?'hidden':'visible'});",'')
        ctx.add_init_script(ext_obs+"window.__extLifecycle=true;")
        ctx.add_init_script("Object.defineProperty(window,'__evpRoot',{configurable:true,get:()=>document.querySelector('[data-kadri-test]')?.shadowRoot})")
    else:
        browser=p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx=browser.new_context(viewport={'width':400,'height':800},is_mobile=True,has_touch=True,device_scale_factor=2)
        ctx.add_init_script(PRE);ctx.add_init_script(OBS);ctx.add_init_script(HLS);ctx.add_init_script(SCRIPT)
    page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto(B+'direct.html');page.wait_for_function('document.getElementById("v").currentTime>1',timeout=20000)
    page.wait_for_selector('button[aria-label="Im Videoplayer öffnen"]',timeout=15000)
    baseline=page.evaluate(SNAP);samples=[{'stage':'before','resources':baseline,'metrics':metric(browser,ctx,page)}]
    for i in range(6):
        page.evaluate('() => {const v=document.getElementById("v");v.currentTime=2;v.muted=false;v.play();}')
        page.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        page.wait_for_function('''() => {const r=window.__evpRoot,v=r.querySelector('.vv');return v&&v.readyState>=2&&!v.seeking&&!v.paused&&!r.querySelector('.vbridge');}''',timeout=20000)
        state=page.evaluate(SNAP)
        check('Zyklus %d: genau ein Player, höchstens ein potentiell hörbares Video'%(i+1),state['players']==1 and state['potentiallyAudible']<=1,state)
        page.evaluate('() => window.__evpRoot.querySelector(".vp").classList.add("ctl")')
        page.locator('button[aria-label="Wiedergabe"]').click();page.wait_for_function('window.__evpRoot.querySelector(".vv").paused')
        a=page.evaluate('window.__evpRoot.querySelector(".vv").currentTime');page.wait_for_timeout(180)
        check('Zyklus %d: echte Pause'%(i+1),abs(page.evaluate('window.__evpRoot.querySelector(".vv").currentTime')-a)<.15)
        page.locator('button[aria-label="Wiedergabe"]').click();page.wait_for_function('(a)=>window.__evpRoot.querySelector(".vv").currentTime>a+.15',arg=a)
        before=page.evaluate('window.__evpRoot.querySelector(".vv").currentTime')
        page.locator('button[aria-label$=" s vor"]').click()
        page.wait_for_function('(a)=>{const v=window.__evpRoot.querySelector(".vv");return !v.seeking&&v.currentTime>a+1;}',arg=before)
        if i==1:
            background=None
            if EXTENSION:
                background=ctx.new_page();background.goto('about:blank');background.bring_to_front();page.wait_for_function('document.hidden',timeout=5000)
            else:page.evaluate('() => {window.__life.hidden=true;dispatchEvent(new Event("blur"));document.dispatchEvent(new Event("visibilitychange"));}')
            page.wait_for_function('window.__evpRoot.querySelector(".vv").paused')
            check(('Echter Desktop-Tabwechsel' if EXTENSION else 'Simulierter Hintergrund')+' bei pause-Einstellung hält an, Player bleibt',page.evaluate('!!window.__evpRoot.querySelector(".vp")'))
            if EXTENSION:
                page.bring_to_front();page.wait_for_function('!document.hidden');background.close()
            else:page.evaluate('() => {window.__life.hidden=false;document.dispatchEvent(new Event("visibilitychange"));dispatchEvent(new Event("focus"));}')
            check('Rückkehr behält die Pause bis zur Bedienung',page.evaluate('window.__evpRoot.querySelector(".vv").paused'))
            page.locator('button[aria-label="Wiedergabe"]').click();page.wait_for_function('!window.__evpRoot.querySelector(".vv").paused')
        # The recorded callbacks are the actual production MediaSession handlers.
        ms=page.evaluate('!!window.__life.handlers.pause&&!!window.__life.handlers.play')
        if ms:
            page.evaluate('window.__life.handlers.pause()');page.wait_for_function('window.__evpRoot.querySelector(".vv").paused')
            page.evaluate('window.__life.handlers.play()');page.wait_for_function('!window.__evpRoot.querySelector(".vv").paused')
        if not EXTENSION:check('Zyklus %d: aktive MediaSession Positivkontrolle oder API ausdrücklich nicht verfügbar'%(i+1),ms or page.evaluate('!navigator.mediaSession'))
        target=page.evaluate('window.__evpRoot.querySelector(".vv").currentTime');pop=page.evaluate('window.__life.pops');closed=time.monotonic()
        page.locator('button[aria-label="Schließen"]').click()
        page.wait_for_function('(n)=>!window.__evpRoot.querySelector(".vp")&&window.__life.pops>n&&window.__life.contexts.size===0',arg=pop,timeout=10000)
        page.wait_for_function('!document.getElementById("v").paused')
        page.wait_for_function('(n)=>window.__life.intervals.size<=n',arg=baseline['intervals'],timeout=4000)
        state=page.evaluate(SNAP);elapsed=time.monotonic()-closed
        check('Zyklus %d: Position korrekt zurückgegeben'%(i+1),target-.5<=state['position']<=target+elapsed+1,state['position'])
        if EXTENSION:check('Zyklus %d: eigene Videoelemente entfernt (isolated-realm Listener/AudioContext nicht gemessen)'%(i+1),state['ownedVideos']==0,state)
        else:check('Zyklus %d: Videos/Kontexte/Handler und beobachtete Listener aufgeräumt'%(i+1),state['ownedVideos']==0 and state['contexts']==0 and not state['handlers'] and state['listeners']==baseline['listeners'],state)
        if i in (1,3,5):samples.append({'stage':'after-%d'%(i+1),'resources':state,'metrics':metric(browser,ctx,page)})
    capability=page.evaluate('({enabled:!!document.pictureInPictureEnabled,request:typeof HTMLVideoElement.prototype.requestPictureInPicture==="function"})')
    print('CAPABILITY (desktop engine, not Android Home): '+json.dumps(capability),flush=True)
    check('Keine unbehandelten JavaScript-Fehler',not errors,errors)
    print('RESOURCE OBSERVATIONS (six cycles; route='+('installed-kadri' if EXTENSION else 'injected-userscript')+'; no leak-freedom or device-power claim): '+json.dumps(samples),flush=True)
    Path('out').mkdir(exist_ok=True);Path('out/pip-lifecycle-'+('installed-kadri' if EXTENSION else os.environ.get('BROWSER','chromium'))+'.json').write_text(json.dumps(samples,indent=2))
    ctx.close()
    if not EXTENSION:browser.close()
    if profile:profile.cleanup()
srv.shutdown()
print('ERGEBNIS: %d von %d'%(sum(results),len(results)));sys.exit(0 if all(results) else 1)
