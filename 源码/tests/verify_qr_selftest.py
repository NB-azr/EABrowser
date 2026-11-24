#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""二维码解码自检（不依赖手机 / 模拟器）。

把 tests/qr_selftest/qr_selftest.png 转成 NV21 Y 平面帧，直接调用生产解码器
QrDecoder，断言：
  1) 正常二维码能解出 EA-SELFTEST-0001
  2) 纯噪声 miss 且不抛异常
  3) 反色二维码仍能解（ALSO_INVERTED）
  4) 300ms 内连投 5 帧只解 1~2 次，解码未完成时后续帧被拒

输出与其它 verify_* 脚本一致：最后一行 RESULT failures=N。
"""

import glob
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "android", "BrowserApp", "app", "src", "main",
                     "java", "com", "ea", "browser", "tools")
SELFTEST = os.path.join(ROOT, "tests", "qr_selftest")
PNG = os.path.join(SELFTEST, "qr_selftest.png")


def java_tool(name):
    home = os.environ.get("JAVA_HOME") or r"C:\Users\Administrator\.jdks\jbr-21.0.11"
    exe = os.path.join(home, "bin", name + (".exe" if os.name == "nt" else ""))
    return exe if os.path.exists(exe) else name


def zxing_jar():
    pattern = os.path.join(ROOT, "android", "gradle_home", "caches", "modules-2",
                           "files-2.1", "com.google.zxing", "core", "*", "*", "core-*.jar")
    jars = glob.glob(pattern)
    jars.sort(key=lambda p: ("3.5.3" not in p, p))
    return jars[0] if jars else None


def fail(msg):
    print("[fail] " + msg)
    print("RESULT failures=1")
    return 1


def main():
    if not os.path.exists(PNG):
        return fail("缺少测试图片 " + PNG)
    jar = zxing_jar()
    if not jar:
        return fail("Gradle 缓存里找不到 zxing core jar")
    sources = [
        os.path.join(TOOLS, "QrDecoder.java"),
        os.path.join(TOOLS, "DecodeThrottle.java"),
        os.path.join(SELFTEST, "QrDecodeSelfTest.java"),
    ]
    for s in sources:
        if not os.path.exists(s):
            return fail("缺少源码 " + s)

    out = tempfile.mkdtemp(prefix="qrselftest_")
    try:
        cmd = [java_tool("javac"), "-encoding", "UTF-8", "-cp", jar, "-d", out] + sources
        p = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if p.returncode != 0:
            print(p.stdout.strip())
            print(p.stderr.strip())
            return fail("javac 编译失败")
        cp = out + os.pathsep + jar
        p = subprocess.run(
            [java_tool("java"), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", cp,
             "qrselftest.QrDecodeSelfTest", PNG],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        out_text = p.stdout.strip()
        if out_text:
            print(out_text)
        if p.stderr.strip():
            print(p.stderr.strip())
        return p.returncode
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
