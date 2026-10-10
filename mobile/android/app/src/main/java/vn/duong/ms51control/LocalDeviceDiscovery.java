package vn.duong.ms51control;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Read-only discovery. Never send pairing credentials until MAC and target Wi-Fi match. */
final class LocalDeviceDiscovery {
    static final class Found {
        final EspApi session;
        final JSONObject status;
        Found(EspApi session,JSONObject status) { this.session=session; this.status=status; }
    }
    static boolean privateIpv4(String value) {
        String[] parts=value.split("\\."); if(parts.length!=4) return false;
        int[] n=new int[4];
        try { for(int i=0;i<4;i++) { n[i]=Integer.parseInt(parts[i]); if(n[i]<0 || n[i]>255)return false; } }
        catch(NumberFormatException e) { return false; }
        return n[0]==10 || (n[0]==172 && n[1]>=16 && n[1]<=31) || (n[0]==192 && n[1]==168);
    }
    static List<String> neighbors(LinkProperties properties) {
        if(properties==null)return Collections.emptyList();
        // Bound the fallback to at most the phone's own /24, even on larger LANs.
        for(LinkAddress link:properties.getLinkAddresses()) {
            if(!(link.getAddress() instanceof Inet4Address))continue;
            String local=link.getAddress().getHostAddress(); if(!privateIpv4(local))continue;
            return neighbors(local,link.getPrefixLength());
        }
        return Collections.emptyList();
    }
    static List<String> neighbors(String local,int prefixLength) {
        if(!privateIpv4(local))return Collections.emptyList();
        long address=0; for(String part:local.split("\\."))address=(address<<8)|Integer.parseInt(part);
        int prefix=Math.max(24,prefixLength); if(prefix>=31)return Collections.emptyList();
        long mask=(0xffffffffL<<(32-prefix))&0xffffffffL, first=address&mask, last=first|(~mask&0xffffffffL);
        ArrayList<String> result=new ArrayList<>();
        for(long ip=first+1;ip<last;ip++) if(ip!=address)
            result.add((ip>>24&255)+"."+(ip>>16&255)+"."+(ip>>8&255)+"."+(ip&255));
        return result;
    }
    static boolean matches(JSONObject status,String device,String ssid) {
        JSONObject wifi=status.optJSONObject("wifi"), uplink=wifi==null?null:wifi.optJSONObject("uplink");
        return device.equals(DeviceStore.identity(status)) && uplink!=null &&
                "connected".equals(uplink.optString("state")) && ssid.equals(uplink.optString("ssid")) &&
                privateIpv4(uplink.optString("ip"));
    }
    static Found find(Context context,Network network,EspApi source,String device,String ssid,
                      String knownIp,List<String> neighbors,BooleanSupplier current,long timeoutMs) throws InterruptedException {
        long deadline=SystemClock.elapsedRealtime()+timeoutMs;
        AtomicBoolean running=new AtomicBoolean(true);
        AtomicReference<Found> found=new AtomicReference<>();
        LinkedBlockingDeque<String> addresses=new LinkedBlockingDeque<>();
        Set<String> attempted=ConcurrentHashMap.newKeySet();
        Set<String> preferred=ConcurrentHashMap.newKeySet();
        if(privateIpv4(knownIp)) { addresses.offer(knownIp); preferred.add(knownIp); }
        ExecutorService workers=Executors.newFixedThreadPool(8);
        NsdManager nsd=context==null?null:(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) { }
            public void onDiscoveryStopped(String type) { }
            public void onStartDiscoveryFailed(String type,int code) { }
            public void onStopDiscoveryFailed(String type,int code) { }
            public void onServiceLost(NsdServiceInfo service) { }
            public void onServiceFound(NsdServiceInfo service) {
                if(!running.get() || !device.equalsIgnoreCase(service.getServiceName()))return;
                try { nsd.resolveService(service,new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo info,int code) { }
                    public void onServiceResolved(NsdServiceInfo info) {
                        if(!running.get() || info.getPort()!=80)return;
                        List<InetAddress> hosts=Build.VERSION.SDK_INT>=34?info.getHostAddresses():
                                info.getHost()==null?Collections.emptyList():Collections.singletonList(info.getHost());
                        for(InetAddress host:hosts) if(host instanceof Inet4Address && privateIpv4(host.getHostAddress())) {
                            preferred.add(host.getHostAddress()); addresses.offerFirst(host.getHostAddress());
                        }
                    }
                }); } catch(RuntimeException ignored) { }
            }
        };
        boolean discovering=false;
        try {
            if(nsd!=null) try {
                if(Build.VERSION.SDK_INT>=33) nsd.discoverServices("_http._tcp.",NsdManager.PROTOCOL_DNS_SD,network,Runnable::run,listener);
                else nsd.discoverServices("_http._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);
                discovering=true;
            } catch(RuntimeException ignored) { }
            for(int worker=0;worker<8;worker++) workers.execute(() -> {
                while(running.get() && found.get()==null && current.getAsBoolean() && SystemClock.elapsedRealtime()<deadline) {
                    try {
                        String host=addresses.poll(100,TimeUnit.MILLISECONDS);
                        if(host==null || !attempted.add(host) || !privateIpv4(host))continue;
                        EspApi probe=source.snapshot(850); probe.setNetwork(network); probe.setExpectedDeviceId(device);
                        probe.setBaseUrl("http://"+host);
                        JSONObject status=probe.get("/api/status");
                        if(matches(status,device,ssid)) {
                            // Restore normal timeouts for subsequent authenticated provisioning writes.
                            found.compareAndSet(null,new Found(probe.snapshot(6000),status));
                        }
                    } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    catch(Exception ignored) { }
                }
            });
            long fallbackAt=SystemClock.elapsedRealtime()+2000, retryAt=fallbackAt+3000;
            boolean fallback=false;
            while(found.get()==null && current.getAsBoolean() && SystemClock.elapsedRealtime()<deadline) {
                long now=SystemClock.elapsedRealtime();
                if(!fallback && now>=fallbackAt) { addresses.addAll(neighbors); fallback=true; }
                if(now>=retryAt) { for(String host:preferred) { attempted.remove(host); addresses.offerFirst(host); } retryAt=now+3000; }
                Thread.sleep(75);
            }
            return current.getAsBoolean()?found.get():null;
        } finally {
            running.set(false); workers.shutdownNow();
            if(discovering)try { nsd.stopServiceDiscovery(listener); } catch(RuntimeException ignored) { }
        }
    }
}
