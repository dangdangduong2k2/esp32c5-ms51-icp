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
        HttpURLConnection connection = open(path, "GET");
        return executeJson(connection, null, null);
    }

    JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        HttpURLConnection connection = open(path, "POST");
        return executeJson(connection, body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    JSONObject upload(String fileName, byte[] data) throws IOException, JSONException {
        HttpURLConnection connection = open("/api/upload", "POST");
        connection.setRequestProperty("X-Filename", fileName == null ? "firmware.hex" : fileName);
        return executeJson(connection, data, "application/octet-stream");
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
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
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
            while ((count = reader.read(buffer)) >= 0) output.append(buffer, 0, count);
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
