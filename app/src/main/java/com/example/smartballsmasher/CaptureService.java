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
import java.util.Arrays;

public class CaptureService extends Service {

    // Tunable settings (changed from MainActivity)
    public static volatile float triggerY = 0.55f;   // fraction of screen height
    public static volatile int burst = 3;            // taps per burst
    public static volatile int gapMs = 80;           // gap between taps in a burst
    public static volatile int cooldownMs = 700;     // min time between bursts
    public static volatile int bursts = 0;

    private static final int STEP = 3;
    private static final int MAX_PTS = 6000;
    private static final int MIN_PTS = 12;
    private static final int MAX_MATCH = 1500;
    private static final float FALL_SPEED = 250f;    // px/s downward

    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private HandlerThread thread;
    private Handler handler;

    private int width, height, density;

    private Bitmap frame;
    private int[] rowPixels;
    private int[] prev;
    private final int[] ptsX = new int[MAX_PTS];
    private final int[] ptsY = new int[MAX_PTS];

    // Ball tracking state
    private boolean hasBall = false;
    private float lastX, lastY, vyS;
    private long lastBallT = 0;
    private long lastTap = 0;

    // Diagnostics
    private long lastReport = 0;
    private int frames = 0;
    private int lastMatches = 0;

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, "capture")
            .setContentTitle("Smart Ball Smasher")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(
            "capture", "Game screen analysis", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = buildNotification("Starting...");

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
        prev = null;
        hasBall = false;
        vyS = 0;
    }

    private void report() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 1000) return;
        lastReport = now;

        String ball = hasBall
            ? String.format("%.2f, %.2f", lastX / width, lastY / height)
            : "none";

        String text = "frames=" + frames
            + "  moving-orange=" + lastMatches
            + "\nball (x, y) = " + ball
            + "  speed=" + (int) vyS + " px/s"
            + "\ntrigger=" + String.format("%.2f", triggerY)
            + "  burst=" + burst
            + "  bursts sent=" + bursts
            + "\ntaps ok=" + TapAccessibilityService.tapsOk
            + "  fail=" + TapAccessibilityService.tapsFailed
            + "  service=" + (TapAccessibilityService.get() != null ? "on" : "OFF");

        getSystemService(NotificationManager.class).notify(9, buildNotification(text));
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

                frames++;

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
                    prev = null;
                }

                buffer.rewind();
                frame.copyPixelsFromBuffer(buffer);

                processFrame(frame);
                report();

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) image.close();
            }

        }, handler);

        return START_NOT_STICKY;
    }

    // Finds pixels that are orange AND changed since the previous frame
    // (static orange scenery is ignored), tracks the ball, and fires a
    // tap burst when the ball is falling below the trigger line.
    private void processFrame(Bitmap bmp) {

        long now = System.currentTimeMillis();
        int bw = bmp.getWidth();

        int xStart = (int) (width * 0.03f);
        int xEnd = Math.min((int) (width * 0.97f), bw);
        int yStart = (int) (height * 0.10f);
        int yEnd = (int) (height * 0.89f);

        int gw = (xEnd - xStart + STEP - 1) / STEP;
        int gh = (yEnd - yStart + STEP - 1) / STEP;

        if (prev == null || prev.length != gw * gh) {
            prev = new int[gw * gh];
            Arrays.fill(prev, -1);
        }

        int nPts = 0;
        int matches = 0;
        int gy = 0;

        for (int y = yStart; y < yEnd; y += STEP, gy++) {

            bmp.getPixels(rowPixels, 0, bw, 0, y, bw, 1);

            int gx = 0;
            for (int x = xStart; x < xEnd; x += STEP, gx++) {

                int c = rowPixels[x];
                int idx = gy * gw + gx;
                int p = prev[idx];
                prev[idx] = c & 0xFFFFFF;

                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                if (p < 0) continue;
                if (!(r > 150 && g > 40 && b < 90 && r > g * 1.25f)) continue;

                int pr = (p >> 16) & 0xFF;
                int pg = (p >> 8) & 0xFF;
                int pb = p & 0xFF;

                if (Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb) < 60) continue;

                matches++;
                if (nPts < MAX_PTS) {
                    ptsX[nPts] = x;
                    ptsY[nPts] = y;
                    nPts++;
                }
            }
        }

        lastMatches = matches;

        // Forget the ball if it has not been seen for a while
        if (hasBall && now - lastBallT > 500) {
            hasBall = false;
            vyS = 0;
        }

        float cx = -1, cy = -1;

        if (hasBall) {
            // Follow the ball: only look near where it was
            float gateX = width * 0.12f;
            float gateY = width * 0.20f;
            long sx = 0, sy = 0;
            int n = 0;
            for (int i = 0; i < nPts; i++) {
                if (Math.abs(ptsX[i] - lastX) < gateX
                        && Math.abs(ptsY[i] - lastY) < gateY) {
                    sx += ptsX[i];
                    sy += ptsY[i];
                    n++;
                }
            }
            if (n >= MIN_PTS) {
                cx = (float) sx / n;
                cy = (float) sy / n;
            }
        } else if (matches >= MIN_PTS && matches <= MAX_MATCH && nPts > 0) {
            // Find the ball: centroid of all moving orange
            long sx = 0, sy = 0;
            for (int i = 0; i < nPts; i++) {
                sx += ptsX[i];
                sy += ptsY[i];
            }
            cx = (float) sx / nPts;
            cy = (float) sy / nPts;
        }

        if (cx < 0) return;

        if (hasBall) {
            float dt = Math.max(5, now - lastBallT);
            float vy = (cy - lastY) / dt * 1000f;
            vyS = 0.5f * vyS + 0.5f * vy;
        }

        lastX = cx;
        lastY = cy;
        lastBallT = now;
        hasBall = true;

        if (vyS > FALL_SPEED
                && cy > height * triggerY
                && now - lastTap > cooldownMs) {

            TapAccessibilityService tapper = TapAccessibilityService.get();
            if (tapper != null) {
                tapper.tapBurst(burst, gapMs);
                bursts++;
                lastTap = now;
            }
        }
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
