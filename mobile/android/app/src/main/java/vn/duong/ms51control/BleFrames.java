package vn.duong.ms51control;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** UTF-8 is decoded only after the whole frame arrives, including at MTU 23. */
final class BleFrames {
    interface Frame { void receive(JSONObject envelope) throws Exception; }
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    synchronized void accept(byte[] chunk,Frame consumer) throws IOException {
        if(chunk==null)return;
        for(byte b:chunk){
            if(b=='\n'){
                byte[] complete=bytes.toByteArray();bytes.reset();
                try{consumer.receive(new JSONObject(new String(complete,StandardCharsets.UTF_8)));}
                catch(Exception error){throw new IOException("Dữ liệu Bluetooth chưa đầy đủ. Hãy kết nối lại.",error);}
            }else{
                if(bytes.size()>=32768){bytes.reset();throw new IOException("Phản hồi Bluetooth quá dài.");}
                bytes.write(b);
            }
        }
    }
}
