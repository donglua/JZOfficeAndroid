package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.AbsoluteSizeSpan;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class PptxRenderingChecks {
    static final String FIXTURE = "pptx-compat.pptx";
    static final String IMAGE = "ppt/media/pixel.png";
    static final int GREEN = 0xff22aa66, ORANGE = 0xffe47722, TEAL = 0xff2a9d8f;

    private PptxRenderingChecks() { }

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        return PptxTextChecks.run(instrumentation) + runGeometry(instrumentation);
    }

    static String runGeometry(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        Map<String, Bitmap> images = new HashMap<>();
        StringBuilder log = new StringBuilder("PPTX COMPATIBILITY CHECKS\n");
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            String json = NativeCore.parse(source.file.getAbsolutePath());
            OfficeDocument document = source.document;
            check(document.kind == OfficeDocument.Kind.PPTX && document.pages.size() == 2, "Compatibility deck has two slides");
            for (OfficeDocument.Page page : document.pages) for (OfficeDocument.Element element : page.elements) {
                if (element.image != null && !images.containsKey(element.image)) {
                    Bitmap bitmap = source.image(element.image, OfficeImages.MAX_PIXELS).bitmap;
                    check(bitmap != null, "Fixture image decodes: " + element.image);
                    images.put(element.image, bitmap);
                }
            }
            check(images.keySet().equals(Collections.singleton(IMAGE)), "Fixture image relationship resolves to expected package part");
            color(images.get(IMAGE), 0, 0, TEAL, "Native fixture image pixel");
            log.append(PptxGeometryChecks.run(context, json));
            instrumentation.runOnMainChecked(() -> {
                geometry(document);
                localFlips();
                fractionalDimensions();
                text(document);
                overflowText();
                for (int page = 0; page < 2; page++) {
                    capture(context, document, images, page, 1080, 1600);
                    capture(context, document, images, page, 1800, 1000);
                }
            });
            log.append("PASS nested/group transforms, transformed clipping and image visibility\n")
                .append("PASS picture and line H/V/both flips, cropped picture pixels\n")
                .append("PASS fractional picture bounds and zero-height rotated/flipped line\n")
                .append("PASS saved font scaling, paragraph baselines and hanging indents\n")
                .append("SCREENSHOTS pptx-compat-page{1,2}-{1080x1600,1800x1000}.png\n");
            return log.toString();
        } finally {
            for (Bitmap bitmap : images.values()) bitmap.recycle();
        }
    }

    private static void geometry(OfficeDocument document) {
        OfficeDocument.Element rectangle = filled(document, GREEN);
        matrix(rectangle, new float[] {1, 0, 0, 1, 120, 60}, "Scaled group");
        near(rectangle.width, 40, 0.01f, "Scaled rectangle width in points");
        OfficeDocument.Element nested = textElement(document, "Grouped");
        matrix(nested, new float[] {1, 0, 0, 1, 120, 90}, "Nested group");
        near(nested.paragraphs.get(0).runs.get(0).size, 12, 0.01f, "Nested text keeps local font size");
        matrix(filled(document, ORANGE), new float[] {0, 1, -1, 0, 480, 30}, "Rotated group");
        OfficeDocument.Element picture = image(document);
        check(IMAGE.equals(picture.image), "Grouped picture resolves rImage to normalized package part");
        matrix(picture, new float[] {-1, 0, 0, 1, 280, 60}, "Grouped horizontally flipped picture");
        near(picture.x, 0, 0.01f, "Picture local x");
        near(picture.y, 0, 0.01f, "Picture local y");
        near(picture.width, 80, 0.01f, "Picture width in points");
        near(picture.height, 20, 0.01f, "Picture local height");
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        check(renderer.visibleImages(210, 62, 230, 78, 0).equals(Collections.singleton(picture.image)), "Moved picture enters viewport");
        check(renderer.visibleImages(65, 32, 95, 48, 0).isEmpty(), "Old local picture bounds are not visible");
        check(renderer.visibleImages(0, 421, 720, 826, 0).isEmpty(), "Second slide requests no picture");
        Bitmap quadrants = quadrants();
        Bitmap frame = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
        try {
            Map<String, Bitmap> images = Collections.singletonMap(picture.image, quadrants);
            renderer.draw(new Canvas(frame), 0, 405, images);
            color(frame, 140, 70, GREEN, "Scaled rectangle interior");
            color(frame, 115, 70, Color.WHITE, "Scaled rectangle left exterior");
            color(frame, 165, 70, Color.WHITE, "Scaled rectangle right exterior");
            color(frame, 140, 55, Color.WHITE, "Scaled rectangle top exterior");
            color(frame, 140, 85, Color.WHITE, "Scaled rectangle bottom exterior");
            color(frame, 470, 70, ORANGE, "Rotated bar interior");
            color(frame, 450, 70, Color.WHITE, "Rotated bar left exterior");
            color(frame, 490, 70, Color.WHITE, "Rotated bar right exterior");
            color(frame, 470, 20, Color.WHITE, "Rotated bar top exterior");
            color(frame, 470, 120, Color.WHITE, "Rotated bar bottom exterior");
            color(frame, 25, 35, Color.WHITE, "Group does not paint old local bounds");
            color(frame, 220, 65, Color.GREEN, "Grouped flip top left");
            color(frame, 260, 65, Color.RED, "Grouped flip top right");
            color(frame, 220, 75, Color.YELLOW, "Grouped flip bottom left");
            color(frame, 260, 75, Color.BLUE, "Grouped flip bottom right");
            int ink = 0;
            for (int y = 90; y < 120; y++) for (int x = 120; x < 280; x++) {
                int pixel = frame.getPixel(x, y);
                if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) ink++;
            }
            check(ink > 30, "Nested text paints at its transformed location");
            color(frame, 620, 375, 0xff003aac, "635-unit group rectangle interior");
            color(frame, 595, 375, Color.WHITE, "635-unit group stroke does not cover page");
            color(frame, 620, 395, Color.WHITE, "635-unit group stroke stays near outline");
            OfficeDocument.Element title = textElement(document, "Coordinate units");
            near(title.width, 485.6f, 0.01f, "Group text applies point insets after geometry conversion");
            OfficeRenderer.Element titleDrawing = renderer.pages.get(0).elements.stream()
                .filter(element -> element.source == title).findFirst().orElseThrow(AssertionError::new);
            near(titleDrawing.texts.get(0).layout.getPaint().getTextSize(), 24, 0.01f, "Group font stays 24pt");
            int titleInk = 0;
            for (int y = 364; y < 395; y++) for (int x = 47; x < 400; x++) {
                int pixel = frame.getPixel(x, y);
                if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) titleInk++;
            }
            check(titleInk > 100, "635-unit group title remains visible at normal font size");
            frame.eraseColor(Color.TRANSPARENT);
            renderer.draw(new Canvas(frame), 60, 80, images);
            color(frame, 220, 65, Color.GREEN, "Draw culling uses transformed picture bounds");
            color(frame, 470, 70, ORANGE, "Draw culling uses transformed bar bounds");
        } finally {
            frame.recycle(); quadrants.recycle();
        }
    }

    private static void localFlips() {
        OfficeDocument document = new OfficeDocument();
        document.kind = OfficeDocument.Kind.PPTX; document.width = 320;
        OfficeDocument.Page page = new OfficeDocument.Page();
        page.width = 320; page.height = 150; document.pages.add(page);
        int[][] expected = {
            {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW},
            {Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE},
            {Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN},
            {Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED},
            {Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED}
        };
        float[][] imageTransforms = {
            {1, 0, 0, 1, 10, 10}, {-1, 0, 0, 1, 110, 10},
            {1, 0, 0, -1, 130, 50}, {-1, 0, 0, -1, 230, 50},
            {-1, 0, 0, -1, 290, 50}
        };
        float[][] lineTransforms = {
            {1, 0, 0, 1, 10, 90}, {-1, 0, 0, 1, 110, 90},
            {1, 0, 0, -1, 130, 130}, {-1, 0, 0, -1, 230, 130}
        };
        for (int i = 0; i < expected.length; i++) {
            OfficeDocument.Element image = new OfficeDocument.Element();
            image.type = OfficeDocument.Type.IMAGE; image.image = "quadrants";
            image.x = 10 + i * 60; image.y = 10; image.width = image.height = 40;
            image.transform = imageTransforms[i];
            if (i == 4) image.imageBounds = new float[] {-13.333333f, -13.333333f, 40, 40};
            page.elements.add(image);
            if (i < 4) {
                OfficeDocument.Element line = new OfficeDocument.Element();
                line.type = OfficeDocument.Type.LINE; line.x = image.x; line.y = 90;
                line.width = line.height = 40; line.stroke = ORANGE; line.strokeWidth = 4;
                line.transform = lineTransforms[i]; page.elements.add(line);
            }
        }
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        Bitmap quadrants = quadrants();
        Bitmap frame = Bitmap.createBitmap(320, 150, Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(frame), 0, 150, Collections.singletonMap("quadrants", quadrants));
            for (int i = 0; i < expected.length; i++) {
                int left = 10 + i * 60;
                color(frame, left + 8, 18, expected[i][0], "Local flip " + i + " top left");
                color(frame, left + 32, 18, expected[i][1], "Local flip " + i + " top right");
                color(frame, left + 8, 42, expected[i][2], "Local flip " + i + " bottom left");
                color(frame, left + 32, 42, expected[i][3], "Local flip " + i + " bottom right");
                if (i < 4) {
                    boolean diagonal = i == 0 || i == 3;
                    color(frame, left + 10, 100, diagonal ? ORANGE : Color.WHITE, "Line flip " + i + " diagonal");
                    color(frame, left + 30, 100, diagonal ? Color.WHITE : ORANGE, "Line flip " + i + " opposite diagonal");
                }
            }
            color(frame, 275, 35, Color.YELLOW, "Crop shifts the color boundary before both flips");
        } finally {
            frame.recycle(); quadrants.recycle();
        }
    }

    private static void fractionalDimensions() {
        OfficeDocument document = new OfficeDocument(); document.kind = OfficeDocument.Kind.PPTX; document.width = 160;
        OfficeDocument.Page page = new OfficeDocument.Page(); page.width = page.height = 160; document.pages.add(page);
        OfficeDocument.Element image = new OfficeDocument.Element();
        image.type = OfficeDocument.Type.IMAGE; image.image = "quadrants";
        image.width = image.height = 0.5f;
        image.transform = new float[] {100, 0, 0, 100, 0, 0}; page.elements.add(image);
        OfficeDocument.Element line = new OfficeDocument.Element();
        line.type = OfficeDocument.Type.LINE; line.x = 80; line.y = 40; line.width = 40; line.height = 0;
        line.transform = new float[] {0, -1, -1, 0, 100, 60};
        line.stroke = ORANGE; line.strokeWidth = 0.5f; page.elements.add(line);
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        check(renderer.visibleImages(25, 25, 49, 49, 0).equals(Collections.singleton("quadrants")), "Fractional picture visible inside 50x50");
        check(renderer.visibleImages(51, 0, 60, 50, 0).isEmpty(), "Fractional picture excluded beyond x=50");
        check(renderer.visibleImages(0, 51, 50, 60, 0).isEmpty(), "Fractional picture excluded beyond y=50");
        check(renderer.visibleImages(75, 0, 80, 50, 0).isEmpty(), "Transparent default stroke does not expand image visibility");
        float[] endpoints = {0, 0, 40, 0};
        renderer.pages.get(0).elements.get(1).transform.mapPoints(endpoints);
        float[] expected = {100, 60, 100, 20};
        for (int i = 0; i < 4; i++) near(endpoints[i], expected[i], 0.001f, "Zero-height line endpoint " + i);
        Bitmap quadrants = quadrants(), frame = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(frame); canvas.scale(4, 4);
            renderer.draw(canvas, 0, 160, Collections.singletonMap("quadrants", quadrants));
            color(frame, 48, 48, Color.RED, "Fractional image top left");
            color(frame, 152, 48, Color.GREEN, "Fractional image top right");
            color(frame, 48, 152, Color.BLUE, "Fractional image bottom left");
            color(frame, 152, 152, Color.YELLOW, "Fractional image bottom right");
            color(frame, 240, 100, Color.WHITE, "Fractional image right exterior");
            color(frame, 100, 240, Color.WHITE, "Fractional image bottom exterior");
            color(frame, 400, 160, ORANGE, "Zero-height line rotates about actual pivot");
            color(frame, 403, 160, Color.WHITE, "Zero-height line excludes clamped-height pivot");
        } finally { frame.recycle(); quadrants.recycle(); }
    }

    private static void text(OfficeDocument document) {
        OfficeDocument.Element box = textElement(document, "PPTX line spacing");
        near(box.x, 36, 0.01f, "Paragraph box x"); near(box.y, 180, 0.01f, "Paragraph box y");
        near(box.width, 600, 0.01f, "Paragraph box width"); near(box.height, 180, 0.01f, "Paragraph box height");
        OfficeDocument.Paragraph paragraph = box.paragraphs.get(0);
        check(paragraph.lineSpacingRule == OfficeDocument.LineSpacingRule.AUTO, "Percentage spacing uses AUTO");
        near(paragraph.lineSpacing, 1.3f, 0.001f, "Saved line spacing reduction");
        near(paragraph.indent, 24, 0.01f, "Left indent");
        near(paragraph.rightIndent, 12, 0.01f, "Right indent");
        near(paragraph.firstLineIndent, -12, 0.01f, "Hanging indent");
        for (OfficeDocument.Run run : paragraph.runs) near(run.size, 18, 0.01f, "Saved font scale resolves to 18pt");
        StaticLayout layout = OfficeTextLayout.paragraph(paragraph, box.width);
        check(layout.getLineCount() > 1, "PPTX paragraph wraps");
        near(layout.getPaint().getTextSize(), 18, 0.01f, "Layout base font uses scaled size");
        AbsoluteSizeSpan[] sizes = ((Spanned) layout.getText()).getSpans(0, layout.getText().length(), AbsoluteSizeSpan.class);
        check(sizes.length > 0, "Scaled run font spans exist");
        for (AbsoluteSizeSpan size : sizes) check(size.getSize() == 18, "Scaled run span is 18pt");
        near(layout.getPrimaryHorizontal(layout.getLineStart(0)), 12, 1, "First line starts at hanging indent");
        near(layout.getPrimaryHorizontal(layout.getLineStart(1)), 24, 1, "Wrapped line starts at left indent");
        for (int line = 0; line < layout.getLineCount(); line++) check(layout.getLineRight(line) <= 589, "Right margin constrains line " + line);
        float spacing = paragraph.lineSpacing;
        StaticLayout single;
        try {
            paragraph.lineSpacing = 1;
            single = OfficeTextLayout.paragraph(paragraph, box.width);
        } finally { paragraph.lineSpacing = spacing; }
        int natural = single.getLineBaseline(1) - single.getLineBaseline(0);
        int actual = layout.getLineBaseline(1) - layout.getLineBaseline(0);
        check(actual > natural, "PPTX line spacing increases baseline distance");
        near(actual, natural * 1.3f, 2, "PPTX baseline distance uses reduced multiplier");
    }

    private static void overflowText() {
        OfficeDocument document = new OfficeDocument(); document.kind = OfficeDocument.Kind.PPTX; document.width = 960;
        OfficeDocument.Page page = new OfficeDocument.Page(); page.width = 960; page.height = 540; page.background = Color.WHITE;
        OfficeDocument.Element box = new OfficeDocument.Element();
        box.type = OfficeDocument.Type.TEXT; box.x = 164; box.y = 104; box.width = 632; box.height = 277;
        box.transform = new float[] {1, 0, 0, 1, 164, 104};
        for (String title : new String[] {"短线超跌15以下的应用策略", "短线超跌15-30%的应用策略",
            "短线超跌30-50以上的应用策略", "短线超跌50%以上应用策略"}) {
            box.paragraphs.add(paragraph(title, 24));
            box.paragraphs.add(paragraph("    正常超跌状态，抓上升回档行情。", 16));
            box.paragraphs.add(paragraph("   ", 16));
        }
        box.paragraphs.add(paragraph("   ", 20));
        box.paragraphs.add(paragraph("   注意事项：短线超跌大部分时间处于0的位置。", 16));
        page.elements.add(box); document.pages.add(page);
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        Bitmap frame = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(frame), 0, page.height, Collections.emptyMap());
            int ink = 0;
            for (int y = Math.round(box.y + box.height) + 1; y < page.height; y++) {
                for (int x = 0; x < frame.getWidth(); x++) {
                    int pixel = frame.getPixel(x, y);
                    if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) ink++;
                }
            }
            check(ink > 0, "PPTX text overflow remains visible below the fixed source box");
        } finally { frame.recycle(); }
    }

    private static OfficeDocument.Paragraph paragraph(String text, float size) {
        OfficeDocument.Paragraph paragraph = new OfficeDocument.Paragraph();
        OfficeDocument.Run run = new OfficeDocument.Run(); run.text = text; run.size = size; run.bold = true;
        paragraph.runs.add(run); paragraph.alignment = 1; paragraph.after = 0; paragraph.before = 0;
        return paragraph;
    }

    private static void capture(Context context, OfficeDocument document, Map<String, Bitmap> images,
                                int pageIndex, int width, int height) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        OfficeRenderer.Page page = renderer.pages.get(pageIndex);
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(frame); canvas.drawColor(Color.LTGRAY);
            float scale = Math.min(width / page.width, height / page.height);
            canvas.scale(scale, scale); canvas.translate(0, -page.y);
            canvas.clipRect(0, page.y, page.width, page.y + page.height);
            renderer.draw(canvas, page.y, page.y + page.height, images);
            color(frame, Math.round(10 * scale), Math.round(300 * scale), page.background, "Captured page background " + (pageIndex + 1));
            if (pageIndex == 0) {
                color(frame, Math.round(140 * scale), Math.round(70 * scale), GREEN, "Captured group rectangle");
                color(frame, Math.round(470 * scale), Math.round(70 * scale), ORANGE, "Captured rotated bar");
            }
            File file = new File(context.getFilesDir(), "pptx-compat-page" + (pageIndex + 1) + "-" + width + "x" + height + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
            }
        } finally { frame.recycle(); }
    }

    private static Bitmap quadrants() {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) {
            bitmap.setPixel(x, y, y < 20 ? (x < 20 ? Color.RED : Color.GREEN) : (x < 20 ? Color.BLUE : Color.YELLOW));
        }
        return bitmap;
    }

    private static OfficeDocument.Element filled(OfficeDocument document, int color) {
        for (OfficeDocument.Element element : document.pages.get(0).elements) if (element.fill == color) return element;
        throw new AssertionError("Missing shape fill " + Integer.toHexString(color));
    }

    private static OfficeDocument.Element image(OfficeDocument document) {
        for (OfficeDocument.Element element : document.pages.get(0).elements) if (element.type == OfficeDocument.Type.IMAGE) return element;
        throw new AssertionError("Missing grouped picture");
    }

    private static OfficeDocument.Element textElement(OfficeDocument document, String prefix) {
        for (OfficeDocument.Element element : document.pages.get(0).elements) {
            for (OfficeDocument.Paragraph paragraph : element.paragraphs) {
                StringBuilder text = new StringBuilder();
                for (OfficeDocument.Run run : paragraph.runs) text.append(run.text);
                if (text.toString().startsWith(prefix)) return element;
            }
        }
        throw new AssertionError("Missing text " + prefix);
    }

    private static void matrix(OfficeDocument.Element element, float[] expected, String label) {
        check(element.transform.length == 6, label + " coefficient count");
        for (int i = 0; i < 6; i++) near(element.transform[i], expected[i], 0.001f, label + " coefficient " + i);
    }

    static void color(Bitmap bitmap, int x, int y, int expected, String label) {
        check(x >= 0 && y >= 0 && x < bitmap.getWidth() && y < bitmap.getHeight(), label + " sample is in viewport");
        int actual = bitmap.getPixel(x, y);
        check(Color.alpha(actual) == 255 && Math.abs(Color.red(actual) - Color.red(expected)) <= 5
            && Math.abs(Color.green(actual) - Color.green(expected)) <= 5 && Math.abs(Color.blue(actual) - Color.blue(expected)) <= 5,
            label + " at " + x + "," + y + " expected #" + Integer.toHexString(expected) + " actual #" + Integer.toHexString(actual));
    }

    static void near(float actual, float expected, float tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + ": expected " + expected + ", actual " + actual);
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
