package com.example.smartballsmasher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 71;
    private TextView status;

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

        Button access = new Button(this);
        access.setText("Enable tap accessibility");
        access.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(access);

        Button start = new Button(this);
        start.setText("Start screen detection");
        start.setOnClickListener(v -> {
            if (TapAccessibilityService.get() == null) {
                status.setText("Enable the tap accessibility service first");
                return;
            }
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop auto-player");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("Stopped");
        });
        layout.addView(stop);

        status = new TextView(this);
        status.setText("Waiting to start");
        layout.addView(status);

        setContentView(layout);
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

                status.setText("Detection started");
            } else {
                status.setText("Screen capture permission denied");
            }
        }
    }
}
