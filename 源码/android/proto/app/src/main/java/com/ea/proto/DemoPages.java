package com.ea.proto;

/** 合成演示页 / 基准指令集（不依赖引擎，用于把 GPU 最小面压满） */
public class DemoPages {

    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static DisplayList.Cmd rect(float x, float y, float w, float h, int color) {
        DisplayList.Cmd c = new DisplayList.Cmd();
        c.type = DisplayList.TYPE_RECT;
        c.x = x; c.y = y; c.w = w; c.h = h;
        c.color = color;
        return c;
    }

    private static DisplayList.Cmd grad(float x, float y, float w, float h,
                                        int angle, int c0, int c1) {
        DisplayList.Cmd c = rect(x, y, w, h, c0);
        c.gradientType = 1;
        c.gradientAngle = angle;
        c.c0 = c0;
        c.c1 = c1;
        return c;
    }

    private static DisplayList.Cmd image(float x, float y, float w, float h, String src) {
        DisplayList.Cmd c = rect(x, y, w, h, 0xFFEEEEEE);
        c.type = DisplayList.TYPE_IMAGE;
        c.src = src;
        return c;
    }

    private static DisplayList.Cmd text(float x, float y, float w, String s, float size,
                                        int color, boolean bold, int align) {
        DisplayList.Cmd c = new DisplayList.Cmd();
        c.type = DisplayList.TYPE_TEXT;
        c.x = x; c.y = y; c.w = w; c.h = size;
        c.text = s;
        c.textSize = size;
        c.color = color;
        c.bold = bold;
        c.align = align;
        return c;
    }

    private static DisplayList.Cmd transform(DisplayList.Cmd c, float sx, float sy,
                                             float rot, float skx, float sky, float ox, float oy) {
        c.hasTransform = true;
        c.tx = 0; c.ty = 0;
        c.sx = sx; c.sy = sy;
        c.rot = rot; c.skx = skx; c.sky = sky;
        c.ox = ox; c.oy = oy;
        return c;
    }

