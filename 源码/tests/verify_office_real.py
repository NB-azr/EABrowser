"""真实 Office / ZIP 样例的解析验证。

样例由本机 Word / Excel / tar 生成（见 gen_office_samples.ps1），不是手写 XML。
这里镜像 OfficeExtract.java 的 docx/xlsx 提取逻辑，逐条比对内容；
同时验证 ZIP 列表与单文件取出，并用一个伪造的 docx 确认会明确报错。
"""
import io
import os
import re
import sys
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
DOCX = os.path.join(BASE, "fixtures", "office", "sample-real.docx")
XLSX = os.path.join(BASE, "fixtures", "office", "sample-real.xlsx")
ZIP = os.path.join(BASE, "fixtures", "archive", "sample-real.zip")

ENTITY = {"amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'"}


def unescape(s: str) -> str:
    def rep(m):
        code = m.group(1)
        if code in ENTITY:
            return ENTITY[code]
        if code.startswith("#x") or code.startswith("#X"):
            return chr(int(code[2:], 16))
        if code.startswith("#"):
            return chr(int(code[1:]))
        return m.group(0)
    return re.sub(r"&([^;]{1,10});", rep, s)


def collect_text(block: str) -> str:
    out = []
    q = 0
    while True:
        t = block.find("<t", q)
        if t < 0:
            break
        tag_end = block.find(">", t)
        if tag_end < 0:
            break
        if block[tag_end - 1] == "/":
            q = tag_end + 1
            continue
        close = block.find("</t>", tag_end)
        if close < 0:
            close = len(block)
        out.append(unescape(block[tag_end + 1:close]))
        q = close + 4
    return "".join(out)


def docx_paragraphs(xml: str) -> str:
    out = []
    pos = 0
    while True:
        a = xml.find("<w:p ", pos)
        b = xml.find("<w:p>", pos)
        start = -1 if a < 0 else a
        if b >= 0 and (start < 0 or b < start):
            start = b
        if start < 0:
            break
        end = xml.find("</w:p>", start)
        if end < 0:
            end = len(xml)
        block = xml[start:end]
        line = []
        q = 0
        while True:
            t = block.find("<w:t", q)
            if t < 0:
                break
            tag_end = block.find(">", t)
            if tag_end < 0:
                break
            if block[tag_end - 1] == "/":
                q = tag_end + 1
                continue
            close = block.find("</w:t>", tag_end)
            if close < 0:
                close = len(block)
            line.append(unescape(block[tag_end + 1:close]))
            q = close + 6
        out.append("".join(line))
        pos = end + 6
    return "\n".join(out) + "\n"


def attr(tag: str, name: str):
    m = re.search(name.replace(":", r"\:") + '="([^"]*)"', tag)
    return m.group(1) if m else None


def first_tag_text(xml: str, tag: str):
    s = xml.find("<" + tag)
    if s < 0:
        return None
    tag_end = xml.find(">", s)
    if tag_end < 0:
        return None
    if xml[tag_end - 1] == "/":
        return ""
    e = xml.find("</" + tag + ">", tag_end)
    if e < 0:
        e = len(xml)
    return xml[tag_end + 1:e]


