package com.xxxk2020.zssteink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small vector glyphs with full 44dp touch targets; no font-dependent symbols. */
final class NativeHomeIcon extends View {
    private final int kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    NativeHomeIcon(Context context,int kind,String label,Runnable click){
        super(context);this.kind=kind;setContentDescription(label);setFocusable(true);setClickable(true);setOnClickListener(v->click.run());
        setMinimumWidth(dp(44));setMinimumHeight(dp(44));paint.setColor(0xff111111);paint.setStrokeWidth(dp(1.6f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);paint.setStyle(Paint.Style.STROKE);float unit=getResources().getDisplayMetrics().density;
        if(kind==0){
            canvas.drawLine(0,-9*unit,0,4*unit,paint);canvas.drawLine(-5*unit,-unit,0,4*unit,paint);canvas.drawLine(5*unit,-unit,0,4*unit,paint);
            path.reset();path.moveTo(-9*unit,3*unit);path.lineTo(-9*unit,9*unit);path.lineTo(9*unit,9*unit);path.lineTo(9*unit,3*unit);canvas.drawPath(path,paint);
        }else if(kind==1){
            path.reset();for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=(i%2==0?11:4.8f)*unit;float x=(float)Math.cos(angle)*radius,y=(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,paint);
        }else{paint.setStyle(Paint.Style.FILL);for(int i=-1;i<=1;i++)canvas.drawCircle(i*7*unit,0,1.7f*unit,paint);}
        canvas.restore();
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
