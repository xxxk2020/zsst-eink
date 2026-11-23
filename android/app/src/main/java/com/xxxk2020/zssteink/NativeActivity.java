package com.xxxk2020.zssteink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import java.io.IOException;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native launcher. Uses only local storage and native Android views. */
public final class NativeActivity extends Activity {
    private static final int IMPORT = 4201, EXPORT = 4202, FONT = 4203;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NativeRepository repository;
    private NativeProgress progress;
    private NativeArchiveStore archiveStore;
    private NativeTextStyle textStyle;
    private NativeReaderView reader;
    private final NativePageKeys pageKeys = new NativePageKeys();
    private NativeResourcesView resources;
    private NativeAboutView about;
    private NativeFavoritesView favorites;
    private NativeReadingSettingsView readingSettings;
    private String settingsScreen="";
    private boolean returnToFavorites;

    private LinearLayout root;
    private ListView list;
    private TextView status;
    private volatile boolean dead;
    private boolean busy;
    private int generation;
    private String subject = "行测", courseKey;
    private File pendingExport;
    private Uri pendingFontImport;
    private final List<NativeRepository.Item> items = new ArrayList<>();
    private final Map<String, String> summaries = new LinkedHashMap<>();
    private final Map<String, String> modes = new LinkedHashMap<>();
    private final Map<String, Long> recent = new LinkedHashMap<>();
    private final Map<String, String> resumeDetails = new LinkedHashMap<>();
    private final Set<String> archived = new HashSet<>();
    private final List<NativeRepository.Issue> libraryIssues = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyReadingOrientation();
        subject = getPreferences(MODE_PRIVATE).getString("subject", "行测");
        if (state != null) {
            subject = state.getString("subject", subject);
            String fontUri = state.getString("pendingFontImport");
            if (fontUri != null) pendingFontImport = Uri.parse(fontUri);
            String path = state.getString("pendingExport");
            if (path != null) {
                File candidate = new File(path);
                try { if (candidate.getCanonicalPath().startsWith(new File(getCacheDir(), "native-export").getCanonicalPath() + File.separator)) pendingExport = candidate; }
                catch (Exception ignored) { }
            }
        }
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(0xFF000000);
        applyStatusBar();
        repository = new NativeRepository(getApplicationContext());
        progress = new NativeProgress(getApplicationContext());
        archiveStore = new NativeArchiveStore(getApplicationContext());
        showStartup();
        work("", () -> { repository.prepareStartup(); textStyle = new NativeTextStyle(getApplicationContext()); return loadLibrary(); }, value -> showHome());
    }

    private Object loadLibrary() throws Exception {
        List<NativeRepository.Item> found = repository.list();
        Map<String,String> nextSummaries = new LinkedHashMap<>(), nextModes = new LinkedHashMap<>(), nextDetails = new LinkedHashMap<>();
        Map<String,Long> nextRecent = new LinkedHashMap<>();
        List<NativeRepository.Issue> problems=repository.issues();
        for (NativeRepository.Item item : found) {
            try {
                NativeProgress.LibraryState snapshot=progress.libraryState(item);
                nextSummaries.put(item.id,snapshot.summary);nextModes.put(item.id,snapshot.mode);
                nextDetails.put(item.id,resumeDetail(item,snapshot.mode,snapshot.state));
                nextRecent.put(item.id,snapshot.lastUsed);
            } catch(Exception damaged) {
                if(Thread.currentThread().isInterrupted())throw damaged;
                nextSummaries.put(item.id,"进度暂不可读取，原记录已保留");
                nextRecent.put(item.id,0L);
                if(problems.size()<40)problems.add(new NativeRepository.Issue(item.title,"学习进度无法读取，原记录已保留。"+message(damaged),null));
            }
        }
        // A single IO queue produces a snapshot; publish it together on the UI thread.
        return new LibrarySnapshot(found,nextSummaries,nextModes,nextRecent,nextDetails,archiveStore.archivedIds(),problems);
    }

    private String resumeDetail(NativeRepository.Item item,String mode,NativeProgress.State state){
        String label="read".equals(mode)?"阅读":"practice".equals(mode)?"练习":"刷题";
        if("read".equals(mode)){
            if(state.position.has("si"))return "第 "+(Math.max(0,state.position.optInt("si"))+1)+" 节 · "+label;
            if(state.position.has("qi"))return "第 "+(Math.max(0,state.position.optInt("qi"))+1)+" 题 · "+label;
        }else if(state.position.has("i")){
            int index=Math.max(0,state.position.optInt("i"));if(item.count>0)index=Math.min(index,item.count-1);
            return "第 "+(index+1)+(item.count>0?" / "+item.count:"")+" 题 · "+label+("drill".equals(mode)&&state.submitted?" · 已交卷":"");
        }
        return label+" · 已保存学习进度";
    }

    private void showStartup() {
        setContentView(new NativeStartupView(this));status=null;
    }
    private void returnHome() {
        work("",()->{try{return loadLibrary();}catch(Exception failure){return failure;}},value->{
            closeReader();if(returnToFavorites)showFavorites();else showHome();if(value instanceof Exception)error(message((Exception)value));
        });
    }
    private void showHome() {
        closeSettings();
        closeFavorites();returnToFavorites=false;
        closeAbout();
        closeResources();
        root = column(); root.setPadding(dp(12),0,dp(12),0);
        LinearLayout top = row();
        TextView title = text(courseKey == null ? "纸上刷题" : "练习集", 21, true);
        if (courseKey != null) top.addView(action("‹", "返回题库", () -> { courseKey=null; showHome(); }), box(44,48));
        top.addView(title, new LinearLayout.LayoutParams(0,dp(52),1));
        top.addView(new NativeHomeIcon(this,0,"资源：本地导入和资料说明",this::showResources),box(44,48));
        top.addView(new NativeHomeIcon(this,1,"我的收藏",this::showFavorites),box(44,48));
        top.addView(new NativeHomeIcon(this,2,"设置",this::showSettings),box(44,48)); root.addView(top);
        if (courseKey == null) {
            LinearLayout tabs = row();
            for (String s : new String[]{"行测","申论"}) {
                TextView tab = action(s,s,() -> { if(!busy){ subject=s;getPreferences(MODE_PRIVATE).edit().putString("subject",s).apply();showHome(); } });
                tab.setTypeface(null, subject.equals(s)?Typeface.BOLD:Typeface.NORMAL);
                tab.setSelected(subject.equals(s));
                tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(44),1));
            }
            root.addView(tabs);
            NativeRepository.Item last=latestItem();
            if(last!=null){
                LinearLayout resume=column();resume.setPadding(dp(4),dp(12),dp(4),dp(14));
                resume.addView(text("继续上次",14,true));
                TextView name=text(last.title,18,true);name.setMaxLines(3);name.setPadding(0,dp(6),0,dp(4));resume.addView(name);
                resume.addView(text(resumeDetails.get(last.id),14,false));resume.setMinimumHeight(dp(92));resume.setFocusable(true);
                resume.setContentDescription("继续上次："+last.title+"，"+resumeDetails.get(last.id));resume.setOnClickListener(v->{if(!busy)open(last,modes.get(last.id));});
                root.addView(resume);
            }
        }
        list = new ListView(this); list.setDivider(null);list.setCacheColorHint(0xFFFFFFFF);
        list.setVerticalScrollBarEnabled(false);
        List<Row> rows = libraryRows();
        list.setAdapter(new Rows(rows));
        list.setOnItemClickListener((p,v,pos,id)->{ if(!busy && rows.get(pos).click!=null)rows.get(pos).click.run(); });
        list.setOnItemLongClickListener((p,v,pos,id)->{Row r=rows.get(pos);if(busy)return false;if(r.item!=null){itemMenu(r.item);return true;}if(!r.heading && courseKey==null){List<NativeRepository.Item> group=new ArrayList<>();for(NativeRepository.Item item:items)if("course".equals(item.kind)&&subject.equals(item.subject)&&course(item).equals(r.title))group.add(item);if(!group.isEmpty()){confirmDeleteCourse(r.title,group);return true;}}return false;});
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        if(!libraryIssues.isEmpty()) {
            TextView warning=text("有 "+libraryIssues.size()+" 项资料或进度未加载 · 查看",13,false);
            warning.setPadding(dp(4),dp(6),dp(4),dp(6));warning.setOnClickListener(v->showLibraryIssues());root.addView(warning);
        }
        status = text("",13,false);status.setVisibility(View.GONE);root.addView(status);
        setContentView(root);
    }

    private List<Row> libraryRows() {
        List<Row> rows=new ArrayList<>();
        List<NativeRepository.Item> visible=new ArrayList<>();
        for(NativeRepository.Item item:items)if(subject.equals(item.subject)&&!archived.contains(item.id))visible.add(item);
        if(courseKey!=null){
            for(NativeRepository.Item item:visible)if("course".equals(item.kind)&&courseKey.equals(course(item)))rows.add(itemRow(item));
            if(rows.isEmpty())rows.add(new Row("这个练习集还没有题目","可导入练习资源包",null,null));
            return rows;
        }
        rows.add(Row.heading("试卷"));int papers=0;
        for(NativeRepository.Item item:visible)if(!"course".equals(item.kind)){rows.add(itemRow(item));papers++;}
        if(papers==0)rows.add(new Row("还没有"+subject+"试卷","点右上角资源图标导入本地试卷",null,null));
        rows.add(Row.heading("练习集"));
        Map<String,List<NativeRepository.Item>> groups=new LinkedHashMap<>();
        for(NativeRepository.Item item:visible)if("course".equals(item.kind)){
            String key=course(item);if(!groups.containsKey(key))groups.put(key,new ArrayList<>());groups.get(key).add(item);
        }
        for(Map.Entry<String,List<NativeRepository.Item>> entry:groups.entrySet()){
            String key=entry.getKey();List<NativeRepository.Item> units=entry.getValue();
            rows.add(new Row(key,units.size()+" 份配套练习",()->{courseKey=key;showHome();},null));
        }
        if(groups.isEmpty())rows.add(new Row("还没有练习集","导入练习资源包后，按练习集和分组查看",null,null));
        List<NativeRepository.Item> unknown=new ArrayList<>();for(NativeRepository.Item item:items)if(!archived.contains(item.id)&&!"行测".equals(item.subject)&&!"申论".equals(item.subject))unknown.add(item);
        if(!unknown.isEmpty()){rows.add(Row.heading("待归类"));for(NativeRepository.Item item:unknown)rows.add(new Row(item.title,"点按选择行测或申论",()->classify(item),item));}
        return rows;
    }

    private NativeRepository.Item latestItem(){
        NativeRepository.Item last=null;long latest=0;
        for(NativeRepository.Item item:items)if(subject.equals(item.subject)&&!archived.contains(item.id)){
            Long time=recent.get(item.id);if(time!=null&&time>latest){latest=time;last=item;}
        }
        return last;
    }

    private String course(NativeRepository.Item item) {
        String value=item.course;
        if(value==null||value.trim().length()==0)value=item.metadata.optString("group","练习集");
        return value.length()==0?"练习集":value;
    }
    private Row itemRow(NativeRepository.Item item) {
        String summary=summaries.get(item.id);String detail=summary==null||summary.length()==0?item.count+" 题":summary;
        return new Row(item.title,detail,()->open(item,modes.get(item.id)),item);
    }
    private void itemMenu(NativeRepository.Item item) {
        boolean reading="shenlun".equals(item.shape)||"note".equals(item.shape);
        String[] labels=reading?new String[]{"继续阅读","归档这份资料","调整学科归类","导出这份资料","删除这份资料"}:new String[]{"继续上次学习","刷题模式","练习模式","归档这份试卷","调整学科归类","导出这份试卷","删除这份试卷"};
        new AlertDialog.Builder(this).setTitle(NativeDisplayText.title(item.title,"资料")).setItems(labels,(d,n)->{
            if(n==0)open(item,modes.get(item.id));
            else if(!reading&&n<3)open(item,n==1?"drill":"practice");
            else if(n==labels.length-4)setArchived(item,true);
            else if(n==labels.length-3)classify(item);
            else if(n==labels.length-2)exportDocument(item);
            else confirmDelete(item);
        }).show();
    }
    private void confirmDeleteCourse(String title,List<NativeRepository.Item> group) {
        new AlertDialog.Builder(this).setTitle("删除整个练习集的本机练习？")
            .setMessage(title+"\n共 "+group.size()+" 份练习。删除题目文件，保留学习进度。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,n)->
                work("正在删除…",()->{for(NativeRepository.Item item:group)repository.remove(item);repository.maintenance();return loadLibrary();},v->{courseKey=null;showHome();toast("已删除练习集，学习进度已保留");})).show();
    }
    private void confirmDelete(NativeRepository.Item item) {
        LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(8));
        body.addView(text(NativeDisplayText.title(item.title,"资料"),17,true));
        CheckBox records=new CheckBox(this);records.setText("同时删除本卷学习记录");records.setTextColor(0xff111111);records.setChecked(false);records.setMinHeight(dp(48));body.addView(records);
        TextView note=text("只删除本机题目和配套图片，保留作答、进度、收藏与历史记录。重新导入相同 ID 的资料后可继续。",15,false);body.addView(note);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("删除这份本机资料？").setView(body).setNegativeButton("取消",null).setPositiveButton("删除试卷",null).create();
        dialog.setOnShowListener(ignored->{
            records.setOnCheckedChangeListener((button,checked)->{note.setText(checked?"同时清除本卷在本机的作答、进度、收藏与历史；其他资料保持不变。":"只删除本机题目和配套图片，保留作答、进度、收藏与历史记录。重新导入相同 ID 的资料后可继续。");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(checked?"删除试卷及记录":"删除试卷");});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(busy)return;final boolean clear=records.isChecked();
                dialog.dismiss();
                work("正在删除…",()->{if(clear){progress.removeDocumentRecords(item.id);archiveStore.setArchived(item.id,false);}repository.remove(item);repository.maintenance();return loadLibrary();},result->{showHome();toast(clear?"已删除本机试卷及本卷学习记录":"已删除本机试卷，学习记录和收藏已保留");});
            });
        });dialog.show();
    }
    private void classify(NativeRepository.Item item) {
        new AlertDialog.Builder(this).setTitle("归入哪个学科？").setItems(new String[]{"行测","申论"},(d,n)->work("正在保存归类…",()->{repository.setSubject(item,n==0?"行测":"申论");return loadLibrary();},v->showHome())).show();
    }
    private void open(NativeRepository.Item item,String mode) {
        if(busy)return;returnToFavorites=false;
        final String selected=mode==null?("shenlun".equals(item.shape)||"note".equals(item.shape)?"read":item.hasAnswers?"practice":"drill"):mode;
        work("正在打开…",()->repository.open(item),value->{
            installReader((NativeRepository.Document)value,selected,null);
        });
    }
    private void installReader(NativeRepository.Document document,String selected,String favoriteNumber){
            closeFavorites();
            reader=new NativeReaderView(this,document,progress,textStyle,selected,new NativeReaderView.Listener(){
                @Override public void onExit(){ returnHome(); }
                @Override public NativeReadingSettingsView createReadingSettings(){return newReadingSettings();}
                @Override public void onError(String message){error(message);}
            });
            if(favoriteNumber==null)reader.open();else reader.openFavorite(favoriteNumber);setContentView(reader);
    }
    private void showFavorites(){
        if(busy||dead)return;
        final List<NativeRepository.Item> available=new ArrayList<>(items);
        final Set<String> archivedIds=new HashSet<>(archived);
        work("正在读取收藏…",()->NativeFavoritesView.prepare(available,progress.favorites(),archivedIds),value->{
            closeSettings();closeAbout();closeResources();closeFavorites();returnToFavorites=false;
            favorites=new NativeFavoritesView(this,(NativeFavoritesView.Snapshot)value,new NativeFavoritesView.Listener(){
                public void back(){showHome();}
                public void open(NativeFavoritesView.Entry entry){openFavorite(entry);}
            });
            root=null;status=null;setContentView(favorites);
        });
    }
    private void closeFavorites(){if(favorites!=null){favorites.release();favorites=null;}}
    private void openFavorite(NativeFavoritesView.Entry entry){
        if(busy||dead)return;NativeRepository.Item found=null;
        for(NativeRepository.Item item:items)if(item.id.equals(entry.id)){found=item;break;}
        if(found==null){error("这份资料未在本机，请先从资源重新导入原资料；收藏记录仍保留");return;}
        final NativeRepository.Item selected=found;final String savedMode=modes.get(selected.id);
        work("正在打开收藏题目…",()->repository.open(selected),value->{
            returnToFavorites=true;installReader((NativeRepository.Document)value,savedMode,entry.number);
        });
    }
    private void closeReader(){pageKeys.reset();if(reader!=null){reader.release();reader=null;}}

    private void pickImport(boolean progressBackup) {
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,progressBackup?"选择学习进度备份 JSON":"选择试卷 JSON 或配套练习 ZIP");
        launchPicker(intent,IMPORT);
    }
    private void exportProgress(){
        if(busy)return;
        work("正在整理进度…",()->NativeTransfer.writeProgress(this,progress.exportProgress(new ArrayList<>(items))),value->chooseExport((File)value,"zsst-progress.json","application/json"));
    }
    private void exportDocument(NativeRepository.Item item){
        if(busy)return;
        work("正在准备导出…",()->{try(NativeRepository.Document document=repository.open(item)) {
            if(!document.hasFileImages())return NativeTransfer.copyForExport(this,item.file);
            File dir=new File(getCacheDir(),"native-export");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("无法准备导出目录");
            File target=File.createTempFile("export-",".json",dir);new NativePortablePaper(document).write(target);return target;
        }},value->chooseExport((File)value,NativeDisplayText.title(item.title,"资料").replaceAll("[\\\\/:*?\"<>|]","_")+".json","application/json"));
    }
    private void chooseExport(File file,String name,String mime){
        pendingExport=file;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name);
        launchPicker(intent,EXPORT);
    }
    private void launchPicker(Intent intent,int code){
        try{startActivityForResult(intent,code);}
        catch(android.content.ActivityNotFoundException e){
            if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())){
                try{startActivityForResult(new Intent(intent).setAction(Intent.ACTION_GET_CONTENT),code);return;}
                catch(Exception fallback){error("无法打开文件选择器："+message(fallback));}
            }else error("此系统没有文件保存界面，请安装支持系统文件选择器的文件管理器后重试");
        }catch(Exception e){error("无法打开文件选择器："+message(e));}
    }

    private void showSettings(){
        if(busy)return;closeSettings();closeFavorites();closeResources();closeAbout();settingsScreen="main";
        root=column();root.setPadding(dp(12),0,dp(12),0);settingsHeader("设置",this::showHome);
        LinearLayout body=column();
        settingsEntry(body,"阅读设置","字体、字号、字重、方向与全屏",this::showReadingSettings);
        settingsEntry(body,"资料管理","已归档资料、学习记录备份",this::showDataSettings);
        settingsEntry(body,"关于与帮助","使用说明、版本与应用信息",this::showAbout);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));status=null;list=null;setContentView(root);
    }
    private void settingsHeader(String title,Runnable back){LinearLayout bar=row();bar.addView(action("‹","返回",back),box(44,48));bar.addView(text(title,20,true),new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bar);}
    private void settingsEntry(LinearLayout body,String title,String detail,Runnable click){LinearLayout entry=column();entry.setPadding(dp(4),dp(16),dp(4),dp(16));entry.setMinimumHeight(dp(76));entry.addView(text(title+"  ›",18,true));TextView hint=text(detail,14,false);hint.setPadding(0,dp(5),0,0);entry.addView(hint);entry.setFocusable(true);entry.setOnClickListener(v->{if(!busy)click.run();});body.addView(entry);}
    private void closeSettings(){if(readingSettings!=null){readingSettings.release();readingSettings=null;}settingsScreen="";}
    private void showLibraryIssues() {
        if(busy)return;
        if(libraryIssues.isEmpty())return;
        String[] labels=new String[libraryIssues.size()];
        for(int i=0;i<labels.length;i++)labels[i]=NativeDisplayText.title(libraryIssues.get(i).title,"资料");
        new AlertDialog.Builder(this).setTitle("未加载的资料与进度").setItems(labels,(d,n)->{
            NativeRepository.Issue issue=libraryIssues.get(n);
            AlertDialog.Builder detail=new AlertDialog.Builder(this).setTitle(NativeDisplayText.title(issue.title,"资料"))
                    .setMessage(issue.detail+"\n其他题库可继续使用；可以重新导入资料。").setNegativeButton("关闭",null);
            if(issue.removable!=null)detail.setPositiveButton("删除损坏文件…",(a,b)->
                new AlertDialog.Builder(this).setTitle("删除这份损坏资料？").setMessage("仅删除 "+issue.title+"，保留学习进度和字体设置。")
                    .setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->work("正在删除…",()->{repository.removeUnreadable(issue);return loadLibrary();},v->showHome())).show());
            detail.show();
        }).setNegativeButton("关闭",null).show();
    }

    private void showReadingSettings(){
        if(busy)return;closeSettings();settingsScreen="reading";root=column();root.setPadding(dp(12),0,dp(12),0);settingsHeader("阅读设置",this::showSettings);
        readingSettings=newReadingSettings();root.addView(readingSettings,new LinearLayout.LayoutParams(-1,0,1));status=null;list=null;setContentView(root);
    }
    private NativeReadingSettingsView newReadingSettings(){
        return new NativeReadingSettingsView(this,textStyle,io,new NativeReadingSettingsView.Host(){
            public boolean fullscreen(){return getPreferences(MODE_PRIVATE).getBoolean("hideStatus",true);}
            public void fullscreen(boolean enabled){if(reader!=null)reader.readingLayoutChanged();getPreferences(MODE_PRIVATE).edit().putBoolean("hideStatus",enabled).apply();applyStatusBar();}
            public void styleChanged(){if(reader!=null)reader.readingStyleChanged();}
            public void layoutChanged(){applyReadingOrientation();if(reader!=null)reader.readingLayoutChanged();}
            public void importFont(){if(!busy)launchPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),FONT);}
        });
    }
    private void showDataSettings(){
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("资料管理").setItems(new String[]{"已归档资料","导出进度备份","导入进度备份"},(d,n)->{
            if(n==0)showArchived();else if(n==1)exportProgress();else pickImport(true);
        }).setNegativeButton("关闭",null).show();
    }
    private void showArchived(){
        if(busy)return;
        List<NativeRepository.Item> saved=new ArrayList<>();for(NativeRepository.Item item:items)if(archived.contains(item.id))saved.add(item);
        if(saved.isEmpty()){new AlertDialog.Builder(this).setTitle("已归档资料").setMessage("还没有归档资料。在本机题库中长按资料可归档；题库文件和学习进度都会保留。")
                .setPositiveButton("知道了",null).show();return;}
        String[] labels=new String[saved.size()];for(int i=0;i<labels.length;i++)labels[i]=saved.get(i).title+"\n"+saved.get(i).subject+" · 点按恢复到题库";
        new AlertDialog.Builder(this).setTitle("已归档资料").setItems(labels,(d,n)->setArchived(saved.get(n),false)).setNegativeButton("关闭",null).show();
    }
    private void setArchived(NativeRepository.Item item,boolean value){
        if(busy)return;
        work(value?"正在归档…":"正在恢复…",()->{archiveStore.setArchived(item.id,value);return loadLibrary();},ignored->{
            if(courseKey!=null&&value)courseKey=null;showHome();toast(value?"已归档，文件和学习进度均保留":"已恢复到题库，原学习进度已保留");
        });
    }
    private void showResources(){
        if(busy)return;closeSettings();closeFavorites();
        closeAbout();
        closeResources();
        resources=new NativeResourcesView(this,new NativeResourcesView.Listener(){
            @Override public boolean isBusy(){return busy;}
            @Override public void onExit(){returnHome();}
            @Override public void onImport(boolean progressBackup){pickImport(progressBackup);}
        });
        root=null;list=null;status=null;setContentView(resources);
    }

    private void showAbout(){
        if(busy)return;closeSettings();closeFavorites();
        closeAbout();closeResources();
        about=new NativeAboutView(this,this::showSettings);
        root=null;list=null;status=null;setContentView(about);
    }
    private void closeAbout(){if(about!=null){about.release();about=null;}}
    private void closeResources(){if(resources!=null){NativeResourcesView previous=resources;resources=null;previous.release();}}
    private void applyStatusBar(){
        boolean hidden=getPreferences(MODE_PRIVATE).getBoolean("hideStatus",true);
        if(hidden)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(android.os.Build.VERSION.SDK_INT>=23){
            // Android 6+ supports dark status icons on the reader's white background.
            // Preserve other system-UI flags, including the ROM's navigation-bar flags.
            getWindow().setStatusBarColor(0xFFFFFFFF);
            View decor=getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }else if(!hidden){
            // Keep the existing Android 5.1/PlainOS path; its SystemUI may style the bar itself.
            getWindow().setStatusBarColor(0xFF333333);
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==EXPORT)deleteExport();return;}
        Uri uri=data.getData();
        if(request==IMPORT)work("正在导入…",()->{NativeTransfer.importFile(this,uri,subject,repository,progress);return loadLibrary();},v->{showHome();toast("导入完成");});
        else if(request==FONT){pendingFontImport=uri;importPendingFont();}
        else if(request==EXPORT){File file=pendingExport;if(file==null){error("导出准备已失效，请重新导出");return;}
            work("正在导出…",()->{try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException("无法写入目标文件");NativeTransfer.copy(in,out);out.flush();}return null;},v->{deleteExport();toast("导出完成");});}
    }
    private void importPendingFont(){
        if(dead||busy||textStyle==null||pendingFontImport==null)return;
        final Uri uri=pendingFontImport;pendingFontImport=null;
        work("正在导入字体…",()->textStyle.importFont(uri),v->{textStyle.selectFont((NativeTextStyle.PreparedFont)v);if(readingSettings!=null)readingSettings.refresh();if(reader!=null)reader.readingStyleChanged();toast("字体已添加并启用");});
    }
    private void deleteExport(){if(pendingExport!=null){File obsolete=pendingExport;pendingExport=null;if(!io.isShutdown())io.execute(obsolete::delete);}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("subject",subject);if(pendingExport!=null)out.putString("pendingExport",pendingExport.getAbsolutePath());if(pendingFontImport!=null)out.putString("pendingFontImport",pendingFontImport.toString());}
    @Override protected void onPause(){pageKeys.reset();if(reader!=null)reader.pause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(reader!=null)reader.resume();}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(!hasFocus)pageKeys.reset();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        boolean enabled=!dead&&!busy&&reader!=null&&hasWindowFocus()&&reader.canHandlePageKeys();
        int action=pageKeys.dispatch(event,enabled);
        if(action==NativePageKeys.PREVIOUS)reader.handlePageKey(-1);
        else if(action==NativePageKeys.NEXT)reader.handlePageKey(1);
        if(action!=NativePageKeys.PASS)return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){
        if(busy){toast("请等待当前文件操作完成");return;}
        if(reader!=null){if(!reader.onBackPressed()){returnHome();}return;}
        if(resources!=null){resources.back();return;}
        if(about!=null){about.back();return;}
        if(favorites!=null){showHome();return;}
        if(settingsScreen.length()>0){if("main".equals(settingsScreen))showHome();else showSettings();return;}
        if(courseKey!=null){courseKey=null;showHome();return;}
        super.onBackPressed();
    }
    @Override protected void onDestroy(){dead=true;generation++;closeSettings();closeReader();closeResources();closeAbout();closeFavorites();ui.removeCallbacksAndMessages(null);io.shutdown();if(repository!=null)repository.close();super.onDestroy();}

    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);if(reader!=null)reader.readingLayoutChanged();
    }
    private void applyReadingOrientation(){
        String direction=getSharedPreferences("reader-layout",MODE_PRIVATE).getString("orientation","portrait");
        int orientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if("auto".equals(direction))orientation=ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        else if("landscape".equals(direction))orientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        else if("reverse-landscape".equals(direction))orientation=ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        if(getRequestedOrientation()!=orientation)setRequestedOrientation(orientation);
    }
    private void applyLibrarySnapshot(LibrarySnapshot s){
        generation++;
        items.clear();items.addAll(s.items);summaries.clear();summaries.putAll(s.summaries);modes.clear();modes.putAll(s.modes);recent.clear();recent.putAll(s.recent);resumeDetails.clear();resumeDetails.putAll(s.details);archived.clear();archived.addAll(s.archived);libraryIssues.clear();libraryIssues.addAll(s.issues);
    }

    private interface Job{Object run()throws Exception;}
    private interface Done{void accept(Object value);}
    private void work(String label,Job job,Done done){
        if(dead||busy)return;busy=true;int token=++generation;
        final Runnable waiting=()->{
            if(dead||!busy||token!=generation||label.length()==0)return;
            if(status!=null){status.setText(NativeDisplayText.clean(label));status.setVisibility(View.VISIBLE);}
            if(resources!=null)resources.showStatus(label);
        };
        ui.postDelayed(waiting,700);
        io.execute(()->{
            Object result=null;Exception failure=null;
            try{result=job.run();}catch(Exception e){failure=e;}
            final Object value=result;final Exception error=failure;
            if(dead){if(value instanceof NativeRepository.Document)((NativeRepository.Document)value).close();return;}
            ui.post(()->{
                if(dead||token!=generation){if(value instanceof NativeRepository.Document)((NativeRepository.Document)value).close();return;}
                ui.removeCallbacks(waiting);
                busy=false;if(status!=null)status.setVisibility(View.GONE);
                if(resources!=null)resources.showStatus("");
                try{
                    if(error!=null){if(root==null&&reader==null&&resources==null&&about==null&&favorites==null)showHome();error(message(error));return;}
                    if(value instanceof LibrarySnapshot)applyLibrarySnapshot((LibrarySnapshot)value);
                    done.accept(value);
                }finally{importPendingFont();}
            });
        });
    }
    private static final class LibrarySnapshot{
        final List<NativeRepository.Item> items;final Map<String,String> summaries,modes,details;final Map<String,Long> recent;final Set<String> archived;
        final List<NativeRepository.Issue> issues;
        LibrarySnapshot(List<NativeRepository.Item> i,Map<String,String>s,Map<String,String>m,Map<String,Long>r,Map<String,String>d,Set<String>a,List<NativeRepository.Issue>p){items=i;summaries=s;modes=m;recent=r;details=d;archived=a;issues=p;}
    }
    private static final class Row{
        final String title,detail;final Runnable click;final NativeRepository.Item item;final boolean heading;
        Row(String t,String d,Runnable c,NativeRepository.Item i){title=NativeDisplayText.title(t,"资料");detail=NativeDisplayText.clean(d);click=c;item=i;heading=false;}
        private Row(String title){this.title=NativeDisplayText.clean(title);detail="";click=null;item=null;heading=true;}
        static Row heading(String title){return new Row(title);}
    }
    private final class Rows extends BaseAdapter{
        final List<Row> rows;Rows(List<Row>r){rows=r;}
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        @Override public boolean areAllItemsEnabled(){return false;}@Override public boolean isEnabled(int p){return rows.get(p).click!=null;}
        public View getView(int p,View recycled,ViewGroup parent){
            LinearLayout v;TextView title,detail;
            if(recycled instanceof LinearLayout){v=(LinearLayout)recycled;title=(TextView)v.getChildAt(0);detail=(TextView)v.getChildAt(1);}
            else{v=column();title=text("",17,false);detail=text("",13,false);v.addView(title);v.addView(detail);}
            Row r=rows.get(p);v.setPadding(dp(4),dp(r.heading?18:12),dp(4),dp(r.heading?4:12));
            title.setText(r.title);title.setTextSize(r.heading?14:17);title.setTypeface(null,r.heading?Typeface.BOLD:Typeface.NORMAL);title.setMaxLines(3);
            detail.setText(r.detail);detail.setTextColor(0xFF555555);detail.setVisibility(r.heading?View.GONE:View.VISIBLE);
            v.setMinimumHeight(dp(r.heading?40:64));return v;
        }
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setBackgroundColor(0xFFFFFFFF);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,boolean bold){TextView v=new TextView(this);v.setText(NativeDisplayText.clean(value));v.setTextColor(0xFF111111);v.setTextSize(size);v.setGravity(Gravity.CENTER_VERTICAL);v.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private TextView action(String value,String label,Runnable click){TextView v=text(value,17,false);v.setGravity(Gravity.CENTER);v.setContentDescription(NativeDisplayText.clean(label));v.setFocusable(true);v.setOnClickListener(w->click.run());return v;}
    private LinearLayout.LayoutParams box(int w,int h){return new LinearLayout.LayoutParams(dp(w),dp(h));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String value){if(!dead)Toast.makeText(this,NativeDisplayText.clean(value),Toast.LENGTH_LONG).show();}
    private void error(String value){if(!dead)new AlertDialog.Builder(this).setTitle("暂未完成").setMessage(NativeDisplayText.clean(value)).setPositiveButton("知道了",null).show();}
    private static String message(Exception e){return e.getMessage()==null?"请稍后重试":NativeDisplayText.clean(e.getMessage());}
}








