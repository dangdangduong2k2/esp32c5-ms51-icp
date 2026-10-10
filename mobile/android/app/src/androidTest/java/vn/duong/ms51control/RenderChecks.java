package vn.duong.ms51control;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;

/** Offline drawing regression: a status refresh must not fade the hero background. */
final class RenderChecks {
    static void run(Instrumentation test) {
        Bundle report = new Bundle();
        try {
            Design.Hero hero = new Design.Hero();
            for (int width : new int[]{360, 720}) {
                int height = width / 2;
                hero.setBounds(0, 0, width, height);
                Bitmap baseline = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                baseline.eraseColor(Design.BG);
                hero.draw(new Canvas(baseline));
                for (int frame = 1; frame <= 30; frame++) {
                    Bitmap actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    actual.eraseColor(Design.BG);
                    hero.draw(new Canvas(actual));
                    if (frame == 1) {
                        save(test, baseline, "hero-first-" + width + ".png");
                        save(test, actual, "hero-redraw-" + width + ".png");
                    }
                    if (!baseline.sameAs(actual)) {
                        throw new AssertionError("Hero changed on redraw " + frame + " at width " + width
                                + ": first=" + Integer.toHexString(baseline.getPixel(width / 2, height / 2))
                                + ", redraw=" + Integer.toHexString(actual.getPixel(width / 2, height / 2)));
                    }
                    actual.recycle();
                }
                Bitmap transparent = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                hero.draw(new Canvas(transparent));
                if (Color.alpha(transparent.getPixel(width / 2, height / 2)) != 255) {
                    throw new AssertionError("Hero center became translucent");
                }
                transparent.recycle(); baseline.recycle();
            }
            report.putString("stream", "PASS: hero remains identical across 30 redraws at each of two sizes; center stays opaque.");
            test.finish(-1, report);
        } catch (Throwable error) {
            report.putString("stream", "FAIL: " + error);
            test.finish(0, report);
        }
    }

    private static void save(Instrumentation test, Bitmap bitmap, String name) throws Exception {
        File folder = new File(test.getTargetContext().getExternalFilesDir(null), "render-checks");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create render output");
        try (FileOutputStream out = new FileOutputStream(new File(folder, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
