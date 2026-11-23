package com.xxxk2020.zssteink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A metadata-only favorites directory; opening it does not scan question bodies or images. */
final class NativeFavoritesView extends LinearLayout {
    interface Listener { void back(); void open(Entry favorite); }
    static final class Entry {
        final String id, number;
        Entry(String id, String number) { this.id=id; this.number=number; }
    }
    static final class Group {
        final String title, subject, kind;
        final boolean available, archived;
        final List<Entry> entries=new ArrayList<>();
        Group(String title,String subject,String kind,boolean available,boolean archived) {
            this.title=title;this.subject=subject;this.kind=kind;this.available=available;this.archived=archived;
        }
    }
    static final class Snapshot {
        final List<Group> groups;
        Snapshot(List<Group> groups) { this.groups=groups; }
    }
    static Snapshot prepare(List<NativeRepository.Item> items,JSONArray stars,Set<String> archived) {
        Map<String,NativeRepository.Item> local=new LinkedHashMap<>();
        for(NativeRepository.Item item:items)local.put(item.id,item);
        Map<String,Group> groups=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<stars.length();i++) {
            JSONObject star=stars.optJSONObject(i);if(star==null)continue;
            String id=star.optString("id",""),number=String.valueOf(star.opt("no"));
            if(id.length()==0||number.equals("null")||!seen.add(id+"\u0000"+number))continue;
            Group group=groups.get(id);
            if(group==null) {
                NativeRepository.Item item=local.get(id);
                group=new Group(NativeDisplayText.title(item==null?star.optString("title",""):item.title,"未命名资料"),
                        item==null?star.optString("subject",""):item.subject,item==null?star.optString("kind",""):item.kind,
                        item!=null,archived.contains(id));groups.put(id,group);
            }
            group.entries.add(new Entry(id,number));
        }
        for(Group group:groups.values())Collections.sort(group.entries,new Comparator<Entry>() {
            @Override public int compare(Entry a,Entry b) {
                try{return Double.compare(Double.parseDouble(a.number),Double.parseDouble(b.number));}
                catch(NumberFormatException ignored){return a.number.compareTo(b.number);}
            }
        });
        return new Snapshot(new ArrayList<>(groups.values()));
    }
    private static final class Row {
        final String title,detail;final Entry entry;final boolean heading;
        Row(String title,String detail,Entry entry,boolean heading){this.title=title;this.detail=detail;this.entry=entry;this.heading=heading;}
    }
    private final Snapshot snapshot;
    private final Listener listener;
    private final ListView list;
    private final List<TextView> tabs=new ArrayList<>();
    private boolean exercises,released;
    NativeFavoritesView(Context context,Snapshot snapshot,Listener listener) {
        super(context);this.snapshot=snapshot;this.listener=listener;
        setOrientation(VERTICAL);setBackgroundColor(Color.WHITE);setPadding(dp(12),0,dp(12),0);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=action("‹");back.setContentDescription("返回首页");back.setTextSize(26);back.setOnClickListener(v->listener.back());
        header.addView(back,new LinearLayout.LayoutParams(dp(44),dp(52)));
        TextView title=label("我的收藏",21,true);header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));addView(header);
        LinearLayout selectors=new LinearLayout(context);
        for(int i=0;i<2;i++) {
            final boolean exercise=i==1;TextView tab=action(exercise?"练习":"试卷");tabs.add(tab);
            tab.setOnClickListener(v->{if(!released){exercises=exercise;render();}});
            selectors.addView(tab,new LinearLayout.LayoutParams(0,dp(44),1));
        }
        addView(selectors);list=new ListView(context);list.setDivider(null);list.setCacheColorHint(Color.WHITE);list.setVerticalScrollBarEnabled(false);
        addView(list,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private void render() {
        for(int i=0;i<tabs.size();i++){boolean selected=(i==1)==exercises;tabs.get(i).setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);tabs.get(i).setSelected(selected);}
        final List<Row> rows=new ArrayList<>();int count=0;
        for(Group group:snapshot.groups) {
            if(group.kind.length()==0)continue;
            boolean exercise="course".equals(group.kind)||"exercise".equals(group.kind)||"practice".equals(group.kind);
            if(exercise!=exercises)continue;count+=group.entries.size();appendGroup(rows,group,false);
        }
        if(count==0)rows.add(new Row(exercises?"还没有收藏的练习题":"还没有收藏的试卷题目","在答题页点 ☆ 收藏题目",null,true));
        for(Group group:snapshot.groups)if(group.kind.length()==0)appendGroup(rows,group,true);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount(){return rows.size();}
            @Override public Object getItem(int position){return rows.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public int getViewTypeCount(){return 2;}
            @Override public int getItemViewType(int position){return rows.get(position).heading?0:1;}
            @Override public boolean isEnabled(int position){return rows.get(position).entry!=null;}
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                Row row=rows.get(position);LinearLayout cell=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(getContext());
                cell.removeAllViews();cell.setOrientation(VERTICAL);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(row.heading?2:12),dp(9),dp(4),dp(9));cell.setMinimumHeight(dp(row.heading?66:48));
                TextView title=label(row.title,row.heading?17:18,row.heading);cell.addView(title,new LinearLayout.LayoutParams(-1,-2));
                if(row.detail.length()>0){TextView detail=label(row.detail,13,false);detail.setPadding(0,dp(4),0,0);cell.addView(detail,new LinearLayout.LayoutParams(-1,-2));}
                cell.setContentDescription(row.title+"，"+row.detail);return cell;
            }
        });
        list.setOnItemClickListener((parent,view,position,id)->{Entry entry=rows.get(position).entry;if(!released&&entry!=null)listener.open(entry);});
    }
    private void appendGroup(List<Row> rows,Group group,boolean unknown) {
        String detail=(group.subject.length()>0?group.subject+" · ":"")+group.entries.size()+" 道收藏"+(group.archived?" · 已归档":"");
        if(!group.available)detail+=" · 资料未在本机";
        if(unknown)detail+=" · 类型待恢复";
        rows.add(new Row(group.title,detail,null,true));
        for(Entry entry:group.entries)rows.add(new Row("第 "+entry.number+" 题  ›",group.available?"进入原资料，保留作答状态":"重新导入原资料后可打开",entry,false));
    }
    void release(){released=true;list.setAdapter(null);list.setOnItemClickListener(null);}
    private TextView label(String value,int size,boolean bold){TextView text=new TextView(getContext());text.setText(value);text.setTextColor(Color.BLACK);text.setTextSize(size);text.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL);text.setGravity(Gravity.CENTER_VERTICAL);return text;}
    private TextView action(String value){TextView text=label(value,17,false);text.setGravity(Gravity.CENTER);text.setMinimumHeight(dp(44));text.setFocusable(true);return text;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
