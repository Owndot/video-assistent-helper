import pwshim  # BROWSER=firefox → Tests in Firefox
exec(open('test_v8.py').read().split("results = []")[0])
R='window.__evpRoot'
SC = SCRIPT.replace("startFullscreen: true,", "startFullscreen: false,")
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SC)
    page = ctx.new_page(); errs=[]; page.on('pageerror', lambda e: errs.append(str(e)))
    page.goto('http://127.0.0.1:8775/emb_top.html'); time.sleep(1.5)
    page.mouse.wheel(0, 120); time.sleep(0.8)
    fi = [f for f in page.frames if 'emb_inner' in f.url][0]
    fi.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
    fi.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.0)
    page.frame_locator('#f').locator('button[aria-label="Im Videoplayer öffnen"]').click()
    time.sleep(1.5)
    r = page.evaluate('() => { const r = document.getElementById("f").getBoundingClientRect(); const e = document.elementFromPoint(200, 30); return [r.left, r.top, r.width, r.height, document.fullscreenElement, e && e.id]; }')
    ok1 = r[0]==0 and r[1]==0 and r[2]==400 and r[3]==800 and r[5]=='f'
    print('OK  ' if ok1 else 'FAIL', 'ohne Vollbild: Fenster deckt alles', r)
    page.screenshot(path=OUT+'/emb_nofs.png')
    page.frame_locator('#f').locator('button[aria-label="Schließen"]').click(); time.sleep(0.8)
    r2 = page.evaluate('() => [document.getElementById("f").getBoundingClientRect().height, document.querySelector("div[style*=translateZ]").getAttribute("style"), scrollY]')
    ok2 = r2[0]==260 and 'important' not in r2[1] and not errs
    print('OK  ' if ok2 else 'FAIL', 'zurückgestellt', r2, errs)
    b.close()
print('ERGEBNIS: %d von 2' % (ok1 + ok2))
