package com.ea.proto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas 路径（对应主工程 NativeView.onDraw）的逐条绘制：
 * EA_DRAW_VIDEO 标记的矩形不画、不填色，把这块留给叠在上面的 SurfaceView。
 *
 * 坐标换算（工程化写法）：
 *   screenX = docX * pageScale * dpr
 *   screenY = (docY * pageScale - scrollCss) * dpr
 * 其中 doc 是文档/CSS 坐标，scrollCss 是 CSS px 的滚动量，dpr 是屏幕密度。
 * Canvas 变换用 scale(pageScale*dpr) + translate(0, -scrollCss/pageScale)，
 * 与上式严格等价（deviceY = (docY*pageScale - scrollCss) * dpr）。
 */
public class CanvasVideoView extends View {

    private static final String TAG = "EACanvasVideo";
    private static final float[] QX = new float[]{0f, 1f, 0f, 1f};
    private static final float[] QY = new float[]{0f, 0f, 1f, 1f};

    public interface HoleListener {
        /** 屏幕坐标（px，View 坐标系）里的视频洞；没有洞时传空列表 */
        void onVideoHoles(List<RectF> holes);
    }

    private final SwRaster raster = new SwRaster();
    private final List<RectF> holes = new ArrayList<RectF>();
    private final List<RectF> lastHoles = new ArrayList<RectF>();

    private DisplayList list;
    private float scrollCss = 0f;
    private float pageScale = 1f;
    private float dpr = 1f;
    private boolean skipVideo = true;
    private HoleListener holeListener;

    public CanvasVideoView(Context c) {
        super(c);
        dpr = c.getResources().getDisplayMetrics().density;
    }

    public void setList(DisplayList l) {
        list = l;
        invalidate();
    }

    public void setScrollCss(float y) {
        if (y < 0f) y = 0f;
        float max = maxScrollCss();
        if (y > max) y = max;
        if (Math.abs(y - scrollCss) < 0.01f) return;
        scrollCss = y;
        invalidate();
    }

    public float getScrollCss() { return scrollCss; }
    public float getContentCssH() { return list == null ? 0f : list.contentH; }
    public float getViewportCssH() { return dpr <= 0f ? 0f : getHeight() / dpr; }
    public float maxScrollCss() {
        return Math.max(0f, getContentCssH() - getViewportCssH());
    }
    public float getDpr() { return dpr; }

    public void setPageScale(float s) { pageScale = Math.max(0.5f, Math.min(2.5f, s)); invalidate(); }
    public float getPageScale() { return pageScale; }

    /** false = 对照实验：照常绘制，等于没开洞（会看到视频元素的占位色） */
    public void setSkipVideo(boolean skip) { skipVideo = skip; invalidate(); }
    public boolean isSkipVideo() { return skipVideo; }

    public void setHoleListener(HoleListener l) { holeListener = l; }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        DisplayList l = list;
        if (l == null) return;

        canvas.save();
        canvas.scale(pageScale * dpr, pageScale * dpr);
        canvas.translate(0f, -scrollCss / pageScale);
        for (DisplayList.Cmd c : l.cmds) {
            if (c.type == DisplayList.TYPE_VIDEO && skipVideo) continue;
            raster.drawCmd(canvas, c);
        }
        canvas.restore();

        collectHoles();
        notifyHolesIfChanged();
    }

    private void collectHoles() {
        holes.clear();
        DisplayList l = list;
        if (l == null || !skipVideo) return;
        int vw = getWidth(), vh = getHeight();
        for (DisplayList.Cmd c : l.cmds) {
            if (c.type != DisplayList.TYPE_VIDEO) continue;
            RectF r = new RectF();
            if (videoRectToScreen(c, pageScale, scrollCss, dpr, vw, vh, r)) holes.add(r);
        }
    }

    private void notifyHolesIfChanged() {
        boolean same = holes.size() == lastHoles.size();
        if (same) {
            for (int i = 0; i < holes.size(); i++) {
                if (!sameRect(holes.get(i), lastHoles.get(i))) { same = false; break; }
            }
        }
        if (same) return;
        lastHoles.clear();
        for (RectF r : holes) lastHoles.add(new RectF(r));
        HoleListener l = holeListener;
        if (l != null) l.onVideoHoles(new ArrayList<RectF>(holes));
    }

    private static boolean sameRect(RectF a, RectF b) {
        return Math.round(a.left) == Math.round(b.left)
                && Math.round(a.top) == Math.round(b.top)
                && Math.round(a.right) == Math.round(b.right)
                && Math.round(a.bottom) == Math.round(b.bottom);
    }

    /**
     * 文档坐标 → 屏幕坐标（含 pageScale、滚动、dpr、以及命令自带的 4 角变换）。
     * 返回 false 表示完全在视口外。
     */
    public static boolean videoRectToScreen(DisplayList.Cmd c, float pageScale, float scrollCss,
                                            float dpr, int viewW, int viewH, RectF out) {
        if (c.w <= 0f || c.h <= 0f) return false;
        float rad = (float) Math.toRadians(c.rot);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float tanSkx = (float) Math.tan(Math.toRadians(c.skx));
        float tanSky = (float) Math.tan(Math.toRadians(c.sky));
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float px = c.x + QX[i] * c.w, py = c.y + QY[i] * c.h;
            if (c.hasTransform) {
                float absOx = c.x + c.ox, absOy = c.y + c.oy;
                float x1 = px - absOx, y1 = py - absOy;
                float x2 = x1 * c.sx, y2 = y1 * c.sy;
                float x3 = x2 + tanSkx * y2;
                float y3 = y2 + tanSky * x3;
                float x4 = cos * x3 - sin * y3;
                float y4 = sin * x3 + cos * y3;
                px = x4 + absOx + c.tx;
                py = y4 + absOy + c.ty;
            }
            float sx = px * pageScale * dpr;
            float sy = (py * pageScale - scrollCss) * dpr;
            if (sx < minX) minX = sx;
            if (sx > maxX) maxX = sx;
            if (sy < minY) minY = sy;
            if (sy > maxY) maxY = sy;
        }
        float x0 = Math.max(0f, minX), y0 = Math.max(0f, minY);
        float x1 = Math.min((float) viewW, maxX), y1 = Math.min((float) viewH, maxY);
        if (x1 - x0 < 1f || y1 - y0 < 1f) return false;
        out.set(x0, y0, x1, y1);
        return true;
    }

    public static void logHole(RectF r, float scrollCss, float pageScale) {
        Log.i(TAG, "canvas hole screen=(" + (int) r.left + "," + (int) r.top + ","
                + (int) (r.right - r.left) + "x" + (int) (r.bottom - r.top) + ")"
                + " scrollCss=" + (int) scrollCss + " scale=" + pageScale);
    }
}
