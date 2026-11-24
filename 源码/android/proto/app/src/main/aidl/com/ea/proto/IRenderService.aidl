package com.ea.proto;

import com.ea.proto.IRenderCallback;

interface IRenderService {
    void setCallback(IRenderCallback cb);

    /** 主进程创建共享帧文件后通知子进程打开：path / 宽 / 高 / 代次 */
    void configure(String sharedPath, int width, int height, int generation);

    /**
     * 打开页面。
     * page: "gpu" = 合成图形页（GPU 指令模式）；"engine" = assets/displaylist.json（真实引擎输出，软件帧模式）
     */
    void openPage(String page, float vw, float vh, float scrollY, int historyIndex, in String[] history);

    void setScroll(float y);
    void requestFrame();

    /** 演练：让渲染进程自己崩溃（验证主进程存活与重启恢复） */
    void crashNow();

    String getStats();

    /** 整帧 vs 脏带 的渲染+发布耗时对照（JSON） */
    String runTransportBench();
}
