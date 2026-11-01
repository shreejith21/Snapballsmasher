package com.example.smartballsmasher;

import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 71;
    private TextView status;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 24, 24, 24);

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
            MediaProjectionManager mpm =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop auto-player");
        stop.setOnClickListener(v ->
            stopService(new Intent(this, CaptureService.class)));
        layout.addView(stop);

        status = new TextView(this);
        status.setText("Waiting to start");
        layout.addView(status);

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);

            if (Build.VERSION.SDK_INT >= 26)
                startForegroundService(service);
            else
                startService(service);

            status.setText("Detection started");
        }
    }
}
