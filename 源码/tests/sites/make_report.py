#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 tests/sites/report.html：原站截图 vs 引擎 draw 导出，只并排展示，不做评判。"""
import csv
import html
import json
import os
import time

ROOT = os.path.dirname(os.path.abspath(__file__))

# 对照口径备注：只陈述事实，不评判渲染好坏
FACTS = {
    "baidu-search": ("左侧截图为 Headless Edge 实时访问时被“百度安全验证”拦截后得到的验证码页，"
                     "与右侧快照（快照本体是真实结果页，title=浏览器_百度搜索）不是同一页面。"),
    "baidu-home": ("引擎 SVG 导出只写 draw 指令（矩形/文本），不嵌入图片资源；本页 logo 文本为白字，"
                   "与白色背景同色，因此在右图中不可见。"),
    "tieba-hottopic": ("左侧无原站截图：Headless Edge 打开该页后加载事件迟迟不结束，超时未产出 PNG；"
                       "未做任何替代或伪造，仅保留右侧引擎导出。"),
}


def load_json(name, default=None):
    p = os.path.join(ROOT, name)
    if not os.path.exists(p):
        return default
    with open(p, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def load_csv(name):
    p = os.path.join(ROOT, name)
    if not os.path.exists(p):
        return []
    with open(p, "r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def main():
    sites = load_json("sites.json", [])
    base = {r["page"]: r for r in load_csv("baseline.csv")}
    unknown = {}
    for r in load_csv("baseline_unknown.csv"):
        unknown.setdefault(r["page"], []).append((r["prop"], int(r["count"])))
    shots = {r["page"]: r for r in load_json("shots/meta.json", [])}

    cards = []
    for s in sites:
        slug = s["slug"]
        m = base.get(slug, {})
        pm = load_json(os.path.join(s["dir"].replace("/", os.sep), "page.meta.json"), {})
        shot = shots.get(slug, {})
        png_rel = "shots/%s.png" % slug
        svg_rel = "snapshots/%s.svg" % slug
        has_png = os.path.exists(os.path.join(ROOT, png_rel)) and os.path.getsize(os.path.join(ROOT, png_rel)) > 8000
        has_svg = os.path.exists(os.path.join(ROOT, svg_rel)) and os.path.getsize(os.path.join(ROOT, svg_rel)) > 0

        unk_html = ""
        if unknown.get(slug):
            chips = "".join(
                '<span class="unk">%s <b>%d</b></span>' % (html.escape(p), c)
                for p, c in sorted(unknown[slug], key=lambda x: -x[1])[:12])
            unk_html = '<div class="unkrow"><span class="lbl">unsupported 明细</span>%s</div>' % chips

        if has_png:
            left = ('<a href="%s" target="_blank"><img src="%s" loading="lazy" alt="原站截图"></a>'
                    % (png_rel, png_rel))
        else:
            reason = shot.get("reason") or "未获取到原站截图"
            left = ('<div class="miss"><b>原站截图缺失</b><p>%s</p>'
                    '<p class="small">站点：%s</p></div>'
                    % (html.escape(reason), html.escape(s["url"])))

        if has_svg:
            right = ('<a href="%s" target="_blank"><img class="eng" src="%s" loading="lazy" alt="引擎渲染"></a>'
                     '<div class="cap"><a href="%s" target="_blank">打开完整 SVG</a></div>'
                     % (svg_rel, svg_rel, svg_rel))
        else:
            right = '<div class="miss"><b>引擎渲染导出缺失</b><p>未生成 svg 文件</p></div>'

        metrics = [
            ("指令数", m.get("cmds", "-")),
            ("unsupported", m.get("unsupported_decls", "-")),
            ("unknown", m.get("unknown_props", "-")),
            ("layout", (m.get("layout_ms") or "-") + " ms"),
            ("style", (m.get("style_ms") or "-") + " ms"),
            ("状态", m.get("status", "-")),
        ]
        chips = "".join('<span class="chip">%s <b>%s</b></span>' % (html.escape(k), html.escape(str(v))
                                                                  ) for k, v in metrics)
        note = pm.get("note") or s.get("note") or ""
        fact = FACTS.get(slug, "")
        fact_html = '<p class="fact">%s</p>' % html.escape(fact) if fact else ""
        cards.append("""
<section class="card">
  <header>
    <h2>%s</h2>
    <a class="url" href="%s" target="_blank">%s</a>
    <p class="note">%s</p>
    %s
    <div class="chips">%s</div>
    %s
  </header>
  <div class="cols">
    <div class="pane"><div class="cap"><b>原站截图</b><span>%dx%d · Headless Edge</span></div>%s</div>
    <div class="pane"><div class="cap"><b>引擎渲染</b><span>draw 指令 → SVG</span></div>%s</div>
  </div>
</section>""" % (
            html.escape(slug), html.escape(s["url"]), html.escape(s["url"]),
            html.escape(note), fact_html, chips, unk_html, s["vw"], s["vh"], left, right))

    shot_ok = sum(1 for s in sites if shots.get(s["slug"], {}).get("ok") and
                  os.path.exists(os.path.join(ROOT, "shots/%s.png" % s["slug"])))
    total_ms = sum(float(base.get(s["slug"], {}).get("total_ms") or 0) for s in sites)
    ts = time.strftime("%Y-%m-%d %H:%M:%S")

    doc = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>重度站点回归 · 原站 vs 引擎</title>
<style>
:root{--bg:#0f1115;--panel:#171a21;--line:#272c36;--fg:#e6e9ef;--dim:#9aa3b2;--acc:#6ea8fe}
*{box-sizing:border-box}
body{margin:0;padding:28px;background:var(--bg);color:var(--fg);
     font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
h1{font-size:24px;margin:0 0 6px}
.sub{color:var(--dim);margin:0 0 18px}
.summary{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 22px}
.summary .chip{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:6px 12px;color:var(--dim)}
.summary .chip b{color:var(--fg)}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 22px}
.card header{margin-bottom:12px}
h2{font-size:17px;margin:0 0 2px;font-family:ui-monospace,Consolas,monospace}
.url{color:var(--acc);text-decoration:none;font-size:12px;word-break:break-all}
.note{color:var(--dim);margin:6px 0 8px;font-size:13px}
.fact{color:#e8c39e;margin:0 0 8px;font-size:12.5px;border-left:2px solid #6b4f33;padding-left:8px}
.chips{display:flex;gap:8px;flex-wrap:wrap}
.chip{background:#10131a;border:1px solid var(--line);border-radius:8px;padding:4px 9px;color:var(--dim);font-size:12px}
.chip b{color:var(--fg);font-family:ui-monospace,Consolas,monospace}
.unkrow{display:flex;gap:6px;flex-wrap:wrap;align-items:center;margin-top:8px}
.lbl{color:var(--dim);font-size:12px}
.unk{background:#1d2230;border:1px solid #2b3346;border-radius:6px;padding:2px 7px;font-size:12px;color:#c9d4e8}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:900px){.cols{grid-template-columns:1fr}}
.pane{background:#0c0f14;border:1px solid var(--line);border-radius:10px;overflow:hidden}
.cap{display:flex;justify-content:space-between;gap:8px;padding:7px 10px;color:var(--dim);font-size:12px;border-bottom:1px solid var(--line)}
.cap b{color:var(--fg);font-weight:600}
.pane img{display:block;width:100%;height:auto;max-height:720px;object-fit:cover;object-position:top}
.pane img.eng{background:#fff;object-fit:contain;object-position:top}
.miss{padding:22px;color:var(--dim);background:repeating-linear-gradient(45deg,#12151c,#12151c 10px,#151922 10px,#151922 20px);min-height:240px}
.miss b{color:#ffb4a8}
.small{font-size:12px;word-break:break-all}
footer{color:var(--dim);font-size:12px;margin-top:8px}
footer code{color:#c9d4e8}
</style></head><body>
<h1>重度站点回归 · 原站 vs 引擎渲染</h1>
<p class="sub">离线快照逐页跑 <code>feature_runner</code>，仅并排展示截图与 draw 导出，不做评分。生成于 {{TS}}。</p>
<div class="summary">
  <span class="chip">快照页 <b>{{NPAGES}}</b></span>
  <span class="chip">抓取成功 <b>{{NOK}}</b></span>
  <span class="chip">原站截图 <b>{{NSHOT}}</b></span>
  <span class="chip">引擎布局+样式合计 <b>{{TOTAL_MS}} ms</b></span>
  <span class="chip">执行模式 <b>--no-js</b></span>
</div>
{{CARDS}}
<footer>
  <p>口径：快照内所有外链已改写为页面相对路径；图片固有尺寸经 <code>--img</code> 回填；未执行页面 JS（<code>--no-js</code>），因此动态内容不参与对照。左图为 Headless Edge 实时截取的当前线上首屏，右图为引擎 draw 指令导出的 SVG（可滚动查看）。</p>
  <p>抓取降级说明：贴吧首页 <code>tieba.baidu.com</code> 返回 403，改用可访问的 <code>/hottopic/browse/hottopic</code>；知乎匿名访问被 302 到登录页。抓不下来的页面均在卡片内写明原因，未做任何内容伪造。</p>
</footer>
</body></html>"""

    doc = (doc.replace("{{TS}}", html.escape(ts))
              .replace("{{NPAGES}}", str(len(sites)))
              .replace("{{NOK}}", str(len(base)))
              .replace("{{NSHOT}}", str(shot_ok))
              .replace("{{TOTAL_MS}}", "%.0f" % total_ms)
              .replace("{{CARDS}}", "".join(cards)))

    out = os.path.join(ROOT, "report.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(doc)
    print("report.html written, %d cards" % len(cards))


if __name__ == "__main__":
    main()
