package com.ea.proto;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 渲染子进程（android:process=":render"）。
 * 持有解析/布局/光栅，随时可被牺牲；权威状态在主进程。
 */
public class RenderService extends Service {
    private static final String TAG = "EAProto";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile IRenderCallback callback;
    private FrameStore store;
    private Bitmap bitmap;
    private DisplayList list;
    private String pageKind = "gpu";
    private float scrollY;
    private float contentH = 1920;
    private int width = 1, height = 1;
    private int generation;
    private int seq;
    private float lastRasterMs;
    private int cmdCount;
    private int historyIndex;
    private String[] history = new String[0];
    private float lastRenderedScroll;
    private boolean hasFrame;
    private final SwRaster sw = new SwRaster();

    private final IRenderService.Stub binder = new IRenderService.Stub() {
        @Override public void setCallback(IRenderCallback cb) {
            callback = cb;
        }

        @Override public void configure(final String path, final int w, final int h, final int gen) {
            executor.execute(new Runnable() {
                @Override public void run() {
                    generation = gen;
                    if (store != null) store.close();
                    store = FrameStore.open(new File(path));
                    if (store == null) {
                        Log.e(TAG, "render: FrameStore.open failed " + path);
                        reportError(1, "shared frame open failed");
                        return;
                    }
                    width = w;
                    height = h;
                    if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    }
                    Log.i(TAG, "render configured " + w + "x" + h + " gen=" + generation
                            + " pid=" + Process.myPid());
                }
            });
        }

        @Override public void openPage(final String page, final float vw, final float vh,
                                       final float sy, final int hIndex, final String[] hist) {
            executor.execute(new Runnable() {
                @Override public void run() {
                    pageKind = page;
                    scrollY = sy;
                    historyIndex = hIndex;
                    history = hist;
                    if (page.startsWith("engine")) {
                        list = loadEngineList();
                    } else if (page.equals("video")) {
                        list = DemoPages.buildDemoVideo(vw, vh);
                    } else {
                        list = DemoPages.buildGpuPage(vw, vh);
                    }
                    if (list == null) {
                        list = DemoPages.buildGpuPage(vw, vh);
                        pageKind = "gpu";
                    }
                    contentH = list.contentH;
                    cmdCount = list.cmds.size();
                    hasFrame = false;
                    lastRenderedScroll = scrollY;
                    IRenderCallback cb = callback;
                    if (cb != null) {
                        try {
                            cb.onDisplayList(list.toJson(), cmdCount, contentH,
                                    Process.myPid(), generation);
                        } catch (Throwable t) {
                            Log.e(TAG, "onDisplayList failed: " + t);
                        }
                    }
                    Log.i(TAG, "render openPage " + pageKind + " cmds=" + cmdCount
                            + " contentH=" + contentH + " scroll=" + scrollY
                            + " history=" + historyIndex + "/" + history.length
                            + " pid=" + Process.myPid());
                    if (pageKind.equals("engine")) renderFrame("open");
                    else notifyFrame("open", 0f);
                }
            });
        }

        @Override public void setScroll(final float y) {
            executor.execute(new Runnable() {
                @Override public void run() {
                    scrollY = y;
                    if (pageKind.equals("engine")) renderFrame("scroll");
                    else notifyFrame("scroll", 0f);
                }
            });
        }

        @Override public void requestFrame() {
            executor.execute(new Runnable() {
                @Override public void run() {
                    if (pageKind.equals("engine")) renderFrame("request");
                    else notifyFrame("request", 0f);
                }
            });
        }

        @Override public void crashNow() {
            executor.execute(new Runnable() {
                @Override public void run() {
                    Log.w(TAG, "render crash requested; kill pid=" + Process.myPid());
                    Process.killProcess(Process.myPid());
                }
            });
        }

        @Override public String getStats() {
            return "{\"pid\":" + Process.myPid()
                    + ",\"generation\":" + generation
                    + ",\"mode\":\"" + pageKind + "\""
                    + ",\"cmds\":" + cmdCount
                    + ",\"contentH\":" + contentH
                    + ",\"scrollY\":" + scrollY
                    + ",\"seq\":" + seq
                    + ",\"rasterMs\":" + lastRasterMs
                    + ",\"historyIndex\":" + historyIndex
                    + ",\"historySize\":" + history.length + "}";
        }

