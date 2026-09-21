package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

final class PptxChartChecks {
    private static final String FIXTURE = "pptx-charts.pptx";
    private static final int RED = 0xffc03050, BLUE = 0xff2070c0;

    private PptxChartChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = source.document;
            check(document.pages.size() == 2, "Chart fixture contains category and date slides");
            instrumentation.runOnMainChecked(() -> {
                for (int page = 0; page < 2; page++) {
                    capture(context, document, page, 1080, 1600);
                    capture(context, document, page, 1800, 1000);
                }
            });
        }
        instrumentation.load(FIXTURE, "PPTX");
        instrumentation.captureScreen("screen-pptx-chart-page1");
        instrumentation.runOnMainChecked(() -> activity.preview.jumpToPage(2));
        instrumentation.captureScreen("screen-pptx-chart-page2");
        return "PASS cached chart URI/JNI, colored curve pixels, labels and frame bounds\n"
            + "SCREENSHOTS pptx-chart-page{1,2}-{1080x1600,1800x1000}.png, screen-pptx-chart-page{1,2}.png\n";
    }

    private static void capture(Context context, OfficeDocument document, int index, int width, int height) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page page = renderer.pages.get(index);
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(frame);
            canvas.drawColor(Color.LTGRAY);
            float scale = Math.min(width / page.width, height / page.height);
            canvas.scale(scale, scale);
            canvas.translate(0, -page.y);
            canvas.clipRect(0, page.y, page.width, page.y + page.height);
            renderer.draw(canvas, page.y, page.y + page.height, Collections.emptyMap());
            int red = 0, blue = 0;
            for (OfficeDocument.Element element : document.pages.get(index).elements) {
                if (element.type != OfficeDocument.Type.LINE || element.height <= 0
                    || (element.stroke != RED && element.stroke != BLUE)) continue;
                if (element.stroke == RED) red++; else blue++;
                int x = Math.round((element.x + element.width / 2) * scale);
                int y = Math.round((element.y + element.height / 2) * scale);
                check(hasColor(frame, x, y, element.stroke), "Chart segment paints its cached-data midpoint");
            }
            check(red == 3 && blue == (index == 0 ? 3 : 0), "All cached data segments reach Canvas");
            int titleInk = 0;
            for (int y = Math.round(96 * scale); y < Math.round(116 * scale); y++) {
                for (int x = Math.round(48 * scale); x < Math.round(672 * scale); x++) {
                    int color = frame.getPixel(x, y);
                    if (Color.red(color) < 130 && Color.green(color) < 130 && Color.blue(color) < 130) titleInk++;
                }
            }
            check(titleInk > 20, "Chart title is visible");
            for (int y = 0; y < Math.floor(page.height * scale); y++) {
                for (int x = 0; x < Math.floor(page.width * scale); x++) {
                    int color = frame.getPixel(x, y);
                    if (color == RED || color == BLUE) {
                        check(x >= 46 * scale && x <= 674 * scale && y >= 94 * scale && y <= 374 * scale,
                            "Chart series stay inside their graphic frame");
                    }
                }
            }
            File file = new File(context.getFilesDir(), "pptx-chart-page" + (index + 1) + "-" + width + "x" + height + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Chart screenshot saved");
            }
        } finally {
            frame.recycle();
        }
    }

    private static boolean hasColor(Bitmap image, int x, int y, int expected) {
        for (int row = Math.max(0, y - 2); row <= Math.min(image.getHeight() - 1, y + 2); row++) {
            for (int column = Math.max(0, x - 2); column <= Math.min(image.getWidth() - 1, x + 2); column++) {
                if (image.getPixel(column, row) == expected) return true;
            }
        }
        return false;
    }
}
