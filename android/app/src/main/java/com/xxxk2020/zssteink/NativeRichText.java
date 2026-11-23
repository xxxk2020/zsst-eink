package com.xxxk2020.zssteink;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, offline formatting adapters shared by prose, options and material cells. */
final class NativeRichText {
    private static final Pattern TAG = Pattern.compile("(?is)<(/?)([a-z][a-z0-9]*)\\b[^>]*>");
    private static final Pattern NON_CONTENT = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

    private NativeRichText() { }

    /** Match the existing document format: consecutive | cells | are a material table. */
    static String expandPipeTables(String source) {
        String[] lines = source.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        boolean table = false;
        for (String line : lines) {
            String row = line.trim();
            boolean isRow = row.length() > 2 && row.startsWith("|") && row.endsWith("|")
                    && row.substring(1, row.length() - 1).indexOf('|') >= 0;
            if (isRow) {
                if (!table) { out.append("<table>"); table = true; }
                if (row.matches("\\|[\\s:|\\-]+\\|")) continue;
                out.append("<tr>");
                String[] cells = row.substring(1, row.length() - 1).split("\\|", -1);
                for (String cell : cells) out.append("<td>").append(cell.trim()).append("</td>");
                out.append("</tr>");
            } else {
                if (table) { out.append("</table>\n"); table = false; }
                out.append(line).append('\n');
            }
        }
        if (table) out.append("</table>");
        return out.toString();
    }

    /** Allow formatting, discard ordinary HTML wrappers, and keep unknown mathematical text literal. */
    static String safeHtml(String source) {
        source = NON_CONTENT.matcher(source).replaceAll("");
        Matcher matcher = TAG.matcher(source);
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            output.append(escapedText(source.substring(cursor, matcher.start())));
            String tag = matcher.group(2).toLowerCase(Locale.US);
            boolean closing = "/".equals(matcher.group(1));
            if ("div".equals(tag) || "section".equals(tag) || "article".equals(tag)
                    || "blockquote".equals(tag) || tag.matches("h[1-6]")) tag = "p";
            if ("p".equals(tag) || "br".equals(tag) || "b".equals(tag) || "strong".equals(tag)
                    || "i".equals(tag) || "em".equals(tag) || "sub".equals(tag)
                    || "sup".equals(tag) || "u".equals(tag)) {
                output.append('<').append(closing ? "/" : "").append(tag).append('>');
            } else if ("li".equals(tag)) {
                output.append(closing ? "<br>" : "• ");
            } else if ("span".equals(tag) || "font".equals(tag) || "a".equals(tag)
                    || "ul".equals(tag) || "ol".equals(tag) || "center".equals(tag)) {
                // These wrappers carry no reading content; never show their source attributes.
            } else {
                output.append(escapedText(matcher.group()));
            }
            cursor = matcher.end();
        }
        return output.append(escapedText(source.substring(cursor))).toString();
    }

    private static String escapedText(String text) {
        return text.replaceAll("&(?!#(?:[0-9]+|[xX][0-9a-fA-F]+);|[A-Za-z][A-Za-z0-9]+;)", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\r\n", "\n").replace("\n", "<br>");
    }
}
