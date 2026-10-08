package com.subhub.app.capture.export;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.subhub.app.R;
import com.subhub.app.capture.CensorRenderer;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.settings.SettingsRepository;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** User-started, cancellable media job, independent of any Activity or capture session. */
public final class ExportService extends Service {
    public static final String ACTION_START = "com.subhub.app.export.START";
    public static final String ACTION_CANCEL = "com.subhub.app.export.CANCEL";
    public static final String EXTRA_JOB = "job";
    private static final String CHANNEL = "subhub_exports";
    private static final int NOTIFICATION = 8406;
    private static volatile String runningJob;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean timedOut;
    public static boolean isRunning() { return runningJob != null; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.export_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false); getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) { cancelled.set(true); if (runningJob == null) stopSelf(startId); return START_NOT_STICKY; }
        String job = intent.getStringExtra(EXTRA_JOB);
        if (!ACTION_START.equals(intent.getAction()) || job == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (runningJob != null) {
            if (!runningJob.equals(job)) try (ExportJobStore store = new ExportJobStore(this)) {
                failPending(store, job, "Another export is running. Retry after it finishes.");
            }
            return START_NOT_STICKY;
        }
        try (ExportJobStore store = new ExportJobStore(this)) { store.options(job); }
        catch (RuntimeException invalid) { stopSelf(startId); return START_NOT_STICKY; }
        runningJob = job; cancelled.set(false); timedOut = false;
        try {
            Notification notification = notification(0, 0);
            if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification);
        } catch (RuntimeException failure) {
            try (ExportJobStore store = new ExportJobStore(this)) { failPending(store, job, "Android could not start background export. Retry from the app."); }
            runningJob = null; stopSelf(startId); return START_NOT_STICKY;
        }
        worker.execute(() -> run(job));
        return START_NOT_STICKY;
    }
    private void run(String job) {
        try (ExportJobStore store = new ExportJobStore(this)) {
            ExportOptions options = store.options(job);
            SettingsRepository settings = SettingsRepository.forPreferences(ExportSettings.frozen(store.snapshot(job)));
            com.subhub.app.settings.CensorAppearance appearance = settings.loadAppearance();
            try (DetectionEngine engine = new DetectionEngine(this, settings.loadDetectorConfig());
                 CensorRenderer renderer = new CensorRenderer(this, ExportMedia.assets(store.directory(job),
                         settings.loadDetectionPreset().getCustomImageDimension(), settings.loadDetectionPreset().getCustomImageCount()))) {
                engine.initialize();
                List<ExportJobStore.Item> items = store.items(job); int index = 0;
                for (ExportJobStore.Item item : items) {
                    index++;
                    if (cancelled.get()) break;
                    if (item.state != ExportJobStore.State.PENDING) continue;
                    store.update(item.id, ExportJobStore.State.RUNNING, null, "", 0);
                    final int number = index;
                    File temporary = new File(store.directory(job), item.id + ".partial");
                    try {
                        Uri source = Uri.parse(item.source); boolean video = ExportMedia.isVideo(this, source);
                        if (video) {
                            final AtomicReference<List<Detection>> previous = new AtomicReference<>(Collections.emptyList());
                            final long[] lastUpdate = {0};
                            VideoExporter.export(this, source, temporary, options, (frame, frameIndex, timeUs) -> {
                                if (frameIndex % options.detectEvery == 0) previous.set(engine.detect(frame));
                                Bitmap output = frame.copy(Bitmap.Config.ARGB_8888, true);
                                if (output == null) throw new IOException("Could not allocate export frame");
                                renderer.draw(output, frame, previous.get(), appearance);
                                return output;
                            }, cancelled::get, (timeUs, durationUs) -> {
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastUpdate[0] < 500) return;
                                lastUpdate[0] = now;
                                store.progress(item.id, durationUs > 0 ? (int) Math.min(99, timeUs * 100 / durationUs) : 0);
                                updateNotification(number, items.size());
                            });
                        } else {
                            Bitmap sourceImage = ExportMedia.image(this, source, options.quality.longestEdge);
                            Bitmap output = null;
                            try {
                                output = sourceImage.copy(Bitmap.Config.ARGB_8888, true);
                                if (output == null) throw new IOException("Could not allocate export image");
                                renderer.draw(output, sourceImage, engine.detect(sourceImage), appearance);
                                try (OutputStream stream = new FileOutputStream(temporary)) {
                                    if (!output.compress(Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, stream))
                                        throw new IOException("Could not encode photo");
                                }
                            } finally { sourceImage.recycle(); if (output != null) output.recycle(); }
                        }
                        if (cancelled.get()) throw new CancellationException();
                        ExportMedia.publish(this, store, item, temporary, video);
                        updateNotification(number, items.size());
                    } catch (CancellationException cancelledError) {
                        store.update(item.id, timedOut ? ExportJobStore.State.INTERRUPTED : ExportJobStore.State.CANCELLED,
                                null, timedOut ? "Android time limit reached. Retry to continue." : "Cancelled", 0);
                        break;
                    } catch (Exception failure) {
                        store.update(item.id, ExportJobStore.State.FAILED, null, message(failure), 0);
                    } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
                }
                if (cancelled.get()) store.cancelPending(job);
            } catch (Exception setupFailure) { failPending(store, job, message(setupFailure)); }
        } catch (Exception invalidJob) {
            try (ExportJobStore store = new ExportJobStore(this)) { failPending(store, job, message(invalidJob)); }
        } finally {
            runningJob = null;
            main.post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
        }
    }
    private static String message(Exception error) {
        if (error instanceof SecurityException) return "Access to the selected media was lost. Select it again.";
        if (error instanceof IOException || error instanceof IllegalArgumentException) {
            String value = error.getMessage(); if (value != null && value.length() < 220) return value;
        }
        return "This media could not be exported on this device. The original was kept.";
    }
    private static void failPending(ExportJobStore store, String job, String message) {
        for (ExportJobStore.Item item : store.items(job)) if (item.state == ExportJobStore.State.PENDING || item.state == ExportJobStore.State.RUNNING)
            store.update(item.id, ExportJobStore.State.FAILED, null, message, 0);
    }
    private Notification notification(int current, int total) {
        PendingIntent open = PendingIntent.getActivity(this, 8406, new Intent(this, ExportActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 8407, new Intent(this, ExportService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.export_notification_title))
                .setContentText(getString(R.string.export_notification_progress, current, total))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(total, current, total == 0)
                .addAction(0, getString(android.R.string.cancel), cancel).build();
    }
    private void updateNotification(int current, int total) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(current, total));
    }
    @Override public void onTimeout(int startId, int type) {
        timedOut = true; cancelled.set(true); worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { cancelled.set(true); worker.shutdownNow(); super.onDestroy(); }
}
