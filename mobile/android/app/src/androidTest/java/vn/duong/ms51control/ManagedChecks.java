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
                    // Save an offline preview of the actual views for visual review.
                    EspApi demo=new EspApi(); demo.setDemo(true);
                    JSONObject sampleStatus=demo.get("/api/status");
                    JSONObject sensor=new JSONObject().put("enabled",true).put("gpio",1).put("valid",true)
                            .put("age_ms",100).put("sensor","ok").put("temperature_centi",2500);
                    sampleStatus.put("analog",sensor);
                    set(a,"lastStatus",sampleStatus);
                    JSONObject preview=demo.get("/api/debug");
                    preview.getJSONArray("variables").remove(5); // third relay awaits data
                    set(a,"lastDebug",preview);
                    long[] sample=new long[30]; sample[0]=7620; sample[1]=5460; sample[2]=8400;
                    set(a,"lastHistory",sample);
                    LinearLayout page=new LinearLayout(a);page.setOrientation(LinearLayout.VERTICAL);
                    page.setPadding(32,32,32,32);page.setBackgroundColor(Design.BG);
                    invoke(a,"addNavigation",new Class[]{LinearLayout.class},page);
                    invoke(a,"buildOverview",new Class[]{LinearLayout.class},page);
                    for(int gpio:new int[]{1,6}) for(int temperature:new int[]{-1800,0,2500,12500}) {
                        sensor.put("gpio",gpio);
                        sensor.put("temperature_centi",temperature);
                        invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,null);
                        check(((TextView)get(a,"temperatureValue")).getText().toString().equals(String.format(java.util.Locale.forLanguageTag("vi-VN"),"%.1f °C",temperature/100.0)),"Temperature remains available without MS51 telemetry");
                    }
                    for(String fault:new String[]{"open","short","out_of_range"}) {
                        sensor.put("sensor",fault);
                        invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                        check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Sensor fault never displays a temperature: "+fault);
                    }
                    sensor.put("sensor","ok").put("age_ms",2500);
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Stale temperature hidden");
                    sensor.put("age_ms",100).put("temperature_centi",JSONObject.NULL);
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Null is not zero degrees");
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},demo.get("/api/status"));
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Older firmware remains usable");
                    sensor.put("temperature_centi",2500);
                    JSONObject second = new JSONObject(sensor.toString()).put("gpio",4).put("temperature_centi",3150);
                    sampleStatus.put("analog50k",second);
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperature50Value")).getText().toString().equals("31,5 °C"),"Independent 50k reading");
                    second.put("sensor","open");
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperature50Value")).getText().toString().equals("— °C"),"50k fault hides only second reading");
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("25,0 °C"),"50k fault preserves 10k");
                    second.put("sensor","ok").put("age_ms",3000);
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperature50Value")).getText().toString().equals("— °C"),"50k stale hidden");
                    second.put("age_ms",100);
                    CountdownTracker tracker = new CountdownTracker();
                    check(tracker.update("COOL","GREEN","5","8",null)==1,"First sample at five already counts");
                    for (int i=0;i<120;i++) check(tracker.update("COOL","GREEN","5","8",null)==1,"Unchanged minutes keep blinking");
                    check(tracker.update("COOL","RED","5","8",null)==2,"Phase change switches immediately without digit change");
                    check(tracker.update("COOL","NONE","5","8",null)==0,"Paused timer stops with unchanged digits");
                    check(tracker.update("COOL","GREEN","5","8","ON")==0,"Remote pause stops blink");
                    check(tracker.update("SETTING","GREEN","5","8",null)==0,"Settings do not blink");
                    check(tracker.update("COOL",null,"4","8",null)==0,"Old firmware does not guess from digit decrease");
                    check(tracker.update("COOL","GREEN","N/A","8",null)==0,"Non-numeric display does not blink");
                    check(!texts(page).contains("MÀN HÌNH XANH") && !texts(page).contains("MÀN HÌNH ĐỎ"),"Timer labels removed");
                    check(texts(page).contains("10k") && texts(page).contains("50k"),"Both small sensor labels");
                    JSONObject activeTimer = new JSONObject().put("name","countdown_active").put("value","GREEN").put("age_ms",100);
                    preview.getJSONArray("variables").put(activeTimer);
                    for(int k=0;k<preview.getJSONArray("variables").length();k++)preview.getJSONArray("variables").getJSONObject(k).put("age_ms",100);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    SevenSegmentView green=(SevenSegmentView)get(a,"greenDisplay"), red=(SevenSegmentView)get(a,"redDisplay");
                    check(green.isCounting() && !red.isCounting(),"First sample blinks without a digit change");
                    Button cool=(Button)get(a,"coolModeButton"), ice=(Button)get(a,"iceModeButton"), refresh=(Button)get(a,"refreshModeButton");
                    check(!cool.isEnabled() && !ice.isEnabled(),"Older MS51 cannot receive soft mode commands");
                    JSONObject modeSource=new JSONObject().put("name","mode_source").put("value","PHYSICAL").put("age_ms",100);
                    JSONObject selectedMode=new JSONObject().put("name","selected_mode").put("value","0").put("age_ms",100);
                    preview.getJSONArray("variables").put(modeSource).put(selectedMode);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(cool.isEnabled() && ice.isEnabled(),"Fresh capable device enables both mode choices");
                    check(((LinearLayout.LayoutParams)cool.getLayoutParams()).weight > ((LinearLayout.LayoutParams)refresh.getLayoutParams()).weight,"Mode buttons wider than refresh");
                    check(cool.getParent()==ice.getParent() && cool.getParent()==refresh.getParent(),"Three actions share one row");
                    set(a,"modeChanging",true);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!cool.isEnabled() && !ice.isEnabled() && !refresh.isEnabled(),"In-flight mode request disables overlapping actions");
                    set(a,"modeChanging",false);selectedMode.put("age_ms",4000);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!cool.isEnabled() && !ice.isEnabled(),"Stale selection disables commands");
                    selectedMode.put("age_ms",100);sampleStatus.put("busy",true);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!cool.isEnabled() && !ice.isEnabled(),"Busy programmer blocks mode buttons");
                    sampleStatus.put("busy",false);

                    JSONObject oldPause = new JSONObject().put("name","remote_paused").put("value","OFF").put("age_ms",60000);
                    preview.getJSONArray("variables").put(oldPause);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(green.isCounting(),"Old optional pause field after MS51 upgrade cannot block current countdown");
                    oldPause.put("value","ON");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(green.isCounting(),"Stale ON is not a current pause");
                    oldPause.put("age_ms",100);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!green.isCounting(),"Fresh remote pause still stops blink");
                    oldPause.put("value","OFF").put("age_ms",60000);

                    preview.put("last_rx_age_ms",5000);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!green.isCounting() && green.getAlpha()==1f,"Stale telemetry cancels pulse and restores alpha");
                    preview.put("last_rx_age_ms",100);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    activeTimer.put("value","RED");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!green.isCounting() && red.isCounting(),"Timer phase switches pulse with unchanged digits");
                    activeTimer.put("age_ms",4000);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!green.isCounting() && !red.isCounting(),"Stale timer state stops despite fresh serial traffic");
                    activeTimer.put("age_ms",100).put("value","NONE");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!green.isCounting() && !red.isCounting(),"Explicit hold stops both displays");
                    activeTimer.put("value","RED");
                    java.lang.reflect.Field digits = SevenSegmentView.class.getDeclaredField("text"); digits.setAccessible(true);
                    for (String[] pair : new String[][]{{"007","  7"},{"025"," 25"},{"000","  0"},{"105","105"}}) {
                        red.setValue(pair[0]);
                        check(digits.get(red).equals(pair[1]), "No leading zero digits: " + pair[0]);
                    }
                    red.setValue(null); check(digits.get(red).equals("---"), "Unknown display remains distinct from zero");

                    invoke(a,"stopDashboard",new Class[]{});
                    check(!green.isCounting() && !red.isCounting() && red.getAlpha()==1f,"Leaving dashboard cancels animations");
                    sensor.put("temperature_centi",2500);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    // Clearly identify preview data on the saved image.
                    TextView previewLabel=new TextView(a);previewLabel.setText("XEM TRƯỚC · SỐ LIỆU MINH HỌA");previewLabel.setTextColor(Design.TEAL);
                    page.addView(previewLabel,0);
                    page.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    page.layout(0,0,1080,page.getMeasuredHeight());
                    android.graphics.Bitmap shot=android.graphics.Bitmap.createBitmap(1080,page.getMeasuredHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                    page.draw(new android.graphics.Canvas(shot));
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),"overview-preview.png"))) {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                    }
                    shot.recycle();
                } catch(Throwable error) {failure.set(error);}
            });
            if(failure.get()!=null)throw new AssertionError(failure.get());
            result.putString("stream","PASS: dual sensor independence/faults/stale data; immediate countdown state, unchanged digits, phase switch, holds, stale data and animation cleanup; managed settings/updates/history regression.");
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
