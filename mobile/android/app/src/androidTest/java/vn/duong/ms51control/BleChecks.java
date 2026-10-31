package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Transport/policy UI tests run with no account or network and never touch a controller. */
final class BleChecks {
    private static final String[] BASIC={"coolCl","coolOp","defrostCl","defrostOp","stLed","stLcd","df","end","coolStart","defrostStart","hdf"};
    static void run(Instrumentation test){
        Bundle report=new Bundle();MainActivity activity=null;
        try{
            checkSetupState();
            for(int mask=0;mask<64;mask++)for(int mode=0;mode<2;mode++){
                byte[] raw=RuntimeConfigCodec.defaults().raw.clone();
                for(int i=0;i<5;i++)raw[0x27+i]=(byte)((mask>>i)&1);
                raw[0xc3]=(byte)((mask>>5)&1);raw[0x17]=(byte)mode;
                RuntimeConfigCodec.Config config=RuntimeConfigCodec.decode(RuntimeConfigCodec.toHex(raw));
                JSONArray fields=new JSONArray(Arrays.asList(BASIC));fields.put("hcfMode").put("displayMode");
                JSONObject access=BleMenuAccess.apply(new JSONObject().put("visible",fields),config);
                String visible=access.getJSONArray("visible").toString();
                String[] modes={"df","end","displayMode","hcfMode","hdf"};
                for(int i=0;i<5;i++)check(visible.contains("\""+modes[i]+"\"")==((mask&(1<<i))!=0),"Menu flag "+modes[i]);
                check(visible.contains("\"stLed\"")==((mask&32)!=0 && ((mask&4)!=0 || mode==1)),"ST LED follows available display");
                check(visible.contains("\"stLcd\"")==((mask&32)!=0 && ((mask&4)!=0 || mode==0)),"ST LCD follows available display");
                check(!visible.contains("pinPassword") && !visible.contains("showSt"),"Do not grant factory settings");
            }
            check(BleMenuAccess.apply(new JSONObject(),RuntimeConfigCodec.defaults()).getJSONArray("visible").length()==0,"Missing capabilities never unlock all fields");
            JSONObject staleAccess=new JSONObject().put("visible",new JSONArray(Arrays.asList(BASIC)))
                    .put("settings_write_supported",true).put("data",RuntimeConfigCodec.toHex(RuntimeConfigCodec.defaults().raw));
            boolean hiddenRejected=false;
            try{BleMenuAccess.requireAllowed(staleAccess,new JSONObject().put("coolCl",7).put("df",1));}catch(IOException expected){hiddenRejected=true;}
            check(hiddenRejected,"Preflight rejects whole save when the board has hidden a formerly editable menu");
            String sample=new JSONObject().put("id",9).put("result",new JSONObject().put("ok",true).put("message","Nhiệt độ động cơ · Đã lưu\nXả đá")).toString()+"\n";
            byte[] bytes=sample.getBytes(StandardCharsets.UTF_8);
            for(int size:new int[]{1,20,244,500}){
                BleFrames frames=new BleFrames();ArrayList<JSONObject> messages=new ArrayList<>();
                for(int i=0;i<bytes.length;i+=size)frames.accept(Arrays.copyOfRange(bytes,i,Math.min(bytes.length,i+size)),messages::add);
                check(messages.size()==1 && messages.get(0).getJSONObject("result").getString("message").contains("Đã lưu\nXả đá"),"Fragmented UTF-8 at "+size);
            }
            BleFrames bounded=new BleFrames();boolean rejected=false;
            try{byte[] huge=new byte[32769];Arrays.fill(huge,(byte)'x');bounded.accept(huge,e->{});}catch(IOException expected){rejected=true;}
            check(rejected,"Oversized frame rejected");
            ArrayList<JSONObject> recovery=new ArrayList<>();bounded.accept(bytes,recovery::add);check(recovery.size()==1,"Parser recovers after size failure");
            BleFrames multiple=new BleFrames();ArrayList<JSONObject> replies=new ArrayList<>();multiple.accept((sample+sample).getBytes(StandardCharsets.UTF_8),replies::add);check(replies.size()==2,"Multiple complete replies retained");

            Fake device=new Fake();EspApi transport=new EspApi();transport.setBaseUrl("http://127.0.0.1:1");transport.setBle(device);transport.setExpectedDeviceId(Fake.ID);
            EspApi frozen=transport.snapshot(100);transport.setBle(null);
            check(DeviceStore.identity(frozen.get("/api/status")).equals(Fake.ID),"Snapshot retains Bluetooth without HTTP");
            frozen.setExpectedDeviceId("ms51-000000000000");rejected=false;try{frozen.get("/api/status");}catch(IOException expected){rejected=true;}check(rejected,"Wrong device rejected");
            rejected=false;try{frozen.upload("x.hex",new byte[0]);}catch(IOException expected){rejected=true;}check(rejected,"No firmware upload through offline transport");

            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity;
            checkConnectionIsolation(test,a);AtomicReference<Throwable> uiFailure=new AtomicReference<>();
            test.runOnMainSync(()->{
                try{
                    ManagedChecks.set(a,"demoMode",false);ManagedChecks.set(a,"bleMode",true);
                    ManagedChecks.set(a,"accountId","");ManagedChecks.set(a,"activeDevice",new DeviceStore.Device(Fake.ID,"Hộp hẹn giờ thử","","",""));
                    EspApi api=(EspApi)ManagedChecks.get(a,"api");api.setDemo(false);api.setBle(device);api.setExpectedDeviceId(Fake.ID);
                    Class<?> tab=Class.forName("vn.duong.ms51control.MainActivity$Tab");
                    ManagedChecks.invoke(a,"showControl",new Class[]{tab},Enum.valueOf((Class)tab,"SETTINGS"));
                }catch(Throwable e){uiFailure.set(e);}
            });
            if(uiFailure.get()!=null)throw new AssertionError(uiFailure.get());
            long deadline=SystemClock.elapsedRealtime()+7000;
            while(SystemClock.elapsedRealtime()<deadline && !(Boolean)ManagedChecks.get(a,"configLoaded"))SystemClock.sleep(50);
            check((Boolean)ManagedChecks.get(a,"configLoaded"),"Offline settings load with no account");
            test.waitForIdleSync();
            test.runOnMainSync(()->shot(a,"02-cai-dat-bluetooth.png"));
            test.runOnMainSync(()->{
                try{
                    Map<String,EditText> inputs=(Map<String,EditText>)ManagedChecks.get(a,"configInputs");
                    Map<?,?> choices=(Map<?,?>)ManagedChecks.get(a,"configChoices");
                    check(inputs.size()==5 && inputs.containsKey("stLcd") && !inputs.containsKey("stLed") && !inputs.containsKey("pinPassword"),"Fixed LCD board offers its enabled ST only");
                    check(choices.containsKey("hdf") && !choices.containsKey("hcfMode") && !choices.containsKey("displayMode"),"Physical menu flags control conditional fields");
                    check(!ManagedChecks.texts(a.getWindow().getDecorView()).contains("Cập nhật"),"Offline navigation hides managed updates");
                    inputs.get("coolCl").setText("7");
                    ManagedChecks.invoke(a,"saveRuntimeConfig",new Class[]{});
                }catch(Throwable e){uiFailure.set(e);}
            });
            if(uiFailure.get()!=null)throw new AssertionError(uiFailure.get());
            deadline=SystemClock.elapsedRealtime()+5000;while(device.saved==null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
            check(device.saved!=null && device.saved.getJSONObject("values").length()==1 && device.saved.getJSONObject("values").getInt("coolCl")==7,"Save sends only changed values, not raw counters or hidden configuration");
            test.runOnMainSync(()->{
                try{
                    JSONObject access=device.config();access.getJSONArray("visible").put("hcfMode").put("displayMode");
                    ManagedChecks.set(a,"deviceAccess",access);LinearLayout content=new LinearLayout(a);
                    ManagedChecks.invoke(a,"buildSettings",new Class[]{LinearLayout.class},content);
                    Map<?,?> choices=(Map<?,?>)ManagedChecks.get(a,"configChoices");
                    check(choices.containsKey("hcfMode") && choices.containsKey("displayMode"),"Conditional fields appear when physical menu exposes them");
                    NearbyDeviceSheet connecting=new NearbyDeviceSheet(a,()->{throw new AssertionError("Success must not run cancellation");});
                    connecting.showConnecting("Hộp đã ghép");ManagedChecks.set(a,"bleConnectionSheet",connecting);
                    ManagedChecks.invoke(a,"showBleConnected",new Class[]{});
                    check(ManagedChecks.get(a,"bleConnectionSheet")==null,"Success removes reconnect popup automatically");
                    check(ManagedChecks.get(a,"screen").toString().equals("CONTROL"),"BLE connection opens control directly without onboarding buttons");
                    android.content.SharedPreferences paired=a.getSharedPreferences("ble_devices_v1",0);
                    String testAddress="AA:00:00:00:00:09",oldIdentity=paired.getString(testAddress,null);
                    try {
                        paired.edit().putString(testAddress,Fake.ID).commit();
                        LinearLayout devicePage=new LinearLayout(a);
                        ManagedChecks.invoke(a,"buildBleDevice",new Class[]{LinearLayout.class},devicePage);
                        check(ManagedChecks.texts(devicePage).contains("Bỏ ghép nối") && ManagedChecks.texts(devicePage).contains("Ngắt kết nối"),"Device tab offers both forget and temporary disconnect");
                    } finally {
                        if(oldIdentity==null)paired.edit().remove(testAddress).commit();else paired.edit().putString(testAddress,oldIdentity).commit();
                    }
                }catch(Throwable e){uiFailure.set(e);}
            });
            if(uiFailure.get()!=null)throw new AssertionError(uiFailure.get());
            checkThermal(test,a,device);
            test.runOnMainSync(()->a.onBackPressed());
            check(!(Boolean)ManagedChecks.get(a,"bleMode") && ManagedChecks.get(a,"screen").toString().equals("AUTH"),"Back exits Bluetooth without an account");
            test.waitForIdleSync();test.runOnMainSync(()->shot(a,"01-bluetooth-khong-tai-khoan.png"));
            report.putString("stream","PASS: BLE independent of blocked network workers, cancellable pending connection, ordered bond/service/MTU subscription and fallback; BLE framing, identity, offline menu permissions, delta-only save; three temperatures, source selection, both threshold toggles, offline thermal save/readback, old C5 read-only guard, invalid/stale sensors and rejected saves; no account/server required.\n");
            test.finish(-1,report);
        }catch(Throwable e){report.putString("stream","FAIL: "+e+"\n");test.finish(0,report);}
        finally{if(activity!=null){MainActivity a=activity;test.runOnMainSync(a::finish);}}
    }
    private static void ui(Instrumentation test,Runnable task){
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{task.run();}catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void checkSetupState(){
        BleSetupState state=new BleSetupState();
        check(!state.bonded(),"Early bond event cannot discover before connection");
        check(!state.connected(false),"Unbonded connection waits for bonding");
        check(state.bonded() && !state.bonded(),"Bond completion discovers once");
        check(!state.connected(true),"Repeated connected callback cannot rediscover");
        check(!state.subscribe(),"Early MTU callback cannot subscribe before services");
        check(state.services() && !state.services(),"Service callback starts MTU once");
        check(state.subscribe(),"Missing MTU callback can proceed through fallback");
        check(!state.subscribe(),"Late MTU callback cannot duplicate CCCD write");
        check(!state.isReady(),"Connection is not usable before CCCD acknowledgment");
        check(state.ready() && state.isReady(),"CCCD acknowledgment marks connection usable");
        state.close();check(!state.ready() && !state.services() && !state.subscribe(),"Cancelled setup ignores late callbacks");
        BleSetupState known=new BleSetupState();check(known.connected(true),"Existing bond discovers immediately");
    }
    private static void checkConnectionIsolation(Instrumentation test,MainActivity a)throws Exception{
        java.util.concurrent.ExecutorService cloud=(java.util.concurrent.ExecutorService)ManagedChecks.get(a,"presenceIo");
        java.util.concurrent.ExecutorService ble=(java.util.concurrent.ExecutorService)ManagedChecks.get(a,"bleConnectIo");
        java.util.concurrent.CountDownLatch busy=new java.util.concurrent.CountDownLatch(3),release=new java.util.concurrent.CountDownLatch(1);
        try{
            for(int i=0;i<3;i++)cloud.submit(()->{busy.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(busy.await(2,java.util.concurrent.TimeUnit.SECONDS),"Simulated network requests occupy all cloud workers");
            check(ble.submit(()->true).get(2,java.util.concurrent.TimeUnit.SECONDS),"BLE starts even while cloud requests are blocked");
            java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),cancelled=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> pending=ble.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){cancelled.countDown();Thread.currentThread().interrupt();}});
            check(started.await(2,java.util.concurrent.TimeUnit.SECONDS),"Pending BLE attempt started");
            ui(test,()->{try{ManagedChecks.set(a,"bleConnectTask",pending);ManagedChecks.invoke(a,"disconnectBle",new Class[]{});}catch(Exception e){throw new RuntimeException(e);}});
            check(cancelled.await(2,java.util.concurrent.TimeUnit.SECONDS),"Cancel interrupts pending BLE attempt without waiting for timeout");
            check(ble.submit(()->true).get(2,java.util.concurrent.TimeUnit.SECONDS),"Retry is not queued behind cancelled connection");
        }finally{release.countDown();}
    }
    private static void checkThermal(Instrumentation test,MainActivity a,Fake device)throws Exception{
        ui(test,()->{try{
            ManagedChecks.invoke(a,"stopDashboard",new Class[]{});
            JSONObject status=device.status();ManagedChecks.invoke(a,"rememberStatus",new Class[]{JSONObject.class},status);
            ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,null);
            check(((android.widget.TextView)ManagedChecks.get(a,"temperatureValue")).getText().toString().equals("25,0 °C"),"10k temperature over offline status");
            check(((android.widget.TextView)ManagedChecks.get(a,"temperature50Value")).getText().toString().equals("30,2 °C"),"50k independent temperature over BLE");
            String text=ManagedChecks.texts(a.getWindow().getDecorView());
            check(text.contains("Ngưỡng an toàn") && text.contains("Ngưỡng xả đá"),"Both threshold cards exist over BLE");
            ((android.widget.ImageButton)ManagedChecks.get(a,"motorSensorSwitch")).performClick();
            check(((android.widget.TextView)ManagedChecks.get(a,"temperatureValue")).getText().toString().equals("28,8 °C"),"Switch displays 20k without changing 50k");
            ((EditText)ManagedChecks.get(a,"triggerThreshold")).setText("31,5");
            ((EditText)ManagedChecks.get(a,"defrostThreshold")).setText("-5");
            ((android.widget.Switch)ManagedChecks.get(a,"safetySwitch")).setChecked(true);
            ((android.widget.Switch)ManagedChecks.get(a,"defrostSwitch")).setChecked(false);
            check(((android.widget.Button)ManagedChecks.get(a,"triggerSave")).isEnabled(),"Supported BLE controller allows thermal save");
            ManagedChecks.invoke(a,"saveTrigger",new Class[]{});
        }catch(Exception e){throw new RuntimeException(e);}});
        long deadline=SystemClock.elapsedRealtime()+5000;
        while((device.thermalSaved==null || (Boolean)ManagedChecks.get(a,"triggerSaving")) && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
        check(device.thermalSaved!=null && !(Boolean)ManagedChecks.get(a,"triggerSaving"),"Offline save completes");
        check(device.thermalSaved.getString("source").equals("20K") && device.thermalSaved.getInt("threshold_centi")==3150 && device.thermalSaved.getInt("defrost_centi")==-500,"Source and decimal/negative thresholds delivered unchanged");
        check(device.thermalSaved.getBoolean("safety_enabled") && !device.thermalSaved.getBoolean("defrost_enabled"),"Independent toggle values delivered");
        ui(test,()->{try{
            ManagedChecks.invoke(a,"stopDashboard",new Class[]{});
            JSONObject status=device.status();ManagedChecks.set(a,"triggerLoaded",false);
            ManagedChecks.invoke(a,"rememberStatus",new Class[]{JSONObject.class},status);
            ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,null);
            check(((EditText)ManagedChecks.get(a,"triggerThreshold")).getText().toString().equals("31.5"),"Reread restores saved thermal threshold");
            ((EditText)ManagedChecks.get(a,"triggerThreshold")).setText("126");
            ManagedChecks.invoke(a,"saveTrigger",new Class[]{});check(device.thermalWrites==1,"Out-of-range value never sends BLE request");
            status.remove("ble_thermal_write_supported");
            ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,null);
            check(!((android.widget.Button)ManagedChecks.get(a,"triggerSave")).isEnabled(),"Old C5 cannot attempt unsupported thermal write");
            check(((android.widget.TextView)ManagedChecks.get(a,"temperatureValue")).getText().toString().equals("28,8 °C"),"Old C5 still displays temperatures");
            check(ManagedChecks.texts(a.getWindow().getDecorView()).contains("Cập nhật hộp"),"Explain update requirement");
            status.getJSONObject("analog50k").put("sensor","open");
            ManagedChecks.invoke(a,"renderTemperature",new Class[]{JSONObject.class},status);
            check(((android.widget.TextView)ManagedChecks.get(a,"temperature50Value")).getText().toString().equals("— °C"),"Open 50k never shows fabricated temperature");
            status.getJSONObject("analog20k").put("age_ms",3000);
            ManagedChecks.invoke(a,"renderTemperature",new Class[]{JSONObject.class},status);
            check(((android.widget.TextView)ManagedChecks.get(a,"temperatureValue")).getText().toString().equals("— °C"),"Stale sample remains unavailable");
            status=device.status();ManagedChecks.set(a,"triggerLoaded",false);
            ManagedChecks.invoke(a,"rememberStatus",new Class[]{JSONObject.class},status);
            ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,null);
            device.rejectThermal=true;
            ((EditText)ManagedChecks.get(a,"triggerThreshold")).setText("32");
            ManagedChecks.invoke(a,"saveTrigger",new Class[]{});
        }catch(Exception e){throw new RuntimeException(e);}});
        deadline=SystemClock.elapsedRealtime()+5000;
        while((Boolean)ManagedChecks.get(a,"triggerSaving") && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
        check(device.thermalWrites==1 && device.thermalSaved.getInt("threshold_centi")==3150,"Failed BLE save cannot report a changed threshold");
        check(((String)ManagedChecks.get(a,"lastUiMessage")).contains("Chưa xác nhận lưu"),"Rejected write shows failure, not success");
        ui(test,()->{try{
            ManagedChecks.invoke(a,"stopDashboard",new Class[]{});
            ManagedChecks.invoke(a,"showMessage",new Class[]{String.class},"");
            ManagedChecks.set(a,"triggerLoaded",false);
            ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},device.status(),null);
        }catch(Exception e){throw new RuntimeException(e);}});
        test.waitForIdleSync();
        ui(test,()->{try{
            android.view.View card=(android.view.View)((android.view.View)ManagedChecks.get(a,"temperatureValue")).getParent();
            android.view.ViewParent parent=card.getParent();
            while(parent!=null && !(parent instanceof android.widget.ScrollView))parent=parent.getParent();
            android.widget.ScrollView scroll=(android.widget.ScrollView)parent;
            android.graphics.Rect rect=new android.graphics.Rect();card.getDrawingRect(rect);
            scroll.offsetDescendantRectToMyCoords(card,rect);scroll.scrollTo(0,scroll.getScrollY()+rect.top);
            shot(a,"03-nguong-nhiet-bluetooth.png");
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void shot(MainActivity a,String name){
        try{
            android.view.View view=a.getWindow().getDecorView();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));java.io.File folder=new java.io.File(a.getExternalFilesDir(null),"ble-preview");folder.mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private static final class Fake implements EspApi.LocalTransport{
        static final String ID="ms51-3844be1fe4e0";
        volatile JSONObject saved;
        volatile JSONObject thermalSaved;
        volatile int thermalWrites;
        volatile boolean rejectThermal;
        JSONObject status()throws Exception{
            JSONObject state=new JSONObject().put("ok",true).put("busy",false).put("ble_thermal_write_supported",true).put("device",new JSONObject().put("id",ID));
            state.put("analog",sensor(5,2500)).put("analog20k",sensor(6,2880)).put("analog50k",sensor(4,3020));
            JSONObject thermal=thermalSaved==null ? new JSONObject().put("source","10K").put("threshold_centi",3100).put("defrost_centi",2500).put("safety_enabled",true).put("defrost_enabled",true) : new JSONObject(thermalSaved.toString());
            thermal.put("enabled",true).put("thermal_supported",true).put("thermal_enabled",true).put("safety_supported",true).put("synced",true).put("sensor_valid",true).put("defrost_valid",true);
            return state.put("trigger",thermal);
        }
        JSONObject sensor(int gpio,int centi)throws Exception{return new JSONObject().put("gpio",gpio).put("enabled",true).put("valid",true).put("age_ms",0).put("sensor","ok").put("temperature_centi",centi);}
        JSONObject config() throws Exception{
            byte[] raw=RuntimeConfigCodec.defaults().raw.clone();raw[0xc3]=raw[0x27]=raw[0x28]=raw[0x2b]=1;
            return new JSONObject().put("ok",true).put("settings_write_supported",true).put("data",RuntimeConfigCodec.toHex(raw)).put("visible",new JSONArray(Arrays.asList(BASIC)));
        }
        public JSONObject request(String method,String path,JSONObject body)throws IOException{
            try{
                if(path.equals("/api/status"))return status();
                if(path.equals("/api/thermal")){
                    if(rejectThermal)throw new IOException("Test: device rejected thermal save");
                    thermalSaved=new JSONObject(body.toString());thermalWrites++;return new JSONObject().put("ok",true);
                }
                if(path.equals("/api/debug"))return new JSONObject().put("ok",true).put("variables",new JSONArray());
                if(path.equals("/api/runtime-config"))return config();
                if(path.equals("/api/ble/settings")){saved=body;return config();}
                throw new IOException("Unexpected route: "+path);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
    }
}
