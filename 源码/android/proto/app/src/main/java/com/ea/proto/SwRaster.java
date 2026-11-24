package com.ea.proto;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.HashMap;
import java.util.Map;

/** Canvas 软件光栅：子进程的兜底路径，同时作为 GPU 基准的对照组 */
public class SwRaster {

    private static final Map<String, Bitmap> IMAGE_CACHE = new HashMap<String, Bitmap>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();

    /** 程序化图片（原型不依赖外部资源，相当于纹理加载占位） */
    public static Bitmap image(String src) {
        synchronized (IMAGE_CACHE) {
            Bitmap b = IMAGE_CACHE.get(src);
            if (b != null) return b;
            int size = 256;
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            int seed = src == null ? 0 : src.hashCode();
            int r = 60 + Math.abs(seed % 150);
            int g = 60 + Math.abs((seed / 7) % 150);
            int bl = 60 + Math.abs((seed / 13) % 150);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, size, size,
                    Color.rgb(r, g, bl), Color.rgb(bl, r, g), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, size, size, p);
            p.setShader(null);
            p.setColor(0x66FFFFFF);
            for (int i = 0; i < 6; i++) {
                int d = (i + 1) * 26;
                c.drawCircle(size / 2, size / 2, d, p);
            }
            p.setColor(0x33FFFFFF);
            c.drawRect(0, size * 0.6f, size, size, p);
            IMAGE_CACHE.put(src, b);
            return b;
        }
    }

    public void draw(DisplayList dl, Bitmap target, float scrollY) {
        drawRegion(dl, target, scrollY, 0, target.getHeight(), true);
    }

    /** 增量绘制：只重画设备坐标 [clipTop, clipBottom) 的条带，不擦背景 */
    public void drawBand(DisplayList dl, Bitmap target, float scrollY, int clipTop, int clipBottom) {
        drawRegion(dl, target, scrollY, clipTop, clipBottom, false);
    }

    private void drawRegion(DisplayList dl, Bitmap target, float scrollY,
                            int clipTop, int clipBottom, boolean clear) {
        Canvas canvas = new Canvas(target);
        if (clear) canvas.drawColor(0xFFFFFFFF);
        canvas.save();
        canvas.clipRect(0, clipTop, target.getWidth(), clipBottom);

        for (DisplayList.Cmd c : dl.cmds) {
            float top = c.y;
            float bottom = c.y + c.h;
            if (!c.hasTransform && (bottom < scrollY - 4 || top > scrollY + dl.viewportH + 4)) {
                continue;
            }
            canvas.save();
            canvas.translate(0, -scrollY);
            if (c.hasTransform) {
                float absOx = c.x + c.ox, absOy = c.y + c.oy;
                matrix.reset();
                matrix.setTranslate(-absOx, -absOy);
                matrix.postScale(c.sx, c.sy);
                matrix.postSkew((float) Math.tan(Math.toRadians(c.skx)),
                                (float) Math.tan(Math.toRadians(c.sky)));
                matrix.postRotate(c.rot);
                matrix.postTranslate(absOx + c.tx, absOy + c.ty);
                canvas.concat(matrix);
            }
            drawCmd(canvas, c);
            canvas.restore();
        }
        canvas.restore();
    }

    /** 单条指令绘制（不含滚动/缩放变换）；Canvas 路径与软件光栅共用 */
    void drawCmd(Canvas canvas, DisplayList.Cmd c) {
        rect.set(c.x, c.y, c.x + c.w, c.y + c.h);
        paint.reset();
        paint.setAntiAlias(true);

        int color = c.color;
        int alpha = (int) (Color.alpha(color) * c.opacity);
        color = (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);

        switch (c.type) {
            case DisplayList.TYPE_IMAGE: {
                paint.setAlpha(Math.min(255, Color.alpha(color)));
                Bitmap img = image(c.src != null ? c.src : (c.bg != null ? c.bg : "img"));
                canvas.drawBitmap(img, null, rect, paint);
                break;
            }
            case DisplayList.TYPE_TEXT: {
                paint.setColor(color);
                paint.setTextSize(c.textSize > 0 ? c.textSize : 24f);
                paint.setFakeBoldText(c.bold);
                paint.setTextSkewX(c.italic ? -0.25f : 0);
                paint.setStyle(Paint.Style.FILL);
                String t = c.text != null ? c.text : "";
                float x = c.x;
                if (c.align == 1) x = c.x + c.w / 2f - paint.measureText(t) / 2f;
                else if (c.align == 2) x = c.x + c.w - paint.measureText(t);
                canvas.drawText(t, x, c.y + (c.textSize > 0 ? c.textSize : 24f), paint);
                break;
            }
            case DisplayList.TYPE_BORDER: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(c.textSize > 0 ? c.textSize : 2f);
                paint.setColor(color);
                if (c.radius > 0) canvas.drawRoundRect(rect, c.radius, c.radius, paint);
                else canvas.drawRect(rect, paint);
                break;
            }
            case DisplayList.TYPE_LINE: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(c.textSize > 0 ? c.textSize : 2f);
                paint.setColor(color);
                canvas.drawRect(rect, paint);
                break;
            }
            case DisplayList.TYPE_CONIC: {
                applyConic(paint, c);
                paint.setStyle(Paint.Style.FILL);
                if (c.radius > 0) canvas.drawRoundRect(rect, c.radius, c.radius, paint);
                else canvas.drawRect(rect, paint);
                break;
            }
            default: {
                if (c.gradientType == 1) {
                    applyLinear(paint, c);
                } else if (c.gradientType == 2) {
                    applyRadial(paint, c);
                } else {
                    paint.setColor(color);
                }
                paint.setStyle(Paint.Style.FILL);
                if (c.shadows != null && !c.shadows.isEmpty()) {
                    float[] s = c.shadows.get(0);
                    paint.setShadowLayer(s[2], s[0], s[1], (int) s[4]);
                }
                if (c.radius > 0) canvas.drawRoundRect(rect, c.radius, c.radius, paint);
                else canvas.drawRect(rect, paint);
                if (c.bg != null && c.bg.length() > 0) {
                    paint.clearShadowLayer();
                    paint.setAlpha(120);
                    canvas.drawBitmap(image(c.bg), null, rect, paint);
                }
                break;
            }
        }
    }

    private void applyLinear(Paint p, DisplayList.Cmd c) {
        float angle = (float) Math.toRadians(c.gradientAngle);
        float cx = c.x + c.w / 2f;
        float cy = c.y + c.h / 2f;
        float len = Math.abs(c.w * (float) Math.cos(angle))
                  + Math.abs(c.h * (float) Math.sin(angle));
        float dx = (float) Math.cos(angle) * len / 2f;
        float dy = (float) Math.sin(angle) * len / 2f;
        p.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                c.c0, c.c1, Shader.TileMode.CLAMP));
    }

    private void applyRadial(Paint p, DisplayList.Cmd c) {
        float cx = c.x + c.w / 2f;
        float cy = c.y + c.h / 2f;
        float r = Math.max(c.w, c.h) / 2f;
        p.setShader(new RadialGradient(cx, cy, Math.max(1f, r), c.c0, c.c1,
                Shader.TileMode.CLAMP));
    }

    private void applyConic(Paint p, DisplayList.Cmd c) {
        float cx = c.x + c.w / 2f;
        float cy = c.y + c.h / 2f;
        int n = c.conicPos == null ? 0 : c.conicPos.length;
        if (n < 2) {
            p.setShader(new SweepGradient(cx, cy, c.c0, c.c1));
        } else {
            p.setShader(new SweepGradient(cx, cy, c.conicColor, c.conicPos));
        }
    }
}
