import time
exec(open('test_v8.py').read().split("results = []")[0])
R = 'window.__evpRoot'; ok = []
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    for page in ['direct.html', 'direct_meta.html']:
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(SCRIPT)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/' + page)
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        meta = lambda: pg.evaluate('() => { const m = document.querySelector("meta[name=viewport]"); return m ? m.content : null; }')
        m0 = meta()
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => %s.querySelector(".vv")' % R, timeout=15000); time.sleep(0.4)
        m1 = meta()
        pg.evaluate('() => %s.querySelector("button[aria-label=Schließen]").click()' % R); time.sleep(0.5)
        m2 = meta()
        print('%-17s vorher: %-55s | offen: %s | danach: %s' % (page, m0, m1, m2))
        ok.append('viewport-fit=cover' in (m1 or '') and m2 == m0)
        ctx.close()
    b.close()
srv.shutdown()
print('ERGEBNIS:', sum(ok), 'von', len(ok))
