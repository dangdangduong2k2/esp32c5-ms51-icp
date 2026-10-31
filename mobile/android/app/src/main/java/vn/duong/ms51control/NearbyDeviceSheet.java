package vn.duong.ms51control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.util.HashSet;

/** Appears only after a compatible controller is actually found. */
final class NearbyDeviceSheet implements AutoCloseable {
    private final Activity activity;
    private final AlertDialog dialog;
    private final LinearLayout content, others;
    private final HashSet<String> addresses=new HashSet<>();
    private final Runnable dismissed;
    private boolean closed, selected;
    NearbyDeviceSheet(Activity activity,Runnable dismissed) {
        this.activity=activity;this.dismissed=dismissed;
        content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(12),dp(20),dp(20));content.setBackground(shape(Color.WHITE,26));
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label("Thiết bị ở gần",20,true);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(activity);close.setText("×");close.setTextSize(26);close.setTextColor(Design.MUTED);
        close.setContentDescription("Đóng thiết bị ở gần");close.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),shape(Design.FIELD,24),null));
        heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(heading);
        others=new LinearLayout(activity);others.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        dialog=new AlertDialog.Builder(activity).setView(scroll).create();
        dialog.setOnDismissListener(d->{if(!closed){closed=true;dismissed.run();}});
        close.setOnClickListener(v->dialog.dismiss());
    }
    void addDevice(String address,String name,Runnable connect) {
        if(closed || !addresses.add(address))return;
        Runnable select=()->{if(closed || selected)return;selected=true;close();connect.run();};
        if(addresses.size()==1) {
            DeviceSpinView spin=new DeviceSpinView(activity);
            spin.setContentDescription("Kết nối "+name);spin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            spin.setFocusable(true);spin.setOnClickListener(v->select.run());
            content.addView(spin,new LinearLayout.LayoutParams(-1,dp(190)));
            TextView title=label("Hộp hẹn giờ máy lạnh",21,true);title.setGravity(Gravity.CENTER);content.addView(title);
            TextView device=label(name+" · "+address.substring(Math.max(0,address.length()-8)),14,false);
            device.setTextColor(Design.MUTED);device.setGravity(Gravity.CENTER);content.addView(device,margin(0,6,0,14));
            Button button=action("Kết nối",true);button.setOnClickListener(v->select.run());content.addView(button,margin(0,0,0,8));
            TextView help=label("Chạm vào hộp để điều khiển ngay",14,false);help.setGravity(Gravity.CENTER);help.setTextColor(Design.MUTED);content.addView(help);
            content.addView(others);
            show();
        } else {
            if(addresses.size()==2){TextView more=label("Thiết bị khác ở gần",14,true);others.addView(more,margin(0,18,0,4));}
            Button row=action(name+" · "+address.substring(Math.max(0,address.length()-8)),false);
            row.setContentDescription("Kết nối "+name+" "+address);row.setOnClickListener(v->select.run());others.addView(row,margin(0,6,0,0));resize();
        }
    }
    void showConnecting(String name) {
        if(closed)return;
        selected=true;
        DeviceSpinView spin=new DeviceSpinView(activity);
        spin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(spin,new LinearLayout.LayoutParams(-1,dp(190)));
        TextView device=label(name,20,true);device.setGravity(Gravity.CENTER);content.addView(device,margin(0,4,0,12));
        ProgressBar progress=new ProgressBar(activity);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(28),dp(28));size.gravity=Gravity.CENTER_HORIZONTAL;
        content.addView(progress,size);
        TextView status=label("Đang kết nối…",16,true);status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status,margin(0,12,0,6));
        TextView help=label("Kết nối xong sẽ tự mở Tổng quan",14,false);help.setGravity(Gravity.CENTER);help.setTextColor(Design.MUTED);content.addView(help);
        show();
    }
    private void show() {
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params=window.getAttributes();params.dimAmount=.32f;params.y=dp(16);window.setAttributes(params);resize();
        }
    }
    private void resize() {
        Window window=dialog.getWindow();if(window==null)return;
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        int width=Math.min(metrics.widthPixels-dp(24),dp(480));
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        window.setLayout(width,Math.min(content.getMeasuredHeight()+dp(12),(int)(metrics.heightPixels*.82f)));
    }
    private TextView label(String text,int size,boolean bold){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);v.setTextColor(Design.INK);if(bold)v.setTypeface(null,Typeface.BOLD);return v;}
    private Button action(String text,boolean primary){Button b=new Button(activity);b.setText(text);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(52));b.setTextColor(primary?Color.WHITE:Design.TEAL);b.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),shape(primary?Design.TEAL:Design.FIELD,14),null));return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    @Override public void close(){closed=true;dialog.dismiss();}
}
