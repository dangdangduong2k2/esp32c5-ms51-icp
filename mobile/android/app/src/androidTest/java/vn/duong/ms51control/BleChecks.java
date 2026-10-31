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
            final MainActivity a=activity;AtomicReference<Throwable> uiFailure=new AtomicReference<>();
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
                    a.onBackPressed();
                    check(!(Boolean)ManagedChecks.get(a,"bleMode") && ManagedChecks.get(a,"screen").toString().equals("AUTH"),"Back exits Bluetooth onboarding without an account");
                }catch(Throwable e){uiFailure.set(e);}
            });
            if(uiFailure.get()!=null)throw new AssertionError(uiFailure.get());
            test.waitForIdleSync();test.runOnMainSync(()->shot(a,"01-bluetooth-khong-tai-khoan.png"));
            report.putString("stream","PASS: BLE framing, UTF-8, size bound, identity, snapshot, offline fields, conditional capabilities, delta-only save, no account/server required.\n");
            test.finish(-1,report);
        }catch(Throwable e){report.putString("stream","FAIL: "+e+"\n");test.finish(0,report);}
        finally{if(activity!=null){MainActivity a=activity;test.runOnMainSync(a::finish);}}
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
        JSONObject config() throws Exception{
            byte[] raw=RuntimeConfigCodec.defaults().raw.clone();raw[0xc3]=raw[0x27]=raw[0x28]=raw[0x2b]=1;
            return new JSONObject().put("ok",true).put("settings_write_supported",true).put("data",RuntimeConfigCodec.toHex(raw)).put("visible",new JSONArray(Arrays.asList(BASIC)));
        }
        public JSONObject request(String method,String path,JSONObject body)throws IOException{
            try{
                if(path.equals("/api/status"))return new JSONObject().put("ok",true).put("device",new JSONObject().put("id",ID));
                if(path.equals("/api/debug"))return new JSONObject().put("ok",true).put("variables",new JSONArray());
                if(path.equals("/api/runtime-config"))return config();
                if(path.equals("/api/ble/settings")){saved=body;return config();}
                throw new IOException("Unexpected route: "+path);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
    }
}
