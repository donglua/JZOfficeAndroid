package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.near;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

final class PptxColorChecks {
    private static final String FIXTURE = "pptx-colors.pptx";
    private static final int GOLD = 0xfffff3dd;

    private PptxColorChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        StringBuilder results = new StringBuilder();
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = source.document;
            check(document.kind == OfficeDocument.Kind.PPTX && document.pages.size() == 2,
                "Color fixture contains gradient and solid reference slides");
            for (OfficeDocument.Page page : document.pages) {
                check(page.background == Color.RED, "Color fixture has a red background");
                check(page.elements.size() == 1, "Color fixture has one title per slide");
                OfficeDocument.Element title = page.elements.get(0);
                check(title.type == OfficeDocument.Type.TEXT, "Color fixture title is text");
                near(title.x, 100, 0.01f, "Title x"); near(title.y, 100, 0.01f, "Title y");
                near(title.width, 520, 0.01f, "Title width"); near(title.height, 140, 0.01f, "Title height");
                check(title.paragraphs.size() == 1 && title.paragraphs.get(0).runs.size() == 1,
                    "Color fixture title has one run");
                OfficeDocument.Run run = title.paragraphs.get(0).runs.get(0);
                check("Gold title".equals(run.text) && run.color == GOLD,
                    "URI/JNI title color is FFF3DD, got " + Integer.toHexString(run.color));
                near(run.size, 64, 0.01f, "Title font size");
            }
            instrumentation.runOnMainChecked(() -> {
                OfficeRenderer renderer = new OfficeRenderer();
                renderer.layout(document);
                for (int page = 0; page < 2; page++) {
                    results.append(capture(context, renderer, page, 1080, 1600));
                    results.append(capture(context, renderer, page, 1800, 1000));
                }
            });
        }
        instrumentation.load(FIXTURE, "PPTX");
        instrumentation.runOnMainChecked(() -> { activity.preview.resetZoom(); activity.preview.jumpToPage(1); });
        instrumentation.captureScreen("screen-pptx-color-page1");
        instrumentation.runOnMainChecked(() -> activity.preview.jumpToPage(2));
        instrumentation.captureScreen("screen-pptx-color-page2");
        return results + "PASS gradient and solid title URI/JNI colors, gold Canvas pixels and no black title pixels\n"
            + "SCREENSHOTS pptx-color-page{1,2}-{1080x1600,1800x1000}.png, screen-pptx-color-page{1,2}.png\n";
    }

    private static String capture(Context context, OfficeRenderer renderer, int index, int width, int height) throws Exception {
        OfficeRenderer.Page page = renderer.pages.get(index);
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        String name = "pptx-color-page" + (index + 1) + "-" + width + "x" + height;
        try {
            Canvas canvas = new Canvas(frame);
            canvas.drawColor(Color.LTGRAY);
            float scale = Math.min(width / page.width, height / page.height);
            canvas.scale(scale, scale);
            canvas.translate(0, -page.y);
            canvas.clipRect(0, page.y, page.width, page.y + page.height);
            renderer.draw(canvas, page.y, page.y + page.height, Collections.emptyMap());
            int gold = 0, black = 0;
            for (int y = (int) Math.floor(100 * scale); y < Math.ceil(240 * scale); y++) {
                for (int x = (int) Math.floor(100 * scale); x < Math.ceil(620 * scale); x++) {
                    int pixel = frame.getPixel(x, y);
                    if (Math.abs(Color.red(pixel) - Color.red(GOLD)) <= 8
                        && Math.abs(Color.green(pixel) - Color.green(GOLD)) <= 8
                        && Math.abs(Color.blue(pixel) - Color.blue(GOLD)) <= 8) gold++;
                    if (Color.red(pixel) < 64 && Color.green(pixel) < 64 && Color.blue(pixel) < 64) black++;
                }
            }
            try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name + ".png"))) {
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + name);
            }
            check(gold > 100, name + " contains near-gold title pixels: " + gold);
            check(black == 0, name + " has no black title pixels: " + black);
            return "PASS " + name + " gold=" + gold + " black=" + black + "\n";
        } finally {
            frame.recycle();
        }
    }
}
