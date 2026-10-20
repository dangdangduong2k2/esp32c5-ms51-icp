package vn.duong.ms51control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageView;
import android.view.WindowInsets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
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

    private LinearLayout pageRoot;
    private boolean demoMode;
    private boolean configLoaded;
    private volatile int dashboardEpoch;
    private volatile boolean foreground;

    private enum Screen { AUTH, HOME, CONNECT_DEVICE, CONFIGURE_WIFI, WIFI_PASSWORD, SWITCH_PHONE, CONTROL }
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
    private ConnectivityManager.NetworkCallback internetCallback;
    private Network observedInternet;
    private boolean observedInternetAvailable;
    private final Runnable internetChanged = this::refreshAfterInternetChange;
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
    private TextView dashboardLogs;
    private TextView temperatureValue;
    private TextView temperatureNote;
    private TextView temperature50Value, temperature50Note, temperature20Value, temperature20Note;
    private Spinner triggerSource;
    private EditText triggerThreshold;
    private TextView triggerState;
    private Button triggerSave;
    private boolean triggerLoaded, triggerSaving;
    private static final String[] TRIGGER_SOURCES = {"SIG","10K","20K","50K"};
    private final Runnable expireTriggerControls = () -> { if(triggerSave!=null) triggerSave.setEnabled(false); };
    private final CountdownTracker countdownTracker = new CountdownTracker();
    private final Runnable expireCounting = () -> stopCounting();

    private void stopCounting() {
        if (greenDisplay != null) greenDisplay.setCounting(false);
        if (redDisplay != null) redDisplay.setCounting(false);
        mainHandler.removeCallbacks(expireCounting);
    }
    private long[] lastHistory;
    private long nextHistoryRead;
    private String historyError = "";
    private TextView[] relayViews = new TextView[3];
    private SevenSegmentView greenDisplay;
    private SevenSegmentView redDisplay;
    private JSONObject deviceAccess = new JSONObject();
    private int accessViewEpoch;
    private boolean managedUpdateStarting;
    private boolean modeChanging;
    private Button coolModeButton, iceModeButton;
    private String pendingMode;
    private boolean modeAwaitingConfirmation;
    private final Runnable expireModeConfirmation = () -> {
        if (!modeAwaitingConfirmation) return;
        pendingMode = null; modeAwaitingConfirmation = false;
        renderModeControls(null, null);
        showMessage("Chưa xác nhận trạng thái mới. Đang đọc lại thiết bị…");
        refreshDashboard();
    };
    private final Runnable expireModeControls = () -> { if (coolModeButton != null) coolModeButton.setEnabled(false); if (iceModeButton != null) iceModeButton.setEnabled(false); };

    private TextView firmwareInfo;
    private TextView firmwareFileInfo;
    private TextView deviceInfo;
    private JSONObject lastStatus;
    private JSONObject lastDebug;
    private int imageGeneration;
    private Uri selectedFirmwareUri;
    private int filePickerEpoch;
    private String selectedFirmwareName = "";
    private String uplinkSsid = "";
    private String uplinkIp = "";
    private String uplinkPasswordForPhone = "";
    private RuntimeConfigCodec.Config runtimeConfig = RuntimeConfigCodec.defaults();
    private boolean dashboardBusy;
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
        getWindow().setStatusBarColor(Design.BG);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        connectivityManager.bindProcessToNetwork(null);
        accounts = new CloudAccounts(this); deviceStore = new DeviceStore(this);
        JSONObject user = accounts.session();
        if (user == null) showAuth(false); else { acceptAccount(user); showDevices(); }
        watchInternet();
    }

    private void watchInternet() {
        internetCallback=new ConnectivityManager.NetworkCallback() {
            private void changed() {
                mainHandler.removeCallbacks(internetChanged);
                mainHandler.postDelayed(internetChanged,300);
            }
            @Override public void onAvailable(Network network) { changed(); }
            @Override public void onLost(Network network) { changed(); }
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps) { changed(); }
        };
        connectivityManager.registerDefaultNetworkCallback(internetCallback);
    }

    private void refreshAfterInternetChange() {
        if(isDestroyed())return;
        Network network=connectivityManager.getActiveNetwork();
        NetworkCapabilities caps=network==null?null:connectivityManager.getNetworkCapabilities(network);
        boolean available=caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if(java.util.Objects.equals(network,observedInternet) && available==observedInternetAvailable)return;
        observedInternet=network; observedInternetAvailable=available;
        if(!foreground)return;
        // Refresh reads only. Never replay a file transfer, reset or programming command.
        if(screen==Screen.HOME)restartHomeChecks();
        else if(screen==Screen.CONTROL && tab==Tab.OVERVIEW) {
            stopDashboard();
            mainHandler.post(dashboardRefresh);
        }
    }

    // Cloud account and multi-device home. The ESP MAC is the durable device identity.
    private CloudAccounts accounts;
    private DeviceStore deviceStore;
    private String accountId = "", username = "";
    private DeviceStore.Device activeDevice;
    private String pairingDeviceId = "", pairingHostname = "", legacyPairingId = "";
    private volatile int authEpoch, homeEpoch, controlEpoch;
    private boolean authBusy, registerMode, openingDevice;
    private EditText accountName, accountPassword, accountConfirm;
    private final ExecutorService presenceIo = Executors.newFixedThreadPool(3);
    private final Map<String, TextView> deviceLabels = new HashMap<>();
    private final java.util.Set<String> checkingDevices = new java.util.HashSet<>();
    private final Runnable homeRefresh = new Runnable() {
        @Override public void run() {
            if (screen != Screen.HOME || !foreground) return;
            refreshDevices(); mainHandler.postDelayed(this, 15000);
        }
    };

    private void showAuth(boolean register) {
        stopHome(); stopDashboard(); ++setupEpoch; ++authEpoch;
        screen = Screen.AUTH; setupBusy = false; authBusy = false; registerMode = register;
        lastUiMessage = "";
        LinearLayout content = createPage("", register ? "Tạo tài khoản" : "Đăng nhập", "");
        accountName = textInput("Tên đăng nhập", false); accountName.setSingleLine(true);
        accountName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        accountName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        content.addView(accountName, fullParams(0,0,0,8));
        accountPassword = accountSecret(content, "Mật khẩu");
        accountConfirm = register ? accountSecret(content, "Nhập lại mật khẩu") : null;
        Button submit = primaryButton(register ? "Tạo tài khoản" : "Đăng nhập");
        Button alternate = neutralButton(register ? "Đã có tài khoản? Đăng nhập" : "Tạo tài khoản mới");
        submit.setOnClickListener(v -> {
            if (authBusy) return;
            String name = accountName.getText().toString();
            char[] password = accountPassword.getText().toString().toCharArray();
            if (register && !java.util.Arrays.equals(password, accountConfirm.getText().toString().toCharArray())) {
                java.util.Arrays.fill(password, '\0'); accountConfirm.setError("Mật khẩu nhập lại chưa khớp."); return;
            }
            authBusy = true; final int token = ++authEpoch;
            submit.setEnabled(false); alternate.setEnabled(false); accountName.setEnabled(false);
            accountPassword.setEnabled(false); if (accountConfirm != null) accountConfirm.setEnabled(false);
            submit.setText(register ? "Đang tạo tài khoản…" : "Đang đăng nhập…");
            io.execute(() -> {
                try {
                    JSONObject user = register ? accounts.register(name,password) : accounts.login(name,password);
                    runOnUiThread(() -> {
                        if (token != authEpoch || isDestroyed()) return;
                        accountPassword.setText(""); if (accountConfirm != null) accountConfirm.setText("");
                        authBusy = false; acceptAccount(user); showDevices();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (token != authEpoch || isDestroyed()) return;
                        authBusy = false; submit.setEnabled(true); alternate.setEnabled(true); accountName.setEnabled(true);
                        accountPassword.setEnabled(true); if (accountConfirm != null) accountConfirm.setEnabled(true);
                        submit.setText(register ? "Tạo tài khoản" : "Đăng nhập"); showMessage(errorMessage(error));
                    });
                } finally { java.util.Arrays.fill(password, '\0'); }
            });
        });
        alternate.setOnClickListener(v -> { if (!authBusy) showAuth(!register); });
        content.addView(submit,fullParams(0,20,0,8)); content.addView(alternate);
        addHelp(content,"Tài khoản được lưu trên máy chủ. Mật khẩu tối thiểu 8 ký tự. Tài khoản cũ trên điện thoại cần đăng ký lại một lần.");
    }

    private EditText accountSecret(LinearLayout content, String hint) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(0xFFF0F4F7,dp(12)));
        EditText field = textInput(hint,true); field.setSaveEnabled(false);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        row.addView(field,new LinearLayout.LayoutParams(0,dp(56),1));
        android.widget.ImageButton eye = new android.widget.ImageButton(this);
        eye.setBackgroundColor(Color.TRANSPARENT); eye.setPadding(dp(14),dp(14),dp(14),dp(14));
        eye.setImageDrawable(new Design.Icon("eye",Design.TEAL)); eye.setContentDescription("Hiện " + hint.toLowerCase(Locale.ROOT));
        eye.setOnClickListener(v -> {
            if (authBusy) return;
            boolean hidden = field.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
            field.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            field.setSelection(field.length()); eye.setImageDrawable(new Design.Icon(hidden ? "eye-off" : "eye",Design.TEAL));
            eye.setContentDescription((hidden ? "Ẩn " : "Hiện ") + hint.toLowerCase(Locale.ROOT));
        });
        row.addView(eye,new LinearLayout.LayoutParams(dp(52),dp(52)));
        content.addView(row,fullParams(0,4,0,8)); return field;
    }

    private void acceptAccount(JSONObject user) {
        accountId = user.optString("id"); username = user.optString("username");

    }

    private void logout() {
        ++controlEpoch; ++setupEpoch; releaseRequestedNetwork();
        CloudApi previous = accounts.api(); accounts.logout();
        io.execute(() -> { try { previous.post("/auth/logout",new JSONObject()); } catch(Exception ignored) { } });
        accountId = ""; username = ""; activeDevice = null; clearDeviceState();
        showAuth(false);
    }

    private void clearDeviceState() {
        deviceAccess = new JSONObject(); managedUpdateStarting = false; modeChanging = false; pendingMode = null; modeAwaitingConfirmation = false; triggerLoaded=false; triggerSaving=false; mainHandler.removeCallbacks(expireModeConfirmation); ++accessViewEpoch;
        configLoaded = false; runtimeConfig = RuntimeConfigCodec.defaults();
        lastStatus = null; lastDebug = null; imageGeneration = 0;
        lastHistory = null; nextHistoryRead = 0; historyError = "";
        selectedFirmwareUri = null; selectedFirmwareName = "";
        configInputs.clear(); configChoices.clear(); configChecks.clear(); pinDependentViews.clear();
        firmwareInfo = null; firmwareFileInfo = null; deviceInfo = null;
        uplinkSsid = ""; uplinkIp = ""; uplinkPasswordForPhone = ""; pendingPassword = "";
    }

    private DeviceStore.Device findDevice(String id) {
        for (DeviceStore.Device d : deviceStore.list(accountId)) if (d.id.equals(id)) return d;
        return null;
    }

    private void showDevices() {
        ++controlEpoch; ++setupEpoch; stopDashboard(); stopHome();
        screen = Screen.HOME; setupBusy = false; openingDevice = false; activeDevice = null;
        clearDeviceState(); api.setExpectedDeviceId(""); api.setCloud(null,""); lastUiMessage = "";
        LinearLayout content = createPage("", "Xin chào, " + username,
                "");
        List<DeviceStore.Device> devices = deviceStore.list(accountId);
        LinearLayout hero = card(); hero.setBackground(new Design.Hero());
        TextView count = valueText(String.format(Locale.ROOT,"%02d",devices.size())); count.setTextSize(46); count.setTextColor(Color.WHITE);
        hero.addView(count);
        TextView caption = bodyText("THIẾT BỊ ĐÃ GHÉP NỐI"); caption.setTextColor(Design.MINT); caption.setLetterSpacing(.1f);
        hero.addView(caption,fullParams(0,5,0,0)); content.addView(hero,fullParams(0,0,0,20));
        Button add = primaryButton("+  Thêm thiết bị");
        add.setOnClickListener(v -> { legacyPairingId = ""; activeDevice = null; releaseRequestedNetwork(); showConnectDevice(false); });
        content.addView(add,fullParams(0,0,0,24));
        deviceLabels.clear();
        if (devices.isEmpty()) {
            TextView empty = valueText("Chưa có thiết bị"); empty.setTextSize(19); content.addView(empty);
            addHelp(content,"Nhấn Thêm thiết bị để kết nối bo đầu tiên.");
        } else {
            addSectionTitle(content,"Danh sách thiết bị");
            for (DeviceStore.Device device : devices) {
                LinearLayout card = card();
                TextView name = valueText(device.name); name.setTextSize(20); card.addView(name);
                TextView wifi = mutedText(device.ssid.isEmpty() ? device.id : device.ssid); card.addView(wifi,fullParams(0,5,0,10));
                TextView state = bodyText(device.legacy() ? "Cần ghép lại để nhận diện bo" : "Đang kiểm tra kết nối…");
                state.setTextColor(Design.MUTED); state.setTextSize(13); card.addView(state);
                deviceLabels.put(device.id,state);
                card.setContentDescription("Mở " + device.name); card.setOnClickListener(v -> openDevice(device));
                card.setOnLongClickListener(v -> { deviceOptions(device); return true; });
                content.addView(card,fullParams(0,12,0,0));
            }
            addHelp(content,"Chạm để mở · Giữ để đổi tên hoặc bỏ ghép nối.");
            addHelp(content,"Điều khiển qua Internet · Dùng được bằng Wi-Fi hoặc 4G/5G.");
        }
        mainHandler.post(homeRefresh);
    }

    private void deviceOptions(DeviceStore.Device device) {
        if (screen != Screen.HOME) return;
        final String owner = accountId;
        new AlertDialog.Builder(this).setTitle(device.name).setItems(new String[]{"Đổi tên", "Bỏ ghép nối"},(dialog,which) -> {
            if (!owner.equals(accountId) || screen != Screen.HOME) return;
            if (which == 0) {
                EditText name = textInput("Tên thiết bị",false); name.setText(device.name);
                name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
                AlertDialog edit = new AlertDialog.Builder(this).setTitle("Đổi tên thiết bị").setView(name)
                        .setNegativeButton("Hủy",null).setPositiveButton("Lưu",null).create();
                edit.setOnShowListener(d -> edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (!owner.equals(accountId) || screen != Screen.HOME) { edit.dismiss(); return; }
                    String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError("Nhập tên thiết bị."); return; }
                    DeviceStore.Device latest = findDevice(device.id); if (latest == null) { edit.dismiss(); return; }
                    edit.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    CloudApi cloud = accounts.api(); final int token = homeEpoch;
                    io.execute(() -> {
                        try { cloud.post("/devices/"+device.id+"/name",new JSONObject().put("name",value));
                            runOnUiThread(() -> { if(currentHome(token) && owner.equals(accountId)) { edit.dismiss(); refreshDevices(); } });
                        } catch(Exception error) { runOnUiThread(() -> {
                            if(currentHome(token)) { edit.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); showMessage(errorMessage(error)); }
                        }); }
                    });
                })); edit.show();
            } else new AlertDialog.Builder(this).setTitle("Bỏ ghép nối " + device.name + "?")
                    .setMessage("Thiết bị sẽ được gỡ khỏi tài khoản trên mọi điện thoại. Quyền điều khiển từ xa sẽ bị thu hồi.")
                    .setNegativeButton("Hủy",null).setPositiveButton("Bỏ ghép nối",(d,w) -> {
                        if (owner.equals(accountId) && screen == Screen.HOME) {
                            CloudApi cloud=accounts.api(); final int token=homeEpoch;
                            io.execute(() -> {
                                try { cloud.post("/devices/"+device.id+"/remove",new JSONObject());
                                    runOnUiThread(() -> { if(currentHome(token) && owner.equals(accountId)) refreshDevices(); });
                                } catch(Exception error) { runOnUiThread(() -> { if(currentHome(token)) showMessage(errorMessage(error)); }); }
                            });
                        }
                    }).show();
        }).show();
    }

    private void stopHome() { homeLoading=false; ++homeEpoch; checkingDevices.clear(); mainHandler.removeCallbacks(homeRefresh); }
    private void restartHomeChecks() {
        stopHome(); openingDevice = false;
        for (Map.Entry<String,TextView> item : deviceLabels.entrySet()) {
            if (!item.getKey().startsWith("legacy-")) { item.getValue().setText("Đang kiểm tra kết nối…"); item.getValue().setTextColor(Design.MUTED); }
        }
        mainHandler.post(homeRefresh);
    }
    private boolean currentHome(int token) { return token == homeEpoch && screen == Screen.HOME && foreground && !isDestroyed(); }
    private Network wifiNetwork() {
        if (activeNetwork != null) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return activeNetwork;
        }
        for (Network candidate : connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(candidate);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return candidate;
        }
        return null;
    }
    private EspApi deviceSession(DeviceStore.Device device, Network network) {
        EspApi session = new EspApi(); session.setExpectedDeviceId(device.id);
        session.setCloud(accounts.api(),device.id); return session;
    }
    private JSONObject resolveDevice(DeviceStore.Device device, EspApi session) throws Exception {
        return session.get("/api/status");
    }
    private DeviceStore.Device updateAddress(DeviceStore.Device device, JSONObject status) {
        JSONObject uplink = getUplink(status); String ip = uplink.optString("ip",device.ip);
        if (!isPrivateIpv4(ip)) ip = device.ip;
        DeviceStore.Device current = findDevice(device.id);
        if (current == null) return device;
        DeviceStore.Device updated = new DeviceStore.Device(device.id,current.name,ip,device.hostname,uplink.optString("ssid",device.ssid));
        if (!updated.ip.equals(current.ip) || !updated.ssid.equals(current.ssid)) deviceStore.save(accountId,updated);
        return updated;
    }
    private boolean homeLoading;
    private void refreshDevices() {
        if(homeLoading || screen != Screen.HOME) return;
        final int token=homeEpoch; final String owner=accountId;
        final CloudApi cloud=accounts.api(); homeLoading=true;
        presenceIo.execute(() -> {
            try {
                org.json.JSONArray rows=cloud.get("/devices").getJSONArray("devices");
                runOnUiThread(() -> {
                    if(!currentHome(token) || !owner.equals(accountId)) return;
                    homeLoading=false;
                    try {
                        if(deviceStore.replace(owner,rows)) { showDevices(); return; }
                        for(int i=0;i<rows.length();i++) {
                            JSONObject row=rows.getJSONObject(i); TextView label=deviceLabels.get(row.getString("id"));
                            if(label!=null) { boolean online=row.optBoolean("online");
                                label.setText(online ? "Đã kết nối Internet" : "Chưa kết nối Internet");
                                label.setTextColor(online ? Design.TEAL : Design.MUTED);
                            }
                        }
                    } catch(Exception error) { showMessage(errorMessage(error)); }
                });
            } catch(Exception error) { runOnUiThread(() -> {
                if(!currentHome(token)) return;
                homeLoading=false;
                if(error instanceof CloudApi.Unauthorized) { accounts.logout(); showAuth(false); }
                else for(TextView label:deviceLabels.values()) { label.setText("Chưa cập nhật được trạng thái"); label.setTextColor(Design.MUTED); }
                showMessage(errorMessage(error));
            }); }
        });
    }
    private void openDevice(DeviceStore.Device device) {
        if(screen != Screen.HOME || openingDevice) return;
        openingDevice=true; final int token=homeEpoch;
        final CloudApi cloud=accounts.api();
        showMessage("Đang mở " + device.name + "…"); EspApi session=deviceSession(device,null);
        presenceIo.execute(() -> {
            try {
                JSONObject access = cloud.get("/devices/" + device.id + "/access");
                JSONObject currentJob = access.optJSONObject("job");
                JSONObject status = currentJob != null && "running".equals(currentJob.optString("state"))
                        ? new JSONObject() : resolveDevice(device,session);
                runOnUiThread(() -> {
                    if(!currentHome(token)) return;
                    openingDevice=false; activeDevice=device; stopHome(); ++controlEpoch; clearDeviceState();
                    releaseRequestedNetwork(); api.setExpectedDeviceId(device.id); api.setCloud(cloud,device.id);
                    deviceAccess = access;
                    rememberStatus(status); lastUiMessage=""; showControl(currentJob != null && "running".equals(currentJob.optString("state")) ? Tab.FIRMWARE : Tab.OVERVIEW);
                });
            } catch(Exception error) { runOnUiThread(() -> {
                if(currentHome(token)) { openingDevice=false; showMessage(errorMessage(error)); }
            }); }
        });
    }
    private boolean currentControl(int token) { return token == controlEpoch && screen == Screen.CONTROL && !isDestroyed() && !isFinishing(); }
    private void onControl(int token, Runnable callback) { runOnUiThread(() -> { if (currentControl(token)) callback.run(); }); }


    // -------------------------------------------------------------------------
    // Provisioning flow
    // -------------------------------------------------------------------------

    // One action per page; epochs reject responses from an older page/session.
    private volatile int setupEpoch;
    private boolean setupBusy, localDeviceReady, manualConnection, changingWifi;
    private Button setupAction;
    private String setupActionLabel = "";
    private EditText setupPassword;
    private android.widget.ImageButton passwordEye;
    private LinearLayout setupResults;
    private JSONArray scannedNetworks;
    private String selectedSsid = "", selectedAuth = "";

    private void newSetupPage(Screen next) {
        ++setupEpoch;
        setupBusy = false;
        manualWifiVerificationQueued = false;
        setupAction = null; setupPassword = null; passwordEye = null; setupResults = null;
        screen = next; lastUiMessage = "";
        stopHome();
        stopDashboard();
    }

    private int beginSetup(String message) {
        if (setupBusy || isFinishing() || isDestroyed()) return -1;
        setupBusy = true;
        int token = ++setupEpoch;
        lastUiMessage = "";
        if (messageView != null) messageView.setVisibility(View.GONE);
        setSetupEnabled(false);
        if (setupAction != null) setupAction.setText(message);
        return token;
    }

    private boolean currentSetup(int token) {
        return token == setupEpoch && !isFinishing() && !isDestroyed();
    }

    private void setSetupEnabled(boolean enabled) {
        if (setupAction != null) { setupAction.setEnabled(enabled); setupAction.setAlpha(enabled ? 1f : .6f); }
        if (setupPassword != null) setupPassword.setEnabled(enabled);
        if (passwordEye != null) passwordEye.setEnabled(enabled);
        if (setupResults != null) for (int i = 0; i < setupResults.getChildCount(); i++) {
            View child = setupResults.getChildAt(i);
            child.setEnabled(enabled && !Boolean.FALSE.equals(child.getTag()));
        }
    }

    private void finishSetup(int token, String message) {
        if (!currentSetup(token)) return;
        setupBusy = false;
        manualWifiVerificationQueued = false;
        setSetupEnabled(true);
        if (setupAction != null) setupAction.setText(setupActionLabel);
        if (message != null && !message.isEmpty()) showMessage(message);
        else {
            lastUiMessage = "";
            if (messageView != null) messageView.setVisibility(View.GONE);
        }
    }

    private void addSetupAction(LinearLayout content, String label, Runnable action) {
        setupActionLabel = label; setupAction = primaryButton(label);
        setupAction.setOnClickListener(v -> { if (!setupBusy) action.run(); });
        content.addView(setupAction, fullParams(0, 20, 0, 0));
    }

    private void showConnectDevice(boolean changing) {
        ++controlEpoch;
        api.setExpectedDeviceId(changing && activeDevice != null ? activeDevice.id : "");
        pairingDeviceId = changing && activeDevice != null ? activeDevice.id : "";
        pairingHostname = pairingDeviceId.isEmpty() ? "" : pairingDeviceId + ".local";
        newSetupPage(Screen.CONNECT_DEVICE);
        changingWifi = changing; localDeviceReady = false; manualConnection = false;
        demoMode = false; api.setDemo(false); api.setCloud(null,""); configLoaded = false;
        lastStatus = null; lastDebug = null; imageGeneration = 0;
        uplinkSsid = ""; uplinkIp = ""; uplinkPasswordForPhone = "";
        selectedSsid = ""; selectedAuth = ""; scannedNetworks = null;
        LinearLayout content = createPage("GHÉP NỐI THIẾT BỊ", "Bắt đầu kết nối",
                "Bật nguồn và đặt thiết bị gần điện thoại.");
        LinearLayout visual = card();
        visual.setBackground(new Design.Hero()); visual.setGravity(Gravity.CENTER);
        visual.setPadding(dp(24), dp(48), dp(24), dp(48));
        visual.addView(icon("wifi", Design.MINT, 76), new LinearLayout.LayoutParams(dp(76), dp(76)));
        content.addView(visual, fullParams(0, 12, 0, 4));
        addSetupAction(content, "Tìm Wi-Fi thiết bị", this::startDeviceConnection);
    }

    private void startDeviceConnection() {
        if (screen != Screen.CONNECT_DEVICE || setupBusy) return;
        if (beginSetup("Đang tìm thiết bị…") < 0) return;
        api.setBaseUrl(EspApi.DEVICE_BASE_URL);
        if (manualConnection) openWifiSettings();
        else requestWifi(ESP_SSID, ESP_PASSWORD, ROLE_DEVICE, "");
    }

    private void showWifiSetup(JSONObject status) {
        if (!localDeviceReady) return;
        newSetupPage(Screen.CONFIGURE_WIFI); rememberStatus(status);
        LinearLayout content = createPage("CHỌN MẠNG", "Wi-Fi cho thiết bị",
                "Chọn mạng 2.4 GHz hoặc 5 GHz của bạn.");
        addSetupAction(content, "Quét Wi-Fi", this::scanNetworks);
        setupResults = new LinearLayout(this); setupResults.setOrientation(LinearLayout.VERTICAL);
        content.addView(setupResults, fullParams(0, 18, 0, 0));
        if (scannedNetworks != null) renderNetworks(scannedNetworks);
    }

    private void scanNetworks() {
        if (screen != Screen.CONFIGURE_WIFI || !localDeviceReady) return;
        int token = beginSetup("Đang quét…");
        if (token < 0) return;
        scannedNetworks = null; setupResults.removeAllViews();
        EspApi session = api.snapshot(20000);
        io.execute(() -> {
            try {
                JSONArray networks = session.get("/api/uplink/scan").optJSONArray("networks");
                runOnUiThread(() -> {
                    if (!currentSetup(token) || screen != Screen.CONFIGURE_WIFI) return;
                    scannedNetworks = networks == null ? new JSONArray() : networks;
                    finishSetup(token, ""); renderNetworks(scannedNetworks);
                });
            } catch (Exception error) {
                runOnUiThread(() -> finishSetup(token, "Chưa quét được Wi-Fi. " + errorMessage(error)));
            }
        });
    }

    private void renderNetworks(JSONArray networks) {
        setupResults.removeAllViews();
        int count = 0;
        for (int i = 0; i < networks.length(); i++) {
            JSONObject network = networks.optJSONObject(i);
            if (network == null) continue;
            final String ssid = network.optString("ssid", ""), auth = network.optString("auth", "WPA2");
            if (ssid.isEmpty() || ssid.startsWith(ESP_SSID)) continue;
            int channel = network.optInt("channel", 0);
            String band = channel > 14 ? "5 GHz" : channel > 0 ? "2.4 GHz" : "Wi-Fi";
            boolean supported = !"WEP".equals(auth) && !auth.toUpperCase(Locale.US).contains("ENTERPRISE");
            Button option = neutralButton(ssid + "\n" + band + (supported ? "" : " · Chưa hỗ trợ bảo mật này"));
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setTag(supported); option.setEnabled(supported);
            option.setOnClickListener(v -> {
                if (screen == Screen.CONFIGURE_WIFI && !setupBusy && localDeviceReady) showWifiPassword(ssid, auth);
            });
            setupResults.addView(option, fullParams(0, 0, 0, 8)); count++;
        }
        if (count == 0) addHelp(setupResults, "Chưa tìm thấy mạng. Đặt thiết bị gần router rồi quét lại.");
    }

    private boolean isOpenNetwork(String auth) {
        return "Mo".equalsIgnoreCase(auth) || "open".equalsIgnoreCase(auth);
    }

    private void showWifiPassword(String ssid, String auth) {
        if (!localDeviceReady) return;
        newSetupPage(Screen.WIFI_PASSWORD); selectedSsid = ssid; selectedAuth = auth;
        LinearLayout content = createPage("KẾT NỐI WI-FI", ssid,
                isOpenNetwork(auth) ? "Mạng này không yêu cầu mật khẩu." : "Nhập mật khẩu Wi-Fi để tiếp tục.");
        if (!isOpenNetwork(auth)) addPasswordField(content, "Mật khẩu Wi-Fi");
        addSetupAction(content, "Kết nối", this::connectUplink);
    }

    private void addPasswordField(LinearLayout content, String hint) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(0xFFF0F4F7, dp(12)));
        setupPassword = textInput(hint, true); setupPassword.setSaveEnabled(false);
        setupPassword.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        setupPassword.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (!setupBusy && setupAction != null) setupAction.performClick();
                return true;
            }
            return false;
        });
        row.addView(setupPassword, new LinearLayout.LayoutParams(0, dp(56), 1));
        passwordEye = new android.widget.ImageButton(this);
        passwordEye.setImageDrawable(new Design.Icon("eye", Design.TEAL));
        passwordEye.setContentDescription("Hiện mật khẩu"); passwordEye.setBackgroundColor(Color.TRANSPARENT);
        passwordEye.setPadding(dp(14), dp(14), dp(14), dp(14));
        passwordEye.setOnClickListener(v -> {
            if (setupBusy || setupPassword == null) return;
            int position = setupPassword.getSelectionStart();
            boolean hidden = setupPassword.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
            setupPassword.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            setupPassword.setSelection(Math.max(0, position));
            passwordEye.setImageDrawable(new Design.Icon(hidden ? "eye-off" : "eye", Design.TEAL));
            passwordEye.setContentDescription(hidden ? "Ẩn mật khẩu" : "Hiện mật khẩu");
        });
        row.addView(passwordEye, new LinearLayout.LayoutParams(dp(52), dp(52)));
        content.addView(row, fullParams(0, 12, 0, 0));
    }

    private void connectUplink() {
        if (screen != Screen.WIFI_PASSWORD || !localDeviceReady || setupBusy) return;
        final String ssid = selectedSsid, password = setupPassword == null ? "" : setupPassword.getText().toString();
        int length = utf8Length(password);
        if (!isOpenNetwork(selectedAuth) && (length < 8 || length > 63)) {
            setupPassword.setError("Kiểm tra lại độ dài mật khẩu Wi-Fi."); return;
        }
        int token = beginSetup("Đang kết nối…");
        if (token < 0) return;
        if (setupPassword != null) {
            setupPassword.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            passwordEye.setImageDrawable(new Design.Icon("eye", Design.TEAL));
            passwordEye.setContentDescription("Hiện mật khẩu");
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(setupPassword.getWindowToken(), 0);
        }
        uplinkSsid = ssid; uplinkPasswordForPhone = password; uplinkIp = "";
        EspApi session = api.snapshot(6000);
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "connect"); body.put("ssid", ssid); body.put("password", password);
                session.post("/api/uplink", body);
                waitForUplink(session, token, ssid);
            } catch (EspApi.ApiException rejected) {
                runOnUiThread(() -> finishSetup(token, errorMessage(rejected)));
            } catch (Exception uncertain) {
                // Band/channel may change after accepting credentials, before the reply arrives.
                // Never resend the write blindly: read status, then verify on the target LAN.
                waitForUplink(session, token, ssid);
            }
        });
    }

    private void waitForUplink(EspApi session, int token, String ssid) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        int transportErrors = 0;
        while (token == setupEpoch && android.os.SystemClock.elapsedRealtime() < deadline
                && !Thread.currentThread().isInterrupted()) {
            try {
                JSONObject status = session.get("/api/status"), uplink = getUplink(status);
                String state = uplink.optString("state", "");
                if (ssid.equals(uplink.optString("ssid")) && "connected".equals(state)
                        && isPrivateIpv4(uplink.optString("ip"))) {
                    runOnUiThread(() -> {
                        if (!currentSetup(token)) return;
                        rememberStatus(status); showSwitchPhone();
                    }); return;
                }
                if (ssid.equals(uplink.optString("ssid")) && "failed".equals(state)) {
                    runOnUiThread(() -> finishSetup(token, "Chưa kết nối được. Kiểm tra mật khẩu và thử lại.")); return;
                }
                transportErrors = 0;
            } catch (Exception error) {
                if (++transportErrors >= 2) {
                    runOnUiThread(() -> { if (currentSetup(token)) showSwitchPhone(); }); return;
                }
            }
            try { Thread.sleep(800); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
        runOnUiThread(() -> finishSetup(token, "Thiết bị chưa kết nối được. Kiểm tra mật khẩu rồi thử lại."));
    }

    private void showSwitchPhone() {
        newSetupPage(Screen.SWITCH_PHONE); manualConnection = false;
        LinearLayout content = createPage("HOÀN TẤT GHÉP NỐI", "Kết nối điện thoại",
                "Cho điện thoại vào " + uplinkSsid + " để tiếp tục.");
        if (uplinkPasswordForPhone.isEmpty() && !isOpenNetwork(selectedAuth)) addPasswordField(content, "Mật khẩu Wi-Fi");
        addSetupAction(content, "Tiếp tục", () -> {
            String password = setupPassword == null ? uplinkPasswordForPhone : setupPassword.getText().toString();
            if (!isOpenNetwork(selectedAuth) && (utf8Length(password) < 8 || utf8Length(password) > 63)) {
                if (setupPassword != null) setupPassword.setError("Kiểm tra lại mật khẩu Wi-Fi."); return;
            }
            if (beginSetup("Đang kết nối…") < 0) return;
            if (manualConnection) openWifiSettings();
            else requestWifi(uplinkSsid, password, ROLE_PHONE, uplinkIp);
        });
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 29) return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    @android.annotation.TargetApi(29)
    private NetworkRequest makeWifiRequest(String ssid, String password, String auth) {
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder();
        if (ESP_SSID.equals(ssid)) specifier.setSsidPattern(new android.os.PatternMatcher(ESP_SSID + "-", android.os.PatternMatcher.PATTERN_PREFIX));
        else specifier.setSsid(ssid);
        if (!password.isEmpty()) {
            if ("WPA3".equals(auth)) specifier.setWpa3Passphrase(password);
            else specifier.setWpa2Passphrase(password);
        }
        // C5's AP and target can both use 5 GHz. Specifier connections are local-only.
        return new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier.build()).build();
    }

    private void requestWifi(String ssid, String password, int role, String stationIp) {
        pendingRole = role; pendingSsid = ssid; pendingPassword = password; pendingStationIp = stationIp;
        if(role==ROLE_PHONE) {
            // The household network must remain a normal Internet network. A Wi-Fi
            // specifier creates a local-only connection and can hide Internet/DNS.
            releaseRequestedNetwork();
            Network home=internetWifiNetwork();
            if(home!=null) { activeNetwork=home; api.setNetwork(home); verifyPhoneOnLan(); }
            else openWifiSettings();
            return;
        }
        if (Build.VERSION.SDK_INT < 29) { openWifiSettings(); return; }
        if (!hasWifiPermission()) {
            String[] permissions = Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
                    : Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(permissions, WIFI_PERMISSION_REQUEST); return;
        }
        releaseRequestedNetwork();
        int networkToken = ++requestToken;
        final int operationToken = setupEpoch;
        try {
            NetworkRequest request = makeWifiRequest(ssid, password, role == ROLE_DEVICE ? "WPA2" : selectedAuth);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    runOnUiThread(() -> onWifiAvailable(networkToken, role, network));
                }
                @Override public void onUnavailable() {
                    runOnUiThread(() -> {
                        if (networkToken != requestToken || !currentSetup(operationToken)) return;
                        manualConnection = true;
                        finishSetup(operationToken, "Chưa kết nối được Wi-Fi. Bấm lại để chọn mạng trong cài đặt điện thoại.");
                    });
                }
                @Override public void onLost(Network network) {
                    runOnUiThread(() -> onWifiLost(networkToken, network));
                }
            };
            connectivityManager.requestNetwork(request, networkCallback, mainHandler, 30000);
        } catch (SecurityException | IllegalArgumentException error) {
            manualConnection = true;
            finishSetup(operationToken, "Bấm lại để chọn Wi-Fi trong cài đặt điện thoại.");
        }
    }

    private void onWifiAvailable(int token, int role, Network network) {
        if (token != requestToken || isDestroyed() || isFinishing()) return;
        if (role == ROLE_DEVICE && screen != Screen.CONNECT_DEVICE) return;
        if (role == ROLE_PHONE && screen != Screen.SWITCH_PHONE) return;
        if (activeNetwork != null && activeNetwork.equals(network)) return;
        activeNetwork = network; api.setNetwork(network);
        if (role == ROLE_DEVICE) { api.setBaseUrl(EspApi.DEVICE_BASE_URL); verifyDeviceConnection(); }
        else verifyPhoneOnLan();
    }

    private void onWifiLost(int token, Network network) {
        if (token != requestToken || activeNetwork == null || !activeNetwork.equals(network)) return;
        activeNetwork = null; api.setNetwork(null); connectivityManager.bindProcessToNetwork(null);
        if (screen == Screen.WIFI_PASSWORD && setupBusy) return; // Bounded status check owns C5 band handoff recovery.
        if (screen == Screen.HOME) { restartHomeChecks(); return; }
        if (screen == Screen.AUTH) return;
        if (screen == Screen.CONTROL) showMessage("Đang chuyển kết nối Internet…");
        else if (screen == Screen.SWITCH_PHONE) {
            ++setupEpoch; finishSetup(setupEpoch, "Mất kết nối. Bấm Tiếp tục để thử lại.");
        } else {
            releaseRequestedNetwork(); showConnectDevice(changingWifi);
            showMessage("Mất kết nối với thiết bị. Hãy tìm lại Wi-Fi thiết bị.");
        }
    }

    private void verifyDeviceConnection() {
        int token = setupEpoch;
        EspApi session = api.snapshot(6000);
        io.execute(() -> {
            try {
                JSONObject status = session.get("/api/status");
                String identity = DeviceStore.identity(status);
                if (identity.isEmpty()) throw new IOException("Thiết bị cần được cập nhật trước khi ghép nối.");
                if(status.optJSONObject("cloud")==null || status.getJSONObject("cloud").optInt("protocol")!=1)
                    throw new IOException("Thiết bị cần được cập nhật để kết nối với tài khoản. Hãy liên hệ người quản lý.");
                runOnUiThread(() -> {
                    if (!currentSetup(token) || screen != Screen.CONNECT_DEVICE) return;
                    pairingDeviceId = identity; pairingHostname = DeviceStore.hostname(status);
                    api.setExpectedDeviceId(identity);
                    localDeviceReady = true; showWifiSetup(status);
                });
            } catch (Exception error) {
                runOnUiThread(() -> finishSetup(token, "Chưa kết nối được thiết bị. " + errorMessage(error)));
            }
        });
    }

    private void verifyPhoneOnLan() {
        final int token=setupEpoch;
        final String expectedSsid=uplinkSsid, knownIp=uplinkIp, device=pairingDeviceId, owner=accountId;
        final CloudApi cloud=accounts.api(); final EspApi source=api.snapshot(6000);
        final Network selectedNetwork=activeNetwork;
        final List<String> neighbors=LocalDeviceDiscovery.neighbors(selectedNetwork==null?null:connectivityManager.getLinkProperties(selectedNetwork));
        io.execute(() -> {
            try {
                LocalDeviceDiscovery.Found found=LocalDeviceDiscovery.find(this,selectedNetwork,source,device,expectedSsid,knownIp,neighbors,
                        () -> token==setupEpoch && !Thread.currentThread().isInterrupted(),35000);
                if(token!=setupEpoch) return;
                if(found==null) {
                    runOnUiThread(() -> { manualConnection=true; finishSetup(token,"Chưa liên lạc được với thiết bị. Bấm Tiếp tục để chọn lại "+expectedSsid+"; tránh mạng khách có chặn thiết bị nội bộ."); });
                    return;
                }
                EspApi session=found.session;
                if(token!=setupEpoch) return;
                runOnUiThread(() -> { if(currentSetup(token)) showMessage("Đang ghép thiết bị với tài khoản…"); });
                JSONObject claim=cloud.post("/pair/start",new JSONObject().put("device",device));
                if(!claim.optBoolean("paired")) {
                    if(token!=setupEpoch) return;
                    session.post("/api/cloud/pair",claim.getJSONObject("config"));
                    boolean paired=false;
                    for(int attempt=0;attempt<40 && token==setupEpoch;attempt++) {
                        JSONObject progress=cloud.get("/pair/"+claim.getString("claim_id"));
                        if(progress.optBoolean("paired")) { paired=true; break; }
                        if(progress.optBoolean("expired")) break;
                        Thread.sleep(1500);
                    }
                    if(!paired) throw new IOException("Thiết bị đang kết nối máy chủ. Chờ một chút rồi bấm Tiếp tục để kiểm tra lại.");
                }
                org.json.JSONArray rows=cloud.get("/devices").getJSONArray("devices");
                runOnUiThread(() -> {
                    if(!currentSetup(token) || !owner.equals(accountId)) return;
                    try { deviceStore.replace(owner,rows); } catch(Exception error) { finishSetup(token,errorMessage(error)); return; }
                    pendingPassword=""; uplinkPasswordForPhone=""; if(setupPassword!=null) setupPassword.setText("");
                    releaseRequestedNetwork(); showDevices();
                });
            } catch(Exception error) { runOnUiThread(() -> finishSetup(token,errorMessage(error))); }
        });
    }

    private void releaseRequestedNetwork() {
        requestToken++;
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (IllegalArgumentException ignored) { }
            networkCallback = null;
        }
        activeNetwork = null; api.setNetwork(null); connectivityManager.bindProcessToNetwork(null);
    }

    private Network internetWifiNetwork() {
        Network primary=connectivityManager.getActiveNetwork();
        if(primary!=null) {
            NetworkCapabilities caps=connectivityManager.getNetworkCapabilities(primary);
            if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return primary;
        }
        for(Network network:connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps=connectivityManager.getNetworkCapabilities(network);
            if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return network;
        }
        return null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WIFI_PERMISSION_REQUEST || !setupBusy
                || (screen != Screen.CONNECT_DEVICE && screen != Screen.SWITCH_PHONE)) return;
        if (!hasWifiPermission()) {
            manualConnection = true;
            finishSetup(setupEpoch, "Chưa có quyền Wi-Fi. Bấm lại để kết nối trong cài đặt điện thoại."); return;
        }
        requestWifi(pendingSsid, pendingPassword, pendingRole, pendingStationIp);
    }

    private void openWifiSettings() {
        if (screen != Screen.CONNECT_DEVICE && screen != Screen.SWITCH_PHONE) return;
        releaseRequestedNetwork(); waitingForWifiSettingsReturn = true;
        showMessage("Chọn " + (screen == Screen.CONNECT_DEVICE ? ESP_SSID : uplinkSsid) + " rồi quay lại app.");
        try {
            Intent intent = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS);
            startActivityForResult(intent, WIFI_SETTINGS_REQUEST);
        } catch (ActivityNotFoundException error) {
            waitingForWifiSettingsReturn = false; finishSetup(setupEpoch, "Không mở được cài đặt Wi-Fi trên điện thoại này.");
        }
    }

    private void verifyManualWifiAfterSettings() {
        if (manualWifiVerificationQueued || !setupBusy) return;
        if (screen != Screen.CONNECT_DEVICE && screen != Screen.SWITCH_PHONE) return;
        manualWifiVerificationQueued = true; waitingForWifiSettingsReturn = false;
        Network wifi = screen==Screen.SWITCH_PHONE?internetWifiNetwork():null;
        for (Network candidate : connectivityManager.getAllNetworks()) {
            if(wifi!=null || screen==Screen.SWITCH_PHONE)break;
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(candidate);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { wifi = candidate; break; }
        }
        if (wifi == null) {
            manualWifiVerificationQueued = false; finishSetup(setupEpoch, "Chưa kết nối Wi-Fi. Bấm lại để tiếp tục."); return;
        }
        activeNetwork = wifi; api.setNetwork(wifi);
        if (screen == Screen.CONNECT_DEVICE) { api.setBaseUrl(EspApi.DEVICE_BASE_URL); verifyDeviceConnection(); }
        else verifyPhoneOnLan();
    }



    // -------------------------------------------------------------------------
    // Native control tabs
    // -------------------------------------------------------------------------

    private void showControl(Tab selectedTab) {
        stopDashboard();
        ++accessViewEpoch;
        screen = Screen.CONTROL;
        tab = selectedTab;
        String title = tab == Tab.OVERVIEW ? "Tổng quan" : tab == Tab.SETTINGS ? "Cài đặt vận hành"
                : tab == Tab.FIRMWARE ? "Cập nhật" : "Thiết bị";
        LinearLayout content = createPage("", title,
                tab == Tab.OVERVIEW ? "Theo dõi bộ điều khiển"
                : tab == Tab.SETTINGS ? ""
                : tab == Tab.FIRMWARE ? "Cập nhật phiên bản mới cho thiết bị."
                : "Thông tin kết nối và bộ điều khiển.");
        TextView deviceName = mutedText(activeDevice == null ? "" : activeDevice.name);
        deviceName.setTextColor(Design.TEAL); content.addView(deviceName, fullParams(0,0,0,12));
        addNavigation(content);
        switch (tab) {
            case OVERVIEW:
                buildOverview(content);
                mainHandler.post(dashboardRefresh);
                break;
            case SETTINGS:
                loadManagedTab(content, Tab.SETTINGS);
                break;
            case FIRMWARE:
                loadManagedTab(content, Tab.FIRMWARE);
                break;
            case DEVICE:
                buildDevice(content);
                break;
        }
    }

    private void addNavigation(LinearLayout content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackgroundColor(Color.WHITE);
        row.setElevation(dp(12));
        addNavButton(row, Tab.OVERVIEW, "Tổng quan", "home");
        addNavButton(row, Tab.SETTINGS, "Cài đặt", "settings");
        addNavButton(row, Tab.FIRMWARE, "Cập nhật", "upload");
        addNavButton(row, Tab.DEVICE, "Thiết bị", "chip");
        pageRoot.addView(row, fullParams(0, 0, 0, 0));
    }

    private void addNavButton(LinearLayout row, Tab target, String label, String symbol) {
        boolean selected = target == tab;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(8), dp(2), dp(7));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1800776C),
                rounded(selected ? 0xFFE7F5F1 : Color.WHITE, dp(16)), null));
        item.addView(icon(symbol, selected ? Design.TEAL : Design.MUTED, 22),
                new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView text = mutedText(label); text.setTextSize(11); text.setGravity(Gravity.CENTER);
        text.setTextColor(selected ? Design.TEAL : Design.MUTED);
        if (selected) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.addView(text, fullParams(0, 5, 0, 0));
        item.setContentDescription(label); item.setOnClickListener(v -> showControl(target));
        item.setSelected(selected);
        row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void addQuickAction(LinearLayout row, String title, String symbol, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        ImageView image = icon(symbol, Design.TEAL, 23);
        image.setPadding(dp(14), dp(14), dp(14), dp(14));
        image.setBackground(rounded(0xFFE7F3EF, dp(18)));
        item.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = mutedText(title); label.setGravity(Gravity.CENTER); label.setTextColor(Design.INK);
        item.addView(label, fullParams(0, 8, 0, 0));
        item.setPadding(0, dp(6), 0, dp(6)); item.setOnClickListener(v -> action.run());
        item.setContentDescription(title);
        row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private Button modeAction(LinearLayout row, String title, Runnable action) {
        Button button = neutralButton(title);
        button.setTextSize(13); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(14),0,dp(14),0); button.setMaxLines(2); button.setHorizontallyScrolling(false);
        button.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(64),1);
        if (row.getChildCount() > 0) params.setMargins(dp(12),0,0,0);
        row.addView(button,params); button.setOnClickListener(v -> action.run());
        return button;
    }

    private void paintModeButton(Button button, boolean cool, boolean selected, boolean pending) {
        String title = cool ? "Làm lạnh" : "Xả đá";
        button.setText(title + (pending ? (modeChanging ? "\nĐang gửi…" : "\nĐang xác nhận…") : ""));
        int accent = 0xFF176B9C;
        int ink = selected ? Color.WHITE : (button.isEnabled() ? accent : Design.MUTED);
        button.setSelected(selected);
        button.setTextColor(ink);
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? accent : 0xFFEDF3F6));
        Design.Icon symbol = new Design.Icon(cool ? "snowflake" : "ice-cube", ink);
        symbol.setBounds(0,0,dp(22),dp(22));
        button.setCompoundDrawables(symbol,null,null,null);
    }

    private void beginModeChange(String mode) {
        modeChanging = true; pendingMode = mode; modeAwaitingConfirmation = false;
        mainHandler.removeCallbacks(expireModeConfirmation);
        // Paint on the UI thread before the command is queued for network I/O.
        renderModeControls(lastStatus, lastDebug);
        renderTrigger(lastStatus, lastDebug);
    }

    private void selectMode(String mode) {
        if (triggerSaving || modeChanging || pendingMode != null || managedUpdateStarting || !foreground || screen != Screen.CONTROL || tab != Tab.OVERVIEW) return;
        Button chosen = "COOL".equals(mode) ? coolModeButton : iceModeButton;
        if (chosen == null || !chosen.isEnabled()) return;
        beginModeChange(mode);
        final int token = controlEpoch;
        final EspApi session = api.snapshot(15000);
        showMessage("Đang chuyển chế độ…");
        io.execute(() -> {
            if (!currentControl(token)) return;
            try {
                session.post("/api/mode", new JSONObject().put("mode",mode));
                // Refresh from hardware rather than assuming a late command still owns the mode.
                onControl(token, () -> finishModeChange(true, "Đã gửi lệnh chuyển chế độ."));
            } catch (Exception error) {
                onControl(token, () -> finishModeChange(false, "Chưa xác nhận chuyển chế độ. " + errorMessage(error)));
            }
        });
    }

    private void finishModeChange(boolean accepted, String message) {
        modeChanging = false;
        modeAwaitingConfirmation = accepted;
        if (!accepted) pendingMode = null;
        mainHandler.removeCallbacks(expireModeConfirmation);
        renderModeControls(lastStatus, lastDebug);
        if (modeAwaitingConfirmation) mainHandler.postDelayed(expireModeConfirmation,5000);
        showMessage(message); refreshDashboard();
    }

    private void renderModeControls(JSONObject status, JSONObject debug) {
        if (coolModeButton == null || iceModeButton == null) return;
        String selected = null, source = null, running = null;
        boolean fresh = debug != null && debug.optBoolean("enabled") && debug.optLong("last_rx_age_ms",-1) >= 0 && debug.optLong("last_rx_age_ms",-1) <= 3000;
        JSONArray vars = debug == null ? null : debug.optJSONArray("variables");
        if (vars != null) for (int i=0;i<vars.length();i++) {
            JSONObject v=vars.optJSONObject(i);
            if(v==null || v.optLong("age_ms",-1)<0 || v.optLong("age_ms",-1)>3000) continue;
            if("selected_mode".equals(v.optString("name"))) selected=v.optString("value");
            if("mode_source".equals(v.optString("name"))) source=v.optString("value");
            if("mode".equals(v.optString("name"))) running=v.optString("value");
        }
        if (modeAwaitingConfirmation && fresh &&
            (("COOL".equals(pendingMode) && "0".equals(selected)) ||
             ("ICE_FLUSH".equals(pendingMode) && "1".equals(selected)))) {
            pendingMode = null; modeAwaitingConfirmation = false;
            mainHandler.removeCallbacks(expireModeConfirmation);
        }
        boolean enabled = fresh && selected != null && ("APP".equals(source) || "PHYSICAL".equals(source)) &&
            ("COOL".equals(running) || "ICE_FLUSH".equals(running) || "IDLE".equals(running)) &&
            status != null && !status.optBoolean("busy",true) && !modeChanging && pendingMode == null && !triggerSaving && !managedUpdateStarting;
        coolModeButton.setEnabled(enabled); iceModeButton.setEnabled(enabled);
        String displayed = pendingMode != null ? pendingMode : fresh ? ("0".equals(selected) ? "COOL" : "1".equals(selected) ? "ICE_FLUSH" : null) : null;
        paintModeButton(coolModeButton,true,"COOL".equals(displayed),"COOL".equals(pendingMode));
        paintModeButton(iceModeButton,false,"ICE_FLUSH".equals(displayed),"ICE_FLUSH".equals(pendingMode));
        mainHandler.removeCallbacks(expireModeControls);
        if(enabled) mainHandler.postDelayed(expireModeControls,5000);
    }

    private void buildOverview(LinearLayout content) {
        LinearLayout hero = card(); hero.setBackground(new Design.Hero());
        hero.setPadding(dp(22), dp(22), dp(22), dp(22));
        TextView caption = mutedText("BỘ ĐIỀU KHIỂN");
        caption.setTextColor(0xFFB9D0DD); caption.setLetterSpacing(.10f); hero.addView(caption);
        dashboardMode = valueText("Đang chờ dữ liệu"); dashboardMode.setTextSize(29);
        dashboardMode.setTextColor(Color.WHITE); hero.addView(dashboardMode, fullParams(0, 15, 0, 7));
        TextView detail = bodyText("Chế độ vận hành hiện tại"); detail.setTextColor(0xFFB9D0DD);
        hero.addView(detail, fullParams(0, 0, 0, 22));
        View line = new View(this); line.setBackgroundColor(0x30FFFFFF);
        hero.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        dashboardConnection = bodyText("Đang đọc trạng thái…");
        dashboardConnection.setTextSize(12); dashboardConnection.setTextColor(Design.MINT);
        hero.addView(dashboardConnection, fullParams(0, 14, 0, 0));
        content.addView(hero, fullParams(0, 0, 0, 18));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setBaselineAligned(false);
        coolModeButton = modeAction(actions, "Làm lạnh", () -> selectMode("COOL"));
        iceModeButton = modeAction(actions, "Xả đá", () -> selectMode("ICE_FLUSH"));
        coolModeButton.setEnabled(false); iceModeButton.setEnabled(false);
        content.addView(actions, fullParams(0, 0, 0, 20));

        addSectionTitle(content, "Thời gian hiển thị");
        LinearLayout timers = new LinearLayout(this); timers.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout green = timerCard("green", 0xFF008978);
        LinearLayout red = timerCard("red", 0xFFCF5A68);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMargins(0,0,dp(6),0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.setMargins(dp(6),0,0,0);
        timers.addView(green,left); timers.addView(red,right);
        content.addView(timers, fullParams(0, 6, 0, 20));

        addSectionTitle(content, "Nhiệt độ");
        LinearLayout temperatures = new LinearLayout(this);
        String[] sensorLabels = {"10k","20k","50k"};
        for(int i=0;i<3;i++) {
            LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(0,-1,1);
            if(i>0) param.setMargins(dp(8),0,0,0);
            temperatures.addView(temperatureCard(sensorLabels[i],i),param);
        }
        content.addView(temperatures, fullParams(0,6,0,16));
        buildTriggerCard(content);

        LinearLayout relayCard = card(); addSectionTitle(relayCard, "Trạng thái rơ-le");
        LinearLayout relays = new LinearLayout(this); relays.setOrientation(LinearLayout.HORIZONTAL);
        for (int i=0;i<3;i++) {
            TextView relay = valueText("RL" + (i+1) + "\n—");
            relay.setTextSize(13); relay.setGravity(Gravity.CENTER);
            relay.setLineSpacing(dp(7),1f); relay.setPadding(dp(4),dp(16),dp(4),dp(16));
            relayViews[i]=relay;
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,-2,1);
            rp.setMargins(i==0?0:dp(4),0,i==2?0:dp(4),0); relays.addView(relay,rp);
        }
        relayCard.addView(relays, fullParams(0, 10, 0, 0));
        content.addView(relayCard, fullParams(0, 0, 0, 16));
        LinearLayout logs = card(); addSectionTitle(logs,"Nhật ký thiết bị");
        addHelp(logs, "Thời gian đã lưu · Mới nhất ở L00");
        dashboardLogs=monospaceText("Đang chờ dữ liệu…");
        dashboardLogs.setLineSpacing(dp(10), 1f);
        logs.addView(dashboardLogs,fullParams(0,8,0,0));
        content.addView(logs);
        renderDashboard(lastStatus,lastDebug);
        renderTemperature(null); // wait for a fresh response, not cached device state
        stopCounting();
        renderHistory();
    }

    private void buildTriggerCard(LinearLayout content) {
        triggerLoaded=false;
        LinearLayout card=card(); addSectionTitle(card,"Điều kiện kích");
        card.addView(mutedText("Nguồn tín hiệu"));
        triggerSource=choiceSpinner(choices("10k",1,"20k",2,"50k",3,"SIG",0));
        card.addView(triggerSource,fullParams(0,6,0,12));
        card.addView(mutedText("Ngưỡng nhiệt độ (°C)"));
        triggerThreshold=textInput("Ví dụ: 25",false);
        triggerThreshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        card.addView(triggerThreshold,fullParams(0,6,0,8));
        triggerState=mutedText("Đang chờ dữ liệu…"); card.addView(triggerState,fullParams(0,0,0,12));
        triggerSave=primaryButton("Lưu");triggerSave.setEnabled(false);
        triggerSave.setOnClickListener(v -> saveTrigger());card.addView(triggerSave);
        triggerSource.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,int position,long id) {
                triggerThreshold.setEnabled(!triggerSaving && ((Choice)triggerSource.getSelectedItem()).value!=0);
            }
        });
        content.addView(card,fullParams(0,0,0,16));
    }

    private void renderTrigger(JSONObject status, JSONObject debug) {
        if(triggerSave==null) return;
        JSONObject trigger=status==null ? null : status.optJSONObject("trigger");
        boolean supported=trigger!=null && trigger.optBoolean("enabled");
        if(supported && !triggerLoaded && !triggerSaving) {
            String source=trigger.optString("source","SIG");
            int choice=0; for(int i=0;i<TRIGGER_SOURCES.length;i++) if(TRIGGER_SOURCES[i].equals(source)) choice=i;
            selectChoice(triggerSource,choice);
            triggerThreshold.setText(java.math.BigDecimal.valueOf(trigger.optInt("threshold_centi",2500),2).stripTrailingZeros().toPlainString());
            triggerLoaded=true;
        }
        triggerSource.setEnabled(supported && !triggerSaving);
        triggerThreshold.setEnabled(supported && !triggerSaving && ((Choice)triggerSource.getSelectedItem()).value!=0);
        triggerSave.setEnabled(supported && !triggerSaving && !modeChanging && pendingMode==null && !managedUpdateStarting && !status.optBoolean("busy",true));
        mainHandler.removeCallbacks(expireTriggerControls);
        if(triggerSave.isEnabled()) mainHandler.postDelayed(expireTriggerControls,5000);
        if(triggerSaving) triggerState.setText("Đang lưu…");
        else if(!supported) triggerState.setText("Đang chờ dữ liệu hoặc cần cập nhật thiết bị.");
        else if(!trigger.optBoolean("synced")) triggerState.setText("Đang chờ bộ điều khiển xác nhận.");
        else if("SIG".equals(trigger.optString("source"))) triggerState.setText("Đang dùng tín hiệu từ chân SIG.");
        else if(!trigger.optBoolean("sensor_valid")) triggerState.setText("Kiểm tra cảm biến đã chọn · Không kích.");
        else triggerState.setText(trigger.optBoolean("active") ? "Nhiệt độ vượt ngưỡng · Đang kích." : "Nhiệt độ chưa vượt ngưỡng · Không kích.");
    }

    private void saveTrigger() {
        if(triggerSaving || modeChanging || pendingMode!=null || managedUpdateStarting || !triggerLoaded || triggerSave==null || !triggerSave.isEnabled() || !foreground || screen!=Screen.CONTROL || tab!=Tab.OVERVIEW) return;
        int selected=((Choice)triggerSource.getSelectedItem()).value;
        int threshold=2500;
        try {
            if(selected!=0) threshold=new java.math.BigDecimal(triggerThreshold.getText().toString().trim().replace(',','.')).movePointRight(2).intValueExact();
            else if(lastStatus!=null && lastStatus.optJSONObject("trigger")!=null) threshold=lastStatus.getJSONObject("trigger").optInt("threshold_centi",2500);
            if(threshold < -5500 || threshold > 12500) throw new IllegalArgumentException();
        } catch(Exception invalid) { triggerThreshold.setError("Nhập từ -55 đến 125 °C, tối đa 2 số lẻ."); return; }
        final int value=threshold, token=controlEpoch;
        final EspApi session=api.snapshot(15000);
        triggerSaving=true; renderTrigger(lastStatus,lastDebug);renderModeControls(lastStatus,lastDebug);
        io.execute(() -> {
            if(!currentControl(token)) return;
            try {
                session.post("/api/trigger",new JSONObject().put("source",TRIGGER_SOURCES[selected]).put("threshold_centi",value));
                onControl(token,() -> { triggerSaving=false;triggerLoaded=false;showMessage("Đã lưu điều kiện kích.");refreshDashboard(); });
            } catch(Exception error) {
                onControl(token,() -> { triggerSaving=false;renderTrigger(lastStatus,lastDebug);renderModeControls(lastStatus,lastDebug);showMessage("Chưa xác nhận lưu. "+errorMessage(error));refreshDashboard(); });
            }
        });
    }

    private LinearLayout temperatureCard(String label, int sensorIndex) {
        LinearLayout box = card(); box.setPadding(dp(10),dp(16),dp(10),dp(16));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = mutedText(label); name.setTextSize(12);
        heading.addView(name, new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(icon("thermometer",Design.TEAL,20), new LinearLayout.LayoutParams(dp(22),dp(22)));
        box.addView(heading);
        TextView value = valueText("— °C"); value.setTextSize(20); value.setSingleLine(true);
        if(Build.VERSION.SDK_INT>=26) value.setAutoSizeTextTypeUniformWithConfiguration(13,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(value, fullParams(0,12,0,6));
        TextView note = mutedText("Đang chờ dữ liệu"); note.setTextSize(11);
        box.addView(note);
        if(sensorIndex==1) { temperature20Value=value; temperature20Note=note; }
        else if (sensorIndex==2) { temperature50Value = value; temperature50Note = note; }
        else { temperatureValue = value; temperatureNote = note; }
        return box;
    }

    private LinearLayout timerCard(String channel, int color) {
        LinearLayout card = card();
        TextView label = mutedText("green".equals(channel) ? "OP" : "CL");
        label.setTextSize(12); label.setTextColor(color); label.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(label, fullParams(0,0,0,8));
        SevenSegmentView display=new SevenSegmentView(this); display.setSegmentColor(color);
        card.addView(display,new LinearLayout.LayoutParams(-1,dp(64)));
        if("green".equals(channel)) greenDisplay=display; else redDisplay=display;
        return card;
    }

    private void refreshDashboard() {
        if (!foreground || screen != Screen.CONTROL || tab != Tab.OVERVIEW || dashboardBusy) return;
        dashboardBusy = true;
        final int epoch = dashboardEpoch;
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        final boolean readHistory = android.os.SystemClock.elapsedRealtime() >= nextHistoryRead;
        io.execute(() -> {
            if (!currentControl(controlToken) || epoch!=dashboardEpoch || !foreground) return;
            JSONObject status = null;
            JSONObject debug = null;
            Exception failure = null;
            long[] history = null;
            Exception historyFailure = null;
            try {
                status = session.get("/api/status");
                if(epoch!=dashboardEpoch || !foreground)return;
                debug = session.get("/api/debug");
                if (readHistory && currentControl(controlToken) && epoch == dashboardEpoch && foreground) {
                    try { history = session.history(); }
                    catch (Exception error) { historyFailure = error; }
                }
            } catch (Exception error) {
                failure = error;
            }
            JSONObject finalStatus = status;
            JSONObject finalDebug = debug;
            Exception finalFailure = failure;
            long[] finalHistory = history;
            Exception finalHistoryFailure = historyFailure;
            onControl(controlToken, () -> {
                if (epoch != dashboardEpoch || !foreground) return;
                dashboardBusy = false;
                if (screen != Screen.CONTROL || tab != Tab.OVERVIEW) return;
                if (readHistory) {
                    nextHistoryRead = android.os.SystemClock.elapsedRealtime() + 15000;
                    if (finalHistory != null) { lastHistory = finalHistory; historyError = ""; }
                    else historyError = finalHistoryFailure == null ? "Mất kết nối thiết bị." : errorMessage(finalHistoryFailure);
                }
                if (finalFailure != null) historyError = "Mất kết nối thiết bị.";
                renderHistory();
                if (finalFailure == null) {
                    rememberStatus(finalStatus);
                    lastDebug = finalDebug;
                    renderDashboard(finalStatus, finalDebug);
                } else if (dashboardConnection != null) {
                    renderDashboard(null, null);
                    dashboardMode.setText("Mất kết nối");
                    dashboardConnection.setText("Chưa đọc được thiết bị: " + errorMessage(finalFailure));
                }
                mainHandler.postDelayed(dashboardRefresh, 1000);
            });
        });
    }

    private void renderDashboard(JSONObject status, JSONObject debug) {
        if (dashboardConnection == null) return;
        renderTemperature(status);
        renderModeControls(status, debug);
        renderTrigger(status, debug);
        JSONObject uplink = getUplink(status);
        String ssid = uplink.optString("ssid", uplinkSsid);
        String network = ssid.isEmpty() ? "Chưa nhận Wi‑Fi" : ssid;
        dashboardConnection.setText(network);

        if (debug == null || !debug.optBoolean("enabled", false)) {
            dashboardMode.setText("Đang chờ dữ liệu");
            stopCounting();
            greenDisplay.setValue(null); redDisplay.setValue(null);
            for (int index = 0; index < relayViews.length; index++) setRelay(relayViews[index], index + 1, null);
            return;
        }
        JSONArray variables = debug.optJSONArray("variables");
        Map<String, String> values = new HashMap<>();
        if (variables != null) {
            for (int index = 0; index < variables.length(); index++) {
                JSONObject item = variables.optJSONObject(index);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String value = String.valueOf(item.opt("value"));
                if (name.isEmpty()) continue;
                values.put(name.toLowerCase(Locale.US), value);
            }
        }
        String mode = values.get("mode");
        dashboardMode.setText(friendlyMode(mode));
        greenDisplay.setValue(values.get("green_min"));
        redDisplay.setValue(values.get("red_min"));
        boolean fresh = values.containsKey("countdown_active") && debug.optLong("last_rx_age_ms", -1) >= 0 && debug.optLong("last_rx_age_ms", -1) <= 3000;
        String currentPause = null;
        for (int i = 0; fresh && variables != null && i < variables.length(); i++) {
            JSONObject item = variables.optJSONObject(i);
            if (item != null && "remote_paused".equals(item.optString("name")) && item.optLong("age_ms", -1) >= 0 && item.optLong("age_ms", -1) <= 3000) currentPause = item.optString("value");
            if (item != null && ("countdown_active".equals(item.optString("name")) || "mode".equals(item.optString("name")) || "green_min".equals(item.optString("name")) || "red_min".equals(item.optString("name")))) {
                long age = item.optLong("age_ms", -1);
                if (age < 0 || age > 3000) fresh = false;
            }
        }
        if (!fresh) stopCounting();
        else {
            int active = countdownTracker.update(mode, values.get("countdown_active"), values.get("green_min"), values.get("red_min"), currentPause);
            greenDisplay.setCounting(active == 1); redDisplay.setCounting(active == 2);
            mainHandler.removeCallbacks(expireCounting);
            mainHandler.postDelayed(expireCounting, 5000);
        }
        setRelay(relayViews[0], 1, values.get("relay1"));
        setRelay(relayViews[1], 2, values.get("relay2"));
        setRelay(relayViews[2], 3, values.get("relay3"));

    }

    private void renderTemperature(JSONObject status) {
        renderSensor(status == null ? null : status.optJSONObject("analog"), temperatureValue, temperatureNote, 0);
        renderSensor(status == null ? null : status.optJSONObject("analog50k"), temperature50Value, temperature50Note, 2);
        renderSensor(status == null ? null : status.optJSONObject("analog20k"), temperature20Value, temperature20Note, 1);
    }

    private void renderSensor(JSONObject input, TextView temperatureValue, TextView temperatureNote, int sensorIndex) {
        if (temperatureValue == null) return;
        temperatureValue.setText("— °C"); temperatureValue.setTextColor(Design.MUTED);
        temperatureNote.setText("Đang chờ dữ liệu");
        if (input == null) return;
        if (!input.optBoolean("enabled")) {
            temperatureNote.setText("Thiết bị chưa hỗ trợ cảm biến này"); return;
        }
        int gpio = input.optInt("gpio", -1);
        if ((sensorIndex==2 ? gpio != 4 : sensorIndex==1 ? (gpio != 5 && gpio != 6) : (gpio != 1 && gpio != 5 && gpio != 6)) || !input.optBoolean("valid") || input.isNull("age_ms")) return;
        long age = input.optLong("age_ms", -1);
        if (age < 0 || age > 2000) return;
        String sensor = input.optString("sensor");
        if ("open".equals(sensor) || "short".equals(sensor)) {
            temperatureNote.setText("Kiểm tra dây cảm biến"); return;
        }
        if ("out_of_range".equals(sensor)) { temperatureNote.setText("Nhiệt độ ngoài khoảng đo"); return; }
        if (!"ok".equals(sensor)) return;
        Object raw = input.opt("temperature_centi");
        if (!(raw instanceof Integer || raw instanceof Long)) return;
        long value = ((Number)raw).longValue();
        if (value < -5500 || value > 12500) return;
        temperatureValue.setText(String.format(Locale.forLanguageTag("vi-VN"), "%.1f °C", value / 100.0));
        temperatureValue.setTextColor(Design.TEAL);
        temperatureNote.setText("Cảm biến đang kết nối");
    }

    private void renderHistory() {
        if (dashboardLogs == null) return;
        String text = DeviceHistory.text(lastHistory);
        if (!historyError.isEmpty()) text = (lastHistory == null ? "Chưa đọc được nhật ký." : "Dữ liệu lần đọc trước:\n" + text)
                + "\n\nChưa làm mới: " + historyError;
        dashboardLogs.setText(text);
    }

    private void setRelay(TextView relay, int number, String rawValue) {
        if (relay == null) return;
        boolean known = rawValue != null && !"null".equals(rawValue);
        boolean on = known && ("on".equalsIgnoreCase(rawValue) || "1".equals(rawValue) || "true".equalsIgnoreCase(rawValue));
        relay.setText("Rơ-le " + number + "\n" + (known ? (on ? "BẬT" : "TẮT") : "Chờ dữ liệu"));
        Design.Icon symbol = new Design.Icon("power", on ? Design.TEAL : Design.MUTED);
        symbol.setBounds(0, 0, dp(30), dp(30));
        relay.setCompoundDrawables(null, symbol, null, null);
        relay.setCompoundDrawablePadding(dp(10));
        relay.setContentDescription("Rơ-le " + number + (known ? (on ? " đang bật" : " đang tắt") : " đang chờ dữ liệu"));
        relay.setTextColor(on ? Design.TEAL : Design.MUTED);
        relay.setBackground(rounded(on ? 0xFFE0F4EC : 0xFFF1F4F7, dp(14)));
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
        addHelp(intro, "Đọc cài đặt đang dùng trước khi sửa. Khi lưu, thiết bị sẽ tự khởi động lại.");
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
        addChoiceField(modes, "Xả đá OP tính giây", "defrostOpSeconds", choices("Tắt", 0, "Bật", 1));
        addCheckField(modes, "Hiện chế độ DF", "showDf", false);
        addCheckField(modes, "Hiện chế độ END", "showEnd", false);
        addCheckField(modes, "Hiện chế độ SL", "showSl", false);
        addCheckField(modes, "Hiện chế độ HCF", "showHcf", false);
        addCheckField(modes, "Hiện chế độ HDF", "showHdf", false);
        addChoiceField(modes, "Hiện ST LED", "showSt", choices("Tắt", 0, "Bật", 1));
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
        addHelp(pin, "Chỉ các mục được người quản lý cấp quyền mới có thể chỉnh sửa.");
        content.addView(pin, fullParams(0, 12, 0, 0));
        for (LinearLayout section : new LinearLayout[]{timing, modes, ranges, pin}) {
            boolean hasFields = false;
            for (int i=0;i<section.getChildCount();i++) {
                View child=section.getChildAt(i);
                if (child instanceof EditText || child instanceof Spinner || child instanceof CheckBox) hasFields=true;
            }
            if (!hasFields) section.setVisibility(View.GONE);
        }
        applyRuntimeConfigToViews();
    }

    private void addRangeFields(LinearLayout parent, String label, String key) {
        addNumberField(parent, label + " · Min", key + "Min");
        addNumberField(parent, label + " · Max", key + "Max");
    }

    private void addNumberField(LinearLayout parent, String label, String key) {
        if (!settingVisible(key)) return;
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        EditText input = numberInput(label);
        configInputs.put(key, input);
        parent.addView(input, fullParams(0, 0, 0, 0));
    }

    private void addPinNumberField(LinearLayout parent, String label, String key) {
        if (!settingVisible(key)) return;
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        EditText input = numberInput(label);
        configInputs.put(key, input); pinDependentViews.add(fieldLabel); pinDependentViews.add(input);
        parent.addView(input, fullParams(0, 0, 0, 0));
    }

    private void addChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        if (!settingVisible(key)) return;
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        Spinner spinner = choiceSpinner(choices);
        spinner.setContentDescription(label);
        configChoices.put(key, spinner);
        parent.addView(spinner, fullParams(0, 0, 0, 0));
    }

    private void addPinChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        if (!settingVisible(key)) return;
        TextView fieldLabel = mutedText(label);
        parent.addView(fieldLabel, fullParams(0, 10, 0, 2));
        Spinner spinner = choiceSpinner(choices);
        spinner.setContentDescription(label);
        configChoices.put(key, spinner); pinDependentViews.add(fieldLabel); pinDependentViews.add(spinner);
        parent.addView(spinner, fullParams(0, 0, 0, 0));
    }

    private CheckBox addCheckField(LinearLayout parent, String label, String key, boolean pinRelated) {
        CheckBox check = new CheckBox(this);
        if (!settingVisible(key)) return check;
        check.setText(label);
        check.setTextColor(Design.INK);
        check.setButtonTintList(ColorStateList.valueOf(Design.TEAL));
        check.setMinHeight(dp(48));
        check.setTextSize(14);
        check.setPadding(0, dp(5), 0, dp(5));
        configChecks.put(key, check);
        if (pinRelated && !"pinEnable".equals(key)) pinDependentViews.add(check);
        parent.addView(check, fullParams(0, 3, 0, 0));
        return check;
    }

    private void loadRuntimeConfig() {
        showMessage("Đang đọc cài đặt…");
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject response = session.get("/api/runtime-config");
                RuntimeConfigCodec.Config decoded = RuntimeConfigCodec.decode(response.getString("data"));
                onControl(controlToken, () -> {
                    runtimeConfig = decoded;
                    configLoaded = true;
                    applyRuntimeConfigToViews();
                    showMessage("Đã đọc cài đặt.");
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
            }
        });
    }

    private void saveRuntimeConfig() {
        if (demoMode) { showMessage("Chế độ xem thử: không ghi cài đặt vào thiết bị."); return; }
        if (!configLoaded) { showMessage("Hãy đọc cài đặt từ thiết bị trước khi lưu."); return; }
        final Map<String, Integer> values;
        final JSONObject visibleValues = new JSONObject();
        try {
            values = collectRuntimeConfigValues();
            for (String key : values.keySet()) {
                if (settingVisible(key) && !java.util.Objects.equals(values.get(key), runtimeConfig.values.get(key)))
                    visibleValues.put(key, values.get(key));
            }
            if (visibleValues.length() == 0) { showMessage("Không có thay đổi cần lưu."); return; }
        } catch (Exception error) {
            showMessage(errorMessage(error));
            return;
        }
        showMessage("Đang lưu cài đặt. Thiết bị sẽ khởi động lại…");
        final int controlToken = controlEpoch;
        final CloudApi cloud = accounts.api();
        final String device = activeDevice.id;
        final int revision = deviceAccess.optInt("revision", -1);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject response = cloud.post("/devices/" + device + "/settings",
                        new JSONObject().put("revision", revision).put("values", visibleValues));
                onControl(controlToken, () -> {
                    runtimeConfig = new RuntimeConfigCodec.Config(runtimeConfig.raw, values);
                    showMessage("Đã lưu cài đặt.");
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
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
        boolean active = enabled == null ? configValue("pinEnable") == 1 : enabled.isChecked();
        for (View view : pinDependentViews) {
            view.setEnabled(active);
            view.setAlpha(active ? 1f : 0.43f);
        }
    }

    // -------------------------------------------------------------------------
    // Native firmware tools
    // -------------------------------------------------------------------------

    private boolean settingVisible(String key) {
        if (demoMode) return true;
        JSONArray visible = deviceAccess.optJSONArray("visible");
        if (visible != null) for (int i=0;i<visible.length();i++) if (key.equals(visible.optString(i))) return true;
        return false;
    }

    private void loadManagedTab(LinearLayout content, Tab selected) {
        if (demoMode) {
            if (selected == Tab.SETTINGS) buildSettings(content);
            else { LinearLayout card=card(); addSectionTitle(card,"Cập nhật thiết bị"); addHelp(card,"Không có phiên bản cập nhật."); content.addView(card); }
            return;
        }
        final int controlToken=controlEpoch, viewToken=accessViewEpoch;
        final CloudApi cloud=accounts.api(); final String device=activeDevice.id;
        TextView loading=bodyText("Đang lấy quyền và bản cập nhật từ máy chủ…"); content.addView(loading);
        io.execute(() -> {
            try {
                JSONObject access=cloud.get("/devices/"+device+"/access");
                onControl(controlToken, () -> {
                    if (viewToken!=accessViewEpoch) return;
                    deviceAccess=access; content.removeView(loading);
                    if (selected==Tab.SETTINGS) {
                        JSONArray visible=access.optJSONArray("visible");
                        watchSettingsPolicy(controlToken, viewToken);
                        if (visible==null || visible.length()==0) { content.addView(bodyText("Admin chưa cấp quyền cài đặt cho thiết bị này.")); return; }
                        configLoaded=false; buildSettings(content); loadRuntimeConfig();
                    } else buildManagedFirmware(content,access,viewToken);
                });
            } catch(Exception error) { onControl(controlToken, () -> {
                if (viewToken!=accessViewEpoch) return;
                loading.setText(errorMessage(error));
                Button retry=neutralButton("Thử lại"); retry.setOnClickListener(v -> showControl(selected)); content.addView(retry);
            }); }
        });
    }

    private void watchSettingsPolicy(int controlToken, int viewToken) {
        mainHandler.postDelayed(() -> {
            if (!currentControl(controlToken) || viewToken!=accessViewEpoch || tab!=Tab.SETTINGS) return;
            final CloudApi cloud=accounts.api(); final String device=activeDevice.id;
            io.execute(() -> {
                try {
                    JSONObject fresh=cloud.get("/devices/"+device+"/access");
                    onControl(controlToken, () -> {
                        if(viewToken!=accessViewEpoch) return;
                        if(fresh.optInt("revision",-1)!=deviceAccess.optInt("revision",-1)) {
                            deviceAccess=fresh; showMessage("Người quản lý vừa thay đổi quyền. Cài đặt được tải lại."); showControl(Tab.SETTINGS);
                        } else watchSettingsPolicy(controlToken,viewToken);
                    });
                } catch(Exception error) { onControl(controlToken, () -> {
                    if(viewToken==accessViewEpoch) watchSettingsPolicy(controlToken,viewToken);
                }); }
            });
        },15000);
    }

    private void buildManagedFirmware(LinearLayout content, JSONObject access, int viewToken) {
        LinearLayout box=card(); addSectionTitle(box,"Cập nhật thiết bị"); content.addView(box,fullParams(0,0,0,0));
        JSONObject firmware=access.optJSONObject("firmware"), job=access.optJSONObject("job");
        boolean same=firmware!=null && job!=null && firmware.optString("id").equals(job.optString("release"));
        boolean running=same && "running".equals(job.optString("state"));
        boolean unknown=same && "unknown".equals(job.optString("state"));
        if (running || unknown) {
            addHelp(box,plainMessage(job.optString("message", "Đang cập nhật…")));
            if (unknown) addHelp(box,"Cần người quản lý kiểm tra và cấp lại bản cập nhật.");
        } else if (access.optBoolean("update_available") && firmware!=null) {
            addHelp(box,"Đã có phiên bản mới cho thiết bị.");
            Button update=primaryButton("Cập nhật"); box.addView(update,fullParams(0,12,0,0));
            update.setEnabled(!managedUpdateStarting);
            update.setOnClickListener(v -> {
                if (managedUpdateStarting) return;
                new AlertDialog.Builder(this).setTitle("Cập nhật thiết bị?")
                    .setMessage("Giữ nguồn và Internet trong khi cập nhật. Thiết bị sẽ tạm dừng vận hành.")
                    .setNegativeButton("Hủy",null).setPositiveButton("Cập nhật",(dialog,which) -> {
                        if(viewToken==accessViewEpoch) startManagedUpdate(firmware.optString("id"),update);
                    }).show();
            });
        } else addHelp(box,"Không có phiên bản cập nhật.");
        if (same && "success".equals(job.optString("state"))) addHelp(box,plainMessage(job.optString("message")));
        if (same && "failed".equals(job.optString("state"))) addHelp(box,plainMessage(job.optString("message")));
        final int controlToken=controlEpoch;
        mainHandler.postDelayed(() -> {
            if(!currentControl(controlToken) || viewToken!=accessViewEpoch || tab!=Tab.FIRMWARE || managedUpdateStarting) return;
            final CloudApi cloud=accounts.api(); final String device=activeDevice.id;
            io.execute(() -> {
                try {
                    JSONObject fresh=cloud.get("/devices/"+device+"/access");
                    onControl(controlToken, () -> {
                        if(viewToken!=accessViewEpoch) return;
                        deviceAccess=fresh; content.removeView(box); buildManagedFirmware(content,fresh,viewToken);
                    });
                } catch(Exception error) { onControl(controlToken, () -> {
                    if(viewToken==accessViewEpoch) { content.removeView(box); loadManagedTab(content,Tab.FIRMWARE); }
                }); }
            });
        },running ? 2500 : 15000);
    }

    private void startManagedUpdate(String release, Button button) {
        if(managedUpdateStarting || activeDevice==null) return;
        managedUpdateStarting=true; button.setEnabled(false);
        final int token=controlEpoch; final String device=activeDevice.id; final CloudApi cloud=accounts.api();
        io.execute(() -> {
            try {
                cloud.post("/devices/"+device+"/update",new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","" )).put("release",release));
                onControl(token, () -> { managedUpdateStarting=false; showControl(Tab.FIRMWARE); });
            } catch(Exception error) { onControl(token, () -> { managedUpdateStarting=false; showMessage(errorMessage(error)); showControl(Tab.FIRMWARE); }); }
        });
    }

    private void chooseFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "text/plain", "text/x-ihex", "application/x-ihex"});
        try {
            filePickerEpoch = controlEpoch;
            startActivityForResult(intent, FIRMWARE_FILE_REQUEST);
        } catch (ActivityNotFoundException error) {
            showMessage("Điện thoại không có trình chọn tệp.");
        }
    }

    private void uploadFirmware() {
        if (demoMode) { showMessage("Chế độ xem thử: không tải tệp lên thiết bị."); return; }
        if (selectedFirmwareUri == null || selectedFirmwareName.isEmpty()) {
            showMessage("Hãy chọn tệp cập nhật trước.");
            return;
        }
        String lower = selectedFirmwareName.toLowerCase(Locale.US);
        if (!(lower.endsWith(".hex") || lower.endsWith(".ihex") || lower.endsWith(".ihx") || lower.endsWith(".bin"))) {
            showMessage("Tệp cập nhật không được hỗ trợ.");
            return;
        }
        showMessage("Đang gửi " + selectedFirmwareName + " vào thiết bị…");
        final Uri fileUri = selectedFirmwareUri;
        final String fileName = selectedFirmwareName;
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                String lowerName = fileName.toLowerCase(Locale.US);
                int limit = lowerName.endsWith(".bin") ? 32 * 1024 : 96 * 1024;
                byte[] bytes = readUri(fileUri, limit);
                String serverName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
                JSONObject response = session.upload(serverName, bytes);
                JSONObject image = response.optJSONObject("image");
                onControl(controlToken, () -> {
                    if (image != null) imageGeneration = image.optInt("generation", 0);
                    if (firmwareInfo != null) firmwareInfo.setText(imageSummary(image));
                    showMessage(response.optString("message", "Đã gửi tệp vào thiết bị."));
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
            }
        });
    }

    private void readChipInfo() {
        showMessage("Đang kiểm tra thiết bị…");
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject response = session.post("/api/info", new JSONObject());
                String info = "Đã kết nối bộ điều khiển.";
                onControl(controlToken, () -> {
                    if (firmwareInfo != null) firmwareInfo.setText(info);
                    if (deviceInfo != null) deviceInfo.setText(info);
                    showMessage("Đã kiểm tra thiết bị.");
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
            }
        });
    }

    private void confirmDanger(String action) {
        final int dialogToken = controlEpoch;
        if (demoMode) { showMessage("Chế độ xem thử: các lệnh ghi và xóa đã bị khóa."); return; }
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
                    if (!currentControl(dialogToken)) return;
                    if (!"CONFIRM".equals(confirm.getText().toString().trim())) {
                        showMessage("Chưa nhập đúng CONFIRM.");
                        return;
                    }
                    runJob(action, "program-full".equals(action), true);
                })
                .show();
    }

    private void runJob(String action, boolean needsImage, boolean destructive) {
        if (demoMode) { showMessage("Chế độ xem thử: không gửi lệnh đến thiết bị."); return; }
        if (needsImage && imageGeneration <= 0) {
            showMessage("Hãy gửi bản cập nhật vào thiết bị trước.");
            return;
        }
        showMessage("Thiết bị đang thực hiện: " + jobLabel(action));
        final int generation = imageGeneration;
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject body = new JSONObject();
                body.put("generation", needsImage ? generation : 0);
                if (destructive) body.put("confirm", "CONFIRM");
                JSONObject response = session.post("/api/" + action, body);
                boolean accepted = response.optBoolean("accepted", false);
                onControl(controlToken, () -> {
                    showMessage(response.optString("message", "Đã gửi lệnh."));
                    if (accepted) pollJob(80, session, controlToken, response.optInt("job_id", -1));
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
            }
        });
    }

    private void pollJob(int remaining, EspApi session, int controlToken, int jobId) {
        if (!currentControl(controlToken)) return;
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject status = session.get("/api/status");
                onControl(controlToken, () -> {
                    rememberStatus(status);
                    if (jobId >= 0 && status.optInt("job_id",-1) != jobId) {
                        showMessage("Thiết bị đã nhận tác vụ khác. Hãy kiểm tra trạng thái."); return;
                    }
                    boolean busy = status.optBoolean("busy",false);
                    showMessage(status.optString("last_message",busy ? "Đang xử lý…" : "Hoàn tất."));
                    if (busy && remaining > 0) mainHandler.postDelayed(() -> pollJob(remaining-1, session, controlToken, jobId),850);
                    else if (busy) showMessage("Thiết bị vẫn đang xử lý. Hãy làm mới trạng thái.");
                });
            } catch(Exception error) { onControl(controlToken, () -> showMessage(errorMessage(error))); }
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
        addValueLine(networkCard, "Tín hiệu", uplink.has("rssi") ? (uplink.optInt("rssi", -100) >= -60 ? "Tốt" : uplink.optInt("rssi", -100) >= -75 ? "Trung bình" : "Yếu") : "—");
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
        addSectionTitle(chipCard, "Kiểm tra thiết bị");
        deviceInfo = monospaceText("Kiểm tra kết nối với bộ điều khiển.");
        chipCard.addView(deviceInfo, fullParams(0, 2, 0, 0));
        Button info = neutralButton("Kiểm tra kết nối");
        info.setOnClickListener(v -> readChipInfo());
        chipCard.addView(info, fullParams(0, 10, 0, 0));
        content.addView(chipCard, fullParams(0, 12, 0, 0));
        LinearLayout about = card();
        addSectionTitle(about, "MS51 Connect");
        addHelp(about, "Phiên bản 3.3.1 • Kết nối qua Internet");
        Button exit = neutralButton("Danh sách thiết bị");
        exit.setOnClickListener(v -> showDevices());
        about.addView(exit, fullParams(0, 12, 0, 0));
        content.addView(about, fullParams(0, 16, 0, 0));
    }

    private void refreshDeviceStatus() {
        showMessage("Đang làm mới trạng thái…");
        final int controlToken = controlEpoch;
        final EspApi session = api.snapshot(15000);
        io.execute(() -> {
            if (!currentControl(controlToken)) return;
            try {
                JSONObject status = session.get("/api/status");
                onControl(controlToken, () -> {
                    rememberStatus(status);
                    showControl(Tab.DEVICE);
                });
            } catch (Exception error) {
                onControl(controlToken, () -> showMessage(errorMessage(error)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Common data and view helpers
    // -------------------------------------------------------------------------

    private LinearLayout createPage(String eyebrow, String title, String subtitle) {
        pageRoot = new LinearLayout(this);
        pageRoot.setOrientation(LinearLayout.VERTICAL); pageRoot.setBackgroundColor(Design.BG);
        pageRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(22),dp(16),dp(22),dp(14));
        ImageView logo=icon("chip",Design.TEAL,25);
        brand.addView(logo,new LinearLayout.LayoutParams(dp(27),dp(27)));
        TextView wordmark=valueText("MS51"); wordmark.setTextSize(19); wordmark.setLetterSpacing(.04f);
        brand.addView(wordmark,wrapParams(10,0,0,0));
        TextView connect=bodyText(" / connect"); connect.setTextSize(16);
        brand.addView(connect,new LinearLayout.LayoutParams(0,-2,1));
        if (screen == Screen.HOME) {
            Button signOut = neutralButton("Đăng xuất");
            signOut.setTextSize(12); signOut.setTextColor(Design.TEAL);
            signOut.setMinWidth(0); signOut.setMinimumWidth(0);
            signOut.setMinHeight(dp(48)); signOut.setMinimumHeight(dp(48));
            signOut.setPadding(dp(8),0,dp(8),0);
            signOut.setMaxLines(1); signOut.setHorizontallyScrolling(false);
            signOut.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            signOut.setOnClickListener(v -> logout());
            brand.addView(signOut,new LinearLayout.LayoutParams(-2,dp(48)));
        }
        pageRoot.addView(brand);
        if(demoMode){
            TextView demo=mutedText("XEM THỬ  •  DỮ LIỆU MINH HỌA");
            demo.setTextColor(Design.TEAL); demo.setGravity(Gravity.CENTER);
            demo.setPadding(dp(12),dp(8),dp(12),dp(8)); demo.setBackgroundColor(0xFFE0F4EC);
            pageRoot.addView(demo,fullParams(0,0,0,0));
        }
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22),dp(12),dp(22),dp(28));
        if (!eyebrow.isEmpty()) {
            TextView eyebrowView=mutedText(eyebrow); eyebrowView.setTextColor(Design.TEAL);
            eyebrowView.setTextSize(10); eyebrowView.setLetterSpacing(.12f);
            content.addView(eyebrowView,fullParams(0,0,0,7));
        }
        TextView heading=valueText(title); heading.setTextSize(29);
        heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        content.addView(heading,fullParams(0,0,0,subtitle.isEmpty() ? 22 : 5));
        if (!subtitle.isEmpty()) content.addView(bodyText(subtitle),fullParams(0,0,0,22));
        messageView=bodyText(lastUiMessage); messageView.setTextSize(12);
        messageView.setPadding(dp(14),dp(12),dp(14),dp(12));
        messageView.setBackground(rounded(0xFFE8F2F1,dp(12)));
        messageView.setTextColor(Design.TEAL);
        messageView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        messageView.setVisibility(lastUiMessage.isEmpty() || demoMode ? View.GONE : View.VISIBLE);
        content.addView(messageView,fullParams(0,0,0,16));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        pageRoot.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(pageRoot); pageRoot.requestApplyInsets();
        return content;
    }

    private ImageView icon(String name, int color, int size) {
        ImageView view=new ImageView(this); view.setImageDrawable(new Design.Icon(name,color));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static String friendlyMode(String mode) {
        if(mode==null || "null".equals(mode)) return "Đang chờ dữ liệu";
        switch(mode.toUpperCase(Locale.US)) {
            case "COOL": case "COOLING": return "Đang làm lạnh";
            case "DEFROST": case "ICE_FLUSH": return "Đang xả đá";
            case "IDLE": case "STOP": return "Đang chờ";
            case "SETTING": case "ADVANCE_SETTING": return "Đang cài đặt";
            case "PASSWORD_CONFIG": return "Đang cài mật khẩu";
            default: return "Đang chờ dữ liệu";
        }
    }

    private LinearLayout card() {
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(19),dp(18),dp(19));
        GradientDrawable bg=rounded(Color.WHITE,dp(20)); bg.setStroke(dp(1),0xFFE9EFF4);
        card.setBackground(bg); return card;
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView view=valueText(title); view.setTextSize(17);
        parent.addView(view,fullParams(0,0,0,7));
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
        view.setTextColor(Design.MUTED);
        view.setTextSize(14);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private TextView mutedText(String text) {
        TextView view = bodyText(text);
        view.setTextSize(12);
        view.setTextColor(Design.MUTED);
        return view;
    }

    private TextView valueText(String text) {
        TextView view = bodyText(text);
        view.setTextColor(Design.INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView monospaceText(String text) {
        TextView view = bodyText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(Design.INK);
        view.setTextSize(12);
        view.setTextIsSelectable(true);
        view.setPadding(dp(2), dp(3), dp(2), dp(3));
        return view;
    }

    private EditText textInput(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(Design.INK);
        input.setHintTextColor(Design.MUTED);
        input.setHint(hint);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setMinHeight(dp(52));
        input.setContentDescription(hint);
        input.setBackground(rounded(0xFFF0F4F7, dp(12)));
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
        spinner.setBackground(rounded(0xFFF0F4F7, dp(12)));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        spinner.setMinimumHeight(dp(52));
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

    private Button primaryButton(String text) { return button(text, Design.TEAL); }
    private Button neutralButton(String text) { return button(text, 0xFFEDF3F6); }
    private Button dangerButton(String text) { return button(text, 0xFFFFEEF0); }

    private Button button(String text, int color) {
        Button button=new Button(this); button.setText(text);
        button.setTextColor(color==Design.TEAL ? Color.WHITE : color==0xFFFFEEF0 ? 0xFFAA354A : Design.INK);
        button.setTextSize(14); button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52));
        button.setPadding(dp(14),dp(12),dp(14),dp(12)); button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2200776C),rounded(color,dp(14)),null));
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
        if (messageView != null) {
            messageView.setText(lastUiMessage); messageView.setVisibility(View.VISIBLE);
            if (screen == Screen.CONTROL) Toast.makeText(this,lastUiMessage,Toast.LENGTH_SHORT).show();
        }
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
        return message == null || message.trim().isEmpty() ? "Có lỗi không xác định." : plainMessage(message);
    }

    private static String plainMessage(String text) {
        return text.replace("firmware", "bản cập nhật").replace("Firmware", "Bản cập nhật")
                .replace("UART", "thiết bị").replace("MQTT", "kết nối").replace("MS51", "bộ điều khiển")
                .replace("admin", "người quản lý").replace("Admin", "Người quản lý")
                .replace("CRC", "mã kiểm tra").replace("generation", "phiên bản");
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
        mainHandler.removeCallbacks(expireTriggerControls); expireTriggerControls.run();
        mainHandler.removeCallbacks(expireModeControls);
        expireModeControls.run();
        stopCounting();
        renderTemperature(null);
        dashboardEpoch++;
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
        if (!currentControl(filePickerEpoch)) return;
        selectedFirmwareUri = data.getData();
        selectedFirmwareName = displayName(selectedFirmwareUri);
        if (firmwareFileInfo != null) firmwareFileInfo.setText("Đã chọn: " + selectedFirmwareName);
        showMessage("Đã chọn " + selectedFirmwareName + ". Bấm Gửi tệp vào thiết bị để tiếp tục.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (screen == Screen.HOME) restartHomeChecks();
        if (screen == Screen.CONTROL && tab == Tab.OVERVIEW) {
            mainHandler.removeCallbacks(dashboardRefresh);
            mainHandler.post(dashboardRefresh);
        }
        if (waitingForWifiSettingsReturn) {
            waitingForWifiSettingsReturn = false;
            mainHandler.postDelayed(this::verifyManualWifiAfterSettings, 400);
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        stopHome();
        stopDashboard();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.AUTH) {
            if (authBusy) return;
            if (registerMode) { showAuth(false); return; }
            super.onBackPressed(); return;
        }
        if (screen != Screen.CONTROL && setupBusy) {
            showMessage("Đang xử lý, vui lòng chờ xong bước này.");
            return;
        }
        if (screen == Screen.WIFI_PASSWORD) { showWifiSetup(lastStatus); return; }
        if (screen == Screen.CONFIGURE_WIFI || screen == Screen.SWITCH_PHONE) {
            releaseRequestedNetwork(); showConnectDevice(changingWifi); return;
        }
        if (screen == Screen.CONTROL && tab != Tab.OVERVIEW) {
            showControl(Tab.OVERVIEW);
            return;
        }
        if (screen == Screen.CONTROL || screen == Screen.CONNECT_DEVICE) {
            if (screen == Screen.CONNECT_DEVICE) releaseRequestedNetwork();
            showDevices(); return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(internetChanged);
        if(internetCallback!=null)connectivityManager.unregisterNetworkCallback(internetCallback);
        ++setupEpoch; ++authEpoch; ++controlEpoch; stopHome();
        presenceIo.shutdownNow();
        stopDashboard();
        releaseRequestedNetwork();
        io.shutdownNow();
        super.onDestroy();
    }
}
