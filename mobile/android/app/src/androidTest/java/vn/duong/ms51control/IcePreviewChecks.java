package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** Captures actual native views with offline fixtures, never contacts a device. */
final class IcePreviewChecks {
    static Object call(Object a,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,args);
    }
    static void set(Object a,String name,Object value)throws Exception {ManagedChecks.set(a,name,value);}
    static Object get(Object a,String name)throws Exception {return ManagedChecks.get(a,name);}
    static void state(Object a,String field,String name)throws Exception {
        Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);f.set(a,Enum.valueOf((Class)f.getType(),name));
    }
    static void run(Instrumentation test) {
        Bundle report=new Bundle();
        try {
            MainActivity a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicReference<Throwable> error=new AtomicReference<>();
            test.runOnMainSync(()->{try {
                set(a,"foreground",false);call(a,"stopDashboard",new Class[]{});call(a,"stopHome",new Class[]{});
                call(a,"showAuth",new Class[]{boolean.class},false);capture(a,"01-login",false);
                call(a,"showAuth",new Class[]{boolean.class},true);capture(a,"01-register",false);
                set(a,"demoMode",true);set(a,"username","Anh Trung");set(a,"accountId","offline-preview");
                DeviceStore store=(DeviceStore)get(a,"deviceStore");
                DeviceStore.Device device=new DeviceStore.Device("ms51-000000000001","Đầu kéo 01","","","Cabin Wi-Fi");
                store.save("offline-preview",device);call(a,"showDevices",new Class[]{});call(a,"stopHome",new Class[]{});capture(a,"02-devices",false);
                store.remove("offline-preview",device.id);set(a,"activeDevice",device);
                EspApi demo=new EspApi();demo.setDemo(true);JSONObject status=demo.get("/api/status"),debug=demo.get("/api/debug");
                JSONObject sensor=new JSONObject().put("gpio",5).put("enabled",true).put("valid",true).put("age_ms",100).put("sensor","ok").put("temperature_centi",3050);
                status.put("analog",sensor).put("analog20k",new JSONObject(sensor.toString()).put("gpio",6).put("temperature_centi",3080)).put("analog50k",new JSONObject(sensor.toString()).put("gpio",4).put("temperature_centi",2700));
                status.put("trigger",new JSONObject().put("enabled",true).put("thermal_supported",true).put("thermal_enabled",true).put("source","10K").put("threshold_centi",8000).put("defrost_centi",2500).put("safety_supported",true).put("safety_enabled",true).put("defrost_enabled",true).put("sensor_valid",true).put("defrost_valid",true).put("synced",true));
                status.getJSONObject("wifi").getJSONObject("uplink").put("ssid","Cabin Wi-Fi").put("rssi",-54).put("state","connected");
                debug.put("last_rx_age_ms",100);
                debug.getJSONArray("variables").put(new JSONObject().put("name","selected_mode").put("value","0").put("age_ms",100)).put(new JSONObject().put("name","mode_source").put("value","PHYSICAL").put("age_ms",100));
                status.put("busy",false);
                for(int i=0;i<debug.getJSONArray("variables").length();i++)debug.getJSONArray("variables").getJSONObject(i).put("age_ms",100);
                set(a,"lastStatus",status);set(a,"lastDebug",debug);
                set(a,"lastHistory",new long[]{7620,5460,8400});
                state(a,"screen","CONTROL");state(a,"tab","OVERVIEW");
                LinearLayout overview=page(a,"Tổng quan");call(a,"buildOverview",new Class[]{LinearLayout.class},overview);
                call(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);call(a,"stopCounting",new Class[]{});
                capture(a,"03-overview",true);
                for(int i=0;i<debug.getJSONArray("variables").length();i++) {
                    JSONObject variable=debug.getJSONArray("variables").getJSONObject(i);
                    if("selected_mode".equals(variable.optString("name")))variable.put("value","1");
                    if("mode".equals(variable.optString("name")))variable.put("value","ICE_FLUSH");
                }
                call(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);call(a,"stopCounting",new Class[]{});
                capture(a,"03-overview-defrost",true);
                state(a,"tab","SETTINGS");LinearLayout settings=page(a,"Cài đặt");
                RuntimeConfigCodec.Config config=RuntimeConfigCodec.defaults();config.values.put("coolCl",6);config.values.put("coolOp",5);config.values.put("defrostCl",5);config.values.put("defrostOp",32);config.values.put("delaySt",70);config.values.put("stLed",50);config.values.put("stLcd",50);
                set(a,"runtimeConfig",config);set(a,"configLoaded",true);call(a,"buildSettings",new Class[]{LinearLayout.class},settings);capture(a,"04-settings",true);
                captureSection(a,settings,"Cài đặt nâng cao","04-advanced-collapsed");
                SettingsLayoutChecks.expandGroups(settings);capture(a,"04-settings-expanded",true);
                captureSection(a,settings,"Cài đặt nâng cao","04-startup-cycle");
                captureSection(a,settings,"Giới hạn thời gian","04-limits");
                captureSection(a,settings,"Mật khẩu bảo vệ","04-password");
                state(a,"tab","FIRMWARE");LinearLayout update=page(a,"Cập nhật");call(a,"buildManagedFirmware",new Class[]{LinearLayout.class,JSONObject.class,int.class},update,new JSONObject(),-50);capture(a,"05-update",false);
                state(a,"tab","DEVICE");LinearLayout info=page(a,"Thiết bị");call(a,"buildDevice",new Class[]{LinearLayout.class},info);capture(a,"06-device",true);
                state(a,"screen","AUTH");set(a,"demoMode",false);call(a,"showConnectDevice",new Class[]{boolean.class},false);capture(a,"07-pair",false);
                call(a,"stopDashboard",new Class[]{});call(a,"stopHome",new Class[]{});
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw error.get();
            report.putString("stream","PASS: offline native preview captured, no device commands sent.");test.finish(-1,report);
        }catch(Throwable e){report.putString("stream","FAIL: "+e);test.finish(0,report);}
    }
    static LinearLayout page(MainActivity a,String title)throws Exception {
        LinearLayout content=(LinearLayout)call(a,"createPage",new Class[]{String.class,String.class,String.class},"",title,"");
        call(a,"addNavigation",new Class[]{LinearLayout.class},content);return content;
    }
    static void captureSection(MainActivity a,LinearLayout content,String title,String name)throws Exception {
        android.widget.ScrollView scroll=(android.widget.ScrollView)content.getParent();
        View section=findSection(content,title);
        if(section==null)throw new AssertionError("Missing preview section: "+title);
        android.graphics.Rect rect=new android.graphics.Rect(0,0,section.getWidth(),section.getHeight());
        content.offsetDescendantRectToMyCoords(section,rect);
        scroll.scrollTo(0,Math.max(0,rect.top-Math.round(12*a.getResources().getDisplayMetrics().density)));
        capture(a,name,false);
    }
    static View findSection(View view,String title) {
        if(!(view instanceof ViewGroup))return null;
        ViewGroup group=(ViewGroup)view;
        if(group.getChildCount()>0 && group.getChildAt(0) instanceof android.widget.TextView
                && ((android.widget.TextView)group.getChildAt(0)).getText().toString().startsWith(title))return group;
        for(int i=0;i<group.getChildCount();i++){View found=findSection(group.getChildAt(i),title);if(found!=null)return found;}
        return null;
    }
    static void capture(MainActivity a,String name,boolean full)throws Exception {
        ViewGroup root=(ViewGroup)get(a,"pageRoot");
        int w=a.getResources().getDisplayMetrics().widthPixels,h=a.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        save(a,root,name+".png",w,h);
        if(full)for(int i=0;i<root.getChildCount();i++)if(root.getChildAt(i) instanceof android.widget.ScrollView){View content=((ViewGroup)root.getChildAt(i)).getChildAt(0);save(a,content,name+"-full.png",content.getWidth(),content.getHeight());}
    }
    static void save(MainActivity a,View view,String name,int w,int h)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);c.drawColor(Design.BG);view.draw(c);
        File folder=new File(a.getExternalFilesDir(null),"ui-preview");folder.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(folder,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
}

