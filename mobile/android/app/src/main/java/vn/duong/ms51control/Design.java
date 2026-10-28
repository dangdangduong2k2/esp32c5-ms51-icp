package vn.duong.ms51control;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector artwork keeps every icon crisp without external UI dependencies. */
final class Design {
    static final int NAVY = 0xFF102532;
    static final int INK = 0xFF102F43;
    static final int MUTED = 0xFF526777;
    static final int TEAL = 0xFF087BB8;
    static final int MINT = 0xFFB5E7FF;
    static final int BG = 0xFFEFF7FB;
    static final int LINE = 0xFFD9E7EF;
    static final int ACCENT = 0xFF087BB8;
    static final int SURFACE = Color.WHITE;
    static final int FIELD = 0xFFF1F7FB;
    static final int SOFT = 0xFFE0F2FC;
    static final int RIPPLE = 0x26087BB8;

    static final class Hero extends Drawable {
        private final Paint paint = new Paint(3);
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setAlpha(255); paint.setStyle(Paint.Style.FILL);
            paint.setShader(null); paint.setColor(NAVY);
            canvas.drawRoundRect(new RectF(b),16,16,paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Four signal levels: dot plus three arcs. No reading uses a crossed-out icon. */
    static final class WifiSignal extends Drawable {
        private final Paint paint = new Paint(3);
        private final int level, color;
        private int alpha = 255;
        WifiSignal(int level, int color) { this.level = level; this.color = color; }
        static int level(boolean connected, int rssi) {
            if (!connected || rssi >= 0 || rssi < -126) return 0;
            return rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -75 ? 2 : 1;
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save(); canvas.translate(b.left, b.top); canvas.scale(b.width()/24f, b.height()/24f);
            paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            for (int arc = 1; arc <= 3; arc++) {
                paint.setAlpha(level >= arc + 1 ? alpha : alpha * 28 / 100);
                float radius = 4 + arc * 4;
                canvas.drawArc(12-radius,20-radius,12+radius,20+radius,225,90,false,paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(level > 0 ? alpha : alpha * 28 / 100);
            canvas.drawCircle(12,20,1.3f,paint);
            if (level == 0) {
                paint.setStyle(Paint.Style.STROKE); paint.setAlpha(alpha);
                canvas.drawLine(4,4,20,22,paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Icon extends Drawable {
        private final Paint p = new Paint(3);
        private final String name;
        private final int color;
        Icon(String name, int color) { this.name = name; this.color = color; }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.save(); c.translate(b.left, b.top); c.scale(b.width()/24f, b.height()/24f);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.65f);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch (name) {
                case "home":
                    path(c, 3,11,12,3,21,11); path(c,5,10,5,21,10,21,10,15,14,15,14,21,19,21,19,10); break;
                case "settings":
                    for(int i=0;i<3;i++){ float x=5+i*7; c.drawLine(x,3,x,21,p); }
                    p.setStyle(Paint.Style.FILL); c.drawCircle(5,9,3,p); c.drawCircle(12,16,3,p); c.drawCircle(19,7,3,p); break;
                case "upload":
                    path(c,8,8,12,4,16,8); path(c,12,4,12,16); path(c,4,14,4,20,20,20,20,14); break;
                case "wifi":
                    c.drawArc(1,3,23,23,225,90,false,p); c.drawArc(5,8,19,22,225,90,false,p);
                    c.drawArc(9,13,15,19,225,90,false,p); p.setStyle(Paint.Style.FILL); c.drawCircle(12,20,1,p); break;
                case "brand":
                    p.setStrokeWidth(2.5f);
                    c.drawArc(3,3,21,21,55,285,false,p);
                    path(c,12,6,12,12,8,15);
                    c.save(); c.translate(17.5f,17.5f); p.setStrokeWidth(1.45f);
                    for(int i=0;i<6;i++) { c.save();c.rotate(i*60);path(c,0,0,0,-4.3f);path(c,-1.5f,-3,0,-1.8f,1.5f,-3);c.restore(); }
                    c.restore(); break;
                case "chevron-down": path(c,5,9,12,16,19,9); break;
                case "chevron-up": path(c,5,15,12,8,19,15); break;
                case "device":
                    c.drawRoundRect(3,3,21,21,3,3,p);path(c,7,8,17,8);path(c,7,12,17,12);
                    p.setStyle(Paint.Style.FILL); c.drawCircle(7,17,1,p);c.drawCircle(12,17,1,p);c.drawCircle(17,17,1,p);break;
                case "plug":
                    path(c,8,2,8,7);path(c,16,2,16,7);path(c,5,7,19,7,19,11,15,15,9,15,5,11,5,7);path(c,12,15,12,22);break;
                case "defrost":
                    path(c,9,2,16,6,16,13,9,17,2,13,2,6,9,2);
                    path(c,2,6,9,10,16,6);path(c,9,10,9,17);
                    Path drop=new Path();drop.moveTo(19,12);drop.cubicTo(18,14,15.8f,17,15.8f,18.5f);drop.cubicTo(15.8f,23,22.2f,23,22.2f,18.5f);drop.cubicTo(22.2f,17,20,14,19,12);c.drawPath(drop,p);break;
                case "back": path(c,15,5,8,12,15,19); break;
                case "lock": c.drawRoundRect(5,10,19,22,2,2,p);c.drawArc(8,2,16,16,180,180,false,p);path(c,8,9,8,6);path(c,16,6,16,9);break;
                case "arrow": path(c,5,12,19,12); path(c,14,7,19,12,14,17); break;
                case "swap":
                    path(c,4,7,20,7,16,3); path(c,20,17,4,17,8,21); break;
                case "refresh":
                    c.drawArc(4,4,20,20,40,285,false,p); path(c,16,3,20,5,19,9); break;
                case "snowflake":
                    for (int i=0;i<6;i++) {
                        c.save(); c.rotate(i*60,12,12);
                        path(c,12,12,12,3); path(c,9,5,12,8,15,5); c.restore();
                    }
                    break;
                case "ice-cube":
                    path(c,12,2,21,7,21,17,12,22,3,17,3,7,12,2);
                    path(c,3,7,12,12,21,7); path(c,12,12,12,22);
                    path(c,6,11,6,15); path(c,15,17,18,15); break;
                case "power":
                    c.drawArc(4,4,20,21,310,280,false,p);
                    c.drawLine(12,2,12,12,p); break;
                case "thermometer":
                    path(c,9,14,9,5); c.drawArc(9,2,15,8,180,180,false,p);
                    path(c,15,5,15,14); c.drawArc(6,11,18,23,300,300,false,p);
                    c.drawLine(12,8,12,18,p); p.setStyle(Paint.Style.FILL); c.drawCircle(12,18,2,p); break;
                case "pulse": path(c,2,12,7,12,10,5,14,20,17,12,22,12); break;
                case "clock": c.drawCircle(12,12,9,p); path(c,12,6,12,12,16,14); break;
                case "eye": case "eye-off":
                    Path eye = new Path(); eye.moveTo(2,12); eye.cubicTo(7,3,17,3,22,12);
                    eye.cubicTo(17,21,7,21,2,12); c.drawPath(eye,p); c.drawCircle(12,12,3,p);
                    if ("eye-off".equals(name)) c.drawLine(3,3,21,21,p);
                    break;
                default:
                    c.drawRoundRect(6,6,18,18,3,3,p); c.drawRoundRect(9,9,15,15,1,1,p);
                    for(int i=8;i<=16;i+=4){c.drawLine(i,2,i,5,p);c.drawLine(i,19,i,22,p);c.drawLine(2,i,5,i,p);c.drawLine(19,i,22,i,p);}
            }
            c.restore();
        }
        private void path(Canvas c, float... coords) {
            Path path = new Path(); path.moveTo(coords[0],coords[1]);
            for(int i=2;i<coords.length;i+=2) path.lineTo(coords[i],coords[i+1]); c.drawPath(path,p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
