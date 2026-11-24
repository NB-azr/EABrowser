package com.ea.proto;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 跨进程共享帧缓冲（API 19 可用路径）：
 * 应用私有目录普通文件 + FileChannel.map，同 UID 的两个进程映射同一份页缓存。
 * 双缓冲 + 头字段，主进程用 direct buffer 直接 glTexSubImage2D。
 */
public class FrameStore {
    private static final String TAG = "EAProto";

    public static final int MAGIC = 0x45414652; // 'EAFR'
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 4096;

    /**
     * 头字段（字节偏移）
     * 0 magic, 4 version, 8 width, 12 height, 16 rowBytes,
     * 20 latestSlot, 24 seq, 28 generation,
     * 32 scrollY(bits), 36 contentH(bits), 40 rasterMs(bits), 44 cmdCount,
     * 48 publishTsNanos(long), 56 consumedSeq
     */
    private static final int O_MAGIC = 0;
    private static final int O_VERSION = 4;
    private static final int O_WIDTH = 8;
    private static final int O_HEIGHT = 12;
    private static final int O_ROW_BYTES = 16;
    private static final int O_LATEST_SLOT = 20;
    private static final int O_SEQ = 24;
    private static final int O_GENERATION = 28;
    private static final int O_SCROLL_Y = 32;
    private static final int O_CONTENT_H = 36;
    private static final int O_RASTER_MS = 40;
    private static final int O_CMD_COUNT = 44;
    private static final int O_PUBLISH_TS = 48;
    private static final int O_CONSUMED_SEQ = 56;
    // 脏矩形（v1 扩展，仍在 4096 字节头内）
    private static final int O_DIRTY_X = 64;
    private static final int O_DIRTY_Y = 68;
    private static final int O_DIRTY_W = 72;
    private static final int O_DIRTY_H = 76;

    public final int width;
    public final int height;
    public final int rowBytes;
    private final int frameBytes;
    private final RandomAccessFile raf;
    private final MappedByteBuffer map;

