exec(open('test_v8.py').read().split("results = []")[0])
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok)))
    print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
SC = SCRIPT.replace("  function allVideos() {\n", "  function allVideos() {\n    window.__avc = (window.__avc || 0) + 1;\n", 1)
assert SC != SCRIPT
OLD = None and ("  function allVideos() {\n", "  function allVideos() {\n    window.__avc = (window.__avc || 0) + 1;\n", 1)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    def count(sc, url, secs=10, action=None):
        ctx = b.new_context(viewport={'width': 400, 'height': 800}); ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(sc)
        pg = ctx.new_page(); pg.goto(url); pg.wait_for_timeout(1500)
        c0 = pg.evaluate('() => window.__avc || 0')
        if action: action(pg)
        pg.wait_for_timeout(secs * 1000)
        c = pg.evaluate('() => window.__avc || 0') - c0
        ctx.close(); return c
    old = 25   # Messung mit Version 13.4 (vor dem sparsamen Takt)
    new = count(SC, 'http://127.0.0.1:8775/novideo.html')
    check('Seite ohne Video: deutlich seltener nachsehen', new <= old / 3, 'vorher %d, jetzt %d Prüfungen in 10 s' % (old, new))
    vid = count(SC, 'http://127.0.0.1:8775/direct.html')
    check('Seite mit Video: normaler Takt', vid >= 20, '%d Prüfungen in 10 s' % vid)
    # Video kommt später dazu: sofort wieder normaler Takt
    def add(pg):
        pg.wait_for_timeout(4000)
        pg.evaluate('() => { const v = document.createElement("video"); v.src = "clip.webm"; v.muted = false; v.autoplay = true; v.style.width = "100%"; document.getElementById("x").appendChild(v); }')
    late = count(SC, 'http://127.0.0.1:8775/novideo.html', 10, add)
    check('Video kommt später dazu: Takt springt wieder hoch', late >= 12, '%d Prüfungen in 10 s' % late)
    b.close()
print('ERGEBNIS: %d von %d' % (sum(1 for _, o in results if o), len(results)))
