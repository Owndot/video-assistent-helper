import time, json, sys
exec(open('test_v8.py').read().split("results = []")[0])
class Q2(Q):
    def translate_path(self, path):
        return super().translate_path(path.replace('/att/', '/', 1))
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Access-Control-Allow-Headers', '*'); self.send_header('Access-Control-Expose-Headers', '*')
        if self.path.startswith('/att/'): self.send_header('Content-Disposition', 'attachment; filename="clip.webm"')
        super().end_headers()
    def do_OPTIONS(self):
        self.send_response(204); self.end_headers()
srv2 = http.server.ThreadingHTTPServer(('127.0.0.1', 8776), functools.partial(Q2, directory=WEB))
threading.Thread(target=srv2.serve_forever, daemon=True).start()
GM = """window.GM_xmlhttpRequest = function (d) {
  const x = new XMLHttpRequest(); x.open(d.method || 'GET', d.url);
  if (d.responseType) x.responseType = d.responseType;
  for (const k in (d.headers || {})) { try { x.setRequestHeader(k, d.headers[k]); } catch (e) {} }
  const R = () => ({ readyState: x.readyState, status: x.status, responseHeaders: x.readyState >= 2 ? x.getAllResponseHeaders() : '', response: x.response, finalUrl: x.responseURL });
  x.onreadystatechange = () => d.onreadystatechange && d.onreadystatechange(R());
  x.onload = () => d.onload && d.onload(R()); x.onerror = () => d.onerror && d.onerror(R());
  x.onprogress = (e) => d.onprogress && d.onprogress({ loaded: e.loaded, total: e.total });
  x.send(); return { abort: () => x.abort() };
};"""
REC = """const __ac = HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click = function () {
  if (this.download) { (window.__dls = window.__dls || []).push({ name: this.download, href: this.href }); return; }
  return __ac.call(this); };
window.open = function (u) { (window.__opened = window.__opened || []).push(u); return {}; };"""
results = []
def check(name, ok, info=''):
    results.append((name, bool(ok))); print(('OK   ' if ok else 'FAIL ') + name + ('  – ' + str(info) if info != '' else ''))
R = 'window.__evpRoot'
DL = 'button[aria-label="Herunterladen (z. B. mit YTDLnis)"]'
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    ctx = b.new_context(viewport={'width': 400, 'height': 800}, is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx.add_init_script(PRE); ctx.add_init_script(HLS); ctx.add_init_script(GM); ctx.add_init_script(REC); ctx.add_init_script(SCRIPT)
    def open_player(url):
        pg = ctx.new_page(); pg.goto(url)
        pg.wait_for_function('document.getElementById("v").currentTime > 1', timeout=15000)
        pg.evaluate('() => { document.getElementById("v").muted = false; }'); time.sleep(1)
        pg.locator('button[aria-label="Im Videoplayer öffnen"]').click()
        pg.wait_for_function('() => { const v = %s.querySelector(".vv"); return v && v.currentTime > 0.5; }' % R, timeout=15000)
        time.sleep(0.5)
        if not pg.evaluate('() => %s.querySelector(".vp").classList.contains("ctl")' % R):
            pg.mouse.click(200, 300); time.sleep(0.4)
        return pg
    # 1) gleiche Seite: Browser lädt direkt
    pg = open_player('http://127.0.0.1:8775/direct.html')
    pg.locator(DL).click(); time.sleep(1)
    r = pg.evaluate('() => [window.__dls || [], %s.querySelector(".toast").textContent]' % R)
    check('Gleiche Seite: Download direkt im Browser', len(r[0]) == 1 and r[0][0]['href'].endswith('/clip.webm') and 'Browser' in r[1], r)
    # 2) fremder Server, als Download gekennzeichnet: Browser lädt direkt
    pg = open_player('http://127.0.0.1:8775/xorigin_att.html')
    pg.locator(DL).click(); time.sleep(2)
    r = pg.evaluate('() => [window.__dls || [], %s.querySelector(".toast").textContent, %s.querySelector(".vmenu").classList.contains("on")]' % (R, R))
    check('Fremder Server mit Download-Kennzeichnung: direkt im Browser', len(r[0]) == 1 and '8776/att/clip.webm' in r[0][0]['href'] and not r[2], r)
    # 3) fremder Server ohne Kennzeichnung: Auswahl
    pg = open_player('http://127.0.0.1:8775/xorigin.html')
    pg.locator(DL).click(); time.sleep(2)
    items = pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi")).map(b => b.firstChild.textContent)' % R)
    pg.screenshot(path=OUT + '/dl_menu.png')
    check('Ohne Kennzeichnung: Auswahl erscheint', items[:2] == ['Im Browser öffnen', 'Hier speichern'] and not pg.evaluate('() => window.__dls'), items)
    pg.evaluate('() => Array.from(%s.querySelectorAll(".vmenu .vmi"))[0].click()' % R); time.sleep(0.4)
    op = pg.evaluate('() => window.__opened || []')
    check('"Im Browser öffnen" öffnet die Datei im neuen Tab', len(op) == 1 and op[0].endswith('8776/clip.webm'), op)
    b.close()
srv.shutdown(); srv2.shutdown()
f = [r for r in results if not r[1]]; print('ERGEBNIS:', len(results)-len(f), 'von', len(results)); sys.exit(1 if f else 0)
