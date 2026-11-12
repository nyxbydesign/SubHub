package com.subhub.app.fullaccess;

import android.content.Context;
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
    private static volatile long notificationRevision;
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
            notificationRevision++;
            FullAccessNotificationHistory.clear(app, true);
            FullAccessSmsExport.clearDemo(app);
            return saved;
        }
    }

    JSONObject page(String scope, String cursor) throws IOException, JSONException {
        return page(scope, "page", "", cursor);
    }

    JSONObject page(String scope, String operation, String node, String cursor) throws IOException, JSONException {
        requireCurrent();
        if (!FullAccessDataSources.supportsRead(scope, operation, node)) return status("unavailable");
        int count = FullAccessDataSources.PAGE_SIZE;
        boolean smsContacts = "sms".equals(scope) && !"page".equals(operation) && FullAccessDataSources.hasSmsAccess(app)
                && "ok".equals(FullAccessDataSources.permissionState(app, "contacts"));
        String detailsToken = "notifications".equals(scope)
                ? choices(app).getString(FullAccessNotificationListener.DETAILS_TOKEN, "") : "";
        JSONObject result;
        try {
            result = switch (scope) {
                case "contacts" -> "ok".equals(FullAccessDataSources.permissionState(app, scope))
                        ? FullAccessContactsSource.page(app, FullAccessDataSources.cursor(cursor, 0, Long.MAX_VALUE), count)
                        : status("permission_required");
                case "apps" -> FullAccessAppsSource.page(app,
                        (int) FullAccessDataSources.cursor(cursor, 0, Integer.MAX_VALUE), count, System.currentTimeMillis());
                case "sms" -> sms(operation, node, cursor, smsContacts);
                case "notifications" -> notifications(operation, node, cursor, detailsToken);
                default -> throw new IllegalArgumentException("Unknown Demo data view");
            };
        } catch (SecurityException denied) {
            result = status("permission_required");
        }
        requireCurrent();
        if ("notifications".equals(scope)
                && !detailsToken.equals(choices(app).getString(FullAccessNotificationListener.DETAILS_TOKEN, "")))
            return status("unavailable");
        if ("contacts".equals(scope) && !"ok".equals(FullAccessDataSources.permissionState(app, scope))
                || "notifications".equals(scope) && !FullAccessDataSources.hasNotificationAccess(app)
                || smsContacts && !"ok".equals(FullAccessDataSources.permissionState(app, "contacts"))
                || "apps".equals(scope) && result.optBoolean("usageAvailable") && !FullAccessDataSources.hasUsageAccess(app))
            return status("permission_required");
        return result;
    }

    private JSONObject sms(String operation, String node, String cursor, boolean contacts) throws IOException, JSONException {
        if (FullAccessDataSources.hasSmsAccess(app)) {
            JSONObject result = FullAccessDataSources.directSms(app, operation, node, cursor, contacts);
            return FullAccessDataSources.hasSmsAccess(app) ? result : status("permission_required");
        }
        synchronized (HISTORY_LOCK) {
            long epoch = choice(app, "sms", generation);
            if (epoch <= 0 || !FullAccessSmsExport.demoAvailable(app)) return status("permission_required");
            return FullAccessDataSources.exportedSms(app, epoch, operation, node, cursor, true);
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

    private JSONObject notifications(String operation, String node, String cursor, String detailsToken)
            throws JSONException {
        synchronized (HISTORY_LOCK) {
            long since = choice(app, "notifications", generation);
            if (since <= 0 || !FullAccessDataSources.hasNotificationAccess(app)) return status("permission_required");
            if (!detailsToken.equals(choices(app).getString(FullAccessNotificationListener.DETAILS_TOKEN, "")))
                return status("unavailable");
            long now = System.currentTimeMillis();
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(app, true, detailsToken)) {
                return FullAccessDataSources.notifications(history, app, since, operation, node, cursor,
                        Math.max(since, now - FullAccessNotificationHistory.RETENTION_MILLIS), now);
            }
        }
    }

    boolean enableNotifications() {
        synchronized (HISTORY_LOCK) {
            requireCurrent();
            if (!FullAccessDataSources.hasNotificationAccess(app)) return false;
            if (choice(app, "notifications", generation) > 0) return true;
            FullAccessNotificationHistory.clear(app, true);
            notificationRevision++;
            boolean saved = choices(app).edit().putString("notifications_generation", generation)
                    .putLong("notifications_since", System.currentTimeMillis())
                    .remove(FullAccessNotificationListener.DETAILS_TOKEN).remove(FullAccessNotificationListener.DETAILS_SINCE)
                    .commit();
            if (saved) FullAccessNotificationListener.reconcileIfConnected();
            return saved;
        }
    }

    static FullAccessNotificationListener.Choice notificationChoice(Context context) {
        SharedPreferences prefs = choices(context);
        long observedChange = notificationRevision;
        String generation = prefs.getString("notifications_generation", "");
        long since = choice(context, "notifications", generation);
        return since <= 0 || !enabled(context, generation) ? null : new FullAccessNotificationListener.Choice(
                observedChange, generation, ControllerPinManager.domWorkspaceRevision(), since,
                prefs.getString(FullAccessNotificationListener.DETAILS_TOKEN, ""),
                prefs.getLong(FullAccessNotificationListener.DETAILS_SINCE, 0));
    }

    boolean setNotificationDetails(FullAccessNotificationListener.Choice expected, boolean enabled) {
        synchronized (HISTORY_LOCK) {
            if (!current() || !expected.equals(notificationChoice(app))
                    || enabled && !FullAccessDataSources.hasNotificationAccess(app)) return false;
            return FullAccessNotificationListener.changeDetails(app, choices(app), true, enabled);
        }
    }

    static void recordNotification(Context context, FullAccessNotificationListener.Choice expected,
            String key, String pkg, String category, long timestamp, FullAccessNotificationListener.Preview preview,
            FullAccessNotificationListener.Preview details, String icon) {
        synchronized (HISTORY_LOCK) {
            FullAccessNotificationListener.Choice current = notificationChoice(context);
            if (current == null || !current.sameScope(expected)
                    || !DummySubPreview.current(context, current.generation(), current.revision())
                    || !FullAccessDataSources.hasNotificationAccess(context)) return;
            long now = System.currentTimeMillis();
            if (timestamp < current.since() || timestamp > now
                    || timestamp < now - FullAccessNotificationHistory.RETENTION_MILLIS) return;
            String token = current.acceptedDetailsToken(expected, timestamp);
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context, true, token)) {
                history.record(current.since(), key, pkg, category, timestamp, now, preview, details, icon);
            } catch (RuntimeException unavailable) {
                // Optional collection must never log notification content.
            }
        }
    }

    FullAccessFiles.Session openFiles(BooleanSupplier tab) {
        requireCurrent();
        return FullAccessFiles.open(app, () -> current() && tab.getAsBoolean(), generation);
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
