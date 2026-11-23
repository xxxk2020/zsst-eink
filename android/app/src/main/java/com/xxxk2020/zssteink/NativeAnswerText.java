package com.xxxk2020.zssteink;

/** Plain-text answer groups; spacing is intended for a monospace display. */
final class NativeAnswerText {
    private NativeAnswerText() { }

    /** Five answers per group, with right-aligned ranges and no trailing newline. */
    static String format(char[] answers, int groupsPerLine) {
        if (groupsPerLine != 1 && groupsPerLine != 2) {
            throw new IllegalArgumentException("groupsPerLine must be 1 or 2");
        }
        if (answers == null) throw new IllegalArgumentException("answers must not be null");
        for (char answer : answers) {
            if (answer != '-' && (answer < 'A' || answer > 'H')) {
                throw new IllegalArgumentException("answers must contain only A-H or '-'");
            }
        }
        if (answers.length == 0) return "";

        int groupCount = (answers.length - 1) / 5 + 1;
        int lastStart = (groupCount - 1) * 5 + 1;
        int labelWidth = String.valueOf(lastStart).length() + 1 + String.valueOf(answers.length).length();
        StringBuilder output = new StringBuilder();
        for (int group = 0; group < groupCount; group++) {
            if (group > 0) output.append(group % groupsPerLine == 0 ? "\n" : "    ");
            int start = group * 5;
            int end = start + Math.min(5, answers.length - start);
            String label = String.valueOf(start + 1) + "-" + end;
            for (int padding = label.length(); padding < labelWidth; padding++) output.append(' ');
            output.append(label).append("  ").append(answers, start, end - start);
        }
        return output.toString();
    }
}
