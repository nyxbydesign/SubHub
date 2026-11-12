package com.subhub.app.fullaccess;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import com.subhub.app.people.DummySubPreview;
import com.subhub.app.security.ControllerPinManager;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local Demo observations. There is no paired grant, endpoint, or export to another device. */
final class DemoFullAccess {
    private static final String CHOICES = "demo_full_access_choices";
    private static final Object HISTORY_LOCK = new Object();
    private final Context app;
    private final String generation;
    private final long revision;

    DemoFullAccess(Context context, String generation, long revision) {
        app = context.getApplicationContext();
        this.generation = generation;
        this.revision = revision;
    }

    boolean available() { return DummySubPreview.current(app, generation, revision); }

    boolean current() { return available() && enabled(app, generation); }

    static boolean enabled(Context context, String generation) {
        return generation.equals(choices(context).getString("enabled_generation", ""));
    }

    boolean setEnabled(boolean enabled) {
        synchronized (HISTORY_LOCK) {
            if (!available()) return false;
            if (enabled && current()) return true;
            boolean saved = (enabled ? choices(app).edit().putString("enabled_generation", generation)
                    : choices(app).edit().clear()).commit();
            FullAccessNotificationHistory.clear(app, true);
            FullAccessSmsExport.clearDemo(app);
            return saved;
        }
    }

    JSONObject page(String scope, String cursor) throws IOException, JSONException {
        requireCurrent();
        int count = FullAccessDataSources.PAGE_SIZE;
        JSONObject result;
        try {
            result = switch (scope) {
                case "contacts" -> "ok".equals(FullAccessDataSources.permissionState(app, scope))
                        ? FullAccessContactsSource.page(app, FullAccessDataSources.cursor(cursor, 0, Long.MAX_VALUE), count)
                        : status("permission_required");
                case "apps" -> FullAccessAppsSource.page(app,
                        (int) FullAccessDataSources.cursor(cursor, 0, Integer.MAX_VALUE), count, System.currentTimeMillis());
                case "sms" -> sms(cursor, count);
                case "notifications" -> notifications(cursor, count);
                default -> throw new IllegalArgumentException("Unknown Demo data view");
            };
        } catch (SecurityException denied) {
            result = status("permission_required");
        }
        requireCurrent();
        if ("contacts".equals(scope) && !"ok".equals(FullAccessDataSources.permissionState(app, scope))
                || "notifications".equals(scope) && !FullAccessDataSources.hasNotificationAccess(app)
                || "apps".equals(scope) && result.optBoolean("usageAvailable") && !FullAccessDataSources.hasUsageAccess(app))
            return status("permission_required");
        return result;
    }

    private JSONObject sms(String cursor, int count) throws IOException, JSONException {
        if (FullAccessDataSources.hasSmsAccess(app)) {
            JSONObject result = FullAccessSmsSource.page(app, FullAccessDataSources.cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), count);
            return FullAccessDataSources.hasSmsAccess(app) ? result : status("permission_required");
        }
        synchronized (HISTORY_LOCK) {
            long epoch = choice(app, "sms", generation);
            if (epoch <= 0 || !FullAccessSmsExport.demoAvailable(app)) return status("permission_required");
            return FullAccessSmsExport.page(app, epoch,
                    (int) FullAccessDataSources.cursor(cursor, 0, Integer.MAX_VALUE), count, true);
        }
    }

    boolean importSms(Uri source) throws IOException, JSONException {
        requireCurrent();
        JSONArray lines = FullAccessSmsExport.readSelection(app, source);
        if (lines.length() == 0) return false;
        synchronized (HISTORY_LOCK) {
            requireCurrent();
            long since = System.currentTimeMillis();
            FullAccessSmsExport.store(app, since, lines, true);
            if (saveChoice(app, "sms", generation, since)) return true;
            FullAccessSmsExport.clearDemo(app);
            return false;
        }
    }

    private JSONObject notifications(String cursor, int count) throws JSONException {
        synchronized (HISTORY_LOCK) {
            long since = choice(app, "notifications", generation);
            if (since <= 0 || !FullAccessDataSources.hasNotificationAccess(app)) return status("permission_required");
            long now = System.currentTimeMillis();
            since = Math.max(since, now - FullAccessNotificationHistory.RETENTION_MILLIS);
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(app, true)) {
                return history.page(app, choice(app, "notifications", generation),
                        FullAccessDataSources.cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), since, now, count);
            }
        }
    }

    boolean enableNotifications() {
        synchronized (HISTORY_LOCK) {
            requireCurrent();
            if (!FullAccessDataSources.hasNotificationAccess(app)) return false;
            if (choice(app, "notifications", generation) > 0) return true;
            FullAccessNotificationHistory.clear(app, true);
            return saveChoice(app, "notifications", generation, System.currentTimeMillis());
        }
    }

    static boolean hasNotificationChoice(Context context) {
        return choices(context).getLong("notifications_since", 0) > 0;
    }

    static void recordNotification(Context context, String key, String pkg, String category, long timestamp,
            FullAccessNotificationListener.Preview preview) {
        synchronized (HISTORY_LOCK) {
            String generation = choices(context).getString("notifications_generation", "");
            long revision = ControllerPinManager.domWorkspaceRevision();
            if (!enabled(context, generation) || !DummySubPreview.current(context, generation, revision)
                    || !FullAccessDataSources.hasNotificationAccess(context)) return;
            long since = choice(context, "notifications", generation);
            long now = System.currentTimeMillis();
            if (since <= 0 || timestamp < since || timestamp > now
                    || timestamp < now - FullAccessNotificationHistory.RETENTION_MILLIS) return;
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context, true)) {
                history.record(since, key, pkg, category, timestamp, now, preview);
            } catch (RuntimeException unavailable) {
                // Optional collection must never log notification content.
            }
        }
    }

    boolean filesAvailable() { return current() && FullAccessFiles.available(app, generation); }

    FullAccessFiles.Session openFiles(BooleanSupplier tab) {
        requireCurrent();
        return FullAccessFiles.open(app, () -> current() && tab.getAsBoolean(), generation);
    }

    boolean retainFolder(Intent data) {
        requireCurrent();
        return FullAccessFiles.retainFolder(app, data, generation);
    }

    private void requireCurrent() {
        if (!current()) throw new SecurityException("Demo phone data unavailable");
    }

    private static SharedPreferences choices(Context app) {
        return app.getSharedPreferences(CHOICES, Context.MODE_PRIVATE);
    }

    private static long choice(Context app, String scope, String generation) {
        SharedPreferences saved = choices(app);
        return generation.equals(saved.getString(scope + "_generation", ""))
                ? saved.getLong(scope + "_since", 0) : 0;
    }

    private static boolean saveChoice(Context app, String scope, String generation, long since) {
        return choices(app).edit().putString(scope + "_generation", generation)
                .putLong(scope + "_since", since).commit();
    }

    private static JSONObject status(String status) throws JSONException {
        return FullAccessDataSources.response(status, new JSONArray());
    }
}
