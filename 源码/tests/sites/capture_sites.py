#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线快照抓取器（只写 tests/sites，不碰 engine/android）。

口径（与 feature_runner 宿主一致，已用 _probe 实测）：
  * <link>/<script>/<img> 中的路径由引擎按“页面目录”解析；
  * CSS 里的 url(...) 即使 CSS 在子目录，引擎也按“页面目录”当原样字符串用；
  * 所以所有引用统一改写成 page.html 的相对路径；
  * @import 例外：引擎按 CSS 文件目录解析，单独按 CSS 目录改写；
  * 原始 URL 全部保存在 manifest.json / page.meta.json，便于还原。
"""
import gzip
import hashlib
import html as htmllib
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

MAX_TOTAL = 45 * 1024 * 1024      # 每页资源总量上限
MAX_FILES = 220                   # 每页文件数上限
CAP = {"css": (40, 3 * 1024 * 1024),
       "image": (140, 6 * 1024 * 1024),
       "js": (40, 4 * 1024 * 1024),
       "other": (10, 2 * 1024 * 1024)}
IMG_EXT = {"png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif"}
SKIP_EXT = {"woff", "woff2", "ttf", "otf", "eot", "mp4", "webm", "ogg", "mp3",
            "m4a", "m3u8", "ts", "flv", "zip", "apk"}
TAG_RE = re.compile(r"<(link|script|img|source|video|iframe|base)\b([^>]*)>",
                    re.I | re.S)
ATTR_RE = re.compile(r"""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")
URLFUNC_RE = re.compile(r"url\(\s*(['\"]?)([^'\"\)]+)\1\s*\)", re.I)
IMPORT_RE = re.compile(r"@import\s+(?:url\(\s*)?(['\"]?)([^'\"\)\s;]+)\1", re.I)
STYLE_RE = re.compile(r"<style\b[^>]*>(.*?)</style>", re.I | re.S)
META_CHARSET_RE = re.compile(r"(<meta[^>]+charset\s*=\s*)(['\"]?)([A-Za-z0-9_-]+)\2", re.I)


def log(*a):
    print(*a, flush=True)


def fetch(url, referer=None, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.5",
        "Accept-Encoding": "gzip, deflate",
    })
    if referer:
        req.add_header("Referer", referer)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
        ctype = r.headers.get("Content-Type", "")
        cenc = (r.headers.get("Content-Encoding") or "").lower().strip()
        final = r.geturl()
    if cenc == "gzip" or (not cenc and data[:2] == b"\x1f\x8b"):
        data = gzip.decompress(data)
    elif cenc == "deflate":
        data = zlib.decompress(data, -15)
    elif cenc == "br":
        raise RuntimeError("brotli response (unsupported)")
    return data, ctype, final


def decode_html(data, ctype):
    cs = ""
    m = re.search(r"charset=([A-Za-z0-9_-]+)", ctype or "", re.I)
    if m:
        cs = m.group(1)
    else:
        m = re.search(rb"<meta[^>]+charset\s*=\s*[\"']?([A-Za-z0-9_-]+)", data[:4096], re.I)
        if m:
            cs = m.group(1).decode("ascii", "ignore")
    for enc in [cs, "utf-8", "gb18030", "gbk"]:
        if not enc:
            continue
        try:
            return data.decode(enc), enc
        except Exception:
            pass
    return data.decode("utf-8", "replace"), "utf-8(replace)"


def abs_url(ref, base):
    if not ref:
        return None
    ref = htmllib.unescape(ref.strip())
    if not ref or ref.startswith("#"):
        return None
    low = ref.lower()
    if low.startswith(("data:", "blob:", "javascript:", "mailto:", "tel:", "about:")):
        return None
    try:
        u = urllib.parse.urljoin(base, ref)
    except Exception:
        return None
    if not u.lower().startswith(("http://", "https://")):
        return None
    return urllib.parse.urldefrag(u)[0]


def ext_of(url):
    p = urllib.parse.urlsplit(url).path.lower()
    e = p.rsplit(".", 1)[-1] if "." in p.rsplit("/", 1)[-1] else ""
    return e


