#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, sys, urllib.parse, urllib.request, json

UA = sys.argv[4] if len(sys.argv) > 4 else \
    "Mozilla/5.0 (Linux; U; Android 4.4.2; zh-cn) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://www.baidu.com/"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(), r.headers.get("content-type", "")

def main():
    entry = sys.argv[1]
    out = sys.argv[2]
    base = sys.argv[3] if len(sys.argv) > 3 else "assets"
    body, ctype = get(entry)
    os.makedirs(out, exist_ok=True)
    os.makedirs(os.path.join(out, base), exist_ok=True)
    text = body.decode("utf-8", "replace")

    ents = re.compile(r"""<(?:link|script|img|source)\b[^>]*>""", re.I)
    attr = re.compile(r"""([a-zA-Z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    refs = []
    for m in ents.finditer(text):
        for a in attr.finditer(m.group(0)):
            k = a.group(1).lower()
            v = a.group(2) or a.group(3) or a.group(4) or ""
            if k in ("href", "src", "data-src") and v:
                refs.append(v)
    refs += re.findall(r"""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", text) and []
    for a, b in re.findall(r"""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", text):
        refs.append(b)

    mapping = {}
    for v in refs:
        if v.startswith(("data:", "#", "javascript:")):
            continue
        u = urllib.parse.urljoin(entry, v)
        if urllib.parse.urlsplit(u).scheme not in ("http", "https"):
            continue
        mapping[v] = u

    ok, fail = 0, []
    for v, u in mapping.items():
        p = urllib.parse.urlsplit(u)
        rel = p.netloc + p.path + (("?" + p.query) if p.query else "")
        rel = rel.replace("//", "/").lstrip("/")
        dst = os.path.join(out, base, rel)
        try:
            data, _ = get(u)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(data)
            ok += 1
        except Exception as e:
            fail.append([u, str(e)])

    def repl(m):
        v = m.group(0)
        return v

    new_text = text
    for v, u in mapping.items():
        p = urllib.parse.urlsplit(u)
        rel = p.netloc + p.path + (("?" + p.query) if p.query else "")
        rel = rel.replace("//", "/").lstrip("/")
        local = base + "/" + rel
        for q in (v, u):
            new_text = new_text.replace('"%s"' % q, '"%s"' % local).replace("'%s'" % q, "'%s'" % local)
            new_text = new_text.replace("=%s>" % q, "=%s>" % local)

    page = os.path.join(out, "page.html")
    with open(page, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    man = os.path.join(out, "manifest.json")
    with open(man, "w", encoding="utf-8") as f:
        json.dump({"entry": entry, "ok": ok, "fail": fail, "refs": len(mapping)}, f, ensure_ascii=False, indent=1)
    print("page=%d assets_ok=%d fail=%d refs=%d" % (len(new_text), ok, len(fail), len(mapping)))

main()
