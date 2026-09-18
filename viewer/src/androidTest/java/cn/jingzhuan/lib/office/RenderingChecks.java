package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

final class RenderingChecks {
    private RenderingChecks() { }

    static String run(Context context) throws Exception {
        StringBuilder log = new StringBuilder("LAYOUT CHECKS\n");
        File docx = LayoutFixtures.docx(context.getCacheDir());
        File pptx = LayoutFixtures.pptx(context.getCacheDir());
        try {
            Map<String, Bitmap> docImages = new HashMap<>(), deckImages = new HashMap<>();
            OfficeDocument doc = decode(context, docx, docImages);
            OfficeDocument deck = decode(context, pptx, deckImages);
            checkDocx(doc, log);
            checkPptx(deck, deckImages, log);
            List<String> screenshots = new ArrayList<>();
            screenshots.add(capture(context, "layout-docx-1080x1600", doc, docImages, 1080, 1600));
            screenshots.add(capture(context, "layout-docx-1800x1000", doc, docImages, 1800, 1000));
            screenshots.add(capture(context, "layout-pptx-1080x1600", deck, deckImages, 1080, 1600));
            screenshots.add(capture(context, "layout-pptx-1800x1000", deck, deckImages, 1800, 1000));
            log.append("SCREENSHOTS ").append(screenshots).append('\n');
            log.append("LAYOUT CHECKS PASSED\n");
            return log.toString();
        } finally {
            docx.delete();
            pptx.delete();
        }
    }

