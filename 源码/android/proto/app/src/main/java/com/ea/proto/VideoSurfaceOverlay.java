package com.ea.proto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * 叠在 Canvas 洞上的视频图层占位实现（条纹动画，代替真实 MediaPlayer）。
 * 关键点：setZOrderMediaOverlay(true) 让 surface 盖在 Canvas 所在窗口之上；
 * 暂停时停线程而不是销毁 surface（避免切后台回来重建导致黑闪）。
 */
public class VideoSurfaceOverlay extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "EACanvasVideo";

    private Thread thread;
    private volatile boolean paused = true;
    private volatile boolean surfaceReady = false;
    private final Paint paint = new Paint();
    private int createdCount, changedCount, destroyedCount;

    public VideoSurfaceOverlay(Context c) {
        super(c);
        setZOrderMediaOverlay(true);
        getHolder().addCallback(this);
    }

    public void resumePlayback() {
        paused = false;
        if (surfaceReady && thread == null) {
            thread = new Thread(this, "video-overlay");
            thread.start();
            Log.i(TAG, "video overlay resume (thread start)");
        }
    }

    public void pausePlayback() {
        paused = true;
        Log.i(TAG, "video overlay pause");
    }

    public boolean isPaused() { return paused; }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        createdCount++;
        surfaceReady = true;
        Log.i(TAG, "video surfaceCreated #" + createdCount + " size="
                + holder.getSurfaceFrame().width() + "x" + holder.getSurfaceFrame().height());
        resumePlayback();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        changedCount++;
        Log.i(TAG, "video surfaceChanged #" + changedCount + " " + width + "x" + height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        destroyedCount++;
        surfaceReady = false;
        paused = true;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(200); } catch (Throwable ignored) { }
        }
        Log.i(TAG, "video surfaceDestroyed #" + destroyedCount);
    }

    @Override public void run() {
        long t0 = android.os.SystemClock.uptimeMillis();
        int frame = 0;
        while (!paused && surfaceReady) {
            SurfaceHolder h = getHolder();
            Canvas cv = null;
            try {
                cv = h.lockCanvas();
                if (cv != null) {
                    int w = cv.getWidth(), hh = cv.getHeight();
                    long el = android.os.SystemClock.uptimeMillis() - t0;
                    int off = (int) ((el / 5) % 96);
                    cv.drawColor(0xFF071426);
                    paint.setColor(0xFF22D3EE);
                    for (int y = -96; y < hh + 96; y += 96) {
                        cv.drawRect(0, y + off, w, y + off + 48, paint);
                    }
                    paint.setColor(0xFFFFFFFF);
                    paint.setTextSize(28);
                    cv.drawText("VIDEO Canvas " + frame + "  " + el + "ms", 16, 40, paint);
                    frame++;
                }
            } catch (Throwable ignored) {
            } finally {
                if (cv != null) {
                    try { h.unlockCanvasAndPost(cv); } catch (Throwable ignored) { }
                }
            }
            try { Thread.sleep(40); } catch (Throwable ignored) { }
        }
        thread = null;
    }
}
