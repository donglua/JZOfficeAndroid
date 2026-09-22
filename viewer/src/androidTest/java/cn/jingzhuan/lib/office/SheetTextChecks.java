package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

final class SheetTextChecks {
    private SheetTextChecks() { }

    static String run() throws Exception {
        boundaries();
        long slowest = 0;
        String longText = repeat('W', 262145);
        for (boolean wrap : new boolean[] {true, false}) for (int alignment = 0; alignment < 3; alignment++) {
            SpreadsheetDocument.Sheet sheet = sheet();
            SpreadsheetDocument.Cell cell = new SpreadsheetDocument.Cell(); cell.text = longText;
            sheet.cells.add(cell);
            SpreadsheetDocument.CellStyle style = new SpreadsheetDocument.CellStyle();
            style.wrap = wrap; style.alignment = alignment;
            SheetRenderer renderer = new SheetRenderer(); renderer.setSheet(sheet, Collections.singletonList(style));
            long elapsed = draw(renderer, 0);
            slowest = Math.max(slowest, elapsed);
            StaticLayout layout = (StaticLayout) layouts(renderer).get(cell);
            check(layout != null && layout.getText().length() == 4096, "Long text layout input is bounded");
            check(layout.getText().toString().endsWith("\u2026"), "Long text preview marks omitted content");
            check(layout.getWidth() == 176 || !wrap, "Wrapped preview retains column width");
            check(layout.getLineCount() == 1 || wrap, "Unwrapped preview remains one line");
            check(cell.text == longText, "Layout preserves original cell text");
            draw(renderer, 0);
            check(layouts(renderer).get(cell) == layout, "Repeated draw reuses bounded layout");
            renderer.clear();
        }
        budget();
        return "PASS XLSX preview boundaries, Unicode, wrap/alignment, bounded layout cache and eviction; "
            + "262145-character cell slowest first draw=" + slowest + " ms\n";
    }

    private static void boundaries() {
        SpreadsheetDocument.Cell cell = new SpreadsheetDocument.Cell();
        for (String text : new String[] {"", "中文\nSECOND\n\ud83d\ude00", repeat('W', 4095), repeat('W', 4096)}) {
            cell.text = text;
            check(cell.previewText() == text, "Within-limit text remains unchanged");
        }
        cell.text = repeat('W', 4097);
        check(cell.previewText().equals(repeat('W', 4095) + "\u2026"), "Limit plus one has bounded prefix and ellipsis");
        cell.text = repeat('中', 4094) + "\ud83d\ude00TAIL";
        check(cell.previewText().equals(repeat('中', 4094) + "\u2026"), "Truncation does not split a surrogate pair");
        cell.text = repeat('中', 4093) + "\ud83d\ude00TAIL";
        check(cell.previewText().equals(repeat('中', 4093) + "\ud83d\ude00\u2026"), "Complete surrogate pair is retained");
        cell.text = "FIRST\nSECOND\n" + repeat('中', 5000);
        check(cell.previewText().startsWith("FIRST\nSECOND\n") && cell.previewText().length() == 4096,
            "Long multilingual preview retains explicit newlines");
    }

    private static void budget() throws Exception {
        SpreadsheetDocument.Sheet sheet = sheet();
        sheet.rowHeights = new float[80]; Arrays.fill(sheet.rowHeights, 60);
        String text = repeat('W', 8192);
        for (int row = 0; row < 80; row++) {
            SpreadsheetDocument.Cell cell = new SpreadsheetDocument.Cell();
            cell.row = row; cell.text = text; sheet.cells.add(cell);
        }
        SpreadsheetDocument.CellStyle style = new SpreadsheetDocument.CellStyle(); style.wrap = true;
        SheetRenderer renderer = new SheetRenderer(); renderer.setSheet(sheet, Collections.singletonList(style));
        for (int row = 0; row < 80; row++) draw(renderer, row * 60);
        Map<?, ?> cache = layouts(renderer);
        int expectedCost = 0;
        for (Object value : cache.values()) {
            StaticLayout layout = (StaticLayout) value;
            expectedCost += layout.getText().length() + 8 * layout.getLineCount();
        }
        Field cost = SheetRenderer.class.getDeclaredField("layoutCost"); cost.setAccessible(true);
        check(expectedCost > 0 && expectedCost <= 256 * 1024 && cost.getInt(renderer) == expectedCost,
            "Cache eviction charges and refunds actual preview lengths");
        check(!cache.containsKey(sheet.cells.get(0)) && cache.containsKey(sheet.cells.get(79)),
            "Long-cell cache evicts oldest preview and retains latest");
        renderer.clear();
        check(cost.getInt(renderer) == 0 && cache.isEmpty(), "Clear releases long-cell cache cost");
    }

    static Map<?, ?> layouts(SheetRenderer renderer) throws Exception {
        Field field = SheetRenderer.class.getDeclaredField("layouts"); field.setAccessible(true);
        return (Map<?, ?>) field.get(renderer);
    }

    private static SpreadsheetDocument.Sheet sheet() {
        SpreadsheetDocument.Sheet sheet = new SpreadsheetDocument.Sheet();
        sheet.rowHeights = new float[] {60}; sheet.columnWidths = new float[] {180};
        return sheet;
    }

    private static long draw(SheetRenderer renderer, int top) {
        Bitmap bitmap = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.translate(0, -top);
            long start = SystemClock.uptimeMillis();
            renderer.draw(canvas, new RectF(0, top, 240, top + 120));
            long elapsed = SystemClock.uptimeMillis() - start;
            check(elapsed < 2000, "Cell draw returns within 2 seconds: " + elapsed + " ms");
            return elapsed;
        } finally { bitmap.recycle(); }
    }

    static String repeat(char value, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars);
    }
}
