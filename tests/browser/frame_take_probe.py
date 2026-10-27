"""Passive receiver observation and real-click trials for the production take route."""
import json

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
    setTimeout(()=>{
      const record={receiver:location.href,key:d.k,premises,before,
        after:f.getAttribute('style'),position:f.style.position};
      window.__takeProbes.push(record);
      console.log('KADRI_TAKE_PROBE '+JSON.stringify(record));
    },0);
  },true);
})();'''


def take_trial(browser, setup, url, key, activated=True):
    ctx,pg,errs=setup(browser)
    prefix='KADRI_TAKE_PROBE '
    records=[]
    def parse(message):
        return json.loads(message.text[len(prefix):])
    def observer(message):
        if message.text.startswith(prefix): records.append(parse(message))
    pg.on('console',observer)
    try:
        if not activated:
            # evaluate() is a user gesture in Playwright. Send from native DOM
            # initialization instead, and await a console event without JS evaluation.
            ctx.add_init_script("""(() => {
              if(!location.pathname.endsWith('/emb_inner.html'))return;
              window.__takeClick=null;
              addEventListener('DOMContentLoaded',()=>parent.postMessage(
                {__evpFrame1:1,t:'take',fs:false,k:KEY},'*'),{once:true});
            })();""".replace('KEY',json.dumps(key)))
            with pg.expect_console_message(predicate=lambda m:m.text.startswith(prefix)) as received:
                pg.goto(url)
            record=parse(received.value)
            click=None
        else:
            pg.goto(url)
            source=next(f for f in pg.frames if 'emb_inner' in f.url)
            receiver=source.parent_frame
            ready="document.readyState==='complete' && Array.isArray(window.__takeProbes) && typeof GM_getValue==='function'"
            for frame in (pg.main_frame,source,receiver): frame.wait_for_function(ready)
            source.evaluate('''key=>{
              window.__takeClick=null;
              const b=document.createElement('button');b.id='take-probe';b.textContent='Test frame route';
              b.style.cssText='position:fixed;left:8px;top:8px;width:140px;height:36px;z-index:2147483647';
              b.addEventListener('click',e=>{
                window.__takeClick={trusted:e.isTrusted,active:navigator.userActivation.isActive};
                parent.postMessage({__evpFrame1:1,t:'take',fs:false,k:key},'*');
              });document.body.append(b);
            }''',key)
            receiver_url=receiver.url
            with pg.expect_console_message(predicate=lambda m:m.text.startswith(prefix) and parse(m)['receiver']==receiver_url) as received:
                source.locator('#take-probe').click()
            record=parse(received.value)
            click=None  # read only AFTER the receiver/forwarding has finished
        # For accepted nested takes, prove that forwarding also reaches the top route.
        accepted=record['position']=='fixed'
        if accepted and not any(r['receiver']==url for r in records):
            pg.wait_for_event('console',predicate=lambda m:m.text.startswith(prefix) and parse(m)['receiver']==url)
        if activated: click=source.evaluate('window.__takeClick')
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
