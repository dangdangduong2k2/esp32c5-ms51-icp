package vn.duong.ms51control;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class BleRecoveryChecks {
    static final String ID="ms51-000000000001";
    static class Link implements BleSession.Link {
        boolean alive=true, failWrite;
        int reads,writes;
        String identity=ID;
        public boolean connected(){return alive;}
        public void close(){alive=false;}
        public JSONObject request(String method,String path,JSONObject body)throws IOException {
            if(!alive)throw new IOException("lost");
            if("POST".equals(method)){writes++;if(failWrite){alive=false;throw new IOException("lost acknowledgement");}}
            else reads++;
            try{return new JSONObject().put("ok",true).put("device",new JSONObject().put("id",identity));}
            catch(Exception error){throw new IOException(error);}
        }
    }
    interface Op {void run()throws Exception;}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void fails(Op op)throws Exception{try{op.run();}catch(IOException expected){return;}throw new AssertionError("Expected failure");}
    static void run(Instrumentation test) {
        Bundle report=new Bundle();
        try {
            AtomicLong now=new AtomicLong();AtomicInteger attempts=new AtomicInteger();
            Link old=new Link(), fresh=new Link();old.alive=false;
            BleSession session=new BleSession("same-address",ID,old,allowed->{
                if(attempts.incrementAndGet()<3)throw new IOException("booting");return fresh;
            },now::get);
            fails(()->session.request("POST","/api/mode",new JSONObject()));
            check(attempts.get()==0,"Writes must not initiate recovery");
            fails(()->session.request("GET","/api/status",null));
            fails(()->session.request("GET","/api/status",null));check(attempts.get()==1,"No busy retry loop");
            now.set(1000);fails(()->session.request("GET","/api/status",null));
            now.set(2999);fails(()->session.request("GET","/api/status",null));check(attempts.get()==2,"Increasing delay");
            now.set(3000);session.request("GET","/api/debug",null);
            check(attempts.get()==3 && session.connected() && fresh.reads==2 && fresh.writes==0,"Reopen, verify identity, then read only");
            fresh.failWrite=true;fails(()->session.request("POST","/api/mode",new JSONObject()));
            check(fresh.writes==1 && !session.connected(),"Ambiguous command closes session without replay");
            session.pause();now.set(100000);fails(()->session.request("GET","/api/status",null));
            check(attempts.get()==3,"No background recovery");session.close();session.resume();
            fails(()->session.request("GET","/api/status",null));check(attempts.get()==3,"Leaving or unpair permanently cancels session");

            Link wrong=new Link();wrong.identity="ms51-000000000002";
            BleSession mismatch=new BleSession("same-address",ID,null,allowed->wrong,now::get);
            fails(()->mismatch.request("GET","/api/debug",null));
            check(!wrong.alive && !mismatch.connected() && wrong.reads==1,"Never adopt different controller or read its controls");mismatch.close();

            AtomicInteger concurrent=new AtomicInteger();Link good=new Link();
            BleSession single=new BleSession("same-address",ID,null,allowed->{concurrent.incrementAndGet();try{Thread.sleep(80);}catch(InterruptedException e){throw new IOException(e);}return good;},now::get);
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<?> a=pool.submit(()->{try{single.request("GET","/api/status",null);}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> b=pool.submit(()->{try{single.request("GET","/api/debug",null);}catch(Exception e){throw new RuntimeException(e);}});
                a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);check(concurrent.get()==1,"Only one connection for concurrent readers");
            }finally{single.close();pool.shutdownNow();}

            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Link late=new Link();
            BleSession cancelled=new BleSession("same-address",ID,null,allowed->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}return late;},now::get);
            ExecutorService worker=Executors.newFixedThreadPool(2);
            try {
                Future<?> pending=worker.submit(()->{try{cancelled.request("GET","/api/status",null);throw new AssertionError("Late connection accepted");}catch(IOException expected){}});
                check(entered.await(2,TimeUnit.SECONDS),"Recovery started");
                worker.submit(()->{try{fails(()->cancelled.request("POST","/api/mode",new JSONObject()));}catch(Exception e){throw new RuntimeException(e);}}).get(1,TimeUnit.SECONDS);
                check(late.writes==0,"Reject a command during recovery immediately, never queue it");
                cancelled.pause();release.countDown();pending.get(3,TimeUnit.SECONDS);
                check(!late.alive && late.reads==0 && !cancelled.connected(),"Close late connection after backgrounding");
            }finally{cancelled.close();worker.shutdownNow();}
            AtomicInteger resumedAttempts=new AtomicInteger();
            BleSession resumed=new BleSession("same-address",ID,null,allowed->{resumedAttempts.incrementAndGet();return new Link();},now::get);
            resumed.pause();fails(()->resumed.request("GET","/api/status",null));check(resumedAttempts.get()==0,"Paused session stays idle");
            resumed.resume();resumed.request("GET","/api/status",null);check(resumedAttempts.get()==1 && resumed.connected(),"Resume recovers selected controller");resumed.close();
            checkScreen(test);
            report.putString("stream","PASS: automatic BLE recovery with backoff, single connection, identity verification, no command replay, pause/resume/leave and late result cancellation.\n");
            test.finish(-1,report);
        }catch(Throwable error){report.putString("stream","FAIL: "+error+"\n");test.finish(0,report);}
    }

    static void checkScreen(Instrumentation test)throws Exception {
        MainActivity a=(MainActivity)test.startActivitySync(new android.content.Intent(test.getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicLong now=new AtomicLong();AtomicInteger attempts=new AtomicInteger();Link fresh=new Link();
        BleSession session=new BleSession("same-address",ID,null,allowed->{
            if(attempts.incrementAndGet()==1)throw new IOException("controller starting");return fresh;
        },now::get);
        AtomicReference<Throwable> error=new AtomicReference<>();
        try {
            test.runOnMainSync(()->{try {
                ManagedChecks.set(a,"demoMode",false);ManagedChecks.set(a,"bleMode",true);ManagedChecks.set(a,"bleLink",session);
                ManagedChecks.set(a,"activeDevice",new DeviceStore.Device(ID,"Hộp thử","","",""));
                EspApi api=(EspApi)ManagedChecks.get(a,"api");api.setDemo(false);api.setBle(session);api.setExpectedDeviceId(ID);
                Class<?> tab=Class.forName("vn.duong.ms51control.MainActivity$Tab");
                ManagedChecks.invoke(a,"showControl",new Class[]{tab},Enum.valueOf((Class)tab,"OVERVIEW"));
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw new AssertionError(error.get());
            long end=android.os.SystemClock.elapsedRealtime()+5000;
            while(attempts.get()==0 && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(30);
            check(attempts.get()==1,"Dashboard initiates reconnect without manual discovery");
            test.runOnMainSync(()->{try {
                android.widget.TextView mode=(android.widget.TextView)ManagedChecks.get(a,"dashboardMode");
                check(mode.getText().toString().contains("Đang kết nối lại"),"Dashboard shows recovery");
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw new AssertionError(error.get());
            test.waitForIdleSync();Thread.sleep(150);
            android.graphics.Bitmap shot=test.getUiAutomation().takeScreenshot();
            java.io.File directory=new java.io.File(a.getExternalFilesDir(null),"recovery-preview");directory.mkdirs();
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,"reconnecting.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{shot.recycle();}
            now.set(2000);end=android.os.SystemClock.elapsedRealtime()+6000;
            while(!session.connected() && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(40);
            check(session.connected() && attempts.get()==2,"Dashboard polling automatically restores the link");
            test.runOnMainSync(()->{try {
                check(ManagedChecks.get(a,"screen").toString().equals("CONTROL"),"Stay on control screen");
                ManagedChecks.invoke(a,"disconnectBle",new Class[]{});
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw new AssertionError(error.get());
            check(!session.connected(),"Leaving explicitly closes the recovered session");
        }finally{test.runOnMainSync(a::finish);session.close();}
    }
}
