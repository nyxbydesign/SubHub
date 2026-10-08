package com.subhub.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.subhub.app.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, explicit emulator-only private diagnostic; never blocks capture on PNG encoding. */
final class PreparedFrameRecorder implements AutoCloseable {
    private final File directory;
    private final int limit;
    private final ThreadPoolExecutor writer;
    private int offered;

    static PreparedFrameRecorder startIfArmed(Context context) {
        if (!BuildConfig.DEBUG || !(Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("goldfish"))) return null;
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                "row_motion_experiment", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("save_frames", false)) return null;
        preferences.edit().remove("save_frames").apply();
        long session = SystemClock.uptimeMillis();
        Log.i("ScreenshotA11y", "ROW_FRAME_SESSION id=" + session + " limit=64");
        return new PreparedFrameRecorder(new File(context.getCacheDir(),
                "row-motion-frames-" + session), 64);
    }

    PreparedFrameRecorder(File directory, int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Invalid capture limit");
        this.directory = directory;
        this.limit = limit;
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), runnable -> {
                    Thread thread = new Thread(() -> {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        runnable.run();
                    }, "SubHub-frame-recorder");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Transfers the fresh pixel array; the caller must not mutate it after offering. */
    synchronized boolean offer(int[] pixels, int width, int height, long timestamp) {
        if (offered >= limit || writer.isShutdown()) return false;
        if (pixels == null || width < 1 || height < 1 || width > 512 || height > 512
                || (long) width * height != pixels.length || timestamp < 0L) return false;
        offered++;
        boolean accepted;
        try {
            writer.execute(() -> save(pixels, width, height, timestamp));
            accepted = true;
        } catch (RejectedExecutionException ignored) {
            trace(timestamp, width, height, "dropped");
            accepted = false;
        }
        if (offered >= limit) writer.shutdown();
        return accepted;
    }

    private void save(int[] pixels, int width, int height, long timestamp) {
        Bitmap bitmap = null;
        File output = new File(directory, "frame-" + timestamp + ".png");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Directory unavailable");
            if (!output.createNewFile()) throw new IOException("Duplicate frame timestamp");
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Encoding failed");
                }
            }
            trace(timestamp, width, height, "saved");
        } catch (IOException | RuntimeException error) {
            // Never print image content, settings, or exception payloads into diagnostics.
            trace(timestamp, width, height, "failed");
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static void trace(long timestamp, int width, int height, String status) {
        Log.i("ScreenshotA11y", "ROW_FRAME timestampMs=" + timestamp + " status=" + status
                + " width=" + width + " height=" + height);
    }

    @Override public synchronized void close() { writer.shutdown(); }

    boolean awaitFinished(long timeoutMillis) throws InterruptedException {
        return writer.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
