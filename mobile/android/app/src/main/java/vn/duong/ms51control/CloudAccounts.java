package vn.duong.ms51control;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Server accounts; only an encrypted revocable session is kept on the phone. */
final class CloudAccounts {
    private static final String ALIAS = "ms51_connect_session_v1";
    private final SharedPreferences prefs;
    private final Context context;
    private String token = "";
    CloudAccounts(Context context) {
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("cloud_session_v1",Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS,null);
    }
    synchronized JSONObject session() {
        String saved=prefs.getString("data","");
        if(saved.isEmpty()) return null;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
            JSONObject session=new JSONObject(new String(cipher.doFinal(Base64.decode(saved,Base64.NO_WRAP)),StandardCharsets.UTF_8));
            if (!CloudApi.BASE.equals(session.optString("server")))
                throw new IllegalStateException("Phiên đăng nhập thuộc máy chủ khác.");
            token=session.getString("token"); return session.getJSONObject("user");
        } catch(Exception error) { token=""; prefs.edit().clear().commit(); return null; }
    }
    JSONObject register(String name,char[] password) throws Exception { return authenticate("register",name,password); }
    JSONObject login(String name,char[] password) throws Exception { return authenticate("login",name,password); }
    private JSONObject authenticate(String action,String name,char[] password) throws Exception {
        name=name.trim().toLowerCase(java.util.Locale.ROOT);
        if(!name.matches("[a-z0-9._-]{3,32}")) throw new IllegalArgumentException("Tên đăng nhập cần 3–32 chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới.");
        if(password.length<8 || password.length>128) throw new IllegalArgumentException("Mật khẩu cần từ 8 đến 128 ký tự.");
        JSONObject response=new CloudApi(context,"").post("/auth/"+action,new JSONObject().put("username",name).put("password",new String(password)));
        synchronized(this) {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
            response.put("server", CloudApi.BASE);
            byte[] encrypted=cipher.doFinal(response.toString().getBytes(StandardCharsets.UTF_8));
            if(!prefs.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                    .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
                throw new IllegalStateException("Không lưu được phiên đăng nhập.");
            token=response.getString("token");
        }
        return response.getJSONObject("user");
    }
    synchronized CloudApi api() { return new CloudApi(context,token); }
    synchronized void logout() { token=""; prefs.edit().clear().commit(); }
}
