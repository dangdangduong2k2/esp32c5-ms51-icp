package vn.duong.ms51control;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Immutable authenticated session. Android verifies the public TLS certificate. */
final class CloudApi {
    static final String BASE = BuildConfig.CLOUD_BASE_URL;
    private final String token;
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    CloudApi(Context context, String token) {
        this(token, currentNetwork((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)));
    }
    CloudApi(String token, Connections connections) { this.token = token; this.connections = connections; }

    private static Connections currentNetwork(ConnectivityManager manager) {
        return url -> {
            // Resolve DNS and open sockets on the current Internet network for EACH request.
            // Never inherit a provisioning network or a connection pooled on the old default.
            Network network=manager.getActiveNetwork();
            NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
            if(caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                throw new IOException("Điện thoại chưa có kết nối Internet. Chờ Wi-Fi hoặc 4G/5G kết nối rồi thử lại.");
            return (HttpURLConnection)network.openConnection(url);
        };
    }

    JSONObject get(String path) throws Exception { return request(path, "GET", null, null, null); }
    JSONObject post(String path, JSONObject body) throws Exception {
        return request(path,"POST",body.toString().getBytes(StandardCharsets.UTF_8),null,null);
    }
    JSONObject rpc(String device, String method, String path, JSONObject body) throws Exception {
        return post("/devices/" + device + "/request", new JSONObject().put("id",requestId())
                .put("method",method).put("path",path).put("body",body == null ? new JSONObject() : body));
    }
    JSONObject upload(String device, String name, byte[] bytes) throws Exception {
        String filename = (name == null ? "firmware.hex" : name).replaceAll("[^A-Za-z0-9._ -]","_");
        return request("/devices/" + device + "/upload","POST",bytes,filename,requestId());
    }
    private static String requestId() { return UUID.randomUUID().toString().replace("-",""); }
    private JSONObject request(String path,String method,byte[] body,String filename,String id) throws Exception {
        try { return send(path,method,body,filename,id); }
        catch(UnknownHostException error) {
            throw new IOException("Điện thoại chưa tìm được địa chỉ máy chủ trên mạng hiện tại. Thử lại sau khi Wi-Fi hoặc 4G/5G ổn định.",error);
        } catch(SocketTimeoutException | SocketException error) {
            throw new IOException("Điện thoại chưa kết nối được máy chủ qua mạng hiện tại. Kiểm tra Internet rồi thử lại. Nếu vừa gửi lệnh, kiểm tra trạng thái trước khi gửi lại lệnh.",error);
        }
    }
    private JSONObject send(String path,String method,byte[] body,String filename,String id) throws Exception {
        HttpURLConnection http = connections.open(new URL(BASE + path));
        http.setConnectTimeout(10000); http.setReadTimeout(45000);
        http.setInstanceFollowRedirects(false); http.setUseCaches(false); http.setRequestMethod(method);
        http.setRequestProperty("Accept","application/json");
        if (!token.isEmpty()) http.setRequestProperty("Authorization","Bearer " + token);
        try {
            if (body != null) {
                http.setDoOutput(true); http.setFixedLengthStreamingMode(body.length);
                http.setRequestProperty("Content-Type",filename == null ? "application/json; charset=utf-8" : "application/octet-stream");
                if (filename != null) { http.setRequestProperty("X-Filename",filename); http.setRequestProperty("X-Request-ID",id); }
                try (OutputStream stream = http.getOutputStream()) { stream.write(body); }
            }
            int code = http.getResponseCode();
            InputStream input = code >= 200 && code < 300 ? http.getInputStream() : http.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (input != null) try (InputStream stream = input) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    bytes.write(buffer,0,count);
                    if (bytes.size() > 262144) throw new IOException("Phản hồi máy chủ quá lớn.");
                }
            }
            JSONObject result;
            try { result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())); }
            catch (Exception invalid) { throw new IOException("Máy chủ chưa trả về dữ liệu hợp lệ (HTTP " + code + ")."); }
            if (code == 401) throw new Unauthorized(result.optString("message","Vui lòng đăng nhập lại."));
            if (code < 200 || code >= 300 || !result.optBoolean("ok"))
                throw new IOException(result.optString("message", code == 422 ? "Kiểm tra thông tin đã nhập." : "Máy chủ báo lỗi (HTTP " + code + ")."));
            return result;
        } finally { http.disconnect(); }
    }
    static final class Unauthorized extends IOException { Unauthorized(String message) { super(message); } }
}