    /** GPU 指令模式使用的纯图形页面（无文字，全部落在 GLES2 最小面内） */
    public static DisplayList buildGpuPage(float vw, float vh) {
        DisplayList dl = new DisplayList();
        dl.viewportW = vw;
        dl.viewportH = vh;
        float contentH = 5600;
        dl.contentH = contentH;

        dl.cmds.add(rect(0, 0, vw, contentH, 0xFFF2F4F8));
        dl.cmds.add(grad(0, 0, vw, 320, 135, 0xFF4F8CFF, 0xFF7A5CFF));
        dl.cmds.add(rect(0, 320, vw, 6, 0x22000000));
        dl.cmds.add(text(64, 96, vw - 128, "EA GPU Compositor", 56, 0xFFFFFFFF, true, 0));
        dl.cmds.add(text(64, 180, vw - 128, "OpenGL ES 2.0  single draw call", 26, 0xCCFFFFFF, false, 0));
        dl.cmds.add(text(64, 270, vw - 128, "中文图集：网页排版引擎 / 常用汉字子集", 24, 0xCCFFFFFF, false, 0));

        int[] solids = {
            0xFFFF9A62, 0xFF22C55E, 0xFF111827, 0xFFFDE68A,
            0xFF0EA5E9, 0xFFEC4899, 0xFF8B5CF6, 0xFFF97316
        };
        int[] g0 = {0xFFFF9A62, 0xFF22C55E, 0xFF0EA5E9, 0xFFF97316};
        int[] g1 = {0xFFFF5F6D, 0xFF0EA5E9, 0xFF7A5CFF, 0xFFFACC15};

        float y = 360;
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                float x = 40 + col * 520;
                DisplayList.Cmd c;
                if ((idx & 1) == 0) {
                    c = grad(x, y, 480, 300, (idx % 4) * 45, g0[idx % 4], g1[idx % 4]);
                } else {
                    c = rect(x, y, 480, 300, solids[idx % solids.length]);
                }
                c.radius = 28 + (idx % 3) * 6;
                dl.cmds.add(c);

                // 卡片内部的“图标块”，增加指令密度
                dl.cmds.add(rect(x + 32, y + 32, 96, 96, 0x33FFFFFF));
                dl.cmds.add(rect(x + 148, y + 36, 260, 18, 0x88FFFFFF));
                dl.cmds.add(rect(x + 148, y + 70, 200, 12, 0x55FFFFFF));
                dl.cmds.add(text(x + 148, y + 130, 300, "Card " + (idx + 1), 30,
                        0xFFFFFFFF, true, 0));
            }
            y += 340;
        }

        // 图片带
        y = 2440;
        dl.cmds.add(text(40, y - 70, vw - 80, "Image Atlas", 34, 0xFF111827, true, 0));
        dl.cmds.add(image(40, y, 320, 200, "tile_a.png"));
        dl.cmds.add(image(380, y, 320, 200, "tile_b.png"));
        dl.cmds.add(image(720, y, 320, 200, "tile_c.png"));

        // 旋转 / 缩放 / 斜切
        y = 2720;
        dl.cmds.add(text(80, y - 70, vw - 160, "Transform / Scale / Skew", 34, 0xFF111827, true, 0));
        DisplayList.Cmd rot = grad(80, y, 420, 160, 90, 0xFF8B5CF6, 0xFFEC4899);
        rot.radius = 20;
        dl.cmds.add(transform(rot, 1.08f, 1.08f, -9, 0, 0, 210, 80));
        DisplayList.Cmd skew = rect(80, y + 220, 420, 140, 0xFF0EA5E9);
        skew.radius = 14;
        dl.cmds.add(transform(skew, 1f, 1f, 0, -14, 0, 210, 70));
        DisplayList.Cmd scaled = grad(560, y + 20, 380, 180, 0, 0xFF22C55E, 0xFF0EA5E9);
        scaled.radius = 24;
        dl.cmds.add(transform(scaled, 1.15f, 0.9f, 4, 0, 0, 190, 90));

        // 底部再铺一段卡片，保证可以滚动多屏
        y = 3220;
        for (int i = 0; i < 10; i++) {
            DisplayList.Cmd c = grad(40, y, 1000, 160, 90, g0[i % 4], g1[i % 4]);
            c.radius = 18;
            dl.cmds.add(c);
            dl.cmds.add(rect(64, y + 40, 52, 52, 0x66FFFFFF));
            dl.cmds.add(rect(140, y + 46, 640, 22, 0x99FFFFFF));
            dl.cmds.add(rect(140, y + 80, 420, 14, 0x55FFFFFF));
            y += 210;
        }
        return dl;
    }

    /** 基准：N 条指令，覆盖矩形/圆角/线性渐变/图片/变换 */
    public static DisplayList buildBench(float vw, float vh, int n) {
        DisplayList dl = new DisplayList();
        dl.viewportW = vw;
        dl.viewportH = vh;
        dl.contentH = vh;
        dl.cmds.add(rect(0, 0, vw, vh, 0xFFF7F8FA));

        int cols = 10;
        float cellW = vw / cols;
        float cellH = 120;
        int rows = (int) Math.ceil((float) (n - 1) / cols);
        if (rows * cellH > vh) cellH = vh / rows;

        int[] colors = {
            0xFF3B82F6, 0xFFEF4444, 0xFF10B981, 0xFFF59E0B, 0xFF8B5CF6,
            0xFF06B6D4, 0xFFEC4899, 0xFF84CC16, 0xFFF97316, 0xFF64748B
        };
        for (int i = 1; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float x = col * cellW + 3;
            float y = row * cellH + 3;
            float w = cellW - 6;
            float h = cellH - 6;
            int kind = i % 12;
            DisplayList.Cmd c;
            if (kind < 6) {
                c = rect(x, y, w, h, colors[i % colors.length]);
            } else if (kind < 9) {
                c = rect(x, y, w, h, colors[i % colors.length]);
                c.radius = Math.min(w, h) * 0.28f;
            } else if (kind < 11) {
                c = grad(x, y, w, h, (i % 4) * 45, colors[i % colors.length],
                         0xFFF1F5F9);
            } else {
                c = image(x, y, w, h, "bench_" + (i % 8) + ".png");
            }
            if ((i % 63) == 0 && kind < 9) {
                c = grad(x, y, w, h, (i % 4) * 45, colors[i % colors.length], 0xFF111827);
                c.radius = 12;
                c.hasTransform = true;
                c.sx = 1.15f; c.sy = 0.92f; c.rot = 6; c.ox = w / 2; c.oy = h / 2;
            }
            dl.cmds.add(c);
        }
        return dl;
    }

    private static final String[] CJK_LINES = {
        "中文渲染测试",
        "网页排版引擎",
        "字形图集合批",
        "性能基准测试",
        "图形合成流水线",
        "顶点缓冲上传",
        "这个页面包含中文内容",
        "渲染子进程隔离",
        "低端设备性能优化",
        "显示列表批处理",
        "常用汉字子集",
        "移动浏览器内核"
    };

    /** mode=3 用：约 24 个汉字的长行，且每行唯一（单行纹理宽 ~500px，4 页很快打满） */
    private static String longLine(int i) {
        String base = CJK_LINES[i % CJK_LINES.length];
        StringBuilder sb = new StringBuilder(28);
        while (sb.length() < 20) sb.append(base);
        return sb.substring(0, 20) + i;
    }

    /**
     * CJK 基准。
     * mode=0：混合页（每 3 格放一条中文文本，其余仍是形状/图片，网格布局与 buildBench 一致）
     * mode=1：纯文本页（每个格子一条中文文本，重复 12 条短句，整行缓存全命中）
     * mode=2：纯文本页 + 每行唯一字符串（模拟真实页面；强制多页图集与跨页切换）
     * mode=3：长文页（4 列、每行 20 汉字且唯一；可见行 ≈252，刚好在 4 页容量内）
     * mode=4：长文页加密（8 列，可见行 ≈504，超过 4 页容量，强制 LRU 换页）
     */
    public static DisplayList buildBenchCjk(float vw, float vh, int n, int mode) {
        DisplayList dl = new DisplayList();
        dl.viewportW = vw;
        dl.viewportH = vh;
        dl.contentH = vh;
        dl.cmds.add(rect(0, 0, vw, vh, 0xFFF7F8FA));

        int cols = mode == 0 ? 10 : (mode == 4 ? 8 : 4);
        float cellW = vw / cols;
        int rows = (int) Math.ceil((float) (n - 1) / cols);
        float cellH = mode == 0 ? 120 : Math.max(28, vh / rows);

        int[] colors = {
            0xFF3B82F6, 0xFFEF4444, 0xFF10B981, 0xFFF59E0B, 0xFF8B5CF6, 0xFF64748B
        };
        int[] textColors = {0xFF111827, 0xFF1F2937, 0xFF374151, 0xFF4B5563};

        for (int i = 1; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float x = col * cellW + 3;
            float y = row * cellH + 3;
            float w = cellW - 6;
            float h = Math.max(8, cellH - 6);

            boolean asText = mode != 0 || (mode == 0 && i % 3 == 0);
            if (asText) {
                String s;
                if (mode == 3 || mode == 4) {
                    s = longLine(i);
                } else if (mode == 2) {
                    s = CJK_LINES[i % CJK_LINES.length] + i;
                } else {
                    s = CJK_LINES[i % CJK_LINES.length];
                }
                float fs = mode == 0 ? 20
                        : (mode == 2 ? 18 : (mode >= 3 ? 20 : Math.min(30, cellH * 0.55f)));
                float ty = mode == 0 ? y + h * 0.5f : y + 2;
                dl.cmds.add(text(x + 4, ty, Math.max(1, w - 8), s, fs,
                        textColors[i % textColors.length], (i % 5) == 0, 0));
            } else {
                DisplayList.Cmd c;
                int kind = i % 12;
                if (kind < 6) {
                    c = rect(x, y, w, h, colors[i % colors.length]);
                } else if (kind < 9) {
                    c = rect(x, y, w, h, colors[i % colors.length]);
                    c.radius = Math.min(w, h) * 0.28f;
                } else if (kind < 11) {
                    c = grad(x, y, w, h, (i % 4) * 45, colors[i % colors.length], 0xFFF1F5F9);
                } else {
                    c = image(x, y, w, h, "bench_" + (i % 8) + ".png");
                }
                dl.cmds.add(c);
            }
        }
        return dl;
    }

    /**
     * 视频开洞演示页：可滚动长页，中间一块 EA_DRAW_VIDEO（洋红对照色）留给 SurfaceView。
     * 洞内像素由 GL 跳过；若关闭 videoHoleEnabled，会看到洋红，说明"位图覆盖了视频层"。
     */
    public static DisplayList buildDemoVideo(float vw, float vh) {
        DisplayList dl = new DisplayList();
        dl.viewportW = vw;
        dl.viewportH = vh;
        dl.contentH = vh * 2;
        dl.cmds.add(rect(0, 0, vw, dl.contentH, 0xFFF7F8FA));

        dl.cmds.add(grad(0, 0, vw, 180, 90, 0xFF1E3A8A, 0xFF2563EB));
        dl.cmds.add(text(40, 56, vw - 80, "视频开洞 EA_DRAW_VIDEO", 40, 0xFFFFFFFF, true, 0));
        dl.cmds.add(text(40, 110, vw - 80, "GL 跳过该矩形，SurfaceView 叠在上面并跟随滚动", 22,
                0xFFBFDBFE, false, 0));

        dl.cmds.add(rect(40, 480, vw - 80, 600, 0xFFFFFFFF));
        DisplayList.Cmd v = rect(60, 520, vw - 120, 520, 0xFFCC00CC); // 洋红 = 洞未生效时的对照色
        v.type = DisplayList.TYPE_VIDEO;
        dl.cmds.add(v);
        dl.cmds.add(text(60, 1054, vw - 120, "↑ 洞内应是 SurfaceView 的条纹；若整块洋红说明洞失效",
                22, 0xFF555555, false, 0));
        // 视频块四边的对齐标尺（画在洞外，不参与开洞；错位时会出现白缝）
        dl.cmds.add(rect(52, 512, vw - 104, 4, 0xFF7C3AED));
        dl.cmds.add(rect(52, 1044, vw - 104, 4, 0xFF7C3AED));
        dl.cmds.add(rect(52, 512, 4, 536, 0xFF7C3AED));
        dl.cmds.add(rect(vw - 56, 512, 4, 536, 0xFF7C3AED));

        for (int i = 0; i < 6; i++) {
            float y = 1180 + i * 380;
            dl.cmds.add(rect(40, y, vw - 80, 320, i % 2 == 0 ? 0xFFE0F2FE : 0xFFFEF3C7));
            dl.cmds.add(text(60, y + 28, vw - 120, "第 " + (i + 1) + " 段：滚动时视频应与本段同步",
                    26, 0xFF1F2937, true, 0));
            dl.cmds.add(text(60, y + 88, vw - 120, CJK_LINES[i % CJK_LINES.length], 24,
                    0xFF374151, false, 0));
            dl.cmds.add(rect(60, y + 240, (vw - 120) * (0.3f + 0.1f * i), 40, 0xFF2563EB));
        }
        return dl;
    }
}
