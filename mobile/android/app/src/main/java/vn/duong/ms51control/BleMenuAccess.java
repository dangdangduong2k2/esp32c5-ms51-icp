package vn.duong.ms51control;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Iterator;

/** Intersect device-provided fields with its physical menu flags, including older C5 builds. */
final class BleMenuAccess {
    static JSONObject apply(JSONObject access,RuntimeConfigCodec.Config config) throws JSONException {
        JSONArray offered=access.optJSONArray("visible"),visible=new JSONArray();
        if(offered!=null)for(int i=0;i<offered.length();i++){
            String key=offered.optString(i);
            if(enabled(key,config))visible.put(key);
        }
        access.put("visible",visible);return access;
    }
    static void requireAllowed(JSONObject fresh,JSONObject values) throws JSONException,IOException {
        apply(fresh,RuntimeConfigCodec.decode(fresh.getString("data")));
        if(!fresh.optBoolean("settings_write_supported"))throw new IOException("Mạch chưa hỗ trợ lưu qua Bluetooth.");
        JSONArray visible=fresh.getJSONArray("visible");
        for(Iterator<String> keys=values.keys();keys.hasNext();){
            String key=keys.next();boolean allowed=false;
            for(int i=0;i<visible.length();i++)if(key.equals(visible.optString(i)))allowed=true;
            if(!allowed)throw new IOException("Mục này chưa được bật trên mạch. Hãy đọc lại cài đặt.");
        }
    }
    private static boolean on(RuntimeConfigCodec.Config c,String key){return Integer.valueOf(1).equals(c.values.get(key));}
    private static boolean enabled(String key,RuntimeConfigCodec.Config c){
        switch(key){
            case "df":return on(c,"showDf");
            case "end":return on(c,"showEnd");
            case "hdf":return on(c,"showHdf");
            case "hcfMode":return on(c,"showHcf");
            case "displayMode":return on(c,"showSl");
            case "stLed":return on(c,"showSt") && (on(c,"showSl") || on(c,"displayMode"));
            case "stLcd":return on(c,"showSt") && (on(c,"showSl") || Integer.valueOf(0).equals(c.values.get("displayMode")));
            default:return true;
        }
    }
}
