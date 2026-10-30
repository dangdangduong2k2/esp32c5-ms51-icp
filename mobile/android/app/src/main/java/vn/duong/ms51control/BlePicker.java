package vn.duong.ms51control;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.*;
import java.util.*;

/** Discovery does not contact an account, a Wi-Fi network, or a server. */
@SuppressLint("MissingPermission")
final class BlePicker implements AutoCloseable {
    interface Selected { void select(BluetoothDevice device); }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BluetoothLeScanner scanner;
    private final AlertDialog dialog;
    private final Map<String,BluetoothDevice> found=new LinkedHashMap<>();
    private final ArrayList<BluetoothDevice> devices=new ArrayList<>();
    private final ArrayAdapter<String> rows;
    private final TextView status;
    private boolean closed;
    BlePicker(Activity activity,BluetoothAdapter adapter,Selected selected) {
        scanner=adapter.getBluetoothLeScanner();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);
        status=new TextView(activity);status.setText("Đang tìm thiết bị gần bạn…\nBật nguồn thiết bị và đặt gần điện thoại.");content.addView(status);
        ListView list=new ListView(activity);rows=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1);
        list.setAdapter(rows);content.addView(list,new LinearLayout.LayoutParams(-1,(int)(280*activity.getResources().getDisplayMetrics().density)));
        dialog=new AlertDialog.Builder(activity).setTitle("Kết nối Bluetooth").setView(content).setNegativeButton("Đóng",null).setNeutralButton("Tìm lại",null).create();
        list.setOnItemClickListener((parent,view,position,id)-> {BluetoothDevice device=devices.get(position);close();selected.select(device);});
        dialog.setOnDismissListener(d->stop());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->scan());scan();
    }
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) { handler.post(()->add(result)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult r:results)handler.post(()->add(r)); }
        @Override public void onScanFailed(int code) {handler.post(()->status.setText("Chưa quét được. Bật Bluetooth và quyền Thiết bị ở gần rồi thử lại."));}
    };
    private void add(ScanResult result) {
        if(closed)return;
        BluetoothDevice device=result.getDevice(); if(found.containsKey(device.getAddress()))return;
        found.put(device.getAddress(),device);devices.add(device);
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
        rows.add((name==null?"Hộp hẹn giờ":name)+"\n"+device.getAddress().substring(9));
        status.setText("Chọn thiết bị để kết nối. Không cần Internet.");
    }
    private void scan() {
        if(scanner==null){status.setText("Điện thoại chưa bật Bluetooth.");return;}
        scanner.stopScan(callback);found.clear();devices.clear();rows.clear();handler.removeCallbacksAndMessages(null);
        status.setText("Đang tìm thiết bị gần bạn…");
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BleTransport.SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback);
        handler.postDelayed(()-> {
            if(closed)return;scanner.stopScan(callback);
            if(devices.isEmpty())status.setText("Chưa thấy thiết bị. Đưa điện thoại lại gần, kiểm tra nguồn. C5 cần bản cập nhật hỗ trợ Bluetooth.");
        },15000);
    }
    private void stop(){closed=true;handler.removeCallbacksAndMessages(null);if(scanner!=null)try{scanner.stopScan(callback);}catch(SecurityException revoked){/* Permission revoked in Settings. */}}
    @Override public void close(){stop();dialog.dismiss();}
}
