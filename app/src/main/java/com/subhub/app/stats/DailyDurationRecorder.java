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
    private static volatile DurationCheckpointDispatcher dispatcher;
    private DailyDurationRecorder() { }
    public static synchronized void install(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        DailyStatsStore store = DailyStatsStore.get(app);
        dispatcher = new DurationCheckpointDispatcher(worker, new DurationCheckpointDispatcher.Clock() {
            @Override public long wall() { return System.currentTimeMillis(); }
            @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
        }, DailyDurationRecorder::capture, snapshot -> {
            try {
                store.heartbeat(PROCESS, snapshot.session, snapshot.wall, snapshot.elapsed, snapshot.zone);
            } catch (RuntimeException unavailable) {
                android.util.Log.w("DailyStats", "Daily duration checkpoint unavailable");
            }
        });
        store.queueStats(app.getSharedPreferences(StatsRepository.PREFS_NAME, 0));
        worker.execute(() -> {
            try { store.syncWallet(); }
            catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Wallet migration pending"); }
        });
        worker.scheduleWithFixedDelay(DailyDurationRecorder::changed, 0, 5, TimeUnit.SECONDS);
    }
    public static void changed() {
        DurationCheckpointDispatcher current = dispatcher;
        if (current != null) current.checkpoint();
    }
    /** Call after changing runtime state, retaining the transition's original clocks. */
    public static void changed(long wallMillis, long elapsedRealtimeMillis) {
        DurationCheckpointDispatcher current = dispatcher;
        if (current != null) current.checkpoint(wallMillis, elapsedRealtimeMillis);
    }
    private static DurationCheckpointDispatcher.Snapshot capture(long wall, long elapsed) {
        long session = 0;
        boolean bound = false, interactive = false, locked = true;
        try {
            session = app.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getLong("active_session_start_ms", 0);
            bound = com.subhub.app.service.ScreenCaptureService.isRunning()
                    || com.subhub.app.service.ScreenshotAccessibilityService.isRunning();
            android.os.PowerManager power = app.getSystemService(android.os.PowerManager.class);
            android.app.KeyguardManager keyguard = app.getSystemService(android.app.KeyguardManager.class);
            interactive = power != null && power.isInteractive();
            locked = keyguard == null || keyguard.isKeyguardLocked();
        } catch (RuntimeException unavailable) {
            // An unavailable screen observation cannot extend the previous eligible interval.
            interactive = false;
            android.util.Log.w("DailyStats", "Daily duration observation unavailable");
        }
        return new DurationCheckpointDispatcher.Snapshot(session, bound, interactive, locked,
                wall, elapsed, ZoneId.systemDefault().getId());
    }
}
