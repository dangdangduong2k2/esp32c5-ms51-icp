package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Real attached dashboard: allow layout/pre-draw between background updates. No device commands. */
final class ScrollChecks {
    interface Work { void run() throws Exception; }
    static void main(Instrumentation t, Work work) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        t.runOnMainSync(() -> { try {work.run();} catch(Throwable e){error.set(e);} });
        if(error.get()!=null) throw new AssertionError(error.get());
        t.waitForIdleSync(); SystemClock.sleep(120);
    }
    static void run(Instrumentation t) {
        Bundle result=new Bundle(); MainActivity activity=null;
        try {
            t.setInTouchMode(true);
            activity=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity a=activity;
            EspApi demo=new EspApi();demo.setDemo(true);
            JSONObject status=demo.get("/api/status"),debug=demo.get("/api/debug");
            JSONObject trigger=new JSONObject().put("enabled",true).put("thermal_supported",true).put("safety_supported",true)
                .put("thermal_enabled",true).put("defrost_enabled",true).put("safety_enabled",true).put("source","10K").put("threshold_centi",3200)
                .put("defrost_centi",500).put("sensor_valid",true).put("synced",true).put("defrost_valid",true);
            status.put("trigger",trigger).put("busy",false);
            ScrollView[] scroll={null}; EditText[] field={null};
            main(t,() -> {
                ManagedChecks.invoke(a,"stopDashboard",new Class[]{});
                ManagedChecks.set(a,"lastStatus",null);ManagedChecks.set(a,"lastDebug",null);
                ManagedChecks.set(a,"activeDevice",new DeviceStore.Device("preview","Test","","",""));
                Method page=MainActivity.class.getDeclaredMethod("createPage",String.class,String.class,String.class);page.setAccessible(true);
                LinearLayout content=(LinearLayout)page.invoke(a,"","Tổng quan","");
                ManagedChecks.invoke(a,"buildOverview",new Class[]{LinearLayout.class},content);
                scroll[0]=(ScrollView)content.getParent();
                field[0]=(EditText)ManagedChecks.get(a,"defrostThreshold");
            });
            main(t,() -> scroll[0].scrollTo(0,0));
            for(int i=0;i<4;i++) {
                final int n=i;
                main(t,() -> {
                    trigger.put("synced",n%2==0);
                    ManagedChecks.set(a,"lastStatus",status);ManagedChecks.set(a,"lastDebug",debug);
                    ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);
                    ManagedChecks.invoke(a,"renderHistory",new Class[]{});
                });
                main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==0,"Idle polling moved scroll to "+scroll[0].getScrollY()+"; focus="+a.getCurrentFocus()));
            }
            main(t,() -> {field[0].requestFocus();field[0].setText("8.25");field[0].setSelection(2);});
            main(t,() -> ManagedChecks.check(field[0].hasFocus() && scroll[0].getScrollY()>0,"Explicit input focus still reveals field"));
            main(t,() -> ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug));
            main(t,() -> ManagedChecks.check(field[0].hasFocus() && field[0].getSelectionStart()==2,"Visible editing keeps focus and caret during polling"));
            main(t,() -> scroll[0].scrollTo(0,0));
            for(int i=0;i<5;i++) {
                final int n=i;
                main(t,() -> {
                    trigger.put("synced",n%2==0);
                    ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);
                    ManagedChecks.invoke(a,"renderHistory",new Class[]{});
                });
                main(t,() -> {
                    ManagedChecks.check(scroll[0].getScrollY()==0,"Polling after editing moved scroll to "+scroll[0].getScrollY());
                    ManagedChecks.check(field[0].getText().toString().equals("8.25") && field[0].getSelectionStart()==2,"Polling preserves draft and caret");
                });
            }
            // Losing and regaining connectivity must not focus an input below the viewport.
            for(int i=0;i<4;i++) {
                main(t,() -> ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},null,null));
                main(t,() -> ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug));
                main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==0,"Reconnect moved scroll to "+scroll[0].getScrollY()));
            }
            // Late Save acknowledgement reloads the confirmed values while user has scrolled away.
            main(t,() -> field[0].requestFocus());
            main(t,() -> scroll[0].scrollTo(0,0));
            main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==0,"Save test starts at top"));
            main(t,() -> {
                ManagedChecks.set(a,"triggerLoaded",false);
                ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);
            });
            main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==0,"Late saved values moved scroll to "+scroll[0].getScrollY()));
            // Copyable history can keep focus after it has been scrolled out of sight.
            main(t,() -> ((View)ManagedChecks.get(a,"dashboardLogs")).requestFocus());
            main(t,() -> scroll[0].scrollTo(0,0));
            main(t,() -> {
                long[] logs=new long[30];logs[0]=7620;
                ManagedChecks.set(a,"lastHistory",logs);
                ManagedChecks.invoke(a,"renderHistory",new Class[]{});
            });
            main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==0,"Background history moved scroll to "+scroll[0].getScrollY()));
            main(t,() -> field[0].requestFocus());
            main(t,() -> scroll[0].scrollTo(0,200));
            main(t,() -> {
                ManagedChecks.set(a,"triggerLoaded",false);
                ManagedChecks.invoke(a,"renderDashboard",new Class[]{JSONObject.class,JSONObject.class},status,debug);
            });
            main(t,() -> ManagedChecks.check(scroll[0].getScrollY()==200,"Mid-page position survives late save"));
            result.putString("stream","PASS: attached dashboard preserves top/mid-page scroll during initial polling, late Save acknowledgement, history changes and disconnect/reconnect; explicit input focus, visible editing and draft/caret preserved.");
            t.finish(-1,result);
        } catch(Throwable e) {result.putString("stream","FAIL: "+e);t.finish(0,result);}
        finally {if(activity!=null){MainActivity a=activity;t.runOnMainSync(a::finish);}}
    }
}
