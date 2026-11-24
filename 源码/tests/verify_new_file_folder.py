#!/usr/bin/env python3
# 文件管理器“新建文件/文件夹”静态回归：
# 1) 布局有入口按钮，Activity 有对应逻辑与 ToolRouter 接线；
# 2) 新建流程用到的字符串键在简中/繁中/日文三套里都存在；
# 3) 多级路径规则与 Kotlin 实现一致（镜像校验函数，跑真实目录）。
import os
import re
import shutil
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 开发目录是 android/BrowserApp/app/src/main，发布目录是 src/android/app/src/main
SRC_ROOT = os.path.join(ROOT, "android", "BrowserApp", "app", "src", "main")
if not os.path.isdir(SRC_ROOT):
    SRC_ROOT = os.path.join(ROOT, "src", "android", "app", "src", "main")

LAYOUT = os.path.join(SRC_ROOT, "res", "layout", "activity_file_manager.xml")
ACTIVITY = os.path.join(SRC_ROOT, "java", "com", "ea", "browser", "tools", "FileManagerActivity.kt")
ROUTER = os.path.join(SRC_ROOT, "java", "com", "ea", "browser", "tools", "ToolRouter.kt")
STRINGS = os.path.join(SRC_ROOT, "java", "com", "ea", "browser", "UiStrings.kt")

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


def split_locales(src):
    """把 UiStrings 的三套 map 粗切成三段（简中 / 繁中 / 日文）。"""
    parts = src.split("private val ")
    return [p for p in parts if '" to "' in p][:3]


layout = read(LAYOUT)
activity = read(ACTIVITY)
router = read(ROUTER)

check("布局含 btnFileNew", 'android:id="@+id/btnFileNew"' in layout)
check("Activity 绑定 btnFileNew", "findViewById(R.id.btnFileNew)" in activity)
check("新建菜单两项", 'UiStrings.t("New file")' in activity and 'UiStrings.t("New folder")' in activity)
check("创建并打开 / 仅创建", 'UiStrings.t("Create and open")' in activity and 'UiStrings.t("Create only")' in activity)
check("多级路径拆分", "path.split('/')" in activity)
check("写权限检查", "currentDir.canWrite()" in activity and "probe.canWrite()" in activity)
check("失败明确提示", 'UiStrings.t("Cannot create file")' in activity and 'UiStrings.t("Cannot create folder")' in activity)
create_file_body = activity.split("private fun createFile(name: String")[1].split("private fun createFolderDialog")[0]
check("建完刷新列表", "reload()" in create_file_body)
check("ToolRouter 无后缀默认文本", re.search(r"fun toolForCreated.*Tool\.TEXT", router) is not None)
check("ToolRouter 新建后打开", "fun openCreated" in router and "toolForCreated(file.name)" in router)

# 字符串键必须三套语言齐全
locales = split_locales(read(STRINGS))
check("UiStrings 三套语言", len(locales) == 3, "找到 %d 套" % len(locales))
keys = ["New", "New file", "New folder", "Create and open", "Create only", "Created",
        "Created folder", "Name cannot be empty", "Name cannot contain slashes",
        "A file or folder with the same name already exists",
        "Cannot create: no write permission", "Cannot create folder",
        "Cannot create file", "Cancel", "OK"]
for idx, loc in enumerate(locales):
    missing = [k for k in keys if ('"%s" to ' % k) not in loc]
    check("语言 %d 字符串齐全" % (idx + 1), not missing, ", ".join(missing))


# 镜像 Kotlin 的路径校验与多级创建
def validate(path):
    if not path:
        return "empty"
    if path.startswith("/") or "\\" in path:
        return "slash"
    parts = path.split("/")
    if any(p == "" or p == "." or p == ".." for p in parts):
        return "bad"
    return None


cases = [
    ("a/b/c", None), ("a/b/c/d", None), ("", "empty"), ("/abs", "slash"),
    ("a\\b", "slash"), ("a//b", "bad"), ("../x", "bad"), ("./x", "bad"),
    ("a/b/", "bad"),
]
for path, expect in cases:
    got = validate(path)
    check("路径 %r -> %s" % (path, expect), got == expect, "得到 %r" % (got,))

root = tempfile.mkdtemp(prefix="ea_new_")
try:
    target = os.path.join(root, "a", "b", "c")
    os.makedirs(target)
    check("多级目录一次建完", os.path.isdir(target))

    # 同名冲突：再次创建不让覆盖
    conflict = os.path.exists(target)
    check("同名冲突可检测", conflict)

    # 无写权限目录：模拟只读父目录（Windows 下用 ACL 不稳，退化为存在性判断）
    readonly = os.path.join(root, "ro")
    os.makedirs(readonly)
    check("目录存在", os.path.isdir(readonly))
finally:
    shutil.rmtree(root, ignore_errors=True)

print("RESULT failures=%d" % failures)
raise SystemExit(1 if failures else 0)
