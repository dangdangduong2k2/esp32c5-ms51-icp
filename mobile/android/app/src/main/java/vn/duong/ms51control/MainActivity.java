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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

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

    private enum Screen { AUTH, OFFLINE, HOME, CONNECT_DEVICE, CONFIGURE_WIFI, WIFI_PASSWORD, SWITCH_PHONE, CONTROL }
    private enum Tab { OVERVIEW, SETTINGS, FIRMWARE, DEVICE }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final EspApi api = new EspApi();
    private final Map<String, EditText> configInputs = new HashMap<>();
    private final Map<String, Spinner> configChoices = new HashMap<>();
    private final Map<String, CheckBox> configChecks = new HashMap<>();
    private final List<View> pinDependentViews = new ArrayList<>();
    private final java.util.Set<String> expandedSettings = new java.util.HashSet<>();

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
    private InAppNotice notice;
    private TextView dashboardConnection;
    private TextView dashboardMode;
    private TextView dashboardLogs;
    private TextView temperatureValue;
    private TextView temperatureNote;
    private TextView temperature50Value, temperature50Note, motorSensorLabel;
    private android.widget.ImageButton motorSensorSwitch;
    private int motorSensorIndex;
    private JSONObject temperatureStatus;
    private long temperatureStatusAt;
    private EditText defrostThreshold;
    private TextView defrostState;
    private boolean motorSelectionPending;
    private EditText triggerThreshold;
    private TextView triggerState;
    private Button triggerSave;
    private android.widget.Switch safetySwitch, defrostSwitch;
    private boolean safetyDraftPending, defrostDraftPending;
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
    private android.app.AlertDialog unlockDialog;
    private Button unlockButton;
    private boolean unlockSending;
    private int unlockState=-1, unlockPrompted=-1;
    private int unlockDeviceEpoch=-1;
    private final Runnable expireUnlock=() -> {
        unlockState=-1;
        if(unlockButton!=null) unlockButton.setEnabled(false);
        if(unlockDialog!=null) unlockDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    };
    private final Runnable unlockPoll=this::pollUnlock;

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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
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

    private static final int BLE_PERMISSION_REQUEST=91;
    private BleTransport bleLink;
    private final ExecutorService bleConnectIo=Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> bleConnectTask;
    private BlePicker blePicker;
    private NearbyDeviceSheet bleConnectionSheet;
    private boolean bleMode, pendingBleWifi, bleSaving;

    private void disconnectBle() {
        if(bleConnectTask!=null){bleConnectTask.cancel(true);bleConnectTask=null;}
        closeBleConnectionSheet();
        if(blePicker!=null){blePicker.close();blePicker=null;}
        if(bleLink!=null){bleLink.close();bleLink=null;}
        api.setBle(null);bleMode=false;pendingBleWifi=false;bleSaving=false;
    }
    private static final int BLE_ENABLE_REQUEST=92;
    private boolean nearbySuppressed, blePromptAttempted, blePromptPending, bleChooseManually;
    private boolean bleManualDiscovery, offlineReturnHome;
    private Button bleDiscoveryButton;
    boolean automaticNearbyAllowed(){
        // Authentication never scans or prompts for Bluetooth.
        if(screen==Screen.AUTH || screen==Screen.OFFLINE || screen==Screen.HOME)return false;
        if(deviceStore==null)return false;
        // A new login waits for its first device-list response before deciding.
        if(accountId.isEmpty())return true;
        return deviceStore.hasKnownList(accountId) && deviceStore.list(accountId).isEmpty();
    }
    private final Runnable nearbyRefresh=new Runnable(){
        @Override public void run(){
            if(!foreground)return;
            if(nearbyEligible())discoverNearby(false);else stopNearby();
            mainHandler.postDelayed(this,2000);
        }
    };
    private boolean nearbyEligible(){
        return foreground && !isFinishing() && !isDestroyed() && !demoMode && !bleMode
                && !authBusy && !setupBusy && !openingDevice
                && (screen==Screen.OFFLINE || screen==Screen.HOME || screen==Screen.CONNECT_DEVICE)
                && (bleManualDiscovery || automaticNearbyAllowed());
    }
    private void stopNearby(){if(blePicker!=null){blePicker.close();blePicker=null;}}
    private void endManualDiscovery(){
        bleManualDiscovery=false;stopNearby();updateDiscoveryButton();
    }
    private void updateDiscoveryButton(){
        if(bleDiscoveryButton!=null)bleDiscoveryButton.setText(bleManualDiscovery ? "Đang dò… · Dừng dò" : "Dò thiết bị");
    }
    private void addDiscoveryButton(LinearLayout content){
        bleDiscoveryButton=primaryButton("Dò thiết bị");
        bleDiscoveryButton.setOnClickListener(v->{
            if(bleManualDiscovery){nearbySuppressed=true;endManualDiscovery();}
            else chooseBleDevice();
        });
        content.addView(bleDiscoveryButton,fullParams(0,20,0,0));updateDiscoveryButton();
    }
    private void chooseBleDevice(){
        bleChooseManually=true;bleManualDiscovery=true;updateDiscoveryButton();
        nearbySuppressed=false;blePromptAttempted=false;stopNearby();discoverNearby(true);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void discoverNearby(boolean manual) {
        if(!nearbyEligible() || nearbySuppressed || blePromptPending || blePicker!=null)return;
        android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){endManualDiscovery();if(manual)showMessage("Điện thoại không hỗ trợ Bluetooth.");return;}
        java.util.ArrayList<String> needed=new java.util.ArrayList<>();
        String[] permissions=Build.VERSION.SDK_INT>=31
                ? new String[]{"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"}
                : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
        for(String permission:permissions)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)needed.add(permission);
        if(!needed.isEmpty()){
            if(blePromptAttempted)return;
            blePromptAttempted=true;blePromptPending=true;
            requestPermissions(needed.toArray(new String[0]),BLE_PERMISSION_REQUEST);return;
        }
        try {
            if(!adapter.isEnabled()){
                if(blePromptAttempted)return;
                blePromptAttempted=true;blePromptPending=true;
                startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),BLE_ENABLE_REQUEST);return;
            }
            // Android 6–11 also requires Location enabled for BLE discovery.
            if(Build.VERSION.SDK_INT<31){
                android.location.LocationManager location=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
                if(location!=null && !location.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                        && !location.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)){
                    if(!blePromptAttempted){blePromptAttempted=true;showMessage("Bật Vị trí trên điện thoại để tìm thiết bị Bluetooth.");}
                    endManualDiscovery();return;
                }
            }
            blePromptAttempted=false;
            blePicker=new BlePicker(this,adapter,this::nearbyEligible,
                    device->!bleChooseManually && shouldReconnectBle(device.getAddress(),device.getBondState()==android.bluetooth.BluetoothDevice.BOND_BONDED),
                    device->isPairedBle(device.getAddress(),device.getBondState()==android.bluetooth.BluetoothDevice.BOND_BONDED),
                    this::connectBleDevice,()->{nearbySuppressed=true;endManualDiscovery();});
            if(manual && screen!=Screen.OFFLINE)showMessage("Đưa điện thoại sát hộp mới để ghép nối…");
        }catch(SecurityException|IllegalStateException|ActivityNotFoundException error){
            blePromptPending=false;nearbySuppressed=true;endManualDiscovery();
            if(manual)showMessage("Hãy bật Bluetooth và cấp quyền Thiết bị ở gần rồi thử lại.");
        }
    }
    boolean isPairedBle(String address,boolean bonded) {
        return bonded && !getSharedPreferences("ble_devices_v1",MODE_PRIVATE).getString(address,"").isEmpty();
    }
    boolean shouldReconnectBle(String address,boolean bonded) {
        android.content.SharedPreferences saved=getSharedPreferences("ble_devices_v1",MODE_PRIVATE);
        if(saved.getBoolean("selection_required",false))return false;
        if(!bonded || saved.getString(address,"").isEmpty())return false;
        String last=saved.getString("last_connected_address","");
        if(!last.isEmpty())return last.equals(address);
        // Migrate 3.7.1: auto-select only when there is one previously verified box.
        int known=0;
        for(String key:saved.getAll().keySet())if(key.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))known++;
        return known==1;
    }
    private void closeBleConnectionSheet(){if(bleConnectionSheet!=null){bleConnectionSheet.close();bleConnectionSheet=null;}}
    void rememberBleDevice(String address,String id){
        getSharedPreferences("ble_devices_v1",MODE_PRIVATE).edit().putString(address,id)
                .putString("last_connected_address",address).remove("selection_required").apply();
        bleChooseManually=false;
    }
    private void cancelBleConnection(){
        ++setupEpoch;setupBusy=false;nearbySuppressed=true;disconnectBle();
        if(accountId.isEmpty())showAuth(false);else showDevices();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void connectBleDevice(android.bluetooth.BluetoothDevice device) {
        disconnectBle();stopHome();stopDashboard();++controlEpoch;++authEpoch;
        newSetupPage(Screen.CONNECT_DEVICE);setupBusy=true;
        nearbySuppressed=true;
        bleConnectionSheet=new NearbyDeviceSheet(this,this::cancelBleConnection);
        String name=device.getName();
        bleConnectionSheet.showConnecting(name==null?"Hộp hẹn giờ máy lạnh":name);
        final int token=setupEpoch;
        bleConnectTask=bleConnectIo.submit(()->{
            BleTransport link=null;
            try {
                if(!currentSetup(token) || Thread.currentThread().isInterrupted())return;
                link=BleTransport.connect(this,device);
                if(!currentSetup(token) || Thread.currentThread().isInterrupted()){link.close();return;}
                EspApi session=new EspApi();session.setBle(link);
                JSONObject status=session.get("/api/status");String id=DeviceStore.identity(status);
                if(id.isEmpty())throw new IOException("Thiết bị chưa cung cấp định danh hợp lệ.");
                String saved=getSharedPreferences("ble_devices_v1",MODE_PRIVATE).getString(device.getAddress(),"");
                if(!saved.isEmpty() && !saved.equals(id))throw new IOException("Định danh Bluetooth đã thay đổi. Hãy kiểm tra lại thiết bị.");
                final BleTransport connected=link;
                runOnUiThread(()->{
                    if(!currentSetup(token)){connected.close();return;}
                    bleLink=connected;bleMode=true;api.setDemo(false);demoMode=false;
                    releaseRequestedNetwork();clearDeviceState();api.setBle(connected);api.setExpectedDeviceId(id);
                    pairingDeviceId=id;pairingHostname=id+".local";localDeviceReady=true;
                    activeDevice=new DeviceStore.Device(id,"Hộp hẹn giờ "+id.substring(id.length()-6).toUpperCase(Locale.ROOT),"",pairingHostname,"");
                    rememberBleDevice(device.getAddress(),id);
                    rememberStatus(status);showBleConnected();
                });
            } catch(Exception error) {
                if(link!=null)link.close();
                runOnUiThread(()->{if(currentSetup(token)){
                    closeBleConnectionSheet();setupBusy=false;nearbySuppressed=true;
                    LinearLayout content=createPage("","Chưa kết nối được","");
                    showMessage(errorMessage(error));Button retry=primaryButton("Tìm lại thiết bị");content.addView(retry);
                    retry.setOnClickListener(v->chooseBleDevice());
                    Button back=neutralButton("Quay lại");content.addView(back);back.setOnClickListener(v->cancelBleConnection());
                }});
            }
        });
    }
    private void showBleConnected() {
        closeBleConnectionSheet();
        setupBusy=false;++controlEpoch;showControl(Tab.OVERVIEW);
    }
    private void beginBleWifi() {
        if(bleLink==null || !bleLink.connected()){showMessage("Hãy kết nối Bluetooth lại trước.");return;}
        pendingBleWifi=true;stopDashboard();
        if(accounts.session()==null){showAuth(false);return;}
        pendingBleWifi=false;localDeviceReady=true;showWifiSetup(lastStatus);
    }
    private void finishBleAccountLogin() {
        if(pendingBleWifi && bleMode){pendingBleWifi=false;localDeviceReady=true;showWifiSetup(lastStatus);}
        else showDevices();
    }
    private void linkBleAccount(int token) {
        final CloudApi cloud=accounts.api();final EspApi session=api.snapshot(45000);
        final String id=pairingDeviceId,owner=accountId;
        io.execute(()->{
            try {
                JSONObject claim=cloud.post("/pair/start",new JSONObject().put("device",id));
                if(!claim.optBoolean("paired")){
                    if(!currentSetup(token))return;
                    session.post("/api/cloud/pair",claim.getJSONObject("config"));
                    boolean paired=false;
                    for(int attempt=0;attempt<40 && currentSetup(token);attempt++){
                        JSONObject progress=cloud.get("/pair/"+claim.getString("claim_id"));
                        if(progress.optBoolean("paired")){paired=true;break;}
                        if(progress.optBoolean("expired"))break;
                        Thread.sleep(1500);
                    }
                    if(!paired)throw new IOException("Chưa liên kết được tài khoản. Kiểm tra Internet của thiết bị và điện thoại. Bluetooth vẫn dùng được.");
                }
                JSONArray rows=cloud.get("/devices").getJSONArray("devices");
                runOnUiThread(()->{
                    if(!currentSetup(token) || !owner.equals(accountId))return;
                    try{deviceStore.replace(owner,rows);}catch(Exception e){finishSetup(token,errorMessage(e));return;}
                    uplinkPasswordForPhone="";if(setupPassword!=null)setupPassword.setText("");
                    disconnectBle();showDevices();
                });
            }catch(Exception error){runOnUiThread(()->finishSetup(token,errorMessage(error)));}
        });
    }
    private void loadBleSettings(LinearLayout content,int token,int viewToken) {
        TextView loading=bodyText("Đang đọc cài đặt từ mạch…");content.addView(loading);
        final EspApi session=api.snapshot(45000);
        io.execute(()->{
            try {
                JSONObject result=session.get("/api/runtime-config");
                RuntimeConfigCodec.Config config=RuntimeConfigCodec.decode(result.getString("data"));
                BleMenuAccess.apply(result,config);
                onControl(token,()->{
                    if(viewToken!=accessViewEpoch)return;
                    content.removeView(loading);deviceAccess=result;runtimeConfig=config;configLoaded=true;
                    buildSettings(content);applyRuntimeConfigToViews();
                });
            }catch(Exception error){onControl(token,()->{
                if(viewToken!=accessViewEpoch)return;content.removeView(loading);showMessage(errorMessage(error));
                Button retry=neutralButton("Đọc lại");content.addView(retry);retry.setOnClickListener(v->showControl(Tab.SETTINGS));
            });}
        });
    }
    private void saveBleSettings(JSONObject values) {
        if(bleSaving){showMessage("Đang lưu cài đặt, vui lòng chờ.");return;}
        bleSaving=true;
        final int token=controlEpoch;final EspApi session=api.snapshot(45000);
        io.execute(()->{
            try{
                if(!currentControl(token))return;
                BleMenuAccess.requireAllowed(session.get("/api/runtime-config"),values);
                if(!currentControl(token))return;
                JSONObject result=session.post("/api/ble/settings",new JSONObject().put("values",values));
                RuntimeConfigCodec.Config config=RuntimeConfigCodec.decode(result.getString("data"));
                BleMenuAccess.apply(result,config);
                onControl(token,()->{runtimeConfig=config;deviceAccess=result;showMessage("Đã lưu xuống mạch. Bộ điều khiển đang khởi động lại.");});
            }catch(Exception error){onControl(token,()->showMessage(errorMessage(error)));}
            finally{runOnUiThread(()->bleSaving=false);}
        });
    }
    private void buildBleDevice(LinearLayout content) {
        LinearLayout box=card();content.addView(box);
        addSectionTitle(box,"Kết nối Bluetooth");
        Button wifi=primaryButton("Thêm Wi-Fi và liên kết tài khoản");box.addView(wifi,fullParams(0,16,0,8));wifi.setOnClickListener(v->beginBleWifi());
        Button leave=neutralButton("Ngắt kết nối");box.addView(leave);
        leave.setOnClickListener(v->{++controlEpoch;stopDashboard();nearbySuppressed=true;disconnectBle();if(accountId.isEmpty())showAuth(false);else showDevices();});
        addBleUnpairButton(box);
    }

    private String rememberedBleAddress() {
        if(bleLink!=null)return bleLink.address();
        if(activeDevice==null)return "";
        for(java.util.Map.Entry<String,?> entry:getSharedPreferences("ble_devices_v1",MODE_PRIVATE).getAll().entrySet())
            if(entry.getKey().matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") && activeDevice.id.equals(entry.getValue()))return entry.getKey();
        return "";
    }
    private void addBleUnpairButton(LinearLayout box) {
        String address=rememberedBleAddress();if(address.isEmpty())return;
        Button forget=neutralButton("Bỏ ghép nối");box.addView(forget,fullParams(0,8,0,0));
        forget.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Bỏ ghép nối trong app?")
                .setMessage("App sẽ quên hộp này và cho bạn chọn hộp khác. Ghép Bluetooth trong cài đặt điện thoại vẫn được giữ.")
                .setNegativeButton("Hủy",null).setPositiveButton("Bỏ ghép nối",(d,w)->forgetBleDevice(address)).show());
    }
    private void forgetBleDevice(String address) {
        android.content.SharedPreferences saved=getSharedPreferences("ble_devices_v1",MODE_PRIVATE);
        android.content.SharedPreferences.Editor change=saved.edit().remove(address).putBoolean("selection_required",true);
        if(address.equals(saved.getString("last_connected_address","")))change.remove("last_connected_address");
        change.apply();
        ++controlEpoch;++setupEpoch;stopHome();stopDashboard();disconnectBle();
        showOfflineDiscovery(true);
    }

    private void showAuth(boolean register) {
        endManualDiscovery();
        stopHome(); stopDashboard(); ++setupEpoch; ++authEpoch;
        screen = Screen.AUTH; setupBusy = false; authBusy = false; registerMode = register;
        lastUiMessage = "";
        LinearLayout content = createPage("", register ? "Tạo tài khoản" : "Đăng nhập", "");

        LinearLayout form=card();content.addView(form);content=form;
        TextView loginLabel=mutedText("Tên đăng nhập");content.addView(loginLabel,fullParams(0,0,0,6));
        accountName = textInput("Tên đăng nhập", false);loginLabel.setLabelFor(View.generateViewId());accountName.setId(loginLabel.getLabelFor()); accountName.setSingleLine(true);
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
                        authBusy = false; acceptAccount(user); finishBleAccountLogin();
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
        if(!register) {
            Button offline=neutralButton("Chế độ offline");
            offline.setTextSize(14);
            offline.setOnClickListener(v->{if(!authBusy && !pendingBleWifi)showOfflineDiscovery();});
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(48));
            size.gravity=Gravity.CENTER_HORIZONTAL;size.topMargin=dp(12);
            content.addView(offline,size);
        }
    }

    private void showOfflineDiscovery() {showOfflineDiscovery(false);}
    private void showOfflineDiscovery(boolean forceSelection) {
        if(screen!=Screen.OFFLINE)offlineReturnHome=screen!=Screen.AUTH && !accountId.isEmpty();
        endManualDiscovery();stopHome();stopDashboard();++authEpoch;++setupEpoch;
        authBusy=false;setupBusy=false;openingDevice=false;screen=Screen.OFFLINE;
        LinearLayout content=createPage("",forceSelection ? "Dò thiết bị mới" : "Chế độ offline","");
        LinearLayout box=card();content.addView(box);
        DeviceSpinView guide=new DeviceSpinView(this,"phone-near-device.gif");
        box.addView(guide,new LinearLayout.LayoutParams(-1,dp(150)));
        addSectionTitle(box,"Đưa điện thoại lại gần hộp");
        addDiscoveryButton(box);
        Button back=neutralButton(offlineReturnHome ? "Quay lại thiết bị" : "Quay lại đăng nhập");
        back.setOnClickListener(v->leaveOfflineDiscovery());content.addView(back,fullParams(0,16,0,0));
        bleChooseManually=forceSelection;bleManualDiscovery=true;nearbySuppressed=false;blePromptAttempted=false;
        updateDiscoveryButton();discoverNearby(true);
    }

    private void leaveOfflineDiscovery(){
        if(offlineReturnHome && !accountId.isEmpty())showDevices();else showAuth(false);
    }

    private EditText accountSecret(LinearLayout content, String hint) {
        TextView secretLabel=mutedText(hint);content.addView(secretLabel,fullParams(0,8,0,2));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Design.FIELD,dp(12)));
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
        temperatureStatus = null; motorSensorIndex = 0;
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
        endManualDiscovery();
        disconnectBle();
        if(accountId.isEmpty()){showAuth(false);return;}
        ++controlEpoch; ++setupEpoch; stopDashboard(); stopHome();
        screen = Screen.HOME; setupBusy = false; openingDevice = false; activeDevice = null;
        clearDeviceState(); api.setExpectedDeviceId(""); api.setCloud(null,""); lastUiMessage = "";
        LinearLayout content = createPage("", "Xin chào, " + username,
                "");
        List<DeviceStore.Device> devices = deviceStore.list(accountId);
        Button signOut=neutralButton("Đăng xuất"); signOut.setOnClickListener(v -> logout());
        LinearLayout accountActions=new LinearLayout(this);accountActions.setGravity(Gravity.END);
        accountActions.addView(signOut);content.addView(accountActions,fullParams(0,0,0,12));
        deviceLabels.clear();
        if (devices.isEmpty()) {
            TextView empty = valueText("Chưa có thiết bị"); empty.setTextSize(19); content.addView(empty);
        } else {
            addSectionTitle(content,"Thiết bị đã ghép");
            for (DeviceStore.Device device : devices) {
                LinearLayout card = card();
                LinearLayout identity=new LinearLayout(this);identity.setGravity(Gravity.CENTER_VERTICAL);
                ImageView badge=icon("device",Design.TEAL,24);badge.setPadding(dp(10),dp(10),dp(10),dp(10));badge.setBackground(rounded(Design.SOFT,dp(12)));
                identity.addView(badge,new LinearLayout.LayoutParams(dp(48),dp(48)));
                TextView name = valueText(device.name); name.setTextSize(19);name.setPadding(dp(12),0,dp(8),0);
                identity.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                identity.addView(icon("arrow",Design.INK,22),new LinearLayout.LayoutParams(dp(22),dp(22)));card.addView(identity);
                if(!device.ssid.isEmpty()) { TextView wifi = mutedText(device.ssid); card.addView(wifi,fullParams(0,5,0,10)); }
                TextView state = bodyText(device.legacy() ? "Cần ghép lại để nhận diện bo" : "Đang kiểm tra kết nối…");
                state.setTextColor(Design.MUTED); state.setTextSize(13); card.addView(state);
                deviceLabels.put(device.id,state);
                card.setContentDescription("Mở " + device.name); card.setOnClickListener(v -> openDevice(device));
                card.setOnLongClickListener(v -> { deviceOptions(device); return true; });
                content.addView(card,fullParams(0,12,0,0));
            }

        }
        Button findNew=primaryButton("Dò thiết bị mới");
        findNew.setOnClickListener(v->showOfflineDiscovery(true));
        content.addView(findNew,fullParams(0,20,0,0));
        Button offline=neutralButton("Chế độ offline");offline.setTextSize(14);
        offline.setOnClickListener(v->showOfflineDiscovery());
        LinearLayout.LayoutParams entry=new LinearLayout.LayoutParams(-2,dp(48));
        entry.gravity=Gravity.CENTER_HORIZONTAL;entry.topMargin=dp(12);
        content.addView(offline,entry);
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
        endManualDiscovery();
        stopNearby();
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
        if (notice != null) notice.dismiss();
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
            if (notice != null) notice.dismiss();
        }
    }

    private void addSetupAction(LinearLayout content, String label, Runnable action) {
        setupActionLabel = label; setupAction = primaryButton(label);
        setupAction.setOnClickListener(v -> { if (!setupBusy) action.run(); });
        content.addView(setupAction, fullParams(0, 20, 0, 0));
    }

    private void showConnectDevice(boolean changing) {
        if(!changing){chooseBleDevice();return;}
        disconnectBle();
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
        LinearLayout content = createPage("BƯỚC 1 / 3", "Kết nối thiết bị",
                "");
        LinearLayout visual = card();
        visual.setGravity(Gravity.CENTER);
        visual.setPadding(dp(24), dp(48), dp(24), dp(48));
        visual.addView(icon("wifi", Design.ACCENT, 76), new LinearLayout.LayoutParams(dp(76), dp(76)));
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
        LinearLayout content = createPage("BƯỚC 2 / 3", "Chọn Wi-Fi",
                "");
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
            Design.WifiSignal signal=new Design.WifiSignal(Design.WifiSignal.level(true,network.optInt("rssi",-70)),Design.TEAL);
            signal.setBounds(0,0,dp(24),dp(24));option.setCompoundDrawables(signal,null,null,null);option.setCompoundDrawablePadding(dp(12));
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
        LinearLayout content = createPage("BƯỚC 3 / 3", "Nhập mật khẩu",
                isOpenNetwork(auth) ? "Mạng không có mật khẩu" : "");
        LinearLayout selectedNetwork=card();addValueLine(selectedNetwork,"Wi-Fi",ssid);
        content.addView(selectedNetwork,fullParams(0,0,0,16));
        if (!isOpenNetwork(auth)) addPasswordField(content, "Mật khẩu Wi-Fi");
        addSetupAction(content, "Kết nối", this::connectUplink);
    }

    private void addPasswordField(LinearLayout content, String hint) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(Design.FIELD, dp(12)));
        content.addView(mutedText(hint),fullParams(0,0,0,6));
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
                        rememberStatus(status);
                        if(bleMode){showMessage("Đang liên kết với tài khoản…");linkBleAccount(token);}
                        else showSwitchPhone();
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
        if(requestCode==BLE_PERMISSION_REQUEST){
            blePromptPending=false;
            for(int result:grantResults)if(result!=PackageManager.PERMISSION_GRANTED){endManualDiscovery();nearbySuppressed=true;return;}
            if(grantResults.length==0){endManualDiscovery();nearbySuppressed=true;return;}
            if(grantResults.length>0){blePromptAttempted=false;mainHandler.postDelayed(()->discoverNearby(false),300);}
            return;
        }
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
        stopNearby();
        stopDashboard();
        ++accessViewEpoch;
        screen = Screen.CONTROL;
        tab = selectedTab;
        String title = tab == Tab.OVERVIEW ? "Tổng quan" : tab == Tab.SETTINGS ? "Cài đặt"
                : tab == Tab.FIRMWARE ? "Cập nhật" : "Thiết bị";
        LinearLayout content = createPage("", title, "");
        addNavigation(content);
        if(tab!=Tab.OVERVIEW) mainHandler.post(unlockPoll);
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
        row.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.setBackgroundColor(Color.WHITE);
        row.setElevation(dp(1));
        addNavButton(row, Tab.OVERVIEW, "Tổng quan", "home");
        addNavButton(row, Tab.SETTINGS, "Cài đặt", "gear");
        if(!bleMode) addNavButton(row, Tab.FIRMWARE, "Cập nhật", "cloud-upload");
        addNavButton(row, Tab.DEVICE, "Thiết bị", "device");
        pageRoot.addView(row, fullParams(0, 0, 0, 0));
    }

    private void addNavButton(LinearLayout row, Tab target, String label, String symbol) {
        boolean selected = target == tab;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(6), dp(2), dp(5));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),
                rounded(selected ? Design.SOFT : Color.WHITE, dp(12)), null));
        item.addView(icon(symbol, selected ? Design.ACCENT : Design.MUTED, 22),
                new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView text = mutedText(label); text.setTextSize(11); text.setGravity(Gravity.CENTER);
        text.setTextColor(selected ? Design.ACCENT : Design.MUTED);
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
        image.setBackground(rounded(Design.SOFT, dp(18)));
        item.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = mutedText(title); label.setGravity(Gravity.CENTER); label.setTextColor(Design.INK);
        item.addView(label, fullParams(0, 8, 0, 0));
        item.setPadding(0, dp(6), 0, dp(6)); item.setOnClickListener(v -> action.run());
        item.setContentDescription(title);
        row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private Button modeAction(LinearLayout row, String title, Runnable action) {
        Button button = neutralButton(title);
        button.setTextSize(14); button.setMinHeight(dp(52)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(10),0,dp(10),0); button.setMaxLines(2); button.setHorizontallyScrolling(false);
        button.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,-2,1);
        if (row.getChildCount() > 0) params.setMargins(dp(12),0,0,0);
        row.addView(button,params); button.setOnClickListener(v -> action.run());
        return button;
    }

    private void paintModeButton(Button button, boolean cool, boolean selected, boolean pending) {
        String title = cool ? "Làm lạnh" : "Xả đá";
        button.setText(title + (pending ? (modeChanging ? "\nĐang gửi…" : "\nĐang xác nhận…") : ""));
        int accent = Design.ACCENT;
        int ink = selected ? Color.WHITE : (button.isEnabled() ? Design.INK : Design.MUTED);
        button.setSelected(selected);
        button.setTextColor(ink);
        button.setBackgroundTintList(null);
        GradientDrawable face=rounded(selected?accent:Color.WHITE,dp(12));
        face.setStroke(dp(1),selected?accent:Design.LINE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),face,null));
        Design.Icon symbol = new Design.Icon(cool ? "snowflake" : "defrost", ink);
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
        if (unlockSending || triggerSaving || modeChanging || pendingMode != null || managedUpdateStarting || !foreground || screen != Screen.CONTROL || tab != Tab.OVERVIEW) return;
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
        // Give automatic focus recovery a home outside the scrolling form. Explicit
        // taps and keyboard/accessibility navigation can still focus its children.
        pageRoot.setFocusableInTouchMode(true);
        pageRoot.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        pageRoot.requestFocus();
        LinearLayout hero = card(); hero.setBackground(new Design.Hero());
        hero.setPadding(dp(16), dp(14), dp(16), dp(14));
        dashboardMode=valueText("Đang chờ dữ liệu");dashboardMode.setTextSize(21);
        dashboardMode.setTypeface(Typeface.DEFAULT_BOLD);dashboardMode.setTextColor(Design.INK);
        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);
        Design.Icon modeIcon=new Design.Icon("snowflake",Design.ACCENT);modeIcon.setBounds(0,0,dp(24),dp(24));
        dashboardMode.setCompoundDrawables(modeIcon,null,null,null);dashboardMode.setCompoundDrawablePadding(dp(10));
        summary.addView(dashboardMode);
        dashboardConnection=bodyText("Đang đọc trạng thái…");
        dashboardConnection.setTextSize(12);dashboardConnection.setTextColor(Design.ACCENT);dashboardConnection.setMaxLines(1);dashboardConnection.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(compactSettings()) {
            LinearLayout statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);
            statusRow.setBaselineAligned(false);statusRow.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
            dashboardConnection.setGravity(Gravity.CENTER);dashboardConnection.setPadding(dp(8),0,0,0);
            statusRow.addView(dashboardConnection,new LinearLayout.LayoutParams(dp(68),-2));hero.addView(statusRow);
        } else {
            hero.addView(summary);hero.addView(dashboardConnection,fullParams(0,8,0,0));
        }
        content.addView(hero, fullParams(0, 0, 0, 10));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setBaselineAligned(false);
        coolModeButton = modeAction(actions, "Làm lạnh", () -> selectMode("COOL"));
        iceModeButton = modeAction(actions, "Xả đá", () -> selectMode("ICE_FLUSH"));
        coolModeButton.setEnabled(false); iceModeButton.setEnabled(false);
        content.addView(actions, fullParams(0, 0, 0, 10));

        unlockButton=primaryButton("Mở khóa");unlockButton.setVisibility(View.GONE);
        unlockButton.setOnClickListener(v -> showUnlockDialog());
        content.addView(unlockButton,fullParams(0,0,0,16));

        LinearLayout timers = new LinearLayout(this); timers.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout green = timerCard("green", 0xFF008978);
        LinearLayout red = timerCard("red", 0xFFBC344C);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMargins(0,0,dp(6),0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.setMargins(dp(6),0,0,0);
        timers.addView(red,left); timers.addView(green,right);
        content.addView(timers, fullParams(0, 0, 0, 10));


        motorSensorIndex = getSharedPreferences("temperature_display",MODE_PRIVATE)
                .getBoolean(motorSensorPreferenceKey(),false) ? 1 : 0;
        buildTriggerCard(content);

        LinearLayout relayCard = card();
        addSectionTitle(relayCard,"Trạng thái đầu ra");
        LinearLayout relays = new LinearLayout(this); relays.setOrientation(LinearLayout.HORIZONTAL);
        for (int i=0;i<3;i++) {
            TextView relay = valueText((i==0?"Nguồn":i==1?"Khởi động":"Xả đá")+"\n—");
            relay.setTextSize(13); relay.setGravity(Gravity.CENTER);
            relay.setLineSpacing(dp(2),1f); relay.setPadding(dp(4),dp(4),dp(4),dp(4));
            relayViews[i]=relay;
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,-2,1);
            rp.setMargins(i==0?0:dp(4),0,i==2?0:dp(4),0); relays.addView(relay,rp);
        }
        relayCard.addView(relays);
        content.addView(relayCard, fullParams(0, 0, 0, 16));
        LinearLayout logs = card(); addSectionTitle(logs,"Nhật ký thiết bị");
        dashboardLogs=bodyText("Đang chờ dữ liệu…");dashboardLogs.setTextColor(Design.INK);dashboardLogs.setTextSize(15);
        dashboardLogs.setLineSpacing(dp(10), 1f);
        logs.addView(dashboardLogs,fullParams(0,8,0,0));
        content.addView(logs);
        renderDashboard(lastStatus,lastDebug);
        renderTemperature(null); // wait for a fresh response, not cached device state
        stopCounting();
        renderHistory();
    }

    private void buildTriggerCard(LinearLayout content) {
        triggerLoaded=false; motorSelectionPending=false; safetyDraftPending=false; defrostDraftPending=false;
        LinearLayout row=new LinearLayout(this);
        LinearLayout cutoff=temperatureCard("Nhiệt độ động cơ",0);
        LinearLayout defrost=temperatureCard("Nhiệt độ giám sát",2);
        for(LinearLayout column : new LinearLayout[]{cutoff,defrost}) {
            View divider=new View(this); divider.setBackgroundColor(Design.LINE);
            LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));
            line.setMargins(0,dp(6),0,dp(4)); column.addView(divider,line);
        }
        LinearLayout safetyHeader=new LinearLayout(this); safetyHeader.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView safetyLabel=valueText("Ngưỡng an toàn"); safetyLabel.setTextSize(13);
        safetyHeader.addView(safetyLabel,new LinearLayout.LayoutParams(0,-2,1));
        safetySwitch=new android.widget.Switch(this); safetySwitch.setShowText(false);
        safetySwitch.setContentDescription("Bật hoặc tắt ngưỡng an toàn");
        styleThresholdSwitch(safetySwitch);
        safetyHeader.addView(safetySwitch,new LinearLayout.LayoutParams(-2,dp(48)));
        cutoff.addView(safetyHeader);
        LinearLayout defrostHeader=new LinearLayout(this); defrostHeader.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView defrostLabel=valueText("Ngưỡng xả đá"); defrostLabel.setTextSize(13);
        defrostHeader.addView(defrostLabel,new LinearLayout.LayoutParams(0,-2,1));
        defrostSwitch=new android.widget.Switch(this); defrostSwitch.setShowText(false);
        defrostSwitch.setContentDescription("Bật hoặc tắt ngưỡng xả đá");
        styleThresholdSwitch(defrostSwitch);
        defrostHeader.addView(defrostSwitch,new LinearLayout.LayoutParams(-2,dp(48)));
        defrost.addView(defrostHeader);
        defrostSwitch.setOnCheckedChangeListener((button,checked) -> {
            if(triggerLoaded) { defrostDraftPending=true; renderTrigger(lastStatus,lastDebug); }
        });

        safetySwitch.setOnCheckedChangeListener((button,checked) -> {
            if(triggerLoaded) { safetyDraftPending=true; renderTrigger(lastStatus,lastDebug); }
        });
        triggerThreshold=textInput("Nhập ngưỡng",false);
        defrostThreshold=textInput("Nhập ngưỡng",false);
        int input=InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        triggerThreshold.setInputType(input); defrostThreshold.setInputType(input);
        cutoff.addView(new NumberStepper(this,triggerThreshold,"Ngưỡng an toàn",() -> new int[]{-55,125},true),fullParams(0,8,0,8));
        defrost.addView(new NumberStepper(this,defrostThreshold,"Ngưỡng xả đá",() -> new int[]{-55,125},true),fullParams(0,8,0,8));
        triggerState=mutedText("Đang chờ dữ liệu…"); cutoff.addView(triggerState);
        defrostState=mutedText(""); defrostState.setVisibility(View.GONE); defrost.addView(defrostState);
        boolean sideBySide=compactSettings();
        row.setOrientation(sideBySide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(sideBySide?0:-1,sideBySide?-1:-2,sideBySide?1:0);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(sideBySide?0:-1,sideBySide?-1:-2,sideBySide?1:0);
        if(sideBySide){left.rightMargin=dp(6);right.leftMargin=dp(6);}else right.topMargin=dp(12);
        row.addView(cutoff,left);row.addView(defrost,right);
        content.addView(row,fullParams(0,6,0,12));
        triggerSave=primaryButton("Lưu");triggerSave.setEnabled(false);
        triggerSave.setOnClickListener(v -> saveTrigger());
        content.addView(triggerSave,fullParams(0,0,0,16));
    }

    private void styleThresholdSwitch(android.widget.Switch toggle) {
        toggle.setThumbDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        toggle.setTrackDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        toggle.setSwitchMinWidth(0);toggle.setSwitchPadding(0);toggle.setMinWidth(dp(48));
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,new Design.Toggle(getResources().getDisplayMetrics().density),null);
    }

    private void renderTrigger(JSONObject status, JSONObject debug) {
        if(triggerSave==null) return;
        releaseOffscreenDashboardFocus();
        JSONObject trigger=status==null ? null : status.optJSONObject("trigger");
        boolean supported=trigger!=null && trigger.optBoolean("enabled") && trigger.optBoolean("thermal_supported");
        boolean transportSupported=!bleMode || (status!=null && status.optBoolean("ble_thermal_write_supported"));
        if(supported && !triggerLoaded && !triggerSaving) {
            boolean configured=trigger.optBoolean("thermal_enabled");
            if(configured) motorSensorIndex="20K".equals(trigger.optString("source")) ? 1 : 0;
            triggerThreshold.setText(java.math.BigDecimal.valueOf(trigger.optInt("threshold_centi",2500),2).stripTrailingZeros().toPlainString());
            defrostThreshold.setText(configured ? java.math.BigDecimal.valueOf(trigger.optInt("defrost_centi"),2).stripTrailingZeros().toPlainString() : "");
            safetySwitch.setChecked(trigger.optBoolean("safety_enabled"));
            defrostSwitch.setChecked(trigger.optBoolean("defrost_enabled",configured));
            defrostDraftPending=false; safetyDraftPending=false; triggerLoaded=true; motorSelectionPending=false; renderTemperatureCards();
        }
        boolean safetySupported=supported && trigger.optBoolean("safety_supported");
        boolean canEdit=supported && transportSupported && triggerLoaded && !triggerSaving && !modeChanging && pendingMode==null && !managedUpdateStarting;
        safetySwitch.setEnabled(canEdit && safetySupported);
        ((NumberStepper)triggerThreshold.getParent()).setEnabled(canEdit && safetySupported && safetySwitch.isChecked());
        defrostSwitch.setEnabled(canEdit && trigger.has("defrost_enabled"));
        ((NumberStepper)defrostThreshold.getParent()).setEnabled(canEdit && defrostSwitch.isChecked());
        if(motorSensorSwitch!=null) motorSensorSwitch.setEnabled(!triggerSaving && !modeChanging && pendingMode==null && !managedUpdateStarting);
        triggerSave.setEnabled(canEdit && !status.optBoolean("busy",true));
        mainHandler.removeCallbacks(expireTriggerControls);
        if(triggerSave.isEnabled()) mainHandler.postDelayed(expireTriggerControls,5000);
        if(triggerSaving) setInlineStatus(triggerState,"Đang lưu…");
        else if(!transportSupported && status!=null) setInlineStatus(triggerState,"Cập nhật hộp để lưu ngưỡng qua Bluetooth.");
        else if(!supported) setInlineStatus(triggerState,"Đang chờ dữ liệu hoặc cần cập nhật thiết bị.");
        else if(safetyDraftPending) setInlineStatus(triggerState,"Chưa lưu");
        else if(!safetySupported) setInlineStatus(triggerState,"Cần cập nhật thiết bị để dùng ngưỡng an toàn.");
        else if(motorSelectionPending) setInlineStatus(triggerState,"Chưa lưu");
        else if(!trigger.optBoolean("thermal_enabled")) setInlineStatus(triggerState,"Chưa lưu");
        else if(!trigger.optBoolean("safety_enabled")) setInlineStatus(triggerState,"");
        else if(!trigger.optBoolean("synced")) setInlineStatus(triggerState,"Đang chờ bộ điều khiển xác nhận.");
        else if(!trigger.optBoolean("sensor_valid")) setInlineStatus(triggerState,"Kiểm tra cảm biến động cơ");
        else setInlineStatus(triggerState,"");
        if(triggerSaving) setInlineStatus(defrostState,"Đang lưu…");
        else if(!transportSupported && status!=null) setInlineStatus(defrostState,"Cập nhật hộp để lưu ngưỡng qua Bluetooth.");
        else if(!supported) setInlineStatus(defrostState,"Đang chờ dữ liệu hoặc cần cập nhật thiết bị.");
        else if(defrostDraftPending) setInlineStatus(defrostState,"Chưa lưu");
        else if(!trigger.has("defrost_enabled")) setInlineStatus(defrostState,"Cần cập nhật thiết bị để bật/tắt ngưỡng.");
        else if(!trigger.optBoolean("thermal_enabled")) setInlineStatus(defrostState,"Chưa lưu");
        else if(!trigger.optBoolean("defrost_enabled")) setInlineStatus(defrostState,"");
        else if(!trigger.optBoolean("synced")) setInlineStatus(defrostState,"Đang chờ bộ điều khiển xác nhận.");
        else if(!trigger.optBoolean("defrost_valid")) setInlineStatus(defrostState,"Kiểm tra cảm biến giám sát");
        else setInlineStatus(defrostState,"");
    }

    private void setInlineStatus(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int readTemperatureLimit(EditText field) {
        try {
            int value=new java.math.BigDecimal(field.getText().toString().trim().replace(',','.')).movePointRight(2).intValueExact();
            if(value < -5500 || value > 12500) throw new IllegalArgumentException();
            field.setError(null);
            return value;
        } catch(Exception invalid) {
            field.setError("Nhập từ -55 đến 125 °C, tối đa 2 số lẻ.");
            throw new IllegalArgumentException();
        }
    }
    private void saveTrigger() {
        if(triggerSaving || modeChanging || pendingMode!=null || managedUpdateStarting || !triggerLoaded || triggerSave==null || !triggerSave.isEnabled() || !foreground || screen!=Screen.CONTROL || tab!=Tab.OVERVIEW) return;
        final int cutoff, defrost;
        try {
            cutoff=readTemperatureLimit(triggerThreshold);
            // An unused blank limit must not prevent switching off monitoring.
            defrost=!defrostSwitch.isChecked() && defrostThreshold.getText().toString().trim().isEmpty()
                    ? 0 : readTemperatureLimit(defrostThreshold);
        }
        catch(IllegalArgumentException invalid) { return; }
        final boolean safety=safetySwitch.isChecked(), defrostEnabled=defrostSwitch.isChecked();
        JSONObject storedTrigger=lastStatus==null ? null : lastStatus.optJSONObject("trigger");
        final boolean supportsDefrostToggle=storedTrigger!=null && storedTrigger.has("defrost_enabled");
        final int selected=motorSensorIndex+1, token=controlEpoch;
        final EspApi session=api.snapshot(15000);
        triggerSaving=true; renderTrigger(lastStatus,lastDebug);renderModeControls(lastStatus,lastDebug);
        io.execute(() -> {
            if(!currentControl(token)) return;
            try {
                JSONObject request=new JSONObject().put("source",TRIGGER_SOURCES[selected])
                        .put("threshold_centi",cutoff).put("defrost_centi",defrost).put("safety_enabled",safety);
                if(supportsDefrostToggle) request.put("defrost_enabled",defrostEnabled);
                session.post("/api/thermal",request);
                onControl(token,() -> { triggerSaving=false;triggerLoaded=false;showMessage("Đã lưu ngưỡng nhiệt độ.");refreshDashboard(); });
            } catch(Exception error) {
                onControl(token,() -> { triggerSaving=false;renderTrigger(lastStatus,lastDebug);renderModeControls(lastStatus,lastDebug);showMessage("Chưa xác nhận lưu. "+errorMessage(error));refreshDashboard(); });
            }
        });
    }

    private LinearLayout temperatureCard(String label, int sensorIndex) {
        LinearLayout box = card(); box.setPadding(dp(10),dp(12),dp(10),dp(12));
        TextView title = mutedText(label); title.setTextSize(14); title.setTextColor(Design.INK);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        // The motor title has less width beside the swap button; reserve the same
        // two-line title area in both cards so readings and dividers stay aligned.
        title.setMinLines(getResources().getConfiguration().fontScale>=1.3f?2:1);
        android.widget.FrameLayout heading = new android.widget.FrameLayout(this);
        LinearLayout sensorName=new LinearLayout(this);sensorName.setOrientation(LinearLayout.VERTICAL);sensorName.addView(title);
        TextView name=mutedText(sensorIndex==0?(motorSensorIndex==0?"10k":"20k"):"50k");name.setTextSize(12);
        sensorName.addView(name,fullParams(0,2,48,0));heading.addView(sensorName,new android.widget.FrameLayout.LayoutParams(-1,-2));
        if (sensorIndex==0) {
            motorSensorLabel = name;
            motorSensorSwitch = new android.widget.ImageButton(this);
            motorSensorSwitch.setImageDrawable(new Design.Icon("swap",Design.TEAL));
            motorSensorSwitch.setPadding(dp(13),dp(25),dp(13),dp(1));
            motorSensorSwitch.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),rounded(Color.TRANSPARENT,dp(10)),null));
            motorSensorSwitch.setContentDescription(motorSensorIndex==0 ? "Đổi sang cảm biến 20k" : "Đổi sang cảm biến 10k");
            motorSensorSwitch.setOnClickListener(v -> {
                if(triggerSaving || modeChanging || pendingMode!=null || managedUpdateStarting) return;
                motorSensorIndex = motorSensorIndex==0 ? 1 : 0;
                motorSelectionPending=true;
                if(triggerState!=null) setInlineStatus(triggerState,"Chưa lưu");
                getSharedPreferences("temperature_display",MODE_PRIVATE).edit()
                        .putBoolean(motorSensorPreferenceKey(),motorSensorIndex==1).apply();
                renderTemperatureCards();
            });
            heading.addView(motorSensorSwitch,new android.widget.FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END|Gravity.BOTTOM));
        }
        heading.setMinimumHeight(dp(48));box.addView(heading);
        TextView value = valueText("— °C"); value.setTextSize(30); value.setTypeface(Typeface.DEFAULT_BOLD);value.setSingleLine(true);
        if(Build.VERSION.SDK_INT>=26) value.setAutoSizeTextTypeUniformWithConfiguration(14,30,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(value, fullParams(0,2,0,2));
        TextView note = mutedText("Đang chờ dữ liệu"); note.setTextSize(12);
        box.addView(note);
        if (sensorIndex==2) { temperature50Value = value; temperature50Note = note; }
        else { temperatureValue = value; temperatureNote = note; }
        return box;
    }

    private String motorSensorPreferenceKey() {
        return accountId + ":" + (activeDevice==null ? "" : activeDevice.id);
    }

    private LinearLayout timerCard(String channel, int color) {
        LinearLayout card = card();
        GradientDrawable face=rounded(Color.WHITE,dp(16));
        face.setStroke(dp(1),Design.LINE);card.setBackground(face);
        TextView label = mutedText("green".equals(channel) ? "Thời gian hoạt động" : "Thời gian chờ");
        label.setTextSize(13); label.setTextColor(Design.INK); label.setTypeface(Typeface.DEFAULT_BOLD);
        if(getResources().getConfiguration().fontScale>=1.3f)label.setMinLines(2);
        card.setPadding(dp(12),dp(10),dp(12),dp(10));
        card.addView(label, fullParams(0,0,0,0));
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
                // Temperature comes from C5; do not wait for the separate MS51
                // debug/history requests before showing a fresh sensor sample.
                final JSONObject sensorStatus=status;
                onControl(controlToken,()->{
                    if(epoch==dashboardEpoch && foreground && screen==Screen.CONTROL && tab==Tab.OVERVIEW){
                        rememberStatus(sensorStatus);renderTemperature(sensorStatus);renderTrigger(sensorStatus,lastDebug);
                    }
                });
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
                    updateUnlock(finalDebug);
                } else if (dashboardConnection != null) {
                    updateUnlock(null);
                    renderDashboard(finalStatus, null);
                    dashboardMode.setText("Mất kết nối");
                    dashboardConnection.setText("Chưa đọc được thiết bị: " + errorMessage(finalFailure));
                }
                mainHandler.postDelayed(dashboardRefresh, 1000);
            });
        });
    }

    private void pollUnlock() {
        if(!foreground || screen!=Screen.CONTROL || tab==Tab.OVERVIEW)return;
        final int token=controlEpoch, view=dashboardEpoch;
        final EspApi session=api.snapshot(15000);
        io.execute(() -> {
            JSONObject result=null;
            try { if(currentControl(token))result=session.get("/api/debug"); } catch(Exception ignored) { }
            final JSONObject snapshot=result;
            onControl(token,() -> {
                if(!foreground||view!=dashboardEpoch||screen!=Screen.CONTROL||tab==Tab.OVERVIEW)return;
                updateUnlock(snapshot);mainHandler.postDelayed(unlockPoll,2500);
            });
        });
    }

    private void updateUnlock(JSONObject debug) {
        if(unlockDeviceEpoch!=controlEpoch){unlockDeviceEpoch=controlEpoch;unlockPrompted=-1;}
        int next=UnlockState.read(debug);
        unlockState=next;
        mainHandler.removeCallbacks(expireUnlock);
        if(next>=0)mainHandler.postDelayed(expireUnlock,3500);
        if(unlockButton!=null){
            unlockButton.setVisibility(next>0?View.VISIBLE:View.GONE);
            unlockButton.setText(next==3?"Thiết bị đang khóa":"Mở khóa");
            unlockButton.setEnabled((next==1||next==2)&&!unlockSending);
        }
        if(unlockSending)return;
        if(next==0){unlockPrompted=-1;if(unlockDialog!=null)unlockDialog.dismiss();unlockDialog=null;return;}
        if(next<0 || next==3){
            if(unlockDialog!=null)unlockDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            return;
        }
        if(!foreground || screen!=Screen.CONTROL || managedUpdateStarting || modeChanging || triggerSaving)return;
        if(unlockDialog!=null && unlockPrompted!=next){unlockDialog.dismiss();unlockDialog=null;}
        if(unlockDialog!=null)unlockDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        if(unlockDialog==null && unlockPrompted!=next)showUnlockDialog();
    }

    private void showUnlockDialog() {
        if(unlockSending || unlockDialog!=null || (unlockState!=1 && unlockState!=2) || !foreground)return;
        final int kind=unlockState,token=controlEpoch;
        unlockPrompted=kind;
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),0);
        TextView note=bodyText(kind==2?"Nhập mật khẩu dự phòng của bộ điều khiển.":"Bộ điều khiển đang yêu cầu mật khẩu. Nhập mật khẩu để tiếp tục.");
        body.addView(note);
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(kind==2?"Mật khẩu dự phòng":"Mật khẩu");
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        input.setSaveEnabled(false);input.setTextSize(18);input.setTextColor(Design.INK);
        input.setBackground(rounded(0xFFEEF3F6,dp(14)));input.setPadding(dp(16),dp(14),dp(16),dp(14));
        body.addView(input,fullParams(0,16,0,0));
        TextView error=bodyText("");error.setTextColor(0xFFBF4057);body.addView(error);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Mở khóa thiết bị")
            .setView(body).setNegativeButton("Để sau",(d,w)->{}).setPositiveButton("Mở khóa",null).create();
        unlockDialog=dialog;
        dialog.setOnDismissListener(d->{input.setText("");if(unlockDialog==dialog)unlockDialog=null;});
        dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(rounded(Color.WHITE,dp(24)));
        dialog.getButton(-1).setTextColor(Design.TEAL);dialog.getButton(-2).setTextColor(Design.MUTED);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(unlockSending || token!=controlEpoch || unlockState!=kind || !foreground)return;
            String text=input.getText().toString();
            if(!text.matches("[0-9]{1,3}")){error.setText("Nhập mật khẩu từ 0 đến 999.");return;}
            final int pin=Integer.parseInt(text);
            final EspApi session=api.snapshot(45000);
            unlockSending=true;input.setText("");input.setEnabled(false);error.setText("Đang mở khóa…");
            dialog.setCancelable(false);dialog.getButton(-1).setEnabled(false);dialog.getButton(-2).setEnabled(false);
            io.execute(() -> {
                String failure=null;
                try { if(!currentControl(token))return;session.post("/api/unlock",new JSONObject().put("kind",kind).put("pin",pin)); }
                catch(Exception e){failure=errorMessage(e);}
                finally { final String result=failure;mainHandler.post(() -> {
                    unlockSending=false;
                    if(!currentControl(token))return;
                    if(result==null){if(unlockDialog==dialog)dialog.dismiss();showMessage("Đã mở khóa thiết bị.");}
                    else if(unlockDialog==dialog){
                        input.setEnabled(true);dialog.setCancelable(true);dialog.getButton(-2).setEnabled(true);
                        error.setText(result+" Đang đọc lại trạng thái thiết bị…");
                    }
                    expireUnlock.run();
                    if(foreground){if(tab==Tab.OVERVIEW)refreshDashboard();else {mainHandler.removeCallbacks(unlockPoll);mainHandler.post(unlockPoll);}}
                }); }
            });
        });
    }

    private void renderDashboard(JSONObject status, JSONObject debug) {
        if (dashboardConnection == null) return;
        releaseOffscreenDashboardFocus();
        renderTemperature(status);
        renderModeControls(status, debug);
        renderTrigger(status, debug);
        JSONObject uplink = getUplink(status);
        String ssid = uplink.optString("ssid", uplinkSsid);
        String network = ssid.isEmpty() ? "Chưa nhận Wi‑Fi" : ssid;
        dashboardConnection.setText(network);
        Object signal = uplink.opt("rssi");
        int rssi = signal instanceof Number ? ((Number) signal).intValue() : 0;
        int level = Design.WifiSignal.level("connected".equals(uplink.optString("state")), rssi);
        Design.WifiSignal wifiIcon = new Design.WifiSignal(level, Design.ACCENT);
        wifiIcon.setBounds(0, 0, dp(22), dp(22));
        dashboardConnection.setCompoundDrawables(compactSettings()?null:wifiIcon, compactSettings()?wifiIcon:null, null, null);
        dashboardConnection.setCompoundDrawablePadding(dp(8));
        dashboardConnection.setContentDescription(network + (level > 0
                ? ", tín hiệu " + level + " trên 4 vạch, " + rssi + " dBm"
                : ", chưa có dữ liệu tín hiệu Wi-Fi"));

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
        Design.Icon modeIcon=new Design.Icon("ICE_FLUSH".equalsIgnoreCase(mode) || "DEFROST".equalsIgnoreCase(mode)?"defrost":"snowflake",Design.ACCENT);
        modeIcon.setBounds(0,0,dp(24),dp(24));dashboardMode.setCompoundDrawables(modeIcon,null,null,null);
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

    private void releaseOffscreenDashboardFocus() {
        View focused=getCurrentFocus();
        if(focused==null || focused==pageRoot || pageRoot==null) return;
        // TextView.setText can request that an old cursor/selection be brought
        // back onscreen on the next layout. Never follow an offscreen cursor
        // during telemetry/history/late-save updates; keep visible editing intact.
        android.graphics.Rect visible=new android.graphics.Rect();
        if(!focused.getGlobalVisibleRect(visible) || visible.height()<focused.getHeight()) pageRoot.requestFocus();
    }

    private void renderTemperature(JSONObject status) {
        temperatureStatus = status;
        temperatureStatusAt = android.os.SystemClock.elapsedRealtime();
        renderTemperatureCards();
    }

    private void renderTemperatureCards() {
        JSONObject status = android.os.SystemClock.elapsedRealtime()-temperatureStatusAt > 2000 ? null : temperatureStatus;
        if (motorSensorLabel != null) motorSensorLabel.setText(motorSensorIndex==0 ? "10k" : "20k");
        if (motorSensorSwitch != null) motorSensorSwitch.setContentDescription(motorSensorIndex==0
                ? "Đổi sang cảm biến 20k" : "Đổi sang cảm biến 10k");
        renderSensor(status == null ? null : status.optJSONObject(motorSensorIndex==0 ? "analog" : "analog20k"),
                temperatureValue, temperatureNote, motorSensorIndex);
        renderSensor(status == null ? null : status.optJSONObject("analog50k"), temperature50Value, temperature50Note, 2);
    }

    private void renderSensor(JSONObject input, TextView temperatureValue, TextView temperatureNote, int sensorIndex) {
        if (temperatureValue == null) return;
        temperatureValue.setText("— °C"); temperatureValue.setTextColor(Design.MUTED);
        temperatureNote.setVisibility(View.VISIBLE);temperatureNote.setText("Đang chờ dữ liệu");
        if (input == null) return;
        if (!input.optBoolean("enabled")) {
            temperatureNote.setText("Thiết bị chưa hỗ trợ cảm biến này"); return;
        }
        int gpio = input.optInt("gpio", -1);
        if ((sensorIndex==2 ? gpio != 4 : sensorIndex==1 ? (gpio != 5 && gpio != 6) : (gpio != 1 && gpio != 5 && gpio != 6)) || !input.optBoolean("valid") || input.isNull("age_ms")) return;
        long age = input.optLong("age_ms", -1);
        long elapsed = android.os.SystemClock.elapsedRealtime()-temperatureStatusAt;
        if (age < 0 || age > 2000 || elapsed > 2000-age) return;
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
        temperatureNote.setText("Cảm biến đang kết nối");temperatureNote.setVisibility(View.GONE);
    }

    private void renderHistory() {
        if (dashboardLogs == null) return;
        String text = DeviceHistory.text(lastHistory);
        if (!historyError.isEmpty()) text = (lastHistory == null ? "Chưa đọc được nhật ký." : "Dữ liệu lần đọc trước:\n" + text)
                + "\n\nChưa làm mới: " + historyError;
        if(!android.text.TextUtils.equals(dashboardLogs.getText(),text)) {
            releaseOffscreenDashboardFocus();
            dashboardLogs.setText(text);
        }
    }

    private void setRelay(TextView relay, int number, String rawValue) {
        if (relay == null) return;
        boolean known = rawValue != null && !"null".equals(rawValue);
        boolean on = known && ("on".equalsIgnoreCase(rawValue) || "1".equals(rawValue) || "true".equalsIgnoreCase(rawValue));
        String name = number == 1 ? "Nguồn" : number == 2 ? "Khởi động" : "Xả đá";
        relay.setText(name + "\n" + (known ? (on ? "Bật" : "Tắt") : "—"));
        Design.Icon symbol = new Design.Icon(number==1?"power":number==2?"fan":"defrost", on ? Design.TEAL : Design.MUTED);
        symbol.setBounds(0, 0, dp(30), dp(30));
        relay.setCompoundDrawables(null, symbol, null, null);
        relay.setCompoundDrawablePadding(dp(4));
        relay.setContentDescription(name + (known ? (on ? " đang bật" : " đang tắt") : " đang chờ dữ liệu"));
        relay.setTextColor(on ? Design.TEAL : Design.MUTED);
        relay.setBackground(rounded(Color.TRANSPARENT, dp(14)));
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
        intro.setBackgroundColor(Color.TRANSPARENT);intro.setPadding(0,0,0,0);
        LinearLayout topRow = new LinearLayout(this); topRow.setOrientation(LinearLayout.HORIZONTAL);
        Button read = neutralButton("Đọc cài đặt");
        read.setOnClickListener(v -> loadRuntimeConfig());
        Button save = primaryButton("Lưu cài đặt");
        if(bleMode && !deviceAccess.optBoolean("settings_write_supported")){save.setEnabled(false);addHelp(intro,"Cần cập nhật MS51 để lưu qua Bluetooth. Bạn vẫn xem được dữ liệu hiện tại.");}
        save.setOnClickListener(v -> saveRuntimeConfig());
        topRow.addView(read, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); saveParams.setMargins(dp(5), 0, 0, 0);
        topRow.addView(save, saveParams);
        intro.addView(topRow, fullParams(0, 0, 0, 0));
        content.addView(intro, fullParams(0, 0, 0, 0));

        LinearLayout timing = settingsSection("Thời gian hoạt động");
        addNumberField(timing, "Làm lạnh CL (phút)", "coolCl");
        addNumberField(timing, "Làm lạnh OP (phút)", "coolOp");
        finishSettingsRow(timing);
        addNumberField(timing, "Xả đá CL (phút)", "defrostCl");
        addNumberField(timing, "Xả đá OP (phút / giây)", "defrostOp");
        finishSettingsRow(timing);
        content.addView(timing, fullParams(0,12,0,0));

        LinearLayout advanced = settingsSection("Cài đặt nâng cao");
        LinearLayout startup = new LinearLayout(this); startup.setOrientation(LinearLayout.VERTICAL);
        addNumberField(startup, "Thời gian chờ ST (100 ms)", "delaySt");
        addNumberField(startup, "Thời gian HCF OP", "hcfTime");
        finishSettingsRow(startup);
        addNumberField(startup, "ST LED (100 ms)", "stLed");
        addNumberField(startup, "ST LCD (100 ms)", "stLcd");
        finishSettingsRow(startup);
        addSettingsSubgroup(advanced,"Khởi động",startup);

        LinearLayout modes=new LinearLayout(this); modes.setOrientation(LinearLayout.VERTICAL);
        addChoiceField(modes, "Bắt đầu làm lạnh", "coolStart", choices("CL",0,"OP",1));
        addChoiceField(modes, "Bắt đầu xả đá", "defrostStart", choices("CL",0,"OP",1));
        finishSettingsRow(modes);
        addChoiceField(modes,"Kiểu màn hình","displayMode",choices("LCD",0,"LED",1));
        addChoiceField(modes,"Chế độ HCF","hcfMode",choices("H",0,"CF",1));
        finishSettingsRow(modes);
        addChoiceField(modes, "Chế độ DF", "df", choices("Tắt",0,"Bật",1));
        addChoiceField(modes, "Chế độ END", "end", choices("Tắt",0,"Bật",1));
        finishSettingsRow(modes);
        addChoiceField(modes, "Chế độ HDF", "hdf", choices("Tắt",0,"Bật",1));
        addNumberField(modes, "Trễ bật xả đá", "delayDf");
        finishSettingsRow(modes);
        addChoiceField(modes, "Số lần bật nguồn và khởi động", "onTime", choices("Nguồn: 1 lần · Khởi động: 1 lần",0,"Nguồn: 2 lần · Khởi động: 1 lần",1,"Nguồn: 1 lần · Khởi động: 2 lần",2,"Nguồn: 2 lần · Khởi động: 2 lần",3));
        addSettingsSubgroup(advanced,"Chu trình",modes);

        LinearLayout display=new LinearLayout(this); display.setOrientation(LinearLayout.VERTICAL);
        addNumberField(display,"Độ sáng LED đỏ","redBrightness");
        addNumberField(display,"Độ sáng LED xanh","greenBrightness");
        finishSettingsRow(display);
        addNumberField(display,"Tự khóa","lockTime");
        addChoiceField(display,"Số lần chạm","touch",choices("1",0,"2",1));
        addCheckField(display,"Hiện chế độ DF","showDf",false);
        addCheckField(display,"Hiện chế độ END","showEnd",false);
        addCheckField(display,"Hiện chế độ SL","showSl",false);
        addCheckField(display,"Hiện chế độ HCF","showHcf",false);
        addCheckField(display,"Hiện chế độ HDF","showHdf",false);
        addSettingsSubgroup(advanced,"Hiển thị và thao tác",display);

        LinearLayout retry=new LinearLayout(this); retry.setOrientation(LinearLayout.VERTICAL);
        addNumberField(retry,"Thời gian thử lại","tryTime");
        addChoiceField(retry,"Đơn vị thử lại","tryUnit",choices("Giờ",0,"Ngày",1));
        addSettingsSubgroup(advanced,"Thử lại",retry);
        LinearLayout ranges=new LinearLayout(this);ranges.setOrientation(LinearLayout.VERTICAL);
        addRangeFields(ranges, "Làm lạnh CL", "rangeCoolCl");
        addRangeFields(ranges, "Làm lạnh OP", "rangeCoolOp");
        addRangeFields(ranges, "Xả đá CL", "rangeDefrostCl");
        addRangeFields(ranges, "Xả đá OP", "rangeDefrostOp");
        addRangeFields(ranges, "ST LCD", "rangeStLcd");
        addRangeFields(ranges, "ST LED", "rangeStLed");
        addSettingsSubgroup(advanced,"Giới hạn thời gian (Min / Max)",ranges);

        LinearLayout pin=new LinearLayout(this);pin.setOrientation(LinearLayout.VERTICAL);
        CheckBox pinEnable = addCheckField(pin, "Bật mật khẩu", "pinEnable", true);
        addPinNumberField(pin, "Mật khẩu", "pinPassword");
        addPinNumberField(pin, "Mật khẩu dự phòng", "pinBackupPassword");
        finishSettingsRow(pin);
        addPinNumberField(pin, "Chu kỳ yêu cầu mật khẩu", "pinPeriod");
        addPinChoiceField(pin, "Đơn vị chu kỳ", "pinPeriodUnit", choices("Giờ", 0, "Ngày", 1));
        finishSettingsRow(pin);
        addPinNumberField(pin, "Thời gian dự phòng", "pinBackupPeriod");
        addPinChoiceField(pin, "Đơn vị dự phòng", "pinBackupPeriodUnit", choices("Giờ", 0, "Ngày", 1));
        finishSettingsRow(pin);
        addPinNumberField(pin, "Số lần nhập sai tối đa", "pinWrongLimit");
        if (settingVisible("pinPeriodShow")) {
            LinearLayout periodCell=settingCell(pin,"",false);
            periodCell.removeViewAt(0);
            CheckBox periodShow=addCheckField(periodCell,"Hiện phần chỉnh chu kỳ mật khẩu","pinPeriodShow",true);
            periodShow.setTextSize(13);
            if (compactSettings()) periodShow.setMinHeight(dp(100));
        }
        finishSettingsRow(pin);
        pinEnable.setOnCheckedChangeListener((buttonView, isChecked) -> syncPinControls());
        addSettingsSubgroup(advanced,"Mật khẩu bảo vệ",pin);
        TextView advancedTitle=(TextView)advanced.getChildAt(0);
        advancedTitle.setMinHeight(dp(40));advancedTitle.setGravity(Gravity.CENTER_VERTICAL);
        advancedTitle.setClickable(true);advancedTitle.setFocusable(true);
        Runnable paintAdvanced=()->{
            boolean open=!expandedSettings.contains("advanced-collapsed");
            for(int i=1;i<advanced.getChildCount();i++)advanced.getChildAt(i).setVisibility(open?View.VISIBLE:View.GONE);
            Design.Icon arrow=new Design.Icon(open?"chevron-down":"chevron-right",Design.ACCENT);
            arrow.setBounds(0,0,dp(20),dp(20));advancedTitle.setCompoundDrawables(null,null,arrow,null);
            advancedTitle.setContentDescription("Cài đặt nâng cao, "+(open?"thu gọn":"mở rộng"));
        };
        advancedTitle.setOnClickListener(v->{
            if(!expandedSettings.remove("advanced-collapsed")){expandedSettings.add("advanced-collapsed");stopNumericRepeat(advanced);}
            paintAdvanced.run();
        });paintAdvanced.run();
        content.addView(advanced,fullParams(0,12,0,0));
        for (LinearLayout section : new LinearLayout[]{timing, advanced}) {
            if (!hasSettingControls(section)) section.setVisibility(View.GONE);
        }
        applyRuntimeConfigToViews();
    }

    private LinearLayout settingsSection(String title) {
        LinearLayout section=card(); addSectionTitle(section,title); return section;
    }

    private void addSettingsSubgroup(LinearLayout parent,String title,LinearLayout fields) {
        if(!hasSettingControls(fields)) return;
        Button toggle=neutralButton(title);toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));toggle.setTextColor(Design.INK);toggle.setBackgroundTintList(null);
        toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),rounded(Color.TRANSPARENT,dp(12)),null));
        toggle.setTag("settings-group:"+title);
        Runnable paint=() -> {
            boolean open=expandedSettings.contains(title);
            fields.setVisibility(open?View.VISIBLE:View.GONE);
            Design.Icon arrow=new Design.Icon(open?"chevron-up":"chevron-down",Design.TEAL);
            arrow.setBounds(0,0,dp(20),dp(20));toggle.setCompoundDrawables(null,null,arrow,null);
            toggle.setContentDescription(title+(open?", đang mở, thu gọn":", đang thu gọn, mở rộng"));
            if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(open?"Đã mở rộng":"Đã thu gọn");
        };
        toggle.setOnClickListener(v -> {
            if(expandedSettings.contains(title)) {
                stopNumericRepeat(fields);fields.clearFocus();toggle.requestFocus();expandedSettings.remove(title);
            } else expandedSettings.add(title);
            paint.run();
        });
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background=rounded(Color.WHITE,dp(12));background.setStroke(dp(1),Design.LINE);
        group.setBackground(background);
        fields.setPadding(dp(8),0,dp(8),dp(10));
        group.addView(toggle,fullParams(0,0,0,0));group.addView(fields);
        parent.addView(group,fullParams(0,8,0,0));paint.run();
    }

    private void stopNumericRepeat(View view) {
        if(view instanceof NumberStepper)((NumberStepper)view).stopRepeating();
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)stopNumericRepeat(((ViewGroup)view).getChildAt(i));
    }

    private boolean hasSettingControls(View view) {
        if(view instanceof EditText || view instanceof Spinner || view instanceof CheckBox) return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) if(hasSettingControls(group.getChildAt(i))) return true;
        }
        return false;
    }

    private boolean compactSettings() {
        android.content.res.Configuration configuration=getResources().getConfiguration();
        return configuration.screenWidthDp>=360 && configuration.fontScale<1.3f;
    }

    private void finishSettingsRow(LinearLayout parent) {
        if(parent.getChildCount()>0) parent.getChildAt(parent.getChildCount()-1).setTag(null);
    }

    private LinearLayout settingCell(LinearLayout parent,String label,boolean wide) {
        boolean compact=compactSettings() && !wide;
        LinearLayout row=null;
        if(compact && parent.getChildCount()>0) {
            View last=parent.getChildAt(parent.getChildCount()-1);
            if("settings-pair".equals(last.getTag()) && ((LinearLayout)last).getChildCount()==1) row=(LinearLayout)last;
        }
        if(row==null) {
            row=new LinearLayout(this);
            if(compact) row.setTag("settings-pair");
            parent.addView(row,fullParams(0,8,0,0));
        }
        LinearLayout cell=new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(rounded(Color.WHITE,dp(8)));cell.setPadding(dp(2),dp(4),dp(2),dp(4));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);
        if(row.getChildCount()>0) params.leftMargin=dp(10);
        row.addView(cell,params);
        TextView fieldLabel=mutedText(label); fieldLabel.setTextSize(13);fieldLabel.setTextColor(Design.INK);
        cell.addView(fieldLabel,fullParams(0,0,0,4));
        // Align each pair without reserving a blank second line on every label.
        if(compact && row.getChildCount()==2) {
            TextView first=(TextView)((LinearLayout)row.getChildAt(0)).getChildAt(0);
            float width=dp((getResources().getConfiguration().screenWidthDp-98)/2);
            int lines=Math.max(first.getPaint().measureText(first.getText().toString()),
                    fieldLabel.getPaint().measureText(label))>width?2:1;
            first.setMinLines(lines);fieldLabel.setMinLines(lines);
        }
        return cell;
    }

    private void addRangeFields(LinearLayout parent, String label, String key) {
        // A table is only used where two 48dp arrows and a value fit in each cell.
        boolean table=getResources().getConfiguration().screenWidthDp>=400
                && getResources().getConfiguration().fontScale<1.15f
                && settingVisible(key+"Min") && settingVisible(key+"Max");
        if(table) {
            if(parent.findViewWithTag("range-columns")==null) {
                LinearLayout header=new LinearLayout(this);header.setTag("range-columns");
                header.setBackground(rounded(Design.SOFT,dp(8)));
                for(String column:new String[]{"Tham số","Min","Max"}) {
                    TextView text=valueText(column);text.setTextSize(12);text.setPadding(0,dp(8),0,dp(8));
                    text.setGravity(column.equals("Tham số")?Gravity.START:Gravity.CENTER);
                    header.addView(text,column.equals("Tham số")?new LinearLayout.LayoutParams(dp(86),-2):new LinearLayout.LayoutParams(0,-2,1));
                }
                parent.addView(header,fullParams(0,4,0,0));
            }
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setBaselineAligned(false);
            TextView name=valueText(label);name.setTextSize(12);
            row.addView(name,new LinearLayout.LayoutParams(dp(86),-2));
            for(String suffix:new String[]{"Min","Max"}) {
                String id=key+suffix, description=label+" · "+suffix;
                LinearLayout cell=new LinearLayout(this);
                EditText input=numberInput(description);configInputs.put(id,input);
                cell.addView(new NumberStepper(this,input,description,() -> settingBounds(id)),new LinearLayout.LayoutParams(-1,-2));
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.leftMargin=dp(4);row.addView(cell,params);
            }
            parent.addView(row,fullParams(0,6,0,0));
        } else {
            LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.VERTICAL);
            addNumberField(pair,label+" · Min",key+"Min");addNumberField(pair,label+" · Max",key+"Max");
            if(hasSettingControls(pair))parent.addView(pair);
        }
    }

    private void addNumberField(LinearLayout parent, String label, String key) {
        if (!settingVisible(key)) return;
        LinearLayout cell=settingCell(parent,label,false);
        EditText input = numberInput(label); input.setContentDescription(label);
        configInputs.put(key, input);
        cell.addView(new NumberStepper(this,input,label,() -> settingBounds(key)), fullParams(0,0,0,0));
    }

    private void addPinNumberField(LinearLayout parent, String label, String key) {
        if (!settingVisible(key)) return;
        LinearLayout cell=settingCell(parent,label,false);
        EditText input = numberInput(label); input.setContentDescription(label);
        NumberStepper stepper=new NumberStepper(this,input,label,() -> settingBounds(key));
        configInputs.put(key, input); pinDependentViews.add(cell.getChildAt(0)); pinDependentViews.add(stepper);
        cell.addView(stepper, fullParams(0,0,0,0));
    }

    private int editedSettingValue(String key) {
        EditText input=configInputs.get(key);
        if(input!=null) try { return Integer.parseInt(input.getText().toString().trim()); } catch(NumberFormatException ignored) { }
        return configValue(key);
    }

    private int[] settingBounds(String key) {
        int min=0,max=999;
        if(key.equals("delaySt")) { min=1;max=255; }
        else if(key.equals("stLed") || key.equals("stLcd") || key.equals("delayDf") || key.startsWith("rangeSt")) max=90;
        else if(key.equals("greenBrightness") || key.equals("redBrightness")) max=7;
        else if(key.equals("pinWrongLimit")) { min=1;max=99; }
        else if(key.equals("coolCl") || key.equals("coolOp") || key.equals("defrostCl") || key.equals("defrostOp") || key.equals("hcfTime") || key.equals("pinBackupPeriod") || key.startsWith("range")) min=1;
        String prefix=null;
        switch(key) {
            case "coolCl":prefix="rangeCoolCl";break;
            case "coolOp":prefix="rangeCoolOp";break;
            case "defrostCl":prefix="rangeDefrostCl";break;
            case "defrostOp":prefix="rangeDefrostOp";break;
            case "stLed":prefix="rangeStLed";break;
            case "stLcd":prefix="rangeStLcd";break;
        }
        if(prefix!=null) { min=Math.max(min,editedSettingValue(prefix+"Min"));max=Math.min(max,editedSettingValue(prefix+"Max")); }
        if(key.startsWith("range") && key.endsWith("Min")) max=Math.min(max,editedSettingValue(key.substring(0,key.length()-3)+"Max"));
        if(key.startsWith("range") && key.endsWith("Max")) min=Math.max(min,editedSettingValue(key.substring(0,key.length()-3)+"Min"));
        return new int[]{min,max};
    }

    private void addChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        if (!settingVisible(key)) return;
        LinearLayout cell=settingCell(parent,label,"onTime".equals(key));
        Spinner spinner = choiceSpinner(choices); spinner.setContentDescription(label);
        configChoices.put(key, spinner);
        cell.addView(spinner, fullParams(0,0,0,0));
    }

    private void addPinChoiceField(LinearLayout parent, String label, String key, Choice[] choices) {
        if (!settingVisible(key)) return;
        LinearLayout cell=settingCell(parent,label,false);
        Spinner spinner = choiceSpinner(choices); spinner.setContentDescription(label);
        configChoices.put(key, spinner); pinDependentViews.add(cell.getChildAt(0)); pinDependentViews.add(spinner);
        cell.addView(spinner, fullParams(0,0,0,0));
    }

    private CheckBox addCheckField(LinearLayout parent, String label, String key, boolean pinRelated) {
        CheckBox check = new CheckBox(this);
        check.setButtonDrawable(null);check.setCompoundDrawablePadding(dp(8));
        check.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,new Design.Toggle(getResources().getDisplayMetrics().density),null);
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
                if(bleMode)BleMenuAccess.apply(response,decoded);
                onControl(controlToken, () -> {
                    runtimeConfig = decoded;
                    configLoaded = true;
                    if(bleMode){deviceAccess=response;showControl(Tab.SETTINGS);return;}
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
        if(bleMode){saveBleSettings(visibleValues);return;}
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
        if ("showSt".equals(key) || "defrostOpSeconds".equals(key)) return false;
        if (demoMode) return true;
        JSONArray visible = deviceAccess.optJSONArray("visible");
        if (visible != null) for (int i=0;i<visible.length();i++) if (key.equals(visible.optString(i))) return true;
        return false;
    }

    private void loadManagedTab(LinearLayout content, Tab selected) {
        if(bleMode){
            if(selected==Tab.SETTINGS)loadBleSettings(content,controlEpoch,accessViewEpoch);
            else addHelp(content,"Cập nhật cần kết nối Internet và quyền từ tài khoản.");
            return;
        }
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
                content.removeView(loading);showMessage(errorMessage(error));
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
        LinearLayout box=card();
        ImageView updateIcon=icon("upload",Design.TEAL,32);updateIcon.setPadding(dp(16),dp(16),dp(16),dp(16));updateIcon.setBackground(rounded(Design.SOFT,dp(16)));
        box.addView(updateIcon,new LinearLayout.LayoutParams(dp(64),dp(64)));
        View gap=new View(this);box.addView(gap,new LinearLayout.LayoutParams(1,dp(18)));
        addSectionTitle(box,"Cập nhật thiết bị"); content.addView(box,fullParams(0,0,0,0));
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
        if(bleMode){buildBleDevice(content);return;}
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
        deviceInfo = monospaceText("");
        chipCard.addView(deviceInfo, fullParams(0, 2, 0, 0));
        Button info = neutralButton("Kiểm tra kết nối");
        info.setOnClickListener(v -> readChipInfo());
        chipCard.addView(info, fullParams(0, 10, 0, 0));
        content.addView(chipCard, fullParams(0, 12, 0, 0));
        LinearLayout about = card();
        addSectionTitle(about, "Hẹn Giờ Lạnh");
        addHelp(about, "Phiên bản " + BuildConfig.VERSION_NAME);
        Button exit = neutralButton("Danh sách thiết bị");
        exit.setOnClickListener(v -> showDevices());
        about.addView(exit, fullParams(0, 12, 0, 0));
        addBleUnpairButton(about);
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
        pageRoot.setFocusableInTouchMode(true);pageRoot.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        pageRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER);
        brand.setPadding(dp(12),dp(4),dp(12),dp(4));brand.setMinimumHeight(dp(60));brand.setBackgroundColor(Design.BG);
        ImageView logo=icon("brand",Design.ACCENT,36);
        logo.setPadding(dp(4),dp(4),dp(4),dp(4));
        brand.addView(logo,new LinearLayout.LayoutParams(dp(48),dp(48)));
        if(screen==Screen.CONTROL) {
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            logo.setContentDescription("Danh sách thiết bị");logo.setFocusable(true);
            logo.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),rounded(Color.TRANSPARENT,dp(10)),null));
            logo.setOnClickListener(v -> showDevices());
        }
        TextView wordmark=valueText("Hẹn Giờ Lạnh");wordmark.setTextColor(Design.INK);
        wordmark.setTextSize(22);wordmark.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        wordmark.setSingleLine(true);
        if(Build.VERSION.SDK_INT>=26)wordmark.setAutoSizeTextTypeUniformWithConfiguration(18,22,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams wordmarkParams=new LinearLayout.LayoutParams(-2,dp(48));wordmarkParams.leftMargin=dp(4);
        wordmark.setGravity(Gravity.CENTER_VERTICAL);brand.addView(wordmark,wordmarkParams);
        pageRoot.addView(brand);
        View brandLine=new View(this);brandLine.setBackgroundColor(Design.LINE);
        pageRoot.addView(brandLine,new LinearLayout.LayoutParams(-1,dp(1)));
        if(demoMode){
            TextView demo=mutedText("XEM THỬ  •  DỮ LIỆU MINH HỌA");
            demo.setTextColor(Design.TEAL); demo.setGravity(Gravity.CENTER);
            demo.setPadding(dp(12),dp(8),dp(12),dp(8)); demo.setBackgroundColor(Design.SOFT);
            pageRoot.addView(demo,fullParams(0,0,0,0));
        }
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);scroll.setBackgroundColor(Design.BG);
        scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16),dp(12),dp(16),dp(24));
        if (!eyebrow.isEmpty()) {
            TextView eyebrowView=mutedText(eyebrow); eyebrowView.setTextColor(Design.TEAL);
            eyebrowView.setTextSize(10); eyebrowView.setLetterSpacing(.12f);
            content.addView(eyebrowView,fullParams(0,0,0,7));
        }
        TextView heading=valueText(title); heading.setTextSize(22);
        heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        content.addView(heading,fullParams(0,0,0,subtitle.isEmpty() ? 10 : 5));
        if (!subtitle.isEmpty()) content.addView(bodyText(subtitle),fullParams(0,0,0,22));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        pageRoot.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(pageRoot); pageRoot.requestFocus();pageRoot.requestApplyInsets();
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
        card.setPadding(dp(12),dp(12),dp(12),dp(12));
        GradientDrawable bg=rounded(Color.WHITE,dp(18)); bg.setStroke(dp(1),Design.LINE);
        card.setBackground(bg); return card;
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView view=valueText(title); view.setTextSize(17);view.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        if(Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);
        parent.addView(view,fullParams(0,0,0,6));
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
        view.setLineSpacing(dp(1), 1f);
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
        input.setMinHeight(dp(48));
        input.setContentDescription(hint);
        input.setBackground(rounded(Design.FIELD, dp(12)));
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
        return new ChoiceField(this, values);
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

    private Button primaryButton(String text) { return button(text, Design.ACCENT); }
    private Button neutralButton(String text) {
        Button button=button(text,Color.WHITE);button.setTextColor(Design.ACCENT);
        GradientDrawable border=rounded(Color.WHITE,dp(10));border.setStroke(dp(1),Design.LINE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),border,null));return button;
    }
    private Button dangerButton(String text) { return button(text, 0xFFFFEEF0); }

    private Button button(String text, int color) {
        Button button=new Button(this); button.setText(text);
        button.setTextColor(color==Design.ACCENT ? Color.WHITE : color==0xFFFFEEF0 ? 0xFFAA354A : Design.INK);
        button.setTextSize(14); button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(14),dp(12),dp(14),dp(12)); button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),rounded(color,dp(12)),null));
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
        if (!foreground || isFinishing() || isDestroyed()) return;
        if (notice == null) notice = new InAppNotice(this);
        notice.show(lastUiMessage);
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
        mainHandler.removeCallbacks(unlockPoll);mainHandler.removeCallbacks(expireUnlock);
        expireUnlock.run();
        if(unlockDialog!=null){unlockDialog.dismiss();unlockDialog=null;}
        unlockPrompted=-1;
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
        if(requestCode==BLE_ENABLE_REQUEST){
            blePromptPending=false;
            if(resultCode==RESULT_OK)mainHandler.postDelayed(()->discoverNearby(false),400);
            else {endManualDiscovery();nearbySuppressed=true;}
            return;
        }
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
        nearbySuppressed=false;
        mainHandler.removeCallbacks(nearbyRefresh);mainHandler.postDelayed(nearbyRefresh,500);
        if (screen == Screen.HOME) restartHomeChecks();
        if (screen == Screen.CONTROL && tab == Tab.OVERVIEW) {
            mainHandler.removeCallbacks(dashboardRefresh);
            mainHandler.post(dashboardRefresh);
        }
        if(screen==Screen.CONTROL && tab!=Tab.OVERVIEW) { mainHandler.removeCallbacks(unlockPoll);mainHandler.post(unlockPoll); }
        if (waitingForWifiSettingsReturn) {
            waitingForWifiSettingsReturn = false;
            mainHandler.postDelayed(this::verifyManualWifiAfterSettings, 400);
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        if(notice!=null)notice.dismiss();
        if(!blePromptPending)endManualDiscovery();
        mainHandler.removeCallbacks(nearbyRefresh);stopNearby();
        for(EditText input:configInputs.values()) if(input.getParent() instanceof NumberStepper) ((NumberStepper)input.getParent()).stopRepeating();
        for(EditText input:new EditText[]{triggerThreshold,defrostThreshold}) if(input!=null && input.getParent() instanceof NumberStepper) ((NumberStepper)input.getParent()).stopRepeating();
        stopHome();
        stopDashboard();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if(screen==Screen.OFFLINE){leaveOfflineDiscovery();return;}
        if(bleMode && screen==Screen.CONNECT_DEVICE){
            ++setupEpoch;setupBusy=false;disconnectBle();
            if(accountId.isEmpty())showAuth(false);else showDevices();return;
        }
        if(bleMode && (screen==Screen.CONFIGURE_WIFI || screen==Screen.SWITCH_PHONE)){
            ++setupEpoch;setupBusy=false;showBleConnected();return;
        }
        if(bleMode && screen==Screen.CONTROL && tab==Tab.OVERVIEW){
            ++controlEpoch;stopDashboard();disconnectBle();nearbySuppressed=true;
            if(accountId.isEmpty())showAuth(false);else showDevices();return;
        }
        if(screen==Screen.AUTH && pendingBleWifi && bleMode){pendingBleWifi=false;showBleConnected();return;}
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
        if(notice!=null)notice.dismiss();
        mainHandler.removeCallbacks(nearbyRefresh);
        disconnectBle();
        mainHandler.removeCallbacks(internetChanged);
        if(internetCallback!=null)connectivityManager.unregisterNetworkCallback(internetCallback);
        ++setupEpoch; ++authEpoch; ++controlEpoch; stopHome();
        presenceIo.shutdownNow();bleConnectIo.shutdownNow();
        stopDashboard();
        releaseRequestedNetwork();
        io.shutdownNow();
        super.onDestroy();
    }
}
