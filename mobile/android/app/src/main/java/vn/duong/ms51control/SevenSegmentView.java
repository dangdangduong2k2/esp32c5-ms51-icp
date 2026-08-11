package vn.duong.ms51control;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Native three-digit seven-segment display used for the two MS51 timers. */
final class SevenSegmentView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String text = "---";
    private int segmentColor = Color.rgb(52, 211, 153);

    SevenSegmentView(Context context) {
        super(context);
        init();
    }

    SevenSegmentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        setMinimumHeight(dp(70));
    }

    void setSegmentColor(int color) {
        segmentColor = color;
        invalidate();
    }

    void setValue(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.matches("\\d+")) {
            raw = raw.length() > 3 ? raw.substring(raw.length() - 3) : raw;
            text = String.format("%3s", raw);
        } else {
            text = "---";
        }
        invalidate();
        setContentDescription("Hiển thị " + (raw.matches("\\d+") ? raw : "chưa có dữ liệu"));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = dp(180);
        int desiredHeight = dp(76);
        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        if (height * 2.1f < width) width = Math.min(width, Math.round(height * 2.1f));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = dp(4);
        float gap = dp(5);
        float digitWidth = (getWidth() - padding * 2 - gap * 2) / 3f;
        float digitHeight = getHeight() - padding * 2;
        for (int index = 0; index < 3; index++) {
            drawDigit(canvas, text.charAt(index), padding + index * (digitWidth + gap), padding, digitWidth, digitHeight);
        }
    }

    private void drawDigit(Canvas canvas, char character, float x, float y, float width, float height) {
        float thickness = Math.max(dp(5), width * 0.15f);
        float inset = thickness * 0.42f;
        float middle = y + height / 2f;
        String active = segments(character);
        int inactive = Color.argb(35, Color.red(segmentColor), Color.green(segmentColor), Color.blue(segmentColor));

        paint.setColor(active.indexOf('a') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x + inset, y, x + width - inset, y + thickness), thickness / 2f, thickness / 2f, paint);
        paint.setColor(active.indexOf('g') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x + inset, middle - thickness / 2f, x + width - inset, middle + thickness / 2f), thickness / 2f, thickness / 2f, paint);
        paint.setColor(active.indexOf('d') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x + inset, y + height - thickness, x + width - inset, y + height), thickness / 2f, thickness / 2f, paint);

        paint.setColor(active.indexOf('f') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x, y + inset, x + thickness, middle - inset), thickness / 2f, thickness / 2f, paint);
        paint.setColor(active.indexOf('b') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x + width - thickness, y + inset, x + width, middle - inset), thickness / 2f, thickness / 2f, paint);
        paint.setColor(active.indexOf('e') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x, middle + inset, x + thickness, y + height - inset), thickness / 2f, thickness / 2f, paint);
        paint.setColor(active.indexOf('c') >= 0 ? segmentColor : inactive);
        canvas.drawRoundRect(new RectF(x + width - thickness, middle + inset, x + width, y + height - inset), thickness / 2f, thickness / 2f, paint);
    }

    private static String segments(char value) {
        switch (value) {
            case '0': return "abcdef";
            case '1': return "bc";
            case '2': return "abdeg";
            case '3': return "abcdg";
            case '4': return "bcfg";
            case '5': return "acdfg";
            case '6': return "acdefg";
            case '7': return "abc";
            case '8': return "abcdefg";
            case '9': return "abcdfg";
            default: return "g";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
