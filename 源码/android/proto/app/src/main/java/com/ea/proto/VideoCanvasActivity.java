package com.ea.proto;

import android.app.Activity;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Canvas 路径视频开洞 demo（对应主工程 NativeView.onDraw + SurfaceView 叠层）。
 *
 * am start -n com.ea.proto/.VideoCanvasActivity \
 *   --ez autoscroll true --ef scale 1.0 --ez skip true
 */
public class VideoCanvasActivity extends Activity {
    private static final String TAG = "EACanvasVideo";

    private FrameLayout root;
    private CanvasVideoView cv;
    private VideoSurfaceOverlay video;
    private TextView status;

    private final Handler ui = new Handler();
    private final RectF lastHole = new RectF();
    private boolean hasHole = false;
    private int lastPlacedL = -1, lastPlacedT = -1, lastPlacedW = -1, lastPlacedH = -1;

    private float viewportCssW, viewportCssH;
    private boolean autoscroll = false;
    private boolean appActive = false;
    private boolean videoPage = true;

    private float initialScroll = 0f;

    private long lastLogMs = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        float scale = 1.0f;
        boolean skip = true;
        android.content.Intent it = getIntent();
        if (it != null) {
            autoscroll = it.getBooleanExtra("autoscroll", false);
            scale = it.getFloatExtra("scale", 1.0f);
            skip = it.getBooleanExtra("skip", true);
            initialScroll = it.getFloatExtra("scroll", 0f);
        }

        buildUi();
        cv.setPageScale(scale);
        cv.setSkipVideo(skip);

