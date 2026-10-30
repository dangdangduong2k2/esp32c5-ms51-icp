package vn.duong.ms51control;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.Spinner;
import android.widget.TextView;

/** Anchored picker using the same selection/value contract as the settings Spinner. */
final class ChoiceField extends Spinner {
    private ListPopupWindow popup;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ChoiceField(Context context,Object[] values) {
        // Dialog mode avoids Spinner's native touch-forwarding dropdown; performClick owns the picker.
        super(context,Spinner.MODE_DIALOG);
        setAdapter(new ArrayAdapter<Object>(context,android.R.layout.simple_spinner_item,values) {
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                TextView text=label(String.valueOf(getItem(position)));
                text.setPadding(0,dp(10),0,dp(10));
                return text;
            }
        });
        setPadding(dp(14),0,dp(38),0);setMinimumHeight(dp(48));
        background(false);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value){
        TextView text=new TextView(getContext());text.setText(value);text.setTextSize(14);
        text.setTextColor(Design.INK);text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMinHeight(dp(48));return text;
    }
    private GradientDrawable shape(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private void background(boolean open){
        setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),
                shape(open?Design.SOFT:Design.FIELD,open?Design.TEAL:Design.LINE),null));invalidate();
    }
    @Override public boolean performClick(){
        if(!isEnabled()||!isAttachedToWindow())return false;
        if(popup!=null&&popup.isShowing()){popup.dismiss();return true;}
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        ListPopupWindow menu=new ListPopupWindow(getContext());popup=menu;
        menu.setAnchorView(this);menu.setModal(true);
        menu.setWidth(getWidth());menu.setVerticalOffset(dp(6));
        menu.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        GradientDrawable panel=shape(0xFFFFFFFF,Design.LINE);menu.setBackgroundDrawable(panel);
        menu.setAdapter(new BaseAdapter(){
            public int getCount(){return ChoiceField.this.getCount();}
            public Object getItem(int position){return ChoiceField.this.getItemAtPosition(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                boolean selected=position==getSelectedItemPosition();
                LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12),dp(3),dp(10),dp(3));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Design.RIPPLE),
                        shape(selected?Design.SOFT:0xFFFFFFFF,0),null));
                TextView text=label(String.valueOf(getItem(position)));
                text.setTextColor(selected?Design.TEAL:Design.INK);
                text.setTypeface(Typeface.create(selected?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
                text.setPadding(0,dp(10),dp(6),dp(10));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
                TextView tick=label(selected?"✓":"");tick.setTextColor(Design.TEAL);tick.setTextSize(18);
                tick.setGravity(Gravity.CENTER);tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(tick,new LinearLayout.LayoutParams(dp(22),-2));
                row.setContentDescription(text.getText()+(selected?", đang chọn":""));
                return row;
            }
        });
        menu.setOnItemClickListener((parent,view,position,id)->{
            if(isEnabled()&&isAttachedToWindow())setSelection(position);
            menu.dismiss();
        });
        menu.setOnDismissListener(()->{if(popup==menu){popup=null;background(false);}});
        background(true);menu.show();menu.setSelection(getSelectedItemPosition());
        return true;
    }
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);
        float x=getWidth()-dp(22),y=getHeight()/2f;
        boolean open=popup!=null&&popup.isShowing();
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(isEnabled()?Design.TEAL:Design.MUTED);
        Path arrow=new Path();float offset=open?dp(2):-dp(2);
        arrow.moveTo(x-dp(4),y+offset);arrow.lineTo(x,y-offset);arrow.lineTo(x+dp(4),y+offset);
        canvas.drawPath(arrow,paint);
    }
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled&&popup!=null)popup.dismiss();invalidate();}
    @Override protected void onDetachedFromWindow(){if(popup!=null)popup.dismiss();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&popup!=null)popup.dismiss();}
}
