package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.os.Build;

final class ScreenshotColorChecks {
    private static final int PAPER = 0xfff4f7fa;

    private ScreenshotColorChecks() { }

    static int countPaper(Bitmap bitmap, int expected) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 16) for (int x = 0; x < bitmap.getWidth(); x += 16) {
            int pixel = bitmap.getPixel(x, y);
            // getPixel returns sRGB; an 8-bit Display P3 round trip can shift a channel by one.
            if (Color.alpha(pixel) == Color.alpha(expected)
                && Math.abs(Color.red(pixel) - Color.red(expected)) <= 1
                && Math.abs(Color.green(pixel) - Color.green(expected)) <= 1
                && Math.abs(Color.blue(pixel) - Color.blue(expected)) <= 1) count++;
        }
        return count;
    }

    static String run() {
        Bitmap srgb = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        try {
            srgb.eraseColor(PAPER);
            check(countPaper(srgb, PAPER) == 144, "sRGB screenshot retains paper samples");
            for (int quantized : new int[] {0xfff3f7fa, 0xfff5f7fa}) {
                srgb.eraseColor(quantized);
                check(countPaper(srgb, PAPER) == 144, "One-step quantization accepted: #" + Integer.toHexString(quantized));
            }
            for (int wrong : new int[] {0xffe9ecef, 0xff112233, Color.WHITE, Color.TRANSPARENT,
                0xfef4f7fa, 0xfff2f7fa, 0xfff4f5fa, 0xfff4f7f8}) {
                srgb.eraseColor(wrong);
                check(countPaper(srgb, PAPER) == 0, "Wrong background rejected: #" + Integer.toHexString(wrong));
            }
        } finally { srgb.recycle(); }
        if (Build.VERSION.SDK_INT < 26) return "PASS sRGB screenshot colors; Display P3 requires API 26\n";
        Bitmap p3 = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888, false,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        try {
            new Canvas(p3).drawColor(PAPER);
            int actual = p3.getPixel(0, 0);
            check(countPaper(p3, PAPER) == 144, "Display P3 screenshot retains paper samples: expected #"
                + Integer.toHexString(PAPER) + " actual #" + Integer.toHexString(actual));
        } finally { p3.recycle(); }
        return "PASS sRGB/Display P3 screenshot paper samples and wrong-background rejection\n";
    }
}
