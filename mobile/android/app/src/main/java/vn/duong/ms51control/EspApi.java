package vn.duong.ms51control;

import android.net.Network;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/** Small, dependency-free client for the ESP32 HTTP API. */
final class EspApi {
    static final String DEVICE_BASE_URL = "http://192.168.4.1";

    private volatile String baseUrl = DEVICE_BASE_URL;
    private volatile Network network;
    private volatile boolean demo;
    private volatile String expectedDeviceId = "";
    private int readTimeoutMs = 15000;
    private CloudApi cloud;
    private String cloudDevice = "";
    void setCloud(CloudApi session, String device) { cloud=session; cloudDevice=device; }

    /** Freeze destination and network before queuing a provisioning operation. */
    EspApi snapshot(int timeoutMs) {
        EspApi copy = new EspApi();
        copy.baseUrl = baseUrl;
        copy.network = network;
        copy.demo = demo;
        copy.expectedDeviceId = expectedDeviceId;
        copy.readTimeoutMs = timeoutMs;
        copy.cloud=cloud; copy.cloudDevice=cloudDevice;
        return copy;
    }

    void setDemo(boolean value) { demo = value; }
    void setExpectedDeviceId(String value) { expectedDeviceId = value == null ? "" : value; }

    private JSONObject demoResponse(String path) throws JSONException, IOException {
        if ("/api/status".equals(path)) {
            return new JSONObject("{\"ok\":true,\"busy\":false,\"wifi\":{\"uplink\":{\"state\":\"connected\",\"ssid\":\"Wi-Fi minh họa\",\"ip\":\"192.168.1.51\",\"rssi\":-48}},\"image\":{\"valid\":false}}");
        }
        if ("/api/debug".equals(path)) {
            JSONObject response = new JSONObject("{\"ok\":true,\"enabled\":true,\"last_rx_age_ms\":120}");
            org.json.JSONArray variables = new org.json.JSONArray();
            String[][] values = {{"mode","COOL"},{"green_min","025"},{"red_min","008"},
                    {"relay1","1"},{"relay2","0"},{"relay3","1"}};
            for (String[] pair : values) variables.put(new JSONObject().put("name",pair[0]).put("value",pair[1]));
            response.put("variables",variables);
            response.put("logs",new org.json.JSONArray().put(new JSONObject().put("text",
                    "Dữ liệu minh họa • Chưa kết nối phần cứng.\nKết nối thiết bị để nhận nhật ký trực tiếp.")));
            return response;
        }
        if ("/api/runtime-config".equals(path)) {
            return new JSONObject().put("ok",true).put("data",RuntimeConfigCodec.toHex(RuntimeConfigCodec.defaults().raw))
                    .put("message","Đang hiển thị cài đặt minh họa.");
        }
        throw new IOException("Chế độ xem thử: kết nối thiết bị để dùng chức năng này.");
    }

    void setBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) return;
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        baseUrl = normalized;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    void setNetwork(Network value) {
        network = value;
    }

    JSONObject get(String path) throws IOException, JSONException {
        if (demo) return demoResponse(path);
        if (cloud != null) return remote("GET",path,null);
        HttpURLConnection connection = open(path, "GET");
        JSONObject result = executeJson(connection, null, null);
        if ("/api/status".equals(path) && !expectedDeviceId.isEmpty()
                && !expectedDeviceId.equals(DeviceStore.identity(result)))
            throw new IOException("Địa chỉ này không còn thuộc thiết bị đã ghép.");
        return result;
    }

    JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        if (demo) throw new IOException("Chế độ xem thử: không gửi lệnh đến thiết bị.");
        if (cloud != null) return remote("POST",path,body);
        HttpURLConnection connection = open(path, "POST");
        return executeJson(connection, body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    JSONObject upload(String fileName, byte[] data) throws IOException, JSONException {
        if (demo) throw new IOException("Chế độ xem thử: không tải tệp lên thiết bị.");
        if (cloud != null) {
            try { return cloud.upload(cloudDevice,fileName,data); }
            catch(IOException e) { throw e; }
            catch(Exception e) { throw new IOException(e.getMessage(),e); }
        }
        HttpURLConnection connection = open("/api/upload", "POST");
        connection.setRequestProperty("X-Filename", fileName == null ? "firmware.hex" : fileName);
        return executeJson(connection, data, "application/octet-stream");
    }

    private JSONObject remote(String method,String path,JSONObject body) throws IOException {
        try {
            JSONObject result=cloud.rpc(cloudDevice,method,path,body);
            if ("/api/status".equals(path) && !cloudDevice.equals(DeviceStore.identity(result)))
                throw new IOException("Mã thiết bị trả về không khớp.");
            return result;
        } catch(IOException e) { throw e; }
        catch(Exception e) { throw new IOException(e.getMessage(),e); }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        String normalizedPath = path == null || path.startsWith("/") ? path : "/" + path;
        URL url = new URL(baseUrl + normalizedPath);
        Network requestedNetwork = network;
        URLConnection rawConnection = requestedNetwork == null
                ? url.openConnection()
                : requestedNetwork.openConnection(url);
        if (!(rawConnection instanceof HttpURLConnection)) {
            throw new IOException("Kết nối HTTP không hợp lệ.");
        }
        HttpURLConnection connection = (HttpURLConnection) rawConnection;
        connection.setRequestMethod(method);
        connection.setConnectTimeout(Math.min(5000, readTimeoutMs));
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        if (!expectedDeviceId.isEmpty()) connection.setRequestProperty("X-MS51-Device", expectedDeviceId);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private JSONObject executeJson(HttpURLConnection connection, byte[] body, String contentType)
            throws IOException, JSONException {
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int code = connection.getResponseCode();
            InputStream source = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = source == null ? "" : readAll(source);
            JSONObject response;
            try {
                response = new JSONObject(text);
            } catch (JSONException error) {
                throw new ApiException("Thiết bị trả về dữ liệu không hợp lệ (HTTP " + code + ").", error);
            }
            String message = response.optString("message", "");
            if (code < 200 || code >= 300 || !response.optBoolean("ok", false)) {
                if (message.isEmpty()) message = "Thiết bị báo lỗi (HTTP " + code + ").";
                throw new ApiException(message);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream source) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                output.append(buffer, 0, count);
                if(output.length()>262144) throw new IOException("Phản hồi thiết bị quá lớn.");
            }
        }
        return output.toString();
    }

    static final class ApiException extends IOException {
        ApiException(String message) {
            super(message);
        }

        ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
