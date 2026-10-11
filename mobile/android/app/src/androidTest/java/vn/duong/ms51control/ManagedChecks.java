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
                } catch(Throwable error) {failure.set(error);}
            });
            if(failure.get()!=null)throw new AssertionError(failure.get());
            result.putString("stream","PASS: field-level visibility, independent range limits, hidden PIN controls, firmware available/empty/running/unknown/success states and device-switch guard.");
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
