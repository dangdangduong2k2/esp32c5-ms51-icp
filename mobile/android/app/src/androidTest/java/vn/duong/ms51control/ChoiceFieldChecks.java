package vn.duong.ms51control;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ListPopupWindow;
import java.util.concurrent.atomic.AtomicReference;

/** Offline attached-window regression for the anchored picker, no device commands. */
final class ChoiceFieldChecks {
 static ListPopupWindow popup(ChoiceField field)throws Exception {
  java.lang.reflect.Field f=ChoiceField.class.getDeclaredField("popup");f.setAccessible(true);return (ListPopupWindow)f.get(field);
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void main(Instrumentation test,Runnable action){test.runOnMainSync(action);test.waitForIdleSync();}
 static void run(Instrumentation test){
  Bundle result=new Bundle();MainActivity a=null;
  try {
   a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   final MainActivity activity=a;final ChoiceField[] fields=new ChoiceField[2];final LinearLayout[] page=new LinearLayout[1];
   main(test,()->{
    LinearLayout layout=new LinearLayout(activity);layout.setOrientation(1);layout.setPadding(48,100,48,48);layout.setBackgroundColor(Design.BG);page[0]=layout;
    TextView heading=new TextView(activity);heading.setText("Cài đặt nâng cao");heading.setTextColor(Design.INK);heading.setTextSize(22);layout.addView(heading);
    TextView label=new TextView(activity);label.setText("Kiểu màn hình");label.setPadding(0,64,0,16);layout.addView(label);
    fields[0]=new ChoiceField(activity,new String[]{"LCD","LED"});fields[0].setContentDescription("Kiểu màn hình");layout.addView(fields[0],new LinearLayout.LayoutParams(480,-2));
    TextView second=new TextView(activity);second.setText("Số lần bật nguồn và khởi động");second.setPadding(0,64,0,16);layout.addView(second);
    fields[1]=new ChoiceField(activity,new String[]{"Nguồn: 1 lần · Khởi động: 1 lần","Nguồn: 2 lần · Khởi động: 1 lần","Nguồn: 1 lần · Khởi động: 2 lần","Nguồn: 2 lần · Khởi động: 2 lần"});layout.addView(fields[1],new LinearLayout.LayoutParams(-1,-2));
    activity.setContentView(layout);
   });
   ChoiceField field=fields[0];
   main(test,()->{long t=android.os.SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(t,t+20,action,40,40,0);field.dispatchTouchEvent(e);e.recycle();}});
   check(popup(field)!=null&&popup(field).isShowing(),"Touch opens custom menu");
   check(popup(field).getWidth()==field.getWidth(),"Menu matches field width");
   shot(test,activity,"choice-short.png");
   main(test,()->{try{popup(field).performItemClick(1);}catch(Exception e){throw new RuntimeException(e);}});
   check(field.getSelectedItemPosition()==1&&"LED".equals(field.getSelectedItem()),"Selection preserves adapter identity");check(popup(field)==null,"Selection closes menu");
   main(test,field::performClick);main(test,()->{try{popup(field).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
   check(field.getSelectedItemPosition()==1,"Dismiss does not change value");
   main(test,field::performClick);main(test,()->field.setEnabled(false));check(popup(field)==null,"Disable closes open menu");
   main(test,()->check(!field.performClick(),"Disabled field cannot open"));
   main(test,()->{field.setEnabled(true);field.performClick();});
   main(test,()->page[0].removeView(field));check(popup(field)==null,"Detach closes menu");
   main(test,()->fields[1].performClick());shot(test,activity,"choice-long.png");
   main(test,()->{try{popup(fields[1]).performItemClick(3);}catch(Exception e){throw new RuntimeException(e);}});
   check(fields[1].getSelectedItemPosition()==3,"All four values selectable");
   result.putString("stream","PASS: touch opens anchored menu, matching width, selection identity, dismiss preserves value, disabled and detached controls close menu, long options selectable.");test.finish(-1,result);
  }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));test.finish(0,result);}
  finally{if(a!=null){MainActivity done=a;test.runOnMainSync(done::finish);}}
 }
 static void shot(Instrumentation test,MainActivity a,String name)throws Exception{
  android.os.SystemClock.sleep(300);
  android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot();
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
 }
}
