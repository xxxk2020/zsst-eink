package com.xxxk2020.zssteink;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Local-only resource entry. File access is delegated to Android's document picker. */
final class NativeResourcesView extends LinearLayout {
    interface Listener { boolean isBusy(); void onExit(); void onImport(boolean progress); }
    private final Activity activity;
    private Listener listener;
    private final TextView status;

    NativeResourcesView(Activity activity, Listener listener) {
        super(activity); this.activity=activity; this.listener=listener;
        setOrientation(VERTICAL); setBackgroundColor(0xffffffff); setPadding(dp(12),0,dp(12),0);
        LinearLayout bar=new LinearLayout(activity); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹",26,false); back.setGravity(Gravity.CENTER); back.setContentDescription("返回题库"); back.setOnClickListener(v->back());
        bar.addView(back,new LayoutParams(dp(44),dp(48))); bar.addView(text("本地资源",20,true),new LayoutParams(0,dp(48),1)); addView(bar);
        status=text("",14,false); status.setVisibility(GONE); addView(status);
        LinearLayout body=new LinearLayout(activity); body.setOrientation(VERTICAL);
        TextView intro=text("导入自己的试卷和练习集，保存后即可离线学习。",16,false); intro.setPadding(dp(4),dp(18),dp(4),dp(14)); body.addView(intro);
        body.addView(action("导入试卷 / 练习集","支持 Doc v1 JSON 和带独立图片的资源包 ZIP",false));
        body.addView(action("导入学习进度备份","恢复作答、收藏、阅读位置和历史记录",true));
        TextView help=text("JSON 可内嵌 Base64 图片；独立图片应与 JSON 一起装入资源包。字体可在阅读设置中从本地导入。\n\n首次使用可导入项目 examples 目录中的原创演示资料。",15,false);
        help.setPadding(dp(4),dp(18),dp(4),dp(18)); body.addView(help);
        ScrollView scroll=new ScrollView(activity); scroll.addView(body); addView(scroll,new LayoutParams(-1,0,1));
    }
    private TextView action(String title,String detail,boolean progress) {
        TextView view=text(title+"\n"+detail,17,false); view.setPadding(dp(4),dp(16),dp(4),dp(16)); view.setMinHeight(dp(80)); view.setFocusable(true);
        view.setOnClickListener(v->{if(listener!=null&&!listener.isBusy())listener.onImport(progress);}); return view;
    }
    void showStatus(String message){status.setText(NativeDisplayText.clean(message));status.setVisibility(message.length()==0?GONE:VISIBLE);}
    void back(){if(listener==null)return;if(listener.isBusy()){Toast.makeText(activity,"请等待当前文件操作完成",Toast.LENGTH_SHORT).show();return;}listener.onExit();}
    void release(){listener=null;}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextColor(0xff111111);view.setTextSize(size);view.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
