package com.xxxk2020.zssteink;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** The same controls and preferences are used by Settings and the reader's Aa overlay. */
final class NativeReadingSettingsView extends ScrollView {
    interface Host {
        boolean fullscreen();
        void fullscreen(boolean enabled);
        void styleChanged();
        void layoutChanged();
        void importFont();
    }
    private static final String[] WEIGHTS={"原始","微粗","稍粗","中粗","加粗","特粗"};
    private static final String[] DIRECTIONS={"自动（跟随系统）","竖屏","横屏向左","横屏向右"};
    private static final String[] DIRECTION_VALUES={"auto","portrait","landscape","reverse-landscape"};
    private final Activity activity;
    private final NativeTextStyle style;
    private final ExecutorService worker;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Host host;
    private final TextView font,value,smaller,larger,weight,sample,direction,side,stacked;
    private final SeekBar slider;
    private final CheckBox fullscreen;
    private AlertDialog dialog;
    private boolean dead,busy;
    private int generation;

    NativeReadingSettingsView(Activity activity,NativeTextStyle style,ExecutorService worker,Host host){
        super(activity);this.activity=activity;this.style=style;this.worker=worker;this.host=host;
        setBackgroundColor(Color.WHITE);setFillViewport(false);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(4),dp(12),dp(12));addView(body);
        LinearLayout fonts=row("字体");font=button("",this::chooseFont);font.setSingleLine(true);font.setEllipsize(TextUtils.TruncateAt.END);font.setGravity(Gravity.CENTER_VERTICAL);
        fonts.addView(font,new LinearLayout.LayoutParams(0,dp(48),1));fonts.addView(button("导入",()->{if(!busy&&this.host!=null)this.host.importFont();}),new LinearLayout.LayoutParams(dp(52),dp(48)));body.addView(fonts);
        LinearLayout sizes=row("字号");smaller=button("−",()->resize(style.size()-1));larger=button("＋",()->resize(style.size()+1));value=button("",null);
        smaller.setContentDescription("缩小一号");larger.setContentDescription("放大一号");slider=new SeekBar(activity);slider.setMax(NativeTextStyle.MAX_SIZE-NativeTextStyle.MIN_SIZE);slider.setContentDescription("字号，14 到 36");
        if(slider.getProgressDrawable()!=null)slider.getProgressDrawable().mutate().setColorFilter(Color.BLACK,PorterDuff.Mode.SRC_IN);
        if(slider.getThumb()!=null)slider.getThumb().mutate().setColorFilter(Color.BLACK,PorterDuff.Mode.SRC_IN);
        sizes.addView(smaller,new LinearLayout.LayoutParams(dp(44),dp(48)));sizes.addView(slider,new LinearLayout.LayoutParams(0,dp(48),1));sizes.addView(value,new LinearLayout.LayoutParams(dp(36),dp(48)));sizes.addView(larger,new LinearLayout.LayoutParams(dp(44),dp(48)));body.addView(sizes);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            private boolean tracking;
            public void onStartTrackingTouch(SeekBar bar){tracking=true;}
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser){value.setText(String.valueOf(progress+NativeTextStyle.MIN_SIZE));if(!tracking)resize(progress+NativeTextStyle.MIN_SIZE);}}
            public void onStopTrackingTouch(SeekBar bar){tracking=false;resize(bar.getProgress()+NativeTextStyle.MIN_SIZE);}
        });
        LinearLayout weights=row("字重");weight=button("",this::chooseWeight);weight.setGravity(Gravity.CENTER_VERTICAL);weights.addView(weight,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(weights);
        LinearLayout directions=row("方向");direction=button("",this::chooseDirection);direction.setGravity(Gravity.CENTER_VERTICAL);directions.addView(direction,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(directions);
        LinearLayout split=row("横屏分区");side=button("左右",()->layout("landscapeSplit","side"));stacked=button("上下",()->layout("landscapeSplit","stacked"));split.addView(side,new LinearLayout.LayoutParams(0,dp(48),1));split.addView(stacked,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(split);
        fullscreen=new CheckBox(activity);fullscreen.setText("全屏 · 隐藏系统状态栏");fullscreen.setTextColor(Color.BLACK);fullscreen.setTextSize(16);fullscreen.setMinHeight(dp(48));body.addView(fullscreen);
        fullscreen.setOnCheckedChangeListener((button,checked)->{if(!dead&&this.host!=null&&checked!=this.host.fullscreen())this.host.fullscreen(checked);});
        sample=new TextView(activity);sample.setText("学而时习，温故知新。\n0123456789 ABCD");sample.setTextColor(Color.BLACK);sample.setPadding(0,dp(12),0,dp(4));sample.setContentDescription("字体效果预览");body.addView(sample);
        refresh();
    }
    void refresh(){
        if(dead)return;font.setText(NativeDisplayText.title(style.fontName(),"系统字体")+"  ▾");weight.setText(WEIGHTS[style.weight()]+"  ▾");slider.setProgress(style.size()-NativeTextStyle.MIN_SIZE);value.setText(String.valueOf(style.size()));value.setContentDescription("当前字号 "+style.size());
        smaller.setEnabled(style.size()>NativeTextStyle.MIN_SIZE);larger.setEnabled(style.size()<NativeTextStyle.MAX_SIZE);
        android.content.SharedPreferences prefs=activity.getSharedPreferences("reader-layout",Activity.MODE_PRIVATE);
        direction.setText(DIRECTIONS[directionIndex()]+"  ▾");selected(side,!"stacked".equals(prefs.getString("landscapeSplit","side")));selected(stacked,!side.isSelected());
        fullscreen.setChecked(host!=null&&host.fullscreen());style.apply(sample);
    }
    private void resize(int size){if(dead)return;int next=Math.max(NativeTextStyle.MIN_SIZE,Math.min(NativeTextStyle.MAX_SIZE,size));if(next!=style.size()){style.setSize(next);if(host!=null)host.styleChanged();}refresh();}
    private void layout(String key,String value){if(dead)return;android.content.SharedPreferences prefs=activity.getSharedPreferences("reader-layout",Activity.MODE_PRIVATE);if(value.equals(prefs.getString(key,"")))return;prefs.edit().putString(key,value).apply();refresh();if(host!=null)host.layoutChanged();}
    private int directionIndex(){
        String value=activity.getSharedPreferences("reader-layout",Activity.MODE_PRIVATE).getString("orientation","portrait");
        for(int i=0;i<DIRECTION_VALUES.length;i++)if(DIRECTION_VALUES[i].equals(value))return i;
        return 1;
    }
    private void chooseDirection(){if(dead||busy)return;dismissDialog();dialog=new AlertDialog.Builder(activity).setTitle("屏幕方向").setSingleChoiceItems(DIRECTIONS,directionIndex(),(d,n)->{if(dead)return;dismissDialog();layout("orientation",DIRECTION_VALUES[n]);}).setNegativeButton("取消",null).show();}
    private void chooseWeight(){if(dead||busy)return;dismissDialog();dialog=new AlertDialog.Builder(activity).setTitle("文字粗细").setSingleChoiceItems(WEIGHTS,style.weight(),(d,n)->{if(dead)return;if(style.weight()!=n){style.setWeight(n);if(host!=null)host.styleChanged();}dismissDialog();refresh();}).setNegativeButton("取消",null).show();}
    private void chooseFont(){
        if(dead||busy)return;
        task(()->style.fonts(),result->{
            @SuppressWarnings("unchecked") List<FontStore.Entry> entries=(List<FontStore.Entry>)result;
            String[] names=new String[entries.size()];int selected=0;for(int i=0;i<names.length;i++){names[i]=NativeDisplayText.title(entries.get(i).name,"自定义字体");if(entries.get(i).id.equals(style.fontId()))selected=i;}
            dismissDialog();dialog=new AlertDialog.Builder(activity).setTitle("选择字体").setSingleChoiceItems(names,selected,(d,n)->{String id=entries.get(n).id;dismissDialog();if(id.equals(style.fontId()))return;task(()->style.prepareFont(id),prepared->{style.selectFont((NativeTextStyle.PreparedFont)prepared);if(host!=null)host.styleChanged();refresh();});}).setNegativeButton("取消",null).show();
        });
    }
    private interface Work{Object run()throws Exception;} private interface Done{void accept(Object result);}
    private void task(Work work,Done done){
        if(dead||busy)return;busy=true;font.setEnabled(false);final int token=++generation;
        try{worker.execute(()->{Object result=null;Exception failure=null;try{result=work.run();}catch(Exception e){failure=e;}final Object value=result;final Exception error=failure;
            ui.post(()->{if(dead||token!=generation)return;busy=false;font.setEnabled(true);if(error!=null)Toast.makeText(activity,error.getMessage()==null?"字体读取失败":error.getMessage(),Toast.LENGTH_LONG).show();else done.accept(value);});
        });}catch(RejectedExecutionException e){busy=false;font.setEnabled(true);}
    }
    void release(){dead=true;generation++;dismissDialog();host=null;ui.removeCallbacksAndMessages(null);}
    private void dismissDialog(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    private void selected(TextView view,boolean selected){view.setSelected(selected);view.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);}
    private LinearLayout row(String label){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);TextView name=button(label,null);name.setTextSize(15);name.setGravity(Gravity.CENTER_VERTICAL);row.addView(name,new LinearLayout.LayoutParams(dp(68),dp(48)));return row;}
    private TextView button(String text,Runnable click){TextView view=new TextView(activity);view.setText(text);view.setTextColor(Color.BLACK);view.setTextSize(16);view.setGravity(Gravity.CENTER);if(click!=null){view.setFocusable(true);view.setOnClickListener(v->click.run());}return view;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}

