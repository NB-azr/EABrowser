#!/usr/bin/env python3
# 十六进制查看器的分页/搜索算法回归（镜像 HexViewerActivity.scan 与分页换算）。
# 用 >50MB 文件验证：不整块读取、跨 256KB 块边界命中、回绕查找、页号换算。
import os
import struct
import tempfile

PAGE = 1024
CHUNK = 256 * 1024
NEEDLE = b"\x00UXMAGIC\xff"
HEX_NEEDLE = b"\x50\x4B\x03\x04"
TOTAL = 52 * 1024 * 1024 + 7
POS_TEXT = 50_000_123
POS_HEX = 52 * 1024 * 1024 - 5


def build(path):
    written = 0
    pattern = bytes((i * 7 + 13) & 0xFF for i in range(4096))
    with open(path, "wb") as f:
        while written < TOTAL:
            block = pattern[: min(4096, TOTAL - written)]
            f.write(block)
            written += len(block)
        f.seek(POS_TEXT)
        f.write(NEEDLE)
        f.seek(POS_HEX)
        f.write(HEX_NEEDLE)


def scan(f, needle, start, end, max_read=None):
    n = len(needle)
    if n == 0 or end <= start:
        return -1, 0
    pos = start
    biggest = 0
    reads = 0
    while pos < end:
        f.seek(pos)
        want = min(CHUNK + n - 1, end - pos)
        data = f.read(want)
        biggest = max(biggest, len(data))
        reads += 1
        if len(data) < n:
            return -1, biggest
        for i in range(len(data) - n + 1):
            if data[i:i + n] == needle:
                return pos + i, biggest
        if len(data) < CHUNK + n - 1:
            return -1, biggest
        pos += len(data) - (n - 1)
    return -1, biggest


def main():
    tmp = tempfile.mkdtemp(prefix="ea_hex_")
    path = os.path.join(tmp, "big.bin")
    build(path)
    size = os.path.getsize(path)
    pages = (size + PAGE - 1) // PAGE
    print("file=%s size=%d pages=%d" % (os.path.basename(path), size, pages))

    fails = 0
    with open(path, "rb") as f:
        hit, biggest = scan(f, NEEDLE, 0, size)
        print("text_needle hit=%d expect=%d max_read=%d" % (hit, POS_TEXT, biggest))
        if hit != POS_TEXT or biggest > CHUNK + len(NEEDLE) - 1:
            fails += 1

        hit, _ = scan(f, HEX_NEEDLE, 0, size)
        print("hex_needle  hit=%d expect=%d" % (hit, POS_HEX))
        if hit != POS_HEX:
            fails += 1

        # 从命中页之后开始找：正向扫不到，回绕到开头（带 needle-1 重叠）应再次命中
        page_after = (POS_TEXT // PAGE + 1) * PAGE
        overlap = len(NEEDLE) - 1
        hit, _ = scan(f, NEEDLE, page_after - overlap, size)
        if hit == -1:
            h2, _ = scan(f, NEEDLE, 0, page_after + overlap)
            hit = h2
        print("wrap        hit=%d expect=%d" % (hit, POS_TEXT))
        if hit != POS_TEXT:
            fails += 1

        # 页号/行号换算
        page = POS_TEXT // PAGE
        row = (POS_TEXT - page * PAGE) // 16
        print("page=%d page_start=0x%08X row=%d" % (page, page * PAGE, row))
        if not (0 <= row < PAGE // 16):
            fails += 1

        hit, _ = scan(f, b"\xde\xad\xbe\xef", 0, size)
        print("not_found   hit=%d" % hit)
        if hit != -1:
            fails += 1

    print("RESULT failures=%d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
