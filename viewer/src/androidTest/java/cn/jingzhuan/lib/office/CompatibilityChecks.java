package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CompatibilityChecks {
    private CompatibilityChecks() { }

    static String run(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        palette(instrumentation, activity);
        tint(instrumentation, activity);
        return "PASS custom XLSX palette through JNI, font/fill/border colors, Canvas pixels and URI preview\n"
            + "PASS XLSX positive/negative tint through JNI, Canvas colors and URI preview\n"
            + PptxNumberingChecks.run(instrumentation, activity)
            + DocxFontChecks.run(instrumentation, activity)
            + PptxPresetChecks.run(instrumentation, activity);
    }

    private static void tint(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        Context context = instrumentation.getTargetContext();
        String styles = "<styleSheet><fonts><font><sz val=\"20\"/><color rgb=\"FF0000\" tint=\"-0.5\"/></font></fonts>"
            + "<fills><fill><patternFill patternType=\"solid\"><fgColor rgb=\"0000FF\" tint=\"0.5\"/></patternFill></fill></fills>"
            + "<cellXfs><xf fontId=\"0\" fillId=\"0\"/></cellXfs></styleSheet>";
        File source = spreadsheet(context, "tint", styles, "<row ht=\"60\"><c t=\"inlineStr\"><is><t>Tint 明暗色</t></is></c></row>");
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(source))) {
            SpreadsheetDocument.CellStyle style = pkg.document.cellStyles.get(0);
            check(style.color == 0xff800000 && style.fill == 0xff8080ff, "Tint reaches Java text and fill colors");
            check(pkg.document.warnings.isEmpty(), "Supported tint has no warning");
            instrumentation.runOnMainChecked(() -> {
                SheetRenderer renderer = new SheetRenderer();
                renderer.setSheet(pkg.document.sheets.get(0), pkg.document.cellStyles);
                Bitmap bitmap = Bitmap.createBitmap(300, 160, Bitmap.Config.ARGB_8888);
                try {
                    renderer.draw(new Canvas(bitmap), new RectF(0, 0, 300, 160));
                    color(bitmap, 80, 30, 0xff8080ff, "Tinted fill pixel");
                    check(hasColor(bitmap, 0xff800000), "Tinted font paints its color");
                } finally { bitmap.recycle(); renderer.clear(); }
            });
            show(instrumentation, activity, source, "compat-tint-screen.png");
        } finally { check(source.delete(), "Delete tint fixture"); }
    }

    private static void palette(OfficeInstrumentation instrumentation, PreviewTestActivity activity) throws Exception {
        Context context = instrumentation.getTargetContext();
        String styles = "<styleSheet><fonts><font><sz val=\"20\"/><color indexed=\"0\"/></font></fonts>"
            + "<fills><fill><patternFill patternType=\"solid\"><fgColor indexed=\"1\"/></patternFill></fill></fills>"
            + "<borders><border><right style=\"thin\"><color indexed=\"2\"/></right></border></borders>"
            + "<cellXfs><xf fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs>"
            + "<colors><indexedColors><rgbColor rgb=\"00202080\"/><rgbColor rgb=\"00A0E0C0\"/>"
            + "<rgbColor rgb=\"00800040\"/></indexedColors></colors></styleSheet>";
        File source = spreadsheet(context, "palette", styles, "<row ht=\"60\"><c t=\"inlineStr\"><is><t>Palette 调色板</t></is></c></row>");
        try (OfficePackage pkg = OfficePackage.open(context, Uri.fromFile(source))) {
            SpreadsheetDocument.CellStyle style = pkg.document.cellStyles.get(0);
            check(style.color == 0xff202080 && style.fill == 0xffa0e0c0 && style.borders[2] == 0xff800040,
                "Custom palette reaches Java font/fill/border style");
            check(pkg.document.warnings.isEmpty(), "Supported palette has no unsupported warning");
            instrumentation.runOnMainChecked(() -> {
                SheetRenderer renderer = new SheetRenderer();
                renderer.setSheet(pkg.document.sheets.get(0), pkg.document.cellStyles);
                Bitmap bitmap = Bitmap.createBitmap(600, 320, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(bitmap);
                    canvas.scale(2, 2);
                    renderer.draw(canvas, new RectF(0, 0, 300, 160));
                    color(bitmap, 160, 60, 0xffa0e0c0, "Custom palette fill pixel");
                    check(hasColor(bitmap, 0xff202080), "Custom palette font paints its color");
                    check(hasColor(bitmap, 0xff800040), "Custom palette border paints its color");
                    save(context, bitmap, "compat-palette-pixels.png");
                } finally { bitmap.recycle(); renderer.clear(); }
            });
            show(instrumentation, activity, source, "compat-palette-screen.png");
        } finally { check(source.delete(), "Delete palette fixture"); }
    }

    static File spreadsheet(Context context, String name, String styles, String rows) throws Exception {
        return document(context, name, "xl/workbook.xml", new String[][] {
            {"xl/workbook.xml", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Compatibility\" sheetId=\"1\" r:id=\"sheet\"/></sheets></workbook>"},
            {"xl/_rels/workbook.xml.rels", relationships("sheet", "worksheet", "worksheets/sheet.xml", "style", "styles", "styles.xml")},
            {"xl/styles.xml", styles},
            {"xl/worksheets/sheet.xml", "<worksheet><sheetFormatPr defaultColWidth=\"32\"/><sheetData>" + rows + "</sheetData></worksheet>"}
        });
    }

    static File document(Context context, String name, String main, String[][] parts) throws Exception {
        File source = File.createTempFile("compat-" + name, ".zip", context.getCacheDir());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(source))) {
            entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/></Types>");
            entry(zip, "_rels/.rels", relationships("main", "officeDocument", main));
            for (String[] part : parts) entry(zip, part[0], part[1]);
        }
        return source;
    }

    static File presentation(Context context, String name, String shapes) throws Exception {
        return document(context, name, "ppt/presentation.xml", new String[][] {
            {"ppt/presentation.xml", "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"slide\"/></p:sldIdLst>"
                + "<p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>"},
            {"ppt/_rels/presentation.xml.rels", relationships("slide", "slide", "slides/slide.xml")},
            {"ppt/slides/slide.xml", "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<p:cSld><p:spTree>" + shapes + "</p:spTree></p:cSld></p:sld>"}
        });
    }

    static String relationships(String... triples) {
        StringBuilder result = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < triples.length; i += 3) result.append("<Relationship Id=\"").append(triples[i])
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/").append(triples[i + 1])
            .append("\" Target=\"").append(triples[i + 2]).append("\"/>");
        return result.append("</Relationships>").toString();
    }

    private static void entry(ZipOutputStream zip, String path, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static void show(OfficeInstrumentation instrumentation, PreviewTestActivity activity, File source, String screenshot) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        instrumentation.runOnMainChecked(() -> activity.preview.open(Uri.fromFile(source), new OfficePreviewView.Listener() {
            @Override public void onLoaded(OfficePreviewView.Info info) { loaded.countDown(); }
            @Override public void onError(Exception failure) { error.set(failure); loaded.countDown(); }
        }));
        check(loaded.await(10, TimeUnit.SECONDS), "Compatibility document loads through URI");
        check(error.get() == null, "Compatibility URI load error: " + error.get());
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainChecked(() -> {
            Bitmap bitmap = Bitmap.createBitmap(activity.preview.getWidth(), activity.preview.getHeight(), Bitmap.Config.ARGB_8888);
            try { activity.preview.draw(new Canvas(bitmap)); save(activity, bitmap, screenshot); }
            finally { bitmap.recycle(); activity.preview.clear(); }
        });
    }

    static boolean hasColor(Bitmap bitmap, int expected) {
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (bitmap.getPixel(x, y) == expected) return true;
        }
        return false;
    }

    static void save(Context context, Bitmap bitmap, String name) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Save " + name);
        }
    }
}
