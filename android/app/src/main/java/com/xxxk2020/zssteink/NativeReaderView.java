package com.xxxk2020.zssteink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewConfiguration;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Traditional Android reader. The document and progress stores are never read on the UI thread. */
final class NativeReaderView extends FrameLayout {
    private static final Pattern MATERIAL_IMAGES = Pattern.compile(
            "(?is)\\[\\[img:([^\\]\\r\\n]+)\\]\\]|<img\\b[^>]*?\\s+src\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>");
    interface Listener {
        void onExit();
        NativeReadingSettingsView createReadingSettings();
        void onError(String message);
    }
    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    private Activity activity;
    private Listener listener;
    private final NativeRepository.Document document;
    private final NativeProgress progress;
    private final NativeTextStyle textStyle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout base, contentArea, questionBody, materialPane, materialHead;
    private final ScrollView questionScroll, materialScroll;
    private final ReadingPagePane questionPane, materialReadingPane;
    private final FrameLayout materialDivider;
    private final View dividerPreview, dividerLine, dividerGrip;
    private boolean splitSideBySide;
    private float materialSideSplit = .54f;
    private float materialSplit = .42f;
    private boolean dividerTracking, dividerDragging;
    private final TextView previousButton, nextButton, positionButton, fontButton, starButton;
    private final LinearLayout readingTabs;
    private final TextView materialTab, questionTab;
    private List<ReadingQuestion> readingIndex;
    private final TextView materialLabel, materialFocusButton, materialCollapseButton;
    private final List<NativeContentView> questionContents = new ArrayList<NativeContentView>();
    private final List<View> optionViews = new ArrayList<View>();
    private NativeContentView materialContent;
    private TextView answerAnchor;
    private FrameLayout overlay;
    private AlertDialog confirmation;
    private NativeReadingSettingsView readingSettings;
    private NativeProgress.State state;
    private JSONObject positionRecord = new JSONObject(), materialPositions = new JSONObject();
    private JSONObject pendingPosition;
    private Page current;
    private String mode, materialKey = "";
    private int generation = 0, overlayGeneration = 0;
    private int qi = 0, si = 0, mi = 0, answerY = -1;
    private String readView = "q";
    private boolean released, busy, materialCollapsed, materialFocused, materialTouched, materialFocusTouched, showingResult;
    private int materialReturnX, materialReturnY;
    private int desiredQuestionY, desiredMaterialX, desiredMaterialY;
    private boolean questionTouched;
    private View.OnLayoutChangeListener questionRestoreListener;
    private final Runnable saveAfterScroll = new Runnable() { @Override public void run() { queuePositionSave(); } };
    private final ViewTreeObserver.OnScrollChangedListener scrollObserver = new ViewTreeObserver.OnScrollChangedListener() {
        @Override public void onScrollChanged() {
            if (released) return;
            questionPane.invalidateIndicator(); materialReadingPane.invalidateIndicator();
            if (busy || state == null) return;
            main.removeCallbacks(saveAfterScroll); main.postDelayed(saveAfterScroll, 300);
        }
    };

    private static final class Page {
        int qi, si, mi, questionCount, sectionCount, materialCount;
        String view;
        JSONObject question, section, material, shared, inlineMaterial;
    }
    private static final class ReadingQuestion {
        final int index; final String number, title; final List<Integer> materials;
        ReadingQuestion(int index, String number, String title, List<Integer> materials) {
            this.index = index; this.number = number; this.title = title; this.materials = materials;
        }
    }
    private static final class Loaded {
        final String mode; final NativeProgress.State state; final Page page;
        Loaded(String mode, NativeProgress.State state, Page page) { this.mode = mode; this.state = state; this.page = page; }
    }
    private static final class CardCell {
        final int index, status; final String label, choice;
        CardCell(int index, int status, String label, String choice) { this.index = index; this.status = status; this.label = label; this.choice = choice; }
    }
    private static final class CardRow {
        final int type, index; final String title; final List<CardCell> cells;
        CardRow(int type, int index, String title, List<CardCell> cells) { this.type = type; this.index = index; this.title = NativeDisplayText.clean(title); this.cells = cells; }
    }

