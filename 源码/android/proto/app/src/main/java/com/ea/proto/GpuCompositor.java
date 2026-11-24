package com.ea.proto;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenGL ES 2.0 合成器 v3：
 *   - 单 Uber 着色器 + 单一顶点缓冲：形状 / 图片 / 文字 合批，尽量避免 draw call
 *   - 图片纹理图集（1024×1024，16 个 256×256 tile）
 *   - 字形图集多页（1024×1024/页，ASCII + CJK 同一套，按字号/粗体/斜体分桶）；
 *     文字跨页时按页 flush，正常情况下整帧仍只有 1 次 draw call
 *   - 可选 VBO 路径（useVbo=true 时用 glBufferData 上传顶点，替代客户端数组）
 *   - 每帧不再 glFinish；需要同步时由调用方显式 finish()
 *   - 支持脏矩形子区上传（glTexSubImage2D + GL_UNPACK_ROW_LENGTH）
 *   - 每帧输出分阶段耗时（build / atlas / submit），供大指令量瓶颈定位
 */
public class GpuCompositor {
    private static final String TAG = "EAProto";

    private static final int STRIDE = 19; // pos2 local2 uv2 c0(4) c1(4) params4(w,h,radius,kind) angle1
    private static final int MAX_VERTS = 60000;
    private static final int ATLAS_SIZE = 1024;
    private static final int TILE = 256;
    private static final int TILES_PER_ROW = ATLAS_SIZE / TILE;
    /** API19 的 android.jar 没导出该常量（值来自 GLES 规范） */
    private static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;

    // kind
    private static final float KIND_SOLID = 0f;
    private static final float KIND_GRAD = 1f;
    private static final float KIND_IMAGE = 2f;
    private static final float KIND_TEXT = 3f;

    private int program, presentProgram;
    private int fboId, fboTex, frameTex = 0;
    private int atlasTex = 0;
    private int width = 1, height = 1;
    private int frameTexW = 0, frameTexH = 0;

    /** true = 顶点走 VBO（glBufferData）；false = 客户端数组（旧路径） */
    public boolean useVbo = false;
    private int vboId = 0;

    // 每帧分阶段耗时与计数（基准/诊断用）
    public float lastBuildMs, lastAtlasMs, lastSubmitMs;
    public int lastVerts, lastDrawCalls, lastAtlasUploadBytes;
    public int lastGlyphCount, lastGlyphPages, lastFlushCount;
    /** 本帧发生的换页（LRU 淘汰）次数 */
    public int lastAtlasEvictions;
    /** 本帧因图集满而放弃绘制的文本/字形次数（>0 表示画面缺字） */
    public int lastAllocFails;

    /** true = 4 页打满时按 LRU 换页复用；false = 旧行为（返回 null，文字丢失） */
    public boolean lruAtlas = true;
    /** 诊断开关：禁用整行缓存，全部走逐字图集 */
    public boolean forcePerChar = false;
    /** 整行缓存准入上限（防病态页面无限占格）；超限由 LRU 换页承接，不再静默丢字 */
    public int runCacheLimit = 4096;
    /** 图集页数上限（每页 ALPHA_8 1024² = 1MB；4 页≈300 条 400px 长行，8 页≈600 条） */
    public int maxGlyphPages = 8;

    /** true = TYPE_VIDEO 不合成，留洞给 SurfaceView；false = 按普通矩形画（对照实验用） */
    public boolean videoHoleEnabled = true;
    /** 本帧留给视频图层的屏幕矩形 {x,y,w,h}（文档坐标经变换、滚动后，单位 px） */
    private final List<float[]> videoHoles = new ArrayList<float[]>();
    private final Object holeLock = new Object();

    /** 拷贝本帧视频洞矩形到 out（最多 max 个），返回个数 */
    public int copyVideoHoles(float[] out, int max) {
        synchronized (holeLock) {
            int n = Math.min(videoHoles.size(), max / 4);
            for (int i = 0; i < n; i++) {
                float[] r = videoHoles.get(i);
                out[i * 4] = r[0]; out[i * 4 + 1] = r[1];
                out[i * 4 + 2] = r[2]; out[i * 4 + 3] = r[3];
            }
            return n;
        }
    }
    private long frameNo = 0;
    private int evictThisFrame = 0;

    private int aPos, aLocal, aUv, aC0, aC1, aParams, aAngle;
    private int uAtlas, uGlyph, uTexPresent, uFlipPresent;

