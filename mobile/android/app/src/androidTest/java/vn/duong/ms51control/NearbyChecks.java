package vn.duong.ms51control;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.*;

/** Real popup/GIF rendered on Android; discovery events are simulated, no radio required. */
final class NearbyChecks {
    static void run(Instrumentation test){
        Bundle report=new Bundle();MainActivity activity=null;
        AtomicReference<NearbyDeviceSheet> sheet=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger selected=new AtomicInteger(),dismissed=new AtomicInteger();
        try {
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity;
            android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)a.getSystemService(android.content.Context.BLUETOOTH_SERVICE);
            boolean autoChecked=manager!=null && manager.getAdapter()!=null && manager.getAdapter().isEnabled();
            if(autoChecked){
                long autoDeadline=SystemClock.elapsedRealtime()+5000;
                AtomicReference<Object> picker=new AtomicReference<>();
                while(picker.get()==null && SystemClock.elapsedRealtime()<autoDeadline){
                    test.runOnMainSync(()->{try{picker.set(ManagedChecks.get(a,"blePicker"));}catch(Exception e){failure.set(e);}});
                    SystemClock.sleep(100);
                }
                check(picker.get()!=null,"Opening app starts discovery without a button tap");
                test.runOnMainSync(()->{try{
                    check(!(Boolean)field(picker.get(),"closed"),"Automatic scan remains active");
                    a.onPause();
                    check(ManagedChecks.get(a,"blePicker")==null && (Boolean)field(picker.get(),"closed"),"Background stops scan and closes popup owner");
                    a.onResume();ManagedChecks.set(a,"nearbySuppressed",true);
                }catch(Throwable e){failure.set(e);}});
            }
            test.runOnMainSync(()->{try{
                ManagedChecks.set(a,"nearbySuppressed",true);
                check(!ManagedChecks.texts(a.getWindow().getDecorView()).contains("Dùng Bluetooth"),"No manual Bluetooth entry button");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));
                check(!dialog(sheet.get()).isShowing(),"No popup before discovery");
                sheet.get().addDevice("38:44:BE:1F:E4:E0","HenGio-1FE4E0",selected::incrementAndGet);
                sheet.get().addDevice("38:44:BE:1F:E4:E0","Duplicate",()->selected.addAndGet(100));
                check(dialog(sheet.get()).isShowing(),"Popup opens on discovery");
                check(!ManagedChecks.texts(dialog(sheet.get()).getWindow().getDecorView()).contains("Duplicate"),"Advertisements deduplicated");
            }catch(Throwable e){failure.set(e);}});
            check(failure.get()==null,String.valueOf(failure.get()));
            long deadline=SystemClock.elapsedRealtime()+8000;
            AtomicBoolean loaded=new AtomicBoolean();
            while(!loaded.get() && SystemClock.elapsedRealtime()<deadline){
                test.runOnMainSync(()->{try{DeviceSpinView spin=find(dialog(sheet.get()).getWindow().getDecorView(),DeviceSpinView.class);loaded.set(field(spin,"movie")!=null);}catch(Exception e){failure.set(e);}});
                SystemClock.sleep(100);
            }
            check(loaded.get(),"Bundled GIF decodes on Android");SystemClock.sleep(400);
            AtomicReference<android.graphics.Bitmap> frame=new AtomicReference<>();
            test.runOnMainSync(()->{try{frame.set(drawSpin(sheet.get()));}catch(Exception e){failure.set(e);}});
            SystemClock.sleep(300);
            test.runOnMainSync(()->{try{
                android.graphics.Bitmap next=drawSpin(sheet.get());
                check(!next.sameAs(frame.get()),"Product actually rotates rather than displaying a static GIF frame");
                next.recycle();frame.get().recycle();
            }catch(Exception e){failure.set(e);}});
            check(failure.get()==null,String.valueOf(failure.get()));
            android.graphics.Bitmap shot=test.getUiAutomation().takeScreenshot();
            java.io.File folder=new java.io.File(a.getExternalFilesDir(null),"nearby-preview");folder.mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"01-popup-thiet-bi.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            test.runOnMainSync(()->{try{
                DeviceSpinView spin=find(dialog(sheet.get()).getWindow().getDecorView(),DeviceSpinView.class);
                spin.performClick();spin.performClick();
                check(selected.get()==1 && dismissed.get()==0,"One tap, no duplicate connection and no dismissal callback");
                check(!dialog(sheet.get()).isShowing(),"Connection closes popup");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));
                sheet.get().addDevice("38:44:BE:1F:E4:E0","First",selected::incrementAndGet);
                sheet.get().addDevice("38:44:BE:1F:E4:E1","Second",()->selected.addAndGet(10));
                Button second=findButton(dialog(sheet.get()).getWindow().getDecorView(),"Second");check(second!=null,"Other nearby devices selectable");second.performClick();
                check(selected.get()==11,"Select the requested device, not the first one");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));sheet.get().addDevice("38:44:BE:1F:E4:E0","Dismiss",selected::incrementAndGet);
                dialog(sheet.get()).cancel();
            }catch(Throwable e){failure.set(e);}});
            test.waitForIdleSync();check(failure.get()==null,String.valueOf(failure.get()));check(dismissed.get()==1,"User dismissal suppresses rediscovery popup");
            report.putString("stream","PASS: silent discovery popup, GIF rotation, deduplication, one-tap connection, double-tap guard, multiple devices, dismiss; automatic scan/lifecycle="+autoChecked+".\n");test.finish(-1,report);
        }catch(Throwable e){report.putString("stream","FAIL: "+e+"\n");test.finish(0,report);}
        finally{if(activity!=null){MainActivity a=activity;test.runOnMainSync(()->{if(sheet.get()!=null)sheet.get().close();a.finish();});}}
    }
    private static Object field(Object object,String name)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static android.graphics.Bitmap drawSpin(NearbyDeviceSheet sheet)throws Exception{
        DeviceSpinView view=find(dialog(sheet).getWindow().getDecorView(),DeviceSpinView.class);
        android.graphics.Bitmap frame=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(frame));return frame;
    }
    private static AlertDialog dialog(NearbyDeviceSheet sheet)throws Exception{return (AlertDialog)field(sheet,"dialog");}
    private static <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){T found=find(g.getChildAt(i),type);if(found!=null)return found;}}return null;}
    private static Button findButton(View view,String text){if(view instanceof Button && ((Button)view).getText().toString().startsWith(text))return (Button)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Button b=findButton(g.getChildAt(i),text);if(b!=null)return b;}}return null;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
