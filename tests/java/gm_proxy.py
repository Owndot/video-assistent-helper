# Nachbau von GM_xmlhttpRequest für den Test: führt die Anfrage aus (mit den Browser-Cookies, die das Testskript
# mitschickt) und antwortet der Seite mit CORS-Kopf.
import http.server, json, base64, urllib.request, urllib.error
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_OPTIONS(self):
        self.send_response(204); self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Access-Control-Allow-Headers', '*'); self.send_header('Access-Control-Allow-Methods', 'POST'); self.end_headers()
    def do_POST(self):
        j = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        data = base64.b64decode(j['data']) if j.get('data') is not None else None
        req = urllib.request.Request(j['url'], data=data, method=j.get('method', 'GET'))
        for k, v in (j.get('headers') or {}).items(): req.add_header(k, v)
        if j.get('cookie'): req.add_header('Cookie', j['cookie'])
        if '47811' not in j['url']:
            open('gm_log.txt', 'a').write(j['url'] + ' COOKIE=' + ('ja' if j.get('cookie') else 'nein') + '\n')
        try:
            r = urllib.request.urlopen(req, timeout=j.get('timeout', 30)); st = r.status; body = r.read(); hd = ''.join('%s: %s\r\n' % kv for kv in r.getheaders())
        except urllib.error.HTTPError as e:
            st = e.code; body = e.read() or b''; hd = ''.join('%s: %s\r\n' % kv for kv in e.headers.items())
        except Exception as e:
            st = 0; body = b''; hd = ''
        out = json.dumps({'status': st, 'headers': hd, 'body': base64.b64encode(body).decode()}).encode()
        self.send_response(200); self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(out))); self.end_headers(); self.wfile.write(out)
http.server.ThreadingHTTPServer(('127.0.0.1', 8793), H).serve_forever()