    private final FloatBuffer vbuf =
            ByteBuffer.allocateDirect(MAX_VERTS * STRIDE * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer presentBuf = ByteBuffer.allocateDirect(6 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** 顶点先写在 Java float[]，flush 时一次性拷进 direct buffer；避免逐分量 put 的调用开销 */
    private final float[] vertArr = new float[MAX_VERTS * STRIDE];
    private int vcnt;
    private int verts;
    /** 本帧累计提交顶点数（flush 会清零 verts，跨页批次时不能只看最后一个批次） */
    private int frameVerts;

    private Bitmap atlasBmp;
    private boolean atlasDirty;
    private final Map<String, int[]> tileIndex = new HashMap<String, int[]>();
    private int nextTile = 0;

    /** 字形图集：多页，每页 1024²，ASCII 与 CJK 共用同一套（按 code point 缓存） */
    private final ArrayList<GlyphPage> glyphPages = new ArrayList<GlyphPage>();
    private final Map<String, Glyph> glyphCache = new HashMap<String, Glyph>();
    private static final int MAX_GLYPH_PAGES = 8;
    /** 当前批次绑定的字形页；跨页时先 flush 再切页 */
    private int batchGlyphPage = 0;

    private static class GlyphPage {
        int tex;
        Bitmap bmp;
        boolean dirty;
        /** 整页被清空过（换页），必须整页重传；否则只传 [dY0,dY1) 这段行 */
        boolean fullDirty;
        int dY0 = Integer.MAX_VALUE, dY1 = 0;
        int penX = 1, penY = 1, rowH = 0;
        /** 该页承载的样式（字号|粗|斜）；按样式分页，减少互相淘汰 */
        String styleKey = "";
        /** true = 该页由整行文本占用，false = 逐字字形页 */
        boolean runPage;
        /** 最近使用帧号（LRU 依据） */
        long lastUse;
    }

    private static class Glyph {
        int page;
        float u0, v0, u1, v1;
        float w, h, advance, top;
        boolean blank;
    }

    /** 整行文本纹理缓存（text run）：一条文本 = 一个 quad，避免逐字 6 顶点 */
    private final Map<String, Run> runCache = new HashMap<String, Run>();

    private static class Run {
        int page;
        float u0, v0, u1, v1;
        float w, h, advance, top;
    }

    // ------------------------------------------------------------------ shaders
    private static final String VS =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aLocal;\n" +
            "attribute vec2 aUv;\n" +
            "attribute vec4 aColor0;\n" +
            "attribute vec4 aColor1;\n" +
            "attribute vec4 aParams;\n" +
            "attribute float aAngle;\n" +
            "varying vec2 vLocal; varying vec2 vUv;\n" +
            "varying vec4 vC0; varying vec4 vC1; varying vec4 vParams;\n" +
            "varying float vAngle;\n" +
            "void main(){\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vLocal = aLocal; vUv = aUv;\n" +
            "  vC0 = aColor0; vC1 = aColor1; vParams = aParams; vAngle = aAngle;\n" +
            "}\n";

    private static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform sampler2D uAtlas;\n" +
            "uniform sampler2D uGlyph;\n" +
            "varying vec2 vLocal; varying vec2 vUv;\n" +
            "varying vec4 vC0; varying vec4 vC1; varying vec4 vParams;\n" +
            "varying float vAngle;\n" +
            "void main(){\n" +
            "  float kind = vParams.w;\n" +
            "  vec4 col;\n" +
            "  if (kind > 1.5) {\n" +
            "    if (kind < 2.5) {\n" +
            "      col = texture2D(uAtlas, vUv);\n" +
            "      col.a *= vC0.a;\n" +
            "    } else {\n" +
            "      float a = texture2D(uGlyph, vUv).a;\n" +
            "      col = vec4(vC0.rgb, vC0.a * a);\n" +
            "    }\n" +
            "  } else {\n" +
            "    if (kind > 0.5) {\n" +
            "      vec2 dir = vec2(cos(vAngle), sin(vAngle));\n" +
            "      vec2 size = vParams.xy;\n" +
            "      float len = size.x*abs(dir.x) + size.y*abs(dir.y);\n" +
            "      float t = dot(vLocal - size*0.5, dir) / max(len, 1.0) + 0.5;\n" +
            "      t = clamp(t, 0.0, 1.0);\n" +
            "      col = vec4(mix(vC0.rgb, vC1.rgb, t), mix(vC0.a, vC1.a, t));\n" +
            "    } else {\n" +
            "      col = vC0;\n" +
            "    }\n" +
            "    if (vParams.z > 0.5) {\n" +
            "      vec2 halfSize = vParams.xy*0.5;\n" +
            "      vec2 p = abs(vLocal - halfSize) - halfSize + vec2(vParams.z);\n" +
            "      float d = length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - vParams.z;\n" +
            "      col.a *= clamp(0.5 - d, 0.0, 1.0);\n" +
            "    }\n" +
            "  }\n" +
            "  gl_FragColor = col;\n" +
            "}\n";

    private static final String PRESENT_VS =
            "attribute vec2 aPos;\nattribute vec2 aUv;\nvarying vec2 vUv;\n" +
            "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }\n";

    private static final String PRESENT_FS =
            "precision mediump float;\nuniform sampler2D uTex;\nuniform float uFlip;\n" +
            "varying vec2 vUv;\n" +
            "void main(){ vec2 uv = vUv; if (uFlip > 0.5) uv.y = 1.0 - uv.y; gl_FragColor = texture2D(uTex, uv); }\n";

    private static int compile(String vs, String fs) {
        int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(v, vs);
        GLES20.glCompileShader(v);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(v, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "VS compile failed: " + GLES20.glGetShaderInfoLog(v));
            GLES20.glDeleteShader(v);
            return 0;
        }
        int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(f, fs);
        GLES20.glCompileShader(f);
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "FS compile failed: " + GLES20.glGetShaderInfoLog(f));
            GLES20.glDeleteShader(f);
            GLES20.glDeleteShader(v);
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            p = 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    public void onSurfaceCreated() {
        program = compile(VS, FS);
        presentProgram = compile(PRESENT_VS, PRESENT_FS);
        if (program != 0) {
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aLocal = GLES20.glGetAttribLocation(program, "aLocal");
            aUv = GLES20.glGetAttribLocation(program, "aUv");
            aC0 = GLES20.glGetAttribLocation(program, "aColor0");
            aC1 = GLES20.glGetAttribLocation(program, "aColor1");
            aParams = GLES20.glGetAttribLocation(program, "aParams");
            aAngle = GLES20.glGetAttribLocation(program, "aAngle");
            uAtlas = GLES20.glGetUniformLocation(program, "uAtlas");
            uGlyph = GLES20.glGetUniformLocation(program, "uGlyph");
        }
        uTexPresent = GLES20.glGetUniformLocation(presentProgram, "uTex");
        uFlipPresent = GLES20.glGetUniformLocation(presentProgram, "uFlip");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    public void onSurfaceChanged(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        createFbo();
    }

    private void createFbo() {
        if (fboId != 0) GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
        if (fboTex != 0) GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        fboTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fboId = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTex, 0);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete: 0x" + Integer.toHexString(st));
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    // --------------------------------------------------------------- frame texture
    /** 上传整帧；返回 CPU 提交耗时（不含 GPU 完成，异步） */
    public float uploadFrame(java.nio.Buffer pixels, int w, int h) {
        long t0 = System.nanoTime();
        ensureFrameTex(w, h);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return (System.nanoTime() - t0) / 1000000f;
    }

    /** 只上传脏区；pixels 已定位到该子区起点，rowLength 为整行像素数 */
    public float uploadFramePartial(java.nio.Buffer pixels, int rowLength,
                                    int x, int y, int dw, int dh) {
        long t0 = System.nanoTime();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex);
        GLES20.glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, dw, dh,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        return (System.nanoTime() - t0) / 1000000f;
    }

    private void ensureFrameTex(int w, int h) {
        if (frameTex != 0 && frameTexW == w && frameTexH == h) return;
        if (frameTex != 0) GLES20.glDeleteTextures(1, new int[]{frameTex}, 0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        frameTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        frameTexW = w;
        frameTexH = h;
    }

    public void presentFrameTex(boolean flip, int surfaceW, int surfaceH) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(1f, 1f, 1f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(presentProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTex);
        GLES20.glUniform1i(uTexPresent, 0);
        GLES20.glUniform1f(uFlipPresent, flip ? 1f : 0f);
        fillPresentQuad();
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        presentBuf.position(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, presentBuf);
        presentBuf.position(2);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, presentBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(0);
        GLES20.glDisableVertexAttribArray(1);
        GLES20.glEnable(GLES20.GL_BLEND);
    }

    private void fillPresentQuad() {
        presentBuf.clear();
        float[] pos = {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1};
        for (int i = 0; i < 6; i++) {
            presentBuf.put(pos[i * 2]).put(pos[i * 2 + 1]).put(uv[i * 2]).put(uv[i * 2 + 1]);
        }
        presentBuf.position(0);
    }

    private float nx(float x) { return x / width * 2f - 1f; }
    private float ny(float y) { return 1f - y / height * 2f; }

    // --------------------------------------------------------------- display list
    /**
     * 渲染显示列表到 FBO。
     * @param sync true 时调用 glFinish（基准对照）；运行时应传 false
     * @return CPU 提交耗时；sync=true 时包含 GPU 完成时间
     */
    public float renderDisplayList(DisplayList dl, float scrollY, boolean sync) {
        long t0 = System.nanoTime();
        ensureAtlas();
        ensureGlyphPagesFirst();

        frameNo++;
        evictThisFrame = 0;
        // 每帧重置统计
        lastAtlasMs = 0f;
        lastSubmitMs = 0f;
        lastAtlasUploadBytes = 0;
        lastGlyphCount = 0;
        lastDrawCalls = 0;
        lastFlushCount = 0;
        lastAtlasEvictions = 0;
        lastAllocFails = 0;
        frameVerts = 0;
        batchGlyphPage = 0;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(1f, 1f, 1f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);

        vbuf.clear();
        verts = 0;
        vcnt = 0;

        long tb0 = System.nanoTime();
        synchronized (holeLock) { videoHoles.clear(); }
        for (DisplayList.Cmd c : dl.cmds) {
            if (c.type == DisplayList.TYPE_TEXT) {
                addText(c, scrollY);
            } else if (c.type == DisplayList.TYPE_IMAGE) {
                addImage(c, scrollY);
            } else if (c.type == DisplayList.TYPE_VIDEO) {
                addVideo(c, scrollY);
            } else {
                addShape(c, scrollY);
            }
        }
        lastBuildMs = (System.nanoTime() - tb0) / 1000000f;
        flush();
        lastVerts = frameVerts;
        lastGlyphPages = glyphPages.size();

        if (sync) GLES20.glFinish();
        return (System.nanoTime() - t0) / 1000000f;
    }

    /** 显式同步（基准/取证用，运行时不要调用） */
    public void finish() {
        GLES20.glFinish();
    }

    public void presentFbo(int surfaceW, int surfaceH) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(1f, 1f, 1f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(presentProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(uTexPresent, 0);
        GLES20.glUniform1f(uFlipPresent, 0f);
        fillPresentQuad();
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        presentBuf.position(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, presentBuf);
        presentBuf.position(2);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, presentBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(0);
        GLES20.glDisableVertexAttribArray(1);
        GLES20.glEnable(GLES20.GL_BLEND);
    }

    private void flush() {
        if (verts == 0) return;
        long ta0 = System.nanoTime();
        uploadAtlasesIfDirty();
        lastAtlasMs += (System.nanoTime() - ta0) / 1000000f;

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
        GLES20.glUniform1i(uAtlas, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        int glyphTex = glyphPages.isEmpty() ? 0 : glyphPages.get(batchGlyphPage).tex;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glyphTex);
        GLES20.glUniform1i(uGlyph, 1);

        long ts0 = System.nanoTime();
        vbuf.clear();
        vbuf.put(vertArr, 0, vcnt);
        int stride = STRIDE * 4;
        if (useVbo) {
            if (vboId == 0) {
                int[] b = new int[1];
                GLES20.glGenBuffers(1, b, 0);
                vboId = b[0];
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
            vbuf.position(0);
            vbuf.limit(vcnt);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vcnt * 4,
                    vbuf, GLES20.GL_STREAM_DRAW);
            bindAttribs(0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            vbuf.position(0);
            bindAttribs(null);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
        }
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aLocal);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisableVertexAttribArray(aC0);
        GLES20.glDisableVertexAttribArray(aC1);
        GLES20.glDisableVertexAttribArray(aParams);
        GLES20.glDisableVertexAttribArray(aAngle);
        lastSubmitMs += (System.nanoTime() - ts0) / 1000000f;
        lastDrawCalls++;
        lastFlushCount++;

        frameVerts += verts;
        vbuf.clear();
        verts = 0;
        vcnt = 0;
    }
    /**
     * 绑定 7 个顶点属性。
     * @param vboBase null = 客户端数组（偏移按 position 计算）；非 null 时基于 VBO 偏移 0
     */
    private void bindAttribs(Integer vboBase) {
        int stride = STRIDE * 4;
        GLES20.glEnableVertexAttribArray(aPos);
        if (vboBase == null) {
            vbuf.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(2);
        } else {
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0);
        }
        GLES20.glEnableVertexAttribArray(aLocal);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(4);
        } else {
            GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, stride, 2 * 4);
        }
        GLES20.glEnableVertexAttribArray(aUv);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(6);
        } else {
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, 4 * 4);
        }
        GLES20.glEnableVertexAttribArray(aC0);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aC0, 4, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(10);
        } else {
            GLES20.glVertexAttribPointer(aC0, 4, GLES20.GL_FLOAT, false, stride, 6 * 4);
        }
        GLES20.glEnableVertexAttribArray(aC1);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aC1, 4, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(14);
        } else {
            GLES20.glVertexAttribPointer(aC1, 4, GLES20.GL_FLOAT, false, stride, 10 * 4);
        }
        GLES20.glEnableVertexAttribArray(aParams);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aParams, 4, GLES20.GL_FLOAT, false, stride, vbuf);
            vbuf.position(18);
        } else {
            GLES20.glVertexAttribPointer(aParams, 4, GLES20.GL_FLOAT, false, stride, 14 * 4);
        }
        GLES20.glEnableVertexAttribArray(aAngle);
        if (vboBase == null) {
            GLES20.glVertexAttribPointer(aAngle, 1, GLES20.GL_FLOAT, false, stride, vbuf);
        } else {
            GLES20.glVertexAttribPointer(aAngle, 1, GLES20.GL_FLOAT, false, stride, 18 * 4);
        }
        if (vboBase == null) vbuf.position(0);
    }

    private boolean hasSpace(int n) {
        if (verts + n > MAX_VERTS) {
            flush();
            return verts + n <= MAX_VERTS;
        }
        return true;
    }

    // --------------------------------------------------------------- shapes
    private void addShape(DisplayList.Cmd c, float scrollY) {
        if (c.type == DisplayList.TYPE_CONIC) return;
        if (c.gradientType == 2 || c.gradientType == 3) return;
        if (!hasSpace(6)) return;
        if (c.type == DisplayList.TYPE_BORDER) {
            float sw = c.textSize > 0 ? c.textSize : 2f;
            addQuad(c, KIND_SOLID, c.x, c.y, c.w, sw, c.color, c.color, 0f, 0f, scrollY);
            addQuad(c, KIND_SOLID, c.x, c.y + c.h - sw, c.w, sw, c.color, c.color, 0f, 0f, scrollY);
            addQuad(c, KIND_SOLID, c.x, c.y, sw, c.h, c.color, c.color, 0f, 0f, scrollY);
            addQuad(c, KIND_SOLID, c.x + c.w - sw, c.y, sw, c.h, c.color, c.color, 0f, 0f, scrollY);
            return;
        }
        addQuad(c, c.gradientType == 1 ? KIND_GRAD : KIND_SOLID,
                c.x, c.y, c.w, c.h, c.color, c.gradientType == 1 ? c.c1 : c.color,
                0f, 0f, scrollY);
    }

    private static final float[] QX = {0, 1, 1, 0, 1, 0};
    private static final float[] QY = {0, 0, 1, 0, 1, 1};

    /** 一个矩形/渐变/圆角 quad；图片与文字有各自入口 */
    private void addQuad(DisplayList.Cmd c, float kind, float rx, float ry, float rw, float rh,
                         int col0, int col1, float u0, float v0, float scrollY) {
        if (!c.hasTransform && (ry + rh < scrollY - 2 || ry > scrollY + height + 2)) return;
        if (verts + 6 > MAX_VERTS) return;

        float rad = (float) Math.toRadians(c.rot);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float a0 = ((col0 >>> 24) & 0xFF) / 255f * c.opacity;
        float a1 = ((col1 >>> 24) & 0xFF) / 255f * c.opacity;
        float r = ((col0 >>> 16) & 0xFF) / 255f, g = ((col0 >>> 8) & 0xFF) / 255f, b = (col0 & 0xFF) / 255f;
        float r1 = ((col1 >>> 16) & 0xFF) / 255f, g1 = ((col1 >>> 8) & 0xFF) / 255f, b1 = (col1 & 0xFF) / 255f;
        float ang = (float) Math.toRadians(c.gradientAngle);
        float tanSkx = (float) Math.tan(Math.toRadians(c.skx));
        float tanSky = (float) Math.tan(Math.toRadians(c.sky));

        for (int i = 0; i < 6; i++) {
            float lx0 = QX[i] * rw, ly0 = QY[i] * rh;
            float px = rx + lx0, py = ry + ly0;
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
            py -= scrollY;
            putV(nx(px), ny(py), lx0, ly0, u0, v0,
                    r, g, b, a0, r1, g1, b1, a1,
                    rw, rh, c.radius, kind, ang);
        }
        verts += 6;
    }

    /** 写一个顶点（19 float）到 Java 侧暂存数组；c0=c1 时传相同颜色 */
    private void putV(float x, float y, float lx, float ly, float u, float v,
                      float r, float g, float b, float a,
                      float r1, float g1, float b1, float a1,
                      float pw, float ph, float rad, float kind, float angle) {
        int o = vcnt;
        float[] A = vertArr;
        A[o] = x; A[o + 1] = y; A[o + 2] = lx; A[o + 3] = ly;
        A[o + 4] = u; A[o + 5] = v;
        A[o + 6] = r; A[o + 7] = g; A[o + 8] = b; A[o + 9] = a;
        A[o + 10] = r1; A[o + 11] = g1; A[o + 12] = b1; A[o + 13] = a1;
        A[o + 14] = pw; A[o + 15] = ph; A[o + 16] = rad; A[o + 17] = kind;
        A[o + 18] = angle;
        vcnt = o + STRIDE;
    }

    // --------------------------------------------------------------- video hole
    /**
     * EA_DRAW_VIDEO：默认不合成，只把矩形（经变换 + 滚动 + 视口裁剪）记到 videoHoles，
     * 由主进程把 SurfaceView 摆到该屏幕矩形上；videoHoleEnabled=false 时按普通实心矩形画，用于对照。
     */
    private void addVideo(DisplayList.Cmd c, float scrollY) {
        if (c.w <= 0 || c.h <= 0) return;
        if (!videoHoleEnabled) {
            addQuad(c, KIND_SOLID, c.x, c.y, c.w, c.h, c.color, c.color, 0f, 0f, scrollY);
            return;
        }
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
            py -= scrollY;
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }
        float x0 = Math.max(0f, minX), y0 = Math.max(0f, minY);
        float x1 = Math.min((float) width, maxX), y1 = Math.min((float) height, maxY);
        if (x1 <= x0 || y1 <= y0) return;
        synchronized (holeLock) {
            videoHoles.add(new float[]{x0, y0, x1 - x0, y1 - y0});
        }
    }

    // --------------------------------------------------------------- images
    private void addImage(DisplayList.Cmd c, float scrollY) {
        if (!c.hasTransform && (c.y + c.h < scrollY - 2 || c.y > scrollY + height + 2)) return;
        float[] uv = imageUv(c.src != null ? c.src : "img");
        float alpha = ((c.color >>> 24) & 0xFF) / 255f * c.opacity;

        if (!hasSpace(6)) return;
        float rad = (float) Math.toRadians(c.rot);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float tanSkx = (float) Math.tan(Math.toRadians(c.skx));
        float tanSky = (float) Math.tan(Math.toRadians(c.sky));
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        for (int i = 0; i < 6; i++) {
            float lx0 = QX[i] * c.w, ly0 = QY[i] * c.h;
            float px = c.x + lx0, py = c.y + ly0;
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
            py -= scrollY;
            float uu = QX[i] == 0 ? u0 : u1;
            float vv = QY[i] == 0 ? v0 : v1;
            putV(nx(px), ny(py), lx0, ly0, uu, vv,
                    1f, 1f, 1f, alpha, 1f, 1f, 1f, alpha,
                    c.w, c.h, 0f, KIND_IMAGE, 0f);
        }
        verts += 6;
    }

    private void ensureAtlas() {
        if (atlasTex != 0) return;
        atlasBmp = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        atlasTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ATLAS_SIZE, ATLAS_SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        atlasDirty = true;
    }

    private float[] imageUv(String src) {
        int[] idx = tileIndex.get(src);
        if (idx == null) {
            if (nextTile >= TILES_PER_ROW * TILES_PER_ROW) {
                idx = new int[]{TILES_PER_ROW * TILES_PER_ROW - 1};
                tileIndex.put(src, idx);
            } else {
                idx = new int[]{nextTile++};
                tileIndex.put(src, idx);
                int tx = (idx[0] % TILES_PER_ROW) * TILE;
                int ty = (idx[0] / TILES_PER_ROW) * TILE;
                Canvas cv = new Canvas(atlasBmp);
                Bitmap img = SwRaster.image(src);
                cv.drawBitmap(img, null,
                        new android.graphics.Rect(tx, ty, tx + TILE, ty + TILE), null);
                atlasDirty = true;
            }
        }
        int tx = (idx[0] % TILES_PER_ROW) * TILE;
        int ty = (idx[0] / TILES_PER_ROW) * TILE;
        return new float[]{tx / (float) ATLAS_SIZE, ty / (float) ATLAS_SIZE,
                (tx + TILE) / (float) ATLAS_SIZE, (ty + TILE) / (float) ATLAS_SIZE};
    }

    // --------------------------------------------------------------- glyph atlas
    private void ensureGlyphPagesFirst() {
        if (!glyphPages.isEmpty()) return;
        createGlyphPage();
    }

    private GlyphPage createGlyphPage() {
        GlyphPage p = new GlyphPage();
        // 文字只用到 alpha（见片元着色器 texture2D(uGlyph, vUv).a），ALPHA_8 比 ARGB 省 4 倍显存/上传
        p.bmp = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ALPHA_8);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        p.tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, ATLAS_SIZE, ATLAS_SIZE, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        glyphPages.add(p);
        return p;
    }

    /** 样式键：字号|粗|斜（按样式分页，避免不同字号互相淘汰） */
    private static String styleKey(int size, boolean bold, boolean italic) {
        return size + "|" + (bold ? 1 : 0) + (italic ? 1 : 0);
    }

    private void touch(int pageIdx) {
        if (pageIdx >= 0 && pageIdx < glyphPages.size()) glyphPages.get(pageIdx).lastUse = frameNo;
    }

    /** 当前整行缓存条目数（诊断用） */
    public int runCacheCount() { return runCache.size(); }

    /** 清空图集（换页/基准用例之间隔离用），必须在 GL 线程调用 */
    public void resetGlyphAtlas() {
        for (GlyphPage p : glyphPages) {
            if (p.tex != 0) {
                int[] t = {p.tex};
                GLES20.glDeleteTextures(1, t, 0);
                p.tex = 0;
            }
            if (p.bmp != null) {
                p.bmp.recycle();
                p.bmp = null;
            }
        }
        glyphPages.clear();
        glyphCache.clear();
        runCache.clear();
        paintCache.clear();
        batchGlyphPage = 0;
        frameNo = 0;
    }

    /** code point 级缓存键：字号|粗|斜|字符 */
    private Glyph glyph(int cp, int size, boolean bold, boolean italic) {
        String chStr = new String(Character.toChars(cp));
        String style = styleKey(size, bold, italic);
        String key = style + "|" + chStr;
        Glyph g = glyphCache.get(key);
        if (g != null) {
            touch(g.page);
            return g;
        }

        Paint p = paintFor(size, bold, italic);
        Paint.FontMetrics fm = p.getFontMetrics();

        g = new Glyph();
        g.advance = p.measureText(chStr);
        g.top = fm.ascent;
        if (cp == ' ' || Character.isWhitespace(cp)) {
            g.blank = true;
            g.w = 0;
            g.h = 0;
            glyphCache.put(key, g);
            return g;
        }

        int cw = Math.max(1, (int) Math.ceil(g.advance));
        int chH = Math.max(1, (int) Math.ceil(-fm.ascent + fm.descent));

        int[] t = allocTile(cw + 2, chH + 2, style, false);
        if (t == null) return null;
        GlyphPage page = glyphPages.get(t[0]);
        int gx = t[1], gy = t[2];

        Canvas cv = new Canvas(page.bmp);
        cv.drawText(chStr, gx + 1, gy + 1 - fm.ascent, p);
        page.dirty = true;
        markDirty(page, gy + 1, chH);

        g.page = t[0];
        g.u0 = (gx + 1) / (float) ATLAS_SIZE;
        g.v0 = (gy + 1) / (float) ATLAS_SIZE;
        g.u1 = (gx + 1 + cw) / (float) ATLAS_SIZE;
        g.v1 = (gy + 1 + chH) / (float) ATLAS_SIZE;
        g.w = cw;
        g.h = chH;
        glyphCache.put(key, g);
        return g;
    }

    /** 该页的 shelf 是否还放得下 cw×ch */
    private static boolean fits(GlyphPage p, int cw, int ch) {
        int px = p.penX, py = p.penY;
        if (px + cw > ATLAS_SIZE) { px = 1; py += p.rowH + 1; }
        return py + ch <= ATLAS_SIZE;
    }

    /**
     * 分配 cwPad×chPad 的 shelf 槽位。
     * 顺序：同样式同类型页 → 同样式页 → 新页（≤4）→ LRU 换页（lruAtlas=true）/
     * 返回 null（lruAtlas=false 或强制禁用时，文字回落逐字或丢失）。
     */
    private int[] allocTile(int cwPad, int chPad, String style, boolean forRun) {
        // pass 0 优先当前合批页：不同字号/字重混在同一页，避免每个 quad 切换页导致
        // 每帧上百次 flush（实测曾因此把 2ms 的文本页拖到 9ms）
        if (batchGlyphPage >= 0 && batchGlyphPage < glyphPages.size()) {
            GlyphPage p = glyphPages.get(batchGlyphPage);
            if (fits(p, cwPad, chPad)) return allocIn(batchGlyphPage, p, cwPad, chPad);
        }
        for (int pass = 1; pass <= 2; pass++) {
            for (int i = 0; i < glyphPages.size(); i++) {
                GlyphPage p = glyphPages.get(i);
                if (pass == 1) {
                    if (!p.styleKey.equals(style)) continue;
                    if (p.runPage != forRun) continue;
                }
                if (fits(p, cwPad, chPad)) return allocIn(i, p, cwPad, chPad);
            }
        }
        if (glyphPages.size() < Math.min(maxGlyphPages, MAX_GLYPH_PAGES)) {
            GlyphPage p = createGlyphPage();
            p.styleKey = style;
            p.runPage = forRun;
            return allocIn(glyphPages.size() - 1, p, cwPad, chPad);
        }
        if (!lruAtlas) {
            // 旧行为：不换页，直接放弃（画面缺字）；用 lastAllocFails 统计
            lastAllocFails++;
            return null;
        }
        int victim = pickVictim(style);
        GlyphPage p = glyphPages.get(victim);
        if (verts > 0) flush();          // 先把仍指向旧内容的顶点画掉，再清页
        p.bmp.eraseColor(0);
        p.penX = 1; p.penY = 1; p.rowH = 0;
        p.dirty = true;
        p.fullDirty = true;              // 整页清零，必须整页重传
        p.dY0 = Integer.MAX_VALUE; p.dY1 = 0;
        p.styleKey = style;
        p.runPage = forRun;
        invalidatePage(victim);
        lastAtlasEvictions++;
        evictThisFrame++;
        return allocIn(victim, p, cwPad, chPad);
    }

    /** 优先淘汰不同样式的页（保住当前字号的像素）；全同样式时退回全页 LRU */
    private int pickVictim(String style) {
        int victim = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < glyphPages.size(); i++) {
            GlyphPage p = glyphPages.get(i);
            if (p.styleKey.equals(style)) continue;
            if (p.lastUse < best) { best = p.lastUse; victim = i; }
        }
        if (victim >= 0) return victim;
        best = Long.MAX_VALUE;
        for (int i = 0; i < glyphPages.size(); i++) {
            GlyphPage p = glyphPages.get(i);
            if (p.lastUse < best) { best = p.lastUse; victim = i; }
        }
        return victim;
    }

    private int[] allocIn(int idx, GlyphPage page, int cwPad, int chPad) {
        if (page.penX + cwPad > ATLAS_SIZE) {
            page.penX = 1;
            page.penY += page.rowH + 1;
            page.rowH = 0;
        }
        if (page.rowH < chPad) page.rowH = chPad;
        int gx = page.penX, gy = page.penY;
        page.penX += cwPad;
        page.lastUse = frameNo;
        return new int[]{idx, gx, gy};
    }

    /** 每种样式复用同一个 Paint，避免每行/每字都 new Paint + setTypeface（换页重光栅时是大头） */
    private final Map<String, Paint> paintCache = new HashMap<String, Paint>();
    private Paint paintFor(int size, boolean bold, boolean italic) {
        String k = styleKey(size, bold, italic);
        Paint p = paintCache.get(k);
        if (p == null) {
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(size);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextSkewX(italic ? -0.25f : 0f);
            paintCache.put(k, p);
        }
        return p;
    }

    /** 标记新写入的行段，上传时只传这段（避免每次 1MB 整页） */
    private static void markDirty(GlyphPage p, int gy, int ch) {
        if (gy < p.dY0) p.dY0 = gy;
        if (gy + ch > p.dY1) p.dY1 = gy + ch;
    }

    /** 清页后删掉缓存里指向该页的条目，避免后续画到旧 UV（旧像素已被覆盖） */
    private void invalidatePage(int idx) {
        java.util.Iterator<Glyph> gi = glyphCache.values().iterator();
        while (gi.hasNext()) if (gi.next().page == idx) gi.remove();
        java.util.Iterator<Run> ri = runCache.values().iterator();
        while (ri.hasNext()) if (ri.next().page == idx) ri.remove();
    }

    /**
     * 整行文本缓存：把整条字符串光栅成一格图集纹理，绘制时只发 1 个 quad。
     * 行宽超过图集时返回 null（回落逐字图集路径）。
     */
    private Run textRun(String s, int size, boolean bold, boolean italic) {
        String style = styleKey(size, bold, italic);
        String key = style + "|" + s;
        Run r = runCache.get(key);
        if (r != null) {
            touch(r.page);
            return r;
        }
        // 准入上限：长文页面里唯一行会无限占图集格，超过上限后回落逐字（字形是共享的，稳态更省）
        if (runCache.size() >= runCacheLimit) return null;

        Paint p = paintFor(size, bold, italic);
        Paint.FontMetrics fm = p.getFontMetrics();
        float adv = p.measureText(s);
        if (adv <= 0 || adv > ATLAS_SIZE - 8) return null;

        int cw = (int) Math.ceil(adv);
        int chH = Math.max(1, (int) Math.ceil(-fm.ascent + fm.descent));
        int[] t = allocTile(cw + 2, chH + 2, style, true);
        if (t == null) return null;
        GlyphPage page = glyphPages.get(t[0]);
        Canvas cv = new Canvas(page.bmp);
        cv.drawText(s, t[1] + 1, t[2] + 1 - fm.ascent, p);
        page.dirty = true;
        markDirty(page, t[2] + 1, chH);

        r = new Run();
        r.page = t[0];
        r.u0 = (t[1] + 1) / (float) ATLAS_SIZE;
        r.v0 = (t[2] + 1) / (float) ATLAS_SIZE;
        r.u1 = (t[1] + 1 + cw) / (float) ATLAS_SIZE;
        r.v1 = (t[2] + 1 + chH) / (float) ATLAS_SIZE;
        r.w = cw;
        r.h = chH;
        r.advance = adv;
        r.top = fm.ascent;
        runCache.put(key, r);
        return r;
    }

    /** 整行 quad；对齐按测量宽度计算 */
    private void addTextRun(DisplayList.Cmd c, Run r, float baseline) {
        if (r.page != batchGlyphPage) {
            flush();
            batchGlyphPage = r.page;
        }
        if (!hasSpace(6)) return;
        float penX = c.x;
        if (c.align == 1) penX = c.x + c.w / 2f - r.advance / 2f;
        else if (c.align == 2) penX = c.x + c.w - r.advance;

        float x0 = penX, x1 = penX + r.w;
        float y0 = baseline + r.top, y1 = y0 + r.h;
        float alpha = ((c.color >>> 24) & 0xFF) / 255f * c.opacity;
        float rr = ((c.color >>> 16) & 0xFF) / 255f;
        float gg = ((c.color >>> 8) & 0xFF) / 255f;
        float bb = (c.color & 0xFF) / 255f;
        for (int k = 0; k < 6; k++) {
            float px = (k == 0 || k == 3 || k == 5) ? x0 : x1;
            float py = (k == 0 || k == 1 || k == 3) ? y0 : y1;
            float uu = (k == 0 || k == 3 || k == 5) ? r.u0 : r.u1;
            float vv = (k == 0 || k == 1 || k == 3) ? r.v0 : r.v1;
            putV(nx(px), ny(py), 0f, 0f, uu, vv,
                    rr, gg, bb, alpha, rr, gg, bb, alpha,
                    r.w, r.h, 0f, KIND_TEXT, 0f);
        }
        verts += 6;
        lastGlyphCount++;
    }

    private void addText(DisplayList.Cmd c, float scrollY) {
        String s = c.text;
        if (s == null || s.isEmpty()) return;
        int size = Math.round(c.textSize > 0 ? c.textSize : 24f);
        if (size <= 0) return;
        float baseline = c.y + size - scrollY;
        if (baseline < -size || baseline > height + size) return;

        // 优先整行纹理：1 条文本 = 1 个 quad；图集放不下/缓存到上限时回落逐字
        Run run = forcePerChar ? null : textRun(s, size, c.bold, c.italic);
        if (run != null) {
            addTextRun(c, run, baseline);
            return;
        }

        float total = 0;
        ArrayList<Glyph> gs = new ArrayList<Glyph>(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 32 || cp > 0xFFFF) { // 控制字符与 BMP 外字符（emoji）跳过
                gs.add(null);
                continue;
            }
            Glyph g = glyph(cp, size, c.bold, c.italic);
            gs.add(g);
            if (g != null) total += g.advance;
        }
        if (total <= 0) return;

        float penX = c.x;
        if (c.align == 1) penX = c.x + c.w / 2f - total / 2f;
        else if (c.align == 2) penX = c.x + c.w - total;

        float alpha = ((c.color >>> 24) & 0xFF) / 255f * c.opacity;
        float r = ((c.color >>> 16) & 0xFF) / 255f;
        float g = ((c.color >>> 8) & 0xFF) / 255f;
        float b = (c.color & 0xFF) / 255f;

        for (Glyph gl : gs) {
            if (gl == null) continue;
            if (gl.blank) { penX += gl.advance; continue; }
            if (gl.page != batchGlyphPage) {
                flush();
                batchGlyphPage = gl.page;
            }
            if (!hasSpace(6)) return;
            float x0 = penX, x1 = penX + gl.w;
            float y0 = baseline + gl.top, y1 = y0 + gl.h;
            penX += gl.advance;

            for (int k = 0; k < 6; k++) {
                float px = (k == 0 || k == 3 || k == 5) ? x0 : x1;
                float py = (k == 0 || k == 1 || k == 3) ? y0 : y1;
                float uu = (k == 0 || k == 3 || k == 5) ? gl.u0 : gl.u1;
                float vv = (k == 0 || k == 1 || k == 3) ? gl.v0 : gl.v1;
                putV(nx(px), ny(py), 0f, 0f, uu, vv,
                        r, g, b, alpha, r, g, b, alpha,
                        gl.w, gl.h, 0f, KIND_TEXT, 0f);
            }
            verts += 6;
            lastGlyphCount++;
        }
    }

    private ByteBuffer atlasScratch;
    /** 字形页是 ALPHA_8，1 字节/像素 */
    private ByteBuffer glyphScratch;

    private void uploadAtlasesIfDirty() {
        if (atlasScratch == null) {
            atlasScratch = ByteBuffer.allocateDirect(ATLAS_SIZE * ATLAS_SIZE * 4)
                    .order(ByteOrder.nativeOrder());
        }
        if (atlasDirty && atlasBmp != null) {
            atlasBmp.copyPixelsToBuffer(atlasScratch);
            atlasScratch.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, ATLAS_SIZE, ATLAS_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, atlasScratch);
            atlasDirty = false;
            lastAtlasUploadBytes += ATLAS_SIZE * ATLAS_SIZE * 4;
        }
        for (int i = 0; i < glyphPages.size(); i++) {
            GlyphPage p = glyphPages.get(i);
            if (!p.dirty) continue;
            int y0, y1;
            if (p.fullDirty) {
                y0 = 0; y1 = ATLAS_SIZE;           // 换页清空过，必须整页重传
            } else {
                y0 = Math.max(0, p.dY0 - 1);
                y1 = Math.min(ATLAS_SIZE, p.dY1 + 1);
                if (y1 <= y0) { p.dirty = false; continue; }
            }
            if (glyphScratch == null) {
                glyphScratch = ByteBuffer.allocateDirect(ATLAS_SIZE * ATLAS_SIZE)
                        .order(ByteOrder.nativeOrder());
            }
            glyphScratch.position(0);   // copyPixelsToBuffer 从 position 写起，必须归零否则剩余空间不足
            p.bmp.copyPixelsToBuffer(glyphScratch);
            glyphScratch.position(y0 * ATLAS_SIZE);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, y0, ATLAS_SIZE, y1 - y0,
                    GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, glyphScratch);
            p.dirty = false;
            p.fullDirty = false;
            p.dY0 = Integer.MAX_VALUE;
            p.dY1 = 0;
            lastAtlasUploadBytes += ATLAS_SIZE * (y1 - y0);
        }
    }
}
