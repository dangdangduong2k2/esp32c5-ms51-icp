package vn.duong.ms51control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native provisioning and control application for the ESP32 + MS51 programmer.
 * The app deliberately contains no WebView: every screen talks to the ESP REST API directly.
 */
public final class MainActivity extends Activity {
    private static final String ESP_SSID = "MS51-PROGRAMMER";
    private static final String ESP_PASSWORD = "12345678";
    private static final int WIFI_PERMISSION_REQUEST = 9001;
    private static final int FIRMWARE_FILE_REQUEST = 9002;
    private static final int WIFI_SETTINGS_REQUEST = 9003;
    private static final int ROLE_DEVICE = 1;
    private static final int ROLE_PHONE = 2;

    private enum Screen { CONNECT_DEVICE, CONFIGURE_WIFI, SWITCH_PHONE, CONTROL }
    private enum Tab { OVERVIEW, SETTINGS, FIRMWARE, DEVICE }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final EspApi api = new EspApi();
    private final Map<String, EditText> configInputs = new HashMap<>();
    private final Map<String, Spinner> configChoices = new HashMap<>();
    private final Map<String, CheckBox> configChecks = new HashMap<>();
    private final List<View> pinDependentViews = new ArrayList<>();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeNetwork;
    private int requestToken;
    private int pendingRole;
    private String pendingSsid = "";
    private String pendingPassword = "";
    private String pendingStationIp = "";

    private Screen screen = Screen.CONNECT_DEVICE;
    private Tab tab = Tab.OVERVIEW;
    private String lastUiMessage = "Sẵn sàng kết nối thiết bị.";
    private TextView messageView;
    private TextView dashboardConnection;
    private TextView dashboardMode;
    private TextView dashboardVariables;
    private TextView dashboardLogs;
    private TextView[] relayViews = new TextView[3];
    private SevenSegmentView greenDisplay;
    private SevenSegmentView redDisplay;
    private TextView firmwareInfo;
    private TextView firmwareFileInfo;
    private TextView deviceInfo;
    private JSONObject lastStatus;
    private JSONObject lastDebug;
    private int imageGeneration;
    private Uri selectedFirmwareUri;
    private String selectedFirmwareName = "";
    private String uplinkSsid = "";
    private String uplinkIp = "";
    private String uplinkPasswordForPhone = "";
    private RuntimeConfigCodec.Config runtimeConfig = RuntimeConfigCodec.defaults();
    private boolean dashboardBusy;
    private boolean launchedInitialConnection;
    private boolean waitingForWifiSettingsReturn;
    private boolean manualWifiVerificationQueued;

