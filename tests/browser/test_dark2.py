import pwshim  # BROWSER=firefox → Tests in Firefox
import time, sys
exec(open('test_v8.py').read().split("results = []")[0])
from PIL import Image, ImageChops, ImageStat
def shots(script_path, tag, dark):
    S = open(script_path, encoding='utf-8').read()
    args = ['--autoplay-policy=no-user-gesture-required'] + (['--blink-settings=forceDarkModeEnabled=true'] if dark else [])
    with sync_playwright() as p:
        b = p.chromium.launch(args=args)
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/direct.html')
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1.2)
        r = pg.evaluate('() => { const q = window.__evpRoot.querySelector(".pill").getBoundingClientRect(); return [q.left, q.top, q.width, q.height]; }')
        pg.screenshot(path=OUT + '/p_%s.png' % tag, clip={'x': r[0] - 14, 'y': r[1] - 14, 'width': r[2] + 28, 'height': r[3] + 28})
        pg.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        pg.wait_for_function('() => { const v = window.__evpRoot.querySelector(".vv"); return v && v.currentTime > 1 && !v.paused; }', timeout=15000)
        time.sleep(0.6)
        if not pg.evaluate('() => window.__evpRoot.querySelector(".vp").classList.contains("ctl")'):
            pg.mouse.click(200, 300); time.sleep(0.4)
        pg.evaluate('() => { const v = window.__evpRoot.querySelector(".vv"); v.pause(); v.currentTime = 20; }'); time.sleep(0.6)
        pg.screenshot(path=OUT + '/v_%s.png' % tag)
        pg.mouse.move(330, 650); pg.mouse.down(); pg.mouse.move(330, 520, steps=6)
        time.sleep(0.2); pg.screenshot(path=OUT + '/s_%s.png' % tag); pg.mouse.up()
        b.close()
def diff(a, b):
    A = Image.open(OUT + '/' + a).convert('RGB'); B = Image.open(OUT + '/' + b).convert('RGB')
    if A.size != B.size: B = B.resize(A.size)
    return round(sum(ImageStat.Stat(ImageChops.difference(A, B)).mean) / 3, 2)
old = './backup-8.7-pre.js'
new = '../../userscript/video-assistent.user.js'
shots(old, 'old', False); shots(new, 'new', False); shots(new, 'newdark', True); shots(old, 'olddark', True)
srv.shutdown()
for k in ['p', 'v', 's']:
    print(k, 'alt vs neu (normal):', diff(k + '_old.png', k + '_new.png'), '| neu normal vs neu Dunkelmodus:', diff(k + '_new.png', k + '_newdark.png'), '| alt normal vs alt Dunkelmodus:', diff(k + '_old.png', k + '_olddark.png'))
