package com.subhub.app.settings;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;
import com.subhub.app.appmode.AppModeManager;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persist launchable-app snapshots off the UI/capture thread while All Apps is selected. */
public final class AllAppsScopeObserver {
    private static AllAppsScopeObserver instance;
    private final Context app;
    private final AtomicBoolean pending = new AtomicBoolean(), running = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "subhub-all-apps");
        thread.setDaemon(true);
        return thread;
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener selectionChanged;

    private AllAppsScopeObserver(Application app) {
        this.app = app;
        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_PACKAGE_ADDED);
        changes.addAction(Intent.ACTION_PACKAGE_REMOVED);
        changes.addAction(Intent.ACTION_PACKAGE_CHANGED);
        changes.addDataScheme("package");
        ContextCompat.registerReceiver(app, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { request(); }
        }, changes, ContextCompat.RECEIVER_EXPORTED);
        selectionChanged = (preferences, key) -> {
            if (AppModeManager.KEY_ALL_APPS.equals(key)) request();
        };
        app.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                .registerOnSharedPreferenceChangeListener(selectionChanged);
        request();
    }

    public static synchronized void install(Application app) {
        if (instance == null) instance = new AllAppsScopeObserver(app);
    }

    private void request() {
        if (!new AppModeManager(app).isAllApps()) return;
        pending.set(true);
        if (!running.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                do {
                    pending.set(false);
                    AppModeManager scope = new AppModeManager(app);
                    long revision = scope.scopeRevision();
                    if (scope.isAllApps()) {
                        try { scope.refreshAllApps(revision, InstalledAppCatalog.packageNames(app)); }
                        catch (RuntimeException unavailable) { /* Keep the last durable snapshot. */ }
                    }
                } while (pending.get());
            } finally {
                running.set(false);
                if (pending.get()) request();
            }
        });
    }
}
