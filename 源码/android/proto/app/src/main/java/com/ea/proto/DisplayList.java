package com.ea.proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 与引擎 ea_draw_cmd 对齐的精简显示列表模型。
 * 字段名沿用 drawdump 生成的 JSON（t/x/y/w/h/color/r/gt/...）。
 */
public class DisplayList {
    public static final int TYPE_RECT = 0;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_BORDER = 2;
    public static final int TYPE_LINE = 3;
    public static final int TYPE_IMAGE = 4;
    public static final int TYPE_CONIC = 5;
    /** EA_DRAW_VIDEO：不参与 GL 合成，留洞给 SurfaceView 图层（见 GpuCompositor.videoHoles） */
    public static final int TYPE_VIDEO = 6;

    public static class Cmd {
        public int type;
        public float x, y, w, h;
        public int color = 0xFF000000;
        public float radius;
        public float opacity = 1f;
        public float textSize;
        public boolean bold, italic;
        public int align;
        public int gradientType;   // 0 none 1 linear 2 radial 3 conic
        public int gradientAngle;
        public int c0 = 0, c1 = 0;
        public float[] conicPos;
        public int[] conicColor;
        public boolean hasTransform;
        public float tx, ty, sx = 1f, sy = 1f, rot, skx, sky, ox, oy;
        public String text;
        public String src;
        public String bg;
        /** 每项: [dx,dy,blur,spread,color,inset] */
        public List<float[]> shadows;

        public boolean isRounded() { return radius > 0.01f; }
    }

    public float viewportW = 1080f;
    public float viewportH = 1920f;
    public float contentH = 1920f;
    public float layoutMs;
    public final List<Cmd> cmds = new ArrayList<Cmd>();

