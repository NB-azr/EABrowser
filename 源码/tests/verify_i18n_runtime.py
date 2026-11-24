# -*- coding: utf-8 -*-
"""
verify_i18n_runtime.py — UiStrings.t() 映射逻辑运行时验证

运行：
    python tests/verify_i18n_runtime.py

说明：
本机没有可用的 Android/JVM 运行时（无 JDK，且不能构建 APK，避免与并行会话的
build 目录冲突），所以这里用与 UiStrings.kt 逐条同构的 Python 模型跑同一套映射：
    语义 key(点号) -> 资源名(下划线) -> 反射取 id -> 按当前语言取 Resources
    旧英文原文 -> 英文资源反查表 -> 资源名
    缓存键 = 语言 + '\0' + 入参（语言切换后不会命中旧语言）
数据源是真实的四份 res/values*/strings.xml，不是另抄一份词典。

验证点：
  1. 676 个 key 在 zh-CN / zh-TW / en / ja 四种语言下都能取到非空值
  2. 旧调用（传英文原文）仍能拿到当前语言文案
  3. 语言切换后取到新语言（缓存不串语言）
  4. 未知 key 原样返回，空串原样返回
  5. 带 %1$s / %2$s 的模板能正确填充参数
  6. 同一个 key 在四份资源里的占位符数量（编号集合）一致
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'android', 'BrowserApp', 'app', 'src', 'main', 'res')

LOCALE_DIRS = {
    'zh-CN': 'values',
    'zh-TW': 'values-zh-rTW',
    'en': 'values-en',
    'ja': 'values-ja',
}
LEGACY_NAMES = {'app_name', 'hint_url', 'empty_state'}

fails = []


def check(cond, msg):
    if cond:
        return True
    fails.append(msg)
    return False


def dotted(name):
    return name if name in LEGACY_NAMES else name.replace('_', '.')


def unescape(value):
    """Android 资源里 \\' \\" \\\\ \\n 是转义写法，getString 会还原；这里保持一致"""
    return (value.replace("\\'", "'").replace('\\"', '"')
                 .replace('\\\\', '\\').replace('\\n', '\n'))


def load(dir_name):
    path = os.path.join(RES, dir_name, 'strings.xml')
    tree = ET.parse(path)
    return {e.get('name'): unescape(e.text or '') for e in tree.getroot().findall('string')}


PH = re.compile(r'%(\d+)\$[sd]')


def ph_indices(value):
    """编号占位符集合；%% 是字面百分号，先剔除"""
    return sorted({int(m.group(1)) for m in PH.finditer(value.replace('%%', ' '))})


class UiStringsModel(object):
    """与 UiStrings.kt 同构的最小实现"""

    def __init__(self, tables):
        self.tables = tables                  # lang -> {资源名: 文案}
        self.names = {n for n in tables['zh-CN']}
        self.english = {}                     # 英文原文 -> 资源名
        for name, value in tables['en'].items():
            if value and '%' not in value and value not in self.english:
                self.english[value] = name
        self.cache = {}                       # (lang, 入参) -> 文案
        self.lang = 'zh-CN'

    def switch(self, lang):
        self.lang = lang

    def _resolve(self, text):
        if '.' in text and ' ' not in text:
            name = text.replace('.', '_')
            if name in self.names:
                return name
        if text in self.names:
            return text
        return self.english.get(text)

    def t(self, text, *args):
        if not text:
            return text
        name = self._resolve(text)
        if name is None:
            return text
        if args:
            return self._format(self.tables[self.lang][name], args)
        ck = (self.lang, text)
        if ck not in self.cache:
            self.cache[ck] = self.tables[self.lang][name]
        return self.cache[ck]

    @staticmethod
    def _format(template, args):
        def repl(m):
            idx = int(m.group(1)) - 1
            if idx < 0 or idx >= len(args):
                return m.group(0)
            return str(args[idx])
        return re.sub(r'%(\d+)\$[sd]', repl, template)


