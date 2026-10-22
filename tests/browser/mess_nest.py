import pwshim  # BROWSER=firefox → Tests in Firefox
exec(open('test_v8.py').read().split("results = []")[0])
R='window.__evpRoot'
import sys
W,H = (400,800) if len(sys.argv)<2 else (800,400)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required','--host-resolver-rules=MAP *.test 127.0.0.1'])
    ctx = b.new_context(viewport={'width': W, 'height': H}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
    page = ctx.new_page(); errs=[]; page.on('pageerror', lambda e: errs.append(str(e)))
    page.goto('http://top.test:8775/nest_top.html'); time.sleep(2)
    fi = [f for f in page.frames if 'emb_inner' in f.url][0]
    fi.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    fi.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.0)
    page.frame_locator('#f').frame_locator('#g').locator('button[aria-label="Im Videoplayer öffnen"]').click()
    time.sleep(2)
    print(page.evaluate('() => [document.fullscreenElement && document.fullscreenElement.id, JSON.stringify(document.getElementById("f").getBoundingClientRect())]'))
    mid = [f for f in page.frames if 'nest_mid' in f.url][0]
    print(mid.evaluate('() => [innerWidth, innerHeight, JSON.stringify(document.getElementById("g").getBoundingClientRect())]'))
    print(fi.evaluate('() => { const v = %s.querySelector(".vv"); return [innerWidth, innerHeight, JSON.stringify(v.getBoundingClientRect()), JSON.stringify(%s.querySelector(".vp").getBoundingClientRect())]; }' % (R,R)))
    page.screenshot(path=OUT+'/nest_%dx%d.png'%(W,H))
    print(errs)
    b.close()
