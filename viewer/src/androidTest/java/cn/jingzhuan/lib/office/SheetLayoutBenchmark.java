package cn.jingzhuan.lib.office;

import java.util.Arrays;
import java.util.Locale;

public final class SheetLayoutBenchmark {
    private static final int WARMUP = 30, SAMPLES = 100;
    private static volatile Object sink;

    interface Preparation { long prepare(SpreadsheetDocument.Sheet sheet); }

    public static void main(String[] args) {
        System.out.println("Runtime: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
            + " / " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.print(run("SheetLayout", sheet -> prepareLayout(sheet, 42, 20)));
    }

    static long prepareLayout(SpreadsheetDocument.Sheet sheet, float headerWidth, float headerHeight) {
        long start = System.nanoTime();
        sink = new SheetLayout(sheet, headerWidth, headerHeight);
        return System.nanoTime() - start;
    }

    static String run(String label, Preparation preparation) {
        StringBuilder output = new StringBuilder(label + ": preparation only; excludes file I/O, parsing, text layout and drawing\n");
        output.append("30 warmups, 100 timed preparations per scenario; no performance pass/fail threshold\n");
        output.append(measure("single/plain: 10000 rows, 256 columns, 50000 cells, 0 merges", preparation,
            new SpreadsheetDocument.Sheet[] {fixture(1, 0)}));
        output.append(measure("single/vertical-merges: 10000 rows, 256 columns, 50000 cells, 1000 merges", preparation,
            new SpreadsheetDocument.Sheet[] {fixture(1, 4)}));
        output.append(measure("alternating/plain: 2 sheets, 25000 cells and 0 merges each", preparation,
            new SpreadsheetDocument.Sheet[] {fixture(2, 0), fixture(2, 0)}));
        output.append(measure("alternating/vertical-merges: 2 sheets, 25000 cells and 500 merges each", preparation,
            new SpreadsheetDocument.Sheet[] {fixture(2, 2), fixture(2, 2)}));
        sink = null;
        return output.toString();
    }

    private static String measure(String name, Preparation preparation, SpreadsheetDocument.Sheet[] sheets) {
        long first = preparation.prepare(sheets[0]);
        for (int index = 0; index < WARMUP; index++) preparation.prepare(sheets[index % sheets.length]);
        long[] timings = new long[SAMPLES];
        for (int index = 0; index < SAMPLES; index++) {
            timings[index] = preparation.prepare(sheets[index % sheets.length]);
        }
        Arrays.sort(timings);
        return String.format(Locale.ROOT, "%s: first=%.3f ms, median=%.3f ms, p95=%.3f ms%n", name,
            first / 1_000_000.0, (timings[49] + timings[50]) / 2_000_000.0, timings[94] / 1_000_000.0);
    }

    private static SpreadsheetDocument.Sheet fixture(int rowStep, int bands) {
        SpreadsheetDocument.Sheet sheet = new SpreadsheetDocument.Sheet();
        sheet.rowHeights = new float[10000]; Arrays.fill(sheet.rowHeights, 20);
        sheet.columnWidths = new float[256]; Arrays.fill(sheet.columnWidths, 60);
        int[] columns = {0, 1, 64, 128, 255};
        for (int row = 0; row < 10000; row += rowStep) {
            for (int column : columns) sheet.cells.add(SheetLayoutChecks.cell(row, column));
        }
        for (int band = 0; band < bands; band++) {
            for (int column = 0; column < 250; column++) {
                sheet.merges.add(SheetLayoutChecks.range(band * (10000 / bands), column,
                    (band + 1) * (10000 / bands) - 1, column));
            }
        }
        return sheet;
    }
}
