package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.text.method.PasswordTransformationMethod;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against the real Activity and HTTP client; only Android radio callbacks are simulated.
 * Start work/qa/provision_mock.py on the host before running on the emulator.
 */
@android.annotation.TargetApi(35)
public final class ProvisioningTestRunner extends Instrumentation {
    private MainActivity activity;
    private final ArrayList<String> passed = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences(MainActivity.class.getName(), Context.MODE_PRIVATE).edit().clear().commit();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(getTargetContext(), MainActivity.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(intent);
            waitForIdleSync();

            reset();
            check(buttons().size()==1 && buttons().get(0).getText().toString().equals("Tìm Wi-Fi thiết bị"), "Initial single CTA");
            check(inputs().isEmpty(), "No IP/SSID/password form initially");
            main(() -> call("showWifiSetup", new JSONObject()));
            check(screen().equals("CONNECT_DEVICE"), "Cannot skip local verification");
            shot("01-tim-thiet-bi.png");
            pass("Unpaired screen has one action; local verification cannot be skipped");

            control("{\"status_error\":true}");
            verifyLocal(); awaitIdle();
            check(screen().equals("CONNECT_DEVICE") && !(boolean)get("localDeviceReady"), "HTTP error must not advance");
            control("{\"status_error\":false}");
            verifyLocal(); awaitScreen("CONFIGURE_WIFI");
            check(buttons().size()==1 && inputs().isEmpty(), "Scan-only page");
            check(stats().getInt("scans")==0, "No automatic scan");
            shot("02-quet-wifi.png");
            pass("Failed local handshake stays put; successful handshake opens scan-only page");

            control("{\"delay\":3}");
            main(() -> {
                for(int i=0;i<15;i++) ((Button)field("setupAction")).performClick();
                check((boolean)field("setupBusy") && !((Button)field("setupAction")).isEnabled(), "Scan is locked");
                activity.onBackPressed();
                check(field("screen").toString().equals("CONFIGURE_WIFI"), "Back cannot interrupt an in-flight scan");
            });
            awaitCount("scans",1);
            awaitIdle();
            check(stats().getInt("scans")==1, "Repeated scan clicks must coalesce");
            check(((View)get("messageView")).getVisibility()==View.GONE, "Old busy message cleared when scan completes");
            check(hasButton("Home 5G\n5 GHz") && hasButton("Home 2G\n2.4 GHz"), "Both bands shown");
            shot("03-chon-wifi.png");
            pass("Repeated scans issue one request; 2.4 and 5 GHz choices are shown");

            tap("Home 5G\n5 GHz");
            check(screen().equals("WIFI_PASSWORD") && inputs().size()==1 && buttons().size()==1, "Password is a separate page");
            EditText password = (EditText)get("setupPassword");
            main(() -> { password.setText("Matkhau123"); password.setSelection(4); });
            check(password.getTransformationMethod() instanceof PasswordTransformationMethod, "Password hidden initially");
            main(() -> ((ImageButton)field("passwordEye")).performClick());
            check(password.getTransformationMethod()==null && password.getSelectionStart()==4, "Reveal preserves cursor");
            shot("04-nhap-mat-khau.png");
            main(() -> ((ImageButton)field("passwordEye")).performClick());
            check(password.getTransformationMethod() instanceof PasswordTransformationMethod, "Hide again");
            main(() -> { password.setText("123"); ((Button)field("setupAction")).performClick(); });
            check(stats().getInt("writes")==0 && !(boolean)get("setupBusy"), "Invalid password sends no write");
            pass("Password eye toggles without changing content/cursor; invalid password is rejected");

            main(() -> {
                password.setText("Matkhau123");
                for(int i=0;i<15;i++) ((Button)field("setupAction")).performClick();
                activity.onBackPressed();
                check(!password.isEnabled() && !((ImageButton)field("passwordEye")).isEnabled(), "Password and eye locked");
                check(field("screen").toString().equals("WIFI_PASSWORD"), "Back cannot interrupt connect");
            });
            awaitCount("writes",1);
            awaitIdle();
            check(screen().equals("WIFI_PASSWORD") && password.isEnabled(), "Wrong password stays editable");
            check(stats().getInt("writes")==1, "One connect request");
            check(stats().getJSONObject("last_body").getString("ssid").equals("Home 5G"), "Correct selected SSID");
            check(stats().getJSONObject("last_body").getString("password").equals("Matkhau123"), "Frozen password");
            pass("Repeated connect clicks issue one write; failed association unlocks the password step");

            control("{\"delay\":0,\"connect_result\":\"connected\"}");
            tap("Kết nối"); awaitScreen("SWITCH_PHONE");
            check(buttons().size()==1 && inputs().isEmpty(), "One handoff button, no duplicate password");
            check(!getTargetContext().getSharedPreferences(MainActivity.class.getName(),0).contains("paired_ip"), "Not paired before LAN handshake");
            main(() -> { call("beginSetup","Đang kiểm tra…"); call("verifyPhoneOnLan"); });
            awaitScreen("CONTROL");
            check(getTargetContext().getSharedPreferences(MainActivity.class.getName(),0).getString("paired_ssid","").equals("Home 5G"), "Pair saved after LAN verification");
            check(((String)get("pendingPassword")).isEmpty() && ((String)get("uplinkPasswordForPhone")).isEmpty(), "Transient password cleared");
            pass("Successful association still requires LAN verification before remembering the pair");

            // Cold launch must reconnect the saved pair without reopening setup.
            main(() -> activity.finish());
            SystemClock.sleep(250);
            activity = (MainActivity)startActivitySync(intent);
            awaitScreen("CONTROL");
            pass("Previously paired device reconnects on app launch");

            reset(); verifyLocal(); awaitScreen("CONFIGURE_WIFI");
            control("{\"delay\":3}");
            tap("Quét Wi-Fi"); awaitCount("scans",1);
            main(() -> {
                Network lost = ((android.net.ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
                check(lost != null, "Emulator has a network for the loss callback fixture");
                setField("activeNetwork",lost);
                call("onWifiLost",field("requestToken"),lost);
            });
            check(screen().equals("CONNECT_DEVICE"), "Loss returns to first step");
            SystemClock.sleep(3400); waitForIdleSync();
            check(screen().equals("CONNECT_DEVICE") && buttons().size()==1 && inputs().isEmpty(), "Late scan response discarded");
            pass("Lost local network invalidates late results and returns to the first step");

            reset(); verifyLocal(); awaitScreen("CONFIGURE_WIFI");
            control("{\"scan_error\":true}");
            tap("Quét Wi-Fi"); awaitIdle();
            check(screen().equals("CONFIGURE_WIFI") && ((Button)get("setupAction")).isEnabled(), "Failed scan can retry");
            control("{\"scan_error\":false}");
            tap("Quét Wi-Fi"); awaitIdle();
            tap("Home 2G\n2.4 GHz");
            main(() -> activity.onBackPressed());
            check(screen().equals("CONFIGURE_WIFI") && hasButton("Home 5G\n5 GHz"), "Back preserves choices");
            tap("Guest\n5 GHz");
            check(screen().equals("WIFI_PASSWORD") && inputs().isEmpty() && buttons().size()==1, "Open network needs no password");
            pass("Scan failure retries; Back returns to choices; open 5 GHz networks need no password");

            AtomicReference<NetworkRequest> request = new AtomicReference<>();
            main(() -> request.set((NetworkRequest)call("makeWifiRequest","Home 5G","Matkhau123","WPA2")));
            WifiNetworkSpecifier spec = (WifiNetworkSpecifier)request.get().getNetworkSpecifier();
            check(spec.getBand()==-1, "Network request must not force 2.4 GHz");
            check(!request.get().hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), "Specifier must be local-only");
            pass("Android network request allows either band and does not require Internet");

            reset();
            result.putString("stream","\nPASS: "+passed.size()+" provisioning scenarios\n"+String.join("\n",passed)+"\n");
            result.putString("result","PASS");
            finish(-1,result);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            result.putString("stream","\nFAILED after "+passed.size()+" scenarios\n"+trace);
            result.putString("result","FAIL"); finish(0,result);
        }
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
        long end=SystemClock.elapsedRealtime()+12000;
        while (!screen().equals(wanted) && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50);
        check(screen().equals(wanted),"Expected "+wanted+" but got "+screen()); waitForIdleSync();
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
        File dir=new File(getTargetContext().getExternalFilesDir(null),"pairing-preview"); dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
        bitmap.recycle();
    }
}