    public static FrameStore create(File file, int width, int height) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            int rowBytes = width * 4;
            int frameBytes = rowBytes * height;
            raf.setLength(HEADER_SIZE + 2L * frameBytes);
            FileChannel ch = raf.getChannel();
            MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_SIZE + 2L * frameBytes);
            map.putInt(O_MAGIC, MAGIC);
            map.putInt(O_VERSION, VERSION);
            map.putInt(O_WIDTH, width);
            map.putInt(O_HEIGHT, height);
            map.putInt(O_ROW_BYTES, rowBytes);
            map.putInt(O_LATEST_SLOT, 0);
            map.putInt(O_SEQ, 0);
            map.putInt(O_CONSUMED_SEQ, 0);
            return new FrameStore(raf, map, width, height, rowBytes, frameBytes);
        } catch (Throwable t) {
            Log.e(TAG, "FrameStore.create failed: " + t);
            return null;
        }
    }

    public static FrameStore open(File file) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            FileChannel ch = raf.getChannel();
            long size = ch.size();
            if (size < HEADER_SIZE) return null;
            MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_WRITE, 0, size);
            if (map.getInt(O_MAGIC) != MAGIC || map.getInt(O_VERSION) != VERSION) {
                Log.e(TAG, "FrameStore.open: bad magic/version");
                return null;
            }
            int w = map.getInt(O_WIDTH);
            int h = map.getInt(O_HEIGHT);
            int rowBytes = map.getInt(O_ROW_BYTES);
            int frameBytes = rowBytes * h;
            if (size < HEADER_SIZE + 2L * frameBytes) return null;
            return new FrameStore(raf, map, w, h, rowBytes, frameBytes);
        } catch (Throwable t) {
            Log.e(TAG, "FrameStore.open failed: " + t);
            return null;
        }
    }

    private FrameStore(RandomAccessFile raf, MappedByteBuffer map,
                       int width, int height, int rowBytes, int frameBytes) {
        this.raf = raf;
        this.map = map;
        this.width = width;
        this.height = height;
        this.rowBytes = rowBytes;
        this.frameBytes = frameBytes;
    }

    public void close() {
        try { raf.close(); } catch (Throwable ignored) { }
    }

    /** 子进程：整帧发布 */
    public void publish(Bitmap bitmap, int seq, int generation, float scrollY,
                        float contentH, float rasterMs, int cmdCount, long publishTs) {
        publish(bitmap, seq, generation, scrollY, contentH, rasterMs, cmdCount, publishTs,
                0, 0, width, height);
    }

    /**
     * 子进程：发布整帧或脏区。
     * 脏区发布前调用方必须先 copySlot(上一槽, 本槽)，否则槽内其余区域是旧数据。
     */
    public void publish(Bitmap bitmap, int seq, int generation, float scrollY,
                        float contentH, float rasterMs, int cmdCount, long publishTs,
                        int dx, int dy, int dw, int dh) {
        if (dw <= 0 || dh <= 0) { dx = 0; dy = 0; dw = width; dh = height; }
        boolean full = (dx == 0 && dy == 0 && dw == width && dh == height);
        // 整帧走双缓冲交替；脏区沿用上一槽（其余区域已在槽内，避免 7.3MB 槽复制）
        int slot = full ? (seq & 1) : map.getInt(O_LATEST_SLOT);
        ByteBuffer dst = view(slot);
        if (dx == 0 && dy == 0 && dw == width && dh == height) {
            bitmap.copyPixelsToBuffer(dst);
        } else if (dx == 0 && dw == width) {
            // 全宽脏带：槽内区域连续，用原生裁剪+批量拷贝，避免 Dalvik 逐字节写
            Bitmap band = Bitmap.createBitmap(bitmap, 0, dy, width, dh);
            int off = dy * width * 4;
            ByteBuffer bandDst = view(slot);
            bandDst.position(off);
            bandDst.limit(off + dh * width * 4);
            band.copyPixelsToBuffer(bandDst);
            band.recycle();
        } else {
            int[] px = new int[dw * dh];
            bitmap.getPixels(px, 0, dw, dx, dy, dw, dh);
            for (int row = 0; row < dh; row++) {
                dst.position(((dy + row) * width + dx) * 4);
                int base = row * dw;
                for (int col = 0; col < dw; col++) {
                    int p = px[base + col];
                    dst.put((byte) (p & 0xFF))
                            .put((byte) ((p >>> 8) & 0xFF))
                            .put((byte) ((p >>> 16) & 0xFF))
                            .put((byte) ((p >>> 24) & 0xFF));
                }
            }
        }
        map.putInt(O_DIRTY_X, dx);
        map.putInt(O_DIRTY_Y, dy);
        map.putInt(O_DIRTY_W, dw);
        map.putInt(O_DIRTY_H, dh);
        map.putInt(O_LATEST_SLOT, slot);
        map.putInt(O_GENERATION, generation);
        map.putInt(O_SCROLL_Y, Float.floatToIntBits(scrollY));
        map.putInt(O_CONTENT_H, Float.floatToIntBits(contentH));
        map.putInt(O_RASTER_MS, Float.floatToIntBits(rasterMs));
        map.putInt(O_CMD_COUNT, cmdCount);
        map.putLong(O_PUBLISH_TS, publishTs);
        // seq 最后写：读端看到新 seq 即代表数据和字段已就绪
        map.putInt(O_SEQ, seq);
    }

    /** 脏区发布前把上一槽整帧复制到本槽（7.3MB memcpy） */
    public void copySlot(int from, int to) {
        ByteBuffer src = view(from);
        ByteBuffer dst = view(to);
        dst.put(src);
    }

    /** 主进程：定位到脏区起点，配合 GL_UNPACK_ROW_LENGTH 子区上传 */
    public ByteBuffer bufferForRect(int slot, int x, int y, int dw, int dh) {
        ByteBuffer v = view(slot);
        int off = (y * width + x) * 4;
        v.position(off);
        v.limit(off + dh * rowBytes);
        return v.slice();
    }

    public ByteBuffer view(int slot) {
        int off = HEADER_SIZE + slot * frameBytes;
        ByteBuffer v = map.duplicate();
        v.position(off);
        v.limit(off + frameBytes);
        return v.slice();
    }

    public int seq() { return map.getInt(O_SEQ); }
    public int latestSlot() { return map.getInt(O_LATEST_SLOT); }
    public int generation() { return map.getInt(O_GENERATION); }
    public float scrollY() { return Float.intBitsToFloat(map.getInt(O_SCROLL_Y)); }
    public float contentH() { return Float.intBitsToFloat(map.getInt(O_CONTENT_H)); }
    public float rasterMs() { return Float.intBitsToFloat(map.getInt(O_RASTER_MS)); }
    public int cmdCount() { return map.getInt(O_CMD_COUNT); }
    public long publishTs() { return map.getLong(O_PUBLISH_TS); }
    public int consumedSeq() { return map.getInt(O_CONSUMED_SEQ); }
    public int dirtyX() { return map.getInt(O_DIRTY_X); }
    public int dirtyY() { return map.getInt(O_DIRTY_Y); }
    public int dirtyW() { return map.getInt(O_DIRTY_W); }
    public int dirtyH() { return map.getInt(O_DIRTY_H); }
    public void markConsumed(int seq) { map.putInt(O_CONSUMED_SEQ, seq); }
}
