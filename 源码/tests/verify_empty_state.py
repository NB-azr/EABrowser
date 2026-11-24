"""工具页空态文案校验。

要求：
- 默认界面语言是简体中文（SettingsStore 默认 zh-CN）
- 简体中文下 "这里空空如也"，繁体 "這裡空空如也"，日文 "ここには何もありません"
- 文件管理器、压缩包查看器、HTML 编辑器三个入口都取同一份文案
- 布局里不得把英文 key 直接当显示文本
"""
import io
import os
import re
import sys

BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "android", "BrowserApp", "app", "src", "main"))
if not os.path.isdir(BASE):
    BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src", "android", "app", "src", "main"))
JAVA = os.path.join(BASE, "java", "com", "ea", "browser")
TOOLS = os.path.join(JAVA, "tools")
RES = os.path.join(BASE, "res")


def read(*parts):
    with io.open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


def main() -> int:
    ui = read(JAVA, "UiStrings.kt")
    settings = read(JAVA, "SettingsStore.kt")
    fm = read(TOOLS, "FileManagerActivity.kt")
    av = read(TOOLS, "ArchiveViewerActivity.kt")
    he = read(TOOLS, "HtmlEditorActivity.kt")
    lay_fm = read(RES, "layout", "activity_file_manager.xml")
    lay_av = read(RES, "layout", "activity_archive_viewer.xml")
    strings = read(RES, "values", "strings.xml")

    failures = 0

    def check(name, cond):
        nonlocal failures
        if cond:
            print("PASS " + name)
        else:
            print("FAIL " + name)
            failures += 1

    check("默认语言是简体中文", 'prefs.getString("ui_language", "zh-CN")' in settings)
    check("简体中文空态 = 这里空空如也", '"Nothing here yet" to "这里空空如也"' in ui)
    check("繁体中文空态 = 這裡空空如也", '"Nothing here yet" to "這裡空空如也"' in ui)
    check("日文空态有对应译文", '"Nothing here yet" to "ここには何もありません"' in ui)

    m = re.search(r'<string name="empty_state">(.*?)</string>', strings)
    check("@string/empty_state 默认中文", bool(m) and m.group(1) == "这里空空如也")
    check("文件管理器布局引用空态", "@string/empty_state" in lay_fm)
    check("压缩包查看器布局引用空态", "@string/empty_state" in lay_av)
    check("文件管理器运行时取统一文案", 'UiStrings.t("Nothing here yet")' in fm)
    check("压缩包查看器运行时取统一文案", 'UiStrings.t("Nothing here yet")' in av)
    check("HTML 编辑器目录空态取统一文案", 'UiStrings.t("Nothing here yet")' in he)

    # 布局/代码里不能把英文 key 直接当显示文本
    hard = re.search(r'(android:text|\.text\s*=|setText\()\s*"Nothing here yet"', lay_fm + lay_av + fm + av + he)
    check("没有把 Nothing here yet 硬编码成显示文本", hard is None)

    print("RESULT failures=%d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
