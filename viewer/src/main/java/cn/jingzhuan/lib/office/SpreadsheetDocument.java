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
        static final int MAX_PREVIEW_LENGTH = 4096;
        int row, column, style;
        String text = "";
        boolean numeric;

        String previewText() {
            if (text.length() <= MAX_PREVIEW_LENGTH) return text;
            int end = MAX_PREVIEW_LENGTH - 1;
            if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            return text.substring(0, end) + "\u2026";
        }
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
