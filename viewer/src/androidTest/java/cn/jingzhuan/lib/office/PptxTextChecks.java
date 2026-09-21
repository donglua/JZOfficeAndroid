package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

final class PptxTextChecks {
    private static final String FIXTURE = "pptx-typography.pptx";

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainChecked(PptxTextChecks::multilineSpacing);
        sampleTitle(instrumentation);
        PptxWrappingChecks.run(instrumentation);
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = source.document;
            instrumentation.runOnMainChecked(() -> {
                validate(document);
                for (int page = 0; page < 2; page++) for (int width : new int[] {960, 1920}) {
                    capture(context, document, page, width);
                }
            });
        }
        return "PASS PPTX typography URI/JNI, narrow number/title boxes, consistent multiline leading and DOCX isolation\n"
            + "PASS sample page 10 chapter labels retain fractional font sizes, one line and no pixel overlap\n"
            + "SCREENSHOTS pptx-typography-page{1,2}-{960,1920}.png, pptx-typography-page10-1920.png\n";
    }

    private static void sampleTitle(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/samples/sample.pptx");
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = source.document;
            instrumentation.runOnMainChecked(() -> {
                capture(context, document, 9, 1920);
                OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
                java.util.List<OfficeRenderer.Element> labels = new java.util.ArrayList<>();
                for (OfficeRenderer.Element element : renderer.pages.get(9).elements) {
                    if (element.texts.isEmpty()) continue;
                    String text = element.texts.get(0).layout.getText().toString();
                    if ("PART 01".equals(text) || "章节标题，完整显示".equals(text)) labels.add(element);
                }
                PptxRenderingChecks.check(labels.size() == 2, "Sample page 10 contains both chapter labels");
                StringBuilder failures = new StringBuilder();
                for (OfficeRenderer.Element label : labels) {
                    android.text.StaticLayout layout = label.texts.get(0).layout;
                    android.text.TextPaint measured = new android.text.TextPaint(layout.getPaint());
                    android.text.TextPaint drawn = new android.text.TextPaint(layout.getPaint());
                    measured.setTextSize(1); drawn.setTextSize(1);
                    android.text.Spanned text = (android.text.Spanned) layout.getText();
                    for (android.text.style.MetricAffectingSpan span : text.getSpans(0, 1, android.text.style.MetricAffectingSpan.class)) {
                        span.updateMeasureState(measured); span.updateDrawState(drawn);
                    }
                    float size = label.source.paragraphs.get(0).runs.get(0).size;
                    verify(Math.abs(measured.getTextSize() - size) < 0.001f && Math.abs(drawn.getTextSize() - size) < 0.001f,
                        failures, "Sample chapter label retains fractional measure/draw size: " + size);
                    String detail = layout.getText() + ": font=" + label.source.paragraphs.get(0).runs.get(0).size
                        + ", box=" + label.width + "x" + label.height + ", layout=" + layout.getWidth() + "x" + layout.getHeight()
                        + ", desired=" + android.text.Layout.getDesiredWidth(layout.getText(), layout.getPaint())
                        + ", lines=" + layout.getLineCount();
                    verify(layout.getLineCount() == 1, failures, "Sample chapter label must stay on one line: " + detail);
                }
                Rect heading = pixels(labels.get(0).source), title = pixels(labels.get(1).source);
                verify(!heading.isEmpty() && !title.isEmpty() && heading.bottom <= title.top, failures,
                    "Sample chapter heading and title must not overlap: " + heading + " / " + title);
                if (failures.length() > 0) throw new AssertionError(failures.toString());
            });
        }
    }

    private static void multilineSpacing() {
        OfficeDocument.Paragraph paragraph = new OfficeDocument.Paragraph();
        paragraph.lineSpacing = 1.5f;
        paragraph.after = 0;
        OfficeDocument.Run run = new OfficeDocument.Run();
        run.size = 14;
        run.text = "半导体设备行业发展与市场情况。半导体设备行业发展与市场情况。半导体设备行业发展与市场情况。";
        paragraph.runs.add(run);
        java.util.List<OfficeTextLayout.Block> blocks = new java.util.ArrayList<>();
        OfficeTextLayout.append(Collections.singletonList(paragraph), 0, 0, 220, blocks, true);
        android.text.StaticLayout layout = blocks.get(0).layout;
        PptxRenderingChecks.check(layout.getLineCount() >= 3, "CJK paragraph wraps onto at least three lines");
        int firstHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        PptxRenderingChecks.check(firstHeight >= 24 && firstHeight <= 30, "150 percent leading includes first line");
        for (int line = 1; line < layout.getLineCount(); line++) {
            PptxRenderingChecks.check(layout.getLineBottom(line) - layout.getLineTop(line) == firstHeight,
                "Equal font sizes keep equal line heights at line " + line);
            PptxRenderingChecks.check(layout.getLineBaseline(line) - layout.getLineBaseline(line - 1) == firstHeight,
                "CJK baselines advance evenly at line " + line);
        }
        paragraph.runs.clear();
        for (int size : new int[] {14, 28, 14}) {
            OfficeDocument.Run mixed = new OfficeDocument.Run();
            mixed.size = size;
            mixed.text = paragraph.runs.size() == 2 ? "小字" : "字号\n";
            paragraph.runs.add(mixed);
        }
        blocks.clear();
        OfficeTextLayout.append(Collections.singletonList(paragraph), 0, 0, 220, blocks, true);
        layout = blocks.get(0).layout;
        PptxRenderingChecks.check(layout.getLineCount() == 3, "Mixed sizes retain explicit line breaks");
        PptxRenderingChecks.check(layout.getLineBottom(2) - layout.getLineTop(2) == firstHeight,
            "Small line after large text returns to its own font metrics");
        PptxRenderingChecks.check(layout.getLineBottom(1) - layout.getLineTop(1) > firstHeight * 1.5f,
            "Large text retains its own leading");
    }

    private static void validate(OfficeDocument document) {
        PptxRenderingChecks.check(document.pages.size() == 2, "Typography fixture has two pages");
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        int numbers = 0;
        StringBuilder failures = new StringBuilder();
        for (OfficeRenderer.Element element : renderer.pages.get(0).elements) {
            if (element.texts.isEmpty()) continue;
            numbers++;
            verify("Impact".equals(element.source.paragraphs.get(0).runs.get(0).fontFace), failures, "Native font face reaches viewer");
            verify(element.texts.get(0).layout.getLineCount() == 1, failures, "Agenda number " + numbers + " stays on one line");
            verify(element.texts.get(0).layout.getHeight() <= element.height, failures, "Agenda number fits box height");
        }
        verify(numbers == 5, failures, "All five agenda numbers checked");
        java.util.List<OfficeDocument.Element> text = new java.util.ArrayList<>();
        for (OfficeRenderer.Element element : renderer.pages.get(1).elements) {
            if (element.texts.isEmpty()) continue;
            text.add(element.source);
            verify(element.texts.get(0).layout.getLineCount() == 1, failures, "Section text stays on one line");
            verify(element.texts.get(0).layout.getHeight() <= element.height, failures, "Section text fits box height");
        }
        PptxRenderingChecks.check(text.size() == 2, "Both section labels checked");
        Rect heading = pixels(text.get(0));
        Rect title = pixels(text.get(1));
        verify(!heading.isEmpty() && !title.isEmpty() && heading.bottom <= title.top,
            failures, "Section heading and CJK title do not overlap: " + heading + " / " + title);
        android.text.StaticLayout docx = OfficeTextLayout.paragraph(text.get(1).paragraphs.get(0), text.get(1).width);
        verify(docx.getHeight() < 100, failures, "DOCX single-line spacing remains unchanged");
        if (failures.length() > 0) throw new AssertionError(failures.toString());
    }

    private static Rect pixels(OfficeDocument.Element element) {
        OfficeDocument document = new OfficeDocument(); document.kind = OfficeDocument.Kind.PPTX;
        OfficeDocument.Page page = new OfficeDocument.Page(); page.width = 960; page.height = 540; page.background = Color.BLACK;
        page.elements.add(element); document.pages.add(page);
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        Bitmap bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(bitmap), 0, 540, Collections.emptyMap());
            Rect bounds = new Rect();
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.red(bitmap.getPixel(x, y)) > 200) bounds.union(x, y, x + 1, y + 1);
            }
            return bounds;
        } finally { bitmap.recycle(); }
    }

    private static void capture(Context context, OfficeDocument document, int index, int width) throws Exception {
        OfficeRenderer renderer = new OfficeRenderer(); renderer.layout(document);
        OfficeRenderer.Page page = renderer.pages.get(index);
        Bitmap frame = Bitmap.createBitmap(width, width * 9 / 16, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(frame); canvas.scale(width / page.width, width / page.width); canvas.translate(0, -page.y);
            renderer.draw(canvas, page.y, page.y + page.height, Collections.emptyMap());
            File file = new File(context.getFilesDir(), "pptx-typography-page" + (index + 1) + "-" + width + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                PptxRenderingChecks.check(frame.compress(Bitmap.CompressFormat.PNG, 100, output), "Typography screenshot saved");
            }
        } finally { frame.recycle(); }
    }

    private static void verify(boolean condition, StringBuilder failures, String message) {
        if (!condition) failures.append(message).append('\n');
    }
}
