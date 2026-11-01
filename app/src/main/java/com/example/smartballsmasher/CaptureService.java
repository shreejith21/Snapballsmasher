package com.example.smartballsmasher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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
import android.util.DisplayMetrics;
import android.view.WindowManager;

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

    // Reused buffers (avoid per-frame allocation)
    private Bitmap frame;
    private int[] rowPixels;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(
            "capture", "Game screen analysis", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "capture")
            .setContentTitle("Smart Ball Smasher")
            .setContentText("Analysing game screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build();

        // Android 14+ requires the mediaProjection type to be declared before
        // getMediaProjection() is called.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(9, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(9, notification);
        }

        thread = new HandlerThread("ball-reader");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @SuppressWarnings("deprecation")
    private void readScreenSize() {
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
            width = b.width();
            height = b.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            width = real.widthPixels;
            height = real.heightPixels;
        }
        density = getResources().getDisplayMetrics().densityDpi;
    }

    @SuppressWarnings("deprecation")
    private Intent readProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra("data", Intent.class);
        }
        return intent.getParcelableExtra("data");
    }

    private void releaseCapture() {
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (frame != null) { frame.recycle(); frame = null; }
        rowPixels = null;
        lastBallY = -1;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = readProjectionData(intent);

        if (data == null || resultCode != Activity.RESULT_OK) {
            stopSelf();
            return START_NOT_STICKY;
        }

        releaseCapture();

        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(resultCode, data);

        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Required on Android 14+ before createVirtualDisplay().
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, handler);

        readScreenSize();

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

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
                int bmpWidth = rowStride / pixelStride;

                if (frame == null || frame.getWidth() != bmpWidth
                        || frame.getHeight() != height) {
                    if (frame != null) frame.recycle();
                    frame = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888);
                    rowPixels = new int[bmpWidth];
                }

                buffer.rewind();
                frame.copyPixelsFromBuffer(buffer);

                int[] ball = detectBall(frame);

                if (ball != null) {

                    float y = ball[1];
                    long now = System.currentTimeMillis();

                    boolean falling = lastBallY >= 0 && y > lastBallY + 3;

                    if (falling && y > height * 0.68f && now - lastTap > 700) {

                        TapAccessibilityService tapper = TapAccessibilityService.get();

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

        int bw = bitmap.getWidth();
        int xStart = (int) (width * 0.04);
        int xEnd = Math.min((int) (width * 0.96), bw);
        int yStart = (int) (height * 0.12);
        int yEnd = (int) (height * 0.86);

        for (int y = yStart; y < yEnd; y += 3) {

            // One bulk read per sampled row instead of per-pixel getPixel()
            bitmap.getPixels(rowPixels, 0, bw, 0, y, bw, 1);

            for (int x = xStart; x < xEnd; x += 3) {

                int color = rowPixels[x];

                int red = (color >> 16) & 0xFF;
                int green = (color >> 8) & 0xFF;
                int blue = color & 0xFF;

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

        if (count < 10 || count > width * height / 2500) {
            return null;
        }

        return new int[] { (int) (sumX / count), (int) (sumY / count) };
    }

    @Override
    public void onDestroy() {
        releaseCapture();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
