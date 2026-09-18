package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class PptxBackgroundChecks {
    private static final int TOP = 0xffff6a28;
    private static final int BOTTOM = 0xfff43f12;
    private static final int PAPER = 0xff111111;
    private static final int PLAIN = 0xff2a9d8f;
    private static final String IMAGE = "ppt/media/transparent-title.png";

    private PptxBackgroundChecks() { }

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainChecked(() -> capture(context));
        return "PASS PPTX inherited gradient background layer, transparent PNG foreground and shader reset pixels\n"
            + "SCREENSHOTS pptx-background-synthetic.png\n";
    }

    private static void capture(Context context) throws Exception {
        OfficeDocument document = document();
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page first = renderer.pages.get(0);
        OfficeRenderer.Page second = renderer.pages.get(1);
        Bitmap bitmap = Bitmap.createBitmap(Math.round(first.width), Math.round(renderer.height), Bitmap.Config.ARGB_8888);
        Bitmap title = transparentTitle();
        try {
            Map<String, Bitmap> images = new HashMap<>();
            images.put(IMAGE, title);
            renderer.draw(new Canvas(bitmap), 0, renderer.height, images);
            int top = bitmap.getPixel(40, 8);
            int middle = bitmap.getPixel(40, 90);
            int bottom = bitmap.getPixel(40, 172);
            check(Color.green(top) > Color.green(middle) && Color.green(middle) > Color.green(bottom),
                "Gradient background descends through green channel");
            check(Color.blue(top) > Color.blue(middle) && Color.blue(middle) > Color.blue(bottom),
                "Gradient background descends through blue channel");
            nearColor(top, TOP, 6, "Gradient top stop reaches slide top");
            nearColor(bottom, BOTTOM, 6, "Gradient bottom stop reaches slide bottom");

            color(bitmap, 145, 78, Color.WHITE, "Opaque white PNG foreground remains white");
            nearColor(bitmap.getPixel(160, 104), bitmap.getPixel(110, 104), 2, "Transparent PNG area reveals gradient background");
            color(bitmap, 36, Math.round(second.y + 36), PLAIN, "Second slide solid fill has no stale gradient shader");
            color(bitmap, 36, Math.round(second.y + 92), PLAIN, "Second slide stays solid at lower sample");

            File file = new File(context.getFilesDir(), "pptx-background-synthetic.png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
            }
        } finally {
            title.recycle();
            bitmap.recycle();
        }
    }

    private static OfficeDocument document() {
        OfficeDocument document = new OfficeDocument();
        document.kind = OfficeDocument.Kind.PPTX;
        document.width = 320;

        OfficeDocument.Page gradientPage = new OfficeDocument.Page();
        gradientPage.width = 320;
        gradientPage.height = 180;
        gradientPage.background = PAPER;
        gradientPage.elements.add(backgroundRect(gradientPage.width, gradientPage.height));
        OfficeDocument.Element image = new OfficeDocument.Element();
        image.type = OfficeDocument.Type.IMAGE;
        image.image = IMAGE;
        image.x = 140;
        image.y = 70;
        image.width = 40;
        image.height = 40;
        gradientPage.elements.add(image);
        document.pages.add(gradientPage);

        OfficeDocument.Page plainPage = new OfficeDocument.Page();
        plainPage.width = 320;
        plainPage.height = 120;
        plainPage.background = PAPER;
        OfficeDocument.Element plain = new OfficeDocument.Element();
        plain.type = OfficeDocument.Type.RECT;
        plain.width = plainPage.width;
        plain.height = plainPage.height;
        plain.fill = PLAIN;
        plainPage.elements.add(plain);
        document.pages.add(plainPage);
        return document;
    }

    private static OfficeDocument.Element backgroundRect(float width, float height) {
        OfficeDocument.Element rect = new OfficeDocument.Element();
        rect.type = OfficeDocument.Type.RECT;
        rect.width = width;
        rect.height = height;
        rect.fillGradient = new OfficeDocument.GradientFill();
        rect.fillGradient.colors = new int[] {TOP, BOTTOM};
        rect.fillGradient.positions = new float[] {0, 1};
        rect.fillGradient.angle = 90;
        rect.fillGradient.scaled = false;
        return rect;
    }

    private static Bitmap transparentTitle() {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        for (int y = 6; y < 14; y++) {
            for (int x = 4; x < 36; x++) bitmap.setPixel(x, y, Color.WHITE);
        }
        return bitmap;
    }

    private static void nearColor(int actual, int expected, int tolerance, String label) {
        check(Color.alpha(actual) == Color.alpha(expected)
            && Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance
            && Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance
            && Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance,
            label + " expected #" + Integer.toHexString(expected) + " actual #" + Integer.toHexString(actual));
    }
}
