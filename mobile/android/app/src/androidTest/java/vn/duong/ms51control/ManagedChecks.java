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
                    SettingsLayoutChecks.run(a);
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
                    set(a,"activeDevice",new DeviceStore.Device("preview","MS51 1FE4E0","","",""));
                    a.getSharedPreferences("temperature_display",0).edit().clear().commit();
                    LinearLayout page=new LinearLayout(a);page.setOrientation(LinearLayout.VERTICAL);
                    page.setPadding(32,32,32,32);page.setBackgroundColor(Design.BG);
                    invoke(a,"addNavigation",new Class[]{LinearLayout.class},page);
                    invoke(a,"buildOverview",new Class[]{LinearLayout.class},page);
                    check(texts(page).contains("Bộ điều khiển MS51 1FE4E0"),"Device name belongs in hero heading");
                    check(!texts(page).contains("Chế độ vận hành hiện tại"),"Removed redundant subtitle");
                    JSONObject uplink=sampleStatus.getJSONObject("wifi").getJSONObject("uplink");
                    int[] signals={-40,-55,-56,-67,-68,-75,-76,-100,0,-127};
                    int[] levels={4,4,3,3,2,2,1,1,0,0};
                    TextView connection=(TextView)get(a,"dashboardConnection");
                    for(int i=0;i<signals.length;i++) {
                        uplink.put("rssi",signals[i]);
                        invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                        check(Design.WifiSignal.level(true,signals[i])==levels[i],"RSSI threshold "+signals[i]);
                        check(connection.getCompoundDrawables()[0] instanceof Design.WifiSignal,"Signal icon precedes SSID");
                        String description=connection.getContentDescription().toString();
                        check(levels[i]==0 ? description.contains("chưa có") : description.contains(levels[i]+" trên 4"),"Signal accessibility matches reading");
                    }
                    uplink.remove("rssi");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(connection.getContentDescription().toString().contains("chưa có"),"Missing RSSI cannot show full signal");
                    uplink.put("rssi",-40).put("state","disconnected");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(connection.getContentDescription().toString().contains("chưa có"),"Disconnected ignores cached RSSI");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},null,null);
                    check(connection.getContentDescription().toString().contains("chưa có"),"Lost status clears signal");
                    uplink.put("rssi",-62).put("state","connected");
                    for(int gpio:new int[]{1,5,6}) for(int temperature:new int[]{-1800,0,2500,12500}) {
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
                    sensor.put("gpio",5).put("temperature_centi",2500);
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
                    JSONObject third=new JSONObject(sensor.toString()).put("gpio",6).put("temperature_centi",2875);
                    sampleStatus.put("analog20k",third);
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("25,0 °C"),"Motor initially shows 10k only");
                    View motorSwitch=(View)get(a,"motorSensorSwitch");
                    motorSwitch.performClick();
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("28,8 °C"),"Motor switch shows independent 20k IO6 reading");
                    check(((TextView)get(a,"motorSensorLabel")).getText().toString().equals("20k"),"Selected source clearly labeled");
                    check(!texts(page).contains("25,0 °C"),"Unselected motor sensor not displayed");
                    check(a.getSharedPreferences("temperature_display",0).getAll().containsValue(true),"Motor source choice persisted");
                    third.put("gpio",5);invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("28,8 °C"),"Legacy 20k IO5 still supported");
                    third.put("gpio",6);
                    third.put("sensor","short");invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"20k short does not silently fall back to 10k");
                    check(((TextView)get(a,"temperature50Value")).getText().toString().equals("31,5 °C"),"20k short leaves 50k intact");
                    third.put("sensor","ok");
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    set(a,"temperatureStatusAt",android.os.SystemClock.elapsedRealtime()-3000);
                    motorSwitch.performClick();
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Switch cannot revive old snapshot");
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("25,0 °C"),"Switch back restores 10k after fresh data");
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},(Object)null);
                    motorSwitch.performClick();
                    check(((TextView)get(a,"temperatureValue")).getText().toString().equals("— °C"),"Switch while disconnected stays empty");
                    motorSwitch.performClick();
                    invoke(a,"renderTemperature",new Class[]{JSONObject.class},sampleStatus);
                    check(texts(page).contains("Nhiệt độ động cơ") && texts(page).contains("Nhiệt độ giám sát"),"Both requested temperature titles");
                    sampleStatus.put("trigger",new JSONObject().put("enabled",true).put("source","20K").put("safety_supported",true).put("safety_enabled",true).put("thermal_supported",true).put("thermal_enabled",true).put("defrost_enabled",true).put("defrost_centi",500).put("defrost_valid",true).put("defrost_active",false).put("threshold_centi",3000).put("synced",true).put("sensor_valid",true).put("active",false));
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    android.widget.EditText threshold=(android.widget.EditText)get(a,"triggerThreshold");
                    check(threshold.getText().toString().equals("30"),"Stored threshold loaded");
                    NumberStepper safetyStepper=(NumberStepper)threshold.getParent();
                    safetyStepper.increase.performClick();check(threshold.getText().toString().equals("31"),"Safety threshold arrow +1 degree");
                    safetyStepper.decrease.performClick();check(threshold.getText().toString().equals("30"),"Safety threshold arrow -1 degree");
                    android.widget.Switch safety=(android.widget.Switch)get(a,"safetySwitch");
                    check(safety.isChecked() && safety.isEnabled(),"Safety switch loads saved state");
                    safety.setChecked(false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!safety.isChecked() && !threshold.isEnabled(),"Polling preserves disabled safety draft");
                    check(!safetyStepper.increase.isEnabled() && !safetyStepper.decrease.isEnabled(),"Safety toggle locks arrows");
                    check(((TextView)get(a,"triggerState")).getText().toString().contains("Nhấn Lưu"),"Unsaved toggle is explicit");
                    safety.setChecked(true);
                    set(a,"safetyDraftPending",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    sampleStatus.getJSONObject("trigger").put("safety_supported",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!safety.isEnabled() && !threshold.isEnabled(),"Older controller cannot arm safety");
                    sampleStatus.getJSONObject("trigger").put("safety_supported",true);
                    android.widget.EditText defrostLimit=(android.widget.EditText)get(a,"defrostThreshold");
                    check(defrostLimit.getText().toString().equals("5"),"Stored defrost threshold loaded");
                    NumberStepper defrostStepper=(NumberStepper)defrostLimit.getParent();
                    defrostStepper.increase.performClick();check(defrostLimit.getText().toString().equals("6"),"Defrost threshold arrow +1 degree");
                    defrostStepper.decrease.performClick();check(defrostLimit.getText().toString().equals("5"),"Defrost threshold arrow -1 degree");
                    android.widget.Switch defrostToggle=(android.widget.Switch)get(a,"defrostSwitch");
                    check(defrostToggle.isChecked() && defrostToggle.isEnabled(),"Defrost switch loads saved state");
                    defrostToggle.setChecked(false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!defrostToggle.isChecked() && !defrostLimit.isEnabled() && safety.isChecked(),"Defrost draft independent from safety and survives polling");
                    check(!defrostStepper.increase.isEnabled() && !defrostStepper.decrease.isEnabled(),"Defrost toggle locks arrows");
                    check(((TextView)get(a,"defrostState")).getText().toString().contains("Nhấn Lưu"),"Unsaved defrost switch is explicit");
                    set(a,"triggerLoaded",false);
                    sampleStatus.getJSONObject("trigger").put("defrost_enabled",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!defrostToggle.isChecked() && ((TextView)get(a,"defrostState")).getText().toString().contains("theo mạch"),"Saved OFF restores machine control");
                    sampleStatus.getJSONObject("trigger").remove("defrost_enabled");
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!defrostToggle.isEnabled(),"Old C5 cannot change unsupported switch");
                    sampleStatus.getJSONObject("trigger").put("defrost_enabled",true);
                    set(a,"triggerLoaded",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);

                    check((Integer)get(a,"motorSensorIndex")==1,"Saved motor control source overrides local display choice");
                    check(texts(page).contains("Ngưỡng an toàn") && texts(page).contains("Ngưỡng xả đá")
                            && !texts(page).contains("Nguồn tín hiệu"),"Two thresholds replace source selector");
                    sampleStatus.getJSONObject("trigger").put("defrost_valid",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(((TextView)get(a,"defrostState")).getText().toString().contains("theo mạch"),"Sensor fault shows machine fallback");
                    sampleStatus.getJSONObject("trigger").put("defrost_valid",true).put("defrost_active",true);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(((TextView)get(a,"defrostState")).getText().toString().contains("bật"),"Below threshold shows relay on");
                    defrostLimit.setText("8");
                    threshold.setText("35");invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(threshold.getText().toString().equals("35"),"Polling preserves an edited threshold");
                    check(defrostLimit.getText().toString().equals("8"),"Polling preserves edited defrost limit");
                    set(a,"triggerSaving",true);invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(!((Button)get(a,"triggerSave")).isEnabled() && !threshold.isEnabled(),"Save blocks overlapping edits");
                    set(a,"triggerSaving",false);set(a,"triggerLoaded",false);
                    invoke(a,"renderTrigger",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    CountdownTracker tracker = new CountdownTracker();
                    check(tracker.update("COOL","GREEN","5","8",null)==1,"First sample at five already counts");
                    for (int i=0;i<120;i++) check(tracker.update("COOL","GREEN","0","8",null)==1,"CF OP keeps blinking with fixed zero display");
                    check(tracker.update("COOL","NONE","0","8",null)==0,"CF actual hold stops fixed-zero blink");
                    for (int i=0;i<120;i++) check(tracker.update("COOL","GREEN","5","8",null)==1,"Unchanged minutes keep blinking");
                    check(tracker.update("COOL","RED","5","8",null)==2,"Phase change switches immediately without digit change");
                    check(tracker.update("COOL","NONE","5","8",null)==0,"Paused timer stops with unchanged digits");
                    check(tracker.update("COOL","GREEN","5","8","ON")==0,"Remote pause stops blink");
                    check(tracker.update("SETTING","GREEN","5","8",null)==0,"Settings do not blink");
                    check(tracker.update("COOL",null,"4","8",null)==0,"Old firmware does not guess from digit decrease");
                    check(tracker.update("COOL","GREEN","N/A","8",null)==0,"Non-numeric display does not blink");
                    check(!texts(page).contains("MÀN HÌNH XANH") && !texts(page).contains("MÀN HÌNH ĐỎ"),"Timer labels removed");
                    check(texts(page).contains("20k") && texts(page).contains("50k"),"Both small sensor labels");
                    JSONObject activeTimer = new JSONObject().put("name","countdown_active").put("value","GREEN").put("age_ms",100);
                    preview.getJSONArray("variables").put(activeTimer);
                    for(int k=0;k<preview.getJSONArray("variables").length();k++)preview.getJSONArray("variables").getJSONObject(k).put("age_ms",100);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    SevenSegmentView green=(SevenSegmentView)get(a,"greenDisplay"), red=(SevenSegmentView)get(a,"redDisplay");
                    check(green.isCounting() && !red.isCounting(),"First sample blinks without a digit change");
                    Button cool=(Button)get(a,"coolModeButton"), ice=(Button)get(a,"iceModeButton");
                    check(!cool.isEnabled() && !ice.isEnabled(),"Older MS51 cannot receive soft mode commands");
                    JSONObject modeSource=new JSONObject().put("name","mode_source").put("value","PHYSICAL").put("age_ms",100);
                    JSONObject selectedMode=new JSONObject().put("name","selected_mode").put("value","0").put("age_ms",100);
                    preview.getJSONArray("variables").put(modeSource).put(selectedMode);
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(cool.isEnabled() && ice.isEnabled(),"Fresh capable device enables both mode choices");
                    check(cool.getParent()==ice.getParent() && ((ViewGroup)cool.getParent()).getChildCount()==2,"Only two mode actions in row");
                    check(((LinearLayout.LayoutParams)cool.getLayoutParams()).weight == ((LinearLayout.LayoutParams)ice.getLayoutParams()).weight,"Equal width mode buttons");
                    check(!texts(page).contains("Làm mới"),"Overview has no refresh button");
                    check(cool.getCompoundDrawables()[0]!=null && ice.getCompoundDrawables()[0]!=null,"Both mode buttons have icons");
                    set(a,"lastStatus",sampleStatus);set(a,"lastDebug",preview);
                    invoke(a,"beginModeChange",new Class[]{String.class},"ICE_FLUSH");
                    check(ice.isSelected() && !cool.isSelected() && ice.getText().toString().contains("Đang gửi"),"Tap paints desired mode immediately before network response");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(ice.isSelected() && !cool.isSelected(),"Older COOL telemetry cannot overwrite pending ICE selection");
                    check(!cool.isEnabled() && !ice.isEnabled(),"In-flight command blocks overlapping taps");
                    check(!((Button)get(a,"triggerSave")).isEnabled(),"Pending mode immediately blocks trigger save");
                    invoke(a,"saveTrigger",new Class[]{});
                    check(!(Boolean)get(a,"triggerSaving"),"Trigger save cannot overlap a pending mode command");
                    invoke(a,"finishModeChange",new Class[]{boolean.class,String.class},false,"Test failed command");
                    check(cool.isSelected() && !ice.isSelected() && get(a,"pendingMode")==null,"Failed request restores last confirmed mode");
                    invoke(a,"beginModeChange",new Class[]{String.class},"ICE_FLUSH");
                    invoke(a,"finishModeChange",new Class[]{boolean.class,String.class},true,"Test accepted command");
                    check(ice.isSelected() && ice.getText().toString().contains("Đang xác nhận") && !cool.isEnabled(),"ACK keeps selection until fresh state arrives");
                    selectedMode.put("value","1");
                    invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},sampleStatus,preview);
                    check(ice.isSelected() && ice.isEnabled() && get(a,"pendingMode")==null,"Confirmed target releases pending state");
                    selectedMode.put("value","0");
                    invoke(a,"beginModeChange",new Class[]{String.class},"ICE_FLUSH");
                    invoke(a,"finishModeChange",new Class[]{boolean.class,String.class},true,"Test accepted command");
                    ((Runnable)get(a,"expireModeConfirmation")).run();
                    check(get(a,"pendingMode")==null && !cool.isEnabled() && !ice.isEnabled() && !ice.isSelected(),"Confirmation timeout never leaves a false confirmed selection");
                    selectedMode.put("age_ms",4000);
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
                    sampleStatus.getJSONObject("trigger").put("defrost_active",false);
                    for(int n=0;n<preview.getJSONArray("variables").length();n++) {
                        JSONObject item=preview.getJSONArray("variables").getJSONObject(n);
                        if("relay3".equals(item.optString("name"))) item.put("value","OFF").put("age_ms",100);
                    }
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
                    // Verify the immediate visual state while network work is still pending.
                    invoke(a,"beginModeChange",new Class[]{String.class},"ICE_FLUSH");
                    page.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    page.layout(0,0,1080,page.getMeasuredHeight());
                    check(cool.getTop()==ice.getTop() && cool.getBottom()==ice.getBottom(),"Two-line pending label keeps button edges aligned");
                    check(ice.getBottom()<=((View)ice.getParent()).getHeight(),"Pending button is not clipped by its row");
                    page.draw(new android.graphics.Canvas(shot));
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),"mode-pending-preview.png"))) {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                    }
                    shot.recycle();
                } catch(Throwable error) {failure.set(error);}
            });
            if(failure.get()!=null)throw new AssertionError(failure.get());
            result.putString("stream","PASS: motor 10k/20k and monitoring 50k; dual thresholds, persisted source, edit preservation, sensor fault fallback, save/mode overlap guards; mode confirmation, countdown and managed settings/update/history regression.");
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
