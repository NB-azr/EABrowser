package com.ea.proto;

oneway interface IRenderCallback {
    /** 显示列表快照（GPU 指令模式 / 软件帧模式都发送，便于主进程校验） */
    void onDisplayList(String json, int cmdCount, float contentH, int renderPid, int generation);

    /** 新像素帧已写入共享内存（软件帧模式） */
    void onFrame(int seq, int generation, float scrollY, float contentH, float rasterMs, int cmdCount, long publishTsNanos);

    void onError(int code, String msg);
}
