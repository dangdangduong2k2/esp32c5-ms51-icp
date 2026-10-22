package vn.duong.ms51control;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import org.json.*;

/** Offline PIN prompt tests, never sends device commands. */
final class UnlockChecks {
 static Object get(Object a,String n)throws Exception{java.lang.reflect.Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 static void set(Object a,String n,Object v)throws Exception{java.lang.reflect.Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
 static void invoke(Object a,String n,JSONObject data)throws Exception{java.lang.reflect.Method m=a.getClass().getDeclaredMethod(n,JSONObject.class);m.setAccessible(true);m.invoke(a,data);}
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static JSONObject sample(int kind,int age)throws Exception{return new JSONObject().put("enabled",true).put("last_rx_age_ms",age).put("variables",new JSONArray()
  .put(new JSONObject().put("name","unlock_v").put("value","1").put("age_ms",age))
  .put(new JSONObject().put("name","pin_state").put("value",Integer.toString(kind)).put("age_ms",age)));}
 static EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText n=input(((ViewGroup)v).getChildAt(i));if(n!=null)return n;}return null;}
 static void run(Instrumentation test){Bundle out=new Bundle();MainActivity a=null;
  try{
   check(UnlockState.read(null)==-1,"missing");check(UnlockState.read(sample(1,4000))==-1,"stale");
   for(int i=0;i<4;i++)check(UnlockState.read(sample(i,10))==i,"explicit state");
   JSONObject old=sample(1,10);old.getJSONArray("variables").remove(0);check(UnlockState.read(old)==-1,"old firmware cannot prompt");
   old=sample(1,10);old.getJSONArray("variables").getJSONObject(1).put("age_ms",4001);check(UnlockState.read(old)==-1,"stale variable");
   a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   final MainActivity activity=a;final Throwable[] fail={null};
   test.runOnMainSync(()->{try{
    Class<?> screen=get(activity,"screen").getClass();set(activity,"screen",Enum.valueOf((Class)screen,"CONTROL"));set(activity,"foreground",true);
    invoke(activity,"updateUnlock",sample(1,10));AlertDialog d=(AlertDialog)get(activity,"unlockDialog");check(d!=null&&d.isShowing(),"automatic popup");
    invoke(activity,"updateUnlock",sample(1,10));check(get(activity,"unlockDialog")==d,"no duplicate popup on polling");
    EditText pin=input(d.getWindow().getDecorView());check(pin!=null&&pin.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,"PIN masked");
    d.getButton(-1).performClick();check(!(boolean)get(activity,"unlockSending"),"empty PIN never sent");
    invoke(activity,"updateUnlock",null);check(!d.getButton(-1).isEnabled(),"stale disables submission");
    invoke(activity,"updateUnlock",sample(2,10));AlertDialog backup=(AlertDialog)get(activity,"unlockDialog");check(backup!=d&&backup.isShowing(),"backup updates prompt");
    backup.getButton(-2).performClick();invoke(activity,"updateUnlock",sample(2,10));check(get(activity,"unlockDialog")==null || get(activity,"unlockDialog")==backup,"defer does not create a new prompt");
    invoke(activity,"updateUnlock",sample(0,10));invoke(activity,"updateUnlock",sample(1,10));check(get(activity,"unlockDialog")!=null,"new request prompts again");
    invoke(activity,"updateUnlock",sample(0,10));check(get(activity,"unlockDialog")==null,"physical unlock closes popup");
    invoke(activity,"updateUnlock",sample(3,10));check(get(activity,"unlockDialog")==null,"permanent lock never offered unlock");
    set(activity,"foreground",false);invoke(activity,"updateUnlock",sample(1,10));check(get(activity,"unlockDialog")==null,"background does not prompt");
    set(activity,"foreground",true);invoke(activity,"updateUnlock",sample(1,10));
   }catch(Throwable e){fail[0]=e;}});test.waitForIdleSync();if(fail[0]!=null)throw fail[0];
   java.io.File image=new java.io.File(a.getExternalFilesDir(null),"unlock-preview.png");
   try(java.io.FileOutputStream f=new java.io.FileOutputStream(image)){test.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}
   out.putString("stream","PASS: PIN/backup detection, freshness, old firmware, popup deduplication, masking, empty input, defer, physical unlock, permanent lock and background guard.");test.finish(-1,out);
  }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));test.finish(0,out);}
  finally{if(a!=null){final MainActivity end=a;test.runOnMainSync(end::finish);}}
 }
}
