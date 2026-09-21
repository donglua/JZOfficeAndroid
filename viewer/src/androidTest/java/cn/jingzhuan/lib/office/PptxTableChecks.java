package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.color;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.near;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class PptxTableChecks {
    private static final int BACKGROUND = 0xff191d20;
    private static final int HEADER_FILL = 0x333d8f8a;

    private PptxTableChecks() { }

    static String run(OfficeInstrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        decoderCellFills(context);
        instrumentation.runOnMainChecked(() -> {
            capturePptx(context);
            emptyDocxCellFillsStayTransparent();
        });
        return "PASS PPTX table cell fills, transparent body cells and DOCX empty cellFills compatibility\n"
            + "SCREENSHOTS pptx-table-synthetic.png\n";
    }

    private static void capturePptx(Context context) throws Exception {
        OfficeDocument document = new OfficeDocument();
        document.kind = OfficeDocument.Kind.PPTX;
        document.width = 320;
        OfficeDocument.Page page = new OfficeDocument.Page();
        page.width = 320;
        page.height = 220;
        page.background = BACKGROUND;
        document.pages.add(page);

        int[][] alphaTable = new int[][] {
            {HEADER_FILL, HEADER_FILL, HEADER_FILL},
            {0x00ffffff, 0x00ffffff, 0x00ffffff},
            {0x00ffffff, 0x00ffffff, 0x00ffffff},
            {0x00ffffff, 0x00ffffff, 0x00ffffff},
            {0x00ffffff, 0x00ffffff, 0x00ffffff}
        };
        page.elements.add(table(10, 10, 300, 100, alphaTable));

        int[][] colorTable = new int[][] {
            {Color.RED, Color.GREEN},
            {Color.BLUE, Color.YELLOW}
        };
        page.elements.add(table(10, 130, 120, 60, colorTable));

        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        OfficeRenderer.Page rendered = renderer.pages.get(0);
        Bitmap bitmap = Bitmap.createBitmap(Math.round(rendered.width), Math.round(rendered.height), Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(bitmap), 0, rendered.height, Collections.emptyMap());
            nearColor(bitmap, 60, 20, blendOver(HEADER_FILL, BACKGROUND), 2, "Header alpha fill blends over dark slide");
            color(bitmap, 60, 40, BACKGROUND, "Transparent body cell preserves dark slide background");
            color(bitmap, 40, 145, Color.RED, "First row first column fill");
            color(bitmap, 100, 145, Color.GREEN, "First row second column fill");
            color(bitmap, 40, 160, Color.BLUE, "Second row first column fill");
            color(bitmap, 100, 160, Color.YELLOW, "Second row second column fill");
            File file = new File(context.getFilesDir(), "pptx-table-synthetic.png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Saved " + file.getName());
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void decoderCellFills(Context context) throws Exception {
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".fixtures/sample.pptx");
        try (OfficePackage source = OfficePackage.open(context, uri)) {
            JSONObject root = new JSONObject(NativeCore.parse(source.file.getAbsolutePath()));
            JSONObject table = firstTable(root);
            JSONArray rows = table.getJSONArray("rows");
            JSONArray fills = fillsFor(rows);
            table.put("cellFills", fills);
            OfficeDocument decoded = DocumentDecoder.decode(root.toString());
            OfficeDocument.Element decodedTable = firstTable(decoded);
            check(!decodedTable.cellFills.isEmpty(), "Decoder reads injected table cellFills");
            check(decodedTable.cellFills.get(0).get(0) == HEADER_FILL, "Decoder preserves header fill color");
            if (decodedTable.cellFills.size() > 1) {
                check(decodedTable.cellFills.get(1).get(0) == 0x00ffffff, "Decoder preserves transparent body fill");
            }

            JSONObject badColumns = new JSONObject(root.toString());
            JSONArray badColumnFills = new JSONArray(fills.toString());
            badColumnFills.getJSONArray(0).put(HEADER_FILL);
            firstTable(badColumns).put("cellFills", badColumnFills);
            reject(badColumns, "Mismatched table fill columns rejected");

            JSONObject badRows = new JSONObject(root.toString());
            JSONArray badRowFills = new JSONArray(fills.toString());
            badRowFills.put(new JSONArray());
            firstTable(badRows).put("cellFills", badRowFills);
            reject(badRows, "Mismatched table fill rows rejected");
        }
    }

    private static void emptyDocxCellFillsStayTransparent() {
        OfficeDocument document = new OfficeDocument();
        document.kind = OfficeDocument.Kind.DOCX;
        document.width = 200;
        document.blocks.add(table(0, 0, 120, 40, null));
        OfficeRenderer renderer = new OfficeRenderer();
        renderer.layout(document);
        float[] origin = {0, 0};
        renderer.pages.get(0).elements.get(0).transform.mapPoints(origin);
        near(origin[0], 32, 0.001f, "DOCX layout still adds its horizontal margin");
        near(origin[1], 32, 0.001f, "DOCX layout still adds its vertical margin");
        Bitmap bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888);
        try {
            renderer.draw(new Canvas(bitmap), 0, renderer.height, Collections.emptyMap());
            color(bitmap, 50, 45, Color.WHITE, "DOCX table with empty cellFills keeps transparent cells");
        } finally {
            bitmap.recycle();
        }
    }

    private static OfficeDocument.Element table(float x, float y, float width, float height, int[][] fills) {
        OfficeDocument.Element table = new OfficeDocument.Element();
        table.type = OfficeDocument.Type.TABLE;
        table.x = x;
        table.y = y;
        table.transform = new float[] {1, 0, 0, 1, x, y};
        table.width = width;
        table.height = height;
        int rows = fills == null ? 1 : fills.length;
        int columns = fills == null ? 1 : fills[0].length;
        for (int column = 0; column < columns; column++) table.columnWidths.add(1f);
        for (int row = 0; row < rows; row++) {
            List<List<OfficeDocument.Paragraph>> cells = new ArrayList<>();
            List<Integer> cellFills = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                cells.add(Collections.emptyList());
                if (fills != null) cellFills.add(fills[row][column]);
            }
            table.rows.add(cells);
            if (fills != null) table.cellFills.add(cellFills);
        }
        return table;
    }

    private static JSONArray fillsFor(JSONArray rows) throws Exception {
        JSONArray fills = new JSONArray();
        for (int row = 0; row < rows.length(); row++) {
            JSONArray cells = rows.getJSONArray(row);
            JSONArray rowFills = new JSONArray();
            for (int column = 0; column < cells.length(); column++) {
                rowFills.put(row == 0 ? HEADER_FILL : 0x00ffffff);
            }
            fills.put(rowFills);
        }
        return fills;
    }

    private static JSONObject firstTable(JSONObject root) throws Exception {
        JSONArray pages = root.getJSONArray("pages");
        for (int page = 0; page < pages.length(); page++) {
            JSONArray elements = pages.getJSONObject(page).getJSONArray("elements");
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                if ("TABLE".equals(element.getString("type"))) return element;
            }
        }
        throw new AssertionError("Missing sample PPTX table element");
    }

    private static OfficeDocument.Element firstTable(OfficeDocument document) {
        for (OfficeDocument.Page page : document.pages) {
            for (OfficeDocument.Element element : page.elements) {
                if (element.type == OfficeDocument.Type.TABLE) return element;
            }
        }
        throw new AssertionError("Missing decoded PPTX table element");
    }

    private static void reject(JSONObject root, String label) throws Exception {
        try {
            DocumentDecoder.decode(root.toString());
        } catch (IOException expected) {
            check("Invalid core display model".equals(expected.getMessage()), label);
            return;
        }
        throw new AssertionError(label + " accepted");
    }

    private static int blendOver(int source, int destination) {
        int alpha = Color.alpha(source);
        int inverse = 255 - alpha;
        return Color.rgb(
            (Color.red(source) * alpha + Color.red(destination) * inverse + 127) / 255,
            (Color.green(source) * alpha + Color.green(destination) * inverse + 127) / 255,
            (Color.blue(source) * alpha + Color.blue(destination) * inverse + 127) / 255);
    }

    private static void nearColor(Bitmap bitmap, int x, int y, int expected, int tolerance, String label) {
        int actual = bitmap.getPixel(x, y);
        check(Color.alpha(actual) == 255
            && Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance
            && Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance
            && Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance,
            label + " at " + x + "," + y + " expected #" + Integer.toHexString(expected)
                + " actual #" + Integer.toHexString(actual));
    }
}
