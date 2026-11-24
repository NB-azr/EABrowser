package com.ea.proto;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Canvas 软件光栅 vs GLES2 合成对照（v4：CJK 图集 + 大指令量分阶段诊断）。
 *
 * 用例：
 *   shapes-100/300/600/1200 : 矩形/圆角/渐变/图片/变换
 *   cjk-mix-300/600         : 每 3 格一条中文文本，其余形状（贴近真实中文页面）
 *   cjk-text-300/600        : 全部中文文本（压字形图集与文字合批）
 *
 * 每用例四组数据：
 *   canvas     : Skia 软绘（当前主工程路径）
 *   glSync     : 每帧 glFinish（最坏同步）
 *   glAsync    : 每帧只提交，整轮一次 glFinish（eglSwapBuffers 语义）
 *   breakdown  : build（Java 顶点生成）/ atlas（图集上传）/ submit（VBO 或客户端数组提交）
 *
 * 诊断实验（只对 600/1200 跑）：
 *   vbo        : 顶点走 glBufferData，对比客户端数组
 *   halfRes    : 半分辨率 FBO，判断是否 fill-rate 受限（时间比 ≈ 1/4 → 像素填充主导）
 */
public class BenchRunner {
    private static final String TAG = "EAProto";
    private static final int WARMUP = 3;
    private static final int FRAMES = 20;
    private static final int[] SIZES = {100, 300, 600, 1200};
    private static final int[] CJK_SIZES = {300, 600};

    public static String run(GpuCompositor gpu, int w, int h, int frames) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        SwRaster sw = new SwRaster();

        StringBuilder summary = new StringBuilder();
        StringBuilder json = new StringBuilder();
        summary.append("bench ").append(w).append('x').append(h)
                .append(" frames=").append(frames).append("\n");
        json.append("{\"width\":").append(w).append(",\"height\":").append(h)
                .append(",\"frames\":").append(frames).append(",\"cases\":[");

        List<String> js = new ArrayList<String>();
        boolean first = true;

