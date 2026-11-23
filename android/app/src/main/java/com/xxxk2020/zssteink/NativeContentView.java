package com.xxxk2020.zssteink;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline, View-based document content. All public View operations belong to the UI thread. */
public final class NativeContentView extends LinearLayout {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(0, 1, 15,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
        @Override public Thread newThread(final Runnable work) {
            Thread thread = new Thread(new Runnable() {
                @Override public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    work.run();
                }
            }, "native-image-reader");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final Pattern TABLE = Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table\\s*>");
    private static final Pattern HTML_IMAGE = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern IMAGE = Pattern.compile("\\[\\[img:([^\\]\\r\\n]+)\\]\\]");
    private static final int IMAGE_PIXELS = 1250000;
    private static final int ZOOM_PIXELS = 2500000;

    private NativeRepository.Document document;
    private final NativeTextStyle style;
    private final List<TextView> readingText = new ArrayList<TextView>();
    private final List<ImageSlot> images = new ArrayList<ImageSlot>();
    private final Set<String> usedImages = new HashSet<String>();
    private volatile int generation;
    private volatile boolean released;
    private boolean compact;
    private int nextImageId;
    private Future<?> decodeJob;
    private boolean visibilityPosted;
    private ViewTreeObserver observedTree;
    private View observedPane;
    private final View.OnLayoutChangeListener paneLayoutListener = new View.OnLayoutChangeListener() {
        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (released || (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop)) return;
            for (ImageSlot slot : images) if (!slot.formula) updateBlockSize(slot);
            scheduleVisibility();
        }
    };
    private Overlay overlay;
    private final Runnable visibilityWork = new Runnable() {
        @Override public void run() { visibilityPosted = false; updateVisibleImages(); }
    };
    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() { scheduleVisibility(); }
            };

    public NativeContentView(Context context, NativeRepository.Document document, NativeTextStyle style) {
        super(context);
        if (document == null || style == null) throw new IllegalArgumentException("Missing document or text style");
        this.document = document;
        this.style = style;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        setClipChildren(true);
    }

    /** Answer rows supply their own insets; prose keeps its slightly roomier reading rhythm. */
    public void setCompact(boolean value) {
        if (released || compact == value) return;
        compact = value;
        for (TextView text : readingText) text.setPadding(0, compact ? 0 : dp(3), 0, compact ? 0 : dp(5));
        for (ImageSlot slot : images) if (slot.frame != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) slot.frame.getLayoutParams();
            lp.topMargin = dp(compact ? 2 : 4);
            lp.bottomMargin = dp(compact ? 2 : 8);
            slot.frame.setLayoutParams(lp);
        }
    }

    /** Replaces only this content region. Attached images are deduplicated against inline references. */
    public void render(String text, JSONArray imageRefs) {
        if (released) return;
        clearContent();
        String source = NativeRichText.expandPipeTables(text == null ? "" : text);
        Matcher tables = TABLE.matcher(source);
        int cursor = 0;
        while (tables.find()) {
            appendRich(source.substring(cursor, tables.start()), this, 0);
            appendTable(tables.group(1));
            cursor = tables.end();
        }
        appendRich(source.substring(cursor), this, 0);
        if (imageRefs != null) for (int i = 0; i < imageRefs.length(); i++) {
            Object raw = imageRefs.opt(i);
            String ref = raw instanceof String ? (String) raw : raw instanceof JSONObject
                    ? ((JSONObject) raw).optString("id", ((JSONObject) raw).optString("src", "")) : "";
            ref = cleanRef(ref);
            if (ref.length() > 0 && usedImages.add(ref)) appendBlockImage(ref, this, 0);
        }
        observeVisibility();
        scheduleVisibility();
    }

    /** Applies a changed size/weight/typeface without replacing the material or resetting its scroll. */
    public void refreshStyle() {
        if (released) return;
        for (TextView text : readingText) style.apply(text);
        for (ImageSlot slot : images) if (slot.formula) {
            updateFormulaSize(slot);
            invalidateFormula(slot);
        }
        requestLayout();
        scheduleVisibility();
    }

    public void release() {
        if (released) return;
        released = true;
        clearContent();
        stopObserving();
        document = null;
    }

    private void clearContent() {
        generation++;
        if (decodeJob != null) { decodeJob.cancel(true); decodeJob = null; }
        DECODER.purge();
        removeCallbacks(visibilityWork);
        visibilityPosted = false;
        if (overlay != null) { overlay.close(); overlay = null; }
        for (ImageSlot slot : images) releaseBitmap(slot);
        for (TextView text : readingText) {
            text.setMovementMethod(null);
            text.setText("");
        }
        removeAllViews();
        readingText.clear();
        images.clear();
        usedImages.clear();
    }

    private void appendRich(String source, LinearLayout target, int widthHint) {
        String normalized = inlineImageTags(source);
        Matcher matcher = IMAGE.matcher(normalized);
        StringBuilder pending = new StringBuilder();
        List<InlineToken> formulas = new ArrayList<InlineToken>();
        int cursor = 0;
        while (matcher.find()) {
            pending.append(normalized, cursor, matcher.start());
            String ref = cleanRef(matcher.group(1));
            usedImages.add(ref);
            if (document.isFormula(ref)) {
                ImageSlot slot = new ImageSlot(++nextImageId, ref, true, widthHint);
                images.add(slot);
                String marker = "\uFFF9native-image-" + slot.id + "\uFFFB";
                pending.append(marker);
                formulas.add(new InlineToken(marker, slot));
            } else {
                appendText(pending.toString(), formulas, target);
                pending.setLength(0);
                formulas.clear();
                appendBlockImage(ref, target, widthHint);
            }
            cursor = matcher.end();
        }
        pending.append(normalized.substring(cursor));
        appendText(pending.toString(), formulas, target);
    }

    @SuppressWarnings("deprecation")
    private void appendText(String source, List<InlineToken> formulas, LinearLayout target) {
        SpannableStringBuilder body = new SpannableStringBuilder(Html.fromHtml(NativeRichText.safeHtml(source)));
        while (body.length() > 0 && body.charAt(body.length() - 1) == '\n') body.delete(body.length() - 1, body.length());
        while (body.length() > 0 && body.charAt(0) == '\n') body.delete(0, 1);
        if (body.length() == 0) return;
        TextView text = new TextView(getContext());
        text.setTextColor(Color.BLACK);
        text.setPadding(0, compact ? 0 : dp(3), 0, compact ? 0 : dp(5));
        style.apply(text);
        for (InlineToken token : formulas) {
            int at = TextUtils.indexOf(body, token.marker);
            if (at < 0) continue;
            ImageSlot slot = token.slot;
            slot.text = text;
            slot.span = new FormulaSpan();
            updateFormulaSize(slot);
            body.replace(at, at + token.marker.length(), "\uFFFC");
            body.setSpan(slot.span, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(new ImageClickSpan(this, slot), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setText(body, TextView.BufferType.SPANNABLE);
        if (!formulas.isEmpty()) {
            text.setMovementMethod(new FormulaMovement());
            text.setHighlightColor(Color.TRANSPARENT);
            // Ordinary option text must still reach the enclosing answer row.
            text.setClickable(false);
            text.setLongClickable(false);
            text.setFocusable(false);
        }
        readingText.add(text);
        target.addView(text, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void appendBlockImage(String ref, LinearLayout target, int widthHint) {
        ImageSlot slot = new ImageSlot(++nextImageId, ref, false, widthHint);
        images.add(slot);
        FrameLayout frame = new FrameLayout(getContext());
        slot.frame = frame;
        frame.setBackgroundColor(0xfff5f5f5);
        frame.setMinimumHeight(dp(64));
        slot.image = new ImageView(getContext());
        slot.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        slot.image.setContentDescription("资料图片，点按放大");
        frame.addView(slot.image, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        slot.label = new TextView(getContext());
        slot.label.setText("图表");
        slot.label.setTextColor(Color.BLACK);
        slot.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        slot.label.setGravity(Gravity.CENTER);
        slot.label.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame.addView(slot.label, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        frame.setOnClickListener(new ImageClick(this, slot));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(140));
        lp.topMargin = dp(compact ? 2 : 4);
        lp.bottomMargin = dp(compact ? 2 : 8);
        target.addView(frame, lp);
    }

    private void appendTable(String source) {
        NativeTableModel model = NativeTableModel.parse(source);
        if (model == null) {
            String flat = source.replaceAll("(?is)</tr\\s*>", "<br>")
                    .replaceAll("(?is)</t[dh]\\s*>", "　")
                    .replaceAll("(?is)</?(?:table|caption|tbody|thead|tfoot|tr|td|th)\\b[^>]*>", "");
            appendRich(flat, this, 0);
            return;
        }
        Matcher caption = Pattern.compile("(?is)<caption\\b[^>]*>(.*?)</caption\\s*>").matcher(source);
        if (caption.find()) appendRich(caption.group(1), this, 0);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellWidth = Math.max(dp(100), Math.min(dp(230), (screenWidth - dp(40)) / Math.min(model.columns, 4)));
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setHorizontalScrollBarEnabled(true);
        NativeTableLayout table = new NativeTableLayout(getContext(), model, cellWidth, dp(32));
        for (NativeTableModel.Cell spec : model.cells) {
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(7), dp(4), dp(7), dp(4));
            GradientDrawable border = new GradientDrawable();
            border.setColor(spec.heading ? 0xffeeeeee : Color.WHITE);
            border.setStroke(dp(1), 0xff888888);
            cell.setBackground(border);
            appendRich(spec.html, cell, cellWidth * spec.columnSpan - dp(14));
            table.addView(cell, new ViewGroup.LayoutParams(cellWidth * spec.columnSpan, LayoutParams.WRAP_CONTENT));
        }
        scroll.addView(table, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(8);
        addView(scroll, lp);
    }

    /** TableRow cannot span rows. Measure each cell once, then lay out true merged rectangles. */
    private static final class NativeTableLayout extends ViewGroup {
        final NativeTableModel model;
        final int columnWidth, minimumRowHeight;
        final int[] rowHeights, rowTops;

        NativeTableLayout(Context context, NativeTableModel model, int columnWidth, int minimumRowHeight) {
            super(context);
            this.model = model;
            this.columnWidth = columnWidth;
            this.minimumRowHeight = minimumRowHeight;
            rowHeights = new int[model.rows];
            rowTops = new int[model.rows + 1];
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            java.util.Arrays.fill(rowHeights, minimumRowHeight);
            for (int i = 0; i < model.cells.size(); i++) {
                NativeTableModel.Cell spec = model.cells.get(i);
                View cell = getChildAt(i);
                cell.measure(MeasureSpec.makeMeasureSpec(columnWidth * spec.columnSpan, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (spec.rowSpan == 1) rowHeights[spec.row] = Math.max(rowHeights[spec.row], cell.getMeasuredHeight());
            }
            for (int i = 0; i < model.cells.size(); i++) {
                NativeTableModel.Cell spec = model.cells.get(i);
                if (spec.rowSpan == 1) continue;
                int available = 0;
                for (int r = spec.row; r < spec.row + spec.rowSpan; r++) available += rowHeights[r];
                int extra = Math.max(0, getChildAt(i).getMeasuredHeight() - available);
                for (int r = 0; r < spec.rowSpan; r++)
                    rowHeights[spec.row + r] += extra / spec.rowSpan + (r < extra % spec.rowSpan ? 1 : 0);
            }
            rowTops[0] = 0;
            for (int r = 0; r < model.rows; r++) rowTops[r + 1] = rowTops[r] + rowHeights[r];
            for (int i = 0; i < model.cells.size(); i++) {
                NativeTableModel.Cell spec = model.cells.get(i);
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(columnWidth * spec.columnSpan, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowTops[spec.row + spec.rowSpan] - rowTops[spec.row], MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(resolveSize(columnWidth * model.columns, widthSpec), resolveSize(rowTops[model.rows], heightSpec));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < model.cells.size(); i++) {
                NativeTableModel.Cell spec = model.cells.get(i);
                getChildAt(i).layout(columnWidth * spec.column, rowTops[spec.row],
                        columnWidth * (spec.column + spec.columnSpan), rowTops[spec.row + spec.rowSpan]);
            }
        }
    }

    private static String attribute(String source, String name) {
        Pattern pattern = Pattern.compile("(?is)(?:^|\\s)" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s'\"=<>`]+))");
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return "";
        for (int i = 1; i <= 3; i++) if (matcher.group(i) != null) return matcher.group(i);
        return "";
    }

    @SuppressWarnings("deprecation")
    private static String inlineImageTags(String source) {
        Matcher matcher = HTML_IMAGE.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String ref = Html.fromHtml(attribute(matcher.group(1), "src")).toString();
            String replacement = ref.length() == 0 ? "[图片缺少本地引用]" : "[[img:" + ref + "]]";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String cleanRef(String ref) {
        String value = ref == null ? "" : ref.trim();
        if (value.startsWith("[[img:") && value.endsWith("]]")) value = value.substring(6, value.length() - 2).trim();
        return value;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observeVisibility();
        scheduleVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        stopObserving();
        generation++;
        if (decodeJob != null) { decodeJob.cancel(true); decodeJob = null; }
        removeCallbacks(visibilityWork);
        visibilityPosted = false;
        if (overlay != null) { overlay.close(); overlay = null; }
        for (ImageSlot slot : images) { slot.loading = false; releaseBitmap(slot); }
        DECODER.purge();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth) for (ImageSlot slot : images) {
            if (slot.formula) { updateFormulaSize(slot); invalidateFormula(slot); }
            else updateBlockSize(slot);
        }
        scheduleVisibility();
    }

    private void observeVisibility() {
        if (released || observedTree != null || getWindowToken() == null) return;
        observedTree = getViewTreeObserver();
        observedTree.addOnScrollChangedListener(scrollListener);
        for (android.view.ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) {
                observedPane = (View) parent;
                observedPane.addOnLayoutChangeListener(paneLayoutListener);
                break;
            }
        }
    }

    private void stopObserving() {
        if (observedPane != null) { observedPane.removeOnLayoutChangeListener(paneLayoutListener); observedPane = null; }
        if (observedTree != null && observedTree.isAlive()) observedTree.removeOnScrollChangedListener(scrollListener);
        observedTree = null;
    }

    private void scheduleVisibility() {
        if (released || visibilityPosted || getWindowToken() == null) return;
        visibilityPosted = true;
        postOnAnimation(visibilityWork);
    }

    private void updateVisibleImages() {
        if (released || document == null || getWidth() <= 0) return;
        for (ImageSlot slot : images) if (slot.bitmap != null && !nearViewport(slot, 2)) releaseBitmap(slot);
        if (decodeJob != null) return;
        for (ImageSlot slot : images) {
            if (slot.failed || slot.loading || slot.bitmap != null || !nearViewport(slot, 1)) continue;
            slot.loading = true;
            decodeJob = DECODER.submit(new DecodeTask(this, slot, document, generation, wantedWidth(slot)));
            break;
        }
    }

    /** Long passages remain lazy: wait only for images in the actual viewport. */
    boolean viewportReady() {
        if(released||!isShown())return true;
        if(isLayoutRequested())return false;
        for(ImageSlot slot:images)
            if(nearViewport(slot,0)&&slot.bitmap==null&&!slot.failed) {scheduleVisibility();return false;}
        return true;
    }

    private boolean nearViewport(ImageSlot slot, int marginScreens) {
        Rect viewport = new Rect();
        if (!isShown() || !getGlobalVisibleRect(viewport)) return false;
        int[] location = new int[2];
        int top, bottom;
        if (slot.formula) {
            if (slot.text == null || slot.span == null || slot.text.getLayout() == null) return false;
            CharSequence value = slot.text.getText();
            if (!(value instanceof Spanned)) return false;
            int offset = ((Spanned) value).getSpanStart(slot.span);
            if (offset < 0) return false;
            Layout layout = slot.text.getLayout();
            int line = layout.getLineForOffset(offset);
            slot.text.getLocationOnScreen(location);
            if (location[0] + slot.text.getWidth() < viewport.left - dp(32)
                    || location[0] > viewport.right + dp(32)) return false;
            top = location[1] + slot.text.getTotalPaddingTop() + layout.getLineTop(line) - slot.text.getScrollY();
            bottom = location[1] + slot.text.getTotalPaddingTop() + layout.getLineBottom(line) - slot.text.getScrollY();
        } else {
            if (slot.frame == null) return false;
            slot.frame.getLocationOnScreen(location);
            if (location[0] + slot.frame.getWidth() < viewport.left - dp(32)
                    || location[0] > viewport.right + dp(32)) return false;
            top = location[1];
            bottom = top + slot.frame.getHeight();
        }
        int margin = Math.max(dp(64), viewport.height()) * marginScreens;
        return bottom >= viewport.top - margin && top <= viewport.bottom + margin;
    }

    private int wantedWidth(ImageSlot slot) {
        int width = slot.widthHint;
        if (slot.formula && slot.text != null && slot.text.getWidth() > 0) {
            width = slot.text.getWidth() - slot.text.getTotalPaddingLeft() - slot.text.getTotalPaddingRight();
        } else if (slot.frame != null && slot.frame.getWidth() > 0) width = slot.frame.getWidth();
        if (width <= 0) width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - dp(32);
        return Math.max(dp(24), Math.min(2400, width));
    }

    /** Inline illustrations leave reading space in either orientation and split layout. */
    private int blockImageHeight() {
        int available = 0;
        for (android.view.ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) {
                View pane = (View) parent;
                available = pane.getHeight() - pane.getPaddingTop() - pane.getPaddingBottom();
                break;
            }
        }
        if (available <= 0) available = getResources().getDisplayMetrics().heightPixels - dp(64);
        int maximum = Math.round(available * (compact ? .18f : .55f));
        if (compact) maximum = Math.min(maximum, dp(120));
        return Math.max(dp(24), maximum);
    }

    private void acceptImage(ImageSlot slot, int token, Decoded decoded, String error) {
        if (released || token != generation) { discard(decoded); return; }
        decodeJob = null;
        slot.loading = false;
        if (decoded == null) {
            slot.failed = true;
            slot.error = error == null ? "图片无法读取" : error;
            if (slot.formula) {
                slot.span.error = true;
                updateFormulaSize(slot);
                invalidateFormula(slot);
            } else {
                slot.label.setText(slot.error + "\n点按重试");
                slot.label.setVisibility(VISIBLE);
                slot.frame.getLayoutParams().height = dp(80);
                slot.frame.requestLayout();
            }
        } else {
            slot.originalWidth = decoded.width;
            slot.originalHeight = decoded.height;
            slot.bitmap = decoded.bitmap;
            if (slot.formula) {
                slot.span.bitmap = decoded.bitmap;
                slot.span.error = false;
                updateFormulaSize(slot);
                invalidateFormula(slot);
            } else {
                slot.image.setImageBitmap(decoded.bitmap);
                slot.label.setVisibility(GONE);
                slot.frame.setBackgroundColor(Color.WHITE);
                updateBlockSize(slot);
            }
            if (!nearViewport(slot, 2)) releaseBitmap(slot);
        }
        scheduleVisibility();
    }

    private void updateBlockSize(ImageSlot slot) {
        if (slot.frame == null || slot.originalWidth <= 0 || slot.originalHeight <= 0) return;
        double width = wantedWidth(slot);
        int maximumHeight = blockImageHeight();
        // Avoid magnifying small bitmaps or growing beyond the portrait reading width.
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        width = Math.max(1, Math.min(width, Math.min(metrics.widthPixels, metrics.heightPixels) - dp(32)));
        width = Math.min(width, slot.originalWidth);
        width = Math.min(width, (double) maximumHeight * slot.originalWidth / slot.originalHeight);
        int height = (int) Math.min(100000L,
                Math.max(dp(24), Math.round(width * slot.originalHeight / slot.originalWidth)));
        if (slot.frame.getMinimumHeight() != 0) slot.frame.setMinimumHeight(0);
        if (slot.frame.getLayoutParams().height != height) {
            slot.frame.getLayoutParams().height = height;
            slot.frame.requestLayout();
        }
    }

    private void updateFormulaSize(ImageSlot slot) {
        if (slot.span == null || slot.text == null) return;
        float textPixels = slot.text.getTextSize();
        if (slot.span.error) {
            slot.span.width = Math.min(wantedWidth(slot), Math.round(textPixels * 8));
            slot.span.height = Math.round(textPixels * 1.5f);
            return;
        }
        if (slot.originalWidth <= 0 || slot.originalHeight <= 0) {
            slot.span.width = Math.round(textPixels * 2);
            slot.span.height = Math.round(textPixels * 1.2f);
            return;
        }
        // Formula exports may be high-DPI bitmaps. Their pixel height must not turn
        // a small inline fraction into a three-line illustration. Use the same
        // compact range in stems and options; the original stays available on tap.
        float height = Math.min(textPixels * 1.45f,
                Math.max(textPixels, slot.originalHeight * textPixels / 24f));
        float width = height * slot.originalWidth / slot.originalHeight;
        if (width > wantedWidth(slot)) { height *= wantedWidth(slot) / width; width = wantedWidth(slot); }
        slot.span.width = Math.max(1, Math.round(width));
        slot.span.height = Math.max(1, Math.round(height));
    }

    private void invalidateFormula(ImageSlot slot) {
        if (slot.text == null) return;
        // setText invalidates TextView's cached line layout after a ReplacementSpan changes size.
        CharSequence text = slot.text.getText();
        slot.text.setText(text, TextView.BufferType.SPANNABLE);
        slot.text.requestLayout();
        slot.text.invalidate();
    }

    private void releaseBitmap(ImageSlot slot) {
        slot.bitmap = null;
        if (slot.image != null) slot.image.setImageDrawable(null);
        if (slot.span != null) { slot.span.bitmap = null; if (slot.text != null) slot.text.invalidate(); }
        if (slot.label != null && !slot.failed) {
            slot.label.setText("图表");
            slot.label.setVisibility(VISIBLE);
        }
        // Displayed bitmaps may still be referenced by Android's render display list. Clearing
        // references is safe; manually recycling those bitmaps can crash a later draw on API 22.
    }

    private void openImage(ImageSlot slot) {
        if (released || document == null) return;
        if (slot.failed) {
            slot.failed = false;
            slot.error = null;
            if (slot.span != null) { slot.span.error = false; invalidateFormula(slot); }
            scheduleVisibility();
            return;
        }
        if (slot.bitmap == null) {
            Toast.makeText(getContext(), "图片正在加载，请稍候", Toast.LENGTH_SHORT).show();
            scheduleVisibility();
            return;
        }
        Activity activity = activityOf(getContext());
        if (activity == null || activity.isFinishing()) return;
        if (overlay != null) overlay.close();
        overlay = new Overlay(this, activity, slot);
        overlay.show(document, generation);
    }

    private static Activity activityOf(Context context) {
        for (int i = 0; i < 16 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return null;
    }

    private static Decoded decode(NativeRepository.Document document, String ref,
            int width, int budget) throws Exception {
        String lower = ref.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("//")
                || lower.startsWith("content:") || lower.startsWith("file:")) {
            throw new IOException("仅支持资料包中的本地图片");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        File file = document.image(ref);
        if (file == null || !file.isFile()) throw new IOException("图片文件不存在");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("当前系统无法解码这张图片");
        if (bounds.outWidth > 100000 || bounds.outHeight > 100000) throw new IOException("图片尺寸过大，无法安全显示");
        int sample = 1;
        while (bounds.outWidth / (sample * 2L) >= width
                || (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > budget) sample *= 2;
        for (int attempt = 0; attempt < 3; attempt++, sample *= 2) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) throw new IOException("当前系统无法解码这张图片");
                if (Thread.currentThread().isInterrupted()) { bitmap.recycle(); throw new InterruptedException(); }
                return new Decoded(bitmap, bounds.outWidth, bounds.outHeight);
            } catch (OutOfMemoryError pressure) {
                if (attempt == 2) throw new IOException("可用内存不足，请关闭图片后重试");
            }
        }
        throw new IOException("图片无法读取");
    }

    private static String imageError(Exception error) {
        if (error instanceof IOException && error.getMessage() != null
                && error.getMessage().length() <= 70) return error.getMessage();
        return "图片无法读取";
    }

    private static void discard(Decoded decoded) {
        if (decoded != null && decoded.bitmap != null && !decoded.bitmap.isRecycled()) decoded.bitmap.recycle();
    }

    private static final class Decoded {
        final Bitmap bitmap;
        final int width, height;
        Decoded(Bitmap bitmap, int width, int height) { this.bitmap = bitmap; this.width = width; this.height = height; }
    }

    private static final class ImageSlot {
        final int id, widthHint;
        final String ref;
        final boolean formula;
        Bitmap bitmap;
        int originalWidth, originalHeight;
        boolean loading, failed;
        String error;
        FrameLayout frame;
        ImageView image;
        TextView label, text;
        FormulaSpan span;
        ImageSlot(int id, String ref, boolean formula, int widthHint) {
            this.id = id; this.ref = ref; this.formula = formula; this.widthHint = widthHint;
        }
    }

    private static final class InlineToken {
        final String marker;
        final ImageSlot slot;
        InlineToken(String marker, ImageSlot slot) { this.marker = marker; this.slot = slot; }
    }

    private static final class DecodeTask implements Runnable {
        final WeakReference<NativeContentView> owner;
        final WeakReference<ImageSlot> target;
        final NativeRepository.Document document;
        final int token, width;
        final String ref;
        DecodeTask(NativeContentView owner, ImageSlot target, NativeRepository.Document document, int token, int width) {
            this.owner = new WeakReference<NativeContentView>(owner);
            this.target = new WeakReference<ImageSlot>(target);
            this.document = document; this.token = token; this.width = width; this.ref = target.ref;
        }
        @Override public void run() {
            NativeContentView initial = owner.get();
            if (initial == null || initial.released || initial.generation != token) return;
            initial = null;
            Decoded image = null;
            String error = null;
            try { image = decode(document, ref, width, IMAGE_PIXELS); }
            catch (Exception failure) { error = imageError(failure); }
            final Decoded result = image;
            final String problem = error;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    NativeContentView view = owner.get();
                    ImageSlot slot = target.get();
                    if (view == null || slot == null || view.released || view.generation != token) discard(result);
                    else view.acceptImage(slot, token, result, problem);
                }
            });
        }
    }

    private static final class ImageClick implements OnClickListener {
        final WeakReference<NativeContentView> owner;
        final WeakReference<ImageSlot> target;
        ImageClick(NativeContentView view, ImageSlot slot) {
            owner = new WeakReference<NativeContentView>(view); target = new WeakReference<ImageSlot>(slot);
        }
        @Override public void onClick(View ignored) {
            NativeContentView view = owner.get(); ImageSlot slot = target.get();
            if (view != null && slot != null) view.openImage(slot);
        }
    }

    private static final class ImageClickSpan extends ClickableSpan {
        final ImageClick click;
        ImageClickSpan(NativeContentView view, ImageSlot slot) { click = new ImageClick(view, slot); }
        @Override public void onClick(View widget) { click.onClick(widget); }
        @Override public void updateDrawState(TextPaint paint) { paint.setUnderlineText(false); }
    }

    /** Consume only touches that started on a formula; leave answer-row text clickable by its parent. */
    private static final class FormulaMovement extends LinkMovementMethod {
        private ClickableSpan pressed;

        @Override public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressed = hit(widget, buffer, event);
                return pressed != null;
            }
            if (pressed == null) return false;
            if (action == MotionEvent.ACTION_UP) {
                ClickableSpan target = pressed;
                pressed = null;
                if (target == hit(widget, buffer, event)) target.onClick(widget);
            } else if (action == MotionEvent.ACTION_CANCEL) pressed = null;
            return true;
        }

        private static ClickableSpan hit(TextView widget, Spannable buffer, MotionEvent event) {
            Layout layout = widget.getLayout();
            if (layout == null) return null;
            float x = event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
            float y = event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
            if (y < 0 || y > layout.getHeight()) return null;
            int line = layout.getLineForVertical((int) y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
            int offset = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = buffer.getSpans(Math.max(0, offset - 1),
                    Math.min(buffer.length(), offset + 1), ClickableSpan.class);
            for (ClickableSpan span : spans) {
                int start = buffer.getSpanStart(span), end = buffer.getSpanEnd(span);
                if (layout.getLineForOffset(start) != line) continue;
                float left = layout.getPrimaryHorizontal(start), right = layout.getPrimaryHorizontal(end);
                if (x >= Math.min(left, right) && x <= Math.max(left, right)) return span;
            }
            return null;
        }
    }

    private static final class FormulaSpan extends ReplacementSpan {
        Bitmap bitmap;
        int width = 30, height = 24;
        boolean error;
        final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint labelPaint = new Paint();
        final RectF imageBounds = new RectF();
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                int center = (paint.getFontMetricsInt().ascent + paint.getFontMetricsInt().descent) / 2;
                fm.ascent = Math.min(fm.ascent, center - height / 2);
                fm.descent = Math.max(fm.descent, fm.ascent + height);
                fm.top = fm.ascent; fm.bottom = fm.descent;
            }
            return width;
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            float imageTop = y + (paint.ascent() + paint.descent()) / 2f - height / 2f;
            if (bitmap != null && !bitmap.isRecycled()) {
                imageBounds.set(x, imageTop, x + width, imageTop + height);
                canvas.drawBitmap(bitmap, null, imageBounds, imagePaint);
            } else {
                labelPaint.set(paint);
                labelPaint.setStyle(Paint.Style.FILL);
                labelPaint.setColor(Color.BLACK);
                labelPaint.setTextSize(Math.min(paint.getTextSize(), height * .7f));
                canvas.drawText(error ? "[图片不可读]" : "[图]", x, y, labelPaint);
            }
        }
    }

    /** A native dialog keeps the page underneath intact; its decode is bounded just like inline images. */
    private static final class Overlay {
        final WeakReference<NativeContentView> owner;
        final Dialog dialog;
        final LinearLayout root;
        final ZoomImageView zoom;
        final TextView status;
        final String ref;
        Future<?> job;
        boolean closed;

        Overlay(NativeContentView content, Activity activity, ImageSlot slot) {
            owner = new WeakReference<NativeContentView>(content);
            ref = slot.ref;
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new LinearLayout(activity);
            root.setOrientation(VERTICAL);
            root.setBackgroundColor(Color.WHITE);
            LinearLayout bar = new LinearLayout(activity);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(button(activity, "×", "关闭图片", 0), new LinearLayout.LayoutParams(content.dp(48), content.dp(48)));
            TextView title = new TextView(activity);
            title.setText("图片");
            title.setTextColor(Color.BLACK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            bar.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            bar.addView(button(activity, "−", "缩小图片", 1), new LinearLayout.LayoutParams(content.dp(48), content.dp(48)));
            bar.addView(button(activity, "+", "放大图片", 2), new LinearLayout.LayoutParams(content.dp(48), content.dp(48)));
            bar.addView(button(activity, "适应", "适应屏幕", 3), new LinearLayout.LayoutParams(content.dp(64), content.dp(48)));
            root.addView(bar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            status = new TextView(activity);
            status.setText("正在加载清晰图…");
            status.setTextColor(Color.BLACK);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            status.setPadding(content.dp(12), content.dp(4), content.dp(12), content.dp(4));
            root.addView(status, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            zoom = new ZoomImageView(activity);
            zoom.setBitmap(slot.bitmap, false);
            zoom.setContentDescription("图片，可双指缩放或用顶部按钮缩放，放大后拖动查看");
            root.addView(zoom, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
            dialog.setContentView(root);
            dialog.setOnDismissListener(new OverlayDismiss(this));
        }

        private Button button(Context context, String text, String description, int action) {
            Button button = new Button(context);
            button.setText(text);
            button.setContentDescription(description);
            button.setTextColor(Color.BLACK);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            button.setBackgroundColor(Color.WHITE);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(new OverlayAction(this, action));
            return button;
        }

        void show(NativeRepository.Document document, int token) {
            NativeContentView content = owner.get();
            if (content == null || content.released) { close(); return; }
            try {
                int target = Math.min(3200, Math.max(content.getResources().getDisplayMetrics().widthPixels,
                        content.getResources().getDisplayMetrics().heightPixels) * 2);
                job = DECODER.submit(new ZoomDecodeTask(this, document, token, target));
            } catch (RuntimeException failure) {
                close();
                Toast.makeText(content.getContext().getApplicationContext(), "暂时无法打开图片", Toast.LENGTH_SHORT).show();
            }
        }

        void accept(Decoded image, String error, int token) {
            NativeContentView content = owner.get();
            if (closed || content == null || content.released || content.generation != token) { discard(image); return; }
            job = null;
            if (image == null) {
                status.setText((error == null ? "清晰图加载失败" : error) + "，当前显示预览图");
                status.setVisibility(VISIBLE);
            } else {
                zoom.setBitmap(image.bitmap, true);
                status.setVisibility(GONE);
            }
            try {
                Window window=dialog.getWindow();
                if(window!=null){window.setWindowAnimations(0);window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));}
                dialog.show();
                if(window!=null)window.setLayout(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT);
            }catch(RuntimeException failure){close();}
        }

        void close() {
            finishDismissal();
            try { dialog.dismiss(); } catch (RuntimeException ignored) { }
        }

        void finishDismissal() {
            if (closed) return;
            closed = true;
            if (job != null) { job.cancel(true); job = null; }
            DECODER.purge();
            dialog.setOnDismissListener(null);
            zoom.setBitmap(null, false);
            root.removeAllViews();
            NativeContentView content = owner.get();
            if (content != null && content.overlay == this) content.overlay = null;
        }
    }

    private static final class OverlayAction implements OnClickListener {
        final WeakReference<Overlay> owner;
        final int action;
        OverlayAction(Overlay overlay, int action) { owner = new WeakReference<Overlay>(overlay); this.action = action; }
        @Override public void onClick(View ignored) {
            Overlay overlay = owner.get();
            if (overlay == null || overlay.closed) return;
            if (action == 0) overlay.close();
            else if (action == 1) overlay.zoom.zoomBy(0.8f);
            else if (action == 2) overlay.zoom.zoomBy(1.25f);
            else overlay.zoom.fit();
        }
    }

    private static final class OverlayDismiss implements android.content.DialogInterface.OnDismissListener {
        final WeakReference<Overlay> owner;
        OverlayDismiss(Overlay overlay) { owner = new WeakReference<Overlay>(overlay); }
        @Override public void onDismiss(android.content.DialogInterface ignored) {
            Overlay overlay = owner.get();
            if (overlay != null) overlay.finishDismissal();
        }
    }

    private static final class ZoomDecodeTask implements Runnable {
        final WeakReference<Overlay> owner;
        final NativeRepository.Document document;
        final String ref;
        final int token, width;
        ZoomDecodeTask(Overlay overlay, NativeRepository.Document document, int token, int width) {
            owner = new WeakReference<Overlay>(overlay);
            this.document = document; this.ref = overlay.ref; this.token = token; this.width = width;
        }
        @Override public void run() {
            Overlay initial = owner.get();
            if (initial == null || initial.closed) return;
            initial = null;
            Decoded image = null;
            String error = null;
            try { image = decode(document, ref, width, ZOOM_PIXELS); }
            catch (Exception failure) { error = imageError(failure); }
            final Decoded result = image;
            final String problem = error;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    Overlay overlay = owner.get();
                    if (overlay == null) discard(result);
                    else overlay.accept(result, problem, token);
                }
            });
        }
    }

    /** No giant backing canvas: draw the sampled bitmap through a transform and clip to this View. */
    private static final class ZoomImageView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final ScaleGestureDetector scaleDetector;
        Bitmap bitmap;
        float scale = 1, minimum = 1, translateX, translateY, lastX, lastY;
        boolean dragging;

        ZoomImageView(Context context) {
            super(context);
            setBackgroundColor(Color.WHITE);
            setFocusable(true);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    changeScale(scale * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    return true;
                }
            });
        }

        void setBitmap(Bitmap value, boolean preserve) {
            float factor = minimum > 0 ? scale / minimum : 1;
            float centerX = .5f, centerY = .5f;
            if (bitmap != null && scale > 0) {
                centerX = (getWidth() / 2f - translateX) / (bitmap.getWidth() * scale);
                centerY = (getHeight() / 2f - translateY) / (bitmap.getHeight() * scale);
            }
            bitmap = value;
            if (value == null) { invalidate(); return; }
            minimum = fittingScale();
            scale = minimum * (preserve ? Math.max(1, Math.min(8, factor)) : 1);
            translateX = getWidth() / 2f - value.getWidth() * scale * (preserve ? centerX : .5f);
            translateY = getHeight() / 2f - value.getHeight() * scale * (preserve ? centerY : .5f);
            clampTranslation();
            invalidate();
        }

        float fittingScale() {
            if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return 1;
            return Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        }

        void fit() {
            if (bitmap == null) return;
            minimum = fittingScale();
            scale = minimum;
            translateX = (getWidth() - bitmap.getWidth() * scale) / 2f;
            translateY = (getHeight() - bitmap.getHeight() * scale) / 2f;
            invalidate();
        }

        void zoomBy(float factor) { changeScale(scale * factor, getWidth() / 2f, getHeight() / 2f); }

        void changeScale(float requested, float focusX, float focusY) {
            if (bitmap == null) return;
            float next = Math.max(minimum, Math.min(minimum * 8, requested));
            float ratio = next / scale;
            translateX = focusX - (focusX - translateX) * ratio;
            translateY = focusY - (focusY - translateY) * ratio;
            scale = next;
            clampTranslation();
            invalidate();
        }

        void clampTranslation() {
            if (bitmap == null) return;
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            translateX = width <= getWidth() ? (getWidth() - width) / 2f : Math.max(getWidth() - width, Math.min(0, translateX));
            translateY = height <= getHeight() ? (getHeight() - height) / 2f : Math.max(getHeight() - height, Math.min(0, translateY));
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { fit(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || bitmap.isRecycled()) return;
            int save = canvas.save();
            canvas.translate(translateX, translateY);
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restoreToCount(save);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null) return false;
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX(); lastY = event.getY(); dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (dragging && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    translateX += event.getX() - lastX;
                    translateY += event.getY() - lastY;
                    clampTranslation();
                    invalidate();
                }
                lastX = event.getX(); lastY = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                dragging = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}


