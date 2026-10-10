package vn.duong.ms51control;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.text.method.PasswordTransformationMethod;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit live test: real HTTPS service and real C5 MQTT; USB adapts only local provisioning. */
public final class CloudIntegrationTestRunner extends Instrumentation {
    private MainActivity activity;
    private final ArrayList<String> passed=new ArrayList<>();
    private boolean live;
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; live="true".equals(args.getString("live")); start(); }
    @Override public void onStart() {
        if("discovery".equals(arguments.getString("mode"))) { DiscoveryChecks.run(this,arguments); return; }
        Bundle report=new Bundle(); CloudApi cloud=null; String device=""; int resultCode=0;
        if(!live) { report.putString("stream","Live test requires -e live true and the documented USB adapter."); finish(0,report); return; }
        try {
            getTargetContext().getSharedPreferences("cloud_session_v1",0).edit().clear().commit();
            Intent intent=new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(MainActivity)startActivitySync(intent); waitForIdleSync();
            check(screen().equals("AUTH"),"Cloud login required"); shot("01-dang-nhap.png");
            String name="ui_"+UUID.randomUUID().toString().substring(0,8);
            String password="Test-"+UUID.randomUUID();
            tap("Tạo tài khoản mới");
            main(() -> {
                ((EditText)field("accountName")).setText(name);
                ((EditText)field("accountPassword")).setText(password);
                ((EditText)field("accountConfirm")).setText("Mismatch");
            });
            tap("Tạo tài khoản"); check(!(boolean)get("authBusy"),"Mismatching passwords rejected");
            main(() -> ((EditText)field("accountConfirm")).setText(password));
            tap("Tạo tài khoản"); awaitScreen("HOME");
            String owner=(String)get("accountId"); report.putString("fixture_owner",owner);
            CloudAccounts accounts=(CloudAccounts)get("accounts"); cloud=accounts.api();
            String saved=getTargetContext().getSharedPreferences("cloud_session_v1",0).getString("data","");
            check(!saved.isEmpty() && !saved.contains(password) && !saved.contains(name),"Session encrypted at rest");
            check(cloud.get("/devices").getJSONArray("devices").length()==0,"Server registry empty");
            shot("02-danh-sach-trong.png");
            main(() -> call("logout"));
            main(() -> { ((EditText)field("accountName")).setText(name); ((EditText)field("accountPassword")).setText("WrongPassword"); });
            tap("Đăng nhập"); awaitAuthIdle(); check(screen().equals("AUTH"),"Wrong password rejected by server");
            main(() -> ((EditText)field("accountPassword")).setText(password)); tap("Đăng nhập"); awaitScreen("HOME");
            check(owner.equals(get("accountId")),"Server account restored");
            cloud=((CloudAccounts)get("accounts")).api();
            pass("Real HTTPS account registration/login, wrong-password rejection, encrypted session");

            reset(); check(buttons().size()==1,"One initial pairing action");
            control("{\"protocol\":0}"); verifyLocal(); awaitIdle();
            check(screen().equals("CONNECT_DEVICE"),"Old firmware cannot reach unsupported pairing endpoint");
            control("{\"protocol\":1}"); verifyLocal(); awaitScreen("CONFIGURE_WIFI");
            check(buttons().size()==1,"Only scan before selecting Wi-Fi");
            JSONObject status=((EspApi)get("api")).snapshot(20000).get("/api/status");
            device=DeviceStore.identity(status); String ssid=status.getJSONObject("wifi").getJSONObject("uplink").getString("ssid");
            check(status.getJSONObject("wifi").getJSONObject("uplink").getString("state").equals("connected"),"C5 must already have Wi-Fi for this USB-adapted test");
            main(() -> call("showWifiPassword",ssid,"WPA2"));
            main(() -> ((EditText)field("setupPassword")).setText("TestOnlyPassword"));
            check(((EditText)get("setupPassword")).getTransformationMethod() instanceof PasswordTransformationMethod,"Wi-Fi password hidden");
            main(() -> ((android.widget.ImageButton)field("passwordEye")).performClick());
            check(!(((EditText)get("setupPassword")).getTransformationMethod() instanceof PasswordTransformationMethod),"Eye reveals password");
            pass("Single-action pairing pages, old firmware guard, Wi-Fi password eye");
            main(() -> {
                setField("uplinkSsid",ssid);setField("uplinkIp","10.0.2.2");
                call("showSwitchPhone"); call("beginSetup","Đang kiểm tra…"); call("verifyPhoneOnLan");
            });
            awaitScreen("HOME"); awaitLabel("Đã kết nối Internet"); shot("03-thiet-bi-online.png");
            check(cloud.get("/devices").getJSONArray("devices").length()==1,"C5 belongs to server account");
            pass("Real C5 pairs through its MQTT identity and appears online in Android");
            int before=stats().getInt("requests");
            DeviceStore.Device chosen=((DeviceStore)get("deviceStore")).list(owner).get(0);
            main(() -> call("openDevice",chosen)); awaitScreen("CONTROL");
            main(() -> { call("stopDashboard"); ((EspApi)field("api")).setBaseUrl("http://10.255.255.254"); });
            ((java.util.concurrent.ExecutorService)get("io")).submit(() -> {}).get(90,java.util.concurrent.TimeUnit.SECONDS);
            EspApi remote=((EspApi)get("api")).snapshot(45000);
            JSONObject remoteStatus=remote.get("/api/status");
            check(DeviceStore.identity(remoteStatus).equals(device),"Correct physical C5 through cloud");
            check(remoteStatus.optBoolean("simulation"),"C5 simulates absent MS51");
            JSONObject config=remote.get("/api/runtime-config"); check(config.getString("data").length()==434,"217-byte config through MQTT");
            JSONObject info=remote.post("/api/info",new JSONObject()); check(info.optLong("aprom_size")==32768,"MS51 info through MQTT");
            byte[] hex=":0400000001020304F2\n:00000001FF\n".getBytes(StandardCharsets.US_ASCII);
            JSONObject upload=remote.upload("cloud-test.hex",hex); check(upload.getJSONObject("image").getInt("covered_size")==4,"Remote HEX upload on real C5");
            StringBuilder fullHex=new StringBuilder(); byte[] fullBin=new byte[32768];
            for(int address=0;address<32768;address+=16) {
                fullHex.append(String.format(Locale.ROOT,":10%04X00",address)); int sum=16+(address>>8)+(address&255);
                for(int i=0;i<16;i++) { int value=(address+i)&255; fullBin[address+i]=(byte)value;
                    fullHex.append(String.format(Locale.ROOT,"%02X",value)); sum+=value; }
                fullHex.append(String.format(Locale.ROOT,"%02X\r\n",(-sum)&255));
            }
            fullHex.append(":00000001FF\r\n");
            check(remote.upload("full-test.hex",fullHex.toString().getBytes(StandardCharsets.US_ASCII)).getJSONObject("image").getInt("covered_size")==32768,"Full APROM HEX upload");
            check(remote.upload("full-test.bin",fullBin).getJSONObject("image").getInt("covered_size")==32768,"Full APROM BIN upload");
            try { remote.upload("bad.hex",":0400000001020304F3\n:00000001FF\n".getBytes(StandardCharsets.US_ASCII)); throw new AssertionError("Bad checksum accepted"); }
            catch(IOException expected) { }
            check(remote.get("/api/status").getJSONObject("image").getInt("covered_size")==32768,"Invalid upload preserves previous image");
            upload=remote.upload("cloud-test.hex",hex);
            remote.post("/api/runtime-config",new JSONObject().put("data",config.getString("data")));
            int generation=upload.getJSONObject("image").getInt("generation");
            for(String operation:new String[]{"/api/program","/api/verify"}) {
                remote.post(operation,new JSONObject().put("generation",generation));
                JSONObject done=null;
                for(int poll=0;poll<25;poll++) { SystemClock.sleep(200); done=remote.get("/api/status"); if(!done.optBoolean("busy"))break; }
                check(done!=null && !done.optBoolean("busy") && "ESP_OK".equals(done.optString("last_error")),"Remote program/verify job completes");
            }
            pass("C5 full 32 KiB BIN/HEX transfers, invalid HEX preservation, config write and program/verify jobs");
            check(stats().getInt("requests")==before,"Remote controls did not use local USB/LAN adapter");
            shot("04-dieu-khien-qua-mqtt.png");
            pass("Android -> public HTTPS -> MQTT -> physical C5: status, config, chip info, HEX upload; no local transport");
            cloud.post("/devices/"+device+"/remove",new JSONObject()); device="";
            check(cloud.get("/devices").getJSONArray("devices").length()==0,"Device removed from server");
            pass("Unpair revokes device credentials and updates server registry");
            report.putString("stream","ALL CLOUD TESTS PASSED\n"+String.join("\n",passed)+"\nfixture_owner="+owner+"\n");
            resultCode=-1;
        } catch(Throwable error) {
            report.putString("stream","FAIL "+error+"\n"+android.util.Log.getStackTraceString(error));
        } finally {
            if(cloud!=null) {
                if(activity!=null) main(() -> call("stopDashboard"));
                try { ((java.util.concurrent.ExecutorService)get("io")).submit(() -> {}).get(90,java.util.concurrent.TimeUnit.SECONDS); } catch(Exception ignored) { }
                try { if(!device.isEmpty()) cloud.post("/devices/"+device+"/remove",new JSONObject()); } catch(Exception ignored) { }
                try { cloud.post("/auth/logout",new JSONObject()); } catch(Exception ignored) { }
            }
        }
        finish(resultCode,report);
    }
    private String labels() {
        AtomicReference<String> result=new AtomicReference<>(""); main(() -> {
            StringBuilder text=new StringBuilder(); for(View v:views()) if(v instanceof android.widget.TextView) text.append(((android.widget.TextView)v).getText()).append("\n"); result.set(text.toString());
        }); return result.get();
    }
    private void awaitRequest(String path) throws Exception {
        long end=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<end) {
            org.json.JSONArray requests=stats().getJSONArray("requests");
            for(int i=0;i<requests.length();i++) if(path.equals(requests.getJSONObject(i).optString("path"))) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Missing HTTP request " + path);
    }
    private void awaitLabel(String label) throws Exception {
        long end=SystemClock.elapsedRealtime()+90000;
        while(!labels().contains(label) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        check(labels().contains(label),"Missing label: " + label + " / " + labels());
    }
    private void awaitAuthIdle() throws Exception {
        long end=SystemClock.elapsedRealtime()+90000;
        while((boolean)get("authBusy") && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50);
        check(!(boolean)get("authBusy"),"Auth timed out"); waitForIdleSync();
    }
    private void pass(String name) {
        passed.add(name); Bundle b = new Bundle(); b.putString("stream","PASS "+name+"\n"); sendStatus(0,b);
    }
    private void reset() throws Exception {
        control("{\"reset\":true}");
        getTargetContext().getSharedPreferences(MainActivity.class.getName(),0).edit().clear().commit();
        main(() -> {
            call("releaseRequestedNetwork"); call("showConnectDevice",false);
            EspApi api=(EspApi)field("api"); api.setNetwork(null); api.setBaseUrl("http://10.0.2.2");
        });
    }
    private void verifyLocal() {
        main(() -> { call("beginSetup","Đang kiểm tra…"); call("verifyDeviceConnection"); });
    }
    private void awaitIdle() throws Exception {
        long end=SystemClock.elapsedRealtime()+10000;
        while ((boolean)get("setupBusy") && SystemClock.elapsedRealtime()<end) SystemClock.sleep(40);
        check(!(boolean)get("setupBusy"),"Operation did not finish"); waitForIdleSync();
    }
    private void awaitScreen(String wanted) throws Exception {
        long end=SystemClock.elapsedRealtime()+90000;
        while (!screen().equals(wanted) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50);
        check(screen().equals(wanted),"Expected "+wanted+" but got "+screen()+" busy="+get("setupBusy")+" message="+get("lastUiMessage")+" base="+((EspApi)get("api")).getBaseUrl()); waitForIdleSync();
    }
    private void awaitCount(String key,int count) throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;
        while (stats().getInt(key)<count && SystemClock.elapsedRealtime()<end) SystemClock.sleep(40);
        check(stats().getInt(key)==count,"Wrong count: "+key);
    }
    private void tap(String text) { main(() -> {
        for(Button b:buttonsMain()) if(b.getText().toString().equals(text)) { b.performClick(); return; }
        throw new AssertionError("No button "+text);
    }); }
    private boolean hasButton(String text) {
        for(Button b:buttons()) if(b.getText().toString().equals(text)) return true;
        return false;
    }
    private ArrayList<View> views() {
        ArrayList<View> list=new ArrayList<>(); walk(activity.getWindow().getDecorView(),list); return list;
    }
    private void walk(View view,ArrayList<View> list) {
        list.add(view);
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) walk(((ViewGroup)view).getChildAt(i),list);
    }
    private ArrayList<Button> buttonsMain() {
        ArrayList<Button> result=new ArrayList<>();
        for(View v:views()) if(v instanceof Button && v.getVisibility()==View.VISIBLE) result.add((Button)v);
        return result;
    }
    private ArrayList<Button> buttons() {
        AtomicReference<ArrayList<Button>> r=new AtomicReference<>(); main(() -> r.set(buttonsMain())); return r.get();
    }
    private ArrayList<EditText> inputs() {
        ArrayList<EditText> r=new ArrayList<>(); main(() -> { for(View v:views()) if(v instanceof EditText) r.add((EditText)v); }); return r;
    }
    private String screen() { return get("screen").toString(); }
    private Object get(String name) { AtomicReference<Object> r=new AtomicReference<>(); main(() -> r.set(field(name))); return r.get(); }
    private Object field(String name) {
        try { Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private void setField(String name,Object value) {
        try { Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity,value); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private Object call(String name,Object...args) {
        try {
            for(Method m:MainActivity.class.getDeclaredMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length) {
                m.setAccessible(true); return m.invoke(activity,args);
            }
            throw new NoSuchMethodException(name);
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    private void main(Runnable task) { runOnMainSync(task); }
    private void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private JSONObject control(String body) throws Exception { return http(body); }
    private JSONObject stats() throws Exception { return http(null); }
    private JSONObject http(String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://10.0.2.2/__test").openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(3000);
        if(body!=null) { c.setRequestMethod("POST"); c.setDoOutput(true); c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); }
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return new JSONObject(out.toString("UTF-8"));
        } finally { c.disconnect(); }
    }
    private void shot(String name) throws Exception {
        waitForIdleSync();SystemClock.sleep(180);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        File dir=new File(getTargetContext().getExternalFilesDir(null),"cloud-preview"); dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
        bitmap.recycle();
    }
}