        ui.postDelayed(statusTick, 500);
        if (autoscroll) ui.postDelayed(autoScrollTick, 1200);
        Log.i(TAG, "canvas video activity scale=" + scale + " skip=" + skip
                + " autoscroll=" + autoscroll);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        cv = new CanvasVideoView(this);
        cv.setHoleListener(new CanvasVideoView.HoleListener() {
            @Override public void onVideoHoles(List<RectF> holes) {
                if (holes.isEmpty()) {
                    hasHole = false;
                    applyVideoVisibility();
                } else {
                    RectF r = holes.get(0);
                    lastHole.set(r);
                    hasHole = true;
                    placeVideo(r);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastLogMs > 500) {
                        lastLogMs = now;
                        CanvasVideoView.logHole(r, cv.getScrollCss(), cv.getPageScale());
                    }
                }
            }
        });
        root.addView(cv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        video = new VideoSurfaceOverlay(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(160), dp(90));
        video.setVisibility(View.INVISIBLE);
        root.addView(video, vlp);

        status = new TextView(this);
        status.setTextColor(0xFFE5E7EB);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setBackgroundColor(0xCC101828);
        status.setPadding(dp(8), dp(6), dp(8), dp(6));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP;
        root.addView(status, slp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xCC101828);
        bottom.setPadding(dp(4), dp(4), dp(4), dp(6));
        bottom.addView(row(new String[]{"滚动-", "滚动+", "自动滚动", "缩放"},
                new Runnable[]{scrollBack, scrollFwd, autoToggle, scaleToggle}));
        bottom.addView(row(new String[]{"对照(不跳过)", "切页", "回到页首"},
                new Runnable[]{skipToggle, pageToggle, resetScroll}));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottom, blp);

        setContentView(root);

        cv.post(new Runnable() {
            @Override public void run() { setupPage(); }
        });
    }

    private LinearLayout row(String[] labels, Runnable[] actions) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            b.setPadding(0, 0, 0, 0);
            final Runnable a = actions[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { a.run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            r.addView(b, lp);
        }
        return r;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 页面按 CSS 尺寸构建：视口宽度 = 屏幕宽 / dpr，坐标才符合 doc→screen 公式 */
    private void setupPage() {
        float dpr = cv.getDpr();
        viewportCssW = cv.getWidth() / dpr;
        viewportCssH = cv.getHeight() / dpr;
        DisplayList list;
        if (videoPage) {
            // vh 传 1.5 倍：contentH=3*vh，留出把视频完全滚出屏幕的余量
            list = DemoPages.buildDemoVideo(viewportCssW, viewportCssH * 1.5f);
        } else {
            list = DemoPages.buildGpuPage(viewportCssW, viewportCssH);
        }
        cv.setList(list);
        cv.setScrollCss(initialScroll);
        initialScroll = 0f;
        Log.i(TAG, "page built videoPage=" + videoPage + " css=" + (int) viewportCssW + "x"
                + (int) viewportCssH + " cmds=" + list.cmds.size()
                + " contentCssH=" + (int) list.contentH);
        updateStatus();
    }

    private void placeVideo(RectF r) {
        if (!appActive || !cv.isSkipVideo()) return;
        final int l = Math.round(r.left), t = Math.round(r.top);
        final int w = Math.max(1, Math.round(r.right - r.left));
        final int h = Math.max(1, Math.round(r.bottom - r.top));
        if (l == lastPlacedL && t == lastPlacedT && w == lastPlacedW && h == lastPlacedH) return;
        lastPlacedL = l; lastPlacedT = t; lastPlacedW = w; lastPlacedH = h;
        // 不在 onDraw 里直接改布局，推迟到下一轮消息，避免绘制中 requestLayout
        root.post(new Runnable() {
            @Override public void run() {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) video.getLayoutParams();
                lp.leftMargin = l;
                lp.topMargin = t;
                lp.width = w;
                lp.height = h;
                video.setLayoutParams(lp);
                video.setVisibility(View.VISIBLE);
                if (!appActive) video.pausePlayback();
            }
        });
    }

    private void applyVideoVisibility() {
        if (!hasHole || !appActive || !cv.isSkipVideo()) {
            video.setVisibility(View.INVISIBLE);
            video.pausePlayback();
        } else {
            placeVideo(lastHole);
            video.setVisibility(View.VISIBLE);
            video.resumePlayback();
        }
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        appActive = true;
        applyVideoVisibility();
    }

    @Override protected void onPause() {
        appActive = false;
        video.setVisibility(View.INVISIBLE);
        video.pausePlayback();
        super.onPause();
        Log.i(TAG, "activity paused: video layer stopped");
    }

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            updateStatus();
            ui.postDelayed(this, 500);
        }
    };

    private final Runnable autoScrollTick = new Runnable() {
        @Override public void run() {
            if (!autoscroll) return;
            float max = cv.maxScrollCss();
            float next = cv.getScrollCss() + 120f;
            if (next > max) next = 0f;
            cv.setScrollCss(next);
            updateStatus();
            ui.postDelayed(this, 600);
        }
    };

    private final Runnable scrollFwd = new Runnable() {
        @Override public void run() { cv.setScrollCss(cv.getScrollCss() + 120f); updateStatus(); }
    };
    private final Runnable scrollBack = new Runnable() {
        @Override public void run() { cv.setScrollCss(cv.getScrollCss() - 120f); updateStatus(); }
    };
    private final Runnable resetScroll = new Runnable() {
        @Override public void run() { cv.setScrollCss(0f); updateStatus(); }
    };
    private final Runnable autoToggle = new Runnable() {
        @Override public void run() {
            autoscroll = !autoscroll;
            if (autoscroll) ui.postDelayed(autoScrollTick, 600);
            Log.i(TAG, "autoscroll=" + autoscroll);
            updateStatus();
        }
    };
    private final Runnable scaleToggle = new Runnable() {
        @Override public void run() {
            float s = cv.getPageScale() >= 1.25f ? 1.0f : 1.25f;
            cv.setPageScale(s);
            Log.i(TAG, "pageScale -> " + s);
            updateStatus();
        }
    };
    private final Runnable skipToggle = new Runnable() {
        @Override public void run() {
            boolean skip = !cv.isSkipVideo();
            cv.setSkipVideo(skip);
            Log.i(TAG, "skipVideo=" + skip);
            if (!skip) {
                video.setVisibility(View.INVISIBLE);
                video.pausePlayback();
            }
            updateStatus();
        }
    };
    private final Runnable pageToggle = new Runnable() {
        @Override public void run() {
            videoPage = !videoPage;
            setupPage();
            Log.i(TAG, "switch page -> videoPage=" + videoPage);
        }
    };

    private void updateStatus() {
        if (status == null) return;
        float dpr = cv.getDpr();
        String hole = hasHole
                ? String.format(Locale.US, "(%d,%d %dx%d)",
                (int) lastHole.left, (int) lastHole.top,
                (int) (lastHole.right - lastHole.left), (int) (lastHole.bottom - lastHole.top))
                : "none";
        status.setText(String.format(Locale.US,
                "Canvas 路径视频开洞  dpr=%.2f  scale=%.2f  skip=%s\n"
                        + "scrollCss=%.0f px=%.0f  max=%.0f  contentHcss=%.0f\n"
                        + "hole(screen)=%s  layer=%s paused=%s\n"
                        + "公式 screen=(doc*pageScale-scroll)*dpr",
                dpr, cv.getPageScale(), cv.isSkipVideo() ? "on" : "OFF",
                cv.getScrollCss(), cv.getScrollCss() * dpr, cv.maxScrollCss(),
                cv.getContentCssH(), hole,
                video.getVisibility() == View.VISIBLE ? "VISIBLE" : "HIDDEN",
                video.isPaused() ? "yes" : "no"));
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
