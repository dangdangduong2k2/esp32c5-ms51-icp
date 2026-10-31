package vn.duong.ms51control;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** One transient notice above the page. Does not steal form focus or change scroll layout. */
final class InAppNotice {
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final PopupWindow popup;
    private final TextView message;
    private final Runnable hide=this::dismiss;

    InAppNotice(Activity activity) {
        this.activity=activity;
        LinearLayout panel=new LinearLayout(activity);panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16),dp(12),dp(4),dp(12));
        GradientDrawable background=new GradientDrawable();background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));background.setStroke(dp(1),Design.ACCENT);panel.setBackground(background);
        LinearLayout words=new LinearLayout(activity);words.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(activity);title.setText("Thông báo");title.setTextColor(Design.ACCENT);
        title.setTextSize(14);title.setTypeface(null,Typeface.BOLD);words.addView(title);
        message=new TextView(activity);message.setTextColor(Design.INK);message.setTextSize(16);
        message.setPadding(0,dp(5),0,0);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        words.addView(message);panel.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(activity);close.setText("×");close.setTextSize(24);close.setTextColor(Design.MUTED);
        close.setBackgroundColor(Color.TRANSPARENT);close.setContentDescription("Đóng thông báo");
        panel.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->dismiss());
        popup=new PopupWindow(panel,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,false);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOutsideTouchable(false);popup.setClippingEnabled(true);
    }

    void show(String text) {
        handler.removeCallbacks(hide);
        if(activity.isFinishing() || activity.isDestroyed())return;
        View anchor=activity.getWindow().getDecorView();
        if(!anchor.isAttachedToWindow())return;
        message.setText(text);
        Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);
        int width=Math.min(frame.width()-dp(24),dp(480));
        if(width<=0)return;
        popup.setWidth(width);
        if(!popup.isShowing())popup.showAtLocation(anchor,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,frame.top+dp(12));
        else popup.update(width,ViewGroup.LayoutParams.WRAP_CONTENT);
        int timeout=Math.min(15000,Math.max(6000,text.length()*70));
        if(Build.VERSION.SDK_INT>=29) {
            AccessibilityManager accessibility=activity.getSystemService(AccessibilityManager.class);
            if(accessibility!=null)timeout=accessibility.getRecommendedTimeoutMillis(timeout,
                    AccessibilityManager.FLAG_CONTENT_TEXT|AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        handler.postDelayed(hide,timeout);
    }

    boolean isShowing(){return popup.isShowing();}
    void dismiss(){handler.removeCallbacks(hide);popup.dismiss();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
