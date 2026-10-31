package vn.duong.ms51control;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Foreground discovery: silent until found, bounded scan windows, no server. */
@SuppressLint("MissingPermission")
final class BlePicker implements AutoCloseable {
    interface Selected { void select(BluetoothDevice device); }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BluetoothLeScanner scanner;
    private final NearbyDeviceSheet sheet;
    private final BooleanSupplier eligible;
    private final Selected selected;
    private final Runnable dismissed;
    private final Predicate<BluetoothDevice> remembered, paired;
    private final BleProximity proximity=new BleProximity();
    private final Set<String> admitted=new HashSet<>();
    private boolean closed, scanning;
    BlePicker(Activity activity,BluetoothAdapter adapter,BooleanSupplier eligible,Predicate<BluetoothDevice> remembered,Predicate<BluetoothDevice> paired,Selected selected,Runnable dismissed) {
        scanner=adapter.getBluetoothLeScanner();this.eligible=eligible;this.selected=selected;this.dismissed=dismissed;
        this.remembered=remembered;this.paired=paired;
        sheet=new NearbyDeviceSheet(activity,()->{close();dismissed.run();});scan();
    }
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->add(result));}
        @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult r:results)handler.post(()->add(r));}
        @Override public void onScanFailed(int code){handler.post(()->{if(!closed){close();dismissed.run();}});}
    };
    private void add(ScanResult result) {
        if(closed || !eligible.getAsBoolean())return;
        BluetoothDevice device=result.getDevice();
        if(remembered.test(device)){
            close();selected.select(device);return;
        }
        String address=device.getAddress();
        if(!paired.test(device) && !admitted.contains(address)){
            if(!proximity.accept(address,result.getRssi(),result.getTimestampNanos()/1000000L,SystemClock.elapsedRealtime()))return;
            admitted.add(address);
        }
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
        sheet.addDevice(device.getAddress(),name==null?"Hẹn Giờ Lạnh":name,()->{
            if(closed || !eligible.getAsBoolean())return;
            close();selected.select(device);
        });
    }
    private void scan() {
        if(closed)return;
        if(scanner==null || !eligible.getAsBoolean()){close();return;}
        try {
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BleTransport.SERVICE)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback);
            scanning=true;
            handler.postDelayed(()->{stopScan();if(!closed)handler.postDelayed(this::scan,10000);},15000);
        }catch(SecurityException|IllegalStateException error){close();dismissed.run();}
    }
    private void stopScan(){if(scanning && scanner!=null)try{scanner.stopScan(callback);}catch(SecurityException|IllegalStateException ignored){}finally{scanning=false;}}
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);stopScan();sheet.close();}
}
