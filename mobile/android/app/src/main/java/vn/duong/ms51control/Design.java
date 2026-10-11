package vn.duong.ms51control;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector artwork keeps every icon crisp without external UI dependencies. */
final class Design {
    static final int NAVY = Color.rgb(17, 38, 61);
    static final int INK = Color.rgb(23, 43, 64);
    static final int MUTED = Color.rgb(101, 118, 135);
    static final int TEAL = Color.rgb(0, 119, 108);
    static final int MINT = Color.rgb(120, 235, 206);
    static final int BG = Color.rgb(244, 247, 250);
    static final int LINE = Color.rgb(226, 233, 240);

    static final class Hero extends Drawable {
        private final Paint paint = new Paint(3);
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            // Decorative circles leave a low alpha on this reused Paint.
            // Restore opacity before every background redraw.
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, 0, b.width(), b.height(),
                    new int[]{NAVY, Color.rgb(20, 62, 76)}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(b), 26, 26, paint);
            paint.setShader(null);
            canvas.save();
            canvas.clipRect(b);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            for (int i = 0; i < 7; i++) {
                paint.setColor(Color.argb(14 + i * 2, 120, 235, 206));
                canvas.drawCircle(b.right * 1.07f, b.height() * .25f,
                        b.width() * (.26f + i * .095f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
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
                case "arrow": path(c,5,12,19,12); path(c,14,7,19,12,14,17); break;
                case "refresh":
                    c.drawArc(4,4,20,20,40,285,false,p); path(c,16,3,20,5,19,9); break;
                case "power":
                    c.drawArc(4,4,20,21,310,280,false,p);
                    c.drawLine(12,2,12,12,p); break;
                case "thermometer":
                    path(c,9,14,9,5); c.drawArc(9,2,15,8,180,180,false,p);
                    path(c,15,5,15,14); c.drawArc(6,11,18,23,300,300,false,p);
                    c.drawLine(12,8,12,18,p); p.setStyle(Paint.Style.FILL); c.drawCircle(12,18,2,p); break;
                case "shield":
                    path(c,12,3,20,6,19,15,16,19,12,22,8,19,5,15,4,6,12,3);
                    path(c,8,12,11,15,16,9); break;
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
