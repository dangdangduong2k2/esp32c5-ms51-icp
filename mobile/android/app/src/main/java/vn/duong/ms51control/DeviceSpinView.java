package vn.duong.ms51control;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import java.io.InputStream;

/** Bundled GIF, decoded off the UI thread and released when the popup closes. */
@SuppressWarnings("deprecation")
final class DeviceSpinView extends View {
    private final String assetName;
    private Movie movie;
    private long started;
    private boolean attached, loading;
    private int loadEpoch;
    DeviceSpinView(Context context) {this(context,"device-spin.gif");}
    DeviceSpinView(Context context,String assetName) {
        super(context);this.assetName=assetName;setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setBackgroundColor(android.graphics.Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();attached=true;
        if(movie!=null || loading)return;
        loading=true;final int epoch=++loadEpoch;
        final android.content.res.AssetManager assets=getContext().getAssets();
        new Thread(()->{
            Movie decoded=null;
            try(InputStream stream=assets.open(assetName)){decoded=Movie.decodeStream(stream);}
            catch(Exception ignored){/* Name and connect action remain available. */}
            final Movie result=decoded;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                if(epoch!=loadEpoch || !attached)return;
                loading=false;movie=result;started=SystemClock.uptimeMillis();invalidate();
            });
        },"product-gif").start();
    }
    @Override protected void onDetachedFromWindow() {
        attached=false;loading=false;++loadEpoch;movie=null;super.onDetachedFromWindow();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(movie==null || movie.width()==0 || movie.height()==0)return;
        boolean motion=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;
        int duration=Math.max(1,movie.duration());
        movie.setTime(motion?(int)((SystemClock.uptimeMillis()-started)%duration):0);
        float scale=Math.min(getWidth()/(float)movie.width(),getHeight()/(float)movie.height());
        canvas.save();canvas.translate((getWidth()-movie.width()*scale)/2,(getHeight()-movie.height()*scale)/2);
        canvas.scale(scale,scale);movie.draw(canvas,0,0);canvas.restore();
        if(attached && isShown() && motion)postInvalidateOnAnimation();
    }
}