    public static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        try {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() == 6) t = "FF" + t;
            return (int) Long.parseLong(t, 16);
        } catch (Throwable e) {
            return fallback;
        }
    }

    public static DisplayList parse(String json) {
        DisplayList dl = new DisplayList();
        try {
            JSONObject root = new JSONObject(json);
            dl.viewportW = (float) root.optDouble("viewportW", 1080);
            dl.viewportH = (float) root.optDouble("viewportH", 1920);
            dl.contentH = (float) root.optDouble("contentH", 1920);
            dl.layoutMs = (float) root.optDouble("layoutMs", 0);
            JSONArray arr = root.optJSONArray("cmds");
            if (arr == null) return dl;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Cmd c = new Cmd();
                c.type = o.optInt("t", 0);
                c.x = (float) o.optDouble("x", 0);
                c.y = (float) o.optDouble("y", 0);
                c.w = (float) o.optDouble("w", 0);
                c.h = (float) o.optDouble("h", 0);
                c.color = parseColor(o.optString("color", null), 0xFF000000);
                c.radius = (float) o.optDouble("r", 0);
                c.opacity = (float) o.optDouble("op", 1);
                c.textSize = (float) o.optDouble("ts", 0);
                c.bold = o.optInt("b", 0) != 0;
                c.italic = o.optInt("i", 0) != 0;
                c.align = o.optInt("al", 0);
                c.gradientType = o.optInt("gt", 0);
                c.gradientAngle = o.optInt("ga", 180);
                c.c0 = parseColor(o.optString("c0", null), 0);
                c.c1 = parseColor(o.optString("c1", null), 0);
                JSONArray cs = o.optJSONArray("cs");
                if (cs != null && cs.length() > 0) {
                    c.conicPos = new float[cs.length()];
                    c.conicColor = new int[cs.length()];
                    for (int k = 0; k < cs.length(); k++) {
                        JSONArray e = cs.getJSONArray(k);
                        c.conicPos[k] = (float) e.getDouble(0);
                        c.conicColor[k] = parseColor(e.getString(1), 0xFF000000);
                    }
                }
                JSONObject tr = o.optJSONObject("tr");
                if (tr != null) {
                    c.hasTransform = true;
                    c.tx = (float) tr.optDouble("tx", 0);
                    c.ty = (float) tr.optDouble("ty", 0);
                    c.sx = (float) tr.optDouble("sx", 1);
                    c.sy = (float) tr.optDouble("sy", 1);
                    c.rot = (float) tr.optDouble("rot", 0);
                    c.skx = (float) tr.optDouble("skx", 0);
                    c.sky = (float) tr.optDouble("sky", 0);
                    c.ox = (float) tr.optDouble("ox", 0);
                    c.oy = (float) tr.optDouble("oy", 0);
                }
                JSONArray sh = o.optJSONArray("sh");
                if (sh != null && sh.length() > 0) {
                    c.shadows = new ArrayList<float[]>();
                    for (int k = 0; k < sh.length(); k++) {
                        JSONArray e = sh.getJSONArray(k);
                        float[] layer = new float[6];
                        layer[0] = (float) e.getDouble(0);
                        layer[1] = (float) e.getDouble(1);
                        layer[2] = (float) e.getDouble(2);
                        layer[3] = (float) e.getDouble(3);
                        layer[4] = (float) parseColor(e.getString(4), 0x55000000);
                        layer[5] = (float) e.getInt(5);
                        c.shadows.add(layer);
                    }
                }
                c.text = o.optString("text", null);
                c.src = o.optString("src", null);
                c.bg = o.optString("bg", null);
                dl.cmds.add(c);
            }
        } catch (Throwable t) {
            android.util.Log.e("EAProto", "DisplayList.parse failed: " + t);
        }
        return dl;
    }

    public static String colorHex(int c) {
        return String.format("#%08X", c);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') b.append("\\\"");
            else if (ch == '\\') b.append("\\\\");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }

    /** 序列化为跨进程传输的精简 JSON（Binder oneway 有 1MB 上限） */
    public String toJson() {
        StringBuilder o = new StringBuilder(1024);
        o.append("{\"version\":1,\"viewportW\":").append(viewportW)
         .append(",\"viewportH\":").append(viewportH)
         .append(",\"contentH\":").append(contentH)
         .append(",\"cmds\":[");
        for (int i = 0; i < cmds.size(); i++) {
            Cmd c = cmds.get(i);
            if (i > 0) o.append(',');
            o.append("{\"t\":").append(c.type)
             .append(",\"x\":").append(c.x).append(",\"y\":").append(c.y)
             .append(",\"w\":").append(c.w).append(",\"h\":").append(c.h)
             .append(",\"color\":\"").append(colorHex(c.color)).append('"');
            if (c.radius > 0) o.append(",\"r\":").append(c.radius);
            if (c.opacity != 1f) o.append(",\"op\":").append(c.opacity);
            if (c.textSize > 0) o.append(",\"ts\":").append(c.textSize);
            if (c.bold) o.append(",\"b\":1");
            if (c.italic) o.append(",\"i\":1");
            if (c.align != 0) o.append(",\"al\":").append(c.align);
            if (c.gradientType != 0) {
                o.append(",\"gt\":").append(c.gradientType)
                 .append(",\"ga\":").append(c.gradientAngle)
                 .append(",\"c0\":\"").append(colorHex(c.c0)).append('"')
                 .append(",\"c1\":\"").append(colorHex(c.c1)).append('"');
                if (c.conicPos != null && c.conicPos.length > 0) {
                    o.append(",\"cs\":[");
                    for (int k = 0; k < c.conicPos.length; k++) {
                        if (k > 0) o.append(',');
                        o.append('[').append(c.conicPos[k]).append(",\"")
                         .append(colorHex(c.conicColor[k])).append("\"]");
                    }
                    o.append(']');
                }
            }
            if (c.hasTransform) {
                o.append(",\"tr\":{\"tx\":").append(c.tx).append(",\"ty\":").append(c.ty)
                 .append(",\"sx\":").append(c.sx).append(",\"sy\":").append(c.sy)
                 .append(",\"rot\":").append(c.rot)
                 .append(",\"skx\":").append(c.skx).append(",\"sky\":").append(c.sky)
                 .append(",\"ox\":").append(c.ox).append(",\"oy\":").append(c.oy).append('}');
            }
            if (c.shadows != null && !c.shadows.isEmpty()) {
                o.append(",\"sh\":[");
                for (int k = 0; k < c.shadows.size(); k++) {
                    float[] l = c.shadows.get(k);
                    if (k > 0) o.append(',');
                    o.append('[').append(l[0]).append(',').append(l[1]).append(',')
                     .append(l[2]).append(',').append(l[3]).append(",\"")
                     .append(colorHex((int) l[4])).append("\",")
                     .append((int) l[5]).append(']');
                }
                o.append(']');
            }
            if (c.text != null) o.append(",\"text\":\"").append(esc(c.text)).append('"');
            if (c.src != null) o.append(",\"src\":\"").append(esc(c.src)).append('"');
            if (c.bg != null && c.bg.length() > 0) o.append(",\"bg\":\"").append(esc(c.bg)).append('"');
            o.append('}');
        }
        o.append("]}");
        return o.toString();
    }
}
