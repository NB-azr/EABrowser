package com.ea.proto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

import javax.microedition.khronos.opengles.GL10;
import android.opengl.GLES20;

/**
 * 主进程：UI + 权威 TabState + GPU 呈现。
 * 渲染在 :render 子进程；子进程死亡后自动重启并恢复滚动/历史。
 */
public class MainActivity extends Activity {
    private static final String TAG = "EAProto";

    private FrameLayout root;
    private GLSurfaceView glView;
    private TextView statusText, benchText;

    private final Handler ui = new Handler();

    // ---- 权威状态（主进程）
    private volatile String mode = "gpu";       // gpu=指令→GPU；sw=子进程软件帧
    private volatile float scrollY = 0f;
    private volatile float contentH = 1920f;
    private volatile int historyIndex = 0;
    private volatile int generation = 1;
    private final String[] history = {
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/c"
    };

    // ---- 渲染进程回报
    private volatile String displayListJson;
    private volatile int renderPid = 0;
    private volatile int frameSeq = 0;
    private volatile int cmdCount = 0;
    private volatile float lastRasterMs, lastUploadMs, lastDrawMs, lastPresentMs, lastIpcMs;
    private volatile boolean lastUploadDirty;
    private volatile int presentedFrames = 0;
    private volatile String benchSummary;
    private volatile int benchEpoch = 0;

    // ---- 表面 / 共享内存
    private volatile int surfaceW = 0, surfaceH = 0;
    private FrameStore store;
    private File storeFile;

    // ---- 绑定与重启
    private volatile IRenderService service;
    private boolean bound = false;
    private boolean blocked = false;
    private boolean deathHandled = false;
    private int crashCount = 0;
    private long crashWindowStart = 0;

    private volatile boolean benchRequested = false;
    private int benchFrames = 20;

    private GLProtoRenderer renderer;

    // ---- 视频开洞 demo（EA_DRAW_VIDEO）
    private volatile boolean videoDemo = false;
    private volatile boolean autoScroll = false;
    private volatile boolean videoHoleOff = false;   // 对照实验：关闭开洞，让 GL 照常画（会看到洋红）
    private SurfaceView videoView;
    private final float[] holeBuf = new float[4];
    private long lastHoleLogMs = 0;

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            Log.w(TAG, "binderDied: render process died");
            ui.post(new Runnable() {
                @Override public void run() { onRendererDied("binderDied"); }
            });
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            IRenderService s = IRenderService.Stub.asInterface(binder);
            service = s;
            deathHandled = false;
            try {
                binder.linkToDeath(deathRecipient, 0);
            } catch (Throwable t) {
                Log.w(TAG, "linkToDeath failed: " + t);
            }
            Log.i(TAG, "onServiceConnected gen=" + generation);
            try {
                s.setCallback(callback);
            } catch (Throwable t) {
                Log.e(TAG, "setCallback failed: " + t);
            }
            ui.post(new Runnable() {
                @Override public void run() {
                    updateStatus();
                    openPageNow();
                }
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected");
            ui.post(new Runnable() {
                @Override public void run() { onRendererDied("onServiceDisconnected"); }
            });
        }
    };

