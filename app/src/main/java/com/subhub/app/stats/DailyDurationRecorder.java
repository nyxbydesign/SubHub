package com.subhub.app.stats;

import android.content.Context;
import android.os.SystemClock;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.*;

/** Five-second durable checkpoints, with no reconstruction of unobserved process downtime. */
public final class DailyDurationRecorder {
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "subhub-daily-time"));
    private static Context app;
    private DailyDurationRecorder() { }
    public static synchronized void install(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        DailyStatsStore store = DailyStatsStore.get(app);
        try { store.syncStats(app.getSharedPreferences(StatsRepository.PREFS_NAME, 0)); store.syncWallet(); }
        catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Daily migration pending"); }
        worker.scheduleWithFixedDelay(DailyDurationRecorder::sample, 0, 5, TimeUnit.SECONDS);
    }
    public static void changed() { if (app != null) worker.execute(DailyDurationRecorder::sample); }
    private static void sample() {
        try {
            long session = app.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getLong("active_session_start_ms", 0);
            boolean live = com.subhub.app.service.ScreenCaptureService.isRunning() || com.subhub.app.service.ScreenshotAccessibilityService.isRunning();
            DailyStatsStore.get(app).heartbeat(PROCESS, live ? session : 0, System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault().getId());
        } catch (RuntimeException unavailable) {
            // Retry at the next checkpoint. Never synthesize dates from lifetime totals.
            android.util.Log.w("DailyStats", "Daily duration checkpoint unavailable");
        }
    }
}