        @Override public String runTransportBench() {
            return transportBench();
        }
    };

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "RenderService onBind pid=" + Process.myPid());
        return binder;
    }

    @Override public void onDestroy() {
        Log.i(TAG, "RenderService onDestroy pid=" + Process.myPid());
        if (store != null) store.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    private DisplayList loadEngineList() {
        try {
            InputStream in = getAssets().open("displaylist.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            String json = new String(bo.toByteArray(), "UTF-8");
            DisplayList dl = DisplayList.parse(json);
            Log.i(TAG, "render engine list cmds=" + dl.cmds.size()
                    + " bytes=" + json.length());
            return dl;
        } catch (Throwable t) {
            Log.e(TAG, "loadEngineList failed: " + t);
            return null;
        }
    }

    /** engine 模式：软件光栅 → 共享内存像素帧（小滚动只重画暴露条带） */
    private void renderFrame(String reason) {
        if (store == null || bitmap == null || list == null) {
            reportError(2, "render not configured");
            return;
        }
        float prevScroll = lastRenderedScroll;
        int delta = Math.round(scrollY - prevScroll);
        boolean incremental = hasFrame
                && ("scroll".equals(reason) || "request".equals(reason))
                && Math.abs(delta) > 0 && Math.abs(delta) <= height / 2;

        int dx = 0, dy = 0, dw = width, dh = height;
        long t0 = System.nanoTime();
        int newSeq = seq + 1;
        if (incremental) {
            int bandTop = delta > 0 ? height - delta : 0;
            int bandBottom = delta > 0 ? height : -delta;
            sw.drawBand(list, bitmap, scrollY, bandTop, bandBottom);
            dy = bandTop;
            dh = bandBottom - bandTop;
        } else {
            sw.draw(list, bitmap, scrollY);
        }
        float rasterMs = (System.nanoTime() - t0) / 1000000f;
        lastRasterMs = rasterMs;
        seq = newSeq;
        long ts = System.nanoTime();
        store.publish(bitmap, seq, generation, scrollY, contentH, rasterMs, cmdCount, ts,
                dx, dy, dw, dh);
        lastRenderedScroll = scrollY;
        hasFrame = true;
        IRenderCallback cb = callback;
        if (cb != null) {
            try {
                cb.onFrame(seq, generation, scrollY, contentH, rasterMs, cmdCount, ts);
            } catch (Throwable t) {
                Log.e(TAG, "onFrame failed: " + t);
            }
        }
        Log.i(TAG, "render frame seq=" + seq + " raster=" + rasterMs + "ms reason=" + reason
                + " scroll=" + scrollY + " dirty=" + dx + "," + dy + "," + dw + "," + dh);
    }

    /** 整帧 vs 脏带 的端到端（光栅+槽复制+发布）耗时对照 */
    public String transportBench() {
        if (store == null || bitmap == null || list == null) return "{}";
        int reps = 12;
        int bandH = Math.min(400, height);
        float[] full = new float[reps];
        float[] dirty = new float[reps];
        float[] drawFull = new float[reps];
        float[] drawBand = new float[reps];
        float[] pubFull = new float[reps];
        float[] pubBand = new float[reps];
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            sw.draw(list, bitmap, scrollY);
            drawFull[i] = (System.nanoTime() - t0) / 1000000f;
            seq++;
            long t1 = System.nanoTime();
            store.publish(bitmap, seq, generation, scrollY, contentH, 0f, cmdCount,
                    System.nanoTime(), 0, 0, width, height);
            pubFull[i] = (System.nanoTime() - t1) / 1000000f;
            full[i] = drawFull[i] + pubFull[i];
        }
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            sw.drawBand(list, bitmap, scrollY, height - bandH, height);
            drawBand[i] = (System.nanoTime() - t0) / 1000000f;
            seq++;
            long t1 = System.nanoTime();
            store.publish(bitmap, seq, generation, scrollY, contentH, 0f, cmdCount,
                    System.nanoTime(), 0, height - bandH, width, bandH);
            pubBand[i] = (System.nanoTime() - t1) / 1000000f;
            dirty[i] = drawBand[i] + pubBand[i];
        }
        return String.format(
                "{\"fullAvg\":%.2f,\"fullDraw\":%.2f,\"fullPublish\":%.2f," +
                        "\"dirtyAvg\":%.2f,\"dirtyDraw\":%.2f,\"dirtyPublish\":%.2f," +
                        "\"bandH\":%d,\"width\":%d,\"height\":%d}",
                avg(full), avg(drawFull), avg(pubFull),
                avg(dirty), avg(drawBand), avg(pubBand),
                bandH, width, height);
    }

    private static float avg(float[] a) {
        float s = 0;
        for (float v : a) s += v;
        return s / a.length;
    }

    private void notifyFrame(String reason, float rasterMs) {
        seq++;
        lastRasterMs = rasterMs;
        IRenderCallback cb = callback;
        if (cb != null) {
            try {
                cb.onFrame(seq, generation, scrollY, contentH, rasterMs, cmdCount, System.nanoTime());
            } catch (Throwable t) {
                Log.e(TAG, "onFrame failed: " + t);
            }
        }
        Log.i(TAG, "render tick seq=" + seq + " reason=" + reason + " scroll=" + scrollY);
    }

    private void reportError(int code, String msg) {
        IRenderCallback cb = callback;
        if (cb != null) {
            try { cb.onError(code, msg); } catch (Throwable ignored) { }
        }
    }
}
