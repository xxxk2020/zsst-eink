package com.xxxk2020.zssteink;

/** Presentation only: never use the result as a resource ID, group key or stored title. */
final class NativeDisplayText {
    static String clean(String value) {
        return value == null ? "" : value;
    }
    static String title(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.trim().length() == 0 ? fallback : cleaned;
    }
    private NativeDisplayText() { }
}