def kind_of(url, hint=""):
    e = ext_of(url)
    if e in SKIP_EXT:
        return "skip"
    if hint:
        return hint
    if e == "css":
        return "css"
    if e == "js":
        return "js"
    if e in IMG_EXT:
        return "image"
    if e in ("html", "htm"):
        return "html"
    return "other"


def local_path_for(url):
    u = urllib.parse.urlsplit(url)
    host = re.sub(r"[^A-Za-z0-9._-]", "_", u.netloc.lower()) or "host"
    path = urllib.parse.unquote(u.path).replace("\\", "/")
    if path.endswith("/") or not path:
        path += "index.html"
    segs = []
    for s in path.split("/"):
        if not s or s == "..":
            continue
        s = re.sub(r'[<>:"|?*\\/]', "_", s)
        if len(s) > 72:
            s = s[:52] + "__" + hashlib.sha1(s.encode("utf-8")).hexdigest()[:10]
        segs.append(s)
    rel = os.path.join("assets", host, *segs)
    if u.query:
        d, f = os.path.split(rel)
        stem, dot, ext = f.rpartition(".")
        if not dot:
            stem, ext = f, ""
        h = hashlib.sha1(u.query.encode("utf-8")).hexdigest()[:8]
        rel = os.path.join(d, "%s__%s%s" % (stem, h, ("." + ext) if ext else ""))
    rel = rel.replace("\\", "/")
    if len(rel) > 190:
        d, f = os.path.split(rel)
        stem, dot, ext = f.rpartition(".")
        ext = ext if dot else ""
        rel = os.path.join("assets", host, "_long",
                           hashlib.sha1(url.encode("utf-8")).hexdigest()[:16] +
                           (("." + ext) if ext else ""))
    return rel


def rel_from(page_dir, local_abs):
    return os.path.relpath(local_abs, page_dir).replace("\\", "/")


def rewrite_url(ref, base, page_dir, url_map):
    a = abs_url(ref, base)
    if not a:
        return ref
    loc = url_map.get(a)
    if not loc:
        return ref
    return rel_from(page_dir, loc)


def rewrite_srcset(val, base, page_dir, url_map):
    out = []
    for part in val.split(","):
        p = part.strip()
        if not p:
            continue
        toks = p.split()
        toks[0] = rewrite_url(toks[0], base, page_dir, url_map)
        out.append(" ".join(toks))
    return ", ".join(out)


def rewrite_css_urls(css, base, page_dir, url_map, import_base=None):
    def ru(m):
        q, u = m.group(1), m.group(2)
        if u.lower().startswith(("data:", "blob:")):
            return m.group(0)
        return 'url(%s%s%s)' % (q, rewrite_url(u, base, page_dir, url_map), q)

    def ri(m):
        q, u = m.group(1), m.group(2)
        if u.lower().startswith(("data:", "blob:")):
            return m.group(0)
        nb = import_base or base
        return '@import %s%s%s' % (q, rewrite_url(u, nb, page_dir, url_map), q)

    css = URLFUNC_RE.sub(ru, css)
    if import_base is None:
        css = IMPORT_RE.sub(ri, css)
    return css


def parse_attrs(attr_text):
    d = {}
    for m in ATTR_RE.finditer(attr_text):
        name = m.group(1).lower()
        val = m.group(3) if m.group(3) is not None else (
            m.group(4) if m.group(4) is not None else (m.group(5) or ""))
        d.setdefault(name, val)
    return d


