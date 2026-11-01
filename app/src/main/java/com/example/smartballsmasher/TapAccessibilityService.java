package com.example.smartballsmasher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class TapAccessibilityService extends AccessibilityService {

    private static TapAccessibilityService instance;
    public static volatile int tapsOk = 0;
    public static volatile int tapsFailed = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static TapAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    // Sends n quick taps on the ball button, gapMs apart.
    public void tapBurst(int n, int gapMs) {
        for (int i = 0; i < n; i++) {
            handler.postDelayed(this::tapButton, (long) i * gapMs);
        }
    }

    @SuppressWarnings("deprecation")
    private void tapButton() {
        int w, h;
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            w = m.widthPixels;
            h = m.heightPixels;
        }
        tap(w * 0.499f, h * 0.947f);
    }

    public void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 40);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        boolean sent = dispatchGesture(builder.build(),
            new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    tapsOk++;
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    tapsFailed++;
                }
            }, null);

        if (!sent) tapsFailed++;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
