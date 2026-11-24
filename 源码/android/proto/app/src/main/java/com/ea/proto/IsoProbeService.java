package com.ea.proto;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.FileOutputStream;

/**
 * 运行在 android:isolatedProcess="true" 的进程里。
 * 它没有存储权限、不能访问 app 私有目录，只能靠 Binder 收 fd。
 * 探针验证：匿名管道 fd 能否穿透 isolated 边界。
 */
public class IsoProbeService extends Service {
    private static final String TAG = "EAProto";

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "isolated probe bound pid=" + Process.myPid() + " uid=" + Process.myUid());
        return binder;
    }

    private final IIsoProbe.Stub binder = new IIsoProbe.Stub() {
        @Override public int ping(ParcelFileDescriptor pfd) {
            try {
                FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
                out.write("ISO19:pipe-ok".getBytes("UTF-8"));
                out.flush();
                // 不关闭 pfd：由调用方负责写端生命周期
                Log.i(TAG, "isolated probe wrote 13 bytes pid=" + Process.myPid());
                return 42;
            } catch (Throwable t) {
                Log.e(TAG, "isolated probe write failed: " + t);
                return -1;
            }
        }

        @Override public String readBytes(ParcelFileDescriptor pfd, int len) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
                byte[] buf = new byte[len];
                int got = in.read(buf);
                String s = new String(buf, 0, Math.max(0, got), "UTF-8");
                Log.i(TAG, "isolated probe read " + got + " bytes: " + s);
                return s;
            } catch (Throwable t) {
                Log.e(TAG, "isolated probe read failed: " + t);
                return "ERR:" + t;
            }
        }

        @Override public String info() {
            boolean canReadFrame = new java.io.File(
                    "/data/data/com.ea.proto/cache/ea_frame.bin").canRead();
            return "pid=" + Process.myPid() + " uid=" + Process.myUid()
                    + " canReadAppCache=" + canReadFrame;
        }
    };
}
