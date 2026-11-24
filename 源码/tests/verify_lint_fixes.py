#!/usr/bin/env python3
# lint 修复静态回归（docs/lint-errors.md：MissingSuperCall x3、AppCompatCustomView、RestrictedApi）
# 只做静态核对，不构建 APK。
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SRC_ROOT = os.path.join(ROOT, "android", "BrowserApp", "app", "src", "main")
if not os.path.isdir(SRC_ROOT):
    SRC_ROOT = os.path.join(ROOT, "src", "android", "app", "src", "main")

JAVA = os.path.join(SRC_ROOT, "java", "com", "ea", "browser")
TOOLS = os.path.join(JAVA, "tools")

failures = 0


def check(name, ok, detail=""):
    global failures
    if ok:
        print("[ok] " + name)
    else:
        failures += 1
        print("[fail] " + name + ((" -> " + detail) if detail else ""))


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def block_after(text, marker, lines=8):
    idx = text.index(marker)
    return "\n".join(text[idx:].splitlines()[:lines])


# 1) HtmlEditorActivity.onBackPressed 调用 super，确认流程改为返回 Boolean
html = read(os.path.join(TOOLS, "HtmlEditorActivity.kt"))
check("HtmlEditor confirmExit 返回 Boolean", "private fun confirmExit(): Boolean" in html)
block = block_after(html, "override fun onBackPressed()", 4)
check("HtmlEditor onBackPressed 调 super", "super.onBackPressed()" in block, block)

# 2) PdfViewerActivity.onBackPressed 调用 super
pdf = read(os.path.join(TOOLS, "PdfViewerActivity.kt"))
block = block_after(pdf, "override fun onBackPressed()", 4)
check("PdfViewer onBackPressed 调 super", "super.onBackPressed()" in block, block)
check("PdfViewer onBackPressed 不再直接 finish", "finish()" not in block, block)

# 3) TextEditorActivity.onBackPressed 调用 super，确认流程改为返回 Boolean
text = read(os.path.join(TOOLS, "TextEditorActivity.kt"))
check("TextEditor confirmExit 返回 Boolean", "private fun confirmExit(): Boolean" in text)
block = block_after(text, "override fun onBackPressed()", 4)
check("TextEditor onBackPressed 调 super", "super.onBackPressed()" in block, block)

# 4) ZoomableImageView 继承 AppCompatImageView
zoom = read(os.path.join(TOOLS, "ZoomableImageView.kt"))
check("ZoomableImageView 导入 AppCompatImageView",
      "import androidx.appcompat.widget.AppCompatImageView" in zoom)
check("ZoomableImageView 继承 AppCompatImageView", ": AppCompatImageView(" in zoom)
check("ZoomableImageView 不再继承 android.widget.ImageView",
      "import android.widget.ImageView" not in zoom and ": ImageView(" not in zoom)

# 5) SvgRasterizer 不再使用受限 API，改用本地 path 解析
svg = read(os.path.join(JAVA, "SvgRasterizer.kt"))
check("不再引用 PathParser API",
      "androidx.core.graphics.PathParser" not in svg and
      "PathParser.createPathFromPathData" not in svg)
check("未用 SuppressLint 绕过受限 API", "RestrictedApi" not in svg)
check("存在本地 parsePathData", "private fun parsePathData(d: String): Path" in svg)
check("存在椭圆弧转贝塞尔", "private fun arcToCubics(" in svg)
check("调用点走本地解析", "try { parsePathData(d) } catch" in svg)
for cmd in ["M", "L", "H", "V", "C", "S", "Q", "T", "A", "Z"]:
    low = cmd.lower()
    check("path 指令覆盖 %s/%s" % (cmd, low), ("'%s', '%s' ->" % (cmd, low)) in svg or
          ("'%s', '%s' ->" % (low, cmd)) in svg)

print("RESULT failures=%d" % failures)
raise SystemExit(0 if failures == 0 else 1)
