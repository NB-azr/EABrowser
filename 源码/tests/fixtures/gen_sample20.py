#!/usr/bin/env python3
# 生成 20 页的最小 PDF（无压缩），用于 PDF 查看器 300dpi / 高倍缩放自测。
import os

W, H = 612, 792  # US Letter，72dpi 基准
PAGES = 20


def content_stream(n):
    lines = [
        "0.9 0.9 0.9 rg",
        "0 0 612 792 re F",
        "0.2 0.35 0.7 RG",
        "2 w",
        "48 48 516 696 re S",
        "0 0 0 rg",
        "BT /F1 40 Tf 72 680 Td (Page %d / %d) Tj ET" % (n, PAGES),
        "BT /F1 18 Tf 72 630 Td (PDF 300dpi self-test page) Tj ET",
        "BT /F1 12 Tf 72 570 Td (Body line A - the quick brown fox jumps over the lazy dog.) Tj ET",
        "BT /F1 12 Tf 72 548 Td (Body line B - 0123456789 0123456789 0123456789 0123456789) Tj ET",
        "BT /F1 12 Tf 72 526 Td (Body line C - rendering stress sample for memory measurement.) Tj ET",
        "0.85 0.15 0.15 rg",
        "72 %d 468 18 re F" % (120 + (n % 5) * 90),
        "0.15 0.6 0.25 rg",
        "72 %d 468 18 re F" % (120 + ((n + 2) % 5) * 90),
    ]
    return "\n".join(lines) + "\n"


def build():
    objs = []  # (obj_num, body_bytes)

    objs.append((1, b"<< /Type /Catalog /Pages 2 0 R >>"))
    kids = " ".join("%d 0 R" % (3 + i * 2) for i in range(PAGES))
    objs.append((2, ("<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, PAGES)).encode()))

    font_num = 3 + PAGES * 2
    for i in range(PAGES):
        page_num = 3 + i * 2
        stream_num = page_num + 1
        body = ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %d %d] "
                "/Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>"
                % (W, H, font_num, stream_num)).encode()
        objs.append((page_num, body))
        data = content_stream(i + 1).encode()
        stream = ("<< /Length %d >>\nstream\n" % len(data)).encode() + data + b"endstream"
        objs.append((stream_num, stream))

    objs.append((font_num, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"))
    objs.sort(key=lambda x: x[0])

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    max_num = 0
    for num, body in objs:
        offsets[num] = len(out)
        out += ("%d 0 obj\n" % num).encode() + body + b"\nendobj\n"
        max_num = max(max_num, num)

    xref_pos = len(out)
    out += ("xref\n0 %d\n" % (max_num + 1)).encode()
    out += b"0000000000 65535 f \n"
    for num in range(1, max_num + 1):
        if num in offsets:
            out += ("%010d 00000 n \n" % offsets[num]).encode()
        else:
            out += b"0000000000 00000 f \n"
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
            % (max_num + 1, xref_pos)).encode()
    return bytes(out)


if __name__ == "__main__":
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample20.pdf")
    data = build()
    with open(path, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (path, len(data)))
