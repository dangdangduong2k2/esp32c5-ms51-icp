package vn.duong.ms51control;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Editable integer field with cancellable, accelerating press-and-hold arrows. */
final class NumberStepper extends LinearLayout {
    interface Bounds { int[] get(); }
    private final EditText input;
    private final Bounds bounds;
    final Arrow decrease, increase;
    NumberStepper(Context context, EditText input, String label, Bounds bounds) {
        super(context); this.input=input; this.bounds=bounds;
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); setBaselineAligned(false);
        GradientDrawable background=new GradientDrawable(); background.setColor(0xFFF0F4F7); background.setCornerRadius(dp(12)); setBackground(background);
        input.setBackgroundColor(Color.TRANSPARENT); input.setPadding(0,dp(12),0,dp(12)); input.setGravity(Gravity.CENTER);
        input.setHorizontallyScrolling(false); input.setMaxLines(1); input.setHint(null);
        decrease=new Arrow(context,-1,"Giảm "+label); increase=new Arrow(context,1,"Tăng "+label);
        addView(decrease,new LayoutParams(dp(48),dp(52)));
        addView(input,new LayoutParams(0,-2,1));
        addView(increase,new LayoutParams(dp(48),dp(52)));
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void step(int delta) {
        if(!isEnabled() || !input.isEnabled()) return;
        int[] range=bounds.get(); if(range[0]>range[1]) return;
        long current;
        try { current=Long.parseLong(input.getText().toString().trim()); }
        catch(NumberFormatException bad) { current=range[0]; }
        // Clamp before adding, avoiding overflow from an invalid manually typed value.
        current=Math.max(range[0],Math.min(range[1],current));
        long next=Math.max(range[0],Math.min(range[1],current+delta));
        input.setText(String.valueOf(next)); input.setSelection(input.length());
    }
    void stopRepeating() { decrease.stop(); increase.stop(); }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if(input!=null) { input.setEnabled(enabled); decrease.setEnabled(enabled); increase.setEnabled(enabled); if(!enabled)stopRepeating(); }
    }
    @Override protected void onDetachedFromWindow() { stopRepeating(); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if(!focused)stopRepeating(); }

    final class Arrow extends View {
        private final int direction;
        private final Handler handler=new Handler(Looper.getMainLooper());
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean holding, touchClick;
        private long started;
        private final Runnable repeat=new Runnable() {
            public void run() {
                if(!holding || !isEnabled() || !input.isEnabled() || !isAttachedToWindow() || !isShown() || !hasWindowFocus()) { stop(); return; }
                long elapsed=SystemClock.uptimeMillis()-started;
                step(direction*(elapsed>=5000 ? 10 : elapsed>=2000 ? 5 : 1));
                handler.postDelayed(this,elapsed>=5000 ? 50 : elapsed>=2000 ? 90 : 180);
            }
        };
        Arrow(Context context,int direction,String description) {
            super(context);this.direction=direction;setClickable(true);setFocusable(true);setContentDescription(description);
            setOnClickListener(v -> { if(!touchClick) step(direction); });
        }
        @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
        void stop() { holding=false; handler.removeCallbacks(repeat); setPressed(false); invalidate(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(!isEnabled() || !input.isEnabled()) { stop(); return false; }
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopRepeating();holding=true;started=SystemClock.uptimeMillis();setPressed(true);invalidate();
                    step(direction);handler.postDelayed(repeat,450);return true;
                case MotionEvent.ACTION_MOVE:
                    if(event.getX()<0 || event.getX()>getWidth() || event.getY()<0 || event.getY()>getHeight())stop();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean clicked=holding;stop();
                    if(clicked) { touchClick=true;performClick();touchClick=false; }
                    return true;
                case MotionEvent.ACTION_CANCEL:stop();return true;
                case MotionEvent.ACTION_POINTER_DOWN:stop();return true;
                default:return true;
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);float x=getWidth()/2f,y=getHeight()/2f;
            if(isPressed()) { paint.setColor(0x22008878);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(19),paint); }
            paint.setColor(isEnabled()?Design.TEAL:Design.MUTED);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x-direction*dp(3),y-dp(6),x+direction*dp(3),y,paint);
            canvas.drawLine(x+direction*dp(3),y,x-direction*dp(3),y+dp(6),paint);
        }
    }
}
