#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, sys, urllib.parse, urllib.request, json

UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EABrowser/1.0"
BASE = "https://www.baidu.com/"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": BASE})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()

def rel_of(u):
    p = urllib.parse.urlsplit(u)
    rel = (p.netloc + p.path + (("?" + p.query) if p.query else "")).replace("//", "/").lstrip("/")
    return rel

def urls_in_css(css):
    out = []
    for m in re.finditer(r"""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", css, re.I):
        out.append(m.group(2))
    for m in re.finditer(r"""@import\s+(?:url\(\s*)?(['"]?)([^)'";]+)\1""", css, re.I):
        out.append(m.group(2))
    return out

def main():
    src, out, base = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(src, "r", encoding="utf-8") as f:
        text = f.read()
    os.makedirs(os.path.join(out, base), exist_ok=True)

    refs = []
    for m in re.finditer(r"""<(?:link|script|img|source)\b[^>]*>""", text, re.I):
        for a in re.finditer(r"""([a-zA-Z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", m.group(0)):
            k = (a.group(1) or "").lower()
            v = a.group(2) or a.group(3) or a.group(4) or ""
            if k in ("href", "src", "data-src") and v:
                refs.append(v)
    refs += [m.group(2) for m in re.finditer(r"""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", text, re.I)]

    mapping = {}
    for v in refs:
        if v.startswith(("data:", "#", "javascript:")):
            continue
        u = urllib.parse.urljoin(BASE, v)
        if urllib.parse.urlsplit(u).scheme in ("http", "https"):
            mapping[v] = u

    ok, fail, fetched = 0, [], {}
    queue = list(mapping.values())
    while queue:
        u = queue.pop(0)
        if u in fetched:
            continue
        dst = os.path.join(out, base, rel_of(u))
        try:
            data = get(u)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(data)
            fetched[u] = dst
            ok += 1
            if urllib.parse.urlsplit(u).path.lower().endswith(".css"):
                css = data.decode("utf-8", "replace")
                for v in urls_in_css(css):
                    if v.startswith(("data:", "#")):
                        continue
                    cu = urllib.parse.urljoin(u, v)
                    if urllib.parse.urlsplit(cu).scheme in ("http", "https") and cu not in fetched:
                        queue.append(cu)
        except Exception as e:
            fail.append([u, str(e)])

    new_text = text
    for v, u in mapping.items():
        local = base + "/" + rel_of(u)
        for q in (v, u):
            for a, b in (('"%s"' % q, '"%s"' % local), ("'%s'" % q, "'%s'" % local),
                         ("=%s>" % q, '="%s">' % local), ("=%s " % q, '="%s" ' % local)):
                new_text = new_text.replace(a, b)

    page = os.path.join(out, "page.html")
    with open(page, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump({"src": src, "ok": ok, "fail": fail, "refs": len(mapping)}, f, ensure_ascii=False, indent=1)
    print("page=%d files=%d fail=%d" % (len(new_text), ok, len(fail)))

main()
