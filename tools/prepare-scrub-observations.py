#!/usr/bin/env python3
"""Add diagnostic observations only; retain every existing assertion."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'tests/browser/test_scrubbar.py').read_text()
needle='''    pg.evaluate('(u) => { const v = %s.querySelector(".vv"); v.src = u; }' % R, B + 'long.webm'); time.sleep(1.0)'''
replacement='''    pg.evaluate("""(u) => {
      const v = %s.querySelector('.vv'), start=performance.now();
      window.__sourceTrace=[];
      const note=(event,extra={})=>window.__sourceTrace.push({
        event,ms:performance.now()-start,time:v.currentTime,paused:v.paused,
        rate:v.playbackRate,ready:v.readyState,source:v.currentSrc,...extra});
      for(const event of ['emptied','loadstart','loadedmetadata','loadeddata','seeking','seeked','playing','pause','error'])
        v.addEventListener(event,()=>note(event));
      const d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'currentTime');
      Object.defineProperty(v,'currentTime',{configurable:true,get(){return d.get.call(this);},
        set(value){note('setCurrentTime',{value,stack:new Error().stack});d.set.call(this,value);}});
      window.__sourceTraceNow=()=>{note('assertion');return window.__sourceTrace;};
      note('before-source'); v.src=u; note('after-source');
    }""" % R, B + 'long.webm'); time.sleep(1.0)'''
assert source.count(needle)==1
source=source.replace(needle,replacement)
marker="    check('anderes Video beim Spulen:"
at=source.index(marker)
source=source[:at]+'''    print('SOURCE_TRANSITION_TRACE '+json.dumps(pg.evaluate('()=>window.__sourceTraceNow()')),flush=True)
'''+source[at:]
(root/'tests/browser/_scrub_observe.py').write_text(source)
