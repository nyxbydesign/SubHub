package com.subhub.app.stats;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Event-driven snapshots only. An unchanged session needs no timer, wake lock or peer sync. */
public final class DailyDurationRecorder {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "subhub-daily-time"));
    private static volatile Context app;
    private DailyDurationRecorder() { }
    public static synchronized void install(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        changed();
        worker.execute(() -> {
            try { DailyStatsStore.get(app).syncWallet(); }
            catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Wallet migration pending"); }
        });
    }
    public static void changed() { changed(System.currentTimeMillis(), 0); }

    /** Preserve the caller's observation time even if the database writer is delayed. */
    public static void changed(long wallMillis, long elapsedRealtimeMillis) {
        Context current = app;
        if (current != null) DailyStatsStore.get(current).queueStats(
                current.getSharedPreferences(StatsRepository.PREFS_NAME, 0), wallMillis);
    }
}