def main():
    tables = {lang: load(d) for lang, d in LOCALE_DIRS.items()}
    missing = [lang for lang in LOCALE_DIRS if not tables[lang]]
    if missing:
        print('FAIL 资源缺失：' + ', '.join(missing))
        return 1

    ui = UiStringsModel(tables)
    keys = [dotted(n) for n in tables['zh-CN'].keys()]
    print('资源：%d 个 key × %d 种语言' % (len(keys), len(LOCALE_DIRS)))

    # 1) 四语取值
    for key in keys:
        name = key if key in LEGACY_NAMES else key.replace('.', '_')
        for lang in LOCALE_DIRS:
            ui.switch(lang)
            value = ui.t(key)
            check(value != '' and value != key,
                  '四语缺失：%s [%s] -> %r' % (key, lang, value))
            check(value == tables[lang][name],
                  '取值不符：%s [%s] -> %r != %r' % (key, lang, value, tables[lang][name]))

    # 2) 旧调用：传英文原文
    groups = {}
    for name, value in tables['en'].items():
        if value and '%' not in value:
            groups.setdefault(value, []).append(name)
    for value, group in groups.items():
        for lang in LOCALE_DIRS:
            ui.switch(lang)
            got = ui.t(value)
            allowed = {tables[lang][n] for n in group}
            check(got in allowed, '英文原文反查失败：%r [%s] -> %r' % (value, lang, got))

    # 3) 语言切换不受缓存影响
    probe = 'menu.settings'
    for lang in ['zh-CN', 'zh-TW', 'en', 'ja', 'zh-CN', 'en']:
        ui.switch(lang)
        got = ui.t(probe)
        check(got == tables[lang]['menu_settings'],
              '切换语言后取到旧值：%s -> %r（期望 %r）' % (lang, got, tables[lang]['menu_settings']))

    # 4) 未知 key / 空串原样返回
    ui.switch('zh-CN')
    check(ui.t('no.such.key') == 'no.such.key', '未知 key 未原样返回')
    check(ui.t('') == '', '空串未原样返回')

    # 5) 占位符填充（按最大编号传参，覆盖 %1$s…%2$s 的模板）
    templates = [n for n, v in tables['zh-CN'].items() if ph_indices(v)]
    check(len(templates) > 0, '词典里没有带占位符的模板，无法验证格式化')
    for name in templates:
        idx = ph_indices(tables['zh-CN'][name])
        args = ['值%d' % i for i in range(1, max(idx) + 1)]
        for lang in LOCALE_DIRS:
            ui.switch(lang)
            got = ui.t(dotted(name), *args)
            for a in args:
                check(a in got, '占位符未填充：%s [%s] -> %r' % (name, lang, got))
            check(not PH.search(got), '占位符残留：%s [%s] -> %r' % (name, lang, got))

    # 6) 同一 key 四语占位符数量（编号集合）一致，否则运行时 MissingFormatArgumentException
    ph_bad = []
    for name in tables['zh-CN']:
        ref = ph_indices(tables['zh-CN'][name])
        for lang in LOCALE_DIRS:
            if name not in tables[lang]:
                continue
            got = ph_indices(tables[lang][name])
            if got != ref:
                ph_bad.append('%s [%s] %s != %s' % (name, lang, got, ref))
    check(not ph_bad, '四语占位符不一致 %d 条：%s' % (len(ph_bad), '; '.join(ph_bad[:20])))

    total = len(keys) * len(LOCALE_DIRS)
    print('检查项：四语取值 %d，英文反查 %d，切换 6，未知/空串 2，模板 %d，占位符一致性 %d'
          % (total, len(groups) * len(LOCALE_DIRS), len(templates) * len(LOCALE_DIRS),
             len(tables['zh-CN'])))
    if fails:
        print('FAIL：%d 项' % len(fails))
        for msg in fails[:60]:
            print('  ' + msg)
        return 1
    print('PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