        for (int n : SIZES) {
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "shapes-" + n, DemoPages.buildBench(w, h, n), summary, n >= 600, n >= 600));
        }

        for (int n : CJK_SIZES) {
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-mix-" + n, DemoPages.buildBenchCjk(w, h, n, 0), summary, n >= 600, false));
        }
        for (int n : CJK_SIZES) {
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-text-" + n, DemoPages.buildBenchCjk(w, h, n, 1), summary, n >= 600, false));
        }
        // 每行唯一字符串：真实页面形态，强制多页图集 + 跨页批次切换
        for (int n : CJK_SIZES) {
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-uniq-" + n, DemoPages.buildBenchCjk(w, h, n, 2), summary, n >= 600, false));
        }

        // 长文页：两种密度，对比"容量内 / 换页前(丢弃) / 4 页 LRU 换页 / 扩容 8 页 / 准入上限 / 逐字"
        for (int n : new int[]{600, 1200}) {
            DisplayList fitDl = DemoPages.buildBenchCjk(w, h, n, 3);   // 4 列，可见 ≈252 行
            DisplayList denseDl = DemoPages.buildBenchCjk(w, h, n, 4); // 8 列，可见 ≈511 行

            gpu.runCacheLimit = 4096;
            gpu.maxGlyphPages = 8;
            gpu.lruAtlas = true;    // 容量内：应 0 换页、稳态全缓存命中
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-fit", fitDl, summary, false, false));

            gpu.maxGlyphPages = 4;
            gpu.lruAtlas = false;   // 换页前：4 页满后直接放弃（画面缺字 + 逐字回落重试）
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-full", denseDl, summary, false, false));

            gpu.lruAtlas = true;    // 换页后：4 页 LRU 复用（文字完整，每帧重光栅被淘汰的行）
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-lru4", denseDl, summary, false, false));

            gpu.runCacheLimit = 256; // 准入上限（旧默认）：超过后 textRun 返回 null，回落逐字
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-cap256", denseDl, summary, false, false));

            gpu.runCacheLimit = 4096;
            gpu.maxGlyphPages = 8;  // 扩容到 8 页：511 行全部驻留，0 换页
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-pages8", denseDl, summary, false, false));

            gpu.forcePerChar = true; // 逐字基线
            js.add(measure(gpu, sw, bmp, w, h, frames,
                    "cjk-long-" + n + "-char", denseDl, summary, false, false));

            gpu.forcePerChar = false;
            gpu.lruAtlas = true;
            gpu.maxGlyphPages = 8;
        }

        // 整帧 vs 脏带上传
        DisplayList dl600 = DemoPages.buildBench(w, h, 600);
        sw.draw(dl600, bmp, 0);
        ByteBuffer full = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        bmp.copyPixelsToBuffer(full);
        full.position(0);
        float upFull = avgUpload(gpu, full, w, h);

        int dh = Math.min(400, h);
        full.position((h - dh) * w * 4);
        ByteBuffer slice = full.slice();
        long t0 = System.nanoTime();
        int reps = 8;
        for (int i = 0; i < reps; i++) {
            slice.position(0);
            gpu.uploadFramePartial(slice, w, 0, h - dh, w, dh);
        }
        float upDirty = (System.nanoTime() - t0) / reps / 1000000f;

        summary.append(String.format("  上传: 整帧 7.3MB=%.2fms  脏带 %dx%d=%.2fms\n",
                upFull, w, dh, upDirty));
        json.append(join(js, ","));
        json.append(String.format("],\"uploadFullMs\":%.2f,\"uploadDirtyMs\":%.2f,\"dirtyH\":%d}",
                upFull, upDirty, dh));

        String out = summary.toString();
        Log.i(TAG, out.replace('\n', ' '));
        return out + "\nJSON " + json;
    }

    /**
     * 一个用例的完整测量。
     * @param diagVbo  是否加测 VBO 路径
     * @param diagHalf 是否加测半分辨率
     */
    private static String measure(GpuCompositor gpu, SwRaster sw, Bitmap bmp, int w, int h,
                                  int frames, String label, DisplayList dl,
                                  StringBuilder summary, boolean diagVbo, boolean diagHalf) {
        int imgCount = 0, textCount = 0;
        for (DisplayList.Cmd c : dl.cmds) {
            if (c.type == DisplayList.TYPE_IMAGE) imgCount++;
            else if (c.type == DisplayList.TYPE_TEXT) textCount++;
        }

        // 每个用例都从空图集开始，避免上一个用例的缓存/换页影响本用例
        gpu.resetGlyphAtlas();

        // Canvas
        for (int i = 0; i < WARMUP; i++) sw.draw(dl, bmp, 0);
        long[] cTimes = new long[frames];
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            sw.draw(dl, bmp, 0);
            cTimes[i] = System.nanoTime() - t0;
        }

        // GLES2 sync（每帧 glFinish）
        for (int i = 0; i < WARMUP; i++) gpu.renderDisplayList(dl, 0, true);
        long[] gsTimes = new long[frames];
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            gpu.renderDisplayList(dl, 0, true);
            gsTimes[i] = System.nanoTime() - t0;
        }

        // GLES2 async（整轮一次 glFinish）
        for (int i = 0; i < WARMUP; i++) gpu.renderDisplayList(dl, 0, false);
        gpu.finish();
        long[] gaTimes = new long[frames];
        long tTotal0 = System.nanoTime();
        int evictTotal = 0, failTotal = 0;
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            gpu.renderDisplayList(dl, 0, false);
            gaTimes[i] = System.nanoTime() - t0;
            evictTotal += gpu.lastAtlasEvictions;
            failTotal += gpu.lastAllocFails;
        }
        float build = gpu.lastBuildMs, atlas = gpu.lastAtlasMs, submit = gpu.lastSubmitMs;
        int verts = gpu.lastVerts, draws = gpu.lastDrawCalls, pages = gpu.lastGlyphPages;
        int glyphs = gpu.lastGlyphCount, atlasBytes = gpu.lastAtlasUploadBytes;
        gpu.finish();
        long total = System.nanoTime() - tTotal0;
        float gaAvg = total / (float) frames / 1000000f;

        // VBO 路径对比
        float vboAvg = 0;
        if (diagVbo) {
            gpu.useVbo = true;
            for (int i = 0; i < WARMUP; i++) gpu.renderDisplayList(dl, 0, false);
            gpu.finish();
            long tv0 = System.nanoTime();
            for (int i = 0; i < frames; i++) gpu.renderDisplayList(dl, 0, false);
            gpu.finish();
            vboAvg = (System.nanoTime() - tv0) / (float) frames / 1000000f;
            gpu.useVbo = false;
        }

        // 半分辨率（fill-rate 诊断）
        float halfAvg = 0;
        if (diagHalf) {
            int hw = Math.max(1, w / 2), hh = Math.max(1, h / 2);
            gpu.onSurfaceChanged(hw, hh);
            DisplayList halfDl = DemoPages.buildBench(hw, hh, dl.cmds.size());
            for (int i = 0; i < 2; i++) gpu.renderDisplayList(halfDl, 0, false);
            gpu.finish();
            long th0 = System.nanoTime();
            for (int i = 0; i < frames; i++) gpu.renderDisplayList(halfDl, 0, false);
            gpu.finish();
            halfAvg = (System.nanoTime() - th0) / (float) frames / 1000000f;
            gpu.onSurfaceChanged(w, h);
        }

        // 可见文本数（scrollY=0 时的基线裁剪），用来判断"画面是否缺字"：
        // 文字用例的 quad 数应 ≈ 可见文本数（另有背景 1 个 quad）
        int visText = 0;
        for (DisplayList.Cmd c : dl.cmds) {
            if (c.type != DisplayList.TYPE_TEXT) continue;
            int sz = Math.round(c.textSize > 0 ? c.textSize : 24f);
            float baseline = c.y + sz;
            if (baseline >= -sz && baseline <= h + sz) visText++;
        }

        summary.append(String.format(
                "  %-13s cmds=%4d img=%2d txt=%3d | Canvas avg=%6.2f p95=%6.2f max=%6.2f | " +
                        "sync avg=%6.2f | async avg=%6.2f submitP95=%5.2f submitMax=%5.2f\n" +
                        "                 breakdown build=%5.2f atlas=%5.2f submit=%5.2f | " +
                        "verts=%5d draws=%2d pages=%d glyphs=%4d atlasUp=%dKB evict=%3d fail=%3d runs=%3d vis=%3d%s\n",
                label, dl.cmds.size(), imgCount, textCount,
                avg(cTimes), pct(cTimes, 95), max(cTimes),
                avg(gsTimes), gaAvg, pct(gaTimes, 95), max(gaTimes),
                build, atlas, submit, verts, draws, pages, glyphs, atlasBytes / 1024,
                evictTotal, failTotal, gpu.runCacheCount(), visText,
                (diagVbo ? String.format(" | vbo=%.2f", vboAvg) : "")
                        + (diagHalf ? String.format(" | half=%.2f(x%.2f)", halfAvg,
                        halfAvg / (gaAvg > 0 ? gaAvg : 1)) : "")));

        int outliers = 0;
        for (long v : gaTimes) if (v / 1000000f > gaAvg * 2f) outliers++;

        StringBuilder caf = new StringBuilder();
        StringBuilder gaf = new StringBuilder();
        for (int i = 0; i < frames; i++) {
            if (i > 0) { caf.append(','); gaf.append(','); }
            caf.append(String.format("%.2f", cTimes[i] / 1000000f));
            gaf.append(String.format("%.2f", gaTimes[i] / 1000000f));
        }

        return String.format(
                "{\"case\":\"%s\",\"cmds\":%d,\"images\":%d,\"texts\":%d," +
                        "\"canvas\":{\"avg\":%.2f,\"p95\":%.2f,\"max\":%.2f}," +
                        "\"gles2Sync\":{\"avg\":%.2f,\"p95\":%.2f,\"max\":%.2f}," +
                        "\"gles2Async\":{\"avg\":%.2f,\"fps\":%.1f,\"submitP95\":%.2f,\"submitMax\":%.2f}," +
                        "\"outliers\":%d," +
                        "\"vbo\":{\"avg\":%.2f}," +
                        "\"halfRes\":{\"avg\":%.2f}," +
                        "\"breakdown\":{\"build\":%.2f,\"atlas\":%.2f,\"submit\":%.2f," +
                        "\"verts\":%d,\"draws\":%d,\"pages\":%d,\"glyphs\":%d,\"atlasBytes\":%d," +
                        "\"evictions\":%d,\"allocFails\":%d,\"runCache\":%d,\"visibleTexts\":%d}," +
                        "\"perFrameCanvasMs\":[%s],\"perFrameAsyncMs\":[%s]}",
                label, dl.cmds.size(), imgCount, textCount,
                avg(cTimes), pct(cTimes, 95), max(cTimes),
                avg(gsTimes), pct(gsTimes, 95), max(gsTimes),
                gaAvg, 1000f / (gaAvg > 0 ? gaAvg : 1), pct(gaTimes, 95), max(gaTimes),
                outliers,
                vboAvg, halfAvg,
                build, atlas, submit, verts, draws, pages, glyphs, atlasBytes,
                evictTotal, failTotal, gpu.runCacheCount(), visText,
                caf.toString(), gaf.toString());
    }

    private static String join(List<String> items, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(items.get(i));
        }
        return b.toString();
    }

    private static float avgUpload(GpuCompositor gpu, ByteBuffer b, int w, int h) {
        long t0 = System.nanoTime();
        int reps = 8;
        for (int i = 0; i < reps; i++) {
            b.position(0);
            gpu.uploadFrame(b, w, h);
        }
        return (System.nanoTime() - t0) / reps / 1000000f;
    }

    private static float avg(long[] a) {
        long s = 0;
        for (long v : a) s += v;
        return s / (float) a.length / 1000000f;
    }

    private static float pct(long[] a, int p) {
        long[] b = a.clone();
        java.util.Arrays.sort(b);
        int idx = Math.min(b.length - 1, Math.max(0, (int) Math.ceil(p / 100f * b.length) - 1));
        return b[idx] / 1000000f;
    }

    private static float max(long[] a) {
        long m = 0;
        for (long v : a) m = Math.max(m, v);
        return m / 1000000f;
    }
}
