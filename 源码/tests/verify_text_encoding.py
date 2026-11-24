#!/usr/bin/env python3
# 文本编码识别/保存回归（镜像 FileTypes.detectAndDecode / writeText 的判定顺序）。
# 生成 fixtures/enc 下的样例文件并校验：编码识别正确、读出的文本一致、按原编码写回字节不变。
import codecs
import os

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "enc")
SAMPLE = "中文编码测试 UTF-8 GBK UTF-16 123 ABC\n第二行：浏览器文本编辑器\n"


def detect(raw, truncated=False):
    """返回 (charset, text, utf8_bom)，顺序与 Kotlin 实现一致。"""
    if raw[:3] == b"\xef\xbb\xbf":
        return "UTF-8", decode(raw[3:], "utf-8"), True
    if raw[:2] == b"\xff\xfe":
        return "UTF-16LE", decode(raw[2:], "utf-16-le"), False
    if raw[:2] == b"\xfe\xff":
        return "UTF-16BE", decode(raw[2:], "utf-16-be"), False
    for cs in ("utf-8", "gbk"):
        try:
            return cs.upper() if cs != "utf-8" else "UTF-8", raw.decode(cs), False
        except UnicodeDecodeError:
            if truncated:
                try:
                    text = raw.decode(cs, errors="replace")
                except Exception:
                    continue
                if text.count("\ufffd") <= 8:
                    return cs.upper() if cs != "utf-8" else "UTF-8", text, False
    return "ISO-8859-1", raw.decode("latin-1"), False


def decode(raw, cs):
    return raw.decode(cs)


def write_bytes(text, charset, utf8_bom):
    if charset == "UTF-16LE":
        return b"\xff\xfe" + text.encode("utf-16-le")
    if charset == "UTF-16BE":
        return b"\xfe\xff" + text.encode("utf-16-be")
    body = text.encode("utf-8" if charset == "UTF-8" else charset)
    return (b"\xef\xbb\xbf" if utf8_bom else b"") + body


def main():
    os.makedirs(BASE, exist_ok=True)
    cases = [
        ("utf8.txt", SAMPLE.encode("utf-8"), "UTF-8", False, SAMPLE),
        ("utf8_bom.txt", b"\xef\xbb\xbf" + SAMPLE.encode("utf-8"), "UTF-8", True, SAMPLE),
        ("gbk.txt", SAMPLE.encode("gbk"), "GBK", False, SAMPLE),
        ("utf16le_bom.txt", b"\xff\xfe" + SAMPLE.encode("utf-16-le"), "UTF-16LE", False, SAMPLE),
        ("utf16be_bom.txt", b"\xfe\xff" + SAMPLE.encode("utf-16-be"), "UTF-16BE", False, SAMPLE),
    ]
    failures = 0
    for name, data, want_cs, want_bom, want_text in cases:
        path = os.path.join(BASE, name)
        with open(path, "wb") as f:
            f.write(data)
        cs, text, bom = detect(data)
        ok = cs == want_cs and bom == want_bom and text == want_text
        saved = write_bytes(text, cs, bom)
        roundtrip = saved == data
        print("%-16s charset=%-8s bom=%-5s text=%s save_identical=%s" %
              (name, cs, bom, "OK" if text == want_text else "FAIL", roundtrip))
        if not (ok and roundtrip):
            failures += 1

    # 截断读取：2MB GBK 文本在双字节字符中间切断，应仍识别为 GBK，不退回 ISO-8859-1
    chunk = ("中文数据截断测试，一二三四五六七八九十。\n" * 100000).encode("gbk")
    cut = chunk[: (2 << 20) | 1]  # 奇数结尾，必然切断一个 GBK 双字节字符
    cs, text, bom = detect(cut, truncated=True)
    print("truncated_gbk   charset=%-8s bom=%-5s replacement=%d" %
          (cs, bom, text.count("\ufffd")))
    if cs != "GBK":
        failures += 1

    # 截断 UTF-8：末尾切断三字节字符同样不能误判成 GBK
    u8 = (SAMPLE * 40000).encode("utf-8")[: (2 << 20) | 2]
    cs, text, bom = detect(u8, truncated=True)
    print("truncated_utf8  charset=%-8s bom=%-5s replacement=%d" %
          (cs, bom, text.count("\ufffd")))
    if cs != "UTF-8":
        failures += 1

    print("RESULT failures=%d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
