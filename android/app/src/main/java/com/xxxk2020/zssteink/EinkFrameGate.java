package com.xxxk2020.zssteink;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

/** Hold the previous frame while layout and visible images settle, without a screenshot bitmap. */
final class EinkFrameGate implements ViewTreeObserver.OnPreDrawListener {
    interface Ready { boolean get(); }
    private final View view;
    private final Ready ready;
    private final Runnable revealed;
    private final long deadline=SystemClock.uptimeMillis()+4000;
    private int stable;
    private boolean closed;
    private final Runnable retry;
    EinkFrameGate(View view,Ready ready,Runnable revealed) {
        this.view=view;this.ready=ready;this.revealed=revealed;
        retry=()->{if(!closed)this.view.invalidate();};
        view.getViewTreeObserver().addOnPreDrawListener(this);view.invalidate();
    }
    @Override public boolean onPreDraw() {
        if(closed)return true;
        stable=ready.get()&&!view.isLayoutRequested()?stable+1:0;
        if(stable>=2||SystemClock.uptimeMillis()>=deadline) {
            cancel();if(revealed!=null)revealed.run();return true;
        }
        view.removeCallbacks(retry);view.postDelayed(retry,32);return false;
    }
    void cancel() {
        if(closed)return;closed=true;view.removeCallbacks(retry);
        if(view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(this);
    }
}
