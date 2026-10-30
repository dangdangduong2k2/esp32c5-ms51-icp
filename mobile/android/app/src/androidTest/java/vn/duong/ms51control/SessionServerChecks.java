package vn.duong.ms51control;
import android.app.Instrumentation;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Offline emulator-only regression for sessions across a VPS migration. */
final class SessionServerChecks {
    static void run(Instrumentation test) {
        Bundle result=new Bundle();
        try {
            if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
                throw new IllegalStateException("Run only on an emulator; this fixture replaces its saved session.");
            CloudAccounts accounts=new CloudAccounts(test.getTargetContext());
            accounts.logout();
            put(test,accounts,CloudApi.BASE);
            if (new CloudAccounts(test.getTargetContext()).session()==null)
                throw new AssertionError("Current-server session was not restored");
            put(test,accounts,"https://old-server.invalid/ms51/connect/v1");
            if (new CloudAccounts(test.getTargetContext()).session()!=null)
                throw new AssertionError("Other-server session must be discarded");
            if (test.getTargetContext().getSharedPreferences("cloud_session_v1",0).contains("data"))
                throw new AssertionError("Rejected credentials were not cleared");
            put(test,accounts,null);
            if (new CloudAccounts(test.getTargetContext()).session()!=null)
                throw new AssertionError("Legacy unscoped session must require login");
            result.putString("stream","PASS: current endpoint restores; different endpoint and legacy sessions cleared. No network or hardware commands.");
            test.finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","FAIL: "+e);
            test.finish(Activity.RESULT_CANCELED,result);
        }
    }
    private static void put(Instrumentation test,CloudAccounts accounts,String endpoint) throws Exception {
        Method key=CloudAccounts.class.getDeclaredMethod("key");key.setAccessible(true);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,(SecretKey)key.invoke(accounts));
        JSONObject payload=new JSONObject().put("token","offline-fixture-token")
                .put("user",new JSONObject().put("id","fixture").put("username","fixture"));
        if(endpoint!=null)payload.put("server",endpoint);
        byte[] encrypted=cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        test.getTargetContext().getSharedPreferences("cloud_session_v1",0).edit()
                .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
    }
}
