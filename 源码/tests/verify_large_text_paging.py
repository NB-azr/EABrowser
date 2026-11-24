"""大文本分页读取与增量写的镜像验证。

对应实现：android/BrowserApp/app/src/main/java/com/ea/browser/tools/LargeTextFile.kt
- 打开只顺序扫描一遍文件建立页边界（1MB 一页、按换行对齐、单页上限 16MB）
- 翻页只读当前页字节
- 保存逐页转发到临时文件，只重编码被修改的页
"""
import os
import sys
import time

PAGE_BYTES = 1024 * 1024
SCAN_CHUNK = 64 * 1024
MAX_PAGE_BYTES = 16 * 1024 * 1024


def scan_pages(raw: bytes) -> list:
    """镜像 Kotlin 的 scanPages（UTF-8/单字节换行分支）。"""
    total = len(raw)
    if total <= 0:
        return [0]
    ends = []
    page_start = 0
    target = PAGE_BYTES
    pos = 0
    while pos < total:
        chunk_end = min(pos + SCAN_CHUNK, total)
        for i in range(pos, chunk_end):
            if raw[i] != 0x0A:
                continue
            end = i + 1
            if end >= target or end - page_start >= MAX_PAGE_BYTES:
                ends.append(end)
                page_start = end
                target = end + PAGE_BYTES
        pos = chunk_end
        if page_start < pos and pos - page_start >= MAX_PAGE_BYTES:
            ends.append(pos)
            page_start = pos
            target = pos + PAGE_BYTES
    if not ends or ends[-1] < total:
        ends.append(total)
    return ends


def read_page(raw: bytes, ends: list, index: int) -> bytes:
    start = 0 if index == 0 else ends[index - 1]
    return raw[start:ends[index]]


def save_pages(raw: bytes, ends: list, changed: dict) -> bytes:
    out = bytearray()
    for index in range(len(ends)):
        if index in changed:
            out.extend(changed[index])
        else:
            out.extend(read_page(raw, ends, index))
    return bytes(out)


def make_text(path: str, target_bytes: int) -> None:
    line = "第 {0:06d} 行：大文件分页读取测试，包含中文与 ASCII 混合内容 ABCDEFGHIJKLMNOPQRSTUVWXYZ。\n"
    with open(path, "wb") as f:
        n = 0
        while f.tell() < target_bytes:
            f.write(line.format(n).encode("utf-8"))
            n += 1
        remaining = target_bytes - f.tell()
        if remaining > 0:
            f.write(b"\n" * remaining)


def main() -> int:
    base = os.path.dirname(os.path.abspath(__file__))
    tmpdir = os.path.join(base, "fixtures", "tmp")
    os.makedirs(tmpdir, exist_ok=True)
    big = os.path.join(tmpdir, "big50mb.txt")
    size = 50 * 1024 * 1024
    make_text(big, size)

    failures = 0
    started = time.perf_counter()
    with open(big, "rb") as f:
        raw = f.read()
    # 打开阶段 = 读前 64KB 探测编码 + 扫描页边界；这里只模拟扫描，读取内容用于断言
    head = raw[: 64 * 1024]
    ends = scan_pages(raw)
    scan_ms = (time.perf_counter() - started) * 1000

    print("file=%s size=%d head=%d kB" % (big, len(raw), len(head) // 1024))
    print("pages=%d scan_ms=%.1f" % (len(ends), scan_ms))

    if len(ends) != len(set(ends)) or ends != sorted(ends):
        print("FAIL page ends not monotonic")
        failures += 1
    if ends[-1] != len(raw):
        print("FAIL last page end %d != file size %d" % (ends[-1], len(raw)))
        failures += 1

    sizes = []
    prev = 0
    for e in ends:
        sizes.append(e - prev)
        prev = e
    print("page size min=%d max=%d avg=%d" % (min(sizes), max(sizes), sum(sizes) // len(sizes)))
    if max(sizes) > PAGE_BYTES + 64 * 1024:
        print("FAIL page bigger than expected")
        failures += 1

    # 页边界必须落在换行之后（最后一页除外）
    for e in ends[:-1]:
        if raw[e - 1:e] != b"\n":
            print("FAIL page end %d not after newline" % e)
            failures += 1
            break

    # 重新拼接所有页 == 原文
    joined = b"".join(read_page(raw, ends, i) for i in range(len(ends)))
    if joined != raw:
        print("FAIL joined pages differ from original")
        failures += 1

    # 只读当前页的内存上界
    page_mem = max(len(read_page(raw, ends, i)) for i in range(len(ends)))
    print("single page max bytes=%d (%.1f kB)" % (page_mem, page_mem / 1024))

    # 增量写：改第 3 页，其余页原样转发
    index = min(2, len(ends) - 1)
    start = 0 if index == 0 else ends[index - 1]
    changed = {index: b"[PAGE 3 EDITED]\n" + raw[start:ends[index]]}
    saved = save_pages(raw, ends, changed)
    expect = raw[:start] + changed[index] + raw[ends[index]:]
    if saved != expect:
        print("FAIL incremental save mismatch")
        failures += 1

    # 再打开保存结果，页边界应能重建
    ends2 = scan_pages(saved)
    if ends2[-1] != len(saved):
        print("FAIL rescan after save")
        failures += 1

    os.remove(big)
    print("RESULT failures=%d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
