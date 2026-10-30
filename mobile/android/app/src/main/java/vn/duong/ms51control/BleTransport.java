package vn.duong.ms51control;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.Build;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One bonded GATT connection, one acknowledged operation at a time. Never replay writes. */
@SuppressLint("MissingPermission")
final class BleTransport implements AutoCloseable, EspApi.LocalTransport {
    static final UUID SERVICE=UUID.fromString("51b10001-8c22-4f42-9a51-3844be1fe4e0");
    static final UUID RX=UUID.fromString("51b10002-8c22-4f42-9a51-3844be1fe4e0");
    static final UUID TX=UUID.fromString("51b10003-8c22-4f42-9a51-3844be1fe4e0");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final BluetoothDevice device;
    private final CountDownLatch ready=new CountDownLatch(1);
    private final BleFrames received=new BleFrames();
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic rx;
    private volatile int mtu=23, sequence, waitingId;
    private volatile boolean closed;
    private volatile IOException failure;
    private volatile CountDownLatch writeDone, responseDone;
    private volatile JSONObject response;
    private boolean registered;
    private BleTransport(Context context,BluetoothDevice device) { this.context=context.getApplicationContext();this.device=device; }
    static BleTransport connect(Context context,BluetoothDevice device) throws IOException {
        BleTransport link=new BleTransport(context,device);
        try {
            IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33) link.context.registerReceiver(link.bond,filter,Context.RECEIVER_EXPORTED);
            else link.context.registerReceiver(link.bond,filter);
            link.registered=true;
            link.gatt=device.connectGatt(link.context,false,link.callback,BluetoothDevice.TRANSPORT_LE);
            if(link.gatt==null) throw new IOException("Không mở được Bluetooth.");
            link.await(link.ready,45000,"Chưa kết nối được. Hãy xác nhận ghép Bluetooth trên điện thoại.");
            return link;
        } catch(Exception error) { link.close(); throw error instanceof IOException?(IOException)error:new IOException(error.getMessage(),error); }
    }
    String address() { return device.getAddress(); }
    boolean connected() { return !closed && failure==null && rx!=null; }
    private final BroadcastReceiver bond=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            BluetoothDevice peer=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(!device.equals(peer) || closed || gatt==null) return;
            int state=i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE);
            if(state==BluetoothDevice.BOND_BONDED) discover(gatt);
            else if(state==BluetoothDevice.BOND_NONE) fail("Chưa ghép Bluetooth. Hãy thử kết nối lại.");
        }
    };
    private void discover(BluetoothGatt g) { if(!g.discoverServices()) fail("Không đọc được dịch vụ Bluetooth."); }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            if(closed) return;
            if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED) { fail("Đã mất kết nối Bluetooth. Đặt điện thoại gần thiết bị rồi kết nối lại."); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                if(device.getBondState()==BluetoothDevice.BOND_BONDED) discover(g);
                else if(device.getBondState()==BluetoothDevice.BOND_NONE && !device.createBond()) fail("Không bắt đầu được ghép Bluetooth.");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
            BluetoothGattService service=g.getService(SERVICE);
            if(status!=BluetoothGatt.GATT_SUCCESS || service==null || service.getCharacteristic(RX)==null || service.getCharacteristic(TX)==null) {
                fail("Thiết bị chưa có bản cập nhật hỗ trợ Bluetooth."); return;
            }
            rx=service.getCharacteristic(RX);
            if(!g.requestMtu(247)) subscribe(g);
        }
        @Override public void onMtuChanged(BluetoothGatt g,int value,int status) { if(status==0) mtu=Math.max(23,value); subscribe(g); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {
            if(status==0) ready.countDown(); else fail("Không nhận được dữ liệu Bluetooth. Hãy ghép lại thiết bị.");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {
            if(status!=0) fail("Thiết bị chưa nhận được lệnh Bluetooth. Hãy đọc lại trạng thái.");
            CountDownLatch latch=writeDone;if(latch!=null)latch.countDown();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) { if(TX.equals(c.getUuid())) receive(c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) { if(TX.equals(c.getUuid())) receive(value); }
    };
    private void subscribe(BluetoothGatt g) {
        if(closed) return;
        BluetoothGattCharacteristic tx=g.getService(SERVICE).getCharacteristic(TX);
        BluetoothGattDescriptor descriptor=tx.getDescriptor(CCCD);
        if(descriptor==null || !g.setCharacteristicNotification(tx,true)) { fail("Không đăng ký được dữ liệu Bluetooth.");return; }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        if(!g.writeDescriptor(descriptor)) fail("Không đăng ký được dữ liệu Bluetooth.");
    }
    private void receive(byte[] bytes) {
        if(closed)return;
        try{received.accept(bytes,envelope->{
            if(envelope.optInt("id",-1)==waitingId && responseDone!=null){response=envelope.getJSONObject("result");responseDone.countDown();}
        });}catch(IOException error){fail(error.getMessage());}
    }
    /* Separate mutex: callbacks must never wait behind a request waiting for those callbacks. */
    private final Object requests=new Object();
    @Override public JSONObject request(String method,String path,JSONObject body) throws IOException {
        synchronized(requests) {
            if(!connected()) throw failure==null?new IOException("Chưa kết nối Bluetooth."):failure;
            try {
                waitingId=++sequence; response=null; responseDone=new CountDownLatch(1);
                byte[] bytes=(new JSONObject().put("id",waitingId).put("method",method).put("path",path).put("body",body)+"\n").getBytes(StandardCharsets.UTF_8);
                if(bytes.length>=8192) throw new IOException("Lệnh Bluetooth quá dài.");
                for(int offset=0;offset<bytes.length;) {
                    int end=Math.min(bytes.length,offset+Math.min(500,mtu-3));writeDone=new CountDownLatch(1);
                    rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(Arrays.copyOfRange(bytes,offset,end));
                    if(!gatt.writeCharacteristic(rx)) throw new IOException("Bluetooth đang bận.");
                    await(writeDone,8000,"Chưa xác nhận lệnh Bluetooth. Hãy kết nối và đọc lại trạng thái.");offset=end;
                }
                await(responseDone,45000,"Thiết bị chưa phản hồi. Hãy kết nối và đọc lại trạng thái trước khi thử lại.");
                JSONObject result=response;
                if(result==null) throw new IOException("Thiết bị chưa phản hồi.");
                if(!result.optBoolean("ok")) throw new EspApi.ApiException(result.optString("message","Thiết bị từ chối yêu cầu."));
                return result;
            } catch(EspApi.ApiException rejected) { throw rejected; }
            catch(Exception error) { close();throw error instanceof IOException?(IOException)error:new IOException(error.getMessage(),error); }
            finally { writeDone=null;responseDone=null; }
        }
    }
    private void await(CountDownLatch latch,int timeout,String message) throws IOException {
        try { if(!latch.await(timeout,TimeUnit.MILLISECONDS)) throw new IOException(message); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("Đã dừng kết nối.",e); }
        if(failure!=null) throw failure;
        if(closed) throw new IOException("Bluetooth đã ngắt kết nối.");
    }
    private void fail(String message) {
        failure=new IOException(message);ready.countDown();
        CountDownLatch write=writeDone,reply=responseDone;if(write!=null)write.countDown();if(reply!=null)reply.countDown();
    }
    @Override public void close() {
        if(closed)return;closed=true;fail("Bluetooth đã ngắt kết nối.");
        BluetoothGatt g=gatt;gatt=null;if(g!=null){try{g.disconnect();g.close();}catch(SecurityException revoked){/* Permission can be revoked while connected. */}}
        if(registered){context.unregisterReceiver(bond);registered=false;}
    }
}
