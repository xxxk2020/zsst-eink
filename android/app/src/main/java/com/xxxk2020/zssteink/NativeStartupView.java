package com.xxxk2020.zssteink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.Random;

/** Static, code-drawn launch view. No animation, font asset or minimum wait. */
final class NativeStartupView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable logo;
    private final int selected;
    NativeStartupView(Context context){
        super(context);setBackgroundColor(Color.WHITE);
        selected=new Random().nextInt(4);logo=getResources().getDrawable(R.drawable.ic_launcher_legacy,context.getTheme());
        setContentDescription("纸上刷题。以题为舟，终抵上岸。");
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float scale=Math.min(getWidth()/540f,getHeight()/720f);if(scale<=0)return;
        canvas.save();canvas.translate((getWidth()-540f*scale)/2f,(getHeight()-720f*scale)/2f-getHeight()*.03f);canvas.scale(scale,scale);
        logo.setBounds(188,120,352,284);logo.draw(canvas);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.BLACK);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));paint.setTextSize(38);
        canvas.drawText("纸上刷题",270,352,paint);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(21);
        for(int i=0;i<4;i++){float x=162+72*i;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(Color.BLACK);canvas.drawCircle(x,414,19,paint);if(i==selected){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,414,19,paint);}paint.setStyle(Paint.Style.FILL);paint.setColor(i==selected?Color.WHITE:Color.BLACK);canvas.drawText(String.valueOf("ABCD".charAt(i)),x,421,paint);}
        paint.setColor(Color.BLACK);paint.setTextSize(20);canvas.drawText("以题为舟，终抵上岸",270,510,paint);canvas.restore();
    }
}
