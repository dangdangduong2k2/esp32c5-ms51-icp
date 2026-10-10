package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Read-only real C5 discovery plus a local delayed-response/NSD fixture. No cloud account changes. */
final class DiscoveryChecks {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void run(Instrumentation test,Bundle args) {
        Bundle report=new Bundle(); List<String> passed=new ArrayList<>(); int result=0;
        try {
            String ip=args.getString("device_ip"), device=args.getString("device_id");
            check(LocalDeviceDiscovery.privateIpv4(ip) && device.matches("ms51-[a-f0-9]{12}"),"Explicit physical C5 target required");
            EspApi api=new EspApi();api.setBaseUrl("http://"+ip);api.setExpectedDeviceId(device);
            JSONObject status=api.get("/api/status");
            String ssid=status.getJSONObject("wifi").getJSONObject("uplink").getString("ssid");
            LocalDeviceDiscovery.Found direct=LocalDeviceDiscovery.find(null,null,api,device,ssid,ip,Collections.emptyList(),()->true,4000);
            check(direct!=null,"Known IP real C5"); passed.add("Real C5 reached over LAN from Android; MAC and selected SSID verified");
            List<String> neighbors=LocalDeviceDiscovery.neighbors(ip.substring(0,ip.lastIndexOf('.'))+".254",24);
            check(neighbors.size()==253 && neighbors.contains(ip),"Bounded local /24 candidates");
            check(LocalDeviceDiscovery.find(null,null,api,device,ssid,"",neighbors,()->true,35000)!=null,"Lost station IP fallback");
            check(LocalDeviceDiscovery.find(null,null,api,device,ssid,"192.168.4.250",neighbors,()->true,35000)!=null,"Stale station IP fallback");
            passed.add("Missing/stale IP recovers through bounded LAN discovery without .local DNS or USB");
            check(LocalDeviceDiscovery.find(null,null,api,"ms51-000000000001",ssid,ip,Collections.emptyList(),()->true,1200)==null,"Wrong MAC accepted");
            check(LocalDeviceDiscovery.find(null,null,api,device,ssid+"-wrong",ip,Collections.emptyList(),()->true,1200)==null,"Wrong Wi-Fi accepted");
            passed.add("Wrong MAC and wrong target Wi-Fi are rejected before pairing credentials can be sent");
            long start=SystemClock.elapsedRealtime();
            check(LocalDeviceDiscovery.find(null,null,api,device,ssid,ip,neighbors,()->false,35000)==null,"Cancelled result delivered");
            check(SystemClock.elapsedRealtime()-start<500,"Cancellation exceeded budget");
            check(LocalDeviceDiscovery.neighbors("8.8.8.8",24).isEmpty(),"Public subnet fallback forbidden");
            check(LocalDeviceDiscovery.neighbors("192.168.2.2",30).equals(Collections.singletonList("192.168.2.1")),"Actual subnet mask respected");
            passed.add("Cancelled work stops; public subnets are excluded and smaller subnet masks respected");
            Context context=test.getTargetContext();
            Network network=((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
            String fake="ms51-112233445566";
            try(Fixture fixture=new Fixture(fake)) {
                String local=((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getLinkProperties(network)
                        .getLinkAddresses().stream().filter(a->a.getAddress() instanceof Inet4Address).findFirst().get().getAddress().getHostAddress();
                check(LocalDeviceDiscovery.find(null,network,new EspApi(),fake,"fixture-wifi",local,Collections.emptyList(),()->true,10000)!=null,"Delayed DHCP/server readiness must be retried");
                check(fixture.calls.get()>=3,"Fixture did not exercise retries");
                passed.add("Temporary connection failure retries successfully within a bounded deadline");
                NsdManager nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
                CountDownLatch registered=new CountDownLatch(1); AtomicReference<String> registeredName=new AtomicReference<>();
                NsdManager.RegistrationListener registration=new NsdManager.RegistrationListener() {
                    public void onServiceRegistered(NsdServiceInfo s) { registeredName.set(s.getServiceName());registered.countDown(); }
                    public void onRegistrationFailed(NsdServiceInfo s,int e) { registered.countDown(); }
                    public void onServiceUnregistered(NsdServiceInfo s) { }
                    public void onUnregistrationFailed(NsdServiceInfo s,int e) { }
                };
                NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(fake);info.setServiceType("_http._tcp.");info.setPort(80);
                nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
                try {
                    check(registered.await(8,TimeUnit.SECONDS) && fake.equals(registeredName.get()),"NSD fixture registration");
                    check(LocalDeviceDiscovery.find(context,network,new EspApi(),fake,"fixture-wifi","",Collections.emptyList(),()->true,12000)!=null,"Android NSD resolution without remembered IP");
                    passed.add("Android NSD discovers and resolves the matching MAC service without a saved IP");
                } finally { nsd.unregisterService(registration); }
            }
            report.putString("stream","ALL DISCOVERY TESTS PASSED\n"+String.join("\n",passed)+"\n");result=-1;
        } catch(Throwable error) { report.putString("stream","FAIL "+error+"\nPassed: "+passed+"\n"+android.util.Log.getStackTraceString(error)); }
        test.finish(result,report);
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(80); final AtomicInteger calls=new AtomicInteger();
        Fixture(String id) throws Exception {
            String json=new JSONObject().put("ok",true).put("device",new JSONObject().put("id",id))
                    .put("wifi",new JSONObject().put("uplink",new JSONObject().put("state","connected").put("ssid","fixture-wifi").put("ip","192.168.7.8"))).toString();
            Thread thread=new Thread(()->{
                while(!server.isClosed())try(Socket socket=server.accept()) {
                    socket.setSoTimeout(1500);
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line;while((line=input.readLine())!=null && !line.isEmpty()) { }
                    boolean ready=calls.incrementAndGet()>2;
                    byte[] body=(ready?json:"{\"ok\":false,\"message\":\"Starting\"}").getBytes(StandardCharsets.UTF_8);
                    OutputStream output=socket.getOutputStream();
                    output.write(("HTTP/1.1 "+(ready?"200 OK":"503 Unavailable")+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
                } catch(IOException ignored) { }
            });thread.setDaemon(true);thread.start();
        }
        public void close() throws IOException { server.close(); }
    }
}
