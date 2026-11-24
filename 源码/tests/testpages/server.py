#!/usr/bin/env python3
"""Local test server for ad-block / link-block / privacy / translation checks."""
import http.server
import json
import os
import socketserver
import sys
import urllib.parse

PORT = 8001
LOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.log")


def log(line):
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line, flush=True)

PNG_1X1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c6360000002000100ffff03000006000557bfabd4000000"
    "0049454e44ae426082"
)


def make_png(w, h, rgb):
    """生成纯色 PNG（避免手写字节出错）"""
    import struct
    import zlib

    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(raw)) +
            chunk(b"IEND", b""))


# LibreTranslate 兼容测试服务用的固定词表（只覆盖 blocktest.html 的文案）
MOCK_DICT = {
    "Block test page": "拦截测试页",
    "Ad blocking and link blocking test": "广告拦截与链接拦截测试",
    "This page loads an ad image from ": "本页从 ",
    "/ads/banner.png": "/ads/banner.png",
    ". With ad blocking on,\nthat request must be blocked and the space below stays empty.":
        " 加载广告图片。开启广告拦截后，该请求必须被拦截，下方留白。",
    "Download test image (long press to save):": "下载测试图片（长按保存）：",
    "Fake ad slot": "假广告位",
    'Long press this box and choose "Mark as ad" to hide it permanently for this site.':
        "长按此框并选择“标记为广告”，即可在本站点永久隐藏。",
    "Link blocking test: ": "链接拦截测试：",
    "open a blocked link": "打开被拦截的链接",
    "location.href navigation": "location.href 跳转",
    "Translation sample": "翻译示例",
    "The quick brown fox jumps over the lazy dog. This paragraph should be\n"
    "replaced with translated text when the page is translated.":
        "敏捷的棕色狐狸跳过了懒狗。翻译页面时，这段文字应被替换为译文。",
    "Cookie check": "Cookie 检查",
    "document.cookie: (loading)": "document.cookie：（加载中）",
    "document.cookie: (empty)": "document.cookie：（空）",
    "Counter: ": "计数：",
}


def translate_mock(text):
    """按行替换：命中词表用中文，未命中加【译】前缀，保证能看出文本已被替换"""
    out = []
    for line in text.split("\n"):
        key = line.strip()
        if key in MOCK_DICT:
            out.append(MOCK_DICT[key])
        elif not any(c.isalpha() for c in line):
            out.append(line)
        else:
            out.append("【译】" + line)
    return "\n".join(out)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        cookie = self.headers.get("Cookie", "")
        path = self.path.split("?")[0]
        print(f"GET {path} cookie={cookie!r}", flush=True)
        log(f"GET {path} cookie={cookie!r}")

        if path == "/translate":
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            q = qs.get("q", [""])[0]
            body = json.dumps({
                "translatedText": translate_mock(q),
                "detectedLanguage": {"language": "en", "confidence": 90},
            }, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path in ("/ads/banner.png", "/pic.png"):
            data = PNG_1X1 if path == "/ads/banner.png" else make_png(120, 120, (64, 128, 255))
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if path == "/should_not_open.html":
            print("OPENED /should_not_open.html (link block FAILED)", flush=True)
            body = b"<html><body><h1>should not open</h1></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/cookiecheck":
            body = f"""<!DOCTYPE html><html><head><meta charset="utf-8">
<title>Cookie check</title></head><body>
<h1>Cookie check</h1>
<p>Cookie header received by server: <b>{cookie or '(empty)'}</b></p>
<p>document.cookie: <b>{cookie or '(empty)'}</b></p>
</body></html>""".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Set-Cookie", "ea_inc=1; Path=/; Max-Age=86400")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/blocktest.html":
            import os
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "blocktest.html"), "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        body = b"<html><body><h1>404</h1></body></html>"
        self.send_response(404)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    print(f"listening on 0.0.0.0:{port}", flush=True)
    with Server(("0.0.0.0", port), Handler) as httpd:
        httpd.serve_forever()
