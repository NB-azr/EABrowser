package com.ea.proto;

import android.os.ParcelFileDescriptor;

/** isolatedProcess 探针：验证 Binder + 匿名 fd 通道是否可用 */
interface IIsoProbe {
    /** 向传入的 fd 写入固定字节串，返回 42 表示成功 */
    int ping(in ParcelFileDescriptor pfd);

    /** 从 fd 顺序读取 len 字节（用于验证 ashmem fd 的共享可见性） */
    String readBytes(in ParcelFileDescriptor pfd, int len);

    String info();
}
