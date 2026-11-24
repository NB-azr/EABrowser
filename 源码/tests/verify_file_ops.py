#!/usr/bin/env python3
# 文件管理器操作回归（镜像 FileManagerActivity 的 uniqueDest / copyInto / deleteRecursive 语义）。
# 在临时目录里做真实的复制、同名冲突改名、移动、递归删除与“目录复制进自身”保护检查。
import os
import shutil
import tempfile


def unique_dest(directory, name):
    candidate = os.path.join(directory, name)
    if not os.path.exists(candidate):
        return candidate
    dot = name.rfind(".")
    base = name[:dot] if dot > 0 else name
    ext = name[dot:] if dot > 0 else ""
    index = 1
    while True:
        candidate = os.path.join(directory, "%s (%d)%s" % (base, index, ext))
        if not os.path.exists(candidate):
            return candidate
        index += 1


def copy_into(src, dest):
    if os.path.isdir(src):
        if not os.path.isdir(dest):
            os.makedirs(dest, exist_ok=True)
        for child in os.listdir(src):
            if not copy_into(os.path.join(src, child), os.path.join(dest, child)):
                return False
        return True
    try:
        with open(src, "rb") as i, open(dest, "wb") as o:
            while True:
                buf = i.read(65536)
                if not buf:
                    break
                o.write(buf)
        return True
    except OSError:
        return False


def inside_dir(src, target):
    if not os.path.isdir(src):
        return False
    return os.path.abspath(target).startswith(os.path.abspath(src) + os.sep)


def delete_recursive(path):
    if os.path.isdir(path):
        for child in os.listdir(path):
            if not delete_recursive(os.path.join(path, child)):
                return False
        try:
            os.rmdir(path)
            return True
        except OSError:
            return False
    try:
        os.remove(path)
        return True
    except OSError:
        return False


def main():
    root = tempfile.mkdtemp(prefix="ea_files_")
    src_dir = os.path.join(root, "album")
    os.makedirs(os.path.join(src_dir, "sub"))
    with open(os.path.join(src_dir, "a.txt"), "wb") as f:
        f.write(b"hello hex/pdf tool")
    with open(os.path.join(src_dir, "sub", "b.txt"), "wb") as f:
        f.write(b"nested")

    target = os.path.join(root, "copy")
    os.makedirs(target)

    fails = 0

    # 1) 首次复制保持原名
    d1 = unique_dest(target, "album")
    print("first_dest =", os.path.basename(d1))
    if os.path.basename(d1) != "album" or not copy_into(src_dir, d1):
        fails += 1

    # 2) 同名冲突自动改名，且不覆盖已有内容
    d2 = unique_dest(target, "album")
    print("second_dest=", os.path.basename(d2))
    if os.path.basename(d2) != "album (1)" or not copy_into(src_dir, d2):
        fails += 1
    if os.path.getsize(os.path.join(target, "album", "a.txt")) != os.path.getsize(os.path.join(src_dir, "a.txt")):
        fails += 1

    # 3) 移动（跨目录时用复制+删除模拟；同目录改名直接 rename）
    moved = unique_dest(target, "c.txt")
    with open(os.path.join(root, "c.txt"), "wb") as f:
        f.write(b"move me")
    if not copy_into(os.path.join(root, "c.txt"), moved) or not delete_recursive(os.path.join(root, "c.txt")):
        fails += 1
    if not os.path.exists(moved) or os.path.exists(os.path.join(root, "c.txt")):
        fails += 1

    # 4) 目录复制进自身的保护
    if not inside_dir(src_dir, os.path.join(src_dir, "sub")):
        fails += 1
    if not inside_dir(src_dir, os.path.join(src_dir, "sub", "deep")):
        fails += 1

    # 5) 递归删除
    if not delete_recursive(d1) or os.path.exists(d1):
        fails += 1
    if delete_recursive(os.path.join(root, "not-exist")):
        fails += 1

    print("RESULT failures=%d" % fails)
    shutil.rmtree(root, ignore_errors=True)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
