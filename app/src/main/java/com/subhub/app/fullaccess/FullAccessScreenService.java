package com.subhub.app.fullaccess;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Owns one approved Android projection and samples the newest image at the wire cadence. */
public final class FullAccessScreenService extends Service {
    private static final int NOTIFICATION_ID = 15032;
    private static final int MAX_EDGE = 960;
    private static final String STOP = "com.subhub.app.fullaccess.STOP_SCREEN_SHARE";
    private static final MediaProjectionLeaseRegistry.Owner LEASE = MediaProjectionLeaseRegistry.Owner.FULL_ACCESS;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FullAccessScreen.Session owner;
    private HandlerThread thread;
    private Handler worker;
    private boolean leaseHeld;
    private boolean closing;
    private boolean receiverRegistered;

    // Projection resources, frame visibility, and encodes are serialized on the worker.
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Bitmap bitmap;
    private ByteBuffer packedRows;
    private final ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream();
    private boolean contentVisible = true;
    private int densityDpi;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && owner != null) owner.close();
        }
    };
    private final MediaProjection.Callback projectionEvents = new MediaProjection.Callback() {
        @Override public void onStop() { owner.close(); }

        @Override public void onCapturedContentResize(int width, int height) {
            if (display != null && owner.captureAllowed()) {
                try { resize(width, height); }
                catch (RuntimeException unavailable) { owner.close(); }
            }
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            contentVisible = visible;
            if (!visible) owner.frames.clear();
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra(FullAccessScreen.EXTRA_SESSION);
        if (intent != null && STOP.equals(intent.getAction())) {
            if (owner != null && owner.id.equals(id)) owner.close();
            if (owner == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (owner != null) return START_NOT_STICKY;
        FullAccessScreen.Session session = FullAccessScreen.find(id);
        if (session == null || !session.revalidate() || !FullAccessScreen.displayAvailable(this)) {
            if (session != null) session.close();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Intent approval = session.consumeApproval();
        if (approval == null) {
            session.close();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!MediaProjectionLeaseRegistry.acquire(LEASE)) {
            session.end(FullAccessScreen.IN_USE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        leaseHeld = true;
        owner = session;
        try {
            if (FullAccessScreen.activeNotifications(this) == null)
                throw new IllegalStateException("Screen sharing indicator unavailable");
            Notification notification = sharingNotification(session);
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, notification);
            ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            thread = new HandlerThread("subhub-screen-frames");
            thread.start();
            worker = new Handler(thread.getLooper());
            if (!session.attachCapture(this::releaseCapture) || !worker.post(() -> begin(approval))) {
                session.close();
                releaseCapture();
            }
        } catch (RuntimeException unavailable) {
            session.close();
            releaseCapture();
        }
        return START_NOT_STICKY;
    }

    private Notification sharingNotification(FullAccessScreen.Session session) {
        Intent stop = new Intent(this, FullAccessScreenService.class).setAction(STOP)
                .setData(Uri.parse("subhub://screen-stop/" + session.id))
                .putExtra(FullAccessScreen.EXTRA_SESSION, session.id);
        PendingIntent stopShare = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        return new Notification.Builder(this, FullAccessScreen.ACTIVE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.full_access_screen_active))
                .setOngoing(true)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setDeleteIntent(stopShare)
                .addAction(new Notification.Action.Builder(null, getString(R.string.full_access_screen_stop),
                        stopShare).build())
                .build();
    }

    private void begin(Intent approval) {
        try {
            if (!owner.revalidate() || !FullAccessScreen.displayAvailable(this)) {
                owner.close();
                return;
            }
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("Screen projection unavailable");
            projection = manager.getMediaProjection(Activity.RESULT_OK, approval);
            if (projection == null) throw new IllegalStateException("Screen projection unavailable");
            projection.registerCallback(projectionEvents, worker);
            int[] size = displaySize();
            densityDpi = getResources().getDisplayMetrics().densityDpi;
            reader = createReader(size[0], size[1]);
            display = projection.createVirtualDisplay("SubHub screen share", reader.getWidth(), reader.getHeight(),
                    densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            if (display == null || !owner.revalidate() || !owner.startStreaming() || !owner.captureAllowed()) {
                owner.close();
                return;
            }
            worker.postDelayed(this::checkSharing, FullAccessScreenFrames.INTERVAL_MS);
        } catch (RuntimeException unavailable) {
            owner.close();
        }
    }

    @SuppressWarnings("deprecation")
    private int[] displaySize() {
        WindowManager windows = getSystemService(WindowManager.class);
        if (windows == null) throw new IllegalStateException("Display unavailable");
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private ImageReader createReader(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid screen size");
        double scale = Math.min(1d, (double) MAX_EDGE / Math.max(width, height));
        ImageReader images = ImageReader.newInstance(Math.max(1, (int) (width * scale)),
                Math.max(1, (int) (height * scale)), PixelFormat.RGBA_8888, 3);
        try {
            images.setOnImageAvailableListener(this::captureFrame, worker);
            return images;
        } catch (RuntimeException unavailable) {
            images.close();
            throw unavailable;
        }
    }

    private void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        double scale = Math.min(1d, (double) MAX_EDGE / Math.max(width, height));
        if (reader.getWidth() == Math.max(1, (int) (width * scale))
                && reader.getHeight() == Math.max(1, (int) (height * scale))) return;
        owner.frames.clear();
        ImageReader replacement = createReader(width, height);
        try {
            display.resize(replacement.getWidth(), replacement.getHeight(), densityDpi);
            display.setSurface(replacement.getSurface());
        } catch (RuntimeException unavailable) {
            replacement.close();
            throw unavailable;
        }
        ImageReader previous = reader;
        reader = replacement;
        previous.close();
    }

    private void captureFrame(ImageReader images) {
        if (images != reader) return;
        try (Image frame = images.acquireLatestImage()) {
            if (frame == null || !contentVisible) return;
            long now = SystemClock.elapsedRealtime();
            if (!owner.frames.beginSample(now) || !owner.captureAllowed()) return;
            byte[] jpeg = encode(frame);
            if (owner.captureAllowed()) owner.frames.publish(jpeg, now);
        } catch (RuntimeException unavailable) {
            owner.close();
        }
    }

    private void checkSharing() {
        if (!owner.captureAllowed()) return;
        try {
            if (!FullAccessScreen.sharingIndicatorEnabled(getSystemService(NotificationManager.class))) {
                owner.close();
                return;
            }
            worker.postDelayed(this::checkSharing, FullAccessScreenFrames.INTERVAL_MS);
        } catch (RuntimeException unavailable) {
            owner.close();
        }
    }

    private byte[] encode(Image image) {
        int width = image.getWidth(), height = image.getHeight(), rowBytes = width * 4;
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 1 || planes[0].getPixelStride() != 4) return null;
        Image.Plane plane = planes[0];
        ByteBuffer source = plane.getBuffer();
        int stride = plane.getRowStride();
        long required = (long) stride * (height - 1) + rowBytes;
        if (stride < rowBytes || source.remaining() < required) return null;
        int offset = source.position();
        ByteBuffer pixels = source;
        if (stride != rowBytes) {
            int needed = rowBytes * height;
            if (packedRows == null || packedRows.capacity() < needed)
                packedRows = ByteBuffer.allocateDirect(needed);
            pixels = packedRows;
            pixels.clear();
            for (int row = 0; row < height; row++) {
                int start = offset + row * stride;
                source.limit(start + rowBytes).position(start);
                pixels.put(source);
            }
            pixels.flip();
        }
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.copyPixelsFromBuffer(pixels);
        jpegBytes.reset();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, jpegBytes)) return null;
        if (jpegBytes.size() > FullAccessScreenFrames.MAX_BYTES) {
            jpegBytes.reset();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 48, jpegBytes)) return null;
        }
        return jpegBytes.size() <= FullAccessScreenFrames.MAX_BYTES ? jpegBytes.toByteArray() : null;
    }

    private void releaseCapture() {
        if (closing) return;
        closing = true;
        if (worker == null) {
            if (leaseHeld) MediaProjectionLeaseRegistry.release(LEASE);
            if (thread != null) thread.quitSafely();
            stopSelf();
            return;
        }
        worker.post(() -> {
            worker.removeCallbacksAndMessages(null);
            boolean released = true;
            if (display != null) {
                released = release(display::release);
                display = null;
            }
            if (reader != null) {
                released &= release(reader::close);
                reader = null;
            }
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
            packedRows = null;
            if (projection != null) {
                released &= release(() -> projection.unregisterCallback(projectionEvents));
                released &= release(projection::stop);
                projection = null;
            }
            // Keep competing projection owners out if Android could not confirm teardown.
            if (released && leaseHeld) MediaProjectionLeaseRegistry.release(LEASE);
            thread.quitSafely();
            main.post(this::stopSelf);
        });
    }

    private static boolean release(Runnable resource) {
        try {
            resource.run();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override public void onDestroy() {
        if (owner != null) owner.close();
        releaseCapture();
        if (receiverRegistered) unregisterReceiver(screenOff);
        super.onDestroy();
    }
}
