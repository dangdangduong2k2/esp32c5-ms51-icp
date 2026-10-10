package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in emulator radio switching; only public HTTPS health reads, no accounts or device writes. */
final class NetworkChecks {
    static void run(Instrumentation test,Bundle args) {
        Bundle report=new Bundle(); ArrayList<String> passed=new ArrayList<>(); int result=0;
        ConnectivityManager manager=(ConnectivityManager)test.getTargetContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        MainActivity activity=null; boolean radiosChanged=false;
        String oldWifi="",oldData="";
        try {
            check("true".equals(args.getString("emulator")) &&
                    ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)),"Requires explicit -e emulator true on an emulator");
            oldWifi=shell(test,"settings get global wifi_on").trim();
            oldData=shell(test,"settings get global mobile_data").trim();
            radiosChanged=true;
            shell(test,"svc data enable"); shell(test,"svc wifi enable");
            Network wifi=awaitNetwork(manager,NetworkCapabilities.TRANSPORT_WIFI,null);
            CloudApi cloud=new CloudApi(test.getTargetContext(),"");
            check(cloud.get("/health").optBoolean("mqtt"),"Public HTTPS/MQTT health over Wi-Fi");
            passed.add("Verified HTTPS health on emulator Wi-Fi with Android certificate validation");

            Intent intent=new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(MainActivity)test.startActivitySync(intent); test.waitForIdleSync();
            awaitObserved(test,activity,wifi);

            // Reproduce a stale route left by a local-only provisioning session.
            check(manager.bindProcessToNetwork(wifi),"Bind test process to original Wi-Fi");
            shell(test,"svc wifi disable");
            Network mobile=awaitNetwork(manager,NetworkCapabilities.TRANSPORT_CELLULAR,wifi);
            HttpURLConnection stale=(HttpURLConnection)new URL(CloudApi.BASE+"/health").openConnection();
            stale.setConnectTimeout(1500); stale.setReadTimeout(1500);
            boolean oldRouteFailed=false;
            try { stale.getInputStream().close(); } catch(IOException expected) { oldRouteFailed=true; }
            finally { stale.disconnect(); }
            check(oldRouteFailed,"Old default-bound URL transport must fail after Wi-Fi is lost");
            check(cloud.get("/health").optBoolean("mqtt"),"Same CloudApi instance uses new mobile network despite stale binding");
            awaitObserved(test,activity,mobile);
            passed.add("Stale default-bound transport fails; current-network HTTPS succeeds after Wi-Fi to mobile handoff");
            manager.bindProcessToNetwork(null);

            shell(test,"svc data disable"); awaitNetwork(manager,-1,null);
            long start=SystemClock.elapsedRealtime();
            try { cloud.get("/health"); throw new AssertionError("Offline request accepted"); }
            catch(IOException expected) { check(expected.getMessage().contains("Điện thoại chưa có kết nối Internet"),"Offline message identifies phone connection"); }
            check(SystemClock.elapsedRealtime()-start<2000,"Offline request fails promptly");
            awaitObserved(test,activity,null);
            passed.add("Loss of both networks fails promptly and identifies the phone Internet connection");

            shell(test,"svc wifi enable"); Network nextWifi=awaitNetwork(manager,NetworkCapabilities.TRANSPORT_WIFI,wifi);
            check(cloud.get("/health").optBoolean("mqtt"),"Existing session recovers on newly created Wi-Fi Network");
            awaitObserved(test,activity,nextWifi);
            passed.add("Same cloud session recovers on a new Wi-Fi network handle without restarting the app");
            passed.add("Activity default-network observer follows Wi-Fi, mobile, offline and restored Wi-Fi");

            AtomicInteger writes=new AtomicInteger();
            CloudApi uncertain=new CloudApi("test-session",url -> new HttpURLConnection(url) {
                @Override public void connect() { }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
                @Override public int getResponseCode() throws IOException { writes.incrementAndGet(); throw new SocketTimeoutException("reply lost"); }
            });
            try { uncertain.rpc("ms51-001122334455","POST","/api/reset",new JSONObject()); throw new AssertionError("Timeout accepted"); }
            catch(IOException expected) { check(expected.getMessage().contains("kiểm tra trạng thái"),"Uncertain mutation asks to check state"); }
            check(writes.get()==1,"Mutation must never be automatically resent");
            passed.add("Lost command response is not retried and asks the user to verify state before resending");
            result=-1; report.putString("stream","ALL NETWORK TESTS PASSED\n"+String.join("\n",passed)+"\n");
        } catch(Throwable error) { report.putString("stream","FAIL "+android.util.Log.getStackTraceString(error)); }
        finally {
            manager.bindProcessToNetwork(null);
            if(activity!=null) { final MainActivity finished=activity; test.runOnMainSync(finished::finish); }
            if(radiosChanged) try {
                shell(test,"svc wifi "+("1".equals(oldWifi)?"enable":"disable"));
                shell(test,"svc data "+("1".equals(oldData)?"enable":"disable"));
            } catch(Exception ignored) { }
        }
        test.finish(result,report);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static String shell(Instrumentation test,String command) throws Exception {
        try(ParcelFileDescriptor file=test.getUiAutomation().executeShellCommand(command);
            java.io.InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(file)) {
            java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream(); byte[] b=new byte[1024]; int n;
            while((n=stream.read(b))!=-1)data.write(b,0,n);
            return data.toString("UTF-8");
        }
    }
    private static Network awaitNetwork(ConnectivityManager manager,int transport,Network old) throws Exception {
        long end=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<end) {
            Network network=manager.getActiveNetwork();
            NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
            if(transport<0 && network==null)return null;
            if(caps!=null && caps.hasTransport(transport) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    && !network.equals(old))return network;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Network transition timed out: "+transport);
    }
    private static void awaitObserved(Instrumentation test,MainActivity activity,Network expected) throws Exception {
        Field field=MainActivity.class.getDeclaredField("observedInternet"); field.setAccessible(true);
        long end=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<end) {
            AtomicReference<Object> actual=new AtomicReference<>();
            test.runOnMainSync(() -> { try { actual.set(field.get(activity)); } catch(Exception e) { throw new RuntimeException(e); } });
            if(java.util.Objects.equals(expected,actual.get()))return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Activity did not observe default-network change");
    }
}
