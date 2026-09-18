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
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/" + FIXTURE);
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            OfficeDocument document = DocumentDecoder.decode(NativeCore.parse(source.file.getAbsolutePath()));
            instrumentation.runOnMainChecked(() -> {
                validate(document);
                for (int page = 0; page < 2; page++) for (int width : new int[] {960, 1920}) {
                    capture(context, document, page, width);
                }
            });
        }
        return "PASS PPTX typography URI/JNI, narrow number/title boxes, first-line leading and DOCX isolation\n"
            + "SCREENSHOTS pptx-typography-page{1,2}-{960,1920}.png\n";
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
