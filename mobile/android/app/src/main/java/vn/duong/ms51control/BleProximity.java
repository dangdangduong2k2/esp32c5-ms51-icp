package vn.duong.ms51control;

import java.util.LinkedHashMap;

/** RSSI is a proximity hint, not a distance or authorization measurement. */
final class BleProximity {
    static final int MIN_RSSI = -50;
    private static final long WINDOW_MS = 2000, MIN_SPAN_MS = 600;
    private static final class Sample { long first,last; int count; }
    private final LinkedHashMap<String,Sample> samples=new LinkedHashMap<>();
    boolean accept(String address,int rssi,long timestamp,long now) {
        if(timestamp<=0 || timestamp>now || now-timestamp>WINDOW_MS)return false;
        Sample sample=samples.get(address);
        if(sample!=null && timestamp<=sample.last)return false;
        if(rssi<MIN_RSSI || rssi>=0){samples.remove(address);return false;}
        if(sample==null || timestamp-sample.first>WINDOW_MS){
            if(samples.size()>=64)samples.remove(samples.keySet().iterator().next());
            sample=new Sample();sample.first=timestamp;samples.put(address,sample);
        }
        sample.last=timestamp;sample.count++;
        return sample.count>=3 && timestamp-sample.first>=MIN_SPAN_MS;
    }
}
