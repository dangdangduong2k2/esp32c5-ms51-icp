package vn.duong.ms51control;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Explicit LAN build only. Credentials are staged privately with adb run-as, never command-line arguments. */
final class LocalServerChecks {
    static void run(Instrumentation test) {
        Bundle report=new Bundle(); int result=0;
        File credentials=new File(test.getTargetContext().getFilesDir(),"lan-smoke.json");
        try {
            if(!test.getTargetContext().getPackageName().endsWith(".localtest"))
                throw new AssertionError("LAN smoke test requires the separate test application");
            JSONObject input=new JSONObject(new String(Files.readAllBytes(credentials.toPath()),StandardCharsets.UTF_8));
            credentials.delete();
            CloudAccounts accounts=new CloudAccounts(test.getTargetContext());
            accounts.login(input.getString("username"),input.getString("password").toCharArray());
            CloudApi api=accounts.api();
            if(!api.get("/health").optBoolean("mqtt"))throw new AssertionError("Broker offline");
            JSONArray devices=api.get("/devices").getJSONArray("devices");
            if(devices.length()==0)throw new AssertionError("No paired device");
            String id=devices.getJSONObject(0).getString("id");
            if(!devices.getJSONObject(0).optBoolean("online"))throw new AssertionError("C5 offline");
            JSONObject status=api.rpc(id,"GET","/api/status",null);
            if(!status.optBoolean("ok"))throw new AssertionError("Status RPC failed");
            JSONObject config=api.rpc(id,"GET","/api/runtime-config",null);
            if(!config.optBoolean("ok"))throw new AssertionError("MS51 configuration read failed");
            if(!api.get("/devices/"+id+"/access").optBoolean("ok"))throw new AssertionError("Permissions unavailable");
            report.putString("stream","PASS: Android verified LAN HTTPS certificate, logged in, listed paired online C5, read status and MS51 settings over MQTT, and loaded permissions. No relay/configuration writes.\n");
            result=-1;
        } catch(Throwable error) { report.putString("stream","FAIL "+android.util.Log.getStackTraceString(error)); }
        finally { credentials.delete(); }
        test.finish(result,report);
    }
}
