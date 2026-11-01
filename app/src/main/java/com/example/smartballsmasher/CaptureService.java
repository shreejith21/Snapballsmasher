package com.example.smartballsmasher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.nio.ByteBuffer;

public class CaptureService extends Service {

    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private HandlerThread thread;
    private Handler handler;

    private int width, height, density;
    private long lastTap = 0;
    private float lastBallY = -1;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(
            "capture",
            "Game screen analysis",
            NotificationManager.IMPORTANCE_LOW
        );

        getSystemService(NotificationManager.class)
            .createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "capture")
            .setContentTitle("Smart Ball Smasher")
            .setContentText("Analysing game screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build();

        startForeground(9, notification);

        thread = new HandlerThread("ball-reader");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        int resultCode = intent.getIntExtra(
            "resultCode", Activity.RESULT_CANCELED);

        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(resultCode, data);

        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        android.util.DisplayMetrics metrics =
            getResources().getDisplayMetrics();

        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.densityDpi;

        reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2);

        display = projection.createVirtualDisplay(
            "BallSmasher",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(), null, handler
        );

        reader.setOnImageAvailableListener(r -> {

            Image image = null;

            try {
                image = r.acquireLatestImage();
                if (image == null) return;

                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();

                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();

                Bitmap bitmap = Bitmap.createBitmap(
                    rowStride / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                );

                bitmap.copyPixelsFromBuffer(buffer);

                int[] ball = detectBall(bitmap);

                bitmap.recycle();

                if (ball != null) {

                    float y = ball[1];
                    long now = System.currentTimeMillis();

                    boolean falling =
                        lastBallY >= 0 && y > lastBallY + 3;

                    if (falling &&
                        y > height * 0.68f &&
                        now - lastTap > 700) {

                        TapAccessibilityService tapper =
                            TapAccessibilityService.get();

                        if (tapper != null) {
                            tapper.tap(width * 0.50f, height * 0.945f);
                            lastTap = now;
                        }
                    }

                    lastBallY = y;

                } else {
                    lastBallY = -1;
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) image.close();
            }

        }, handler);

        return START_NOT_STICKY;
    }

    private int[] detectBall(Bitmap bitmap) {

        long sumX = 0;
        long sumY = 0;
        int count = 0;

        for (int y = (int)(height * 0.12);
             y < (int)(height * 0.86); y += 3) {

            for (int x = (int)(width * 0.04);
                 x < (int)(width * 0.96); x += 3) {

                int color = bitmap.getPixel(x, y);

                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);

                if (red > 175 &&
                    green > 65 && green < 190 &&
                    blue < 100 &&
                    red > green * 1.22f) {

                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }

        if (count < 10 ||
            count > width * height / 2500) {
            return null;
        }

        return new int[] {
            (int)(sumX / count),
            (int)(sumY / count)
        };
    }

    @Override
    public void onDestroy() {

        if (reader != null) reader.close();
        if (display != null) display.release();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
