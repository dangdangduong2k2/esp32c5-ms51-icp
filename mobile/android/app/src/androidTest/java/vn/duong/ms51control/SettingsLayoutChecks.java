package vn.duong.ms51control;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.CheckBox;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;

/** Settings grouping and permission regression checks; no device writes. */
final class SettingsLayoutChecks {
    static void run(MainActivity a) throws Exception {
        Object previousAccess=ManagedChecks.get(a,"deviceAccess");
        Object previousConfig=ManagedChecks.get(a,"runtimeConfig");
        RuntimeConfigCodec.Config config=RuntimeConfigCodec.defaults();
        JSONArray allowed=new JSONArray();
        for(String key:config.values.keySet()) {
            if(!Arrays.asList("showDf","showEnd","showSl","showHcf","showHdf").contains(key)) allowed.put(key);
        }
        ManagedChecks.set(a,"runtimeConfig",config);
        ManagedChecks.set(a,"deviceAccess",new JSONObject().put("visible",allowed));
        LinearLayout page=new LinearLayout(a);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32,32,32,32);page.setBackgroundColor(Design.BG);
        ManagedChecks.invoke(a,"buildSettings",new Class[]{LinearLayout.class},page);
        Map<String,EditText> numbers=(Map<String,EditText>)ManagedChecks.get(a,"configInputs");
        View timing=page.getChildAt(1), advanced=page.getChildAt(2);
        HashSet<String> actual=new HashSet<>();
        for(Map.Entry<String,EditText> field:numbers.entrySet()) if(inside(field.getValue(),timing)) actual.add(field.getKey());
        ManagedChecks.check(actual.equals(new HashSet<>(Arrays.asList("coolCl","coolOp","defrostCl","defrostOp"))),"Timing contains exactly four cycle parameters");
        ManagedChecks.check(inside(numbers.get("hcfTime"),advanced) && inside(numbers.get("tryTime"),advanced),"Other timing fields are in advanced settings");
        for(String key:Arrays.asList("delaySt","stLed","stLcd")) ManagedChecks.check(inside(numbers.get(key),advanced),"ST timing belongs in advanced settings: "+key);
        java.lang.reflect.Method visible=MainActivity.class.getDeclaredMethod("settingVisible",String.class);visible.setAccessible(true);
        for(String key:Arrays.asList("showSt","defrostOpSeconds")) ManagedChecks.check(Boolean.FALSE.equals(visible.invoke(a,key)),"Admin-only option stays hidden even with old policy: "+key);
        View startup=page.findViewWithTag("settings-group:Khởi động");
        ManagedChecks.check(startup!=null,"Startup has a collapsible heading");
        View startupFields=(View)((ViewGroup)startup.getParent()).getChildAt(((ViewGroup)startup.getParent()).indexOfChild(startup)+1);
        ManagedChecks.check(startupFields.getVisibility()==View.GONE,"Startup begins collapsed");
        startup.performClick();
        ManagedChecks.check(startupFields.getVisibility()==View.VISIBLE,"Startup opens on tap");
        String old=numbers.get("delaySt").getText().toString();
        numbers.get("delaySt").setText("73");startup.performClick();startup.performClick();
        ManagedChecks.check(numbers.get("delaySt").getText().toString().equals("73"),"Collapse preserves unsaved startup value");
        numbers.get("delaySt").setText(old);
        expandGroups(advanced);
        if(a.getResources().getConfiguration().screenWidthDp>=360 && a.getResources().getConfiguration().fontScale<1.3f) {
            for(String[] pair:new String[][]{{"coolCl","coolOp"},{"defrostCl","defrostOp"},{"delaySt","hcfTime"},{"stLed","stLcd"},{"rangeCoolClMin","rangeCoolClMax"}})
                ManagedChecks.check(row(numbers.get(pair[0]))==row(numbers.get(pair[1])),"Related settings share a row: "+pair[0]);
        }
        java.lang.reflect.Method collect=MainActivity.class.getDeclaredMethod("collectRuntimeConfigValues");collect.setAccessible(true);
        ManagedChecks.check(collect.invoke(a).equals(config.values),"Rearranging settings preserves all values");
        Map<String,CheckBox> checks=(Map<String,CheckBox>)ManagedChecks.get(a,"configChecks");
        checks.get("pinEnable").setChecked(false);
        ManagedChecks.check(!numbers.get("pinPassword").isEnabled(),"Nested PIN fields disable correctly");
        checks.get("pinEnable").setChecked(true);
        ManagedChecks.check(numbers.get("pinPassword").isEnabled(),"Nested PIN fields enable correctly");
        capture(a,page,"settings-preview.png",2600);
        for(EditText field:numbers.values()) ManagedChecks.check(field.getWidth()>0 && field.getHeight()>0 && field.getLayout().getLineLeft(0)<field.getWidth(),"Numeric value remains visible: "+field.getContentDescription());
        capture(a,(View)advanced,"settings-advanced-preview.png",10000);

        ManagedChecks.set(a,"deviceAccess",new JSONObject().put("visible",new JSONArray().put("coolCl").put("rangeCoolClMin")));
        LinearLayout limited=new LinearLayout(a);limited.setOrientation(LinearLayout.VERTICAL);
        ManagedChecks.invoke(a,"buildSettings",new Class[]{LinearLayout.class},limited);
        ManagedChecks.check(limited.getChildAt(1).getVisibility()==View.VISIBLE && limited.getChildAt(2).getVisibility()==View.GONE,"Nested allowed fields remain visible, empty advanced section hidden");
        Map<String,EditText> limitedNumbers=(Map<String,EditText>)ManagedChecks.get(a,"configInputs");
        ViewGroup loneRow=row(limitedNumbers.get("coolCl"));
        ManagedChecks.check(loneRow.getChildCount()==1 && limitedNumbers.size()==2,"Hidden fields leave no empty cells");
        ManagedChecks.set(a,"deviceAccess",previousAccess);
        ManagedChecks.set(a,"runtimeConfig",previousConfig);
    }
    private static boolean inside(View child,View ancestor) {
        for(View current=child;current!=null;current=current.getParent() instanceof View ? (View)current.getParent() : null) if(current==ancestor)return true;
        return false;
    }
    static void expandGroups(View view) {
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                View child=group.getChildAt(i);
                if(child.getTag() instanceof String && ((String)child.getTag()).startsWith("settings-group:")
                        && i+1<group.getChildCount() && group.getChildAt(i+1).getVisibility()==View.GONE) child.performClick();
                expandGroups(child);
            }
        }
    }
    private static ViewGroup row(EditText field) { return (ViewGroup)field.getParent().getParent().getParent(); }
    private static void capture(MainActivity a,View view,String name,int maxHeight) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,1080,view.getMeasuredHeight());
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1080,Math.min(maxHeight,view.getMeasuredHeight()),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(image));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        }
        image.recycle();
    }
}
