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
    /**
     * Managed DEVICE snapshot only, called inside its exact fresh NORMAL continuation. Unlike the
     * ambient compatibility method, this cannot select a successor's global preferences after a
     * late callback. The existing FIFO writer receives its immutable snapshot before this returns.
     * This is not a new ledger, an SQL completion receipt, or independent effect authority.
     */
    public static void changedDevice(com.subhub.app.runtime.DeviceRuntimeEffects.DeviceComponent component,
            Context physical, StatsRepository.SessionStamp expectedStamp,
            String expectedStatistics, String expectedOwner, long wallMillis, long elapsedRealtimeMillis) {
        if (component == null || !component.owner().session.equals(expectedOwner)
                || !(physical instanceof android.app.Application) || physical.getApplicationContext() != physical
                || expectedStamp == null || !expectedStamp.isDeviceSession()
                || !com.subhub.app.appmode.SessionExpiryAlarms.validIdentity(expectedStatistics)
                || !com.subhub.app.appmode.SessionExpiryAlarms.validIdentity(expectedOwner)
                || wallMillis <= 0 || elapsedRealtimeMillis < 0)
            throw new SecurityException("Invalid managed DEVICE duration source");
        boolean admitted = component.normal(() -> {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            android.content.SharedPreferences main = physical.getSharedPreferences(
                    com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0);
            android.content.SharedPreferences stats = physical.getSharedPreferences(StatsRepository.PREFS_NAME, 0);
            if (!expectedStamp.isCurrent()
                    || !expectedOwner.equals(main.getString(com.subhub.app.appmode.AppModeManager.KEY_MANAGED_DEVICE_SESSION, ""))
                    || !expectedStatistics.equals(stats.getString("active_session_identity", "")))
                throw new SecurityException("Managed duration source is no longer current");
            // queueStats calls prefs.getAll synchronously here, before dispatching the existing
            // immutable historical snapshot. No queued callback dereferences current preferences.
            DailyStatsStore.get(physical).queueStats(stats, wallMillis);
        }
        });
        if (!admitted) throw new SecurityException("Managed duration snapshot admission unavailable");
    }

    public static void changed() { changed(System.currentTimeMillis(), 0); }

    /** Preserve the caller's observation time even if the database writer is delayed. */
    public static void changed(long wallMillis, long elapsedRealtimeMillis) {
        Context current = app;
        if (current != null) DailyStatsStore.get(current).queueStats(
                current.getSharedPreferences(StatsRepository.PREFS_NAME, 0), wallMillis);
    }
}
