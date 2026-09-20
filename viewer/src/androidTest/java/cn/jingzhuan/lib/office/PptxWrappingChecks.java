package cn.jingzhuan.lib.office;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.StaticLayout;
import java.util.Collections;

final class PptxWrappingChecks {
    static void run() {
        for (int alignment = 0; alignment <= 2; alignment++) {
            OfficeDocument document = new OfficeDocument();
            document.kind = OfficeDocument.Kind.PPTX; document.width = 600;
            OfficeDocument.Page page = new OfficeDocument.Page();
            page.width = 600; page.height = 300; page.background = Color.BLACK;
            OfficeDocument.Element text = new OfficeDocument.Element();
            text.x = 200; text.y = 10; text.width = 150; text.height = 80;
            text.padding = 0; text.textWrap = false;
            OfficeDocument.Paragraph paragraph = new OfficeDocument.Paragraph();
            paragraph.alignment = alignment; paragraph.after = 0;
            for (String part : new String[] {"PART 0", "5"}) {
                OfficeDocument.Run run = new OfficeDocument.Run();
                run.text = part; run.size = 60; run.color = Color.WHITE;
                paragraph.runs.add(run);
            }
            text.paragraphs.add(paragraph); page.elements.add(text); document.pages.add(page);
            OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
            OfficeRenderer.Element drawn = renderer.pages.get(0).elements.get(0);
            OfficeTextLayout.Block block = drawn.texts.get(0);
            StaticLayout layout = block.layout;
            check(layout.getLineCount() == 1, "Unwrapped text retains one line across runs");
            check(layout.getHeight() <= text.height, "PART 05 stays above its following title");
            float left = block.x + layout.getLineLeft(0), right = block.x + layout.getLineRight(0);
            float anchor = alignment == 0 ? left : alignment == 1 ? (left + right) / 2 : right;
            float expected = alignment == 0 ? 0 : alignment == 1 ? text.width / 2 : text.width;
            check(Math.abs(anchor - expected) <= 1, "Unwrapped paragraph preserves alignment " + alignment);
            Bitmap frame = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888);
            try {
                renderer.draw(new Canvas(frame), 0, 300, Collections.emptyMap());
                int overflowPixels = 0;
                for (int y = 10; y < 90; y++) for (int x = 0; x < 600; x++) {
                    if ((x < text.x || x >= text.x + text.width) && Color.red(frame.getPixel(x, y)) > 200) overflowPixels++;
                }
                check(overflowPixels > 100, "Unwrapped overflow remains visible outside original shape width");
                check(drawn.bounds.left <= text.x + left && drawn.bounds.right >= text.x + right,
                    "Visibility bounds include unwrapped text");
            } finally { frame.recycle(); }
            paragraph.runs.get(0).text = "PART\n0";
            renderer.layout(document);
            check(renderer.pages.get(0).elements.get(0).texts.get(0).layout.getLineCount() == 2,
                "Unwrapped text preserves explicit line breaks");
            paragraph.runs.get(0).text = "PART 0";
            text.textWrap = true;
            renderer.layout(document);
            check(renderer.pages.get(0).elements.get(0).texts.get(0).layout.getLineCount() > 1,
                "Ordinary text still wraps");
        }
    }

    private static void check(boolean value, String message) { PptxRenderingChecks.check(value, message); }
}
