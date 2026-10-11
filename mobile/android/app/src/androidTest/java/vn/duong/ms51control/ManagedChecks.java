package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Offline UI checks: no account, MQTT or physical firmware actions. */
final class ManagedChecks {
    static void run(Instrumentation test) {
        Bundle result=new Bundle(); MainActivity activity=null;
        try {
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity; AtomicReference<Throwable> failure=new AtomicReference<>();
            test.runOnMainSync(() -> {
                try {
                    set(a,"demoMode",false);
                    set(a,"deviceAccess",new JSONObject().put("visible",new JSONArray().put("coolCl").put("rangeCoolClMin").put("pinPassword")));
                    LinearLayout content=new LinearLayout(a);
                    invoke(a,"buildSettings",new Class[]{LinearLayout.class},content);
                    Map<?,?> fields=(Map<?,?>)get(a,"configInputs");
                    check(fields.size()==3 && fields.containsKey("coolCl") && fields.containsKey("rangeCoolClMin") && fields.containsKey("pinPassword"),"Only allowed fields rendered");
                    check(((Map<?,?>)get(a,"configChoices")).isEmpty(),"Hidden choices omitted");
                    check(((Map<?,?>)get(a,"configChecks")).isEmpty(),"Hidden toggles omitted");
                    check(!texts(content).contains("Mật khẩu dự phòng"),"Hidden PIN field label omitted");
                    check(!texts(content).contains("Làm lạnh CL · Max"),"Min/Max independently hidden");
                    JSONObject firmware=new JSONObject().put("id","test-release");
                    for(String state:new String[]{"none","available","running","unknown","success"}) {
                        JSONObject access=new JSONObject().put("update_available",!state.equals("none")&&!state.equals("success"));
                        if(!state.equals("none"))access.put("firmware",firmware);
                        if(!state.equals("none")&&!state.equals("available"))access.put("job",new JSONObject().put("release","test-release").put("state",state).put("message","Fixture update status"));
                        LinearLayout card=new LinearLayout(a);
                        invoke(a,"buildManagedFirmware",new Class[]{LinearLayout.class,JSONObject.class,int.class},card,access,0);
                        ArrayList<Button> buttons=new ArrayList<>();collect(card,buttons);
                        check(buttons.size()==(state.equals("available")?1:0),"Firmware button count: "+state);
                        if(!buttons.isEmpty())check(buttons.get(0).getText().toString().equals("Cập nhật"),"Only managed update action");
                    }
                    set(a,"managedUpdateStarting",true);
                    invoke(a,"clearDeviceState",new Class[]{});
                    check(!(boolean)get(a,"managedUpdateStarting"),"Device switch clears pending UI flag");
                    byte[] raw=RuntimeConfigCodec.defaults().raw;
                    long[] durations={1,599,600,601,0xffffffffL};
                    for(int i=0;i<durations.length;i++)for(int b=0;b<4;b++)raw[0x33+i*4+b]=(byte)(durations[i] >>> (24-b*8));
                    // Last slot must survive sig_index wrap and intervening empty slots.
                    raw[0x32]=0; raw[0xAA]=10;
                    JSONObject response=DeviceHistory.fromConfig(RuntimeConfigCodec.toHex(raw));
                    long[] decoded=DeviceHistory.decode(response);
                    check(decoded.length==30 && decoded[4]==0xffffffffL && decoded[29]==10,"History offsets, uint32 and last slot");
                    check(DeviceHistory.format(599).equals("0 phút 59,9 giây"),"Sub-minute precision");
                    check(DeviceHistory.format(600).equals("1 phút"),"Minute conversion");
                    check(DeviceHistory.text(decoded).contains("L29    0 phút 1 giây"),"Original history index retained");
                    check(!DeviceHistory.text(decoded).contains("L05"),"Unused slots hidden");
                    try { DeviceHistory.decode(new JSONObject().put("ok",true).put("capacity",30).put("tick_ms",100).put("ticks",new JSONArray())); throw new AssertionError("Partial snapshot accepted"); }
                    catch(org.json.JSONException|IllegalArgumentException expected) { }
                    LinearLayout overview=new LinearLayout(a);
                    set(a,"lastHistory",decoded);
                    invoke(a,"buildOverview",new Class[]{LinearLayout.class},overview);
                    String historyText=((TextView)get(a,"dashboardLogs")).getText().toString();
                    check(historyText.contains("L00") && historyText.contains("L29"),"History appears without debug status");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},null,new JSONObject().put("enabled",true).put("logs",new JSONArray().put(new JSONObject().put("text","STATUS_SHOULD_NOT_APPEAR"))));
                    check(((TextView)get(a,"dashboardLogs")).getText().toString().equals(historyText),"Status logs never replace saved history");
                    set(a,"historyError","Mất kết nối");invoke(a,"renderHistory",new Class[]{});
                    check(((TextView)get(a,"dashboardLogs")).getText().toString().contains("Dữ liệu lần đọc trước"),"Stale history labelled");
                    invoke(a,"clearDeviceState",new Class[]{});
                    check(get(a,"lastHistory")==null && ((long)get(a,"nextHistoryRead"))==0,"Device switch clears history");
                    check(DeviceHistory.text(new long[30]).contains("Chưa có"),"Empty history explicit");
                } catch(Throwable error) {failure.set(error);}
            });
            if(failure.get()!=null)throw new AssertionError(failure.get());
            result.putString("stream","PASS: managed settings/firmware regression; history uint32 decoding, all 30 slots, duration precision, empty/stale/device-switch states, no status logs.");
            test.finish(-1,result);
        } catch(Throwable error) {result.putString("stream","FAIL: "+error);test.finish(0,result);}
        finally {if(activity!=null){MainActivity a=activity;test.runOnMainSync(a::finish);}}
    }
    static Object get(Object a,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    static void set(Object a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    static void invoke(Object a,String name,Class[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(a,args);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void collect(View v,ArrayList<Button> found){if(v instanceof Button)found.add((Button)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),found);}
    static String texts(View v){String s=v instanceof TextView?((TextView)v).getText().toString():"";if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s+="\n"+texts(((ViewGroup)v).getChildAt(i));return s;}
}
