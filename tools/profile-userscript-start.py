#!/usr/bin/env python3
"""Real Chromium observations, synthetic manager/shadow hooks; no pass latency threshold."""
import os,sys,json,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
stable=Path(sys.argv[1]).resolve().read_text()
current=(root/'userscript/video-assistent.user.js').read_text()
output=Path(sys.argv[2]).resolve()
os.chdir(root/'tests/browser');sys.path.insert(0,os.getcwd())
exec(Path('test_v8.py').read_text().split('results = []')[0])
order=['stable','current','current','stable']*3
with sync_playwright() as p:
    for index,label in enumerate(order):
        source=stable if label=='stable' else current
        at=source.rfind('})();')
        assert at>=0
        source=source[:at]+'window.__cookieSiteLookup = baseDomain;\n'+source[at:]
        browser=p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx=browser.new_context(viewport={'width':400,'height':800},is_mobile=True,has_touch=True)
        ctx.add_init_script(PRE);ctx.add_init_script(HLS)
        ctx.add_init_script('window.__bootT=performance.now();\n'+source+'\nwindow.__bootMs=performance.now()-window.__bootT;')
        page=ctx.new_page()
        cdp=ctx.new_cdp_session(page);cdp.send('Performance.enable')
        page.goto('http://127.0.0.1:8775/direct.html')
        frame=page.evaluate("""() => new Promise(resolve => {
          const v=document.getElementById('v');
          v.requestVideoFrameCallback(()=>resolve(performance.now()));
        })""")
        boot=page.evaluate('window.__bootMs')
        cdp.send('HeapProfiler.collectGarbage')
        before={m['name']:m['value'] for m in cdp.send('Performance.getMetrics')['metrics']}
        lookup=page.evaluate("""() => {
          const t=performance.now(), site=window.__cookieSiteLookup('cdn.example.co.uk');
          return {site,cold_lookup_ms:performance.now()-t};
        }""")
        cdp.send('HeapProfiler.collectGarbage')
        after={m['name']:m['value'] for m in cdp.send('Performance.getMetrics')['metrics']}
        assert lookup['site']=='example.co.uk'
        row={'label':label,'sample':index,'browser':browser.version,'scope':'desktop Chromium, synthetic manager, direct fixture; no physical display/battery',
             'bootstrap_body_ms':boot,'first_compositor_frame_ms':frame,**lookup,
             'lookup_retained_heap_bytes':after['JSHeapUsedSize']-before['JSHeapUsedSize']}
        print(json.dumps(row),flush=True)
        with output.open('a') as out:out.write(json.dumps(row)+'\n')
        browser.close()
srv.shutdown()
