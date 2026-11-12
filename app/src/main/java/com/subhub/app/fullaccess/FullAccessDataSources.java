package com.subhub.app.fullaccess;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Sub-owned, bounded Full Access readers. Nothing here launches a permission request. */
public final class FullAccessDataSources {
    private static final String SETTINGS = "full_access_data_scopes";
    private static final int PAGE_SIZE = 12;
    private static final String SMS_SOURCE = "sms_source";
    private static final String DIRECT_SMS = "direct";
    private static final String EXPORTED_SMS = "export";
    private static final Object LOCAL_HISTORY_LOCK = new Object();
    private static long scopeChange;

    private FullAccessDataSources() { }

    public static String permissionState(Context context, String scope) {
        return switch (scope) {
            case "contacts" -> context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    ? "ok" : "permission_required";
            case "notifications" -> hasNotificationAccess(context) ? "ok" : "permission_required";
            case "sms" -> hasSmsAccess(context) || FullAccessSmsExport.available(context) ? "ok"
                    : canRequestSmsPermission(context) ? "permission_required" : "unavailable";
            case "apps" -> "ok";
            default -> "unavailable";
        };
    }

    public static boolean hasUsageAccess(Context context) {
        return FullAccessAppsSource.hasUsageAccess(context);
    }

    public static boolean hasSmsAccess(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Checks declaration only. Android's installer and runtime policy decide whether it is grantable. */
    public static boolean canRequestSmsPermission(Context context) {
        try {
            String[] declared = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (declared == null) return false;
            for (String permission : declared) {
                if (Manifest.permission.READ_SMS.equals(permission)) return true;
            }
        } catch (PackageManager.NameNotFoundException unavailable) {
            return false;
        }
        return false;
    }

    public static boolean isScopeEnabled(Context context, String scope) {
        long epoch = currentEpoch(context);
        return epoch >= 0 && recognized(scope) && chosenAt(context, scope, epoch) > 0;
    }

    /** Called only after the Sub takes the explicit setup action for the selected scope. */
    public static boolean enableScope(Context context, String scope) {
        if (!recognized(scope) || !"ok".equals(permissionState(context, scope))) return false;
        long observedChange = scopeChange();
        long epoch = currentEpoch(context);
        if (epoch < 0) return false;
        synchronized (LOCAL_HISTORY_LOCK) {
            if (scopeChange != observedChange) return false;
            if ("sms".equals(scope)) {
                if (!hasSmsAccess(context)) return false;
                if (chosenAt(context, scope, epoch) > 0 && DIRECT_SMS.equals(smsSource(context))) return true;
                boolean saved = choice(context, scope, epoch).putString(SMS_SOURCE, DIRECT_SMS).commit();
                if (saved) FullAccessSmsExport.clear(context);
                return saved;
            }
            if (chosenAt(context, scope, epoch) > 0) return true;
            if ("notifications".equals(scope)) FullAccessNotificationHistory.clear(context);
            return choice(context, scope, epoch).commit();
        }
    }

    public static void disableScope(Context context, String scope) {
        if (!recognized(scope)) return;
        synchronized (LOCAL_HISTORY_LOCK) {
            scopeChange++;
            SharedPreferences.Editor choice = settings(context).edit().remove(scope + "_epoch").remove(scope + "_since");
            if ("sms".equals(scope)) choice.remove(SMS_SOURCE);
            choice.commit();
            if ("notifications".equals(scope)) FullAccessNotificationHistory.clear(context);
            if ("sms".equals(scope)) FullAccessSmsExport.clear(context);
        }
    }

    /** Runs on a worker after an explicit local document selection. No persistable URI grant is needed. */
    public static boolean importSmsExport(Context context, Uri selected) {
        long observedChange = scopeChange();
        long epoch = currentEpoch(context);
        if (epoch < 0) return false;
        try {
            JSONArray lines = FullAccessSmsExport.readSelection(context, selected);
            if (lines.length() == 0 || currentEpoch(context) != epoch) return false;
            synchronized (LOCAL_HISTORY_LOCK) {
                if (scopeChange != observedChange) return false;
                FullAccessSmsExport.store(context, epoch, lines);
                if (choice(context, "sms", epoch).putString(SMS_SOURCE, EXPORTED_SMS).commit()) return true;
                FullAccessSmsExport.clear(context);
                return false;
            }
        } catch (IOException | JSONException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Erase local collection state on Full Access revocation. */
    public static void clear(Context context) {
        synchronized (LOCAL_HISTORY_LOCK) {
            scopeChange++;
            settings(context).edit().clear().commit();
            FullAccessNotificationHistory.clear(context);
            FullAccessSmsExport.clear(context);
        }
    }

    public static JSONObject page(Context context, String scope, String cursor) throws JSONException {
        long epoch = currentEpoch(context);
        if (!recognized(scope) || epoch < 0) return response("unavailable", new JSONArray());
        long enabledAt = chosenAt(context, scope, epoch);
        if (enabledAt <= 0) return response("permission_required", new JSONArray());
        String smsSource = "sms".equals(scope) ? smsSource(context) : "";
        if (DIRECT_SMS.equals(smsSource) && !hasSmsAccess(context))
            return response("permission_required", new JSONArray());
        if (EXPORTED_SMS.equals(smsSource) && !FullAccessSmsExport.available(context))
            return response("unavailable", new JSONArray());
        String permission = permissionState(context, scope);
        if (!"ok".equals(permission)) return response(permission, new JSONArray());
        JSONObject result;
        try {
            long now = System.currentTimeMillis();
            result = switch (scope) {
                case "contacts" -> FullAccessContactsSource.page(context, cursor(cursor, 0, Long.MAX_VALUE), PAGE_SIZE);
                case "apps" -> FullAccessAppsSource.page(context, (int) cursor(cursor, 0, Integer.MAX_VALUE), PAGE_SIZE, now);
                case "sms" -> {
                    if (DIRECT_SMS.equals(smsSource)) {
                        yield FullAccessSmsSource.page(context, cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), PAGE_SIZE);
                    }
                    synchronized (LOCAL_HISTORY_LOCK) {
                        if (!EXPORTED_SMS.equals(smsSource) || chosenAt(context, scope, epoch) != enabledAt
                                || !smsSource.equals(smsSource(context)))
                            yield response("unavailable", new JSONArray());
                        yield FullAccessSmsExport.page(context, epoch, (int) cursor(cursor, 0, Integer.MAX_VALUE), PAGE_SIZE);
                    }
                }
                case "notifications" -> {
                    synchronized (LOCAL_HISTORY_LOCK) {
                        if (chosenAt(context, scope, epoch) != enabledAt)
                            yield response("unavailable", new JSONArray());
                        long since = Math.max(enabledAt, now - FullAccessNotificationHistory.RETENTION_MILLIS);
                        try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context)) {
                            yield history.page(context, epoch, cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), since, now, PAGE_SIZE);
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown scope");
            };
        } catch (SecurityException denied) {
            return response("permission_required", new JSONArray());
        } catch (IOException | RuntimeException unavailable) {
            return response("unavailable", new JSONArray());
        }
        if (currentEpoch(context) != epoch || chosenAt(context, scope, epoch) != enabledAt)
            return response("unavailable", new JSONArray());
        if ("sms".equals(scope) && !smsSource.equals(smsSource(context)))
            return response("unavailable", new JSONArray());
        if (DIRECT_SMS.equals(smsSource) && !hasSmsAccess(context)
                || "apps".equals(scope) && result.optBoolean("usageAvailable") && !hasUsageAccess(context))
            return response("permission_required", new JSONArray());
        permission = permissionState(context, scope);
        return "ok".equals(permission) ? result : response(permission, new JSONArray());
    }

    static boolean notificationScopeWasChosen(Context context) {
        return settings(context).getLong("notifications_since", 0) > 0;
    }

    static void recordNotification(Context context, String key, String pkg, String category, long timestamp,
            FullAccessNotificationListener.Preview preview) {
        long observedChange = scopeChange();
        long epoch = currentEpoch(context);
        if (epoch < 0 || !hasNotificationAccess(context)) return;
        synchronized (LOCAL_HISTORY_LOCK) {
            if (scopeChange != observedChange || !hasNotificationAccess(context)) return;
            long enabledAt = chosenAt(context, "notifications", epoch);
            long now = System.currentTimeMillis();
            if (enabledAt <= 0 || timestamp < enabledAt || timestamp > now
                    || timestamp < now - FullAccessNotificationHistory.RETENTION_MILLIS) return;
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context)) {
                history.record(epoch, key, pkg, category, timestamp, now, preview);
            } catch (RuntimeException unavailable) {
                // Collection is optional and must not log private notification data.
            }
        }
    }

