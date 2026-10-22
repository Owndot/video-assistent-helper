import pwshim  # BROWSER=firefox → Tests in Firefox
# Untersuchung: Wie verhält sich die Bildspiegelung (captureStream) in diesem Browser, wenn das Original-Video
# am Ende neu geladen wird (wie bei Facebook-Reels)? Ohne Userscript – nur der Browser selbst.
import json, time, http.server, threading, functools
from playwright.sync_api import sync_playwright
WEB = 'web'
class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def translate_path(self, path):
        if '/data/' in path: path = '/' + path.split('/data/')[1].split('?')[0] + '.webm'
        return super().translate_path(path)
H = functools.partial(Q, directory=WEB)
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8791), H); threading.Thread(target=srv.serve_forever, daemon=True).start()
JS = r'''async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const v = document.getElementById('v');
  const cap = (el) => el.captureStream ? el.captureStream() : el.mozCaptureStream();
  const out = { ua: navigator.userAgent, unprefixed: !!HTMLMediaElement.prototype.captureStream, log: [] };
  const L = (m) => out.log.push(Math.round(performance.now()) + ' ' + m);
  const sink = (ms) => { const o = document.createElement('video'); o.muted = true; o.srcObject = ms; document.body.appendChild(o); o.play().catch(() => {}); return o; };
  const frames = (o, t = 1000) => new Promise((res) => { let n = 0; const cb = () => { n++; o.requestVideoFrameCallback(cb); }; o.requestVideoFrameCallback(cb); setTimeout(() => res(n), t); });
  const tr = (ms) => ms.getVideoTracks().map((t) => t.readyState + (t.muted ? '/stumm' : '')).join(',') + ' a:' + ms.getAudioTracks().length;
  await sleep(1500);
  const s1 = cap(v); const o1 = sink(s1);
  s1.addEventListener('addtrack', (e) => L('s1 addtrack ' + e.track.kind));
  s1.addEventListener('removetrack', (e) => L('s1 removetrack ' + e.track.kind));
  s1.getVideoTracks().forEach((t) => { t.onended = () => L('s1 ended'); t.onmute = () => L('s1 mute'); t.onunmute = () => L('s1 unmute'); });
  await sleep(500);
  out.vorher = [tr(s1), await frames(o1)];
  ['emptied', 'loadstart', 'loadedmetadata', 'loadeddata', 'playing', 'ended'].forEach((t) => v.addEventListener(t, () => L('orig ' + t)));
  let s2 = null, o2 = null, s3 = null, o3 = null;
  let s0 = null, o0 = null;
  v.addEventListener('ended', () => setTimeout(() => { s0 = cap(v); o0 = sink(s0); L('s0 (ended+30) ' + tr(s0)); }, 30), { once: true });
  v.addEventListener('loadeddata', () => setTimeout(() => { s2 = cap(v); o2 = sink(s2); L('s2 erstellt ' + tr(s2)); }, 60), { once: true });
  v.addEventListener('playing', () => setTimeout(() => { if (window.__loops) { s3 = cap(v); o3 = sink(s3); L('s3 erstellt ' + tr(s3)); } }, 400));
  v.currentTime = v.duration - 1.0;
  while (!window.__loops) await sleep(50);
  await sleep(2000);
  out.nachher_s1 = [tr(s1), await frames(o1)];
  out.nachher_s0 = s0 ? [tr(s0), await frames(o0)] : null;
  out.nachher_s2 = s2 ? [tr(s2), await frames(o2)] : null;
  out.nachher_s3 = s3 ? [tr(s3), await frames(o3)] : null;
  // dasselbe Element neu an denselben Strom binden
  o1.srcObject = null; o1.srcObject = s1; o1.play().catch(() => {}); await sleep(300); out.s1_neu_gebunden = [tr(s1), await frames(o1)];
  // frisches Element an den gleichen Strom (s1)
  const o4 = sink(s1); await sleep(300); out.s1_neues_element = await frames(o4);
  // spätere neue Spiegelung
  const s5 = cap(v); const o5 = sink(s5); await sleep(500); out.spaeter_neu = [tr(s5), await frames(o5)];
  out.orig = [v.paused, v.currentTime, v.readyState];
  return out;
}'''
with sync_playwright() as p:
    b = p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
    pg = b.new_page(viewport={'width': 400, 'height': 800})
    for page in ['loopreload.html', 'loopmse.html']:
        pg.goto('http://127.0.0.1:8791/' + page)
        pg.wait_for_function('document.getElementById("v").currentTime > 0.5', timeout=15000)
        r = pg.evaluate(JS)
        print('==', page); print(json.dumps(r, indent=1, ensure_ascii=False))
    b.close()
print('ERGEBNIS: Untersuchung fertig')
srv.shutdown()
