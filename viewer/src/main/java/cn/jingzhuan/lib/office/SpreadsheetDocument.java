package cn.jingzhuan.lib.office;

import java.util.ArrayList;
import java.util.List;

final class SpreadsheetDocument {
    private SpreadsheetDocument() { }

    static final class Sheet {
        String name = "";
        float[] rowHeights = {15};
        float[] columnWidths = {64};
        final List<Cell> cells = new ArrayList<>();
        final List<CellRange> merges = new ArrayList<>();
    }

    static final class Cell {
        int row, column, style;
        String text = "";
        boolean numeric;
    }

    static final class CellRange {
        int startRow, startColumn, endRow, endColumn;
    }

    static final class CellStyle {
        float fontSize = 11;
        boolean bold, italic, underline, wrap;
        int color = 0xff202124, fill;
        int alignment = 3;
        OfficeDocument.VerticalAlignment verticalAlignment = OfficeDocument.VerticalAlignment.BOTTOM;
        final Integer[] borders = new Integer[4];
    }
}
