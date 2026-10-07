package vn.duong.ms51control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local accounts only: salted, slow password hashes; never stores a plaintext password. */
final class LocalAccounts {
    private final SharedPreferences prefs;
    LocalAccounts(Context context) { prefs = context.getSharedPreferences("local_accounts_v1", Context.MODE_PRIVATE); }
    private JSONArray users() throws Exception { return new JSONArray(prefs.getString("users", "[]")); }
    synchronized JSONObject session() {
        try {
            String id = prefs.getString("session", "");
            JSONArray users = users();
            for (int i=0;i<users.length();i++) if(id.equals(users.getJSONObject(i).getString("id"))) return users.getJSONObject(i);
            return null;
        } catch(Exception error) { throw new IllegalStateException("Không đọc được tài khoản trên điện thoại.",error); }
    }
    private static String normalize(String input) { return input.trim().toLowerCase(Locale.ROOT); }
    synchronized JSONObject register(String username, char[] password) throws Exception {
        username = normalize(username);
        if (!username.matches("[a-z0-9._-]{3,32}")) throw new IllegalArgumentException("Tên đăng nhập: 3–32 chữ không dấu, số, dấu chấm, gạch ngang hoặc gạch dưới.");
        if (password.length < 8 || password.length > 128) throw new IllegalArgumentException("Mật khẩu cần từ 8 đến 128 ký tự.");
        JSONArray users=users();
        for(int i=0;i<users.length();i++) if(username.equals(users.getJSONObject(i).getString("username")))
            throw new IllegalArgumentException("Tên đăng nhập này đã tồn tại trên điện thoại.");
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
        String algorithm=Build.VERSION.SDK_INT>=26 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        int rounds=Build.VERSION.SDK_INT>=26 ? 600000 : 1300000;
        byte[] hash=derive(password,salt,algorithm,rounds);
        JSONObject user=new JSONObject().put("id",UUID.randomUUID().toString()).put("username",username)
                .put("salt",Base64.encodeToString(salt,Base64.NO_WRAP)).put("hash",Base64.encodeToString(hash,Base64.NO_WRAP))
                .put("algorithm",algorithm).put("rounds",rounds);
        Arrays.fill(hash,(byte)0);
        users.put(user);
        if(!prefs.edit().putString("users",users.toString()).putString("session",user.getString("id")).commit())
            throw new IllegalStateException("Chưa lưu được tài khoản. Hãy thử lại.");
        return user;
    }
    synchronized JSONObject login(String username,char[] password) throws Exception {
        if(password.length>128) throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng.");
        JSONArray users=users();JSONObject found=null;username=normalize(username);
        for(int i=0;i<users.length();i++) if(username.equals(users.getJSONObject(i).getString("username"))) found=users.getJSONObject(i);
        if(found==null) throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng.");
        byte[] result=derive(password,Base64.decode(found.getString("salt"),Base64.NO_WRAP),
                found.getString("algorithm"),found.getInt("rounds"));
        boolean valid=MessageDigest.isEqual(result,Base64.decode(found.getString("hash"),Base64.NO_WRAP));
        Arrays.fill(result,(byte)0);
        if(!valid) throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng.");
        if(!prefs.edit().putString("session",found.getString("id")).commit()) throw new IllegalStateException("Chưa lưu được phiên đăng nhập.");
        return found;
    }
    synchronized void logout() { prefs.edit().remove("session").commit(); }
    private static byte[] derive(char[] password,byte[] salt,String algorithm,int rounds) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,rounds,256);
        try { return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
