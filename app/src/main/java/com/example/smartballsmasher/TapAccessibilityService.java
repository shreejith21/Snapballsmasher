package com.example.smartballsmasher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class TapAccessibilityService extends AccessibilityService {

    private static TapAccessibilityService instance;

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

    public void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 60);

        GestureDescription.Builder builder =
            new GestureDescription.Builder();

        builder.addStroke(stroke);
        dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