def collect_refs(text, base):
    """返回 [(url, kind)]，按出现顺序去重。"""
    refs, seen = [], set()

    def add(u, kind):
        if u and u not in seen:
            seen.add(u)
            refs.append((u, kind))

    for m in TAG_RE.finditer(text):
        tag = m.group(1).lower()
        a = parse_attrs(m.group(2))
        if tag == "base":
            continue
        if tag == "link":
            rel = (a.get("rel") or "").lower()
            href = a.get("href")
            if href:
                kind = "css" if "stylesheet" in rel else kind_of(abs_url(href, base) or "", "other")
                add(abs_url(href, base), kind)
            continue
        if tag == "script":
            add(abs_url(a.get("src"), base), "js")
            continue
        if tag == "img":
            add(abs_url(a.get("src"), base), "image")
            add(abs_url(a.get("data-src"), base), "image")
            if a.get("srcset"):
                for part in a["srcset"].split(","):
                    toks = part.strip().split()
                    if toks:
                        add(abs_url(toks[0], base), "image")
            continue
        if tag == "source":
            add(abs_url(a.get("src"), base), "image")
            if a.get("srcset"):
                for part in a["srcset"].split(","):
                    toks = part.strip().split()
                    if toks:
                        add(abs_url(toks[0], base), "image")
            continue
        if tag == "video":
            add(abs_url(a.get("poster"), base), "image")
            continue
        if tag == "iframe":
            add(abs_url(a.get("src"), base), "html")
            continue

    for m in URLFUNC_RE.finditer(text):
        add(abs_url(m.group(2), base), "image")
    for m in IMPORT_RE.finditer(text):
        add(abs_url(m.group(2), base), "css")

    for m in STYLE_RE.finditer(text):
        for u in URLFUNC_RE.finditer(m.group(1)):
            add(abs_url(u.group(2), base), "image")
    return refs


