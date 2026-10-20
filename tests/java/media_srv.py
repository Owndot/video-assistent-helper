# Videoserver für den Ende-zu-Ende-Test: /ck/* nur mit Sitzungs-Cookie sess=abc (das im Browser HttpOnly ist)
import http.server, functools, os, re
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def end_headers(self):
        self.send_header('Accept-Ranges', 'bytes'); super().end_headers()
    def do_GET(self):
        open('media.log','a').write(self.path + ' C=' + str(self.headers.get('Cookie')) + '\n')
        if self.path.startswith('/ck/') and 'sess=abc' not in (self.headers.get('Cookie') or ''):
            self.send_response(403); self.end_headers(); return
        if self.path.startswith('/pub/') and 'pub=1' not in (self.headers.get('Cookie') or ''):
            self.send_response(403); self.end_headers(); return
        rng = self.headers.get('Range'); p = self.translate_path(self.path.split('?')[0])
        if rng and os.path.isfile(p):
            size = os.path.getsize(p); m = re.match(r'bytes=(\d*)-(\d*)', rng)
            s = int(m.group(1) or 0); e = min(int(m.group(2)) if m.group(2) else size - 1, size - 1)
            with open(p, 'rb') as f: f.seek(s); d = f.read(e - s + 1)
            self.send_response(206); self.send_header('Content-Type', 'video/mp4'); self.send_header('Content-Length', str(len(d)))
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (s, e, size)); self.end_headers(); self.wfile.write(d); return
        return super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1', 8792), functools.partial(H, directory='srv')).serve_forever()
