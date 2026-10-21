package vn.duong.ms51control;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.concurrent.atomic.AtomicInteger;

final class StepperChecks {
    static void run(Instrumentation test) {
        Bundle result=new Bundle();MainActivity a=null;
        try {
            a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity=a; NumberStepper[] holder=new NumberStepper[1]; EditText[] field=new EditText[1];LinearLayout[] page=new LinearLayout[1];
            test.runOnMainSync(() -> {
                page[0]=new LinearLayout(activity);field[0]=new EditText(activity);field[0].setText("100");
                holder[0]=new NumberStepper(activity,field[0],"Thời gian",()->new int[]{1,999});page[0].addView(holder[0]);activity.setContentView(page[0]);
            });
            test.waitForIdleSync();SystemClock.sleep(300);
            NumberStepper stepper=holder[0];EditText input=field[0];
            touch(test,stepper.increase,MotionEvent.ACTION_DOWN);touch(test,stepper.increase,MotionEvent.ACTION_UP);
            check(value(test,input)==101,"Tap increments exactly one");
            touch(test,stepper.decrease,MotionEvent.ACTION_DOWN);touch(test,stepper.decrease,MotionEvent.ACTION_UP);
            check(value(test,input)==100,"Tap decrements exactly one");
            touch(test,stepper.increase,MotionEvent.ACTION_DOWN);SystemClock.sleep(1200);
            int slow=value(test,input);check(slow>101 && slow<115,"Short hold repeats slowly");
            SystemClock.sleep(4400);int fast=value(test,input);check(fast>slow+100,"Long hold accelerates");
            touch(test,stepper.increase,MotionEvent.ACTION_UP);int released=value(test,input);SystemClock.sleep(400);check(value(test,input)==released,"Release stops without an extra click");
            touch(test,stepper.decrease,MotionEvent.ACTION_DOWN);SystemClock.sleep(600);touch(test,stepper.decrease,MotionEvent.ACTION_CANCEL);
            int cancelled=value(test,input);SystemClock.sleep(400);check(value(test,input)==cancelled,"Scroll cancellation stops repeat");
            test.runOnMainSync(()->{input.setText("998");stepper.increase.performClick();stepper.increase.performClick();});
            check(value(test,input)==999,"Upper bound");
            test.runOnMainSync(()->{input.setText("1");stepper.decrease.performClick();});check(value(test,input)==1,"Lower bound");
            test.runOnMainSync(()->{input.setText("42");stepper.increase.performClick();});check(value(test,input)==43,"Typed value is used");
            touch(test,stepper.increase,MotionEvent.ACTION_DOWN);test.runOnMainSync(()->stepper.setEnabled(false));int disabled=value(test,input);
            SystemClock.sleep(600);test.runOnMainSync(()->stepper.increase.performClick());check(value(test,input)==disabled,"Disable stops repeat and blocks arrows");
            test.runOnMainSync(()->stepper.setEnabled(true));touch(test,stepper.increase,MotionEvent.ACTION_DOWN);
            test.runOnMainSync(()->page[0].removeView(stepper));int detached=value(test,input);SystemClock.sleep(600);check(value(test,input)==detached,"Navigation detaches and stops repeat");
            test.runOnMainSync(()->{
                EditText temperature=new EditText(activity);temperature.setText("-0,5");
                NumberStepper decimal=new NumberStepper(activity,temperature,"Ngưỡng",()->new int[]{-55,125},true);
                page[0].addView(decimal);
                decimal.increase.performClick();check(temperature.getText().toString().equals("0.5"),"Temperature +1 preserves fraction and crosses zero");
                decimal.decrease.performClick();check(temperature.getText().toString().equals("-0.5"),"Temperature -1 preserves fraction");
                temperature.setText("124.5");decimal.increase.performClick();check(temperature.getText().toString().equals("125"),"Temperature upper bound");
                temperature.setText("-54.5");decimal.decrease.performClick();check(temperature.getText().toString().equals("-55"),"Temperature lower bound");
                decimal.setEnabled(false);decimal.increase.performClick();check(temperature.getText().toString().equals("-55"),"Disabled temperature arrows blocked");
            });
            result.putString("stream","PASS: single-unit taps, accelerating hold, release/cancel/disable/detach stop, bounded values, manual editing and accessibility click; decimal temperature +/-1, comma, negative values, zero crossing and -55/125 bounds.");test.finish(-1,result);
        } catch(Throwable error) { result.putString("stream","FAIL: "+error);test.finish(0,result); }
        finally {if(a!=null){MainActivity activity=a;test.runOnMainSync(activity::finish);}}
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    private static int value(Instrumentation test,EditText input) {AtomicInteger result=new AtomicInteger();test.runOnMainSync(()->result.set(Integer.parseInt(input.getText().toString())));return result.get();}
    private static void touch(Instrumentation test,android.view.View button,int action) {
        test.runOnMainSync(()->{long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,button.getWidth()/2f,button.getHeight()/2f,0);button.dispatchTouchEvent(event);event.recycle();});
    }
}
