package vn.duong.ms51control;

import org.json.JSONObject;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Keeps the selected controller across radio loss. Recovery only performs reads. */
final class BleSession implements EspApi.LocalTransport, AutoCloseable {
    interface Link extends EspApi.LocalTransport, AutoCloseable {
        boolean connected();
        @Override void close();
    }
    interface Connector { Link connect(BooleanSupplier allowed) throws IOException; }
    private final Connector connector;
    private final LongSupplier clock;
    private final String address, identity;
    private final Object requests=new Object();
    private volatile Link link;
    private Link opening;
    private volatile boolean closed, paused;
    private volatile int epoch;
    private volatile long nextAttempt;
    private int failures;

    BleSession(String address,String identity,Link initial,Connector connector) {
        this(address,identity,initial,connector,android.os.SystemClock::elapsedRealtime);
    }
    BleSession(String address,String identity,Link initial,Connector connector,LongSupplier clock) {
        if(identity==null || identity.isEmpty())throw new IllegalArgumentException("Missing controller identity");
        this.address=address;this.identity=identity;this.link=initial;this.connector=connector;this.clock=clock;
    }
    String address(){return address;}
    boolean connected(){Link current=link;return !closed && current!=null && current.connected();}
    synchronized void pause(){paused=true;epoch++;if(opening!=null)opening.close();}
    synchronized void resume(){paused=false;nextAttempt=0;}
    private IOException waiting(){return new IOException("Đang kết nối lại Bluetooth…");}
    private boolean allowed(int token){return !closed && !paused && epoch==token;}
    private void invalidate(Link failed) {
        if(link==failed)link=null;
        if(failed!=null)failed.close();
        nextAttempt=clock.getAsLong()+Math.min(15000L,1000L<<Math.min(failures++,4));
    }
    private Link recover() throws IOException {
        int token=epoch;
        if(!allowed(token) || clock.getAsLong()<nextAttempt)throw waiting();
        Link old=link;link=null;if(old!=null)old.close();
        Link candidate=null;
        try {
            candidate=connector.connect(()->allowed(token));
            synchronized(this) {
                if(!allowed(token))throw waiting();
                opening=candidate;
            }
            JSONObject status=candidate.request("GET","/api/status",null);
            if(!identity.equals(DeviceStore.identity(status)))throw new IOException("Bluetooth không thuộc thiết bị đã chọn.");
            synchronized(this) {
                if(!allowed(token))throw waiting();
                link=candidate;failures=0;nextAttempt=0;
            }
            return candidate;
        } catch(Exception error) {
            if(candidate!=null)candidate.close();
            if(allowed(token))invalidate(null);
            throw error instanceof IOException?(IOException)error:new IOException("Chưa kết nối lại được Bluetooth.",error);
        } finally {
            synchronized(this){if(opening==candidate)opening=null;}
        }
    }
    @Override public JSONObject request(String method,String path,JSONObject body) throws IOException {
        boolean read="GET".equals(method);
        Link submitted=link;
        if(!read && !connected())throw new IOException("Đang kết nối lại. Hãy đọc trạng thái rồi thao tác lại.");
        synchronized(requests) {
            if(closed || paused)throw waiting();
            Link current=link;
            if(!read && current!=submitted)throw new IOException("Kết nối đã thay đổi. Hãy đọc trạng thái rồi thao tác lại.");
            if(!connected()) {
                // A button pressed while disconnected is never queued or replayed later.
                if(!read)throw new IOException("Đang kết nối lại. Hãy đọc trạng thái rồi thao tác lại.");
                current=recover();
            }
            try {return current.request(method,path,body);}
            catch(EspApi.ApiException rejected){throw rejected;}
            catch(IOException error){invalidate(current);throw error;}
        }
    }
    @Override public synchronized void close() {
        closed=true;epoch++;if(opening!=null)opening.close();Link old=link;link=null;if(old!=null)old.close();
    }
}
