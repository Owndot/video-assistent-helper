import http.server, functools, sys
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path.startswith('/ck/') and 'sess=abc' not in (self.headers.get('Cookie') or ''):
            self.send_response(403); self.end_headers(); return
        ref = self.headers.get('Referer') or ''
        if 'example.test' not in ref and '127.0.0.1:8791' not in ref:
            self.send_response(403); self.end_headers(); return
        rng = self.headers.get('Range')
        if rng:
            import os, re
            p = self.translate_path(self.path.split('?')[0]); size = os.path.getsize(p)
            m = re.match(r'bytes=(\d+)-(\d+)', rng); s, e = int(m.group(1)), int(m.group(2))
            with open(p, 'rb') as f: f.seek(s); d = f.read(e - s + 1)
            self.send_response(206); self.send_header('Content-Length', str(len(d))); self.send_header('Content-Range', 'bytes %d-%d/%d' % (s, s + len(d) - 1, size)); self.end_headers(); self.wfile.write(d); return
        return super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1', 8791), functools.partial(H, directory='srv')).serve_forever()
