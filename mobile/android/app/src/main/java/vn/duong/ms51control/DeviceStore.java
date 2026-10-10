package vn.duong.ms51control;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Account-scoped registry keyed by the ESP factory MAC, not by a DHCP address or SSID. */
final class DeviceStore {
    static final class Device {
        final String id, name, ip, hostname, ssid;
        Device(String id,String name,String ip,String hostname,String ssid) {
            this.id=id;this.name=name;this.ip=ip;this.hostname=hostname;this.ssid=ssid;
        }
        boolean legacy() { return id.startsWith("legacy-"); }
        JSONObject json() throws Exception { return new JSONObject().put("id",id).put("name",name).put("ip",ip).put("hostname",hostname).put("ssid",ssid); }
    }
    private final SharedPreferences prefs;
    DeviceStore(Context context) { prefs=context.getSharedPreferences("paired_devices_v1",Context.MODE_PRIVATE); }
    synchronized List<Device> list(String user) {
        ArrayList<Device> result=new ArrayList<>();
        try {
            JSONArray array=new JSONArray(prefs.getString(user,"[]"));
            for(int i=0;i<array.length();i++) { JSONObject o=array.getJSONObject(i);
                result.add(new Device(o.getString("id"),o.getString("name"),o.optString("ip"),o.optString("hostname"),o.optString("ssid"))); }
            return result;
        } catch(Exception error) { throw new IllegalStateException("Không đọc được danh sách thiết bị.",error); }
    }
    synchronized void save(String user,Device device) {
        List<Device> devices=list(user);int existing=-1;
        for(int i=0;i<devices.size();i++) if(devices.get(i).id.equals(device.id)) existing=i;
        if(existing<0) devices.add(device); else devices.set(existing,device);
        write(user,devices);
    }
    synchronized void remove(String user,String id) {
        List<Device> devices=list(user);devices.removeIf(d->d.id.equals(id));write(user,devices);
    }
    synchronized boolean replace(String user,JSONArray array) throws Exception {
        List<Device> devices=new ArrayList<>();
        JSONArray canonical=new JSONArray();
        for(int i=0;i<array.length();i++) {
            JSONObject o=array.getJSONObject(i);
            Device d=new Device(o.getString("id"),o.getString("name"),o.optString("ip"),o.optString("hostname"),o.optString("ssid"));
            devices.add(d); canonical.put(d.json());
        }
        boolean changed=!canonical.toString().equals(prefs.getString(user,"[]"));
        if(changed) write(user,devices);
        return changed;
    }
    private void write(String user,List<Device> devices) {
        try {
            JSONArray array=new JSONArray();for(Device device:devices)array.put(device.json());
            if(!prefs.edit().putString(user,array.toString()).commit())throw new IllegalStateException("Không lưu được danh sách thiết bị.");
        }catch(Exception e){throw new IllegalStateException("Không lưu được danh sách thiết bị.",e);}
    }
    static String identity(JSONObject status) {
        JSONObject device=status.optJSONObject("device");
        String id=device==null?"":device.optString("id","");
        return id.matches("ms51-[0-9a-f]{12}")?id:"";
    }
    static String hostname(JSONObject status) {
        String id=identity(status);
        return id.isEmpty()?"":id+".local";
    }
    static String internetLabel(JSONObject status) {
        JSONObject info=status.optJSONObject("internet");
        if(info==null)return "Chưa xác nhận Internet";
        String state=info.optString("state");
        long age=info.optLong("checked_age_ms",-1);
        if("online".equals(state) && age>=0 && age<=90000) return "Có Internet";
        if("offline".equals(state) && age>=0 && age<=90000) return "Chưa có Internet";
        if("checking".equals(state))return "Đang kiểm tra Internet";
        return "Chưa xác nhận Internet";
    }
}