    private static long currentEpoch(Context context) {
        return FullAccessGrant.epoch(context);
    }

    // Take the local state witness before checking relationship authority. Never acquire that
    // authority while holding the history lock because its revocation path may clear history.
    private static long scopeChange() {
        synchronized (LOCAL_HISTORY_LOCK) {
            return scopeChange;
        }
    }

    private static boolean recognized(String scope) {
        return "contacts".equals(scope) || "notifications".equals(scope) || "sms".equals(scope) || "apps".equals(scope);
    }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    private static long chosenAt(Context context, String scope, long epoch) {
        SharedPreferences chosen = settings(context);
        return epoch == chosen.getLong(scope + "_epoch", -1) ? chosen.getLong(scope + "_since", 0) : 0;
    }

    private static SharedPreferences.Editor choice(Context context, String scope, long epoch) {
        return settings(context).edit().putLong(scope + "_epoch", epoch)
                .putLong(scope + "_since", System.currentTimeMillis());
    }

    private static String smsSource(Context context) {
        return settings(context).getString(SMS_SOURCE, "");
    }

    private static boolean hasNotificationAccess(Context context) {
        ComponentName listener = new ComponentName(context, FullAccessNotificationListener.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(listener);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String component : enabled.split(":")) {
            if (listener.equals(ComponentName.unflattenFromString(component))) return true;
        }
        return false;
    }

    static String appLabel(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            return bounded(String.valueOf(manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0))), 160);
        } catch (PackageManager.NameNotFoundException unavailable) {
            return bounded(packageName, 160);
        }
    }

    static String bounded(String text, int length) {
        if (text == null) return "";
        if (text.length() <= length) return text;
        int end = length - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    static JSONObject response(String status, JSONArray items) throws JSONException {
        return new JSONObject().put("status", status).put("items", items);
    }

    private static long cursor(String value, long first, long max) {
        if (value == null || value.isEmpty()) return first;
        if (value.length() > 19) throw new IllegalArgumentException("Invalid cursor");
        long parsed = Long.parseLong(value);
        if (parsed < 0 || parsed > max) throw new IllegalArgumentException("Invalid cursor");
        return parsed;
    }
}
