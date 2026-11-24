package qrselftest;

import com.ea.browser.tools.DecodeThrottle;
import com.ea.browser.tools.QrDecoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * 不依赖手机的确定性解码自检：
 *  PNG -> 640x480 预览帧（灰度 + 居中缩放）-> NV21 Y 平面 -> 生产解码器 QrDecoder。
 *
 * 运行方式见 tests/verify_qr_selftest.py。
 */
public final class QrDecodeSelfTest {

    private static final String EXPECTED = "EA-SELFTEST-0001";
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length < 1) {
            System.out.println("usage: QrDecodeSelfTest <qr_selftest.png>");
            System.exit(2);
        }
        BufferedImage qr = ImageIO.read(new File(args[0]));
        if (qr == null) {
            System.out.println("[fail] PNG 读取失败: " + args[0]);
            System.out.println("RESULT failures=1");
            System.exit(1);
        }

        // 1. 正常二维码：灰度化 + 居中缩放到预览尺寸
        byte[] normal = buildFrame(qr, FRAME_W, FRAME_H, false);
        String r1 = QrDecoder.decodeYuv(normal, FRAME_W, FRAME_H);
        check("二维码 NV21 解码", EXPECTED.equals(r1), "got=" + r1);

        // 2. 纯噪声：必须 miss，且不能抛异常
        byte[] noise = buildNoise(FRAME_W, FRAME_H, 42L);
        String r2 = null;
        Throwable thrown = null;
        try {
            r2 = QrDecoder.decodeYuv(noise, FRAME_W, FRAME_H);
        } catch (Throwable t) {
            thrown = t;
        }
        check("纯噪声 miss 且不抛异常", r2 == null && thrown == null,
                "got=" + r2 + " err=" + thrown);

        // 3. 反色二维码：覆盖 DecodeHintType.ALSO_INVERTED 分支
        byte[] inverted = buildFrame(qr, FRAME_W, FRAME_H, true);
        String r3 = QrDecoder.decodeYuv(inverted, FRAME_W, FRAME_H);
        check("反色二维码仍可解", EXPECTED.equals(r3), "got=" + r3);

        // 4. 限流：300ms 内连投 5 帧，只允许 1~2 次解码
        DecodeThrottle throttle = new DecodeThrottle(300);
        int decoded = 0;
        long base = 1000L;
        for (int i = 0; i < 5; i++) {
            long now = base + i * 10L; // 0/10/20/30/40ms，全部落在 300ms 窗口内
            if (throttle.tryAcquire(now)) {
                decoded++;
                throttle.release();
            }
        }
        check("300ms 内 5 帧只解 1~2 次", decoded >= 1 && decoded <= 2, "decoded=" + decoded);

        // 4b. 解码未完成（未 release）时，即使时间已过 300ms 也必须拒绝
        DecodeThrottle busyThrottle = new DecodeThrottle(300);
        boolean first = busyThrottle.tryAcquire(2000L);
        boolean second = busyThrottle.tryAcquire(2400L);
        busyThrottle.release();
        check("解码未完成时后续帧全部拒绝", first && !second, "first=" + first + " second=" + second);

        System.out.println("RESULT failures=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static byte[] buildFrame(BufferedImage qr, int w, int h, boolean invert) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int side = (int) (Math.min(w, h) * 0.6);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        g.drawImage(qr, x, y, side, side, null);
        g.dispose();

        byte[] nv21 = new byte[w * h * 3 / 2];
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int rgb = canvas.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r * 77 + gg * 150 + b * 29) >> 8;
                if (invert) gray = 255 - gray;
                nv21[yy * w + xx] = (byte) gray;
            }
        }
        for (int i = w * h; i < nv21.length; i++) nv21[i] = (byte) 128;
        return nv21;
    }

    private static byte[] buildNoise(int w, int h, long seed) {
        Random rnd = new Random(seed);
        byte[] nv21 = new byte[w * h * 3 / 2];
        for (int i = 0; i < nv21.length; i++) nv21[i] = (byte) rnd.nextInt(256);
        return nv21;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("[ok] " + name);
        } else {
            failures++;
            System.out.println("[fail] " + name + " (" + detail + ")");
        }
    }
}