    private final IRenderCallback callback = new IRenderCallback.Stub() {
        @Override public void onDisplayList(final String json, final int cmds,
                                            final float contentHeight, final int pid, final int gen) {
            displayListJson = json;
            cmdCount = cmds;
            contentH = contentHeight;
            renderPid = pid;
            generation = gen;
            Log.i(TAG, "main onDisplayList cmds=" + cmds + " contentH=" + contentHeight
                    + " pid=" + pid + " gen=" + gen + " bytes=" + (json == null ? 0 : json.length()));
            ui.post(new Runnable() {
                @Override public void run() {
                    updateStatus();
                    if (glView != null) glView.requestRender();
                }
            });
        }

        @Override public void onFrame(final int seq, final int gen, final float sy,
                                      final float ch, final float rasterMs,
                                      final int cmds, final long publishTs) {
            frameSeq = seq;
            generation = gen;
            scrollY = sy;
            contentH = ch;
            lastRasterMs = rasterMs;
            lastIpcMs = (System.nanoTime() - publishTs) / 1000000f;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (glView != null) glView.requestRender();
                    updateStatus();
                }
            });
        }

        @Override public void onError(int code, String msg) {
            Log.e(TAG, "main onError " + code + " " + msg);
        }
    };

    private final Runnable rebindRunnable = new Runnable() {
        @Override public void run() {
            if (blocked) return;
            bindToRenderer();
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            updateStatus();
            writeBenchIfNeeded();
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        SharedPreferences prefs = getSharedPreferences("tab_state", MODE_PRIVATE);
        mode = prefs.getString("mode", "gpu");
        scrollY = prefs.getFloat("scrollY", 0f);
        historyIndex = prefs.getInt("historyIndex", 0);
        generation = prefs.getInt("generation", 1);

        android.content.Intent it0 = getIntent();
        if (it0 != null) {
            videoDemo = it0.getBooleanExtra("video", false);
            autoScroll = it0.getBooleanExtra("autoscroll", false);
            videoHoleOff = !it0.getBooleanExtra("hole", true);
        }
        if (videoDemo) {
            mode = "gpu";       // 视频层必须由主进程 GL 合成
            scrollY = 0f;       // demo 从页首开始
        }

        buildUi();
        bindToRenderer();
        probeIsolatedProcess();
        ui.postDelayed(statusTick, 500);

        // 真机/脚本自动化入口：am start -n com.ea.proto/.MainActivity -e bench 1 -e frames 20
        android.content.Intent it = getIntent();
        if (it != null && it.getBooleanExtra("bench", false)) {
            benchFrames = it.getIntExtra("frames", 20);
            benchRequested = true;
            if (glView != null) glView.requestRender();
        }
        if (autoScroll) ui.postDelayed(autoScrollTick, 1200);
    }

    // ------------------------------------------------------------ isolatedProcess 探针
    private ServiceConnection isoConn;

    /**
     * 验证 isolatedProcess（独立 UID、无存储权限）下：
     * 1) 主进程能否绑定该 service；2) 匿名管道 fd 能否跨 Binder 传递并写回。
     * 探针只验证通道；ashmem 需要 fd + mmap，管道替代不了随机访问。
     */
    private void probeIsolatedProcess() {
        isoConn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                final IIsoProbe probe = IIsoProbe.Stub.asInterface(binder);
                new Thread(new Runnable() {
                    @Override public void run() {
                        ParcelFileDescriptor[] pipe = null;
                        try {
                            pipe = ParcelFileDescriptor.createPipe();
                            int rc = probe.ping(pipe[1]);
                            pipe[1].close();
                            pipe[1] = null;
                            FileInputStream in = new FileInputStream(pipe[0].getFileDescriptor());
                            byte[] buf = new byte[64];
                            int len = in.read(buf);
                            String data = new String(buf, 0, Math.max(0, len), "UTF-8");
                            Log.i(TAG, "isolated probe rc=" + rc + " data=" + data
                                    + " info=" + probe.info());

                            // ashmem 探针：MemoryFile 的 fd 经反射取出，用 Binder 传给 isolated 读回
                            try {
                                android.os.MemoryFile mf =
                                        new android.os.MemoryFile("iso_probe", 4096);
                                byte[] pay = "ASH19:shared".getBytes("UTF-8");
                                mf.writeBytes(pay, 0, 0, pay.length);
                                java.lang.reflect.Field f =
                                        android.os.MemoryFile.class.getDeclaredField("mFD");
                                f.setAccessible(true);
                                int fd;
                                Object fdObj = f.get(mf);
                                if (fdObj instanceof Integer) {
                                    fd = (Integer) fdObj;
                                } else {
                                    java.lang.reflect.Field df = java.io.FileDescriptor.class
                                            .getDeclaredField("descriptor");
                                    df.setAccessible(true);
                                    fd = (Integer) df.get(fdObj);
                                }
                                ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
                                String back = probe.readBytes(pfd, pay.length);
                                Log.i(TAG, "isolated ashmem read back=" + back);
                                pfd.close();
                            } catch (Throwable t) {
                                Log.w(TAG, "isolated ashmem probe unavailable: " + t);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "isolated probe failed: " + t);
                        } finally {
                            try { if (pipe != null && pipe[0] != null) pipe[0].close(); } catch (Throwable ignored) { }
                            try { if (pipe != null && pipe[1] != null) pipe[1].close(); } catch (Throwable ignored) { }
                        }
                    }
                }, "iso-probe").start();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "isolated probe disconnected");
            }
        };
        try {
            bindService(new Intent(this, IsoProbeService.class), isoConn, BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.e(TAG, "isolated probe bind failed: " + t);
        }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (service != null) {
            try { service.asBinder().unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) { }
        }
        if (bound) {
            try { unbindService(conn); } catch (Throwable ignored) { }
            bound = false;
        }
        if (store != null) store.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------ UI
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        renderer = new GLProtoRenderer();
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (videoDemo) {
            videoView = new StripedVideoView(this);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(160), dp(90));
            vlp.leftMargin = dp(10);
            vlp.topMargin = dp(220);
            videoView.setVisibility(View.INVISIBLE);
            root.addView(videoView, vlp);
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xCC101828);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusText.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusText.setText("初始化…");
        top.addView(statusText);

        benchText = new TextView(this);
        benchText.setTextColor(0xFF9AE6B4);
        benchText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        benchText.setTypeface(android.graphics.Typeface.MONOSPACE);
        benchText.setVisibility(View.GONE);
        top.addView(benchText);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xCC101828);
        bottom.setPadding(dp(4), dp(4), dp(4), dp(6));
        bottom.addView(makeButtonRow(new String[]{"崩溃演练", "滚动+", "滚动-", "后退"},
                new Runnable[]{crashAction, scrollUpAction, scrollDownAction, backAction}));
        bottom.addView(makeButtonRow(new String[]{"前进", "切换模式", "跑基准", "重置"},
                new Runnable[]{forwardAction, modeAction, benchAction, resetAction}));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        botLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, botLp);

        setContentView(root);
    }

    private LinearLayout makeButtonRow(String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setPadding(0, 0, 0, 0);
            final Runnable r = actions[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { r.run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    dp(44), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, lp);
        }
        return row;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ 状态
    private void updateStatus() {
        if (statusText == null) return;
        String h = historyIndex >= 0 && historyIndex < history.length
                ? history[historyIndex] : "-";
        String bench = benchSummary == null ? "-" : benchSummary.replace('\n', ' ');
        String text = String.format(Locale.US,
                "主进程 pid=%d  渲染 pid=%s  代次=%d  %s\n" +
                "模式=%s  seq=%d  cmds=%d  contentH=%.0f  scroll=%.0f\n" +
                "历史 %d/%d  %s\n" +
                "raster=%.1f  upload=%.1f(%s)  draw=%.1f  present=%.1f  ipc=%.1f ms\n" +
                "呈现帧=%d  渲染崩溃=%d 次\n" +
                "bench: %s",
                android.os.Process.myPid(),
                renderPid == 0 ? "-" : Integer.toString(renderPid),
                generation,
                blocked ? "[已停止自动重启]" : (service == null ? "[等待重启]" : "[已连接]"),
                mode.equals("gpu") ? "GPU指令" : "SW软件帧",
                frameSeq, cmdCount, contentH, scrollY,
                historyIndex + 1, history.length, h,
                lastRasterMs, lastUploadMs, lastUploadDirty ? "脏区" : "整帧",
                lastDrawMs, lastPresentMs, lastIpcMs,
                presentedFrames, crashCount,
                bench);
        statusText.setText(text);

        if (benchSummary != null) {
            benchText.setVisibility(View.VISIBLE);
            benchText.setText(benchSummary);
        }
    }

    private void writeBenchIfNeeded() {
        if (benchSummary == null || benchEpoch == 0) return;
        if (benchEpoch <= lastWrittenEpoch) return;
        lastWrittenEpoch = benchEpoch;
        try {
            FileOutputStream out = openFileOutput("bench_result.txt", MODE_PRIVATE);
            out.write(("epoch=" + benchEpoch + "\n" + benchSummary + "\n").getBytes("UTF-8"));
            out.close();
            Log.i(TAG, "bench result written: files/bench_result.txt");
        } catch (Throwable t) {
            Log.e(TAG, "write bench failed: " + t);
        }
    }

    private int lastWrittenEpoch = 0;

    // ------------------------------------------------------------ 绑定 / 重启
    private void bindToRenderer() {
        if (bound) return;
        Intent i = new Intent(this, RenderService.class);
        try {
            bound = bindService(i, conn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService bound=" + bound);
        } catch (Throwable t) {
            Log.e(TAG, "bindService failed: " + t);
        }
    }

    private void onRendererDied(String why) {
        if (deathHandled) {
            Log.i(TAG, "onRendererDied ignored (already handled): " + why);
            return;
        }
        deathHandled = true;
        Log.w(TAG, "onRendererDied: " + why + " main pid=" + android.os.Process.myPid());

        IRenderService s = service;
        service = null;
        renderPid = 0;
        if (s != null) {
            try { s.asBinder().unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) { }
        }
        if (bound) {
            try { unbindService(conn); } catch (Throwable ignored) { }
            bound = false;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - crashWindowStart > 60000L) {
            crashWindowStart = now;
            crashCount = 0;
        }
        crashCount++;
        updateStatus();

        if (crashCount >= 3) {
            blocked = true;
            Log.w(TAG, "render crash loop guarded: stop auto restart");
            updateStatus();
            return;
        }
        generation++;
        saveState();
        long delayMs = crashCount == 1 ? 500L : Math.min(8000L, 500L << Math.min(4, crashCount - 1));
        Log.i(TAG, "restart render in " + delayMs + "ms gen=" + generation
                + " (restore scroll=" + scrollY + " history=" + historyIndex + ")");
        ui.postDelayed(rebindRunnable, delayMs);
    }

    private void resetCrashGuard() {
        crashCount = 0;
        blocked = false;
        crashWindowStart = 0;
        deathHandled = false;
        generation++;
        updateStatus();
        bindToRenderer();
    }

    private void saveState() {
        SharedPreferences.Editor e = getSharedPreferences("tab_state", MODE_PRIVATE).edit();
        e.putString("mode", mode);
        e.putFloat("scrollY", scrollY);
        e.putInt("historyIndex", historyIndex);
        e.putInt("generation", generation);
        e.commit();
    }

    // ------------------------------------------------------------ 与子进程交互
    private void ensureStore() {
        if (surfaceW <= 0 || surfaceH <= 0) return;
        if (store != null && store.width == surfaceW && store.height == surfaceH) return;
        if (store != null) store.close();
        storeFile = new File(getCacheDir(), "ea_frame.bin");
        store = FrameStore.create(storeFile, surfaceW, surfaceH);
        Log.i(TAG, "shared frame created " + storeFile + " " + surfaceW + "x" + surfaceH
                + " size=" + (storeFile.length() / 1024) + "KB");
    }

    /** 子进程连接 / 尺寸变化 / 模式切换 / 历史切换后调用：下发状态并恢复渲染 */
    private void openPageNow() {
        ensureStore();
        IRenderService s = service;
        if (s == null || store == null) return;
        try {
            s.configure(storeFile.getAbsolutePath(), surfaceW, surfaceH, generation);
            String page = videoDemo ? "video" : (mode.equals("gpu") ? "gpu" : "engine");
            s.openPage(page, surfaceW, surfaceH, scrollY, historyIndex, history);
            Log.i(TAG, "openPage sent page=" + page + " scroll=" + scrollY
                    + " historyIndex=" + historyIndex + " gen=" + generation);
        } catch (Throwable t) {
            Log.e(TAG, "openPage failed: " + t);
            ui.post(new Runnable() {
                @Override public void run() { onRendererDied("call failed"); }
            });
        }
    }

    // ------------------------------------------------------------ 按钮动作
    private final Runnable crashAction = new Runnable() {
        @Override public void run() {
            IRenderService s = service;
            Log.w(TAG, "crash action requested");
            if (s == null) {
                updateStatus();
                return;
            }
            try {
                s.crashNow();
            } catch (Throwable t) {
                Log.w(TAG, "crashNow threw: " + t);
            }
        }
    };

    private final Runnable scrollUpAction = new Runnable() {
        @Override public void run() { doScroll(300f); }
    };

    private final Runnable scrollDownAction = new Runnable() {
        @Override public void run() { doScroll(-300f); }
    };

    /** demo：自动滚动，验证视频层跟随内容（到底后回到页首） */
    private final Runnable autoScrollTick = new Runnable() {
        @Override public void run() {
            float viewport = surfaceH > 0 ? surfaceH : 1920f;
            float max = Math.max(0f, contentH - viewport);
            float next = scrollY + 160f;
            if (next > max) next = 0f;
            scrollY = next;
            updateStatus();
            if (glView != null) glView.requestRender();
            if (autoScroll) ui.postDelayed(this, 700);
        }
    };

    private void doScroll(float delta) {
        float viewport = surfaceH > 0 ? surfaceH : 1920f;
        float max = Math.max(0f, contentH - viewport);
        float y = scrollY + delta;
        if (y < 0) y = 0;
        if (y > max) y = max;
        scrollY = y;
        saveState();
        updateStatus();
        if (mode.equals("sw")) {
            IRenderService s = service;
            if (s != null) {
                try { s.setScroll(scrollY); } catch (Throwable t) { Log.e(TAG, "setScroll: " + t); }
            }
        } else if (glView != null) {
            glView.requestRender();
        }
        Log.i(TAG, "scroll -> " + scrollY);
    }

    private final Runnable backAction = new Runnable() {
        @Override public void run() {
            if (historyIndex > 0) {
                historyIndex--;
                saveState();
                openPageNow();
                Log.i(TAG, "history back -> " + history[historyIndex]);
            }
        }
    };

    private final Runnable forwardAction = new Runnable() {
        @Override public void run() {
            if (historyIndex < history.length - 1) {
                historyIndex++;
                saveState();
                openPageNow();
                Log.i(TAG, "history forward -> " + history[historyIndex]);
            }
        }
    };

    private final Runnable modeAction = new Runnable() {
        @Override public void run() {
            mode = mode.equals("gpu") ? "sw" : "gpu";
            saveState();
            Log.i(TAG, "mode -> " + mode);
            openPageNow();
            if (glView != null) glView.requestRender();
            updateStatus();
        }
    };

    private final Runnable benchAction = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "bench requested");
            benchRequested = true;
            if (glView != null) glView.requestRender();
        }
    };

    private final Runnable resetAction = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "reset crash guard");
            resetCrashGuard();
        }
    };

    // ------------------------------------------------------------ 视频层
    /** 把 SurfaceView 摆到 GL 留出的洞上；四边内缩，露出一圈 GL 背景，用来证明洞真的没被合成 */
    private void placeVideo(float x, float y, float w, float h) {
        if (videoView == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        lp.leftMargin = Math.round(x) + dp(8);
        lp.topMargin = Math.round(y) + dp(8);
        lp.width = Math.max(1, Math.round(w) - dp(16));
        lp.height = Math.max(1, Math.round(h) - dp(16));
        videoView.setLayoutParams(lp);
        if (videoView.getVisibility() != View.VISIBLE) videoView.setVisibility(View.VISIBLE);
    }

    /** 视频图层的占位实现：SurfaceView 上画移动条纹，证明 GL 位图没有盖住这一层 */
    private final class StripedVideoView extends SurfaceView
            implements android.view.SurfaceHolder.Callback, Runnable {
        private Thread thread;
        private volatile boolean running;
        private final android.graphics.Paint paint = new android.graphics.Paint();

        StripedVideoView(Context c) {
            super(c);
            setZOrderMediaOverlay(true);
            getHolder().addCallback(this);
        }

        @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
            running = true;
            thread = new Thread(this, "video-stripes");
            thread.start();
        }

        @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format,
                                             int width, int height) { }

        @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) { stopLoop(); }

        private void stopLoop() {
            running = false;
            Thread t = thread;
            thread = null;
            if (t != null) {
                try { t.join(200); } catch (Throwable ignored) { }
            }
        }

        @Override public void run() {
            long t0 = android.os.SystemClock.uptimeMillis();
            int frame = 0;
            while (running) {
                android.view.SurfaceHolder h = getHolder();
                android.graphics.Canvas cv = null;
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
                        cv.drawText("VIDEO " + frame + "  " + el + "ms", 16, 40, paint);
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
        }
    }

    // ------------------------------------------------------------ GL 呈现
    private final class GLProtoRenderer implements GLSurfaceView.Renderer {
        private final GpuCompositor gpu = new GpuCompositor();
        private DisplayList gpuList;
        private String parsedJson;
        private int lastFrameSeq = -1;
        private long fpsStart;
        private int fpsFrames;

        @Override public void onSurfaceCreated(GL10 unused, javax.microedition.khronos.egl.EGLConfig config) {
            gpu.onSurfaceCreated();
            if (videoDemo) gpu.videoHoleEnabled = !videoHoleOff;
            Log.i(TAG, "GL surface created");
        }

        @Override public void onSurfaceChanged(GL10 unused, final int w, final int h) {
            surfaceW = w;
            surfaceH = h;
            gpu.onSurfaceChanged(w, h);
            Log.i(TAG, "GL surface " + w + "x" + h);
            ui.post(new Runnable() {
                @Override public void run() {
                    ensureStore();
                    openPageNow();
                    updateStatus();
                }
            });
        }

        @Override public void onDrawFrame(GL10 unused) {
            int w = surfaceW, h = surfaceH;
            if (benchRequested) {
                benchRequested = false;
                final String result = BenchRunner.run(gpu, w, h, benchFrames);
                final IRenderService svc = service;
                new Thread(new Runnable() {
                    @Override public void run() {
                        String transport = null;
                        if (svc != null) {
                            try { transport = svc.runTransportBench(); } catch (Throwable t) {
                                Log.e(TAG, "transport bench failed: " + t);
                            }
                        }
                        String full = result + (transport == null ? ""
                                : "\n  跨进程 整帧vs脏带: " + transport);
                        benchSummary = full;
                        benchEpoch++;
                        Log.i(TAG, "bench done epoch=" + benchEpoch + " transport=" + transport);
                    }
                }).start();
            }

            if (mode.equals("gpu")) {
                String json = displayListJson;
                if (json != null && (parsedJson == null || !json.equals(parsedJson))) {
                    parsedJson = json;
                    gpuList = DisplayList.parse(json);
                    Log.i(TAG, "GL parsed display list cmds="
                            + (gpuList == null ? -1 : gpuList.cmds.size()));
                }
                if (gpuList != null) {
                    long t0 = System.nanoTime();
                    gpu.renderDisplayList(gpuList, scrollY, false);
                    lastDrawMs = (System.nanoTime() - t0) / 1000000f;
                    long t1 = System.nanoTime();
                    gpu.presentFbo(w, h);
                    lastPresentMs = (System.nanoTime() - t1) / 1000000f;
                }
            } else {
                FrameStore s = store;
                if (s != null) {
                    int seq = s.seq();
                    if (seq != lastFrameSeq) {
                        lastFrameSeq = seq;
                        int slot = s.latestSlot();
                        int dx = s.dirtyX(), dy = s.dirtyY(), dw = s.dirtyW(), dh = s.dirtyH();
                        if (dx == 0 && dy == 0 && dw == s.width && dh == s.height) {
                            lastUploadMs = gpu.uploadFrame(s.view(slot), s.width, s.height);
                            lastUploadDirty = false;
                        } else {
                            lastUploadMs = gpu.uploadFramePartial(
                                    s.bufferForRect(slot, dx, dy, dw, dh), s.width,
                                    dx, dy, dw, dh);
                            lastUploadDirty = true;
                        }
                        s.markConsumed(seq);
                    }
                    if (lastFrameSeq >= 0) {
                        long t0 = System.nanoTime();
                        gpu.presentFrameTex(true, w, h);
                        lastPresentMs = (System.nanoTime() - t0) / 1000000f;
                    }
                }
            }

            if (videoDemo) {
                final int n = gpu.copyVideoHoles(holeBuf, 4);
                if (n > 0) {
                    final float hx = holeBuf[0], hy = holeBuf[1];
                    final float hw = holeBuf[2], hh = holeBuf[3];
                    ui.post(new Runnable() {
                        @Override public void run() { placeVideo(hx, hy, hw, hh); }
                    });
                    long nowMs = android.os.SystemClock.uptimeMillis();
                    if (nowMs - lastHoleLogMs > 500) {
                        lastHoleLogMs = nowMs;
                        Log.i(TAG, "video hole screen x=" + (int) hx + " y=" + (int) hy
                                + " w=" + (int) hw + " h=" + (int) hh
                                + " scroll=" + (int) scrollY);
                    }
                } else {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (videoView != null) videoView.setVisibility(View.INVISIBLE);
                        }
                    });
                }
            }

            presentedFrames++;
            long now = System.nanoTime();
            if (fpsStart == 0) fpsStart = now;
            fpsFrames++;
            if (now - fpsStart >= 1000000000L) {
                fpsStart = now;
                fpsFrames = 0;
            }
        }
    }
}