    NativeReaderView(Activity activity, NativeRepository.Document document, NativeProgress progress,
                     NativeTextStyle textStyle, String mode, Listener listener) {
        super(activity);
        this.activity = activity; this.document = document; this.progress = progress;
        this.textStyle = textStyle; this.mode = mode; this.listener = listener;
        setBackgroundColor(Color.WHITE);
        base = vertical(); addView(base, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = horizontal(); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        // Equal side regions keep the question number at the screen centre.
        LinearLayout leftTools = horizontal();
        TextView exit = tool("×", "关闭面板或退出当前资料"); exit.setTextSize(25); exit.setOnClickListener(v -> { if (overlay != null) closeOverlay(); else requestExit(); });
        leftTools.addView(exit, new LinearLayout.LayoutParams(dp(44), dp(48)));
        toolbar.addView(leftTools, new LinearLayout.LayoutParams(dp(88), dp(48)));
        FrameLayout navigationSlot = new FrameLayout(getContext());
        toolbar.addView(navigationSlot, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout navigation = new LinearLayout(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                        ? dp(176) : Math.min(dp(176), MeasureSpec.getSize(widthSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        navigation.setOrientation(LinearLayout.HORIZONTAL); navigation.setBaselineAligned(false);
        navigationSlot.addView(navigation, new FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER));
        previousButton = tool("<", "上一题"); previousButton.setTextSize(23); previousButton.setOnClickListener(v -> navigate(-1));
        navigation.addView(previousButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        positionButton = tool("…", "打开答题卡"); positionButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        positionButton.setPadding(0, 0, 0, 0);
        positionButton.setOnClickListener(v -> showCard());
        navigation.addView(positionButton, new LinearLayout.LayoutParams(0, dp(48), 2));
        nextButton = tool(">", "下一题"); nextButton.setTextSize(23); nextButton.setOnClickListener(v -> navigate(1));
        navigation.addView(nextButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        fontButton = tool("Aa", "阅读设置：字体、字号、字重、方向与全屏"); fontButton.setOnClickListener(v -> showFonts());
        toolbar.addView(fontButton, new LinearLayout.LayoutParams(dp(44), dp(48)));
        starButton = tool("☆", "收藏当前题目"); starButton.setTextSize(25); starButton.setOnClickListener(v -> toggleStar());
        toolbar.addView(starButton, new LinearLayout.LayoutParams(dp(44), dp(48)));
        base.addView(toolbar); base.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        readingTabs = horizontal(); readingTabs.setGravity(Gravity.CENTER); readingTabs.setBaselineAligned(false);
        materialTab = tool("材料", "切换到材料，保留阅读位置");
        questionTab = tool("题目", "切换到题目，保留阅读位置");
        materialTab.setOnClickListener(v -> switchReading("mat")); questionTab.setOnClickListener(v -> switchReading("q"));
        readingTabs.addView(materialTab, new LinearLayout.LayoutParams(dp(96), dp(44)));
        readingTabs.addView(questionTab, new LinearLayout.LayoutParams(dp(96), dp(44)));
        readingTabs.setVisibility(GONE); base.addView(readingTabs);
        contentArea = vertical(); base.addView(contentArea, new LinearLayout.LayoutParams(-1, 0, 1));

        materialPane = vertical(); materialPane.setVisibility(GONE);
        materialHead = horizontal(); materialHead.setGravity(Gravity.CENTER_VERTICAL);
        materialLabel = tool("共用材料", "共用材料"); materialLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); materialLabel.setTextSize(14);
        materialHead.addView(materialLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        materialFocusButton = tool("全屏", "全屏阅读材料"); materialFocusButton.setTextSize(14); materialFocusButton.setOnClickListener(v -> toggleMaterialFocus());
        materialHead.addView(materialFocusButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        materialCollapseButton = tool("收起", "收起材料"); materialCollapseButton.setTextSize(14); materialCollapseButton.setOnClickListener(v -> toggleMaterialCollapse());
        materialHead.addView(materialCollapseButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        materialPane.addView(materialHead); materialPane.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        materialScroll = new ScrollView(activity); materialScroll.setFillViewport(false); materialScroll.setBackgroundColor(Color.WHITE);
        materialScroll.setVerticalScrollBarEnabled(false);
        materialScroll.setPadding(dp(8), dp(4), dp(8), dp(4)); materialScroll.setClipToPadding(false);
        materialReadingPane = new ReadingPagePane(true);
        materialReadingPane.addView(materialScroll, new FrameLayout.LayoutParams(-1, -1));
        materialPane.addView(materialReadingPane, new LinearLayout.LayoutParams(-1, 0, 1));
        contentArea.addView(materialPane, new LinearLayout.LayoutParams(-1, 0, .42f));
        materialDivider = new FrameLayout(getContext()); materialDivider.setVisibility(GONE);
        dividerLine = line(); materialDivider.addView(dividerLine, new FrameLayout.LayoutParams(-1, dp(1), Gravity.CENTER));
        dividerGrip = line(); materialDivider.addView(dividerGrip, new FrameLayout.LayoutParams(dp(42), dp(3), Gravity.CENTER));
        materialDivider.setContentDescription("上下拖动预览分屏位置，松手调整比例，点按恢复默认比例");
        materialDivider.setFocusable(true);
        contentArea.addView(materialDivider, new LinearLayout.LayoutParams(-1, dp(28)));
        questionPane = new ReadingPagePane(false);
        questionScroll = new ScrollView(activity); questionScroll.setFillViewport(false); questionScroll.setClipToPadding(false);
        questionScroll.setVerticalScrollBarEnabled(false);
        questionScroll.setPadding(dp(8), dp(6), dp(8), dp(8));
        questionBody = vertical(); questionScroll.addView(questionBody, new ScrollView.LayoutParams(-1, -2));
        questionPane.addView(questionScroll, new FrameLayout.LayoutParams(-1, -1));
        contentArea.addView(questionPane, new LinearLayout.LayoutParams(-1, 0, 1));
        // This overlay only moves by translation; the text panes stay still until release.
        dividerPreview = line(); dividerPreview.setVisibility(INVISIBLE);
        dividerPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(dividerPreview, new FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP));
        configureDivider();
        materialScroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                materialTouched = true; if (materialFocused && event.getActionMasked() == MotionEvent.ACTION_MOVE) materialFocusTouched = true;
            }
            return false;
        });
        questionScroll.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN) questionTouched = true; return false; });
        questionScroll.getViewTreeObserver().addOnScrollChangedListener(scrollObserver);
        materialScroll.getViewTreeObserver().addOnScrollChangedListener(scrollObserver);
        setBusy(true);showMessage("正在准备页面…");
    }

    void open() { openAtFavorite(null); }
    void openFavorite(String number) { openAtFavorite(number); }
    private void openAtFavorite(final String favoriteNumber) {
        if (released) return;
        final int token = ++generation; setBusy(true); closeOverlay();
        holdFrame(()->current!=null,null);
        final String requested = mode;
        task(token, () -> {
            String actual = "note".equals(document.item.shape) || "shenlun".equals(document.item.shape) ? "read" : requested;
            if (!"drill".equals(actual) && !"practice".equals(actual) && !"read".equals(actual)) actual = progress.resumeMode(document.item);
            NativeProgress.State loaded = progress.load(document.item, actual);
            JSONObject p = loaded.position;
            int question=p.optInt("i",p.optInt("qi",0));String view=p.optString("view","mat");
            if(favoriteNumber!=null) {
                question=-1;
                for(int i=0;i<document.questionCount();i++) {
                    if(favoriteNumber.equals(String.valueOf(questionNo(document.question(i))))) {question=i;break;}
                }
                if(question<0)throw new java.io.IOException("收藏的题号已不在当前资料中，原收藏和学习进度已保留");
                view="q";
            }
            Page page = loadPage(actual, question, p.optInt("si", 0), p.optInt("mi", 0), view);
            return new Loaded(actual, loaded, page);
        }, result -> {
            mode = result.mode; state = result.state; positionRecord = copyObject(state.position);
            materialPositions = copyObject(positionRecord.optJSONObject("materialPositions"));
            materialCollapsed = positionRecord.optBoolean("materialCollapsed", false);
            materialSplit = safeSplit(positionRecord.optDouble("materialSplit", .42));
            materialSideSplit = safeSplit(positionRecord.optDouble("materialSideSplit", .54));
            display(result.page, favoriteNumber==null);
        });
    }

    private Page loadPage(String actualMode, int q, int s, int m, String view) throws Exception {
        Page page = new Page(); page.questionCount = document.questionCount(); page.sectionCount = document.sectionCount();
        page.qi = clamp(q, page.questionCount); page.si = clamp(s, page.sectionCount); page.mi = m;
        if ("read".equals(actualMode) && "note".equals(document.item.shape)) {
            page.view = "note"; page.section = page.sectionCount > 0 ? document.section(page.si) : new JSONObject();
        } else if ("read".equals(actualMode)) {
            ensureReadingIndex();
            page.materialCount = document.materialCount(); page.mi = clamp(m, page.materialCount);
            page.view = page.materialCount > 0 && !"q".equals(view) ? "mat" : "q";
            page.material = "mat".equals(page.view) ? document.material(page.mi) : null;
            page.question = page.questionCount > 0 ? document.question(page.qi) : new JSONObject();
        } else {
            page.view = "q"; page.question = page.questionCount > 0 ? document.question(page.qi) : new JSONObject();
            page.shared = page.questionCount > 0 ? document.sharedMaterial(page.qi) : null;
            if (page.shared == null) page.inlineMaterial = inlineMaterial(page.question);
        }
        return page;
    }

    private boolean isShenlun() { return "shenlun".equals(document.item.shape); }
    /** Retain only small navigation metadata, never all passage bodies. Called on the IO worker. */
    private void ensureReadingIndex() throws Exception {
        if (readingIndex != null) return;
        List<ReadingQuestion> result = new ArrayList<>();
        for (int i = 0; i < document.questionCount(); i++) {
            JSONObject question = document.question(i); List<Integer> refs = new ArrayList<>();
            JSONArray values = question.optJSONArray("mats");
            if (values != null) for (int j = 0; j < values.length(); j++) {
                int at = document.materialIndex(values.opt(j)); if (at >= 0 && !refs.contains(at)) refs.add(at);
            }
            // Old papers without explicit references allow browsing all materials.
            String title = question.optString("title", "");
            if (title.length() == 0 && !question.optString("type").matches("[0-9]+")) title = question.optString("type");
            result.add(new ReadingQuestion(i, String.valueOf(questionNo(question)), NativeDisplayText.clean(title), refs));
        }
        readingIndex = result;
    }
    private void switchReading(String view) {
        if (busy || current == null || view.equals(readView)) return;
        if ("mat".equals(view) && current.materialCount == 0) { toast("这份资料没有独立材料"); return; }
        move(qi, si, mi, view);
    }

    private void display(Page page, boolean restoreQuestion) {
        if (released) return;
        current = page; qi = page.qi; si = page.si; mi = page.mi; readView = page.view;
        pendingPosition = null; showingResult = false; closeOverlay(); clearQuestion();
        positionButton.setText("note".equals(readView) ? (si + 1) + "/" + page.sectionCount :
                "mat".equals(readView) ? (mi + 1) + "/" + page.materialCount : (page.questionCount > 0 ? qi + 1 : 0) + "/" + page.questionCount);
        if (isShenlun()) {
            positionButton.setText(("mat".equals(readView) ? "材料 " : "题目 ") +
                    ("mat".equals(readView) ? (mi + 1) + "/" + page.materialCount :
                            (page.questionCount > 0 ? String.valueOf(questionNo(page.question)) : "0") + "/" + page.questionCount));
            positionButton.setTextSize(14);
        }
        readingTabs.setVisibility(isShenlun() ? VISIBLE : GONE);
        materialTab.setEnabled(page.materialCount > 0);
        materialTab.setTypeface(Typeface.DEFAULT, "mat".equals(readView) ? Typeface.BOLD : Typeface.NORMAL);
        questionTab.setTypeface(Typeface.DEFAULT, "q".equals(readView) ? Typeface.BOLD : Typeface.NORMAL);
        materialTab.setContentDescription("材料" + ("mat".equals(readView) ? "，当前页面" : ""));
        questionTab.setContentDescription("题目" + ("q".equals(readView) ? "，当前页面" : ""));
        positionButton.setContentDescription(("read".equals(mode) ? "打开目录，" : "打开答题卡，") + positionButton.getText());
        previousButton.setContentDescription("note".equals(readView) ? "上一节" : "mat".equals(readView) ? "上一则材料" : "上一题");
        nextButton.setContentDescription("note".equals(readView) ? "下一节" : "mat".equals(readView) ? "下一则材料" : "下一题");
        renderMaterial(page.shared);
        if ("note".equals(readView)) renderNote(page.section);
        else if ("read".equals(mode)) renderReading(page);
        else renderQuestion(page.question);
        updateStar();
        final int token = generation;
        final int y = restoreQuestion ? savedQuestionY() : 0;
        desiredQuestionY = y; questionTouched = false;
        questionRestoreListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!released && token == generation && !questionTouched) questionScroll.scrollTo(0, y);
        };
        questionBody.addOnLayoutChangeListener(questionRestoreListener);
        questionScroll.post(() -> { if (!released && token == generation) questionScroll.scrollTo(0, y); });
        holdFrame(this::viewportReady,()->{if(!released&&token==generation)setBusy(false);});
    }

    private EinkFrameGate frameGate;
    private void holdFrame(EinkFrameGate.Ready ready,Runnable done) {
        if(frameGate!=null)frameGate.cancel();
        frameGate=new EinkFrameGate(this,ready,done);
    }
    private boolean viewportReady() {
        if(materialContent!=null&&!materialContent.viewportReady())return false;
        for(NativeContentView content:questionContents)if(!content.viewportReady())return false;
        return true;
    }

    private void renderMaterial(JSONObject shared) {
        if (shared == null) { clearMaterial(); return; }
        String key = shared.optString("key", "");
        if (!key.equals(materialKey) || materialContent == null) {
            captureMaterial(); clearMaterial(); materialKey = key; materialTouched = false;
            materialContent = new NativeContentView(getContext(), document, textStyle);
            materialScroll.addView(materialContent, new ScrollView.LayoutParams(-1, -2));
            materialContent.render(text(shared.opt("text")), array(shared.optJSONArray("images")));
            final NativeContentView content = materialContent; final String restoringKey = key;
            final JSONObject saved = materialPositions.optJSONObject(key);
            final int x = saved == null ? 0 : dp(saved.optDouble("x", 0));
            final int y = saved == null ? 0 : dp(saved.optDouble("y", 0));
            desiredMaterialX = x; desiredMaterialY = y;
            content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (!released && materialContent == content && restoringKey.equals(materialKey) && !materialTouched && !materialCollapsed) materialScroll.scrollTo(x, y);
            });
            content.post(() -> { if (!released && content == materialContent && !materialTouched) materialScroll.scrollTo(x, y); });
        }
        materialLabel.setText(NativeDisplayText.title(shared.optString("label", "共用材料"), "共用材料")); layoutMaterial();
    }

    private void layoutMaterial() {
        cancelDividerDrag();
        splitSideBySide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && !"stacked".equals(getContext().getSharedPreferences("reader-layout", Context.MODE_PRIVATE).getString("landscapeSplit", "side"));
        contentArea.setOrientation(splitSideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        materialDivider.setLayoutParams(new LinearLayout.LayoutParams(splitSideBySide ? dp(28) : -1, splitSideBySide ? -1 : dp(28)));
        dividerLine.setLayoutParams(new FrameLayout.LayoutParams(splitSideBySide ? dp(1) : -1, splitSideBySide ? -1 : dp(1), Gravity.CENTER));
        dividerGrip.setLayoutParams(new FrameLayout.LayoutParams(splitSideBySide ? dp(3) : dp(42), splitSideBySide ? dp(42) : dp(3), Gravity.CENTER));
        boolean present = materialContent != null;
        materialPane.setVisibility(present ? VISIBLE : GONE);
        materialDivider.setVisibility(present && !materialCollapsed && !materialFocused ? VISIBLE : GONE);
        materialScroll.setVisibility(materialCollapsed ? GONE : VISIBLE);
        materialReadingPane.setVisibility(materialCollapsed ? GONE : VISIBLE);
        materialFocusButton.setText(materialFocused ? "看题" : "全屏");
        materialCollapseButton.setText(materialCollapsed ? "展开" : "收起");
        if (!present) {
            questionPane.setVisibility(VISIBLE); questionPane.setLayoutParams(paneWeight(1)); return;
        }
        questionPane.setVisibility(materialFocused ? GONE : VISIBLE);
        if (materialCollapsed && splitSideBySide) {
            // A collapsed material header stays above the full-width question pane.
            contentArea.setOrientation(LinearLayout.VERTICAL);
            materialPane.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46)));
            questionPane.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            float split = splitSideBySide ? materialSideSplit : materialSplit;
            materialPane.setLayoutParams(materialCollapsed ? new LinearLayout.LayoutParams(-1, dp(46)) : paneWeight(materialFocused ? 1 : split));
            questionPane.setLayoutParams(paneWeight(materialCollapsed ? 1 : 1 - split));
        }
    }
    private LinearLayout.LayoutParams paneWeight(float weight) {
        return splitSideBySide ? new LinearLayout.LayoutParams(0, -1, weight) : new LinearLayout.LayoutParams(-1, 0, weight);
    }
    private float activeSplit() { return splitSideBySide ? materialSideSplit : materialSplit; }

    void readingLayoutChanged() { readingLayoutChanged(false); }
    void readingLayoutChanged(boolean dismissSettings) {
        if (released) return;
        final float q = scrollFraction(questionScroll), m = scrollFraction(materialScroll);
        final int token = generation;
        questionTouched = true; materialTouched = true;
        if(dismissSettings)closeOverlay(); layoutMaterial();
        contentArea.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                contentArea.removeOnLayoutChangeListener(this);
                contentArea.post(() -> {
                    if (released || token != generation) return;
                    restoreFraction(questionScroll, q); restoreFraction(materialScroll, m);
                    desiredQuestionY = questionScroll.getScrollY(); desiredMaterialY = materialScroll.getScrollY();
                    queuePositionSave();
                });
            }
        });
        contentArea.requestLayout();
    }
    private float scrollFraction(ScrollView scroll) {
        if (scroll.getChildCount() == 0) return 0;
        int range = scroll.getChildAt(0).getHeight() - scroll.getHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom();
        return range <= 0 ? 0 : Math.max(0, Math.min(1, (float)scroll.getScrollY() / range));
    }
    private void restoreFraction(ScrollView scroll, float value) {
        if (scroll.getChildCount() == 0) return;
        int range = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom());
        scroll.scrollTo(scroll.getScrollX(), Math.round(range * value));
    }

    private float safeSplit(double value) { return Double.isNaN(value) || Double.isInfinite(value) ? .42f : (float)Math.max(.12, Math.min(.88, value)); }

    private void configureDivider() {
        materialDivider.setOnClickListener(v -> applyMaterialSplit(splitSideBySide ? .54f : .42f));
        materialDivider.setOnTouchListener(new View.OnTouchListener() {
            float down, pendingSplit, minimum, maximum, origin, previewX, previewY;
            int available, dividerSize;
            boolean horizontal;
            final int[] location = new int[2];
            final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            private void preview(float raw) {
                pendingSplit = safeSplit(Math.max(minimum, Math.min(maximum, (raw - origin - dividerSize / 2f) / available)));
                dividerPreview.setTranslationX(horizontal ? previewX + available * pendingSplit + dividerSize / 2f - dp(1) : previewX);
                dividerPreview.setTranslationY(horizontal ? previewY : previewY + available * pendingSplit + dividerSize / 2f - dp(1));
            }
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (busy || released || materialContent == null || materialCollapsed || materialFocused) { cancelDividerDrag(); return false; }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelDividerDrag(); horizontal = splitSideBySide;
                    dividerSize = horizontal ? materialDivider.getWidth() : materialDivider.getHeight();
                    available = (horizontal ? contentArea.getWidth() : contentArea.getHeight()) - dividerSize;
                    if (available <= 0) return false;
                    contentArea.getLocationOnScreen(location); origin = location[horizontal ? 0 : 1];
                    int cx = location[0], cy = location[1]; NativeReaderView.this.getLocationOnScreen(location);
                    previewX = cx - location[0]; previewY = cy - location[1];
                    dividerPreview.setLayoutParams(new FrameLayout.LayoutParams(horizontal ? dp(2) : contentArea.getWidth(), horizontal ? contentArea.getHeight() : dp(2), Gravity.TOP | Gravity.LEFT));
                    minimum = Math.min(.4f, (float)dp(horizontal ? 110 : 76) / available);
                    maximum = 1 - Math.min(.4f, (float)dp(horizontal ? 110 : 96) / available);
                    down = horizontal ? event.getRawX() : event.getRawY(); pendingSplit = activeSplit(); dividerTracking = true;
                    contentArea.requestDisallowInterceptTouchEvent(true); return true;
                }
                if (!dividerTracking) return true;
                if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) { cancelDividerDrag(); return true; }
                float raw = horizontal ? event.getRawX() : event.getRawY();
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!dividerDragging && Math.abs(raw - down) > touchSlop) { dividerDragging = true; dividerPreview.setVisibility(VISIBLE); }
                    if (dividerDragging) preview(raw); return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    boolean dragged = dividerDragging; if (dragged) preview(raw); cancelDividerDrag();
                    if (dragged) applyMaterialSplit(pendingSplit); else view.performClick(); return true;
                }
                return true;
            }
        });
    }

    private void cancelDividerDrag() {
        dividerTracking = false; dividerDragging = false;
        dividerPreview.setVisibility(INVISIBLE);
        contentArea.requestDisallowInterceptTouchEvent(false);
    }

    private void applyMaterialSplit(float value) {
        float split = safeSplit(value);
        if (Math.abs(split - activeSplit()) < .003f) return;
        captureMaterial(); materialTouched = true; questionTouched = true;
        if (splitSideBySide) materialSideSplit = split; else materialSplit = split;
        layoutMaterial(); queuePositionSave();
    }

    /** Observe taps over the text; drags, selection and real controls keep their normal events. */
    private final class ReadingPagePane extends FrameLayout {
        final boolean material;
        float downX, downY; long started; boolean edgeTap;
        final Paint indicatorPaint = new Paint();
        ReadingPagePane(boolean material) {
            super(NativeReaderView.this.getContext()); this.material = material;
            indicatorPaint.setColor(Color.DKGRAY);
        }
        void invalidateIndicator() {
            // Only this narrow strip needs an additional redraw when the child scrolls.
            invalidate(Math.max(0, getWidth() - dp(5)), 0, getWidth(), getHeight());
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            ScrollView scroll = material ? materialScroll : questionScroll;
            if (scroll == null || scroll.getChildCount() == 0) return;
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            int content = scroll.getChildAt(0).getHeight();
            int range = content - viewport;
            // A short page has no indicator. Layout changes also update this calculation.
            if (viewport <= 0 || range <= 0) return;
            float top = scroll.getTop() + dp(2), track = scroll.getHeight() - dp(4);
            if (track <= 0) return;
            float height = Math.min(track, Math.max(dp(20), track * viewport / content));
            int offset = Math.max(0, Math.min(range, scroll.getScrollY()));
            float y = top + (track - height) * offset / range;
            float right = getWidth() - dp(2);
            // The parent is stationary: the thumb stays at the screen edge, outside text padding.
            canvas.drawRect(right - dp(3), y, right, y + height, indicatorPaint);
        }
        boolean ownsEdge;
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); started = event.getEventTime();
                ownsEdge = downX >= getWidth() - dp(44);
                edgeTap = ownsEdge && canHandlePageKeys();
                // The complete gesture belongs to the strip, even over an option or at a page boundary.
                if (ownsEdge) { requestDisallowInterceptTouchEvent(true); return true; }
            }
            if (!ownsEdge) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE) {
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) edgeTap = false;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) edgeTap = false;
            else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                boolean flip = action == MotionEvent.ACTION_UP && edgeTap && event.getPointerCount() == 1
                        && canHandlePageKeys() && event.getEventTime() - started < ViewConfiguration.getLongPressTimeout();
                ownsEdge = false; edgeTap = false; requestDisallowInterceptTouchEvent(false);
                if (flip) scrollReadingPage(material ? materialScroll : questionScroll, downY < getHeight() / 2f ? -1 : 1);
            }
            return true;
        }
    }

    private void toggleMaterialFocus() {
        if (busy || materialContent == null) return;
        if (materialFocused) { closeMaterialFocus(); return; }
        captureMaterial(); materialReturnX = materialScroll.getScrollX(); materialReturnY = materialScroll.getScrollY();
        materialFocused = true; materialCollapsed = false; materialFocusTouched = false; layoutMaterial();
    }
    private boolean closeMaterialFocus() {
        if (!materialFocused) return false;
        final int x = materialFocusTouched ? materialScroll.getScrollX() : materialReturnX;
        final int y = materialFocusTouched ? materialScroll.getScrollY() : materialReturnY;
        materialFocused = false; layoutMaterial(); materialScroll.post(() -> { if (!released) materialScroll.scrollTo(x, y); }); return true;
    }
    private void toggleMaterialCollapse() {
        if (busy || materialContent == null) return;
        captureMaterial(); materialFocused = false; materialCollapsed = !materialCollapsed; layoutMaterial();
        JSONObject saved = materialPositions.optJSONObject(materialKey);
        if (!materialCollapsed && saved != null) {
            final int x = dp(saved.optDouble("x", 0)), y = dp(saved.optDouble("y", 0));
            materialScroll.post(() -> { if (!released && !materialCollapsed) materialScroll.scrollTo(x, y); });
        }
        queuePositionSave();
    }

    private void renderNote(JSONObject section) {
        if (section == null) section = new JSONObject();
        String title = NativeDisplayText.clean(section.optString("title", "")); if (title.length() > 0) questionBody.addView(label(title, true));
        addContent(text(section.opt("text")), section.optJSONArray("imgs"));
    }

    private void renderReading(Page page) {
        if ("mat".equals(page.view)) {
            JSONObject material = page.material == null ? new JSONObject() : page.material;
            questionBody.addView(label("材料 " + NativeDisplayText.clean(material.optString("no", String.valueOf(mi + 1))), true));
            addContent(text(material.opt("text")), material.optJSONArray("imgs"));
            if (readingIndex != null) {
                questionBody.addView(label("相关题目", true));
                for (ReadingQuestion item : readingIndex) if (item.materials.isEmpty() || item.materials.contains(mi)) {
                    TextView link = action("第 " + item.number + " 题" + (item.title.length() > 0 ? " · " + item.title : ""));
                    link.setOnClickListener(v -> move(item.index, si, mi, "q")); questionBody.addView(link);
                }
            }
            return;
        }
        JSONObject question = page.question;
        if (page.questionCount == 0) { questionBody.addView(label("这份资料暂无题目", false)); return; }
        questionBody.addView(label("第 " + String.valueOf(questionNo(question)) + " 题", true));
        String requirement = question.optString("req", "");
        if (question.has("score")) requirement = question.optString("score") + " 分  " + requirement;
        if (question.has("limit")) requirement += "  " + question.optString("limit") + " 字以内";
        if (requirement.trim().length() > 0) questionBody.addView(label(requirement, false));
        addContent(question.optString("parentStem", ""), null);
        addContent(text(question.opt("stem")), question.optJSONArray("imgs"));
        if (page.materialCount > 0 && readingIndex != null && qi < readingIndex.size()) {
            questionBody.addView(label("依据材料", true));
            List<Integer> refs = readingIndex.get(qi).materials;
            if (refs.isEmpty()) { refs = new ArrayList<>(); for (int i = 0; i < page.materialCount; i++) refs.add(i); }
            addMaterialLinks(questionBody, refs);
        }
        addDisclosure("参考答案", text(question.opt("ref")), null);
        addDisclosure("解析", text(question.opt("explain")), question.optJSONArray("eimgs"));
        addDisclosure("知识拓展", text(question.opt("extension")), null);
        TextView done = action(isDone(questionNo(question)) ? "✓ 已练过，点此取消标记" : "标记已练过"); done.setOnClickListener(v -> toggleDone()); questionBody.addView(done);
    }

    private void addMaterialLinks(LinearLayout target, List<Integer> indices) {
        LinearLayout row = null; int column = 0;
        for (Integer at : indices) {
            if (column % 4 == 0) { row = horizontal(); row.setBaselineAligned(false); target.addView(row); }
            final int material = at;
            String number; try { number = String.valueOf(document.materialNumber(at)); } catch (Exception ignored) { number = String.valueOf(at + 1); }
            TextView link = tool("材料 " + number, "查看材料 " + number);
            link.setOnClickListener(v -> move(qi, si, material, "mat"));
            row.addView(link, new LinearLayout.LayoutParams(0, dp(44), 1)); column++;
        }
        if (row != null) while (column++ % 4 != 0) row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, dp(44), 1));
    }

    private void renderQuestion(JSONObject question) {
        if (current.questionCount == 0) { questionBody.addView(label("这份资料暂无题目", false)); return; }
        if (current.shared == null) {
            JSONObject material = current.inlineMaterial;
            if (material != null) addContent(material.optString("text"), material.optJSONArray("images"));
        }
        addContent(text(question.opt("stem")), question.optJSONArray("imgs"));
        JSONArray options = array(question.optJSONArray("options")), optionImages = array(question.optJSONArray("oimgs"));
        int chosen = chosen(question), answer = answerIndex(question);
        boolean reveal = state.submitted || ("practice".equals(mode) && chosen >= 0);
        for (int i = 0; i < options.length(); i++) {
            final int index = i; int status = 0;
            if (reveal && answer >= 0) { if (i == answer) status = 2; else if (i == chosen) status = 3; }
            else if (i == chosen) status = 1;
            LinearLayout row = vertical(); row.setMinimumHeight(dp(44));
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Android padding includes the reserved 2dp stroke: content inset remains 4dp / 2dp.
            row.setPadding(dp(6), dp(4), dp(6), dp(4)); row.setBackground(stroke(status));
            NativeContentView content = new NativeContentView(getContext(), document, textStyle); content.setCompact(true); questionContents.add(content);
            String option = text(options.opt(i)); String prefix = option.length() == 1 && "ABCDEFGH".contains(option) ? "" : letter(i) + ". ";
            JSONArray refs = new JSONArray(); if (!optionImages.isNull(i)) refs.put(optionImages.opt(i));
            content.render(prefix + option, refs); row.addView(content, new LinearLayout.LayoutParams(-1, -2));
            if (status == 3) row.addView(label("你选的", false));
            row.setTag(Integer.valueOf(status)); row.setContentDescription("选项 " + letter(i) + (status == 2 ? "，正确答案" : status == 3 ? "，你选的，错误" : status == 1 ? "，已选择" : ""));
            row.setOnClickListener(v -> choose(index)); optionViews.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(2); questionBody.addView(row, lp);
            if (status == 1 || status == 2) forceBold(row);
        }
        answerY = -1;
        if (reveal && (answer >= 0 || question.optString("explain", "").length() > 0 || array(question.optJSONArray("eimgs")).length() > 0)) {
            TextView answerLabel = label(answer >= 0 ? "正确答案：" + letter(answer) : "参考答案与解析", true); questionBody.addView(answerLabel);
            answerAnchor = answerLabel;
            answerLabel.post(() -> { if (!released && answerLabel.getParent() == questionBody) answerY = answerLabel.getTop(); });
            addContent(text(question.opt("explain")), question.optJSONArray("eimgs"));
        }
    }

    private NativeContentView addContent(String text, JSONArray images) {
        if ((text == null || text.length() == 0) && (images == null || images.length() == 0)) return null;
        NativeContentView content = new NativeContentView(getContext(), document, textStyle); questionContents.add(content);
        questionBody.addView(content, new LinearLayout.LayoutParams(-1, -2)); content.render(text == null ? "" : text, array(images)); return content;
    }
    private void addDisclosure(String title, String text, JSONArray images) {
        if (text.length() == 0 && (images == null || images.length() == 0)) return;
        final String headingTitle = NativeDisplayText.clean(title);
        TextView heading = action(headingTitle + "  +"); questionBody.addView(heading);
        LinearLayout slot = vertical(); slot.setVisibility(GONE); questionBody.addView(slot);
        final String key = qi + ":" + title;
        Runnable populate = () -> {
            if (slot.getChildCount() == 0) {
                NativeContentView content = new NativeContentView(getContext(), document, textStyle); questionContents.add(content);
                slot.addView(content, new LinearLayout.LayoutParams(-1, -2)); content.render(text, array(images));
            }
        };
        JSONObject opened = positionRecord.optJSONObject("open");
        if (opened != null && opened.optBoolean(key)) { populate.run(); slot.setVisibility(VISIBLE); heading.setText(headingTitle + "  −"); }
        heading.setOnClickListener(v -> {
            boolean open = slot.getVisibility() != VISIBLE;
            if (open) populate.run();
            slot.setVisibility(open ? VISIBLE : GONE); heading.setText(headingTitle + (open ? "  −" : "  +"));
            try { JSONObject values = copyObject(positionRecord.optJSONObject("open")); values.put(key, open); positionRecord.put("open", values); } catch (Exception ignored) {}
            queuePositionSave();
        });
    }

    boolean canHandlePageKeys() {
        if (released || busy || dividerTracking || current == null || state == null || overlay != null || showingResult
                || (confirmation != null && confirmation.isShowing())) return false;
        View focused = findFocus();
        return focused == null || (!focused.onCheckIsTextEditor()
                && !(focused instanceof TextView && ((TextView)focused).isTextSelectable()));
    }

    void handlePageKey(int direction) {
        if (!canHandlePageKeys() || (direction != -1 && direction != 1)) return;
        if (!materialFocused && !"read".equals(mode)) {
            navigate(direction);
            return;
        }
        scrollReadingPage(materialFocused ? materialScroll : questionScroll, direction);
    }

    private void scrollReadingPage(ScrollView scroll, int direction) {
        int height = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
        if (height <= 0) return;
        if (scroll == materialScroll) { materialTouched = true; if (materialFocused) materialFocusTouched = true; }
        else questionTouched = true;
        int step = Math.max(1, height - Math.max(dp(24), height / 10));
        // Immediate scrolling avoids extra E-ink refreshes. ScrollView clamps
        // at either end; a reading-page boundary never submits or changes a question.
        scroll.scrollBy(0, direction * step);
    }

    private void navigate(int delta) {
        if (busy || current == null) return;
        int target = "note".equals(readView) ? si + delta : "mat".equals(readView) ? mi + delta : qi + delta;
        int count = "note".equals(readView) ? current.sectionCount : "mat".equals(readView) ? current.materialCount : current.questionCount;
        if (target < 0 || target >= count) { toast(target < 0 ? "已经是第一项" : "已经是最后一项"); return; }
        move("q".equals(readView) ? target : qi, "note".equals(readView) ? target : si, "mat".equals(readView) ? target : mi, readView);
    }
    private void move(final int q, final int s, final int m, final String view) {
        if (busy || state == null) return;
        closeMaterialFocus(); final JSONObject position = makePosition(q, s, m, view, false);
        final String actual = mode; final int token = ++generation; pendingPosition = position; setBusy(true); closeOverlay();
        task(token, () -> { progress.savePosition(document.item.id, actual, position); return new Loaded(actual, progress.load(document.item, actual), loadPage(actual, q, s, m, view)); },
                result -> { state = result.state; positionRecord = copyObject(position); display(result.page, "read".equals(actual)); });
    }
    private void choose(final int index) {
        if (busy || current == null || current.question == null || "read".equals(mode)) return;
        if (("practice".equals(mode) && chosen(current.question) >= 0) || ("drill".equals(mode) && state.submitted)) return;
        final JSONObject answers = copyObject(state.answers); final String number = String.valueOf(questionNo(current.question));
        try { answers.put(number, index); } catch (Exception error) { report(error); return; }
        final int target = "drill".equals(mode) && qi + 1 < current.questionCount ? qi + 1 : qi;
        final boolean sameQuestion = target == qi;
        final JSONObject position = makePosition(target, si, mi, "q", target == qi); final String actual = mode;
        final int token = ++generation; pendingPosition = position; setBusy(true);
        task(token, () -> { progress.saveAnswer(document.item.id, actual, answers, position); return new Loaded(actual, progress.load(document.item, actual), loadPage(actual, target, si, mi, "q")); },
                result -> { state = result.state; positionRecord = copyObject(position); display(result.page, sameQuestion); });
    }

    private void toggleStar() {
        if (!busy && isShenlun() && "mat".equals(readView) && state != null) {
            final JSONObject position = makePosition(qi, si, mi, readView, true);
            JSONObject stars = copyObject(position.optJSONObject("materialStars"));
            try { stars.put(String.valueOf(mi), !stars.optBoolean(String.valueOf(mi))); position.put("materialStars", stars); }
            catch (Exception error) { report(error); return; }
            final int token = generation; setBusy(true);
            task(token, () -> { progress.savePosition(document.item.id, mode, position); return position; }, saved -> {
                positionRecord = copyObject(saved); setBusy(false); updateStar();
            });
            return;
        }
        if (busy || current == null || current.question == null || current.questionCount == 0) return;
        final Object number = questionNo(current.question); final int token = generation; final String actual = mode; setBusy(true);
        task(token, () -> { progress.toggleStar(document.item, number); return progress.load(document.item, actual); }, value -> { state = value; setBusy(false); updateStar(); });
    }
    private void toggleDone() {
        if (busy || current == null || current.question == null) return;
        final Object number = questionNo(current.question); final JSONArray done = new JSONArray(); boolean wasDone = false;
        for (int i = 0; i < state.done.length(); i++) { Object existing = state.done.opt(i); if (same(existing, number)) wasDone = true; else done.put(existing); }
        if (!wasDone) done.put(number);
        final JSONObject position = makePosition(qi, si, mi, readView, true); final int token = ++generation; setBusy(true); pendingPosition = position;
        task(token, () -> { progress.saveDone(document.item.id, done, position); return progress.load(document.item, mode); }, value -> { state = value; display(current, true); });
    }

    private void requestSubmit() {
        if (busy || state == null || !"drill".equals(mode)) return;
        if (state.submitted) { showResult(state.result); return; }
        final JSONObject answers = copyObject(state.answers); final int token = generation; setBusy(true);
        task(token, () -> grade(answers), result -> {
            setBusy(false); int blank = result.optInt("total") - result.optInt("answered");
            if (blank > 0) confirm("还有 " + blank + " 题未作答，仍要交卷吗？", "交卷", () -> commitSubmit(result));
            else commitSubmit(result);
        });
    }
    private JSONObject grade(JSONObject answers) throws Exception {
        int total = document.questionCount(), answered = 0, known = 0, correct = 0;
        for (int i = 0; i < total; i++) {
            JSONObject q = document.question(i); int selected = index(answers.opt(String.valueOf(questionNo(q)))), answer = answerIndex(q);
            if (selected >= 0) answered++; if (answer >= 0) { known++; if (selected == answer) correct++; }
        }
        return new JSONObject().put("total", total).put("answered", answered).put("known", known).put("correct", correct)
                .put("unknown", total - known).put("score", known == total && total > 0 ? (Object)Math.round(100f * correct / total) : JSONObject.NULL)
                .put("practice", known == 0).put("time", System.currentTimeMillis());
    }
    private void commitSubmit(final JSONObject result) {
        if (busy || released) return;
        final JSONObject position = makePosition(qi, si, mi, readView, true);
        final int token = ++generation; setBusy(true); pendingPosition = position;
        task(token, () -> {
            progress.submit(document.item.id, result, position);
            return progress.load(document.item, mode);
        }, value -> {
            state = value; positionRecord = copyObject(position); pendingPosition = null;
            setBusy(false); showResult(result);
        });
    }
    private void showResult(JSONObject result) {
        closeOverlay(); captureMaterial(); clearMaterial(); clearQuestion(); showingResult = true; answerY = -1;
        String title = !result.isNull("score") && result.has("score") ? "得分 " + result.optInt("score") + " 分" : "本轮作答";
        questionBody.addView(label(title, true));
        questionBody.addView(label("已答 " + result.optInt("answered") + "/" + result.optInt("total") + " 题", false));
        if (result.optInt("known") > 0) questionBody.addView(label("答对 " + result.optInt("correct") + "/" + result.optInt("known") + " 道可判分题", false));
        if (result.optInt("unknown") > 0) questionBody.addView(label("另有 " + result.optInt("unknown") + " 题未收录答案", false));
        LinearLayout secondary=horizontal();
        TextView card = action("查看答题卡");card.setTextSize(16);card.setOnClickListener(v -> showCard());secondary.addView(card,new LinearLayout.LayoutParams(0,-2,1));
        TextView answers = action("分组答案 / 复制");answers.setTextSize(16);answers.setOnClickListener(v -> showGroupedAnswers(false));secondary.addView(answers,new LinearLayout.LayoutParams(0,-2,1));
        questionBody.addView(secondary);
        TextView restart = action("开始新一轮");restart.setTextSize(16);restart.setOnClickListener(v -> newRound()); questionBody.addView(restart);
        questionScroll.scrollTo(0, 0);
        holdFrame(()->true,null);
    }

    private void newRound() {
        if (busy || state == null) return;
        confirm("本轮记录会保存在历史记录中，然后从头开始。", "开始新一轮", () -> {
            final String actual = mode; final int token = ++generation; setBusy(true); closeOverlay();
            task(token, () -> { progress.beginNewRound(document.item, actual); return progress.load(document.item, actual); }, value -> {
                state = value; positionRecord = new JSONObject(); materialPositions = new JSONObject(); materialCollapsed = false;
                clearMaterial(); setBusy(false); open();
            });
        });
    }

    private void showCard() {
        if (busy || current == null) return;
        if (isShenlun()) { showReadingDirectory(); return; }
        closeOverlay(); final int overlayToken = ++overlayGeneration, token = generation;
        final boolean showAnswers = !"read".equals(mode);
        LinearLayout panel = vertical(); panel.setBackgroundColor(Color.WHITE);
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = tool("×", "关闭答题卡"); close.setTextSize(25); close.setOnClickListener(v -> closeOverlay()); header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = label("read".equals(mode) ? "目录" : "答题卡", true); title.setTextSize(17); header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        String name = "drill".equals(mode) ? state.submitted ? "成绩" : "交卷" : "read".equals(mode) ? "mat".equals(readView) ? "看题目" : "看材料" : "答案";
        TextView primary = tool(name, name); primary.setOnClickListener(v -> {
            closeOverlay();
            if ("drill".equals(mode)) requestSubmit();
            else if ("read".equals(mode)) { if (!"note".equals(readView)) move(qi, si, mi, "mat".equals(readView) ? "q" : "mat"); }
            else if (answerY >= 0) { questionTouched = true; questionScroll.scrollTo(0, answerY); } else toast("请先作答");
        });
        if (!"note".equals(readView)) header.addView(primary, new LinearLayout.LayoutParams(dp(64), dp(44)));
        panel.addView(header); panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        if (showAnswers) {
            TextView legend = new TextView(getContext()); legend.setTextColor(Color.BLACK); legend.setTextSize(12);
            legend.setPadding(dp(8), dp(6), dp(8), dp(6));
            legend.setText(("practice".equals(mode) || state.submitted
                    ? "粗框 已答/正确 · 虚框 错误 · 细框 未答" : "粗框 已答 · 细框 未答") + "\n下行是我的答案");
            panel.addView(legend);
        }
        final TextView loading = label("正在读取目录…", false); panel.addView(loading);
        final ListView list = new ListView(getContext()); list.setDivider(null); list.setBackgroundColor(Color.WHITE);
        LinearLayout footer = vertical();
        TextView restart = action("开始新一轮"); restart.setOnClickListener(v -> newRound()); footer.addView(restart);
        list.addFooterView(footer, null, false); panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        if (showAnswers) {
            panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
            TextView grouped = tool("分组答案文本    查看 / 复制 ›", "查看并复制分组答案文本");
            grouped.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); grouped.setPadding(dp(10), 0, dp(10), 0);
            grouped.setOnClickListener(v -> showGroupedAnswers(true)); panel.addView(grouped, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        installOverlay(panel, true);
        holdFrame(()->loading.getVisibility()==GONE || !"正在读取目录…".contentEquals(loading.getText()),null);
        final NativeProgress.State snapshot = state; final String actual = mode;
        final int columns = showAnswers ? answerCardColumns() : Math.max(4, Math.min(7, getWidth() > 0 ? (getWidth() - dp(16)) / dp(50) : 5));
        try { worker.execute(() -> {
            try {
                final List<CardRow> rows = cardRows(actual, snapshot, columns);
                main.post(() -> {
                    if (released || generation != token || overlayGeneration != overlayToken || overlay == null) return;
                    loading.setVisibility(GONE); list.setAdapter(new CardAdapter(rows, columns, showAnswers));
                });
            } catch (Exception error) { main.post(() -> { if (!released && overlayGeneration == overlayToken) loading.setText(message(error)); }); }
        }); } catch (RejectedExecutionException ignored) {}
    }

    private void showReadingDirectory() {
        closeOverlay();
        LinearLayout panel = vertical(); panel.setBackgroundColor(Color.WHITE);
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tool("目录", "材料和题目目录"); title.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = tool("×", "关闭目录"); close.setTextSize(25); close.setOnClickListener(v -> closeOverlay());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(48))); panel.addView(header);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout body = vertical(); ScrollView scroll = new ScrollView(getContext()); scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (current.materialCount > 0) {
            body.addView(label("给定资料 · " + current.materialCount + " 则", true));
            List<Integer> indices = new ArrayList<>(); for (int i = 0; i < current.materialCount; i++) indices.add(i);
            addMaterialLinks(body, indices);
        }
        body.addView(label("题目 · 已练 " + state.done.length() + "/" + current.questionCount, true));
        if (readingIndex != null) for (ReadingQuestion item : readingIndex) {
            boolean done = contains(state.done, item.number);
            TextView link = action("第 " + item.number + " 题" + (item.title.length() > 0 ? " · " + item.title : "") + "　" + (done ? "✓ 已练" : "未练"));
            link.setOnClickListener(v -> move(item.index, si, mi, "q")); body.addView(link);
        }
        installOverlay(panel, true);
    }

    private int answerCardColumns() {
        int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        // Ten 44dp hit areas, outer padding and the wider gap between each five.
        return width >= dp(496) ? 10 : width >= dp(248) ? 5 : 4;
    }

    private void showGroupedAnswers(final boolean returnToCard) {
        if (released || busy || state == null || "read".equals(mode)) return;
        closeOverlay(); final int token = generation, overlayToken = overlayGeneration;
        final JSONObject answers = copyObject(state.answers);
        final int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        final int groupsPerLine = width >= dp(400) ? 2 : 1;
        LinearLayout panel = vertical(); panel.setBackgroundColor(Color.WHITE);
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = tool("‹", "返回"); back.setTextSize(25);
        back.setOnClickListener(v -> { if (returnToCard) showCard(); else closeOverlay(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = tool("分组答案", "分组答案"); title.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        final TextView copy = tool("复制", "复制全部分组作答"); copy.setEnabled(false);
        header.addView(copy, new LinearLayout.LayoutParams(dp(64), dp(44)));
        panel.addView(header); panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView description = new TextView(getContext()); description.setTextColor(Color.BLACK); description.setTextSize(14);
        description.setText("我的作答 · " + NativeDisplayText.title(document.item.title, "当前试卷") + "\n每 5 题一组，- 表示未答");
        description.setPadding(dp(12), dp(10), dp(12), dp(8)); panel.addView(description);
        final TextView content = new TextView(getContext()); content.setTextColor(Color.BLACK); content.setTextSize(17);
        content.setTypeface(Typeface.MONOSPACE); content.setLineSpacing(dp(8), 1f); content.setTextIsSelectable(true);
        content.setPadding(dp(12), dp(4), dp(12), dp(12)); content.setText("正在整理作答…");
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); installOverlay(panel, true);
        holdFrame(()->!"正在整理作答…".contentEquals(content.getText()),null);
        try { worker.execute(() -> {
            try {
                char[] choices = new char[document.questionCount()];
                for (int i = 0; i < choices.length; i++) {
                    JSONObject question = document.question(i);
                    int selected = index(answers.opt(String.valueOf(questionNo(question))));
                    choices[i] = selected >= 0 ? letter(selected).charAt(0) : '-';
                }
                final String text = NativeAnswerText.format(choices, groupsPerLine);
                main.post(() -> {
                    if (released || generation != token || overlayGeneration != overlayToken || overlay == null) return;
                    content.setText(text.length() == 0 ? "暂无作答题目" : text); copy.setEnabled(text.length() > 0);
                    copy.setOnClickListener(v -> {
                        try {
                            ClipboardManager clipboard = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard == null) { toast("无法使用剪贴板，可长按文本复制"); return; }
                            clipboard.setPrimaryClip(ClipData.newPlainText("我的作答", text)); toast("已复制全部分组作答");
                        } catch (RuntimeException error) { toast("复制失败，可长按文本复制"); }
                    });
                });
            } catch (Exception error) { main.post(() -> {
                if (!released && generation == token && overlayGeneration == overlayToken && overlay != null) content.setText(message(error));
            }); }
        }); } catch (RejectedExecutionException ignored) { }
    }

    private List<CardRow> cardRows(String actual, NativeProgress.State snapshot, int columns) throws Exception {
        List<CardRow> rows = new ArrayList<CardRow>();
        if ("note".equals(document.item.shape)) {
            for (int i = 0; i < document.sectionCount(); i++) { JSONObject section = document.section(i); rows.add(new CardRow(1, i, "第 " + (i + 1) + " 节  " + section.optString("title", ""), null)); }
            return rows;
        }
        if ("read".equals(actual)) {
            for (int i = 0; i < document.materialCount(); i++)
                rows.add(new CardRow(2, i, "材料 " + String.valueOf(document.materialNumber(i)), null));
        }
        List<CardCell> line = new ArrayList<CardCell>(); String lastType = "";
        for (int i = 0; i < document.questionCount(); i++) {
            JSONObject question = document.question(i); String type = question.optString("type", "");
            if (type.length() > 0 && !type.equals(lastType)) {
                if (!line.isEmpty()) { rows.add(new CardRow(3, -1, "", line)); line = new ArrayList<CardCell>(); }
                rows.add(new CardRow(0, -1, type, null)); lastType = type;
            }
            int chosen = index(snapshot.answers.opt(String.valueOf(questionNo(question)))), answer = answerIndex(question), status = 0;
            if ("read".equals(actual)) { if (contains(snapshot.done, questionNo(question))) status = 1; }
            else if (chosen >= 0) status = !"practice".equals(actual) && !snapshot.submitted || answer < 0 ? 1 : chosen == answer ? 2 : 3;
            line.add(new CardCell(i, status, String.valueOf(i + 1), chosen >= 0 ? letter(chosen) : "-"));
            if (line.size() == columns) { rows.add(new CardRow(3, -1, "", line)); line = new ArrayList<CardCell>(); }
        }
        if (!line.isEmpty()) rows.add(new CardRow(3, -1, "", line)); return rows;
    }

    private final class CardAdapter extends BaseAdapter {
        private final List<CardRow> rows; private final int columns; private final boolean showAnswers;
        CardAdapter(List<CardRow> rows, int columns, boolean showAnswers) { this.rows = rows; this.columns = columns; this.showAnswers = showAnswers; }
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position).type == 3 ? 1 : 0; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            final CardRow row = rows.get(position);
            if (row.type != 3) {
                TextView view = convertView instanceof TextView ? (TextView)convertView : action("");
                view.setText(row.title); view.setTypeface(Typeface.DEFAULT, row.type == 0 ? Typeface.BOLD : Typeface.NORMAL);
                view.setOnClickListener(row.type == 0 ? null : v -> { closeOverlay(); move(qi, row.type == 1 ? row.index : si, row.type == 2 ? row.index : mi, row.type == 1 ? "note" : "mat"); });
                return view;
            }
            LinearLayout grid;
            if (convertView instanceof LinearLayout) grid = (LinearLayout)convertView;
            else {
                grid = horizontal();
                // Invisible empty slots still participate in baseline alignment on
                // Android 5.1 and can push two-line cells below the row's bounds.
                grid.setBaselineAligned(false);
                grid.setPadding(dp(4), dp(2), dp(4), dp(2));
                for (int i = 0; i < columns; i++) {
                    TextView cell = tool("", ""); cell.setMaxLines(showAnswers ? 2 : 1); cell.setIncludeFontPadding(false);
                    cell.setPadding(dp(2), dp(2), dp(2), dp(2));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(showAnswers ? 54 : 48), 1);
                    lp.setMargins(dp(showAnswers && columns == 10 && i == 5 ? 10 : 2), 0, dp(2), 0); grid.addView(cell, lp);
                }
            }
            for (int i = 0; i < columns; i++) {
                TextView view = (TextView)grid.getChildAt(i);
                if (i >= row.cells.size()) { view.setVisibility(INVISIBLE); view.setOnClickListener(null); continue; }
                final CardCell cell = row.cells.get(i); view.setVisibility(VISIBLE); view.setBackground(cardStroke(cell.status));
                SpannableString text = new SpannableString(cell.label + (showAnswers ? "\n" + cell.choice : ""));
                if (cell.index == qi) text.setSpan(new UnderlineSpan(), 0, cell.label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                view.setText(text);
                view.setTypeface(Typeface.DEFAULT, cell.status > 0 ? Typeface.BOLD : Typeface.NORMAL);
                view.setPaintFlags(view.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
                view.setContentDescription("第 " + cell.label + " 题" + (showAnswers ? "，我的答案 " + ("-".equals(cell.choice) ? "未答" : cell.choice)
                        + (cell.status == 2 ? "，正确" : cell.status == 3 ? "，错误" : "") : "") + (cell.index == qi ? "，当前题" : ""));
                view.setOnClickListener(v -> { closeOverlay(); move(cell.index, si, mi, "q"); });
            }
            return grid;
        }
    }

    private void showFonts() {
        if(released||listener==null)return;
        if(readingSettings!=null){closeOverlay();return;}
        closeOverlay();readingSettings=listener.createReadingSettings();installOverlay(readingSettings,false);
    }
    void readingStyleChanged(){if(!released){refreshStylePreservingPosition();if(readingSettings!=null)readingSettings.refresh();}}

    private void refreshStylePreservingPosition() {
        final int token = generation, x = materialScroll.getScrollX(), y = materialScroll.getScrollY(), q = questionScroll.getScrollY();
        captureMaterial(); questionTouched = true; materialTouched = true;
        desiredQuestionY = q; desiredMaterialX = x; desiredMaterialY = y; refreshStyle();
        questionScroll.post(() -> { if (!released && generation == token) questionScroll.scrollTo(0, q); });
        materialScroll.post(() -> { if (!released && generation == token && !materialCollapsed) materialScroll.scrollTo(x, y); });
    }
    private void refreshStyle() {
        if (materialContent != null) materialContent.refreshStyle();
        for (NativeContentView content : questionContents) content.refreshStyle();
        refreshReadingLabels(questionBody);
        for (View option : optionViews) { Object tag = option.getTag(); if (Integer.valueOf(1).equals(tag) || Integer.valueOf(2).equals(tag)) forceBold(option); }
        final TextView anchor = answerAnchor;
        if (anchor != null) anchor.post(() -> { if (!released && answerAnchor == anchor && anchor.getParent() == questionBody) answerY = anchor.getTop(); });
    }
    private void refreshReadingLabels(View view) {
        if (view instanceof NativeContentView) return;
        if (view instanceof TextView && view.getTag() instanceof Boolean) {
            TextView text = (TextView)view; boolean bold = Boolean.TRUE.equals(view.getTag()); textStyle.apply(text);
            if (bold) text.setTypeface(text.getTypeface(), Typeface.BOLD);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view; for (int i = 0; i < group.getChildCount(); i++) refreshReadingLabels(group.getChildAt(i));
        }
    }
    private void installOverlay(View panel, boolean fill) {
        cancelDividerDrag();
        overlay = new FrameLayout(getContext()); overlay.setBackgroundColor(fill ? Color.WHITE : Color.TRANSPARENT);
        overlay.setOnClickListener(v -> closeOverlay()); panel.setClickable(true);
        FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(-1, fill ? -1 : -2, Gravity.TOP); overlay.addView(panel, inner);
        FrameLayout.LayoutParams outer = new FrameLayout.LayoutParams(-1, -1); outer.topMargin = dp(49); addView(overlay, outer);
    }
    private void closeOverlay() { if(frameGate!=null){frameGate.cancel();frameGate=null;} overlayGeneration++;if(readingSettings!=null){readingSettings.release();readingSettings=null;} if (overlay != null) { removeView(overlay); overlay.removeAllViews(); overlay = null; } }

    private JSONObject makePosition(int q, int s, int m, String view, boolean keepQuestionScroll) {
        captureMaterial(); JSONObject position = copyObject(positionRecord);
        try {
            position.put("t", System.currentTimeMillis());
            if ("read".equals(mode)) {
                if ("note".equals(document.item.shape)) position.put("si", s);
                else { position.put("qi", q).put("mi", m).put("view", view); JSONObject scroll = copyObject(position.optJSONObject("scroll")); scroll.put(readView + ":" + ("mat".equals(readView) ? mi : qi), questionPositionY() / density()); position.put("scroll", scroll); }
            } else position.put("i", q);
            position.put("materialPositions", copyObject(materialPositions)).put("materialCollapsed", materialCollapsed).put("materialSplit", materialSplit).put("materialSideSplit", materialSideSplit);
            position.put("nativeQuestionY", keepQuestionScroll ? questionPositionY() / density() : 0);
        } catch (Exception ignored) {}
        return position;
    }
    private void captureMaterial() {
        if (materialContent == null || materialCollapsed || materialKey.length() == 0) return;
        try { JSONObject old = copyObject(materialPositions.optJSONObject(materialKey)); old.put("x", (materialTouched ? materialScroll.getScrollX() : Math.max(desiredMaterialX, materialScroll.getScrollX())) / density()).put("y", (materialTouched ? materialScroll.getScrollY() : Math.max(desiredMaterialY, materialScroll.getScrollY())) / density()); materialPositions.put(materialKey, old); } catch (Exception ignored) {}
    }
    private int questionPositionY() { return questionTouched ? questionScroll.getScrollY() : Math.max(desiredQuestionY, questionScroll.getScrollY()); }
    private int savedQuestionY() {
        if ("read".equals(mode) && !"note".equals(readView)) {
            JSONObject scroll = positionRecord.optJSONObject("scroll"); return scroll == null ? 0 : dp(scroll.optDouble(readView + ":" + ("mat".equals(readView) ? mi : qi), 0));
        }
        return dp(positionRecord.optDouble("nativeQuestionY", 0));
    }
    private void queuePositionSave() {
        if (released || state == null) return;
        final JSONObject position = pendingPosition == null ? makePosition(qi, si, mi, readView, true) : copyObject(pendingPosition); final String actual = mode;
        positionRecord = copyObject(position);
        try { worker.execute(() -> { try { progress.savePosition(document.item.id, actual, position); } catch (Exception error) { main.post(() -> { if (!released) report(error); }); } }); } catch (RejectedExecutionException ignored) {}
    }
    private void requestExit() {
        if (released) return;
        if (state == null) { generation++; if (listener != null) listener.onExit(); return; }
        if (busy) { toast("正在保存，请稍候"); return; }
        final JSONObject position = makePosition(qi, si, mi, readView, true); final int token = ++generation; final String actual = mode;
        pendingPosition = position; setBusy(true); closeOverlay();
        task(token, () -> { progress.savePosition(document.item.id, actual, position); return Boolean.TRUE; }, done -> { pendingPosition = null; if (listener != null) listener.onExit(); });
    }

    boolean onBackPressed() {
        if (overlay != null) { closeOverlay(); return true; }
        if (confirmation != null && confirmation.isShowing()) { confirmation.dismiss(); confirmation = null; return true; }
        if (closeMaterialFocus()) return true;
        requestExit(); return true;
    }
    void pause() { if (!released) { cancelDividerDrag(); main.removeCallbacks(saveAfterScroll); queuePositionSave(); } }
    void resume() { if (!released) refreshStyle(); }
    void release() {
        if (released) return;
        pause(); released = true; generation++; closeOverlay(); main.removeCallbacksAndMessages(null);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
        if (questionScroll.getViewTreeObserver().isAlive()) questionScroll.getViewTreeObserver().removeOnScrollChangedListener(scrollObserver);
        if (materialScroll.getViewTreeObserver().isAlive()) materialScroll.getViewTreeObserver().removeOnScrollChangedListener(scrollObserver);
        clearQuestion(); clearMaterial(); listener = null; activity = null;
        try { worker.execute(() -> { try { document.close(); } catch (Exception ignored) {} }); } catch (RejectedExecutionException ignored) {}
        worker.shutdown();
    }

    private <T> void task(final int token, final Work<T> work, final Done<T> done) {
        try { worker.execute(() -> {
            try { final T result = work.run(); main.post(() -> { if (!released && generation == token) done.accept(result); }); }
            catch (Exception error) { main.post(() -> { if (!released && generation == token) { pendingPosition = null; if(frameGate!=null){frameGate.cancel();frameGate=null;}setBusy(false); report(error); } }); }
        }); } catch (RejectedExecutionException ignored) {}
    }
    private void setBusy(boolean value) {
        if (value) cancelDividerDrag();
        busy = value; previousButton.setEnabled(!value); nextButton.setEnabled(!value); positionButton.setEnabled(!value); starButton.setEnabled(!value); fontButton.setEnabled(!value);
        materialTab.setEnabled(!value); questionTab.setEnabled(!value);
        if (!value && current != null && isShenlun()) {
            int at = "mat".equals(readView) ? mi : qi, count = "mat".equals(readView) ? current.materialCount : current.questionCount;
            previousButton.setEnabled(at > 0); nextButton.setEnabled(at + 1 < count);
            materialTab.setEnabled(current.materialCount > 0);
        }
    }
    private void clearQuestion() {
        if (questionRestoreListener != null) { questionBody.removeOnLayoutChangeListener(questionRestoreListener); questionRestoreListener = null; }
        for (NativeContentView content : questionContents) content.release(); questionContents.clear(); optionViews.clear(); questionBody.removeAllViews(); answerAnchor = null; answerY = -1;
    }
    private void clearMaterial() {
        if (materialContent != null) materialContent.release(); materialContent = null; materialScroll.removeAllViews();
        materialKey = ""; materialFocused = false; layoutMaterial();
    }
    private void showMessage(String message) { clearQuestion(); questionBody.addView(label(NativeDisplayText.clean(message), false)); }
    private void updateStar() {
        if (isShenlun() && "mat".equals(readView)) {
            JSONObject stars = positionRecord.optJSONObject("materialStars");
            boolean saved = stars != null && stars.optBoolean(String.valueOf(mi));
            starButton.setVisibility(VISIBLE); starButton.setText(saved ? "★" : "☆");
            starButton.setContentDescription(saved ? "取消收藏当前材料" : "收藏当前材料"); return;
        }
        boolean available = current != null && current.question != null && current.questionCount > 0 && !"note".equals(readView);
        starButton.setVisibility(available ? VISIBLE : INVISIBLE); boolean starred = false;
        if (available && state != null) for (int i = 0; i < state.stars.length(); i++) {
            JSONObject star = state.stars.optJSONObject(i);
            if (star != null && document.item.id.equals(star.optString("id")) && same(star.opt("no"), questionNo(current.question))) { starred = true; break; }
        }
        starButton.setText(starred ? "★" : "☆"); starButton.setContentDescription(starred ? "取消收藏当前题目" : "收藏当前题目");
    }
    private JSONObject inlineMaterial(JSONObject question) throws Exception {
        String own = text(question.opt("material")); JSONArray images = array(question.optJSONArray("mimgs"));
        if (own.length() > 0 || images.length() > 0) return new JSONObject().put("text", own).put("images", images);
        JSONArray refs = question.optJSONArray("mats");
        if (refs != null && refs.length() > 0) {
            JSONArray combinedImages = new JSONArray(); StringBuilder text = new StringBuilder();
            Set<String> includedImages = new HashSet<String>();
            for (int i = 0; i < refs.length(); i++) {
                int at = document.materialIndex(refs.opt(i));
                if (at < 0) continue;
                JSONObject material = document.material(at);
                appendMaterialPart(text, combinedImages, includedImages,
                        text(material.opt("text")), array(material.optJSONArray("imgs")));
            }
            if (text.length() > 0 || combinedImages.length() > 0) return new JSONObject().put("text", text.toString()).put("images", combinedImages);
        }
        return question.optString("parentStem", "").length() > 0 ? new JSONObject().put("text", question.optString("parentStem")).put("images", new JSONArray()) : null;
    }

    /** Keep each attached chart next to its own passage, not after every passage. */
    @SuppressWarnings("deprecation")
    private static void appendMaterialPart(StringBuilder output, JSONArray combinedImages,
                                           Set<String> includedImages, String body, JSONArray images) {
        if (output.length() > 0) output.append('\n');
        output.append(body);
        Matcher inline = MATERIAL_IMAGES.matcher(body);
        while (inline.find()) {
            String ref = inline.group(1);
            if (ref == null) {
                for (int i = 2; i <= 4 && ref == null; i++) ref = inline.group(i);
                ref = Html.fromHtml(ref == null ? "" : ref).toString();
            }
            includedImages.add(ref.trim());
        }
        for (int i = 0; i < images.length(); i++) {
            Object raw = images.opt(i);
            combinedImages.put(raw); // Retain the renderer's attachment fallback.
            String ref = raw instanceof String ? (String) raw : raw instanceof JSONObject
                    ? ((JSONObject) raw).optString("id", ((JSONObject) raw).optString("src", "")) : "";
            ref = ref.trim();
            if (ref.startsWith("[[img:") && ref.endsWith("]]")) ref = ref.substring(6, ref.length() - 2).trim();
            if (ref.length() > 0 && ref.indexOf(']') < 0 && ref.indexOf('\n') < 0 && ref.indexOf('\r') < 0
                    && includedImages.add(ref)) output.append("\n[[img:").append(ref).append("]]");
        }
    }
    private int chosen(JSONObject question) { return state == null ? -1 : index(state.answers.opt(String.valueOf(questionNo(question)))); }
    private boolean isDone(Object number) { return state != null && contains(state.done, number); }
    private static boolean contains(JSONArray values, Object value) { for (int i = 0; i < values.length(); i++) if (same(values.opt(i), value)) return true; return false; }
    private static boolean same(Object a, Object b) { return a != null && b != null && String.valueOf(a).equals(String.valueOf(b)); }
    private static Object questionNo(JSONObject question) { return question.opt("no") == null ? "" : question.opt("no"); }
    private static int index(Object value) {
        if (value == null || value == JSONObject.NULL) return -1;
        if (value instanceof Number) { int n = ((Number)value).intValue(); return n >= 0 && n < 8 ? n : -1; }
        String s = String.valueOf(value).trim().toUpperCase(java.util.Locale.US);
        if (s.length() == 1) { int letter = "ABCDEFGH".indexOf(s); if (letter >= 0) return letter; }
        try { int n = Integer.parseInt(s); return n >= 0 && n < 8 ? n : -1; } catch (Exception ignored) { return -1; }
    }
    private static int answerIndex(JSONObject question) { int index = index(question.opt("answer")); return index < array(question.optJSONArray("options")).length() ? index : -1; }
    private static String letter(int index) { return index >= 0 && index < 8 ? String.valueOf("ABCDEFGH".charAt(index)) : String.valueOf(index + 1); }
    private static int clamp(int index, int size) { return size <= 0 ? 0 : Math.max(0, Math.min(index, size - 1)); }
    private static JSONArray array(JSONArray value) { return value == null ? new JSONArray() : value; }
    private static JSONObject copyObject(JSONObject value) { try { return value == null ? new JSONObject() : new JSONObject(value.toString()); } catch (Exception ignored) { return new JSONObject(); } }
    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONArray) { StringBuilder out = new StringBuilder(); JSONArray array = (JSONArray)value; for (int i = 0; i < array.length(); i++) { if (out.length() > 0) out.append('\n'); out.append(text(array.opt(i))); } return out.toString(); }
        return String.valueOf(value);
    }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private View line() { View line = new View(getContext()); line.setBackgroundColor(Color.BLACK); return line; }
    private TextView tool(String text, String description) {
        TextView view = new TextView(getContext()); view.setText(NativeDisplayText.clean(text)); view.setTextColor(Color.BLACK); view.setTextSize(16); view.setTypeface(Typeface.DEFAULT);
        view.setGravity(Gravity.CENTER); view.setMinimumHeight(dp(44)); view.setPadding(dp(4), 0, dp(4), 0); view.setBackgroundColor(Color.WHITE);
        view.setContentDescription(NativeDisplayText.clean(description)); view.setFocusable(true); return view;
    }
    private TextView label(String text, boolean bold) {
        TextView view = new TextView(getContext()); textStyle.apply(view); view.setTextColor(Color.BLACK); view.setText(text); view.setTag(Boolean.valueOf(bold)); view.setPadding(dp(4), dp(6), dp(4), dp(6));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD); return view;
    }
    private TextView action(String text) { TextView view = label(NativeDisplayText.clean(text), false); view.setMinimumHeight(dp(44)); view.setGravity(Gravity.CENTER_VERTICAL); view.setBackgroundColor(Color.WHITE); view.setFocusable(true); return view; }
    private GradientDrawable stroke(int status) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.WHITE);
        if (status == 3) drawable.setStroke(dp(2), Color.BLACK, dp(6), dp(4));
        else drawable.setStroke(dp(status == 1 || status == 2 ? 4 : 2), status == 0 ? Color.TRANSPARENT : Color.BLACK);
        return drawable;
    }
    private GradientDrawable cardStroke(int status) { if (status > 0) return stroke(status); GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.WHITE); drawable.setStroke(dp(1), Color.BLACK); return drawable; }
    private static void forceBold(View view) {
        if (view instanceof TextView) { TextView text = (TextView)view; text.setTypeface(text.getTypeface(), Typeface.BOLD); }
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup)view; for (int i = 0; i < group.getChildCount(); i++) forceBold(group.getChildAt(i)); }
    }
    private void confirm(String message, String positive, Runnable action) {
        if (released || activity == null) return; if (confirmation != null) confirmation.dismiss();
        confirmation = new AlertDialog.Builder(activity).setMessage(NativeDisplayText.clean(message)).setNegativeButton("取消", (dialog, which) -> {})
                .setPositiveButton(NativeDisplayText.clean(positive), (dialog, which) -> { confirmation = null; if (!released) action.run(); }).create();
        confirmation.setOnDismissListener(dialog -> confirmation = null); confirmation.show();
    }
    private void toast(String message) { if (!released) Toast.makeText(getContext().getApplicationContext(), NativeDisplayText.clean(message), Toast.LENGTH_SHORT).show(); }
    private void report(Exception error) { if (listener != null) listener.onError(message(error)); }
    private static String message(Exception error) { String message = NativeDisplayText.clean(error.getMessage()); return message.trim().length() == 0 ? "操作未完成，原记录已保留" : message; }
    private float density() { return getResources().getDisplayMetrics().density; }
    private int dp(double value) { return (int)Math.round(value * density()); }
}




