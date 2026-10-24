#!/usr/bin/env python3
"""Owned, deterministic Android fixtures. No external video sites and no GM shims."""
import argparse
from collections import deque
import json
import mimetypes
import re
import shutil
import struct
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

EVENTS = deque(maxlen=10000)
LOCK = threading.Lock()
ROOT = Path(__file__).resolve().parent
MEDIA = ROOT / "generated"

def generate():
    MEDIA.mkdir(exist_ok=True)
    for name, pattern in (("clip", "testsrc2"), ("alternate", "smptebars")):
        path = MEDIA / (name + ".mp4")
        if not path.exists():
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", pattern + "=size=480x270:rate=24",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "60", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-threads", "2", "-c:a", "aac",
                "-b:a", "64k", "-movflags", "+faststart", str(path)], check=True)
    # A valid large ISO-BMFF file: unchanged playable MP4 + sparse free box.
    # Only test disk space, not 128 MiB of Python RAM, is reserved here.
    large = MEDIA / "large.mp4"
    if not large.exists():
        shutil.copyfile(MEDIA / "clip.mp4", large)
        total = 128 * 1024 * 1024
        padding = total - large.stat().st_size
        assert 8 <= padding < 2**32
        with large.open("ab") as stream:
            stream.write(struct.pack(">I4s", padding, b"free"))
            stream.truncate(total)
    hls = MEDIA / "hls"
    hls.mkdir(exist_ok=True)
    if not (hls / "index.m3u8").exists():
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(MEDIA / "clip.mp4"), "-c", "copy", "-hls_time", "4",
            "-hls_list_size", "0", "-hls_segment_filename", str(hls / "seg%03d.ts"),
            str(hls / "index.m3u8")], check=True)
    (MEDIA / "captions.vtt").write_text(
        "WEBVTT\n\n00:00.000 --> 00:59.900\nANDROID E2E SUBTITLE\n", encoding="utf8")

