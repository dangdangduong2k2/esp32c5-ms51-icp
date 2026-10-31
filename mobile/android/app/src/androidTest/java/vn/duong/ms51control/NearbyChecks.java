package vn.duong.ms51control;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.*;

/** Real popup/GIF rendered on Android; discovery events are simulated, no radio required. */
final class NearbyChecks {
    static void run(Instrumentation test){
        Bundle report=new Bundle();MainActivity activity=null;
        AtomicReference<NearbyDeviceSheet> sheet=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger selected=new AtomicInteger(),dismissed=new AtomicInteger();
        try {
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity;
            android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)a.getSystemService(android.content.Context.BLUETOOTH_SERVICE);
            boolean autoChecked=manager!=null && manager.getAdapter()!=null && manager.getAdapter().isEnabled();
            SystemClock.sleep(800);
            test.runOnMainSync(()->{try{
                check(ManagedChecks.get(a,"blePicker")==null,"Login never starts scanner");
                check(!(Boolean)ManagedChecks.get(a,"blePromptPending"),"Login never requests Bluetooth permission");
            }catch(Throwable e){failure.set(e);}});
            test.runOnMainSync(()->{try{
                checkProximity();
                checkDiscoveryPolicy(a,autoChecked);
                ManagedChecks.set(a,"nearbySuppressed",true);
                check(!ManagedChecks.texts(a.getWindow().getDecorView()).contains("Dùng Bluetooth"),"No manual Bluetooth entry button");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));
                check(!dialog(sheet.get()).isShowing(),"No popup before discovery");
                sheet.get().addDevice("38:44:BE:1F:E4:E0","HenGio-1FE4E0",selected::incrementAndGet);
                sheet.get().addDevice("38:44:BE:1F:E4:E0","Duplicate",()->selected.addAndGet(100));
                check(dialog(sheet.get()).isShowing(),"Popup opens on discovery");
                check(!ManagedChecks.texts(dialog(sheet.get()).getWindow().getDecorView()).contains("Duplicate"),"Advertisements deduplicated");
            }catch(Throwable e){failure.set(e);}});
            check(failure.get()==null,String.valueOf(failure.get()));
            long deadline=SystemClock.elapsedRealtime()+8000;
            AtomicBoolean loaded=new AtomicBoolean();
            while(!loaded.get() && SystemClock.elapsedRealtime()<deadline){
                test.runOnMainSync(()->{try{DeviceSpinView spin=find(dialog(sheet.get()).getWindow().getDecorView(),DeviceSpinView.class);loaded.set(field(spin,"movie")!=null);}catch(Exception e){failure.set(e);}});
                SystemClock.sleep(100);
            }
            check(loaded.get(),"Bundled GIF decodes on Android");SystemClock.sleep(400);
            AtomicReference<android.graphics.Bitmap> frame=new AtomicReference<>();
            test.runOnMainSync(()->{try{frame.set(drawSpin(sheet.get()));}catch(Exception e){failure.set(e);}});
            SystemClock.sleep(300);
            test.runOnMainSync(()->{try{
                android.graphics.Bitmap next=drawSpin(sheet.get());
                check(!next.sameAs(frame.get()),"Product actually rotates rather than displaying a static GIF frame");
                next.recycle();frame.get().recycle();
            }catch(Exception e){failure.set(e);}});
            check(failure.get()==null,String.valueOf(failure.get()));
            android.graphics.Bitmap shot=test.getUiAutomation().takeScreenshot();
            java.io.File folder=new java.io.File(a.getExternalFilesDir(null),"nearby-preview");folder.mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"01-popup-thiet-bi.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            test.runOnMainSync(()->{try{
                DeviceSpinView spin=find(dialog(sheet.get()).getWindow().getDecorView(),DeviceSpinView.class);
                spin.performClick();spin.performClick();
                check(selected.get()==1 && dismissed.get()==0,"One tap, no duplicate connection and no dismissal callback");
                check(!dialog(sheet.get()).isShowing(),"Connection closes popup");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));
                sheet.get().addDevice("38:44:BE:1F:E4:E0","First",selected::incrementAndGet);
                sheet.get().addDevice("38:44:BE:1F:E4:E1","Second",()->selected.addAndGet(10));
                check(findButton(dialog(sheet.get()).getWindow().getDecorView(),"First")!=null,"First and second boxes both have named selection rows");
                find(dialog(sheet.get()).getWindow().getDecorView(),DeviceSpinView.class).performClick();
                check(selected.get()==1,"With multiple boxes the illustration cannot silently select the first");
                sheet.get().addDevice("38:44:BE:1F:E4:E1","Duplicate",()->selected.addAndGet(100));
                check(ManagedChecks.texts(dialog(sheet.get()).getWindow().getDecorView()).contains("Tìm thấy 2 hộp"),"Repeated advertisements do not duplicate choices");
                Button second=findButton(dialog(sheet.get()).getWindow().getDecorView(),"Second");check(second!=null,"Other nearby devices selectable");second.performClick();
                check(selected.get()==11,"Select the requested device, not the first one");
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));sheet.get().addDevice("38:44:BE:1F:E4:E0","Dismiss",selected::incrementAndGet);
                dialog(sheet.get()).cancel();
            }catch(Throwable e){failure.set(e);}});
            test.waitForIdleSync();check(failure.get()==null,String.valueOf(failure.get()));check(dismissed.get()==1,"User dismissal suppresses rediscovery popup");
            test.runOnMainSync(()->{try{
                android.content.SharedPreferences prefs=a.getSharedPreferences("ble_devices_v1",0);
                java.util.Map<String,?> before=prefs.getAll();
                String first="AA:00:00:00:00:01",second="AA:00:00:00:00:02";
                try {
                    prefs.edit().clear().commit();
                    check(!a.shouldReconnectBle(first,true),"System bond alone cannot automatically open an unknown box");
                    check(!a.isPairedBle(first,true),"System bond alone does not bypass proximity");
                    prefs.edit().putString(first,"ms51-test1").commit();
                    check(a.shouldReconnectBle(first,true),"One saved 3.7.1 box automatically reconnects after upgrade");
                    check(a.isPairedBle(first,true) && !a.isPairedBle(first,false),"Only verified app pairing with live bond bypasses proximity");
                    check(!a.shouldReconnectBle(first,false),"Removed bond requires explicit pairing again");
                    prefs.edit().putString(second,"ms51-test2").commit();
                    check(!a.shouldReconnectBle(first,true),"Ambiguous old pairings require selection once");
                    prefs.edit().putString("last_connected_address",second).commit();
                    check(a.shouldReconnectBle(second,true) && !a.shouldReconnectBle(first,true),"Reconnect only last verified box, not another nearby box");
                    if(autoChecked){
                        AtomicInteger connections=new AtomicInteger();
                        BlePicker picker=new BlePicker(a,manager.getAdapter(),()->true,d->a.shouldReconnectBle(d.getAddress(),true),d->a.isPairedBle(d.getAddress(),true),d->connections.incrementAndGet(),()->{});
                        java.lang.reflect.Method add=BlePicker.class.getDeclaredMethod("add",android.bluetooth.le.ScanResult.class);add.setAccessible(true);
                        android.bluetooth.le.ScanResult result=new android.bluetooth.le.ScanResult(manager.getAdapter().getRemoteDevice(second),null,-90,SystemClock.elapsedRealtimeNanos());
                        add.invoke(picker,result);add.invoke(picker,result);
                        check(connections.get()==1 && (Boolean)field(picker,"closed"),"Remembered advertisement connects without tapping, stops scanning and ignores queued duplicates");
                        picker.close();
                    }
                    ManagedChecks.invoke(a,"forgetBleDevice",new Class[]{String.class},second);
                    check(!prefs.contains(second) && prefs.contains(first),"Unpair forgets only the selected box");
                    check(!a.isPairedBle(second,true),"Forgotten box requires close proximity again");
                    check(!a.shouldReconnectBle(second,true) && !a.shouldReconnectBle(first,true),"Unpair requires manual selection even when another box was remembered");
                    check((Boolean)ManagedChecks.get(a,"bleChooseManually"),"Unpair opens explicit device selection");
                    if(autoChecked){
                        BlePicker picker=(BlePicker)ManagedChecks.get(a,"blePicker");
                        check(picker!=null,"Unpair starts discovery immediately");
                        java.lang.reflect.Method add=BlePicker.class.getDeclaredMethod("add",android.bluetooth.le.ScanResult.class);add.setAccessible(true);
                        emitClose(add,picker,manager.getAdapter().getRemoteDevice(first));
                        emitClose(add,picker,manager.getAdapter().getRemoteDevice(second));
                        check(!(Boolean)field(picker,"closed"),"Neither box auto-connects during explicit selection");
                        NearbyDeviceSheet choices=(NearbyDeviceSheet)field(picker,"sheet");
                        check(ManagedChecks.texts(dialog(choices).getWindow().getDecorView()).contains("Tìm thấy 2 hộp"),"Picker displays both advertisements after unpair");
                        ManagedChecks.invoke(a,"stopNearby",new Class[]{});
                    }
                    a.rememberBleDevice(first,"ms51-test1");
                    check(a.shouldReconnectBle(first,true) && !a.shouldReconnectBle(second,true),"Next successful selection becomes the automatic reconnect target");
                    ManagedChecks.set(a,"nearbySuppressed",true);
                } finally {
                    android.content.SharedPreferences.Editor restore=prefs.edit().clear();
                    for(java.util.Map.Entry<String,?> entry:before.entrySet()){
                        if(entry.getValue() instanceof Boolean)restore.putBoolean(entry.getKey(),(Boolean)entry.getValue());
                        else restore.putString(entry.getKey(),(String)entry.getValue());
                    }
                    restore.commit();
                }
                sheet.set(new NearbyDeviceSheet(a,dismissed::incrementAndGet));sheet.get().showConnecting("Hộp đã ghép");
                check(findButton(dialog(sheet.get()).getWindow().getDecorView(),"Kết nối")==null,"Reconnect popup has no second Connect button");
                check(ManagedChecks.texts(dialog(sheet.get()).getWindow().getDecorView()).contains("Đang kết nối"),"Automatic connection exposes progress");
            }catch(Throwable e){failure.set(e);}});
            test.waitForIdleSync();SystemClock.sleep(700);
            android.graphics.Bitmap connectingShot=test.getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"02-tu-ket-noi.png"))){connectingShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}connectingShot.recycle();
            test.runOnMainSync(()->{try{dialog(sheet.get()).cancel();}catch(Throwable e){failure.set(e);}});
            test.waitForIdleSync();
            check(failure.get()==null,String.valueOf(failure.get()));
            check(dismissed.get()==2,"Automatic connection can be cancelled");
            test.runOnMainSync(()->{
                sheet.set(new NearbyDeviceSheet(a,()->{}));
                sheet.get().addDevice("38:44:BE:1F:E4:E0","Hẹn Giờ Lạnh 1FE4E0",()->{});
                sheet.get().addDevice("38:44:BE:1F:E4:E1","Hẹn Giờ Lạnh 1FE4E1",()->{});
            });
            test.waitForIdleSync();SystemClock.sleep(700);
            android.graphics.Bitmap choicesShot=test.getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"03-chon-thiet-bi.png"))){choicesShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}choicesShot.recycle();
            test.runOnMainSync(()->{try{
                sheet.get().close();ManagedChecks.invoke(a,"showAuth",new Class[]{boolean.class},false);
            }catch(Exception e){failure.set(e);}});
            test.waitForIdleSync();SystemClock.sleep(300);
            saveShot(test,folder,"04-login-offline-entry.png");
            test.runOnMainSync(()->findButton(a.getWindow().getDecorView(),"Chế độ offline").performClick());
            long guideDeadline=SystemClock.elapsedRealtime()+5000;
            AtomicBoolean guideLoaded=new AtomicBoolean();
            while(!guideLoaded.get() && SystemClock.elapsedRealtime()<guideDeadline){
                test.runOnMainSync(()->{try{
                    DeviceSpinView guide=find(a.getWindow().getDecorView(),DeviceSpinView.class);
                    guideLoaded.set(guide!=null && field(guide,"movie")!=null);
                }catch(Exception e){failure.set(e);}});SystemClock.sleep(100);
            }
            check(guideLoaded.get(),"Phone approach GIF actually decodes on Android");
            saveShot(test,folder,"05-offline-guide.png");
            test.runOnMainSync(()->a.onBackPressed());test.waitForIdleSync();
            check(failure.get()==null,String.valueOf(failure.get()));
            String previewOwner=(String)ManagedChecks.get(a,"accountId"),previewName=(String)ManagedChecks.get(a,"username");
            DeviceStore previewStore=(DeviceStore)ManagedChecks.get(a,"deviceStore");
            try {
                test.runOnMainSync(()->{try{
                    ManagedChecks.set(a,"accountId","entry-preview");ManagedChecks.set(a,"username","duongdd");
                    previewStore.save("entry-preview",new DeviceStore.Device("ms51-preview","Hộp máy lạnh","Wi-Fi đã lưu","",""));
                    ManagedChecks.invoke(a,"showDevices",new Class[]{});ManagedChecks.invoke(a,"stopHome",new Class[]{});
                }catch(Exception e){failure.set(e);}});
                test.waitForIdleSync();SystemClock.sleep(300);saveShot(test,folder,"06-home-entries.png");
            }finally{
                test.runOnMainSync(()->{try{
                    previewStore.remove("entry-preview","ms51-preview");
                    ManagedChecks.set(a,"accountId",previewOwner);ManagedChecks.set(a,"username",previewName);
                    ManagedChecks.invoke(a,"showAuth",new Class[]{boolean.class},false);
                }catch(Exception e){failure.set(e);}});
            }
            check(failure.get()==null,String.valueOf(failure.get()));
            report.putString("stream","PASS: first-pair RSSI strength/stability, per-device isolation, stale/invalid samples, known weak reconnect; quiet login, explicit offline entry starts scanner and GIF guide, back/return/background stop scanning, home new-device and offline entries, guide on both routes, signed-in return preserved, empty home quiet, unknown account waits, manual scan/stop and permission cancellation, background requires fresh manual tap, silent discovery popup, GIF rotation, deduplication, multiple-device selection, unpair and reselect, remembered reconnect; automatic scan/lifecycle="+autoChecked+".\n");test.finish(-1,report);
        }catch(Throwable e){report.putString("stream","FAIL: "+e+"\n");test.finish(0,report);}
        finally{if(activity!=null){MainActivity a=activity;test.runOnMainSync(()->{if(sheet.get()!=null)sheet.get().close();a.finish();});}}
    }
    private static void checkDiscoveryPolicy(MainActivity a,boolean radio)throws Exception{
        android.content.SharedPreferences prefs=a.getSharedPreferences("paired_devices_v1",0);
        java.util.Map<String,?> before=prefs.getAll();String owner=(String)ManagedChecks.get(a,"accountId");
        DeviceStore store=(DeviceStore)ManagedChecks.get(a,"deviceStore");
        try {
            ManagedChecks.invoke(a,"endManualDiscovery",new Class[]{});
            prefs.edit().clear().commit();ManagedChecks.set(a,"accountId","");
            check(!a.automaticNearbyAllowed(),"Login requires explicit offline entry even without Wi-Fi device");
            ManagedChecks.set(a,"accountId","discovery-test");
            ManagedChecks.invoke(a,"showDevices",new Class[]{});ManagedChecks.invoke(a,"stopHome",new Class[]{});
            check(!a.automaticNearbyAllowed(),"Unknown account list must not be treated as empty before server response");
            store.replace("discovery-test",new org.json.JSONArray());
            check(!a.automaticNearbyAllowed(),"Empty account also waits for explicit entry");
            store.save("discovery-test",new DeviceStore.Device("ms51-3844be1fe4e0","MS51 1FE4E0","","",""));
            check(!a.automaticNearbyAllowed(),"Paired device suppresses auto discovery even with missing SSID or offline");
            ManagedChecks.invoke(a,"showDevices",new Class[]{});ManagedChecks.invoke(a,"stopHome",new Class[]{});
            ManagedChecks.set(a,"nearbySuppressed",false);
            ManagedChecks.invoke(a,"discoverNearby",new Class[]{boolean.class},false);
            check(ManagedChecks.get(a,"blePicker")==null && !(Boolean)ManagedChecks.get(a,"blePromptPending"),"Paired home never scans or prompts Bluetooth automatically");
            Button scan=findButton(a.getWindow().getDecorView(),"Dò thiết bị mới");check(scan!=null,"Home offers new device entry");
            check(findButton(a.getWindow().getDecorView(),"Chế độ offline")!=null,"Home offers offline entry too");
            if(radio){
                scan.performClick();BlePicker picker=(BlePicker)ManagedChecks.get(a,"blePicker");
                check((Boolean)ManagedChecks.get(a,"bleChooseManually"),"New device entry requires explicit selection");
                check(find(a.getWindow().getDecorView(),DeviceSpinView.class)!=null,"New device entry shows GIF guide");
                scan=findButton(a.getWindow().getDecorView(),"Đang dò");
                check(picker!=null && (Boolean)ManagedChecks.get(a,"bleManualDiscovery"),"Tap starts manual discovery with a paired Wi-Fi device");
                check(scan.getText().toString().contains("Dừng dò"),"Manual scan offers visible progress and stop");
                NearbyDeviceSheet choices=(NearbyDeviceSheet)field(picker,"sheet");
                check(!dialog(choices).isShowing(),"Manual scan does not open popup before discovery");
                android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)a.getSystemService(android.content.Context.BLUETOOTH_SERVICE);
                java.lang.reflect.Method add=BlePicker.class.getDeclaredMethod("add",android.bluetooth.le.ScanResult.class);add.setAccessible(true);
                emitClose(add,picker,manager.getAdapter().getRemoteDevice("AA:00:00:00:00:01"));
                check(dialog(choices).isShowing(),"Manual scan opens popup only after advertisement");
                // Dialog cancellation posts its listener; exercise the callback here
                // without racing later actions in this single UI-thread test block.
                ((Runnable)field(picker,"dismissed")).run();
                check(!(Boolean)ManagedChecks.get(a,"bleManualDiscovery") && scan.getText().toString().equals("Dò thiết bị"),"Dismiss ends manual discovery");
                scan.performClick();scan.performClick();
                check(ManagedChecks.get(a,"blePicker")==null,"Stop button closes scanner");
                scan.performClick();a.onPause();a.onResume();ManagedChecks.invoke(a,"stopHome",new Class[]{});
                ManagedChecks.invoke(a,"discoverNearby",new Class[]{boolean.class},false);
                check(!(Boolean)ManagedChecks.get(a,"bleManualDiscovery") && ManagedChecks.get(a,"blePicker")==null,"Returning from background does not restart manual scan");
                ManagedChecks.set(a,"bleManualDiscovery",true);ManagedChecks.set(a,"blePromptPending",true);
                a.onPause();check((Boolean)ManagedChecks.get(a,"bleManualDiscovery"),"System permission dialog preserves manual intent");
                a.onResume();ManagedChecks.invoke(a,"stopHome",new Class[]{});
                a.onActivityResult(92,Activity.RESULT_CANCELED,null);
                check(!(Boolean)ManagedChecks.get(a,"bleManualDiscovery"),"Cancel Bluetooth enable clears manual intent");
                ManagedChecks.set(a,"bleManualDiscovery",true);ManagedChecks.set(a,"blePromptPending",true);
                a.onRequestPermissionsResult(91,new String[]{"android.permission.BLUETOOTH_SCAN"},new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
                check(!(Boolean)ManagedChecks.get(a,"bleManualDiscovery"),"Denied scan permission ends manual intent");
                a.onBackPressed();ManagedChecks.invoke(a,"stopHome",new Class[]{});
                check("discovery-test".equals(ManagedChecks.get(a,"accountId")),"Returning from new device keeps account");
                check(findButton(a.getWindow().getDecorView(),"Dò thiết bị mới")!=null,"Back returns to home instead of login");
                findButton(a.getWindow().getDecorView(),"Chế độ offline").performClick();
                check(!(Boolean)ManagedChecks.get(a,"bleChooseManually"),"Offline entry permits remembered reconnect");
                check(ManagedChecks.get(a,"blePicker")!=null,"Home offline starts scan immediately");
                findButton(a.getWindow().getDecorView(),"Quay lại thiết bị").performClick();
                ManagedChecks.invoke(a,"stopHome",new Class[]{});
                check(ManagedChecks.get(a,"blePicker")==null && !a.automaticNearbyAllowed(),"Return button stops scan and home remains quiet");
                check("discovery-test".equals(ManagedChecks.get(a,"accountId")),"Offline exit retains signed-in account");
            }
            ManagedChecks.set(a,"accountId","");
            ManagedChecks.invoke(a,"showAuth",new Class[]{boolean.class},false);
            check(!a.automaticNearbyAllowed(),"Login cannot auto discover even with cached Wi-Fi pairings");
            check(findButton(a.getWindow().getDecorView(),"Dò thiết bị")==null,"Login has no scan button");
            Button offline=findButton(a.getWindow().getDecorView(),"Chế độ offline");
            check(offline!=null,"Login offers compact offline entry");
            check(!ManagedChecks.texts(a.getWindow().getDecorView()).contains("Đưa điện thoại"),"Login does not show scanning instructions");
            if(radio){
                ManagedChecks.set(a,"nearbySuppressed",false);
                ManagedChecks.invoke(a,"discoverNearby",new Class[]{boolean.class},false);
                check(ManagedChecks.get(a,"blePicker")==null,"Login refresh cannot start scanning");
                offline.performClick();
                BlePicker picker=(BlePicker)ManagedChecks.get(a,"blePicker");
                check(picker!=null,"Offline tap starts scan without another tap");
                check(ManagedChecks.texts(a.getWindow().getDecorView()).contains("Đưa điện thoại lại gần hộp"),"Offline guide visible while scanning");
                DeviceSpinView guide=find(a.getWindow().getDecorView(),DeviceSpinView.class);
                check(guide!=null && field(guide,"assetName").equals("phone-near-device.gif"),"Offline shows phone approach GIF");
                NearbyDeviceSheet popup=(NearbyDeviceSheet)field(picker,"sheet");
                check(!dialog(popup).isShowing(),"Offline popup still waits for nearby device");
                android.bluetooth.BluetoothManager manager=(android.bluetooth.BluetoothManager)a.getSystemService(android.content.Context.BLUETOOTH_SERVICE);
                java.lang.reflect.Method add=BlePicker.class.getDeclaredMethod("add",android.bluetooth.le.ScanResult.class);add.setAccessible(true);
                emitClose(add,picker,manager.getAdapter().getRemoteDevice("AA:00:00:00:00:01"));
                check(dialog(popup).isShowing(),"Close device opens offline popup");
                a.onBackPressed();
                check(ManagedChecks.get(a,"blePicker")==null && !dialog(popup).isShowing(),"Back closes scanner and popup");
                check(!a.automaticNearbyAllowed(),"Returning to login stays quiet");
                findButton(a.getWindow().getDecorView(),"Chế độ offline").performClick();
                a.onPause();a.onResume();
                check(ManagedChecks.get(a,"blePicker")==null,"Background stops offline scan");
                findButton(a.getWindow().getDecorView(),"Dò thiết bị").performClick();
                check(ManagedChecks.get(a,"blePicker")!=null,"Offline scan can be restarted");
                findButton(a.getWindow().getDecorView(),"Quay lại đăng nhập").performClick();
                check(ManagedChecks.get(a,"blePicker")==null,"Return button also closes scan");
            }
            ManagedChecks.set(a,"accountId","discovery-test");
            check(!a.automaticNearbyAllowed(),"Login remains quiet with stale account identity");
        } finally {
            android.content.SharedPreferences.Editor restore=prefs.edit().clear();
            for(java.util.Map.Entry<String,?> entry:before.entrySet())restore.putString(entry.getKey(),(String)entry.getValue());
            restore.commit();ManagedChecks.set(a,"accountId",owner);
            ManagedChecks.invoke(a,"showAuth",new Class[]{boolean.class},false);
            ManagedChecks.set(a,"nearbySuppressed",true);
        }
    }
    private static void saveShot(Instrumentation test,java.io.File folder,String name)throws Exception {
        android.graphics.Bitmap shot=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
    }
    private static void emitClose(java.lang.reflect.Method add,BlePicker picker,android.bluetooth.BluetoothDevice device)throws Exception {
        NearbyDeviceSheet popup=(NearbyDeviceSheet)field(picker,"sheet");
        boolean visible=dialog(popup).isShowing();
        long now=SystemClock.elapsedRealtimeNanos();
        add.invoke(picker,new android.bluetooth.le.ScanResult(device,null,-80,now-900000000L));
        check(dialog(popup).isShowing()==visible,"Distant unknown box cannot open popup");
        add.invoke(picker,new android.bluetooth.le.ScanResult(device,null,-40,now-800000000L));
        check(dialog(popup).isShowing()==visible,"One strong sample cannot open popup");
        add.invoke(picker,new android.bluetooth.le.ScanResult(device,null,-42,now-400000000L));
        add.invoke(picker,new android.bluetooth.le.ScanResult(device,null,-41,now));
    }
    private static void checkProximity() {
        BleProximity p=new BleProximity();
        check(!p.accept("range",-50,1000,1000),"Expanded range still requires stability");
        check(!p.accept("range",-49,1300,1300),"Expanded range requires third sample");
        check(p.accept("range",-50,1600,1600),"Stable -50 dBm now qualifies");
        check(!p.accept("outside",-51,1000,1000) && !p.accept("outside",-51,1300,1300) && !p.accept("outside",-51,1600,1600),"Weaker than -50 dBm remains outside first-pair range");
        check(!p.accept("a",-80,1000,1000),"Weak sample rejected");
        check(!p.accept("a",-40,1100,1100),"Strong spike waits");
        check(!p.accept("a",-41,1400,1400),"Two samples wait");
        check(!p.accept("b",-40,1700,1700),"Separate devices cannot pool samples");
        check(p.accept("a",-45,1700,1700),"Three strong samples spanning 600ms qualify");
        check(!p.accept("a",-40,1700,1700),"Duplicate rejected");
        check(!p.accept("a",-40,1800,5000),"Stale advertisement rejected");
        check(!p.accept("a",127,5100,5100),"Unavailable RSSI rejected");
        check(!p.accept("a",0,5200,5200),"Zero RSSI rejected");
        check(!p.accept("a",-40,6000,6000),"New window after invalid starts fresh");
        check(!p.accept("a",-80,6300,6300),"Weak sample clears strong streak");
        check(!p.accept("a",-40,6600,6600),"Streak restarts after weak sample");
        check(!p.accept("a",-40,6900,6900),"Still waits for third");
        check(p.accept("a",-40,7200,7200),"Close again qualifies");
        check(!p.accept("b",-40,8000,7900),"Future sample rejected");
        check(!p.accept("b",-40,8000,8000),"Long scan gaps start new window");
    }
    private static Object field(Object object,String name)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static android.graphics.Bitmap drawSpin(NearbyDeviceSheet sheet)throws Exception{
        DeviceSpinView view=find(dialog(sheet).getWindow().getDecorView(),DeviceSpinView.class);
        android.graphics.Bitmap frame=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(frame));return frame;
    }
    private static AlertDialog dialog(NearbyDeviceSheet sheet)throws Exception{return (AlertDialog)field(sheet,"dialog");}
    private static <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){T found=find(g.getChildAt(i),type);if(found!=null)return found;}}return null;}
    private static Button findButton(View view,String text){if(view instanceof Button && ((Button)view).getText().toString().startsWith(text))return (Button)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Button b=findButton(g.getChildAt(i),text);if(b!=null)return b;}}return null;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
