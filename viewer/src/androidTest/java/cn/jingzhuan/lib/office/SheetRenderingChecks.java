package cn.jingzhuan.lib.office;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

final class SheetRenderingChecks {
    private SheetRenderingChecks() { }

    static String run() throws Exception { return run(null); }

    static String run(Context context) throws Exception {
        StringBuilder log = new StringBuilder("SHEET RENDERING CHECKS\n");
        checkDecoder();
        log.append("PASS schema3 styles, hidden dimensions, sparse cells, ranges and invalid-model rejection\n");
        checkMergedViewport(context);
        log.append("PASS offscreen merged anchor, hidden rows/columns, merged grid suppression and pinned headers\n");
        checkText();
        log.append("PASS wrap, explicit multiline, numeric alignment, font styling and text clipping\n");
        checkCache();
        log.append("PASS visible-only layout, bounded cache, sheet replacement and clear\n");
        return log.append("SHEET RENDERING CHECKS PASSED\n").toString();
    }

    private static void checkDecoder() throws Exception {
        String json = "{\"schemaVersion\":5,\"kind\":\"XLSX\",\"width\":595,\"warnings\":[],\"blocks\":[],\"pages\":[],"
            + "\"cellStyles\":[{\"fontSize\":13,\"bold\":true,\"italic\":true,\"underline\":true,\"color\":4279385946,"
            + "\"fill\":4294967295,\"alignment\":3,\"verticalAlignment\":\"BOTTOM\",\"wrap\":true,"
            + "\"borders\":[4278190335,null,4278190335,null]}],"
            + "\"sheets\":[{\"name\":\"Sheet 1\",\"rowHeights\":[28,0,36],\"columnWidths\":[80,0,100],"
            + "\"cells\":[{\"row\":0,\"column\":0,\"text\":\"123.45\",\"style\":0,\"numeric\":true}],"
            + "\"merges\":[{\"startRow\":0,\"startColumn\":0,\"endRow\":2,\"endColumn\":2}]}]}";
        OfficeDocument document = DocumentDecoder.decode(json);
        SpreadsheetDocument.Sheet sheet = document.sheets.get(0);
        SpreadsheetDocument.CellStyle style = document.cellStyles.get(0);
        check(document.kind == OfficeDocument.Kind.XLSX && sheet.name.equals("Sheet 1"), "Spreadsheet kind and name");
        check(sheet.rowHeights[1] == 0 && sheet.columnWidths[1] == 0, "Hidden dimensions are retained");
        check(sheet.cells.size() == 1 && sheet.cells.get(0).numeric && sheet.cells.get(0).text.equals("123.45"), "Sparse numeric text");
        check(style.fill == Color.WHITE && style.bold && style.italic && style.underline && style.wrap, "Cell style flags and unsigned color");
        check(style.borders[0] == Color.BLUE && style.borders[1] == null, "Optional borders");
        check(sheet.merges.get(0).endRow == 2 && sheet.merges.get(0).endColumn == 2, "Inclusive merged range");

        JSONObject invalid = new JSONObject(json);
        invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("style", 1);
        reject(invalid, "Out-of-range style");
        invalid = new JSONObject(json);
        invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("row", .5);
        reject(invalid, "Fractional coordinate");
        invalid = new JSONObject(json);
        invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("rowHeights").put(0, -1);
        reject(invalid, "Negative dimension");
        invalid = new JSONObject(json);
        invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("merges").getJSONObject(0).put("endColumn", 3);
        reject(invalid, "Out-of-range merge");
        invalid = new JSONObject(json);
        invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("cells")
            .put(invalid.getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0));
        reject(invalid, "Duplicate coordinate");
        invalid = new JSONObject(json).put("schemaVersion", 3);
        reject(invalid, "Unsupported schema");
        invalid = new JSONObject(json).put("sheets", new org.json.JSONArray());
        reject(invalid, "XLSX without sheets");
        invalid = new JSONObject(json).put("cellStyles", new org.json.JSONArray());
        reject(invalid, "XLSX without styles");
        for (String kind : new String[] {"DOCX", "PPTX"}) {
            JSONObject root = new JSONObject(json).put("kind", kind);
            root.put("sheets", new org.json.JSONArray()); root.put("cellStyles", new org.json.JSONArray());
            OfficeDocument legacy = DocumentDecoder.decode(root.toString());
            check(legacy.kind.name().equals(kind) && legacy.sheets.isEmpty() && legacy.cellStyles.isEmpty(), kind + " schema3 decode");
        }
    }

    private static void reject(JSONObject source, String label) throws Exception {
        try { DocumentDecoder.decode(source.toString()); throw new AssertionError(label + " accepted"); }
        catch (IOException expected) { }
    }

    private static void checkMergedViewport(Context context) throws Exception {
        SpreadsheetDocument.Sheet sheet = new SpreadsheetDocument.Sheet();
        sheet.rowHeights = new float[] {28, 0, 36, 30, 70};
        sheet.columnWidths = new float[] {80, 0, 100, 70, 90};
        SpreadsheetDocument.Cell anchor = cell(0, 0, "MERGED", 0);
        sheet.cells.add(anchor);
        sheet.cells.add(cell(1, 3, "HIDDEN ROW", 1));
        sheet.cells.add(cell(2, 2, "COVERED", 1));
        sheet.cells.add(cell(4, 1, "HIDDEN COLUMN", 1));
        SpreadsheetDocument.CellRange range = new SpreadsheetDocument.CellRange();
        range.endRow = 3; range.endColumn = 2; sheet.merges.add(range);
        SpreadsheetDocument.CellStyle merged = new SpreadsheetDocument.CellStyle();
        merged.fontSize = 13; merged.bold = true; merged.italic = true; merged.underline = true;
        merged.color = 0xff123f5a; merged.fill = 0xffffe49a; merged.alignment = 2;
        Arrays.fill(merged.borders, Color.BLUE);
        SpreadsheetDocument.CellStyle hidden = new SpreadsheetDocument.CellStyle(); hidden.fill = Color.RED;
        SheetRenderer renderer = new SheetRenderer();
        renderer.setSheet(sheet, Arrays.asList(merged, hidden));
        check(renderer.width == 382 && renderer.height == 184, "Dimensions exclude hidden rows and columns and include gutters");
        check(cache(renderer).isEmpty(), "Selecting sheet does not layout offscreen text");
        RectF viewport = new RectF(130, 60, 350, 180);
        Bitmap bitmap = render(renderer, viewport);
        try {
            assertColor(bitmap, 50, 24, merged.fill, 0, "Merged fill suppresses internal row grid after anchor leaves viewport");
            assertColor(bitmap, 10, 10, 0xfff3f5f7, 0, "Pinned header corner");
            assertColor(bitmap, 50, 2, 0xfff3f5f7, 0, "Pinned column header");
            assertColor(bitmap, 2, 30, 0xfff3f5f7, 0, "Pinned row header");
            assertColor(bitmap, 91, 30, Color.BLUE, 140, "Merged right border");
            check(!ink(bitmap, 43, 33, 90, 54).isEmpty(), "Merged anchor text remains visible");
            check(cache(renderer).size() == 1 && cache(renderer).containsKey(anchor), "Hidden and covered cells have no text layouts");
            if (context != null) {
                try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "sheet-merged-scrolled.png"))) {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved merged viewport screenshot");
                }
            }
        } finally { bitmap.recycle(); }
        SpreadsheetDocument.Sheet allHidden = new SpreadsheetDocument.Sheet();
        allHidden.rowHeights = new float[] {0}; allHidden.columnWidths = new float[] {0};
        renderer.setSheet(allHidden, Collections.singletonList(merged));
        check(renderer.width == SheetRenderer.HEADER_WIDTH && renderer.height == SheetRenderer.HEADER_HEIGHT, "All-hidden dimensions");
        bitmap = render(renderer, new RectF(0, 0, 160, 120)); bitmap.recycle();
        SpreadsheetDocument.Sheet hiddenMerge = new SpreadsheetDocument.Sheet();
        hiddenMerge.rowHeights = new float[] {30, 0, 30}; hiddenMerge.columnWidths = new float[] {100};
        hiddenMerge.cells.add(cell(1, 0, "HIDDEN", 0));
        SpreadsheetDocument.CellRange hiddenRange = new SpreadsheetDocument.CellRange();
        hiddenRange.startRow = hiddenRange.endRow = 1; hiddenMerge.merges.add(hiddenRange);
        Arrays.fill(hidden.borders, Color.RED);
        renderer.setSheet(hiddenMerge, Collections.singletonList(hidden));
        bitmap = render(renderer, new RectF(0, 0, 160, 120));
        try {
            int boundary = bitmap.getPixel(80, 49);
            check(Color.red(boundary) - Color.green(boundary) < 20, "Zero-height merged cell has no visible border");
        } finally { bitmap.recycle(); }
    }

    private static void checkText() throws Exception {
        SpreadsheetDocument.Sheet sheet = new SpreadsheetDocument.Sheet();
        sheet.rowHeights = new float[] {85, 85, 85, 85}; sheet.columnWidths = new float[] {120};
        List<SpreadsheetDocument.CellStyle> styles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SpreadsheetDocument.CellStyle style = new SpreadsheetDocument.CellStyle();
            style.fontSize = 12; style.color = Color.BLACK; style.verticalAlignment = OfficeDocument.VerticalAlignment.TOP;
            styles.add(style);
        }
        styles.get(1).wrap = true; styles.get(1).alignment = 1;
        styles.get(2).bold = true; styles.get(2).italic = true; styles.get(2).underline = true;
        styles.get(3).verticalAlignment = OfficeDocument.VerticalAlignment.BOTTOM;
        String words = "ONE TWO THREE FOUR FIVE SIX";
        sheet.cells.add(cell(0, 0, words, 0)); sheet.cells.add(cell(1, 0, words, 1));
        sheet.cells.add(cell(2, 0, "FIRST\nSECOND", 2)); sheet.cells.add(cell(3, 0, "123", 3));
        sheet.cells.get(3).numeric = true;
        SheetRenderer renderer = new SheetRenderer(); renderer.setSheet(sheet, styles);
        Bitmap bitmap = render(renderer, new RectF(0, 0, 190, 370));
        try {
            StaticLayout nowrap = (StaticLayout) cache(renderer).get(sheet.cells.get(0));
            StaticLayout wrap = (StaticLayout) cache(renderer).get(sheet.cells.get(1));
            StaticLayout multiline = (StaticLayout) cache(renderer).get(sheet.cells.get(2));
            check(nowrap.getLineCount() == 1 && nowrap.getWidth() > 116, "Non-wrapped text keeps natural width");
            check(wrap.getLineCount() > 1 && wrap.getWidth() == 116, "Wrapped text uses column width");
            check(multiline.getLineCount() == 2, "Explicit newline is retained without wrapping");
            check(multiline.getPaint().isUnderlineText() && multiline.getPaint().getTypeface().isBold()
                && multiline.getPaint().getTypeface().isItalic(), "Typeface and underline styles");
            check(ink(bitmap, 164, 22, 189, 100).isEmpty(), "Long text does not leak outside its cell");
            Rect numeric = ink(bitmap, 44, 277, 160, 359);
            check(!numeric.isEmpty() && numeric.left > 130 && numeric.bottom > 350, "Numeric general alignment is right and bottom");
        } finally { bitmap.recycle(); }
    }

    private static void checkCache() throws Exception {
        SpreadsheetDocument.Sheet sheet = new SpreadsheetDocument.Sheet();
        sheet.rowHeights = new float[10000]; Arrays.fill(sheet.rowHeights, 20);
        sheet.columnWidths = new float[100]; Arrays.fill(sheet.columnWidths, 60);
        for (int row = 0; row < 10000; row++) {
            sheet.cells.add(cell(row, 0, "R" + row, 0));
            sheet.cells.add(cell(row, 99, "OFFSCREEN" + row, 0));
        }
        SheetRenderer renderer = new SheetRenderer();
        List<SpreadsheetDocument.CellStyle> styles = Collections.singletonList(new SpreadsheetDocument.CellStyle());
        renderer.setSheet(sheet, styles);
        Bitmap bitmap = render(renderer, new RectF(0, 0, 240, 240)); bitmap.recycle();
        check(cache(renderer).size() > 0 && cache(renderer).size() <= 11, "Only visible rows and columns get layouts");
        for (Object key : cache(renderer).keySet()) check(((SpreadsheetDocument.Cell) key).column == 0, "Offscreen column is not laid out");
        Object firstLayout = cache(renderer).get(sheet.cells.get(0));
        bitmap = render(renderer, new RectF(0, 0, 240, 240)); bitmap.recycle();
        check(firstLayout == cache(renderer).get(sheet.cells.get(0)), "Stable cell reuses its cached layout");
        for (int row = 200; row < 9600; row += 200) {
            bitmap = render(renderer, new RectF(0, row * 20, 240, row * 20 + 240)); bitmap.recycle();
        }
        check(cache(renderer).size() <= 256 && cache(renderer).size() > 200, "Cache retains a bounded scrolling working set");
        check(!cache(renderer).containsKey(sheet.cells.get(0)), "Oldest layout is evicted");
        SpreadsheetDocument.Sheet large = new SpreadsheetDocument.Sheet();
        large.rowHeights = new float[] {60}; large.columnWidths = new float[] {180};
        char[] text = new char[256 * 1024 + 1]; Arrays.fill(text, 'W');
        large.cells.add(cell(0, 0, new String(text), 0));
        SpreadsheetDocument.CellStyle wrap = new SpreadsheetDocument.CellStyle(); wrap.wrap = true;
        renderer.setSheet(large, Collections.singletonList(wrap));
        check(cache(renderer).isEmpty(), "Sheet replacement discards all cached layouts");
        bitmap = render(renderer, new RectF(0, 0, 240, 120)); bitmap.recycle();
        check(cache(renderer).isEmpty(), "Oversized text layout is not retained");
        renderer.clear();
        check(renderer.width == 0 && renderer.height == 0 && cache(renderer).isEmpty(), "Clear releases dimensions and text layouts");
    }

    private static SpreadsheetDocument.Cell cell(int row, int column, String text, int style) {
        SpreadsheetDocument.Cell cell = new SpreadsheetDocument.Cell();
        cell.row = row; cell.column = column; cell.text = text; cell.style = style;
        return cell;
    }

    private static Map<?, ?> cache(SheetRenderer renderer) throws Exception {
        Field field = SheetRenderer.class.getDeclaredField("layouts"); field.setAccessible(true);
        return (Map<?, ?>) field.get(renderer);
    }

    private static Bitmap render(SheetRenderer renderer, RectF viewport) {
        Bitmap bitmap = Bitmap.createBitmap((int) viewport.width(), (int) viewport.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        canvas.translate(-viewport.left, -viewport.top); renderer.draw(canvas, viewport);
        return bitmap;
    }

    private static Rect ink(Bitmap bitmap, int left, int top, int right, int bottom) {
        Rect bounds = new Rect(right, bottom, left, top);
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int color = bitmap.getPixel(x, y);
            if (Color.red(color) < 100 && Color.green(color) < 130 && Color.blue(color) < 170) {
                bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y);
                bounds.right = Math.max(bounds.right, x + 1); bounds.bottom = Math.max(bounds.bottom, y + 1);
            }
        }
        return bounds;
    }

    private static void assertColor(Bitmap bitmap, int x, int y, int expected, int tolerance, String label) {
        int actual = bitmap.getPixel(x, y);
        check(Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance
            && Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance
            && Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance,
            label + " actual=" + Integer.toHexString(actual));
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
