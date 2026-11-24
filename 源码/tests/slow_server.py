#!/usr/bin/env python3
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
IMG = os.path.join(ROOT, "sites", "bigimg", "big0.jpg")

HTML = """<!doctype html><html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>slow layout gate</title>
<link rel='stylesheet' href='/slow.css'>
</head><body>
<div id='box'><h1>首帧门帘测试</h1><p>CSS 到达前这里应该是默认样式</p></div>
<img id='pic' src='/slowimg.jpg'>
<div id='jsbox'>等待脚本</div>
<script src='/slow.js'></script>
</body></html>""".encode("utf-8")

CSS = """html,body{margin:0;padding:0;background:#fafafa;font-family:sans-serif}
#box{width:100%;box-sizing:border-box;padding:24px;background:#17324d;color:#fff}
#box h1{font-size:30px;margin:0 0 10px 0}
#box p{font-size:16px;margin:0;color:#cfe3ff}
#pic{display:block;width:60%;height:auto;margin:18px auto;border-radius:12px}
#jsbox{height:100px;margin:0 18px;background:#ffe0e0;color:#900;font-size:20px;
       display:flex;align-items:center;justify-content:center;border-radius:10px}
""".encode("utf-8")

JS = """var d=document.getElementById('jsbox');
if(d){d.textContent='脚本已执行 · 布局稳定';d.style.background='#d8f5d0';}
""".encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def _send(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/slow.html":
            self._send(HTML, "text/html; charset=utf-8")
        elif p == "/slow.css":
            time.sleep(1.2)
            self._send(CSS, "text/css; charset=utf-8")
        elif p == "/slow.js":
            time.sleep(0.9)
            self._send(JS, "application/javascript; charset=utf-8")
        elif p == "/slowimg.jpg":
            time.sleep(1.0)
            with open(IMG, "rb") as f:
                self._send(f.read(), "image/jpeg")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
