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
    private final LinearLayout panel;
    private final android.widget.ImageView symbol;
    private final Runnable hide=this::dismiss;

    InAppNotice(Activity activity) {
        this.activity=activity;
        panel=new LinearLayout(activity);panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(16),dp(6),dp(2),dp(6));
        GradientDrawable background=new GradientDrawable();background.setColor(Color.WHITE);
        background.setCornerRadius(dp(30));panel.setBackground(background);
        symbol=new android.widget.ImageView(activity);symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(symbol,new LinearLayout.LayoutParams(dp(24),dp(24)));
        message=new TextView(activity);message.setTextColor(Design.INK);message.setTextSize(14);
        message.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        message.setPadding(dp(10),dp(6),0,dp(6));message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(message,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(activity);close.setText("×");close.setTextSize(20);close.setTextColor(Design.MUTED);
        close.setBackgroundColor(Color.TRANSPARENT);close.setContentDescription("Đóng thông báo");
        panel.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->dismiss());
        popup=new PopupWindow(panel,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,false);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOutsideTouchable(false);popup.setClippingEnabled(true);
    }

    void show(String text) {
        handler.removeCallbacks(hide);
        if(activity.isFinishing() || activity.isDestroyed())return;
        View anchor=activity.getWindow().getDecorView();
        if(!anchor.isAttachedToWindow())return;
        message.setText(text);
        boolean success=text.startsWith("Đã lưu") || text.startsWith("Đã gửi") || text.startsWith("Đã kết nối") || text.startsWith("Hoàn tất");
        symbol.setImageDrawable(new Design.Icon(success?"check":"info",success?0xFF079957:Design.ACCENT));
        Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);
        int width=Math.min(Math.min(frame.width()-dp(32),dp(420)),Math.max(dp(220),Math.round(message.getPaint().measureText(text))+dp(106)));
        if(width<=0)return;
        popup.setWidth(width);
        if(!popup.isShowing()) {
            popup.showAtLocation(anchor,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,frame.top+dp(8));
            if(Build.VERSION.SDK_INT>=26 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                panel.animate().cancel();panel.setAlpha(0f);panel.setTranslationY(-dp(8));
                panel.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {panel.setAlpha(1f);panel.setTranslationY(0);}
        }
        else popup.update(width,ViewGroup.LayoutParams.WRAP_CONTENT);
        int timeout=Math.min(15000,Math.max(4000,text.length()*70));
        if(Build.VERSION.SDK_INT>=29) {
            AccessibilityManager accessibility=activity.getSystemService(AccessibilityManager.class);
            if(accessibility!=null)timeout=accessibility.getRecommendedTimeoutMillis(timeout,
                    AccessibilityManager.FLAG_CONTENT_TEXT|AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        handler.postDelayed(hide,timeout);
    }

    boolean isShowing(){return popup.isShowing();}
    void dismiss(){handler.removeCallbacks(hide);panel.animate().cancel();popup.dismiss();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