def img_size(path):
    try:
        b = open(path, "rb").read(65536)
    except Exception:
        return None
    if len(b) < 16:
        return None
    if b[:8] == b"\x89PNG\r\n\x1a\n" and len(b) >= 24:
        return int.from_bytes(b[16:20], "big"), int.from_bytes(b[20:24], "big")
    if b[:3] == b"GIF" and len(b) >= 10:
        return int.from_bytes(b[6:8], "little"), int.from_bytes(b[8:10], "little")
    if b[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(b):
            if b[i] != 0xFF:
                i += 1
                continue
            mk = b[i + 1]
            if mk in (0xD8, 0xD9) or 0xD0 <= mk <= 0xD7:
                i += 2
                continue
            ln = int.from_bytes(b[i + 2:i + 4], "big")
            if mk in list(range(0xC0, 0xD0)) and mk not in (0xC4, 0xC8, 0xCC):
                h = int.from_bytes(b[i + 5:i + 7], "big")
                w = int.from_bytes(b[i + 7:i + 9], "big")
                return (w, h) if w and h else None
            i += 2 + ln
        return None
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        if b[12:16] == b"VP8X" and len(b) >= 30:
            w = 1 + int.from_bytes(b[24:27], "little")
            h = 1 + int.from_bytes(b[27:30], "little")
            return w, h
        if b[12:16] == b"VP8 " and len(b) >= 30:
            w = int.from_bytes(b[26:28], "little") & 0x3FFF
            h = int.from_bytes(b[28:30], "little") & 0x3FFF
            return w, h
        if b[12:16] == b"VP8L" and len(b) >= 25:
            b0, b1, b2, b3 = b[21], b[22], b[23], b[24]
            w = 1 + (((b1 & 0x3F) << 8) | b0)
            h = 1 + (((b3 & 0xF) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))
            return w, h
        return None
    head = b[:4096]
    if b"<svg" in head.lower():
        t = head.decode("utf-8", "ignore")
        m = re.search(r'viewBox\s*=\s*["\']\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)', t, re.I)
        if m:
            return int(float(m.group(1))), int(float(m.group(2)))
        wm = re.search(r'width\s*=\s*["\']([\d.]+)', t, re.I)
        hm = re.search(r'height\s*=\s*["\']([\d.]+)', t, re.I)
        if wm and hm:
            return int(float(wm.group(1))), int(float(hm.group(1)))
    return None


def capture_page(cfg):
    slug, entry = cfg["slug"], cfg["url"]
    out = os.path.join(ROOT, cfg["dir"].replace("/", os.sep))
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)
    page_dir = out
    t0 = time.time()
    meta = {"slug": slug, "entry": entry, "captured_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "viewport": [cfg["vw"], cfg["vh"]], "note": cfg.get("note", ""),
            "items": [], "failed": []}
    try:
        data, ctype, final = fetch(entry)
    except Exception as e:
        meta.update(status="FETCH_FAIL", error=str(e))
        json.dump(meta, open(os.path.join(out, "page.meta.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)
        log("[FAIL] %-16s %s : %s" % (slug, entry, e))
        return meta

    text, enc = decode_html(data, ctype)
    base_url = final
    meta.update(status="OK", final_url=final, http_charset=enc,
                entry_bytes=len(data), entry_content_type=ctype)
    if final != entry:
        meta["note"] = (meta.get("note") or "") + "；302→" + final

    # 解析 <base href> 后再删除，避免引擎按远端 base 解析
    mb = re.search(r"<base\b[^>]*href\s*=\s*[\"']([^\"']+)", text, re.I)
    if mb:
        base_url = abs_url(mb.group(1), final) or final
        text = re.sub(r"<base\b[^>]*>", "", text, flags=re.I)

    refs = collect_refs(text, base_url)
    by_kind = {"css": [], "image": [], "js": [], "html": [], "other": [], "skip": []}
    for u, k in refs:
        by_kind.setdefault(k, []).append(u)

    url_map, sizes, total, nfiles = {}, {}, 0, 0

    def fetch_kind(kinds, cap):
        nonlocal total, nfiles
        per = CAP.get(kinds, (10, 2 * 1024 * 1024))
        got = 0
        for u in by_kind.get(kinds, []):
            if u in url_map or total >= MAX_TOTAL or nfiles >= MAX_FILES or got >= per[0]:
                continue
            if ext_of(u) in SKIP_EXT:
                continue
            loc_rel = local_path_for(u)
            loc_abs = os.path.join(page_dir, loc_rel.replace("/", os.sep))
            if os.path.exists(loc_abs):
                url_map[u] = loc_abs
                if kinds == "image":
                    wh = img_size(loc_abs)
                    if wh:
                        sizes[loc_rel] = wh
                continue
            try:
                d, ct, fin = fetch(u, referer=final, timeout=25)
            except Exception as e:
                meta["failed"].append({"url": u, "error": str(e)})
                continue
            if len(d) > per[1]:
                meta["failed"].append({"url": u, "error": "too large %d" % len(d)})
                continue
            os.makedirs(os.path.dirname(loc_abs), exist_ok=True)
            with open(loc_abs, "wb") as f:
                f.write(d)
            total += len(d)
            nfiles += 1
            got += 1
            url_map[u] = loc_abs
            meta["items"].append({"url": u, "final_url": fin, "local": loc_rel,
                                  "bytes": len(d), "content_type": ct,
                                  "sha1": hashlib.sha1(d).hexdigest()[:16]})
            if kinds == "css":
                css, _ = decode_html(d, ct)
                for cu, ck in collect_refs(css, fin):
                    if ck in ("image", "css", "skip") and cu not in url_map:
                        by_kind.setdefault(ck, []).append(cu)
            if kinds == "image":
                wh = img_size(loc_abs)
                if wh:
                    sizes[loc_rel] = wh
        return got

    # CSS 优先，多轮以收拢 @import；再图片，再 JS
    for _ in range(3):
        if fetch_kind("css", "css") == 0:
            break
    fetch_kind("image", "image")
    fetch_kind("js", "js")
    fetch_kind("html", "other")

    # ---------- 改写 HTML ----------
    def rw_attr(val, base, ref_dir):
        return rewrite_url(val, base, ref_dir, url_map)

    def tag_sub(m):
        tag = m.group(1).lower()
        raw = m.group(2)
        if tag == "base":
            return ""
        a = parse_attrs(raw)

        def setattr(name, newv):
            nonlocal raw
            pat = re.compile(r"(?<![-\w:])(%s\s*=\s*)(\"([^\"]*)\"|'([^']*)'|([^\s>]+))" % re.escape(name), re.I)
            q = '"'
            raw = pat.sub(lambda x: x.group(1) + q + newv.replace(q, "&quot;") + q, raw, count=1)

        if tag == "link" and a.get("href"):
            setattr("href", rw_attr(a["href"], base_url, page_dir))
        elif tag == "script" and a.get("src"):
            setattr("src", rw_attr(a["src"], base_url, page_dir))
        elif tag == "img":
            for k in ("src", "data-src", "data-original"):
                if a.get(k):
                    setattr(k, rw_attr(a[k], base_url, page_dir))
            if a.get("srcset"):
                setattr("srcset", rewrite_srcset(a["srcset"], base_url, page_dir, url_map))
        elif tag in ("source", "video", "iframe"):
            for k in ("src", "poster"):
                if a.get(k):
                    setattr(k, rw_attr(a[k], base_url, page_dir))
            if a.get("srcset"):
                setattr("srcset", rewrite_srcset(a["srcset"], base_url, page_dir, url_map))
        if a.get("style"):
            setattr("style", rewrite_css_urls(a["style"], base_url, page_dir, url_map))
        return "<%s%s>" % (m.group(1), raw)

    text = TAG_RE.sub(tag_sub, text)

    def style_sub(m):
        css = rewrite_css_urls(m.group(1), base_url, page_dir, url_map)
        return m.group(0)[:m.group(0).lower().index(">") + 1] + css + "</style>"

    text = STYLE_RE.sub(style_sub, text)
    text = META_CHARSET_RE.sub(r'\1"utf-8"', text)

    # ---------- 改写已下载 CSS（url 页面相对，@import CSS 相对） ----------
    for u, loc_abs in list(url_map.items()):
        if ext_of(u) != "css" and not loc_abs.lower().endswith(".css"):
            continue
        try:
            css = open(loc_abs, "r", encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        css_dir = os.path.dirname(loc_abs)
        new = rewrite_css_urls(css, u, page_dir, url_map, import_base=u)
        # @import 需要相对 CSS 自身目录（引擎 inliner 的解析口径）
        def imp_fix(m):
            q, ref = m.group(1), m.group(2)
            a = abs_url(ref, u)
            if a and a in url_map:
                ref = os.path.relpath(url_map[a], css_dir).replace("\\", "/")
            return "@import %s%s%s" % (q, ref, q)
        new = IMPORT_RE.sub(imp_fix, new)
        open(loc_abs, "w", encoding="utf-8").write(new)

    # 顶部保留原始 URL 便于还原
    text = ('<!-- EA-OFFLINE-SNAPSHOT entry="%s" final="%s" captured="%s" -->\n'
            % (entry, html_escape(final), meta["captured_at"])) + text
    with open(os.path.join(out, "page.html"), "w", encoding="utf-8") as f:
        f.write(text)

    img_list = []
    for loc_rel, wh in sorted(sizes.items()):
        img_list.append({"url": loc_rel, "w": wh[0], "h": wh[1]})
    json.dump(img_list, open(os.path.join(out, "images.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    meta.update(html_bytes=len(text.encode("utf-8")), assets=nfiles,
                asset_bytes=total, image_sizes=len(img_list), elapsed_s=round(time.time() - t0, 2))
    json.dump(meta, open(os.path.join(out, "manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    meta_out = {k: v for k, v in meta.items() if k != "items"}
    meta_out["items"] = len(meta["items"])
    json.dump(meta_out, open(os.path.join(out, "page.meta.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    log("[OK]   %-16s %s  html=%dB assets=%d/%dB imgs=%d %.1fs"
        % (slug, final, len(text.encode("utf-8")), nfiles, total, len(img_list),
           meta["elapsed_s"]))
    return meta


def html_escape(s):
    return htmllib.escape(s, quote=True)


def main():
    cfgs = json.load(open(os.path.join(ROOT, "sites.json"), "r", encoding="utf-8"))
    ok = 0
    for c in cfgs:
        m = capture_page(c)
        if m.get("status") == "OK":
            ok += 1
    log("\n抓取完成：%d/%d 页成功" % (ok, len(cfgs)))


if __name__ == "__main__":
    main()