def page(kind, port):
    if kind == "embedded":
        return f"""<!doctype html><meta name=viewport content="width=device-width">
<title>E2E embedded</title><iframe title="Owned embedded player" allow="autoplay; fullscreen; picture-in-picture"
src="/page/direct?embedded=1" style="width:100%;height:420px;border:0"></iframe>"""
    src = "/media/hls/index.m3u8" if kind == "hls" else "/slowhls/index.m3u8" if kind == "slowhls" else "/slow/clip.mp4" if kind == "download" else "/media/large.mp4" if kind == "large" else "/media/clip.mp4"
    if kind == "redirect":
        src = "/redirect?to=/media/clip.mp4"
    return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<title>E2E """ + kind + """</title><script src="/hls.min.js"></script><style>body{margin:0;background:#eee;font:18px sans-serif}
video{width:100%;max-height:55vh}button,a{font:18px sans-serif;min-height:48px;margin:8px}
</style></head><body><h1>Owned Android video fixture</h1>
<video id=v controls playsinline loop preload=auto src='""" + src + """'>
<track kind=subtitles src=/media/captions.vtt srclang=en label="E2E English" default></video>
<button id=play>Play fixture</button><button id=change>Switch fixture source</button>
<output id=status>Waiting for playback</output>
<p><a href="/page/direct">Direct</a><a href="/page/hls">HLS</a>
<a href="/page/embedded">Embedded</a><a href="/page/redirect">Redirect</a></p>
<script>
const v=document.getElementById('v');
if(v.getAttribute('src').includes('.m3u8')&&!v.canPlayType('application/vnd.apple.mpegurl')&&window.Hls&&Hls.isSupported()){
const h=new Hls();h.loadSource(v.src);h.attachMedia(v);}
const report=(t)=>fetch('/observe',{method:'POST',
body:JSON.stringify({event:t,time:v.currentTime,paused:v.paused,source:v.currentSrc,at:performance.now()})}).catch(()=>{});
document.getElementById('play').onclick=()=>v.play();
document.getElementById('change').onclick=()=>{v.src='/media/alternate.mp4';v.play()};
for(const t of ['playing','pause','seeked','loadedmetadata','ended','error'])v.addEventListener(t,()=>report(t));
setInterval(()=>{document.getElementById('status').textContent='Fixture time '+v.currentTime.toFixed(1);},500);
</script></body></html>"""

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_):
        pass

    def reply(self, status, body=b"", ctype="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_POST(self):
        if urlsplit(self.path).path != "/observe":
            self.reply(404)
            return
        size = int(self.headers.get("Content-Length", "0"))
        if size < 0 or size > 4096:
            self.reply(413)
            return
        try:
            event = json.loads(self.rfile.read(size))
            # Only owned fixture observation, never a browser/helper key.
            event = {k: event[k] for k in ("event", "time", "paused", "source", "at") if k in event}
            with LOCK:
                EVENTS.append(dict(event, received=time.monotonic()))
            self.reply(200, b'{"ok":true}')
        except (ValueError, TypeError):
            self.reply(400)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlsplit(self.path)
        q = parse_qs(u.query)
        if u.path != "/events":
            self.trace_id = time.monotonic_ns()
            with LOCK:
                EVENTS.append({"event": "request", "id": self.trace_id, "path": u.path,
                    "cookie": bool(self.headers.get("Cookie")),
                    "authorization": bool(self.headers.get("Authorization")),
                    "range": self.headers.get("Range", ""), "received": time.monotonic()})
        if u.path == "/events":
            with LOCK:
                data = list(EVENTS)
            self.reply(200, json.dumps(data).encode())
        elif u.path == "/health":
            self.reply(200, b'{"ok":true}')
        elif u.path == "/redirect":
            target = q.get("to", ["/media/clip.mp4"])[0]
            # Redirect destinations are owned fixture routes only.
            if not re.fullmatch(r"/(?:media/[A-Za-z0-9_./-]+|sink)", target):
                self.reply(400)
                return
            origin = "http://localhost:" + str(self.server.server_port) if "cross" in q else ""
            self.reply(302, headers={"Location": origin + target})
        elif u.path == "/sink":
            self.reply(200, b"owned destination", "text/plain")
        elif u.path == "/video-assistent.user.js":
            data = (ROOT.parent.parent / "userscript/video-assistent.user.js").read_bytes()
            self.reply(200, data, "application/javascript; charset=utf-8")
        elif u.path == "/hls.min.js":
            data = (ROOT.parent / "browser/web/hls.min.js").read_bytes()
            self.reply(200, data, "application/javascript")
        elif u.path.startswith("/page/"):
            self.reply(200, page(u.path.rsplit("/", 1)[1], self.server.server_port).encode(), "text/html; charset=utf-8")
        elif u.path.startswith(("/media/", "/slow/", "/slowhls/")):
            relative = ("hls/" if u.path.startswith("/slowhls/") else "") + u.path.split("/", 2)[2]
            f = (MEDIA / relative).resolve()
            if MEDIA.resolve() not in f.parents or not f.is_file():
                self.reply(404)
                return
            if "header_ms" in q:
                time.sleep(min(10000, max(0, int(q["header_ms"][0]))) / 1000)
            total = f.stat().st_size
            start, end, status = 0, total - 1, 200
            value = self.headers.get("Range")
            if value:
                m = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
                if not m or (not m[1] and not m[2]):
                    self.reply(416, headers={"Content-Range": f"bytes */{total}"})
                    return
                if m[1]:
                    start = int(m[1])
                    end = min(total - 1, int(m[2])) if m[2] else total - 1
                else:
                    start = max(0, total - int(m[2]))
                if start >= total or end < start:
                    self.reply(416, headers={"Content-Range": f"bytes */{total}"})
                    return
                status = 206
            self.send_response(status)
            ctype = {"m3u8": "application/vnd.apple.mpegurl", "ts": "video/mp2t", "vtt": "text/vtt"}.get(
                f.suffix[1:], mimetypes.guess_type(str(f))[0] or "application/octet-stream")
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
            self.end_headers()
            if self.command != "HEAD":
                left = end - start + 1
                completed = False
                try:
                    with f.open("rb") as stream:
                        stream.seek(start)
                        left = end - start + 1
                        while left:
                            chunk = stream.read(min(32768, left))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            self.wfile.flush()
                            left -= len(chunk)
                            if u.path.startswith(("/slow/", "/slowhls/")) and f.suffix != ".m3u8":
                                time.sleep(0.1)
                    completed = left == 0
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    with LOCK:
                        EVENTS.append({"event": "transfer_end", "id": self.trace_id, "path": u.path,
                            "bytes": end - start + 1 - left, "complete": completed, "received": time.monotonic()})
        else:
            self.reply(404)

def serve(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    return server

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--generate-only", action="store_true")
    args = ap.parse_args()
    generate()
    if not args.generate_only:
        serve(args.port).serve_forever()
