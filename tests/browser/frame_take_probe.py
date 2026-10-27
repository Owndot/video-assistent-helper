"""Passive receiver observation and real-click trials for the production take route."""
TAKE_PROBE = r'''(() => {
  window.__takeProbes=[];
  addEventListener('message',e=>{
    const d=e.data;if(!d || d.__evpFrame1!==1 || d.t!=='take')return;
    const f=[...document.querySelectorAll('iframe,frame')].find(f=>f.contentWindow===e.source);
    if(!f)return;
    const r=f.getBoundingClientRect(),ua=navigator.userActivation;
    const before=f.getAttribute('style');
    const premises={knownSource:true,origin:e.origin,focused:document.activeElement===f,
      active:ua?ua.isActive:null,width:r.width,height:r.height};
    // A separate task sees the actual production listener's synchronous stretch.
    setTimeout(()=>window.__takeProbes.push({key:d.k,premises,before,
      after:f.getAttribute('style'),position:f.style.position}),0);
  },true);
})();'''


def take_trial(browser, setup, url, key, activated=True):
    ctx,pg,errs=setup(browser)
    try:
        pg.goto(url)
        pg.wait_for_function('!!window.__evpRoot')
        source=next(f for f in pg.frames if 'emb_inner' in f.url)
        source.wait_for_function('!!window.__evpRoot')
        receiver=source.parent_frame
        receiver.wait_for_function('!!window.__evpRoot')
        source.evaluate('''key=>{
          window.__takeClick=null;
          const b=document.createElement('button');b.id='take-probe';b.textContent='Test frame route';
          b.style.cssText='position:fixed;left:8px;top:8px;width:140px;height:36px;z-index:2147483647';
          b.addEventListener('click',e=>{
            window.__takeClick={trusted:e.isTrusted,active:navigator.userActivation.isActive};
            parent.postMessage({__evpFrame1:1,t:'take',fs:false,k:key},'*');
          });document.body.append(b);
        }''',key)
        if activated:
            source.locator('#take-probe').click()  # trusted browser input; no activation/focus shims
        else:
            source.evaluate("key=>parent.postMessage({__evpFrame1:1,t:'take',fs:false,k:key},'*')",key)
        receiver.wait_for_function('window.__takeProbes.length===1')
        record=receiver.evaluate('window.__takeProbes[0]')
        click=source.evaluate('window.__takeClick')
        # For accepted nested takes, prove that forwarding also reaches the top route.
        accepted=record['position']=='fixed'
        if accepted:
            pg.wait_for_function('window.__takeProbes.length===1')
        top=pg.evaluate('''() => {
          const f=document.getElementById('f'),r=f.getBoundingClientRect();
          return {position:f.style.position,height:r.height,viewport:innerHeight,received:window.__takeProbes.length};
        }''')
        return {'record':record,'click':click,'accepted':accepted,'top':top,'errors':list(errs)}
    finally:
        ctx.close()


def armed(trial):
    p=trial['record']['premises'];click=trial['click']
    return bool(click and click['trusted'] and click['active'] and p['knownSource']
                and p['focused'] and (p['active'] or p['focused'])
                and p['width']>=100 and p['height']>=60 and not trial['errors'])


def unchanged(trial):
    r=trial['record']
    return not trial['accepted'] and r['after']==r['before'] and trial['top']['position']!='fixed'
