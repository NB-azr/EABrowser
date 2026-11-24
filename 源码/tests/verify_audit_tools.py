#!/usr/bin/env python3
# 工具批次审计修复静态回归（docs/bug-audit-app.md 第 1-10 条 + PDF 授权路径）
# 只做静态核对，不构建 APK；Office 写回的真实往返由 run_office_writeback.ps1 覆盖。
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 开发目录是 android/BrowserApp/app/src/main，发布目录是 src/android/app/src/main
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


def src(name, base=TOOLS):
    return read(os.path.join(base, name))


# 1. 图片查看器 P0：percent 必须是整数
image = src("ImageViewerActivity.kt")
check("图片 %d%% 用整数百分比", '"%d%%", zoomView.zoomPercent()' in image)
check("zoomPercent 返回 Int", "fun zoomPercent(): Int" in src("ZoomableImageView.kt"))

# 2. 文件管理器：30+ 全文件访问 + 私有目录降级
fm = src("FileManagerActivity.kt")
check("30+ 检查 isExternalStorageManager", "Environment.isExternalStorageManager()" in fm)
check("跳转全文件访问设置页", "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" in fm)
check("权限不足降级私有目录", "degradeToPrivateDir()" in fm and "getExternalFilesDir(null) ?: File(filesDir" in fm)
check("降级有明确提示", "No storage permission, app private directory only" in fm)
check("回到前台复查权限", "override fun onResume()" in fm and "waitingForStorage" in fm)
check("baseDir 与当前目录一起降级", "baseDir = getExternalFilesDir" in fm and "currentDir = baseDir" in fm)

# 3. OpenWith：后台复制、复制成功后再分发/退出
ow = src("OpenWithActivity.kt")
check("复制放后台线程", "Thread {" in ow and "resolveIncoming" in ow)
check("兜底对话框消失后再 finish", "ToolRouter.open(this, file, mime) { finish() }" in ow)
check("无文件才直接 finish", "No file to open" in ow)

# 4. HTML 编辑器：保存成功后才 finish
html = src("HtmlEditorActivity.kt")
check("saveActive 带完成回调", "private fun saveActive(onSaved: (() -> Unit)? = null)" in html)
check("写入成功才回调", "if (writeTab(tab, f)) onSaved?.invoke()" in html)
check("退出确认保存后 finish", "saveActive { finish() }" in html)


# 5. 文本编辑器：大文本后台着色 + 代次
te = src("TextEditorActivity.kt")
check("大文本阈值常量", "SPAN_BACKGROUND_CHARS" in te)
check("着色在后台线程", "Thread {" in te and "TextHighlighter.build(text, ext, true)" in te)
check("后台结果按代次丢弃", "seq != viewBuildSeq" in te)

# 6. 十六进制：翻页代次 + 线程中断 + 参数快照
hexv = src("HexViewerActivity.kt")
check("翻页代次", "pageSeq" in hexv and "seq != pageSeq" in hexv)
check("上一线程中断", "pageThread?.interrupt()" in hexv)
check("页码/命中/总长按快照传参", "formatPage(f, index, match, total)" in hexv)

# 7. 压缩包：一次遍历建目录索引
av = src("ArchiveViewerActivity.kt")
check("目录统计用一次遍历", "dirStats.getOrPut" in av)
check("不再按目录二次扫描", "childPrefix" not in av)
check("只有一个 allItems 循环", av.count("for (item in allItems)") == 1)

# 8. Office 写回：临时文件 + 备份 + 回滚
wb = src("OfficeWriteback.java")
check("临时文件", ".eabtmp" in wb)
check("备份原文件", ".eabak" in wb and "无法备份原文件" in wb)
check("失败回滚", "rollback(file, backup, replaced)" in wb)

# 9. Office 查看器：docx 保存前提示丢失格式
ov = src("OfficeViewerActivity.kt")
check("docx 保存前确认", "Unsupported formatting will be lost" in ov and "if (ext == \"docx\")" in ov)
check("确认后才真正保存", "doSave(f, text)" in ov)

# 10. SVG 光栅化：save 后必须 finally restore
svg = src("SvgRasterizer.kt", JAVA)
check("画布恢复放进 finally", "} finally {" in svg and "canvas.restoreToCount(save)" in svg)

# 11. PDF：白名单外路径复制到 cache，不依赖全盘可读
pdf = src("PdfViewerActivity.kt")
check("白名单根判断", "isUnderAllowedRoots" in pdf)
check("白名单外复制到 cache", 'File(cacheDir, "pdf")' in pdf)

# 12. 新文案在四套语言资源里齐全（UiStrings 支持英文原文反查）
RES = os.path.join(SRC_ROOT, "res")
LANG_DIRS = ["values", "values-zh-rTW", "values-en", "values-ja"]
RES_KEYS = [
    "file_all_files_needed",
    "file_all_files_prompt",
    "file_open_settings",
    "file_use_private_dir",
    "office_unsupported_format_lost",
    "office_save_plaintext_confirm",
    "common_continue",
]
missing_dirs = [d for d in LANG_DIRS if not os.path.exists(os.path.join(RES, d, "strings.xml"))]
if missing_dirs:
    print("[skip] 文案四语: 资源目录未同步（" + ",".join(missing_dirs) + "）")
else:
    for key in RES_KEYS:
        flag = 'name="' + key + '"'
        missing = []
        for lang in LANG_DIRS:
            path = os.path.join(RES, lang, "strings.xml")
            if not os.path.exists(path) or flag not in read(path):
                missing.append(lang)
        check("文案四语: " + key, not missing, "missing=" + ",".join(missing))

print("RESULT failures=%d" % failures)
raise SystemExit(1 if failures else 0)
