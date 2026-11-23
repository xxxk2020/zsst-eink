package com.xxxk2020.zssteink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small native help page; it never opens a document or a browser inside the app. */
final class NativeAboutView extends LinearLayout {
    private final Activity activity;
    private Runnable exit;
    private int taps;
    private long lastTap;
    private AlertDialog details;
    NativeAboutView(Activity activity,Runnable exit) {
        super(activity);this.activity=activity;this.exit=exit;
        setOrientation(VERTICAL);setBackgroundColor(0xffffffff);setPadding(dp(16),0,dp(16),0);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹",26,false);back.setGravity(Gravity.CENTER);back.setContentDescription("返回设置");back.setOnClickListener(v->back());
        header.addView(back,new LayoutParams(dp(44),dp(48)));header.addView(text("关于与帮助",20,true),new LayoutParams(0,dp(48),1));addView(header);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);body.setPadding(dp(8),dp(24),dp(8),dp(24));
        ImageView logo=new ImageView(activity);logo.setImageResource(R.drawable.ic_launcher_legacy);logo.setContentDescription("纸上刷题墨水屏版");logo.setOnClickListener(v->logoTapped());
        LayoutParams image=new LayoutParams(dp(88),dp(88));image.gravity=Gravity.CENTER_HORIZONTAL;body.addView(logo,image);
        TextView name=text("纸上刷题 · 墨水屏版",23,true);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(16),0,0);body.addView(name);
        TextView slogan=text("以题为舟，终抵上岸",16,false);slogan.setGravity(Gravity.CENTER);slogan.setPadding(0,dp(10),0,0);body.addView(slogan);
        TextView version=text("版本 "+BuildConfig.VERSION_NAME+" · MIT 开源",14,false);version.setGravity(Gravity.CENTER);version.setPadding(0,dp(10),0,dp(22));body.addView(version);
        section(body,"导入与开始","在首页选择行测或申论，通过资源图标导入本地 JSON 或资源包 ZIP。长按资料可选择模式、归档、导出或删除；删除时可选择是否同时清除本卷记录。");
        section(body,"做题与阅读","刷题模式交卷后显示答案与解析，包括未作答题。练习模式按题核对。点击顶栏题号打开答题卡，Aa 调整字体、字号、字重、方向和全屏。");
        section(body,"长材料","右侧窄区上半部向前翻页，下半部向后翻页。共用材料支持独立滚动、拖动分区和换题时保留位置。申论可分别浏览材料与题目。");
        section(body,"进度与收藏","作答、收藏与阅读位置保存在本机。设置中的资料管理可导出或导入进度备份。卸载会移除应用数据，换设备前请先备份。");
        section(body,"开源与资料","原创应用代码、几何图标和演示题采用 MIT 许可证。可导入自己有权使用的题库、图片和字体；内容授权与源码许可分别处理。第三方授权见仓库 THIRD_PARTY_NOTICES.md。");
        TextView project=text("查看开源项目  ›",17,true);project.setPadding(0,dp(16),0,dp(16));project.setFocusable(true);project.setOnClickListener(v->{try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/xxxk2020/zsst-eink")));}catch(Exception unavailable){new AlertDialog.Builder(activity).setMessage("开源项目：github.com/xxxk2020/zsst-eink").setPositiveButton("关闭",null).show();}});body.addView(project);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);addView(scroll,new LayoutParams(-1,0,1));
    }
    private void section(LinearLayout body,String title,String message){TextView h=text(title,17,true);h.setPadding(0,dp(16),0,dp(6));body.addView(h);body.addView(text(message,15,false));}
    private void logoTapped(){long now=SystemClock.uptimeMillis();taps=now-lastTap<2000?taps+1:1;lastTap=now;if(taps<5)return;taps=0;if(details!=null)details.dismiss();details=new AlertDialog.Builder(activity).setTitle("版本与构建信息").setMessage("版本："+BuildConfig.VERSION_NAME+"\n构建："+BuildConfig.VERSION_CODE+"\n提交："+BuildConfig.GIT_COMMIT+"\n最低支持：Android 5.1\n界面：Android 原生").setPositiveButton("关闭",null).show();}
    void back(){if(exit!=null)exit.run();}
    void release(){if(details!=null){details.dismiss();details=null;}exit=null;}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextColor(0xff111111);view.setTextSize(size);view.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