    private static OfficeDocument decode(Context context, File file, Map<String, Bitmap> images) throws Exception {
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(file))) {
            OfficeDocument doc = DocumentDecoder.decode(NativeCore.parse(pkg.file.getAbsolutePath()));
            List<OfficeDocument.Element> elements = new ArrayList<>(doc.blocks);
            for (OfficeDocument.Page page : doc.pages) elements.addAll(page.elements);
            for (OfficeDocument.Element element : elements) {
                if (element.image != null && !images.containsKey(element.image)) images.put(element.image, pkg.image(element.image, 8_000_000).bitmap);
            }
            return doc;
        }
    }

    private static void checkDocx(OfficeDocument document, StringBuilder log) {
        check(document.kind == OfficeDocument.Kind.DOCX, "DOCX kind");
        check(document.blocks.size() >= 7, "DOCX fixture block count");
        assertParagraph(paragraph(document, 0), OfficeDocument.LineSpacingRule.AUTO, 1f, 0f, 0f, 0f);
        assertParagraph(paragraph(document, 1), OfficeDocument.LineSpacingRule.AUTO, 1.5f, 0f, 0f, 0f);
        assertParagraph(paragraph(document, 2), OfficeDocument.LineSpacingRule.AUTO, 2f, 0f, 0f, 0f);
        assertParagraph(paragraph(document, 3), OfficeDocument.LineSpacingRule.EXACT, 30f, 0f, 0f, 0f);
        assertParagraph(paragraph(document, 4), OfficeDocument.LineSpacingRule.AT_LEAST, 28f, 0f, 0f, 0f);
        assertParagraph(paragraph(document, 5), OfficeDocument.LineSpacingRule.AUTO, 1f, 24f, 12f, 10f);
        assertParagraph(paragraph(document, 6), OfficeDocument.LineSpacingRule.AUTO, 1f, 24f, 12f, -8f);

        StaticLayout auto1 = layout(paragraph(document, 0));
        StaticLayout auto15 = layout(paragraph(document, 1));
        StaticLayout auto2 = layout(paragraph(document, 2));
        check(auto1.getLineCount() > 1 && auto15.getLineCount() > 1 && auto2.getLineCount() > 1, "DOCX auto paragraphs wrap");
        int d1 = baselineDelta(auto1), d15 = baselineDelta(auto15), d2 = baselineDelta(auto2);
        check(d15 > d1 + 4 && d2 > d15 + 4, "DOCX auto baseline distance increases: " + d1 + "," + d15 + "," + d2);
        check(near(lineHeight(layout(paragraph(document, 3)), 0), 30f, 2f), "DOCX exact line height");
        check(lineHeight(layout(paragraph(document, 4)), 0) >= 28, "DOCX atLeast line height");

        StaticLayout first = layout(paragraph(document, 5));
        StaticLayout hanging = layout(paragraph(document, 6));
        check(first.getLineCount() > 1 && hanging.getLineCount() > 1, "DOCX indented paragraphs wrap");
        check(near(first.getPrimaryHorizontal(first.getLineStart(0)), 34f, 2f)
            && near(first.getPrimaryHorizontal(first.getLineStart(1)), 24f, 2f), "DOCX first-line text origins");
        check(near(hanging.getPrimaryHorizontal(hanging.getLineStart(0)), 16f, 2f)
            && near(hanging.getPrimaryHorizontal(hanging.getLineStart(1)), 24f, 2f), "DOCX hanging text origins");
        Rect firstLine = inkBounds(first, 0), firstWrap = inkBounds(first, 1);
        Rect hangingLine = inkBounds(hanging, 0), hangingWrap = inkBounds(hanging, 1);
        check(near(firstLine.left, 34, 3) && near(firstWrap.left, 24, 3), "DOCX first-line indent pixels");
        check(firstLine.right <= 169 && firstWrap.right <= 169, "DOCX right indent pixels");
        check(near(hangingLine.left, 16, 3) && near(hangingWrap.left, 24, 3), "DOCX hanging indent pixels");
        check(hanging.getLineStart(1) > hanging.getLineStart(0), "DOCX wrapped line start advances");
        log.append("PASS DOCX line spacing, baseline, wrap and indent checks\n");
    }

    private static OfficeDocument.Paragraph paragraph(OfficeDocument document, int index) {
        return document.blocks.get(index).paragraphs.get(0);
    }

    private static StaticLayout layout(OfficeDocument.Paragraph paragraph) {
        return OfficeTextLayout.paragraph(paragraph, 180);
    }

    private static void assertParagraph(OfficeDocument.Paragraph p, OfficeDocument.LineSpacingRule rule,
                                        float spacing, float indent, float right, float first) {
        check(p.lineSpacingRule == rule, "Paragraph rule " + rule);
        check(near(p.lineSpacing, spacing, 0.1f), "Paragraph spacing " + spacing);
        check(near(p.indent, indent, 0.1f), "Paragraph indent " + indent);
        check(near(p.rightIndent, right, 0.1f), "Paragraph right indent " + right);
        check(near(p.firstLineIndent, first, 0.1f), "Paragraph first indent " + first);
    }

    private static int baselineDelta(StaticLayout layout) {
        return layout.getLineBaseline(1) - layout.getLineBaseline(0);
    }

    private static int lineHeight(StaticLayout layout, int line) {
        return layout.getLineBottom(line) - layout.getLineTop(line);
    }

    private static Rect inkBounds(StaticLayout layout, int line) {
        Bitmap bitmap = Bitmap.createBitmap(220, layout.getHeight() + 4, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        layout.draw(canvas);
        int top = Math.max(0, layout.getLineTop(line));
        int bottom = Math.min(bitmap.getHeight(), layout.getLineBottom(line));
        Rect bounds = new Rect(bitmap.getWidth(), bottom, 0, top);
        for (int y = top; y < bottom; y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            int color = bitmap.getPixel(x, y);
            if (Color.alpha(color) != 0 && Color.red(color) + Color.green(color) + Color.blue(color) < 720) {
                if (x < bounds.left) bounds.left = x;
                if (x > bounds.right) bounds.right = x;
            }
        }
        bitmap.recycle();
        check(bounds.left <= bounds.right, "Line has ink");
        return bounds;
    }

    private static void checkPptx(OfficeDocument document, Map<String, Bitmap> decodedImages, StringBuilder log) {
        check(document.kind == OfficeDocument.Kind.PPTX, "PPTX kind");
        check(document.pages.size() == 2, "PPTX slide count");
        List<OfficeDocument.Element> texts = new ArrayList<>();
        List<OfficeDocument.Element> images = new ArrayList<>();
        for (OfficeDocument.Element e : document.pages.get(0).elements) {
            if (e.type == OfficeDocument.Type.TEXT) texts.add(e);
            if (e.type == OfficeDocument.Type.IMAGE) images.add(e);
        }
        check(texts.size() >= 3 && images.size() == 2, "PPTX text and image elements decoded");
        check(texts.get(0).verticalAlignment == OfficeDocument.VerticalAlignment.TOP, "PPTX top anchor decoded");
        check(texts.get(1).verticalAlignment == OfficeDocument.VerticalAlignment.CENTER, "PPTX center anchor decoded");
        check(texts.get(2).verticalAlignment == OfficeDocument.VerticalAlignment.BOTTOM, "PPTX bottom anchor decoded");
        check(images.get(0).imageCrop == null, "PPTX uncropped image decoded");
        OfficeDocument.ImageCrop crop = images.get(1).imageCrop;
        check(crop != null && near(crop.left, 0.5f, 0.01f) && near(crop.bottom, 0.5f, 0.01f), "PPTX asymmetric crop decoded");

        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page page = renderer.pages.get(0);
        List<OfficeRenderer.Element> drawnTexts = new ArrayList<>();
        for (OfficeRenderer.Element e : page.elements) if (e.source.type == OfficeDocument.Type.TEXT) drawnTexts.add(e);
        float textHeight = drawnTexts.get(0).texts.get(0).layout.getHeight();
        check(near(drawnTexts.get(0).texts.get(0).y, 0f, 1f), "PPTX top text layout");
        check(near(drawnTexts.get(1).texts.get(0).y, (drawnTexts.get(1).height - textHeight) / 2f, 1.5f), "PPTX center text layout");
        check(near(drawnTexts.get(2).texts.get(0).y, drawnTexts.get(2).height - textHeight, 1.5f), "PPTX bottom text layout");

        Bitmap bitmap = Bitmap.createBitmap(Math.round(page.width), Math.round(page.height), Bitmap.Config.ARGB_8888);
        renderer.draw(new Canvas(bitmap), 0, page.height, decodedImages);
        assertColor(bitmap, 54, 180, Color.RED, "PPTX uncropped red quadrant");
        assertColor(bitmap, 90, 180, Color.GREEN, "PPTX uncropped green quadrant");
        assertColor(bitmap, 54, 216, Color.BLUE, "PPTX uncropped blue quadrant");
        assertColor(bitmap, 90, 216, Color.YELLOW, "PPTX uncropped yellow quadrant");
        assertColor(bitmap, 252, 180, Color.GREEN, "PPTX cropped quadrant renders source rect");
        bitmap.recycle();
        log.append("PASS PPTX anchors, renderer offsets and crop pixels\n");
    }

    private static String capture(Context context, String name, OfficeDocument document, Map<String, Bitmap> images, int width, int height) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        float scale = Math.min(width / renderer.width, height / renderer.height);
        canvas.scale(scale, scale);
        renderer.draw(canvas, 0, renderer.height, images);
        File file = new File(context.getFilesDir(), name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
        } finally {
            bitmap.recycle();
        }
        return file.getName();
    }

    private static void assertColor(Bitmap bitmap, int x, int y, int expected, String label) {
        int color = bitmap.getPixel(x, y);
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        if (expected == Color.RED) check(r > 200 && g < 80 && b < 80, label + " actual=" + hex(color));
        else if (expected == Color.GREEN) check(g > 120 && r < 120 && b < 120, label + " actual=" + hex(color));
        else if (expected == Color.BLUE) check(b > 200 && r < 80 && g < 80, label + " actual=" + hex(color));
        else if (expected == Color.YELLOW) check(r > 180 && g > 180 && b < 80, label + " actual=" + hex(color));
    }

    private static String hex(int color) {
        return "#" + Integer.toHexString(color);
    }

    private static boolean near(float actual, float expected, float tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
