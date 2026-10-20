exec(open('test_v8.py').read().split("results = []")[0])
R='window.__evpRoot'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for name, sc in (('ohne', SCRIPT.replace('lensGlass: true', 'lensGlass: false')), ('mit', SCRIPT)):
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=3)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(sc)
        page = ctx.new_page()
        page.goto('http://127.0.0.1:8775/lens_text.html')
        page.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        page.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.2)
        r = page.evaluate('() => { const p = %s.querySelector(".pill").getBoundingClientRect(); return [p.left, p.top, p.width, p.height]; }' % R)
        print(name, page.evaluate('() => { const p = %s.querySelector(".pill"); return [p.style.getPropertyValue("--lens"), getComputedStyle(p).backdropFilter]; }' % R))
        page.screenshot(path=OUT + '/lt_%s.png' % name, clip={'x': r[0] - 30, 'y': r[1] - 15, 'width': r[2] + 60, 'height': r[3] + 30})
        ctx.close()
    b.close()
