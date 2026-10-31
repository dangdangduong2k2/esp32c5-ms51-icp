package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.concurrent.atomic.AtomicReference;

/** Notifications stay outside the scrolling form and replace one another. No hardware I/O. */
final class NoticeChecks {
    static void run(Instrumentation test) {
        Bundle result=new Bundle();MainActivity activity=null;
        try {
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity;
            test.waitForIdleSync();android.os.SystemClock.sleep(300);
            AtomicReference<Throwable> error=new AtomicReference<>();
            test.runOnMainSync(()->{try {
                ManagedChecks.set(a,"foreground",true);
                ManagedChecks.invoke(a,"showAuth",new Class[]{boolean.class},false);
            }catch(Throwable e){error.set(e);}});
            test.waitForIdleSync();
            test.runOnMainSync(()->{try {
                EditText input=(EditText)ManagedChecks.get(a,"accountName");
                input.requestFocus();input.setText("nguoidung");input.setSelection(4);
                LinearLayout page=(LinearLayout)ManagedChecks.get(a,"pageRoot");
                ScrollView scroll=null;for(int i=0;i<page.getChildCount();i++)if(page.getChildAt(i) instanceof ScrollView)scroll=(ScrollView)page.getChildAt(i);
                if(scroll==null)throw new AssertionError("Missing page scroll");
                int children=((LinearLayout)scroll.getChildAt(0)).getChildCount(),position=scroll.getScrollY();
                ManagedChecks.invoke(a,"showMessage",new Class[]{String.class},"Đang lưu cài đặt…");
                InAppNotice notice=(InAppNotice)ManagedChecks.get(a,"notice");
                ManagedChecks.check(notice.isShowing(),"Notice opens above login page");
                ManagedChecks.invoke(a,"showMessage",new Class[]{String.class},"Đã lưu cài đặt.");
                ManagedChecks.check(ManagedChecks.get(a,"notice")==notice && notice.isShowing(),"New notice updates one popup");
                ManagedChecks.check(!ManagedChecks.texts(page).contains("Đã lưu cài đặt."),"Notification never enters page content");
                ManagedChecks.check(children==((LinearLayout)scroll.getChildAt(0)).getChildCount() && position==scroll.getScrollY(),"Popup leaves page layout and scroll intact");
                ManagedChecks.check(input.hasFocus() && input.getSelectionStart()==4,"Popup preserves editing focus and caret");
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw error.get();
            test.waitForIdleSync();android.os.SystemClock.sleep(200);
            android.graphics.Bitmap shot=test.getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),"notice-preview.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            test.runOnMainSync(()->{try {
                InAppNotice notice=(InAppNotice)ManagedChecks.get(a,"notice");notice.dismiss();
                ManagedChecks.check(!notice.isShowing(),"Notice is dismissible");
                ManagedChecks.set(a,"foreground",false);
                ManagedChecks.invoke(a,"showMessage",new Class[]{String.class},"Không hiển thị khi chạy nền");
                ManagedChecks.check(!notice.isShowing(),"Background callback cannot open popup");
            }catch(Throwable e){error.set(e);}});
            if(error.get()!=null)throw error.get();
            result.putString("stream","PASS: floating notice, replacement without stacking, no inline copy, stable form layout/focus/caret, dismissal and background guard.");
            test.finish(-1,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);test.finish(0,result);}
        finally{if(activity!=null){MainActivity a=activity;test.runOnMainSync(a::finish);}}
    }
}
