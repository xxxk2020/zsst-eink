"""Exercise presentation scheduling without an Android device or learning data."""
from pathlib import Path
import subprocess,shutil,tempfile
root=Path(__file__).resolve().parents[1]
stubs={
'android/os/SystemClock.java':'''package android.os; public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}''',
'android/view/ViewTreeObserver.java':'''package android.view;
public class ViewTreeObserver { public interface OnPreDrawListener {boolean onPreDraw();}
public OnPreDrawListener listener; public boolean isAlive(){return true;}
public void addOnPreDrawListener(OnPreDrawListener l){listener=l;}
public void removeOnPreDrawListener(OnPreDrawListener l){if(listener==l)listener=null;} }''',
'android/view/View.java':'''package android.view;
public class View {public ViewTreeObserver tree=new ViewTreeObserver();public boolean layout;public Runnable pending;
public ViewTreeObserver getViewTreeObserver(){return tree;} public void invalidate(){} public boolean isLayoutRequested(){return layout;}
public void removeCallbacks(Runnable r){if(pending==r)pending=null;}public void postDelayed(Runnable r,long delay){pending=r;}}''',
'com/xxxk2020/zssteink/EinkFrameGateTest.java':'''package com.xxxk2020.zssteink;
import android.view.View;import android.os.SystemClock;
public class EinkFrameGateTest {
static void check(boolean value){if(!value)throw new AssertionError();}
public static void main(String[] args){
 View v=new View();boolean[] ready={false};int[] done={0};SystemClock.now=0;
 EinkFrameGate gate=new EinkFrameGate(v,()->ready[0],()->done[0]++);
 check(!gate.onPreDraw());check(done[0]==0);ready[0]=true;check(!gate.onPreDraw());
 ready[0]=false;check(!gate.onPreDraw());ready[0]=true;v.layout=true;check(!gate.onPreDraw());
 v.layout=false;check(!gate.onPreDraw());check(gate.onPreDraw());check(done[0]==1);check(v.tree.listener==null&&v.pending==null);
 check(gate.onPreDraw());check(done[0]==1);
 SystemClock.now=0;gate=new EinkFrameGate(v,()->false,()->done[0]++);SystemClock.now=4000;
 check(gate.onPreDraw());check(done[0]==2);
 SystemClock.now=0;gate=new EinkFrameGate(v,()->false,()->done[0]++);check(!gate.onPreDraw());gate.cancel();
 check(v.tree.listener==null&&v.pending==null);check(gate.onPreDraw());check(done[0]==2);
 System.out.println("PASS: stable layout, image readiness, deadline and cancellation checks");
}}'''}
java=shutil.which('java');javac=shutil.which('javac')
if not java or not javac:raise SystemExit('JDK is required')
with tempfile.TemporaryDirectory(prefix='zsst-eink-') as folder:
 base=Path(folder)
 for name,source in stubs.items():
  p=base/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source,encoding='utf-8')
 files=[str(p) for p in base.rglob('*.java')]+[str(root/'android/app/src/main/java/com/xxxk2020/zssteink/EinkFrameGate.java')]
 subprocess.run([javac,'--release','8','-encoding','UTF-8','-d',folder]+files,check=True)
 subprocess.run([java,'-cp',folder,'com.xxxk2020.zssteink.EinkFrameGateTest'],check=True)
