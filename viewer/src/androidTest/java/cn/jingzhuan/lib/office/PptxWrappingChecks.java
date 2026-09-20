package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

final class PptxWrappingChecks {
    private static final String FIXTURE = "pptx-wrapping.pptx";

    static void run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = DocumentDecoder.decode(NativeCore.parse(source.file.getAbsolutePath()));
            check(document.kind == OfficeDocument.Kind.PPTX && document.pages.size() == 9,
                "Wrapping sample contains three break modes for each alignment");
            instrumentation.runOnMainChecked(() -> capture(context, document));
        }
    }

    private static void capture(Context context, OfficeDocument document) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        for (int index = 0; index < document.pages.size(); index++) {
            int alignment = index / 3;
            int mode = index % 3;
            OfficeRenderer.Page page = renderer.pages.get(index);
            OfficeRenderer.Element drawn = page.elements.get(0);
            OfficeDocument.Element text = drawn.source;
            check(text.type == OfficeDocument.Type.TEXT && text.paragraphs.get(0).runs.size() == 2,
                "Parsed wrapping sample retains mixed runs");
            check(text.paragraphs.get(0).alignment == alignment, "Parsed paragraph alignment " + alignment);
            check(text.textWrap == (mode == 2), "Parsed wrapping mode " + mode);
            OfficeTextLayout.Block block = drawn.texts.get(0);
            StaticLayout layout = block.layout;
            float left = block.x + layout.getLineLeft(0), right = block.x + layout.getLineRight(0);
            if (mode == 0) {
                check(layout.getLineCount() == 1, "Unwrapped text retains one line across runs");
                check(layout.getHeight() <= text.height, "PART 05 stays above its following title");
                float anchor = alignment == 0 ? left : alignment == 1 ? (left + right) / 2 : right;
                float expected = alignment == 0 ? 0 : alignment == 1 ? text.width / 2 : text.width;
                check(Math.abs(anchor - expected) <= 1, "Unwrapped paragraph preserves alignment " + alignment);
                check(drawn.bounds.left <= text.x + left && drawn.bounds.right >= text.x + right,
                    "Visibility bounds include unwrapped text");
            } else if (mode == 1) {
                check(layout.getLineCount() == 2, "Unwrapped text preserves explicit line breaks");
            } else {
                check(layout.getLineCount() > 1, "Ordinary text still wraps");
            }
            Bitmap frame = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(frame);
                canvas.translate(0, -page.y);
                renderer.draw(canvas, page.y, page.y + page.height, Collections.emptyMap());
                if (mode == 0) {
                    int overflowPixels = 0;
                    for (int y = 10; y < 90; y++) for (int x = 0; x < 600; x++) {
                        if ((x < text.x || x >= text.x + text.width) && Color.red(frame.getPixel(x, y)) > 200) overflowPixels++;
                    }
                    check(overflowPixels > 100, "Unwrapped overflow remains visible outside original shape width");
                }
                File file = new File(context.getFilesDir(), "pptx-wrapping-page" + (index + 1) + ".png");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
                }
            } finally { frame.recycle(); }
        }
    }

    private static void check(boolean value, String message) { PptxRenderingChecks.check(value, message); }
}
