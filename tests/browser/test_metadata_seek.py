import pwshim
# Native media events: opening seek is valid once, not on an unrelated later source.
import time
exec(open('test_extras.py').read().split("with sync_playwright() as p:")[0])
with sync_playwright() as p:
    b=p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx=b.new_context(viewport={'width':400,'height':800},is_mobile=True,has_touch=True)
    ctx.add_init_script(PRE);ctx.add_init_script(HLS);ctx.add_init_script(SCRIPT)
    pg=ctx.new_page();errors=[];pg.on('pageerror',lambda e:errors.append(str(e)))
    pg.goto(B+'direct.html')
    pg.wait_for_function('document.getElementById("v").readyState>=2 && document.getElementById("v").duration>4')
    opening=pg.evaluate('''()=>{const v=document.getElementById("v");v.pause();v.muted=false;
      const t=Math.min(10,v.duration/2);v.currentTime=t;return t;}''')
    pg.wait_for_function('()=>!document.getElementById("v").seeking')
    pg.wait_for_function('()=>!!window.__evpRoot')
    pg.wait_for_function('()=>!!window.__evpRoot.querySelector("button[aria-label=\\"Im Videoplayer öffnen\\"]")')
    pg.evaluate('()=>window.__evpRoot.querySelector("button[aria-label=\\"Im Videoplayer öffnen\\"]").click()')
    pg.wait_for_function('()=>{const v=window.__evpRoot.querySelector(".vv");return v&&v.readyState>=2&&v.currentTime>1;}')
    actual=pg.evaluate('()=>window.__evpRoot.querySelector(".vv").currentTime')
    check('intentional opening keeps playback position',abs(actual-opening)<1.5,[opening,actual])
    result=pg.evaluate('''()=>new Promise(resolve=>{
      const v=window.__evpRoot.querySelector(".vv");v.pause();
      v.addEventListener("loadedmetadata",()=>resolve({time:v.currentTime,source:v.currentSrc}),{once:true});
      v.src=v.currentSrc+(v.currentSrc.includes("?")?"&":"?")+"independent-source=1";
    })''')
    check('new source does not inherit opening position',result['time']<1,result)
    pg.wait_for_function('()=>window.__evpRoot.querySelector(".vv").readyState>=2')
    check('new source remains playable without page errors',not errors,errors)
    b.close()
srv.shutdown()
print('ERGEBNIS: %d von %d' %(sum(results),len(results)))
