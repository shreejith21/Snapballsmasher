package com.example.smartballsmasher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 71;
    private TextView status;
    private String message = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText(message
                + "\nAccessibility: " + (TapAccessibilityService.get() != null ? "ON" : "OFF")
                + "\nTrigger line: " + String.format("%.2f", CaptureService.triggerY)
                + "  (0 = top, 1 = bottom)"
                + "\nTaps per burst: " + CaptureService.burst
                + "\nBursts sent: " + CaptureService.bursts
                + "\nTaps ok: " + TapAccessibilityService.tapsOk
                + "  failed: " + TapAccessibilityService.tapsFailed);
            ui.postDelayed(this, 500);
        }
    };

    private Button addButton(LinearLayout layout, String text, Runnable action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setOnClickListener(v -> action.run());
        layout.addView(btn);
        return btn;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("🏀 Smart Ball Smasher");
        title.setTextSize(26);
        layout.addView(title);

        addButton(layout, "Enable tap accessibility", () ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        addButton(layout, "Start screen detection", () -> {
            if (TapAccessibilityService.get() == null) {
                message = "Enable the tap accessibility service first";
                return;
            }
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });

        addButton(layout, "Stop", () -> {
            stopService(new Intent(this, CaptureService.class));
            message = "Stopped";
        });

        addButton(layout, "Trigger line UP (taps earlier)", () ->
            CaptureService.triggerY = Math.max(0.30f, CaptureService.triggerY - 0.03f));

        addButton(layout, "Trigger line DOWN (taps later)", () ->
            CaptureService.triggerY = Math.min(0.85f, CaptureService.triggerY + 0.03f));

        addButton(layout, "More taps per burst (+1)", () ->
            CaptureService.burst = Math.min(8, CaptureService.burst + 1));

        addButton(layout, "Fewer taps per burst (-1)", () ->
            CaptureService.burst = Math.max(1, CaptureService.burst - 1));

        addButton(layout, "Test burst in 5 s", () -> {
            message = "Switch to the game now";
            ui.postDelayed(() -> {
                TapAccessibilityService s = TapAccessibilityService.get();
                if (s != null) s.tapBurst(CaptureService.burst, CaptureService.gapMs);
            }, 5000);
        });

        status = new TextView(this);
        status.setTextSize(16);
        layout.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, CaptureService.class);
                service.putExtra("resultCode", resultCode);
                service.putExtra("data", data);

                if (Build.VERSION.SDK_INT >= 26)
                    startForegroundService(service);
                else
                    startService(service);

                message = "Detection started. Open the game; pull down the notification shade to see live stats.";
            } else {
                message = "Screen capture permission denied";
            }
        }
    }
}
