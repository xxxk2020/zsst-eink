package com.xxxk2020.zssteink;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded table coordinates, including real rowspan/colspan occupancy. No Android objects. */
final class NativeTableModel {
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern CELL = Pattern.compile("(?is)<(td|th)\\b([^>]*)>(.*?)</\\1\\s*>");
    static final int MAX_COLUMNS = 32;
    final List<Cell> cells = new ArrayList<Cell>();
    int rows, columns;

    static final class Cell {
        final int row, column, rowSpan, columnSpan;
        final String html;
        final boolean heading;
        Cell(int row, int column, int rowSpan, int columnSpan, String html, boolean heading) {
            this.row = row; this.column = column; this.rowSpan = rowSpan; this.columnSpan = columnSpan;
            this.html = html; this.heading = heading;
        }
    }

    static NativeTableModel parse(String html) {
        if (html.toLowerCase(java.util.Locale.US).contains("<table")) return null;
        List<String> sourceRows = new ArrayList<String>();
        Matcher rowMatcher = ROW.matcher(html);
        while (rowMatcher.find()) {
            sourceRows.add(rowMatcher.group(1));
            if (sourceRows.size() > 100) return null;
        }
        if (sourceRows.isEmpty()) return null;
        NativeTableModel model = new NativeTableModel();
        model.rows = sourceRows.size();
        int[] untilRow = new int[MAX_COLUMNS];
        for (int r = 0; r < model.rows; r++) {
            Matcher cellMatcher = CELL.matcher(sourceRows.get(r));
            int column = 0;
            while (cellMatcher.find()) {
                int span = positiveAttribute(cellMatcher.group(2), "colspan");
                int rowSpan = Math.min(positiveAttribute(cellMatcher.group(2), "rowspan"), model.rows - r);
                if (span > MAX_COLUMNS) return null;
                // A colspan needs a contiguous free region, not just a free starting cell.
                while (column + span <= MAX_COLUMNS && !free(untilRow, column, span, r)) column++;
                if (column + span > MAX_COLUMNS || model.cells.size() >= 512) return null;
                model.cells.add(new Cell(r, column, rowSpan, span, cellMatcher.group(3),
                        "th".equalsIgnoreCase(cellMatcher.group(1))));
                for (int c = column; c < column + span; c++) untilRow[c] = r + rowSpan;
                column += span;
                model.columns = Math.max(model.columns, column);
            }
        }
        return model.cells.isEmpty() ? null : model;
    }

    private static boolean free(int[] occupied, int start, int count, int row) {
        for (int c = start; c < start + count; c++) if (occupied[c] > row) return false;
        return true;
    }

    private static int positiveAttribute(String attributes, String name) {
        Matcher matcher = Pattern.compile("(?is)(?:^|\\s)" + name
                + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s'\"=<>`]+))").matcher(attributes);
        if (!matcher.find()) return 1;
        for (int i = 1; i <= 3; i++) if (matcher.group(i) != null) {
            try { return Math.max(1, Math.min(101, Integer.parseInt(matcher.group(i)))); }
            catch (NumberFormatException ignored) { return 1; }
        }
        return 1;
    }
}
