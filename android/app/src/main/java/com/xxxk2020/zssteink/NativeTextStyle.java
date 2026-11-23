package com.xxxk2020.zssteink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

/** Reading typography only. Construct/reloadFont on the repository worker, apply on the UI thread. */
public final class NativeTextStyle {
    public static final int MIN_SIZE = 14, MAX_SIZE = 36, WEIGHT_COUNT = 6;
    private static final float[] STROKE_WIDTHS = {0f, .005f, .010f, .020f, .030f, .040f};
    private static final String WEIGHT_KEY = "fontWeightSix", FONT_KEY = "fontSelection";
    private static WeakReference<Typeface> cachedFace = new WeakReference<Typeface>(null);
    private static String cachedPath = "";
    private static long cachedLength = -1;
    private static long cachedModified = -1;

    private final Context app;
    private final SharedPreferences preferences;
    private volatile int textSize;
    private volatile int textWeight;
    private volatile Typeface face = Typeface.DEFAULT;
    private volatile String fontId = FontStore.SYSTEM, fontName = "系统字体";

    /** Prepared on an IO queue; it has no effect until its owner accepts the result on the UI thread. */
    static final class PreparedFont {
        final FontStore.Entry entry;
        final Typeface face;
        PreparedFont(FontStore.Entry entry, Typeface face) { this.entry = entry; this.face = face; }
    }

    public NativeTextStyle(Context context) {
        app = context.getApplicationContext();
        preferences = app.getSharedPreferences("native-reader", Context.MODE_PRIVATE);
        int size = 18, weight = 0;
        try { size = preferences.getInt("fontSize", 18); } catch (ClassCastException ignored) { }
        if (preferences.contains(WEIGHT_KEY)) {
            try { weight = preferences.getInt(WEIGHT_KEY, 0); } catch (ClassCastException ignored) { }
        } else {
            int oldWeight = 0;
            try { oldWeight = preferences.getInt("fontWeight", 0); } catch (ClassCastException ignored) { }
            weight = oldWeight <= 0 ? 0 : oldWeight == 1 ? 2 : 3;
            preferences.edit().putInt(WEIGHT_KEY, weight).apply();
        }
        textSize = normalizedSize(size);
        textWeight = Math.max(0, Math.min(WEIGHT_COUNT - 1, weight));
        reloadFont();
    }

    /** Actual sp value, including when setSize was called with a legacy 1/2/3 preset. */
    public int size() { return textSize; }
    public int weight() { return textWeight; }
    public String fontId() { return fontId; }
    public String fontName() { return fontName; }

    public void setSize(int value) {
        textSize = normalizedSize(value);
        preferences.edit().putInt("fontSize", textSize).apply();
    }

    public void setWeight(int value) {
        textWeight = Math.max(0, Math.min(WEIGHT_COUNT - 1, value));
        preferences.edit().putInt(WEIGHT_KEY, textWeight).apply();
    }

    private static int normalizedSize(int value) {
        if (value >= 1 && value <= 3) return 12 + value * 3;
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    /** Load the saved font on the repository worker, falling back to system text if needed. */
    public void reloadFont() {
        String requested = FontStore.file(app).isFile() ? FontStore.LEGACY : FontStore.SYSTEM;
        try { requested = preferences.getString(FONT_KEY, requested); } catch (ClassCastException ignored) { }
        try {
            install(prepareFont(requested), false);
        } catch (IOException ignored) {
            install(new PreparedFont(new FontStore.Entry(FontStore.SYSTEM, "系统字体", null), Typeface.DEFAULT), false);
        }
    }

    List<FontStore.Entry> fonts() { return FontStore.list(app); }

    PreparedFont prepareFont(String id) throws IOException {
        FontStore.Entry entry = FontStore.entry(app, id);
        if (entry.file == null) return new PreparedFont(entry, Typeface.DEFAULT);
        if (entry.file.length() <= 0 || entry.file.length() > FontStore.MAX_BYTES) throw new IOException("字体文件为空或过大，请选择其他字体");
        try {
            Typeface loaded = loadFace(entry.file);
            if (loaded == null) throw new IOException("此字体无法用于原生界面，原字体已保留");
            return new PreparedFont(entry, loaded);
        } catch (RuntimeException error) { throw new IOException("此字体无法用于原生界面，原字体已保留"); }
        catch (OutOfMemoryError error) { throw new IOException("此字体占用内存过大，请选择较小的字体文件"); }
    }

    PreparedFont importFont(Uri uri) throws IOException {
        FontStore.Imported imported = FontStore.importNativeFont(app, uri);
        rememberFace(imported.entry.file, imported.face);
        return new PreparedFont(imported.entry, imported.face);
    }

    /** Call only after checking that the requesting screen still owns the prepared result. No disk reads. */
    void selectFont(PreparedFont prepared) { install(prepared, true); }

    private void install(PreparedFont prepared, boolean remember) {
        face = prepared.face;
        fontId = prepared.entry.id; fontName = prepared.entry.name;
        if (remember) preferences.edit().putString(FONT_KEY, fontId).apply();
    }

    private static synchronized Typeface loadFace(File font) {
        String path = font.getAbsolutePath();
        long length = font.length(), modified = font.lastModified();
        Typeface cached = cachedFace.get();
        if (cached != null && path.equals(cachedPath)
                && length == cachedLength && modified == cachedModified) return cached;
        Typeface loaded = Typeface.createFromFile(font);
        rememberFace(font, loaded);
        return loaded;
    }

    private static synchronized void rememberFace(File font, Typeface loaded) {
        cachedPath = font.getAbsolutePath(); cachedLength = font.length(); cachedModified = font.lastModified();
        cachedFace = new WeakReference<Typeface>(loaded);
    }

    /** Do not apply to toolbar buttons: their sizing and typeface remain system UI typography. */
    public void apply(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        view.setTypeface(face, Typeface.NORMAL);
        Paint paint = view.getPaint();
        paint.setFakeBoldText(false);
        paint.setStyle(textWeight == 0 ? Paint.Style.FILL : Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(view.getTextSize() * STROKE_WIDTHS[textWeight]);
        view.setLineSpacing(0f, 1.35f);
        view.setIncludeFontPadding(true);
        view.requestLayout();
        view.invalidate();
    }
}
