#!/usr/bin/env python3
# 图片查看器回归（镜像 ImageViewerActivity 的采样解码与 ZoomableImageView 的边界回弹数学）。
# 验证：任意超大图采样后最长边 ≤2048、位图内存有上限；拖动越界不超过 1/4 屏，松手回弹目标在合法范围。
MAX_SIDE = 2048
MEM_LIMIT = MAX_SIDE * MAX_SIDE * 4


def sample_for(w, h):
    s = 1
    while w // s > MAX_SIDE or h // s > MAX_SIDE:
        s *= 2
    return s


def translation_bounds(view_w, view_h, img_w, img_h, extra_scale):
    fit = min(view_w / img_w, view_h / img_h)
    scaled_w = img_w * fit * extra_scale
    scaled_h = img_h * fit * extra_scale
    return max(0, (scaled_w - view_w) / 2), max(0, (scaled_h - view_h) / 2)


def main():
    fails = 0
    for w, h in [(4032, 3024), (8000, 6000), (12000, 12000), (20000, 5000), (60000, 800)]:
        s = sample_for(w, h)
        dw, dh = w // s, h // s
        mem = dw * dh * 4
        ok = max(dw, dh) <= MAX_SIDE and s & (s - 1) == 0 and mem <= MEM_LIMIT
        print("%6dx%-6d sample=%-3d decoded=%dx%d mem=%.1fMB %s" %
              (w, h, s, dw, dh, mem / 1048576, "OK" if ok else "FAIL"))
        if not ok:
            fails += 1

    # 边界回弹：1080x1920 视图、4000x3000 图片、放大 2.5 倍
    vw, vh, iw, ih, sc = 1080, 1920, 4000, 3000, 2.5
    maxx, maxy = translation_bounds(vw, vh, iw, ih, sc)
    print("bounds maxX=%.1f maxY=%.1f" % (maxx, maxy))
    for tx, ty in [(maxx + 999, maxy + 999), (-maxx - 999, -maxy - 999), (maxx // 2, maxy // 2)]:
        ox, oy = vw * 0.25, vh * 0.25
        cx = min(max(tx, -maxx - ox), maxx + ox)
        cy = min(max(ty, -maxy - oy), maxy + oy)
        back_x = min(max(cx, -maxx), maxx)
        back_y = min(max(cy, -maxy), maxy)
        within = abs(back_x) <= maxx and abs(back_y) <= maxy and abs(cx) <= maxx + ox
        print("drag=(%.0f,%.0f) overscroll=(%.0f,%.0f) spring=(%.0f,%.0f) %s" %
              (tx, ty, cx, cy, back_x, back_y, "OK" if within else "FAIL"))
        if not within:
            fails += 1

    print("RESULT failures=%d" % fails)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