    private final Runnable dashboardRefresh = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        showConnectDevice(false);
        // Opening the app always starts at the device connection gate. Android will show
        // its own explicit consent dialog; this is the strongest connection flow Android permits.
        mainHandler.postDelayed(() -> {
            if (!launchedInitialConnection && screen == Screen.CONNECT_DEVICE) {
                launchedInitialConnection = true;
                startDeviceConnection();
            }
        }, 350);
    }

    // -------------------------------------------------------------------------
    // Provisioning flow
    // -------------------------------------------------------------------------

    private void showConnectDevice(boolean changingWifi) {
        stopDashboard();
        screen = Screen.CONNECT_DEVICE;
        LinearLayout content = createPage("BƯỚC 1 / 3", "Kết nối thiết bị", changingWifi
                ? "Kết nối lại Wi‑Fi riêng của bộ điều khiển trước khi đổi mạng cho thiết bị."
                : "Để bắt đầu, điện thoại cần kết nối Wi‑Fi riêng của bộ điều khiển.");

        LinearLayout card = card();
        addSectionTitle(card, "Wi‑Fi của bộ điều khiển");
        addValueLine(card, "Tên Wi‑Fi", ESP_SSID);
        addValueLine(card, "Mật khẩu", ESP_PASSWORD);
        addHelp(card, "App sẽ mở hộp thoại xác nhận của Android. Chọn Kết nối để tiếp tục.");
        Button connect = primaryButton("Kết nối " + ESP_SSID);
        connect.setOnClickListener(v -> startDeviceConnection());
        card.addView(connect, fullParams(0, 12, 0, 0));
        Button settings = neutralButton("Mở cài đặt Wi‑Fi");
        settings.setOnClickListener(v -> openWifiSettings());
        card.addView(settings, fullParams(0, 8, 0, 0));
        Button check = neutralButton("Đã kết nối, kiểm tra thiết bị");
        check.setOnClickListener(v -> verifyManualWifiAfterSettings());
        card.addView(check, fullParams(0, 8, 0, 0));
        content.addView(card, fullParams(0, 0, 0, 0));

        LinearLayout note = card();
        addSectionTitle(note, "Luồng sử dụng");
        addHelp(note, "1. Kết nối bộ điều khiển  •  2. Cài Wi‑Fi có Internet cho thiết bị  •  3. Chuyển điện thoại sang Wi‑Fi đó. Chỉ sau ba bước app mới mở màn điều khiển.");
        content.addView(note, fullParams(0, 12, 0, 0));
    }

    private void startDeviceConnection() {
        requestWifi(ESP_SSID, ESP_PASSWORD, ROLE_DEVICE, "");
    }

    private void showWifiSetup(JSONObject status) {
        stopDashboard();
        screen = Screen.CONFIGURE_WIFI;
        rememberStatus(status);
        LinearLayout content = createPage("BƯỚC 2 / 3", "Cài Wi‑Fi cho thiết bị",
                "Chọn Wi‑Fi 2.4 GHz có Internet. Thiết bị sẽ tự lưu và kết nối mạng này.");

        JSONObject uplink = getUplink(status);
        String state = uplink.optString("state", "not_configured");
        String currentSsid = uplink.optString("ssid", "");
        String currentIp = uplink.optString("ip", "");
        if ("connected".equals(state) && isPrivateIpv4(currentIp)) {
            LinearLayout activeCard = card();
            addSectionTitle(activeCard, "Wi‑Fi đang lưu trên thiết bị");
            addValueLine(activeCard, "Tên Wi‑Fi", blankForDash(currentSsid));
            addValueLine(activeCard, "IP của thiết bị", currentIp);
            addHelp(activeCard, "Để hiện danh sách Wi‑Fi mới, thiết bị cần ngắt mạng cũ trước. Sau đó app sẽ tự quét danh sách để bạn chọn.");
            Button forget = primaryButton("Đổi Wi‑Fi: ngắt mạng cũ và quét danh sách");
            forget.setOnClickListener(v -> confirmForgetThenConfigure());
            activeCard.addView(forget, fullParams(0, 10, 0, 0));
            content.addView(activeCard, fullParams(0, 0, 0, 0));
        }

        LinearLayout setupCard = card();
        addSectionTitle(setupCard, "Chọn Wi‑Fi mới");
        final EditText ssidInput = textInput("Tên Wi‑Fi có Internet", false);
        if (!currentSsid.isEmpty()) ssidInput.setText(currentSsid);
        setupCard.addView(ssidInput, fullParams(0, 0, 0, 0));
        final LinearLayout scanResults = new LinearLayout(this);
        scanResults.setOrientation(LinearLayout.VERTICAL);
        scanResults.setVisibility(View.GONE);
        Button scan = neutralButton("Quét Wi‑Fi gần đây");
        scan.setOnClickListener(v -> {
            if ("connected".equals(getUplink(lastStatus).optString("state", ""))) {
                confirmForgetThenConfigure();
                return;
            }
            scanNetworks(scan, scanResults, ssidInput);
        });
        setupCard.addView(scan, fullParams(0, 10, 0, 0));
        setupCard.addView(scanResults, fullParams(0, 6, 0, 0));

        final EditText passwordInput = textInput("Mật khẩu Wi‑Fi", true);
        passwordInput.setHint("Để trống nếu Wi‑Fi không có mật khẩu");
        setupCard.addView(passwordInput, fullParams(0, 10, 0, 0));
        Button saveAndConnect = primaryButton("Lưu và kết nối Internet");
        saveAndConnect.setOnClickListener(v -> connectUplink(ssidInput, passwordInput));
        setupCard.addView(saveAndConnect, fullParams(0, 12, 0, 0));
        addHelp(setupCard, "Chỉ hỗ trợ Wi‑Fi 2.4 GHz. Mật khẩu không được lưu trong điện thoại; thiết bị tự lưu trong bộ nhớ của nó.");
        content.addView(setupCard, fullParams(0, 12, 0, 0));

        // Step 2 should behave like a picker, not a blank form. For a fresh
        // or failed uplink the ESP is allowed to scan, so populate the list
        // immediately instead of waiting for the user to discover the button.
        if ("not_configured".equals(state) || "failed".equals(state)) {
            mainHandler.postDelayed(() -> {
                if (screen == Screen.CONFIGURE_WIFI && scan.isEnabled()
                        && scanResults.getVisibility() != View.VISIBLE) {
                    scanNetworks(scan, scanResults, ssidInput);
                }
            }, 180);
        }
    }

    private void scanNetworks(Button scanButton, LinearLayout resultRoot, EditText ssidInput) {
        scanButton.setEnabled(false);
        resultRoot.removeAllViews();
        addHelp(resultRoot, "Đang lấy danh sách Wi‑Fi từ bộ điều khiển…");
        resultRoot.setVisibility(View.VISIBLE);
        showMessage("Thiết bị đang quét Wi‑Fi…");
        io.execute(() -> {
            try {
                JSONObject response = api.get("/api/uplink/scan");
                JSONArray networks = response.optJSONArray("networks");
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    resultRoot.removeAllViews();
                    int count = networks == null ? 0 : networks.length();
                    if (count == 0) {
                        addHelp(resultRoot, "Không tìm thấy Wi‑Fi gần đây. Bạn vẫn có thể tự nhập tên Wi‑Fi.");
                    } else {
                        for (int index = 0; index < count; index++) {
                            JSONObject network = networks.optJSONObject(index);
                            if (network == null) continue;
                            String ssid = network.optString("ssid", "");
                            if (ssid.isEmpty()) continue;
                            String label = ssid + "  ·  " + network.optInt("rssi", 0) + " dBm  ·  "
                                    + network.optString("auth", "Bảo mật");
                            Button option = neutralButton(label);
                            option.setTextSize(12);
                            option.setOnClickListener(v -> {
                                ssidInput.setText(ssid);
                                resultRoot.setVisibility(View.GONE);
                                showMessage("Đã chọn " + ssid + ". Nhập mật khẩu rồi kết nối.");
                            });
                            resultRoot.addView(option, fullParams(0, 5, 0, 0));
                        }
                    }
                    resultRoot.setVisibility(View.VISIBLE);
                    showMessage("Đã quét xong. Chọn một Wi‑Fi hoặc tự nhập tên.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    resultRoot.removeAllViews();
                    addHelp(resultRoot, "Chưa lấy được danh sách Wi‑Fi. Bạn có thể quét lại hoặc tự nhập tên Wi‑Fi.");
                    resultRoot.setVisibility(View.VISIBLE);
                    showMessage(errorMessage(error));
                });
            }
        });
    }

    private void confirmForgetThenConfigure() {
        new AlertDialog.Builder(this)
                .setTitle("Đổi Wi‑Fi cho thiết bị?")
                .setMessage("Thiết bị sẽ ngắt mạng đang dùng, sau đó app tự hiện danh sách Wi‑Fi mới.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Quét Wi‑Fi mới", (dialog, which) -> forgetUplink())
                .show();
    }

    private void forgetUplink() {
        showMessage("Đang quên Wi‑Fi đã lưu…");
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "forget");
                JSONObject response = api.post("/api/uplink", body);
                runOnUiThread(() -> {
                    showMessage(response.optString("message", "Đã quên Wi‑Fi."));
                    refreshSetupStatus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void refreshSetupStatus() {
        io.execute(() -> {
            try {
                JSONObject response = api.get("/api/status");
                runOnUiThread(() -> showWifiSetup(response));
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void connectUplink(EditText ssidInput, EditText passwordInput) {
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (ssid.isEmpty() || utf8Length(ssid) > 32) {
            showMessage("Tên Wi‑Fi phải có từ 1 đến 32 ký tự.");
            return;
        }
        int passwordSize = utf8Length(password);
        if (passwordSize != 0 && (passwordSize < 8 || passwordSize > 63)) {
            showMessage("Mật khẩu Wi‑Fi phải để trống hoặc có từ 8 đến 63 ký tự.");
            return;
        }
        showMessage("Đang lưu Wi‑Fi và đợi thiết bị kết nối…");
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "connect");
                body.put("ssid", ssid);
                body.put("password", password);
                api.post("/api/uplink", body);
                waitForUplink(ssid, password);
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void waitForUplink(String desiredSsid, String desiredPassword) {
        String failure = "";
        for (int attempt = 0; attempt < 36 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                JSONObject status = api.get("/api/status");
                JSONObject uplink = getUplink(status);
                String state = uplink.optString("state", "");
                String ip = uplink.optString("ip", "");
                if ("connected".equals(state) && isPrivateIpv4(ip)) {
                    runOnUiThread(() -> {
                        rememberStatus(status);
                        uplinkSsid = uplink.optString("ssid", desiredSsid);
                        uplinkIp = ip;
                        uplinkPasswordForPhone = desiredPassword;
                        showSwitchPhone();
                    });
                    return;
                }
                if ("failed".equals(state)) {
                    failure = uplink.optString("reason", "Thiết bị không thể vào Wi‑Fi này.");
                    break;
                }
            } catch (Exception error) {
                failure = errorMessage(error);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        final String message = failure.isEmpty()
                ? "Chưa thấy thiết bị kết nối Wi‑Fi. Kiểm tra tên, mật khẩu và sóng 2.4 GHz rồi thử lại."
                : failure;
        runOnUiThread(() -> showMessage(message));
    }

    private void showSwitchPhone() {
        stopDashboard();
        screen = Screen.SWITCH_PHONE;
        LinearLayout content = createPage("BƯỚC 3 / 3", "Chuyển điện thoại sang Wi‑Fi mới",
                "Thiết bị đã vào mạng mới. Điện thoại cần vào cùng mạng để điều khiển bình thường.");

        LinearLayout card = card();
        addSectionTitle(card, "Thiết bị đã kết nối");
        addValueLine(card, "Wi‑Fi", blankForDash(uplinkSsid));
        addValueLine(card, "IP của thiết bị", blankForDash(uplinkIp));
        addHelp(card, "IP này được hiển thị để bạn luôn biết địa chỉ của thiết bị sau khi đổi mạng.");

        final EditText phonePassword = textInput("Mật khẩu Wi‑Fi cho điện thoại", true);
        if (uplinkPasswordForPhone.isEmpty()) {
            phonePassword.setHint("Nhập lại mật khẩu của " + blankForDash(uplinkSsid));
            card.addView(phonePassword, fullParams(0, 10, 0, 0));
        } else {
            addHelp(card, "Mật khẩu vừa nhập sẽ chỉ dùng cho lần chuyển mạng này, không lưu trong app.");
        }

        Button join = primaryButton("Kết nối điện thoại tới " + blankForDash(uplinkSsid));
        join.setOnClickListener(v -> {
            String password = uplinkPasswordForPhone.isEmpty() ? phonePassword.getText().toString() : uplinkPasswordForPhone;
            int size = utf8Length(password);
            if (size != 0 && (size < 8 || size > 63)) {
                showMessage("Mật khẩu Wi‑Fi phải để trống hoặc có từ 8 đến 63 ký tự.");
                return;
            }
            requestWifi(uplinkSsid, password, ROLE_PHONE, uplinkIp);
        });
        card.addView(join, fullParams(0, 12, 0, 0));
        Button settings = neutralButton("Mở cài đặt Wi‑Fi nếu Android không tự chuyển");
        settings.setOnClickListener(v -> openWifiSettings());
        card.addView(settings, fullParams(0, 8, 0, 0));
        Button check = neutralButton("Đã kết nối, kiểm tra IP thiết bị");
        check.setOnClickListener(v -> verifyManualWifiAfterSettings());
        card.addView(check, fullParams(0, 8, 0, 0));
        addHelp(card, "Android luôn yêu cầu bạn xác nhận khi app đổi Wi‑Fi. Sau khi xác nhận, app sẽ kiểm tra đúng IP ở trên trước khi mở điều khiển.");
        content.addView(card, fullParams(0, 0, 0, 0));
    }

    // -------------------------------------------------------------------------
    // Android Wi-Fi connection bridge
    // -------------------------------------------------------------------------

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestWifi(String ssid, String password, int role, String stationIp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showMessage("Máy Android này cần chọn Wi‑Fi thủ công trong phần Cài đặt.");
            openWifiSettings();
            return;
        }
        pendingRole = role;
        pendingSsid = ssid;
        pendingPassword = password;
        pendingStationIp = stationIp;
        if (!hasWifiPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, WIFI_PERMISSION_REQUEST);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, WIFI_PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, WIFI_PERMISSION_REQUEST);
            }
            return;
        }
        if (ssid == null || ssid.trim().isEmpty()) {
            showMessage("Chưa có tên Wi‑Fi để kết nối.");
            return;
        }

        releaseRequestedNetwork();
        final int token = ++requestToken;
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) specifier.setWpa2Passphrase(password);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            specifier.setBand(ScanResult.WIFI_BAND_24_GHZ);
        }
        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier.build());
        // The ESP SoftAP has no Internet, so its request must be local-only.
        // The third step intentionally keeps Internet capability: the phone is
        // moving to the same normal Wi‑Fi as the ESP, not to another local AP.
        if (role == ROLE_DEVICE) requestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        NetworkRequest request = requestBuilder.build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> onWifiAvailable(token, role, network));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> {
                    if (token == requestToken) showMessage("Android không thể kết nối Wi‑Fi đã chọn.");
                });
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> onWifiLost(token, network));
            }
        };
        try {
            showMessage("Android đang yêu cầu kết nối " + ssid + "… hãy xác nhận trong hộp thoại hệ thống.");
            connectivityManager.requestNetwork(request, networkCallback, mainHandler, 30000);
        } catch (SecurityException error) {
            showMessage("Android chưa cho phép thay đổi Wi‑Fi: " + error.getMessage());
        } catch (IllegalArgumentException error) {
            showMessage("Không thể dùng thông tin Wi‑Fi này: " + error.getMessage());
        }
    }

    private void onWifiAvailable(int token, int role, Network network) {
        if (token != requestToken) return;
        activeNetwork = network;
        api.setNetwork(network);
        connectivityManager.bindProcessToNetwork(network);
        if (role == ROLE_DEVICE) {
            api.setBaseUrl(EspApi.DEVICE_BASE_URL);
            showMessage("Đã vào Wi‑Fi bộ điều khiển. Đang kiểm tra thiết bị…");
            verifyDeviceConnection();
        } else {
            api.setBaseUrl("http://" + pendingStationIp);
            showMessage("Đã vào Wi‑Fi mới. Đang kiểm tra IP " + pendingStationIp + "…");
            verifyPhoneOnLan();
        }
    }

    private void onWifiLost(int token, Network network) {
        if (token != requestToken || activeNetwork == null || !activeNetwork.equals(network)) return;
        activeNetwork = null;
        api.setNetwork(null);
        connectivityManager.bindProcessToNetwork(null);
        if (screen == Screen.CONTROL) {
            showMessage("Mất kết nối Wi‑Fi với thiết bị. Vào tab Thiết bị để kết nối lại.");
        } else {
            showMessage("Kết nối Wi‑Fi vừa bị mất. Hãy thử kết nối lại.");
        }
    }

    private void verifyDeviceConnection() {
        io.execute(() -> {
            try {
                JSONObject status = api.get("/api/status");
                runOnUiThread(() -> showWifiSetup(status));
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Đã vào Wi‑Fi nhưng chưa thấy bộ điều khiển: " + errorMessage(error)));
            }
        });
    }

    private void verifyPhoneOnLan() {
        io.execute(() -> {
            try {
                JSONObject status = api.get("/api/status");
                runOnUiThread(() -> {
                    rememberStatus(status);
                    showControl(Tab.OVERVIEW);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Đã vào Wi‑Fi nhưng không mở được IP " + pendingStationIp + ": " + errorMessage(error)));
            }
        });
    }

    private void releaseRequestedNetwork() {
        requestToken++;
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // It may already have been released by Android.
            }
            networkCallback = null;
        }
        activeNetwork = null;
        api.setNetwork(null);
        connectivityManager.bindProcessToNetwork(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WIFI_PERMISSION_REQUEST) return;
        boolean granted = hasWifiPermission();
        if (!granted) {
            showMessage("Cần quyền Wi‑Fi để app mở bước kết nối. Bạn có thể bật quyền trong Cài đặt ứng dụng.");
            return;
        }
        requestWifi(pendingSsid, pendingPassword, pendingRole, pendingStationIp);
    }

    // -------------------------------------------------------------------------
    // Native control tabs
    // -------------------------------------------------------------------------

    private void showControl(Tab selectedTab) {
        stopDashboard();
        screen = Screen.CONTROL;
        tab = selectedTab;
        LinearLayout content = createPage("ĐÃ KẾT NỐI", "Bộ điều khiển", controlSubtitle());
        addNavigation(content);
        switch (tab) {
            case OVERVIEW:
                buildOverview(content);
                mainHandler.post(dashboardRefresh);
                break;
            case SETTINGS:
                buildSettings(content);
                break;
            case FIRMWARE:
                buildFirmware(content);
                break;
            case DEVICE:
                buildDevice(content);
                break;
        }
    }

    private void addNavigation(LinearLayout content) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(2));
        addNavButton(row, Tab.OVERVIEW, "Vận hành");
        addNavButton(row, Tab.SETTINGS, "Cài đặt");
        addNavButton(row, Tab.FIRMWARE, "Nạp chương trình");
        addNavButton(row, Tab.DEVICE, "Thiết bị");
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, fullParams(0, 0, 0, 10));
    }

    private void addNavButton(LinearLayout row, Tab target, String label) {
        Button button = target == tab ? primaryButton(label) : neutralButton(label);
        button.setTextSize(12);
        button.setOnClickListener(v -> showControl(target));
        row.addView(button, wrapParams(0, 0, 7, 0));
    }

    private void buildOverview(LinearLayout content) {
        LinearLayout connectionCard = card();
        addSectionTitle(connectionCard, "Trạng thái kết nối");
        dashboardConnection = bodyText("Đang đọc trạng thái thiết bị…");
        connectionCard.addView(dashboardConnection, fullParams(0, 2, 0, 0));
        Button refresh = neutralButton("Làm mới ngay");
        refresh.setOnClickListener(v -> refreshDashboard());
        connectionCard.addView(refresh, fullParams(0, 10, 0, 0));
        content.addView(connectionCard, fullParams(0, 0, 0, 0));

        LinearLayout modeCard = card();
        addSectionTitle(modeCard, "Chế độ đang chạy");
        dashboardMode = valueText("Đang chờ dữ liệu UART");
        dashboardMode.setTextSize(24);
        modeCard.addView(dashboardMode, fullParams(0, 3, 0, 0));
        content.addView(modeCard, fullParams(0, 12, 0, 0));

        LinearLayout ledCard = card();
        addSectionTitle(ledCard, "Thời gian hiển thị");
        LinearLayout displays = new LinearLayout(this);
        displays.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout greenColumn = new LinearLayout(this); greenColumn.setOrientation(LinearLayout.VERTICAL);
        TextView greenLabel = mutedText("LED xanh · phút");
        greenDisplay = new SevenSegmentView(this);
        greenDisplay.setSegmentColor(Color.rgb(52, 211, 153));
        greenColumn.addView(greenLabel, fullParams(0, 0, 0, 2));
        greenColumn.addView(greenDisplay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
        LinearLayout redColumn = new LinearLayout(this); redColumn.setOrientation(LinearLayout.VERTICAL);
        TextView redLabel = mutedText("LED đỏ · phút");
        redDisplay = new SevenSegmentView(this);
        redDisplay.setSegmentColor(Color.rgb(248, 113, 113));
        redColumn.addView(redLabel, fullParams(0, 0, 0, 2));
        redColumn.addView(redDisplay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
        displays.addView(greenColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        displays.addView(redColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ledCard.addView(displays, fullParams(0, 2, 0, 0));
        content.addView(ledCard, fullParams(0, 12, 0, 0));

        LinearLayout relayCard = card();
        addSectionTitle(relayCard, "Rơ-le");
        LinearLayout relayRow = new LinearLayout(this);
        relayRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = 0; index < relayViews.length; index++) {
            TextView relay = new TextView(this);
            relay.setGravity(Gravity.CENTER);
            relay.setTextColor(Color.rgb(180, 198, 218));
            relay.setTextSize(13);
            relay.setTypeface(Typeface.DEFAULT_BOLD);
            relay.setPadding(dp(4), dp(13), dp(4), dp(13));
            relay.setText("RL" + (index + 1) + "\n—");
            relay.setBackground(rounded(Color.rgb(22, 43, 66), dp(10)));
            relayViews[index] = relay;
            LinearLayout.LayoutParams relayParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            relayParams.setMargins(index == 0 ? 0 : dp(4), 0, index == 2 ? 0 : dp(4), 0);
            relayRow.addView(relay, relayParams);
        }
        relayCard.addView(relayRow, fullParams(0, 2, 0, 0));
        content.addView(relayCard, fullParams(0, 12, 0, 0));

        LinearLayout variablesCard = card();
        addSectionTitle(variablesCard, "Biến MS51 đang gửi");
        dashboardVariables = monospaceText("Đang chờ dữ liệu…");
        variablesCard.addView(dashboardVariables, fullParams(0, 2, 0, 0));
        content.addView(variablesCard, fullParams(0, 12, 0, 0));

        LinearLayout logCard = card();
        addSectionTitle(logCard, "Nhật ký thiết bị");
        dashboardLogs = monospaceText("Đang chờ dữ liệu UART…");
        logCard.addView(dashboardLogs, fullParams(0, 2, 0, 0));
        content.addView(logCard, fullParams(0, 12, 0, 0));
        renderDashboard(lastStatus, lastDebug);
    }

    private void refreshDashboard() {
        if (screen != Screen.CONTROL || tab != Tab.OVERVIEW || dashboardBusy) return;
        dashboardBusy = true;
        io.execute(() -> {
            JSONObject status = null;
            JSONObject debug = null;
            Exception failure = null;
            try {
                status = api.get("/api/status");
                debug = api.get("/api/debug");
            } catch (Exception error) {
                failure = error;
            }
            JSONObject finalStatus = status;
            JSONObject finalDebug = debug;
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                dashboardBusy = false;
                if (screen != Screen.CONTROL || tab != Tab.OVERVIEW) return;
                if (finalFailure == null) {
                    rememberStatus(finalStatus);
                    lastDebug = finalDebug;
                    renderDashboard(finalStatus, finalDebug);
                } else if (dashboardConnection != null) {
                    dashboardConnection.setText("Chưa đọc được thiết bị: " + errorMessage(finalFailure));
                }
                mainHandler.postDelayed(dashboardRefresh, 1000);
            });
        });
    }

    private void renderDashboard(JSONObject status, JSONObject debug) {
        if (dashboardConnection == null) return;
        JSONObject uplink = getUplink(status);
        String ssid = uplink.optString("ssid", uplinkSsid);
        String ip = uplink.optString("ip", uplinkIp);
        String network = ssid.isEmpty() ? "Chưa nhận Wi‑Fi" : ssid;
        dashboardConnection.setText(network + (ip.isEmpty() ? "" : "  ·  IP " + ip));

        if (debug == null || !debug.optBoolean("enabled", false)) {
            dashboardMode.setText("Đang chờ dữ liệu UART");
            greenDisplay.setValue(null); redDisplay.setValue(null);
            for (int index = 0; index < relayViews.length; index++) setRelay(relayViews[index], index + 1, null);
            dashboardVariables.setText("Chưa có biến nào từ MS51.");
            dashboardLogs.setText("Đang chờ dữ liệu UART…");
            return;
        }
        JSONArray variables = debug.optJSONArray("variables");
        Map<String, String> values = new HashMap<>();
        StringBuilder list = new StringBuilder();
        if (variables != null) {
            for (int index = 0; index < variables.length(); index++) {
                JSONObject item = variables.optJSONObject(index);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String value = String.valueOf(item.opt("value"));
                if (name.isEmpty()) continue;
                values.put(name.toLowerCase(Locale.US), value);
                if (list.length() > 0) list.append('\n');
                list.append(name).append("   ").append(value);
            }
        }
        String age = debug.isNull("last_rx_age_ms") ? "" : " · " + debug.optLong("last_rx_age_ms", 0) + " ms";
        String mode = values.get("mode");
        dashboardMode.setText(mode == null || "null".equals(mode) ? "Đang chờ dữ liệu UART" : mode);
        greenDisplay.setValue(values.get("green_min"));
        redDisplay.setValue(values.get("red_min"));
        setRelay(relayViews[0], 1, values.get("relay1"));
        setRelay(relayViews[1], 2, values.get("relay2"));
        setRelay(relayViews[2], 3, values.get("relay3"));
        dashboardVariables.setText(list.length() == 0 ? "Chưa có biến nào từ MS51." : list.toString());

        JSONArray logs = debug.optJSONArray("logs");
        StringBuilder logText = new StringBuilder();
        if (logs != null) {
            for (int index = 0; index < logs.length(); index++) {
                JSONObject log = logs.optJSONObject(index);
                if (log == null) continue;
                String text = log.optString("text", "");
                if (text.isEmpty()) continue;
                if (logText.length() > 0) logText.append('\n');
                logText.append(text);
            }
        }
        dashboardLogs.setText(logText.length() == 0 ? "Đang nhận dữ liệu" + age : logText.toString());
    }

    private void setRelay(TextView relay, int number, String rawValue) {
        if (relay == null) return;
        boolean known = rawValue != null && !"null".equals(rawValue);
        boolean on = known && ("on".equalsIgnoreCase(rawValue) || "1".equals(rawValue) || "true".equalsIgnoreCase(rawValue));
        relay.setText("RL" + number + "\n" + (known ? (on ? "BẬT" : "TẮT") : "—"));
        relay.setTextColor(on ? Color.WHITE : Color.rgb(180, 198, 218));
        relay.setBackground(rounded(on ? Color.rgb(16, 120, 85) : Color.rgb(22, 43, 66), dp(10)));
    }

    // -------------------------------------------------------------------------
    // Native configuration screen
    // -------------------------------------------------------------------------

    private void buildSettings(LinearLayout content) {
        configInputs.clear();
        configChoices.clear();
        configChecks.clear();
        pinDependentViews.clear();
        LinearLayout intro = card();
        addSectionTitle(intro, "Cài đặt vận hành");
        addHelp(intro, "Đọc cài đặt đang dùng trước khi sửa. Khi lưu, MS51 sẽ tự khởi động lại.");
        LinearLayout topRow = new LinearLayout(this); topRow.setOrientation(LinearLayout.HORIZONTAL);
        Button read = neutralButton("Đọc cài đặt");
        read.setOnClickListener(v -> loadRuntimeConfig());
        Button save = primaryButton("Lưu cài đặt");
        save.setOnClickListener(v -> saveRuntimeConfig());
        topRow.addView(read, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); saveParams.setMargins(dp(5), 0, 0, 0);
        topRow.addView(save, saveParams);
        intro.addView(topRow, fullParams(0, 10, 0, 0));
        content.addView(intro, fullParams(0, 0, 0, 0));

        LinearLayout timing = card();
        addSectionTitle(timing, "Thời gian hoạt động");
        addNumberField(timing, "Làm lạnh CL (phút)", "coolCl");
        addNumberField(timing, "Làm lạnh OP (phút)", "coolOp");
        addNumberField(timing, "Xả đá CL (phút)", "defrostCl");
        addNumberField(timing, "Xả đá OP (phút / giây)", "defrostOp");
        addNumberField(timing, "Thời gian chờ ST (100 ms)", "delaySt");
        addNumberField(timing, "ST LED (100 ms)", "stLed");
        addNumberField(timing, "ST LCD (100 ms)", "stLcd");
        addNumberField(timing, "Tự khóa", "lockTime");
        addNumberField(timing, "Độ sáng LED xanh", "greenBrightness");
        addNumberField(timing, "Độ sáng LED đỏ", "redBrightness");
        addNumberField(timing, "Thời gian thử lại", "tryTime");
        addChoiceField(timing, "Đơn vị thử lại", "tryUnit", choices("Giờ", 0, "Ngày", 1));
        addNumberField(timing, "Thời gian HCF OP", "hcfTime");
        addNumberField(timing, "Trễ bật xả đá", "delayDf");
        content.addView(timing, fullParams(0, 12, 0, 0));

        LinearLayout modes = card();
        addSectionTitle(modes, "Chế độ và hiển thị");
        addChoiceField(modes, "Chế độ DF", "df", choices("Tắt", 0, "Bật", 1));
        addChoiceField(modes, "Chế độ END", "end", choices("Tắt", 0, "Bật", 1));
        addChoiceField(modes, "Bắt đầu làm lạnh", "coolStart", choices("CL", 0, "OP", 1));
        addChoiceField(modes, "Bắt đầu xả đá", "defrostStart", choices("CL", 0, "OP", 1));
        addChoiceField(modes, "Kiểu màn hình", "displayMode", choices("LCD", 0, "LED", 1));
        addChoiceField(modes, "Chế độ HCF", "hcfMode", choices("H", 0, "CF", 1));
        addChoiceField(modes, "Chế độ HDF", "hdf", choices("Tắt", 0, "Bật", 1));
        addChoiceField(modes, "Tỷ lệ thời gian R1 / R2", "onTime", choices("R1:1  R2:1", 0, "R1:2  R2:1", 1, "R1:1  R2:2", 2, "R1:2  R2:2", 3));
        addChoiceField(modes, "Số lần chạm", "touch", choices("1", 0, "2", 1));
        addCheckField(modes, "Xả đá OP tính giây", "defrostOpSeconds", false);
        addCheckField(modes, "Hiện chế độ DF", "showDf", false);
        addCheckField(modes, "Hiện chế độ END", "showEnd", false);
        addCheckField(modes, "Hiện chế độ SL", "showSl", false);
        addCheckField(modes, "Hiện chế độ HCF", "showHcf", false);
        addCheckField(modes, "Hiện chế độ HDF", "showHdf", false);
        addCheckField(modes, "Hiện ST LED", "showSt", false);
        content.addView(modes, fullParams(0, 12, 0, 0));

        LinearLayout ranges = card();
        addSectionTitle(ranges, "Giới hạn thời gian (Min / Max)");
        addRangeFields(ranges, "Làm lạnh CL", "rangeCoolCl");
        addRangeFields(ranges, "Làm lạnh OP", "rangeCoolOp");
        addRangeFields(ranges, "Xả đá CL", "rangeDefrostCl");
        addRangeFields(ranges, "Xả đá OP", "rangeDefrostOp");
        addRangeFields(ranges, "ST LCD", "rangeStLcd");
        addRangeFields(ranges, "ST LED", "rangeStLed");
        content.addView(ranges, fullParams(0, 12, 0, 0));

        LinearLayout pin = card();
        addSectionTitle(pin, "Mật khẩu bảo vệ");
        CheckBox pinEnable = addCheckField(pin, "Bật mật khẩu", "pinEnable", true);
        addPinNumberField(pin, "Mật khẩu", "pinPassword");
        addPinNumberField(pin, "Số lần nhập sai tối đa", "pinWrongLimit");
        addPinNumberField(pin, "Chu kỳ yêu cầu mật khẩu", "pinPeriod");
        addPinChoiceField(pin, "Đơn vị chu kỳ", "pinPeriodUnit", choices("Giờ", 0, "Ngày", 1));
        addPinNumberField(pin, "Mật khẩu dự phòng", "pinBackupPassword");
        addPinNumberField(pin, "Thời gian dự phòng", "pinBackupPeriod");
        addPinChoiceField(pin, "Đơn vị dự phòng", "pinBackupPeriodUnit", choices("Giờ", 0, "Ngày", 1));
        CheckBox periodShow = addCheckField(pin, "Hiện phần chỉnh chu kỳ mật khẩu", "pinPeriodShow", true);
        pinDependentViews.add(periodShow);
        pinEnable.setOnCheckedChangeListener((buttonView, isChecked) -> syncPinControls());
        addHelp(pin, "Tắt mật khẩu sẽ xóa toàn bộ thiết lập mật khẩu khi lưu.");
        content.addView(pin, fullParams(0, 12, 0, 0));
        applyRuntimeConfigToViews();
    }

    private void addRangeFields(LinearLayout parent, String label, String key) {
        TextView title = mutedText(label);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(title, fullParams(0, 9, 0, 2));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        EditText min = numberInput("Min");
        EditText max = numberInput("Max");
        configInputs.put(key + "Min", min); configInputs.put(key + "Max", max);
        row.addView(min, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams maxParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); maxParams.setMargins(dp(6), 0, 0, 0);
        row.addView(max, maxParams);
        parent.addView(row, fullParams(0, 0, 0, 0));
    }

    private void addNumberField(LinearLayout parent, String label, String key) {
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        EditText input = numberInput("");
        configInputs.put(key, input);
        parent.addView(input, fullParams(0, 0, 0, 0));
    }

    private void addPinNumberField(LinearLayout parent, String label, String key) {
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        EditText input = numberInput("");
        configInputs.put(key, input); pinDependentViews.add(fieldLabel); pinDependentViews.add(input);
        parent.addView(input, fullParams(0, 0, 0, 0));
    }

    private void addChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        Spinner spinner = choiceSpinner(choices);
        configChoices.put(key, spinner);
        parent.addView(spinner, fullParams(0, 0, 0, 0));
    }

    private void addPinChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        Spinner spinner = choiceSpinner(choices);
        configChoices.put(key, spinner); pinDependentViews.add(fieldLabel); pinDependentViews.add(spinner);
        parent.addView(spinner, fullParams(0, 0, 0, 0));
    }

    private CheckBox addCheckField(LinearLayout parent, String label, String key, boolean pinRelated) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextColor(Color.rgb(222, 233, 246));
        check.setTextSize(14);
        check.setPadding(0, dp(5), 0, dp(5));
        configChecks.put(key, check);
        if (pinRelated && !"pinEnable".equals(key)) pinDependentViews.add(check);
        parent.addView(check, fullParams(0, 3, 0, 0));
        return check;
    }

    private void loadRuntimeConfig() {
        showMessage("Đang đọc cài đặt từ MS51…");
        io.execute(() -> {
            try {
                JSONObject response = api.get("/api/runtime-config");
                RuntimeConfigCodec.Config decoded = RuntimeConfigCodec.decode(response.getString("data"));
                runOnUiThread(() -> {
                    runtimeConfig = decoded;
                    applyRuntimeConfigToViews();
                    showMessage(response.optString("message", "Đã đọc cài đặt."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void saveRuntimeConfig() {
        final Map<String, Integer> values;
        final byte[] bytes;
        try {
            values = collectRuntimeConfigValues();
            bytes = RuntimeConfigCodec.encode(runtimeConfig.raw, values);
        } catch (Exception error) {
            showMessage(errorMessage(error));
            return;
        }
        showMessage("Đang lưu cài đặt. MS51 sẽ khởi động lại…");
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("data", RuntimeConfigCodec.toHex(bytes));
                JSONObject response = api.post("/api/runtime-config", body);
                runOnUiThread(() -> {
                    runtimeConfig = new RuntimeConfigCodec.Config(bytes, values);
                    showMessage(response.optString("message", "Đã lưu cài đặt."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private Map<String, Integer> collectRuntimeConfigValues() {
        Map<String, Integer> values = new HashMap<>(runtimeConfig.values);
        for (Map.Entry<String, EditText> entry : configInputs.entrySet()) {
            String text = entry.getValue().getText().toString().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("Hãy nhập " + friendlyConfigName(entry.getKey()) + ".");
            try {
                values.put(entry.getKey(), Integer.parseInt(text));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(friendlyConfigName(entry.getKey()) + " không hợp lệ.");
            }
        }
        for (Map.Entry<String, Spinner> entry : configChoices.entrySet()) {
            Choice choice = (Choice) entry.getValue().getSelectedItem();
            values.put(entry.getKey(), choice.value);
        }
        for (Map.Entry<String, CheckBox> entry : configChecks.entrySet()) {
            values.put(entry.getKey(), entry.getValue().isChecked() ? 1 : 0);
        }
        return values;
    }

    private void applyRuntimeConfigToViews() {
        for (Map.Entry<String, EditText> entry : configInputs.entrySet()) {
            entry.getValue().setText(String.valueOf(configValue(entry.getKey())));
        }
        for (Map.Entry<String, Spinner> entry : configChoices.entrySet()) {
            selectChoice(entry.getValue(), configValue(entry.getKey()));
        }
        for (Map.Entry<String, CheckBox> entry : configChecks.entrySet()) {
            entry.getValue().setChecked(configValue(entry.getKey()) == 1);
        }
        syncPinControls();
    }

    private int configValue(String key) {
        Integer value = runtimeConfig.values.get(key);
        return value == null ? 0 : value;
    }

    private void syncPinControls() {
        CheckBox enabled = configChecks.get("pinEnable");
        boolean active = enabled == null || enabled.isChecked();
        for (View view : pinDependentViews) {
            view.setEnabled(active);
            view.setAlpha(active ? 1f : 0.43f);
        }
    }

    // -------------------------------------------------------------------------
    // Native firmware tools
    // -------------------------------------------------------------------------

    private void buildFirmware(LinearLayout content) {
        LinearLayout fileCard = card();
        addSectionTitle(fileCard, "Tệp chương trình MS51");
        firmwareFileInfo = bodyText(selectedFirmwareName.isEmpty()
                ? "Chưa chọn tệp .hex hoặc .bin."
                : "Đã chọn: " + selectedFirmwareName);
        fileCard.addView(firmwareFileInfo, fullParams(0, 2, 0, 0));
        Button choose = neutralButton("Chọn tệp HEX / BIN");
        choose.setOnClickListener(v -> chooseFirmware());
        fileCard.addView(choose, fullParams(0, 10, 0, 0));
        Button upload = primaryButton("Gửi tệp vào thiết bị");
        upload.setOnClickListener(v -> uploadFirmware());
        fileCard.addView(upload, fullParams(0, 8, 0, 0));
        addHelp(fileCard, "Tệp HEX hoặc BIN được gửi trực tiếp vào ESP32. Chọn tệp không tự nạp vào MS51.");
        content.addView(fileCard, fullParams(0, 0, 0, 0));

        LinearLayout programCard = card();
        addSectionTitle(programCard, "Nạp chương trình");
        firmwareInfo = bodyText(imageSummary());
        programCard.addView(firmwareInfo, fullParams(0, 2, 0, 0));
        Button info = neutralButton("Đọc thông tin MS51");
        info.setOnClickListener(v -> readChipInfo());
        programCard.addView(info, fullParams(0, 10, 0, 0));
        Button program = primaryButton("Nạp chương trình");
        program.setOnClickListener(v -> runJob("program", true, false));
        programCard.addView(program, fullParams(0, 8, 0, 0));
        Button verify = neutralButton("Đối chiếu chương trình");
        verify.setOnClickListener(v -> runJob("verify", true, false));
        programCard.addView(verify, fullParams(0, 8, 0, 0));
        Button reset = neutralButton("Khởi động lại MS51");
        reset.setOnClickListener(v -> runJob("reset", false, false));
        programCard.addView(reset, fullParams(0, 8, 0, 0));
        content.addView(programCard, fullParams(0, 12, 0, 0));

        LinearLayout dangerCard = card();
        addSectionTitle(dangerCard, "Dành cho sửa chữa");
        addHelp(dangerCard, "Các lệnh bên dưới có thể xóa phần chương trình hoặc cấu hình không có trong tệp. Chúng yêu cầu nhập CONFIRM.");
        Button fullProgram = dangerButton("Ghi đè toàn bộ bộ nhớ");
        fullProgram.setOnClickListener(v -> confirmDanger("program-full"));
        dangerCard.addView(fullProgram, fullParams(0, 10, 0, 0));
        Button erase = dangerButton("Xóa thiết bị");
        erase.setOnClickListener(v -> confirmDanger("erase"));
        dangerCard.addView(erase, fullParams(0, 8, 0, 0));
        content.addView(dangerCard, fullParams(0, 12, 0, 0));
    }

    private void chooseFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "text/plain", "text/x-ihex", "application/x-ihex"});
        try {
            startActivityForResult(intent, FIRMWARE_FILE_REQUEST);
        } catch (ActivityNotFoundException error) {
            showMessage("Điện thoại không có trình chọn tệp.");
        }
    }

    private void uploadFirmware() {
        if (selectedFirmwareUri == null || selectedFirmwareName.isEmpty()) {
            showMessage("Hãy chọn tệp HEX hoặc BIN trước.");
            return;
        }
        String lower = selectedFirmwareName.toLowerCase(Locale.US);
        if (!(lower.endsWith(".hex") || lower.endsWith(".ihex") || lower.endsWith(".ihx") || lower.endsWith(".bin"))) {
            showMessage("Thiết bị chỉ nhận tệp .hex, .ihex, .ihx hoặc .bin.");
            return;
        }
        showMessage("Đang gửi " + selectedFirmwareName + " vào thiết bị…");
        io.execute(() -> {
            try {
                String lowerName = selectedFirmwareName.toLowerCase(Locale.US);
                int limit = lowerName.endsWith(".bin") ? 32 * 1024 : 96 * 1024;
                byte[] bytes = readUri(selectedFirmwareUri, limit);
                String serverName = selectedFirmwareName.replaceAll("[^A-Za-z0-9._-]", "_");
                JSONObject response = api.upload(serverName, bytes);
                JSONObject image = response.optJSONObject("image");
                runOnUiThread(() -> {
                    if (image != null) imageGeneration = image.optInt("generation", 0);
                    if (firmwareInfo != null) firmwareInfo.setText(imageSummary(image));
                    showMessage(response.optString("message", "Đã gửi tệp vào thiết bị."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void readChipInfo() {
        showMessage("Đang đọc thông tin MS51…");
        io.execute(() -> {
            try {
                JSONObject response = api.post("/api/info", new JSONObject());
                String info = "PDID: " + response.optInt("pdid", 0)
                        + "\nDevice ID: " + response.optInt("device_id", 0)
                        + "\nKhóa chip: " + (response.optBoolean("locked", false) ? "Có" : "Không")
                        + "\nAPROM: " + response.optInt("aprom_size", 0) + " bytes";
                runOnUiThread(() -> {
                    if (firmwareInfo != null) firmwareInfo.setText(info);
                    if (deviceInfo != null) deviceInfo.setText(info);
                    showMessage(response.optString("message", "Đã đọc thông tin MS51."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void confirmDanger(String action) {
        final EditText confirm = new EditText(this);
        confirm.setSingleLine(true);
        confirm.setHint("Nhập CONFIRM");
        confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        String title = "program-full".equals(action) ? "Ghi đè toàn bộ bộ nhớ?" : "Xóa thiết bị?";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Thao tác này không thể hoàn tác.")
                .setView(confirm)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xác nhận", (dialog, which) -> {
                    if (!"CONFIRM".equals(confirm.getText().toString().trim())) {
                        showMessage("Chưa nhập đúng CONFIRM.");
                        return;
                    }
                    runJob(action, "program-full".equals(action), true);
                })
                .show();
    }

    private void runJob(String action, boolean needsImage, boolean destructive) {
        if (needsImage && imageGeneration <= 0) {
            showMessage("Hãy gửi tệp firmware vào thiết bị trước.");
            return;
        }
        showMessage("Thiết bị đang thực hiện: " + jobLabel(action));
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("generation", needsImage ? imageGeneration : 0);
                if (destructive) body.put("confirm", "CONFIRM");
                JSONObject response = api.post("/api/" + action, body);
                boolean accepted = response.optBoolean("accepted", false);
                runOnUiThread(() -> {
                    showMessage(response.optString("message", "Đã gửi lệnh."));
                    if (accepted) pollJob(80);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    private void pollJob(int remaining) {
        io.execute(() -> {
            try {
                JSONObject status = api.get("/api/status");
                runOnUiThread(() -> {
                    rememberStatus(status);
                    boolean busy = status.optBoolean("busy", false);
                    String message = status.optString("last_message", busy ? "Đang xử lý…" : "Hoàn tất.");
                    showMessage(message);
                    if (busy && remaining > 0) mainHandler.postDelayed(() -> pollJob(remaining - 1), 850);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Device tab
    // -------------------------------------------------------------------------

    private void buildDevice(LinearLayout content) {
        LinearLayout networkCard = card();
        addSectionTitle(networkCard, "Kết nối hiện tại");
        JSONObject uplink = getUplink(lastStatus);
        addValueLine(networkCard, "Wi‑Fi", blankForDash(uplink.optString("ssid", uplinkSsid)));
        addValueLine(networkCard, "IP của thiết bị", blankForDash(uplink.optString("ip", uplinkIp)));
        addValueLine(networkCard, "Tín hiệu", uplink.has("rssi") ? uplink.optInt("rssi", 0) + " dBm" : "—");
        Button refresh = neutralButton("Làm mới trạng thái");
        refresh.setOnClickListener(v -> refreshDeviceStatus());
        networkCard.addView(refresh, fullParams(0, 10, 0, 0));
        Button change = primaryButton("Đổi Wi‑Fi cho thiết bị");
        change.setOnClickListener(v -> {
            releaseRequestedNetwork();
            api.setBaseUrl(EspApi.DEVICE_BASE_URL);
            showConnectDevice(true);
        });
        networkCard.addView(change, fullParams(0, 8, 0, 0));
        content.addView(networkCard, fullParams(0, 0, 0, 0));

        LinearLayout chipCard = card();
        addSectionTitle(chipCard, "Thông tin MS51");
        deviceInfo = monospaceText("Bấm Đọc thông tin MS51 để kiểm tra chip.");
        chipCard.addView(deviceInfo, fullParams(0, 2, 0, 0));
        Button info = neutralButton("Đọc thông tin MS51");
        info.setOnClickListener(v -> readChipInfo());
        chipCard.addView(info, fullParams(0, 10, 0, 0));
        content.addView(chipCard, fullParams(0, 12, 0, 0));
    }

    private void refreshDeviceStatus() {
        showMessage("Đang làm mới trạng thái…");
        io.execute(() -> {
            try {
                JSONObject status = api.get("/api/status");
                runOnUiThread(() -> {
                    rememberStatus(status);
                    showControl(Tab.DEVICE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showMessage(errorMessage(error)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Common data and view helpers
    // -------------------------------------------------------------------------

    private LinearLayout createPage(String eyebrow, String title, String subtitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 21, 36));
        root.setPadding(dp(16), dp(18), dp(16), 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));

        TextView eyebrowView = new TextView(this);
        eyebrowView.setText(eyebrow);
        eyebrowView.setTextColor(Color.rgb(52, 211, 153));
        eyebrowView.setTextSize(11);
        eyebrowView.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrowView.setLetterSpacing(0.12f);
        content.addView(eyebrowView, fullParams(0, 0, 0, 3));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(235, 243, 252));
        titleView.setTextSize(26);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(titleView, fullParams(0, 0, 0, 4));

        TextView subtitleView = bodyText(subtitle);
        content.addView(subtitleView, fullParams(0, 0, 0, 12));

        messageView = new TextView(this);
        messageView.setText(lastUiMessage);
        messageView.setTextColor(Color.rgb(192, 211, 232));
        messageView.setTextSize(13);
        messageView.setPadding(dp(12), dp(10), dp(12), dp(10));
        messageView.setBackground(rounded(Color.rgb(15, 36, 58), dp(10)));
        content.addView(messageView, fullParams(0, 0, 0, 12));

        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        return content;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(rounded(Color.rgb(16, 34, 56), dp(14)));
        return card;
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(Color.rgb(235, 243, 252));
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(view, fullParams(0, 0, 0, 5));
    }

    private void addHelp(LinearLayout parent, String text) {
        parent.addView(bodyText(text), fullParams(0, 5, 0, 0));
    }

    private void addValueLine(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView key = mutedText(label);
        TextView val = valueText(value);
        val.setGravity(Gravity.END);
        val.setTextIsSelectable(true);
        row.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.92f));
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f));
        parent.addView(row, fullParams(0, 7, 0, 0));
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(162, 184, 211));
        view.setTextSize(14);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private TextView mutedText(String text) {
        TextView view = bodyText(text);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(145, 170, 200));
        return view;
    }

    private TextView valueText(String text) {
        TextView view = bodyText(text);
        view.setTextColor(Color.rgb(232, 240, 250));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView monospaceText(String text) {
        TextView view = bodyText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(Color.rgb(218, 229, 243));
        view.setTextSize(12);
        view.setTextIsSelectable(true);
        view.setPadding(dp(2), dp(3), dp(2), dp(3));
        return view;
    }

    private EditText textInput(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(Color.rgb(240, 247, 255));
        input.setHintTextColor(Color.rgb(126, 152, 181));
        input.setHint(hint);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(rounded(Color.rgb(9, 25, 42), dp(9)));
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private EditText numberInput(String hint) {
        EditText input = textInput(hint, false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private Spinner choiceSpinner(Choice[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<Choice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setBackground(rounded(Color.rgb(9, 25, 42), dp(8)));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        return spinner;
    }

    private void selectChoice(Spinner spinner, int desiredValue) {
        for (int index = 0; index < spinner.getCount(); index++) {
            Choice value = (Choice) spinner.getItemAtPosition(index);
            if (value.value == desiredValue) {
                spinner.setSelection(index);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private Button primaryButton(String text) { return button(text, Color.rgb(16, 117, 83)); }
    private Button neutralButton(String text) { return button(text, Color.rgb(28, 57, 85)); }
    private Button dangerButton(String text) { return button(text, Color.rgb(125, 45, 61)); }

    private Button button(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(46));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(rounded(color, dp(9)));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams fullParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams wrapParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String message) {
        lastUiMessage = message == null || message.trim().isEmpty() ? "Đã xong." : message;
        if (messageView != null) messageView.setText(lastUiMessage);
    }

    private void openWifiSettings() {
        if (screen == Screen.SWITCH_PHONE) {
            // The prior AP connection is local-only. Release it before showing
            // Android's Wi-Fi chooser so the selected Internet Wi-Fi becomes
            // the route used by the verification step after returning.
            releaseRequestedNetwork();
            if (!uplinkIp.isEmpty()) api.setBaseUrl("http://" + uplinkIp);
        }
        waitingForWifiSettingsReturn = true;
        try {
            Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new Intent(Settings.Panel.ACTION_WIFI)
                    : new Intent(Settings.ACTION_WIFI_SETTINGS);
            startActivityForResult(intent, WIFI_SETTINGS_REQUEST);
        } catch (ActivityNotFoundException error) {
            waitingForWifiSettingsReturn = false;
            Toast.makeText(this, "Không mở được cài đặt Wi‑Fi trên máy này.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Settings.Panel is an Android-owned screen. When it closes, verify the
     * selected Wi-Fi immediately instead of leaving the user at a dead end.
     */
    private void verifyManualWifiAfterSettings() {
        if (manualWifiVerificationQueued) return;
        if (screen != Screen.CONNECT_DEVICE && screen != Screen.SWITCH_PHONE) return;
        manualWifiVerificationQueued = true;
        if (activeNetwork == null) {
            api.setNetwork(null);
            connectivityManager.bindProcessToNetwork(null);
        }
        if (screen == Screen.CONNECT_DEVICE) {
            api.setBaseUrl(EspApi.DEVICE_BASE_URL);
            showMessage("Đang kiểm tra bộ điều khiển sau khi đổi Wi‑Fi…");
        } else {
            if (uplinkIp.isEmpty()) {
                manualWifiVerificationQueued = false;
                showMessage("Chưa có IP Wi‑Fi mới của thiết bị.");
                return;
            }
            api.setBaseUrl("http://" + uplinkIp);
            showMessage("Đang kiểm tra IP " + uplinkIp + " sau khi đổi Wi‑Fi…");
        }
        io.execute(() -> {
            try {
                JSONObject status = api.get("/api/status");
                runOnUiThread(() -> {
                    manualWifiVerificationQueued = false;
                    if (screen == Screen.CONNECT_DEVICE) showWifiSetup(status);
                    else if (screen == Screen.SWITCH_PHONE) {
                        rememberStatus(status);
                        showControl(Tab.OVERVIEW);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    manualWifiVerificationQueued = false;
                    showMessage("Chưa thấy thiết bị trên Wi‑Fi đang chọn: " + errorMessage(error));
                });
            }
        });
    }

    private void rememberStatus(JSONObject status) {
        if (status == null) return;
        lastStatus = status;
        JSONObject image = status.optJSONObject("image");
        if (image != null) {
            imageGeneration = image.optBoolean("valid", false) ? image.optInt("generation", 0) : 0;
        }
        JSONObject uplink = getUplink(status);
        if ("connected".equals(uplink.optString("state", ""))) {
            uplinkSsid = uplink.optString("ssid", uplinkSsid);
            uplinkIp = uplink.optString("ip", uplinkIp);
        }
    }

    private JSONObject getUplink(JSONObject status) {
        if (status == null) return new JSONObject();
        JSONObject wifi = status.optJSONObject("wifi");
        return wifi == null ? new JSONObject() : nullToEmpty(wifi.optJSONObject("uplink"));
    }

    private JSONObject nullToEmpty(JSONObject object) {
        return object == null ? new JSONObject() : object;
    }

    private String controlSubtitle() {
        String ssid = uplinkSsid;
        String ip = uplinkIp;
        if (lastStatus != null) {
            JSONObject uplink = getUplink(lastStatus);
            ssid = uplink.optString("ssid", ssid);
            ip = uplink.optString("ip", ip);
        }
        if (ssid.isEmpty()) return "Kết nối với thiết bị đã sẵn sàng.";
        return ssid + (ip.isEmpty() ? "" : " · IP " + ip);
    }

    private String imageSummary() {
        return imageSummary(lastStatus == null ? null : lastStatus.optJSONObject("image"));
    }

    private String imageSummary(JSONObject image) {
        if (image == null || !image.optBoolean("valid", false)) return "Chưa có tệp chương trình trên thiết bị.";
        return "Tệp: " + image.optString("name", "firmware")
                + "\nDung lượng: " + image.optInt("size", 0) + " bytes"
                + "\nDữ liệu chương trình: " + image.optInt("covered_size", 0) + " bytes"
                + "\nSẵn sàng nạp.";
    }

    private static String blankForDash(String text) { return text == null || text.isEmpty() ? "—" : text; }

    private static boolean isPrivateIpv4(String address) {
        if (address == null) return false;
        String[] parts = address.trim().split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]); int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]); int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) return false;
            return a == 10 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String errorMessage(Exception error) {
        String message = error == null ? "Có lỗi không xác định." : error.getMessage();
        return message == null || message.trim().isEmpty() ? "Có lỗi không xác định." : message;
    }

    private static String jobLabel(String action) {
        if ("program".equals(action)) return "nạp chương trình";
        if ("verify".equals(action)) return "đối chiếu chương trình";
        if ("reset".equals(action)) return "khởi động lại";
        if ("program-full".equals(action)) return "ghi đè toàn bộ bộ nhớ";
        if ("erase".equals(action)) return "xóa thiết bị";
        return action;
    }

    private static String friendlyConfigName(String key) {
        return key.replace("range", "Giới hạn ").replace("Min", " tối thiểu").replace("Max", " tối đa");
    }

    private static Choice[] choices(Object... items) {
        Choice[] result = new Choice[items.length / 2];
        for (int index = 0; index < items.length; index += 2) {
            result[index / 2] = new Choice((String) items[index], (Integer) items[index + 1]);
        }
        return result;
    }

    private static final class Choice {
        final String label;
        final int value;
        Choice(String label, int value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private byte[] readUri(Uri uri, int maxBytes) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Không mở được tệp đã chọn.");
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maxBytes) throw new IOException("Tệp quá lớn cho thiết bị.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        if (uri == null) return "firmware.hex";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
            // A content provider may not expose a display name; keep a safe fallback.
        }
        return "firmware.hex";
    }

    private void stopDashboard() {
        mainHandler.removeCallbacks(dashboardRefresh);
        dashboardBusy = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WIFI_SETTINGS_REQUEST) {
            waitingForWifiSettingsReturn = false;
            mainHandler.postDelayed(this::verifyManualWifiAfterSettings, 400);
            return;
        }
        if (requestCode != FIRMWARE_FILE_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        selectedFirmwareUri = data.getData();
        selectedFirmwareName = displayName(selectedFirmwareUri);
        if (firmwareFileInfo != null) firmwareFileInfo.setText("Đã chọn: " + selectedFirmwareName);
        showMessage("Đã chọn " + selectedFirmwareName + ". Bấm Gửi tệp vào thiết bị để tiếp tục.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForWifiSettingsReturn) {
            waitingForWifiSettingsReturn = false;
            mainHandler.postDelayed(this::verifyManualWifiAfterSettings, 400);
        }
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.CONTROL && tab != Tab.OVERVIEW) {
            showControl(Tab.OVERVIEW);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopDashboard();
        releaseRequestedNetwork();
        io.shutdownNow();
        super.onDestroy();
    }
}
