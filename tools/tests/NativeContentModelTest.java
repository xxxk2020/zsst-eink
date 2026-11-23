package com.xxxk2020.zssteink;

/** Pure-Java regression checks for the document-format compatibility layer. */
public final class NativeContentModelTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String html = NativeRichText.safeHtml("<p><span class='x'>H<sub>2</sub>O</span> "
                + "<font color='red'>x<sup>2</sup></font><a href='https://example.invalid'>链接</a></p>");
        check(html.contains("H<sub>2</sub>O") && html.contains("x<sup>2</sup>"), "sub/sup preserved");
        check(!html.contains("span") && !html.contains("font") && !html.contains("href"), "wrappers hidden");
        check(NativeRichText.safeHtml("x<y>z & a < b").equals("x&lt;y&gt;z &amp; a &lt; b"), "math symbols stay literal");
        check(NativeRichText.safeHtml("A<script>alert(1)</script><style>b{}</style>B").equals("AB"), "non-content removed");
        check(NativeRichText.safeHtml("x &gt; y\nnext").equals("x &gt; y<br>next"), "entities and line breaks retained");

        String pipe = NativeRichText.expandPipeTables("说明\n| 年份 | 产值 |\n|---|---:|\n|2025|15|\n结论");
        check(pipe.contains("<table><tr><td>年份</td><td>产值</td></tr>"), "pipe table starts");
        check(pipe.contains("<tr><td>2025</td><td>15</td></tr></table>"), "pipe data retained");
        check(!pipe.contains("---"), "separator not rendered as data");
        check(NativeRichText.expandPipeTables("|\n|x|\nabsolute | x |").equals("|\n|x|\nabsolute | x |\n"), "non-table bars safe");

        NativeTableModel table = NativeTableModel.parse("<tr><th rowspan='2'>地区</th><th colspan='2'>产值</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr><tr><td>甲</td><td>12</td><td>15</td></tr>");
        check(table != null && table.columns == 3 && table.rows == 3, "merged table dimensions");
        check(table.cells.get(0).rowSpan == 2 && table.cells.get(0).heading, "rowspan/heading retained");
        check(table.cells.get(1).columnSpan == 2 && table.cells.get(2).column == 1, "rowspan reserves next row");
        check(table.cells.get(4).column == 0 && table.cells.get(4).row == 2, "rowspan reservation expires");
        NativeTableModel collision = NativeTableModel.parse("<tr><td>A</td><td rowspan=2>B</td></tr><tr><td colspan=2>C</td></tr>");
        check(collision != null && collision.cells.get(2).column == 2, "colspan cannot overlap a reserved column");
        check(NativeTableModel.parse("<tr><td colspan=99>huge</td></tr>") == null, "width limit");
        check(NativeTableModel.parse("<tr><td><table><tr><td>nested</td></tr></table></td></tr>") == null, "nested table fallback");
        StringBuilder tall = new StringBuilder();
        for (int i = 0; i < 101; i++) tall.append("<tr><td>x</td></tr>");
        check(NativeTableModel.parse(tall.toString()) == null, "row limit");
        System.out.println("PASS: rich HTML, mathematical text, pipe tables, merged-cell placement and table bounds");
    }
}
