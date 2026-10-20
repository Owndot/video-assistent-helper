import time, json, sys
src = open('test_v8.py').read().split("results = []")[0]
src = src.replace("srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8775), functools.partial(Q, directory=WEB))", """class K(Q):
    def do_GET(self):
        if self.path.startswith('/get_file/'):
            self.send_response(302); self.send_header('Location', 'http://127.0.0.1:8776/remote_control.php?file=clip.webm&cv=abc&rnd=123'); self.end_headers(); return
        if self.path.startswith('/videos/'):
            body = b'<!doctype html><html><head><title>Videoname</title></head><body style="margin:0"><video id=v src="/get_file/51/8976e5134faa0a4474fce82b1516baad/615000/615810/615810_1440p.mp4/?v-acctoken=x" muted autoplay playsinline loop style="width:100%;display:block"></video><p>x</p></body></html>'
            self.send_response(200); self.send_header('Content-Type', 'text/html'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
        return super().do_GET()
class C(Q):
    def translate_path(self, path): return WEB + '/clip.webm'
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Access-Control-Allow-Headers', '*'); self.send_header('Access-Control-Expose-Headers', '*'); super().end_headers()
    def do_OPTIONS(self): self.send_response(204); self.end_headers()
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8775), functools.partial(K, directory=WEB))
srv2 = http.server.ThreadingHTTPServer(('127.0.0.1', 8776), functools.partial(C, directory=WEB))
threading.Thread(target=srv2.serve_forever, daemon=True).start()""")
exec(src)
GM = open('test_dl2.py').read().split('GM = """')[1].split('"""')[0]
REC = """const __ac = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () {
  if (/^intent:/.test(this.href)) { (window.__intents = window.__intents || []).push(this.href); return; }
  return __ac.call(this); };"""
R = 'window.__evpRoot'
def run(path):
    S = open(path, encoding='utf-8').read()
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
        ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(GM); ctx.add_init_script(REC); ctx.add_init_script(S)
        pg = ctx.new_page(); pg.goto('http://127.0.0.1:8775/videos/Videoname/')
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(0.8)
        pg.evaluate('() => %s.querySelector("button[aria-label=\'Im Videoplayer öffnen\']").click()' % R)
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.5; }' % R, timeout=15000)
        time.sleep(1.0)
        t0 = time.time()
        pg.evaluate('() => { %s.querySelector(".vp").classList.add("ctl"); %s.querySelector("button[aria-label=\'In externer App öffnen\']").click(); }' % (R, R))
        time.sleep(1.2)
        it = pg.evaluate('() => window.__intents || []')
        b.close()
    return it
for label, path in [('aktuell', '../../userscript/video-assistent.user.js')]:
    it = run(path)
    print('%-9s an VLC: %s' % (label, (it[0].split('#Intent')[0] if it else '-')))
srv.shutdown(); srv2.shutdown()
