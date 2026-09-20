package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map;

final class PptxBackgroundChecks {
    private static final String FIXTURE = "pptx-backgrounds.pptx";
    private static final int TOP = 0xffff6a28;
    private static final int BOTTOM = 0xfff43f12;
    private static final int PLAIN = 0xff2a9d8f;
    private static final String IMAGE = "ppt/media/transparent-title.png";

    private PptxBackgroundChecks() { }

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = DocumentDecoder.decode(NativeCore.parse(source.file.getAbsolutePath()));
            check(document.kind == OfficeDocument.Kind.PPTX && document.pages.size() == 3,
                "Background sample contains inherited, solid and transformed fills");
            OfficeDocument.Element image = document.pages.get(0).elements.get(1);
            check(image.type == OfficeDocument.Type.IMAGE && IMAGE.equals(image.image),
                "PNG relationship resolves to the sample image");
            Bitmap title = source.image(image.image, OfficeImages.MAX_PIXELS).bitmap;
            check(title != null, "Sample transparent PNG decodes");
            try {
                check(title.getWidth() == 40 && title.getHeight() == 40, "PNG retains sample dimensions");
                color(title, 5, 8, Color.WHITE, "PNG contains opaque white foreground");
                check(Color.alpha(title.getPixel(20, 34)) == 0, "PNG contains transparent background");
                instrumentation.runOnMainChecked(() -> capture(context, document, Collections.singletonMap(IMAGE, title)));
            } finally { title.recycle(); }
        }
        return "PASS PPTX background sample URI/JNI, inherited gradient, slide-space fill, transparent PNG and shader reset pixels\n"
            + "SCREENSHOTS pptx-backgrounds.png\n";
    }

    private static void capture(Context context, OfficeDocument document, Map<String, Bitmap> images) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page first = renderer.pages.get(0);
        OfficeRenderer.Page second = renderer.pages.get(1);
        Bitmap bitmap = Bitmap.createBitmap(Math.round(first.width), Math.round(renderer.height), Bitmap.Config.ARGB_8888);
        try {
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
            nearColor(bitmap.getPixel(160, 104), bitmap.getPixel(110, 104), 2,
                "Transparent PNG area reveals gradient background");
            color(bitmap, 36, Math.round(second.y + 36), PLAIN, "Second slide solid fill has no stale gradient shader");
            color(bitmap, 36, Math.round(second.y + 92), PLAIN, "Second slide stays solid at lower sample");
            OfficeRenderer.Page third = renderer.pages.get(2);
            color(bitmap, 5, Math.round(third.y + 90), PLAIN, "Foreground covers the actual slide background");
            for (int[] point : new int[][] {{35, 70}, {70, 100}, {230, 65}, {230, 105}}) {
                nearColor(bitmap.getPixel(point[0], Math.round(third.y + point[1])), bitmap.getPixel(40, point[1]), 2,
                    "Rotated/flipped shape reveals the background at slide coordinates");
            }
            File file = new File(context.getFilesDir(), "pptx-backgrounds.png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
            }
        } finally { bitmap.recycle(); }
    }

    private static void nearColor(int actual, int expected, int tolerance, String label) {
        check(Color.alpha(actual) == Color.alpha(expected)
            && Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance
            && Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance
            && Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance,
            label + " expected #" + Integer.toHexString(expected) + " actual #" + Integer.toHexString(actual));
    }
}
