package vn.duong.ms51control;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only explicit, fresh controller telemetry may prompt for a PIN. */
final class UnlockState {
    static int read(JSONObject debug) {
        if(debug==null || !debug.optBoolean("enabled") || debug.optLong("last_rx_age_ms",-1)<0 || debug.optLong("last_rx_age_ms",-1)>3000)return -1;
        JSONArray vars=debug.optJSONArray("variables");String version="",state="";
        if(vars!=null)for(int i=0;i<vars.length();i++){
            JSONObject v=vars.optJSONObject(i);
            if(v==null||v.optLong("age_ms",-1)<0||v.optLong("age_ms",-1)>3000)continue;
            if("unlock_v".equals(v.optString("name")))version=v.optString("value");
            if("pin_state".equals(v.optString("name")))state=v.optString("value");
        }
        return "1".equals(version)&&state.matches("[0-3]")?Integer.parseInt(state):-1;
    }
}