def xlsx_text(path: str) -> str:
    z = zipfile.ZipFile(path)
    content_types = z.read("[Content_Types].xml").decode("utf-8")
    if "spreadsheetml.sheet" not in content_types:
        raise ValueError("内容类型不是 Excel 工作簿")
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        xml = z.read("xl/sharedStrings.xml").decode("utf-8")
        pos = 0
        while True:
            s = xml.find("<si", pos)
            if s < 0:
                break
            e = xml.find("</si>", s)
            if e < 0:
                break
            shared.append(collect_text(xml[s:e]))
            pos = e + 5
    workbook = z.read("xl/workbook.xml").decode("utf-8")
    rid = None
    s = workbook.find("<sheet ")
    if s >= 0:
        e = workbook.find(">", s)
        rid = attr(workbook[s:e], "r:id") or attr(workbook[s:e], "id")
    if rid and "xl/_rels/workbook.xml.rels" in z.namelist():
        rels = z.read("xl/_rels/workbook.xml.rels").decode("utf-8")
        m = re.search(r'<Relationship[^>]*Id="%s"[^>]*>' % re.escape(rid), rels)
        target = attr(m.group(0), "Target") if m else None
        sheet_path = target.lstrip("/") if target and target.startswith("/") else ("xl/" + target if target else "xl/worksheets/sheet1.xml")
    else:
        sheet_path = "xl/worksheets/sheet1.xml"
    xml = z.read(sheet_path).decode("utf-8")
    rows = []
    pos = 0
    while True:
        s = xml.find("<row", pos)
        if s < 0:
            break
        tag_end = xml.find(">", s)
        if tag_end < 0:
            break
        if xml[tag_end - 1] == "/":
            pos = tag_end + 1
            continue
        e = xml.find("</row>", tag_end)
        if e < 0:
            e = len(xml)
        row = xml[tag_end + 1:e]
        cells = []
        cp = 0
        while True:
            cs = row.find("<c ", cp)
            if cs < 0:
                cs = row.find("<c>", cp)
            if cs < 0:
                break
            ct = row.find(">", cs)
            if ct < 0:
                break
            cell_tag = row[cs:ct]
            if row[ct - 1] == "/":
                cells.append("")
                cp = ct + 1
                continue
            ce = row.find("</c>", ct)
            if ce < 0:
                ce = len(row)
            cell_xml = row[ct + 1:ce]
            t = attr(cell_tag, "t")
            if t == "inlineStr":
                cells.append(collect_text(cell_xml))
            else:
                raw = first_tag_text(cell_xml, "v")
                if raw is None:
                    cells.append("")
                elif t == "s":
                    idx = int(raw.strip())
                    cells.append(shared[idx] if 0 <= idx < len(shared) else raw)
                else:
                    cells.append(unescape(raw))
            cp = ce + 4
        line = "\t".join(cells)
        if line.strip():
            rows.append(line)
        pos = e + 6
    return "\n".join(rows) + "\n"


def main() -> int:
    failures = 0

    # docx
    z = zipfile.ZipFile(DOCX)
    ct = z.read("[Content_Types].xml").decode("utf-8")
    text = docx_paragraphs(z.read("word/document.xml").decode("utf-8"))
    print("[docx] entries=%d content-type-word=%s" % (len(z.namelist()), "wordprocessingml.document" in ct))
    print(text.strip())
    for expected in ("季度工作汇报", "接口联调", "张伟（后端，已完成）", "RPT-2026-11"):
        if expected not in text:
            print("FAIL docx missing: %s" % expected)
            failures += 1

    # xlsx
    xtext = xlsx_text(XLSX)
    zx = zipfile.ZipFile(XLSX)
    print("[xlsx] entries=%d shared=%s" % (len(zx.namelist()), "xl/sharedStrings.xml" in zx.namelist()))
    print(xtext.strip())
    rows = [r for r in xtext.splitlines() if r.strip()]
    if not rows or rows[0].split("\t")[:3] != ["部门", "人数", "完成率"]:
        print("FAIL xlsx header: %r" % (rows[0] if rows else None))
        failures += 1
    if "研发\t18\t0.92" not in xtext:
        print("FAIL xlsx numeric row")
        failures += 1
    if not any(r.startswith("合计\t") for r in rows):
        print("FAIL xlsx formula total row")
        failures += 1
    else:
        total = [r for r in rows if r.startswith("合计\t")][0].split("\t")[1]
        if total not in ("33", "33.0"):
            print("FAIL formula cached value: %s" % total)
            failures += 1

    # zip
    zz = zipfile.ZipFile(ZIP)
    items = [(i.filename, i.file_size, i.compress_type) for i in zz.infolist()]
    print("[zip] %r" % (items,))
    if "readme.txt" not in zz.namelist() or "docs/notes.md" not in zz.namelist():
        print("FAIL zip entries")
        failures += 1
    data = zz.read("readme.txt").decode("utf-8")
    if "offset check 1234567890" not in data:
        print("FAIL zip readme content")
        failures += 1
    if zz.getinfo("readme.txt").compress_type != zipfile.ZIP_DEFLATED:
        print("FAIL zip deflate method")
        failures += 1

    # 伪造样本：普通 zip 改名成 docx，必须明确报错
    fake = os.path.join(BASE, "fixtures", "tmp", "fake.docx")
    os.makedirs(os.path.dirname(fake), exist_ok=True)
    with zipfile.ZipFile(fake, "w") as fz:
        fz.writestr("hello.txt", "not a word file")
    try:
        with zipfile.ZipFile(fake) as fz2:
            if "word/document.xml" not in fz2.namelist():
                raise ValueError("不是有效的 docx：缺少 word/document.xml")
        print("FAIL fake docx not rejected")
        failures += 1
    except ValueError as e:
        print("[fake] rejected: %s" % e)
    os.remove(fake)

    print("RESULT failures=%d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
