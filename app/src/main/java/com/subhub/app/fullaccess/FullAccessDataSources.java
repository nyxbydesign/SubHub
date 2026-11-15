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
    static final int PAGE_SIZE = 12;
    private static final int FIRST_PAGE_SIZE = 8;
    static final int DETAIL_BATCH_SIZE = 6;
    private static final String SMS_SOURCE = "sms_source";
    private static final String DIRECT_SMS = "direct";
    private static final String EXPORTED_SMS = "export";
    private static final Object LOCAL_HISTORY_LOCK = new Object();
    private static volatile long scopeChange;

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
    public static boolean enableScope(Context context, String scope, boolean notificationsDisclosed) {
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
            if (!"notifications".equals(scope)) return choice(context, scope, epoch).commit();
            FullAccessNotificationHistory.clear(context);
            SharedPreferences.Editor editor = choice(context, scope, epoch)
                    .remove(FullAccessNotificationListener.TEXT_SINCE);
            if (notificationsDisclosed) editor.putLong(FullAccessNotificationListener.TEXT_SINCE, System.currentTimeMillis() + 1);
            boolean saved = editor.commit();
            if (saved) FullAccessNotificationListener.reconcileIfConnected();
            return saved;
        }
    }

    public static void disableScope(Context context, String scope) {
        if (!recognized(scope)) return;
        synchronized (LOCAL_HISTORY_LOCK) {
            scopeChange++;
            SharedPreferences.Editor choice = settings(context).edit().remove(scope + "_epoch").remove(scope + "_since");
            if ("sms".equals(scope)) choice.remove(SMS_SOURCE);
            if ("notifications".equals(scope)) choice.remove(FullAccessNotificationListener.TEXT_SINCE);
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
        return page(context, scope, "page", "", cursor);
    }

    public static boolean supportsRead(String scope, String operation, String node) {
        if (node == null || node.length() > 512) return false;
        if ("page".equals(operation)) return recognized(scope) && node.isEmpty();
        if ("contacts".equals(scope))
            return "contact_rows".equals(operation) && node.isEmpty()
                    || "contact_photos".equals(operation) && FullAccessContactsSource.validPhotoIds(node);
        if ("apps".equals(scope))
            return "app_rows".equals(operation) && node.isEmpty()
                    || "app_usage".equals(operation) && FullAccessAppsSource.validPackages(node);
        if ("sms".equals(scope))
            return "conversations".equals(operation) && node.isEmpty()
                    || "thread".equals(operation) && !node.isEmpty();
        return "notifications".equals(scope) && ("groups".equals(operation) && node.isEmpty()
                || "group".equals(operation) && !node.isEmpty());
    }

    /** Source snapshots exist only while the authenticated read channel owns them. */
    public static final class ReadSession {
        private final FullAccessAppsSource.Reader apps = new FullAccessAppsSource.Reader();
        private final FullAccessSmsExport.Reader export = new FullAccessSmsExport.Reader();
    }

    public static JSONObject page(Context context, String scope, String operation, String node, String cursor)
            throws JSONException {
        return page(context, scope, operation, node, cursor, null);
    }

    public static JSONObject page(Context context, String scope, String operation, String node, String cursor,
            ReadSession session) throws JSONException {
        if (cursor == null) cursor = "";
        long epoch = currentEpoch(context);
        if (!supportsRead(scope, operation, node) || epoch < 0) return response("unavailable", new JSONArray());
        long enabledAt = chosenAt(context, scope, epoch);
        if (enabledAt <= 0) return response("permission_required", new JSONArray());
        String smsSource = "sms".equals(scope) ? smsSource(context) : "";
        if (DIRECT_SMS.equals(smsSource) && !hasSmsAccess(context))
            return response("permission_required", new JSONArray());
        if (EXPORTED_SMS.equals(smsSource) && !FullAccessSmsExport.available(context))
            return response("unavailable", new JSONArray());
        String permission = permissionState(context, scope);
        if (!"ok".equals(permission)) return response(permission, new JSONArray());
        long smsContactsSince = DIRECT_SMS.equals(smsSource) && !"page".equals(operation)
                ? contactsSharedSince(context, epoch) : 0;
        long textSince = "notifications".equals(scope) ? notificationTextSince(context) : 0;
        boolean details = "app_usage".equals(operation) || "contact_photos".equals(operation);
        if (details && !cursor.isEmpty()) return response("unavailable", new JSONArray());
        int count = session != null && cursor.isEmpty() ? FIRST_PAGE_SIZE : PAGE_SIZE;
        JSONObject result;
        try {
            long now = System.currentTimeMillis();
            result = switch (scope) {
                case "contacts" -> "contact_photos".equals(operation)
                        ? FullAccessContactsSource.photos(context, node)
                        : FullAccessContactsSource.page(context, cursor(cursor, 0, Long.MAX_VALUE), count,
                                !"contact_rows".equals(operation));
                case "apps" -> {
                    FullAccessAppsSource.Reader apps = session == null ? new FullAccessAppsSource.Reader() : session.apps;
                    if ("app_usage".equals(operation)) yield apps.usagePage(context, node, now);
                    boolean inlineUsage = !"app_rows".equals(operation);
                    yield apps.page(context, inlineUsage ? (int) cursor(cursor, 0, Integer.MAX_VALUE) : 0,
                            inlineUsage ? "" : cursor, count, now, inlineUsage);
                }
                case "sms" -> {
                    if (DIRECT_SMS.equals(smsSource)) {
                        yield directSms(context, operation, node, cursor, smsContactsSince > 0, count);
                    }
                    synchronized (LOCAL_HISTORY_LOCK) {
                        if (!EXPORTED_SMS.equals(smsSource) || chosenAt(context, scope, epoch) != enabledAt
                                || !smsSource.equals(smsSource(context)))
                            yield response("unavailable", new JSONArray());
                        yield exportedSms(context, epoch, operation, node, cursor, false, count,
                                session == null ? new FullAccessSmsExport.Reader() : session.export);
                    }
                }
                case "notifications" -> {
                    synchronized (LOCAL_HISTORY_LOCK) {
                        if (chosenAt(context, scope, epoch) != enabledAt
                                || textSince != notificationTextSince(context))
                            yield response("unavailable", new JSONArray());
                        long since = Math.max(enabledAt, now - FullAccessNotificationHistory.RETENTION_MILLIS);
                        try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context, false, textSince)) {
                            yield notifications(history, context, epoch, operation, node, cursor, since, now, count);
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown scope");
            };
        } catch (SecurityException denied) {
            return response("permission_required", new JSONArray());
        } catch (IllegalArgumentException invalid) {
            return response("unavailable", new JSONArray());
        } catch (IOException | RuntimeException unavailable) {
            result = response("unavailable", new JSONArray());
        }
        if (currentEpoch(context) != epoch || chosenAt(context, scope, epoch) != enabledAt)
            return response("unavailable", new JSONArray());
        if ("notifications".equals(scope)
                && textSince != notificationTextSince(context))
            return response("unavailable", new JSONArray());
        if ("sms".equals(scope) && !smsSource.equals(smsSource(context)))
            return response("unavailable", new JSONArray());
        if (smsContactsSince > 0 && contactsSharedSince(context, epoch) != smsContactsSince)
            return response("unavailable", new JSONArray());
        if (DIRECT_SMS.equals(smsSource) && !hasSmsAccess(context))
            return response("permission_required", new JSONArray());
        permission = permissionState(context, scope);
        if (!"ok".equals(permission)) return response(permission, new JSONArray());
        boolean usageAllowed = "apps".equals(scope) && hasUsageAccess(context);
        if ("apps".equals(scope) && !usageAllowed) FullAccessAppsSource.removeUsage(result);
        if (session == null) return result;
        String status = result.optString("status");
        if (!"ok".equals(status) && !"unavailable".equals(status)) return result;
        // A viewer can retain rows only on a same-authority provider failure, never on a lost channel.
        String revision = Long.toUnsignedString(epoch, 36) + ":" + enabledAt + ":" + smsSource + ":"
                + smsContactsSince + ":" + textSince + ":" + usageAllowed;
        result.put("scopeRevision", revision);
        return "unavailable".equals(status) ? result.put("status", "temporarily_unavailable") : result;
    }

    private static long contactsSharedSince(Context context, long epoch) {
        return "ok".equals(permissionState(context, "contacts")) ? chosenAt(context, "contacts", epoch) : 0;
    }

    static JSONObject directSms(Context context, String operation, String node, String cursor, boolean contacts)
            throws IOException, JSONException {
        return directSms(context, operation, node, cursor, contacts, PAGE_SIZE);
    }

    private static JSONObject directSms(Context context, String operation, String node, String cursor, boolean contacts,
            int count) throws JSONException {
        return switch (operation) {
            case "conversations" -> FullAccessSmsSource.conversations(context, cursor, count, contacts);
            case "thread" -> FullAccessSmsSource.thread(context, node, cursor, count, contacts);
            default -> FullAccessSmsSource.page(context, cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), count);
        };
    }

    static JSONObject exportedSms(Context context, long epoch, String operation, String node, String cursor,
            boolean demo) throws IOException, JSONException {
        return exportedSms(context, epoch, operation, node, cursor, demo, PAGE_SIZE, new FullAccessSmsExport.Reader());
    }

    private static JSONObject exportedSms(Context context, long epoch, String operation, String node, String cursor,
            boolean demo, int count, FullAccessSmsExport.Reader reader) throws IOException, JSONException {
        return switch (operation) {
            case "conversations" -> reader.conversations(context, epoch, cursor, count, demo);
            case "thread" -> reader.thread(context, epoch, node, cursor, count, demo);
            default -> reader.page(context, epoch, (int) cursor(cursor, 0, Integer.MAX_VALUE), count, demo);
        };
    }

    static JSONObject notifications(FullAccessNotificationHistory history, Context context, long epoch,
            String operation, String node, String cursor, long since, long now) throws JSONException {
        return notifications(history, context, epoch, operation, node, cursor, since, now, PAGE_SIZE);
    }

    private static JSONObject notifications(FullAccessNotificationHistory history, Context context, long epoch,
            String operation, String node, String cursor, long since, long now, int count) throws JSONException {
        JSONObject page = switch (operation) {
            case "groups" -> history.groups(context, epoch, cursor, since, now, count);
            case "group" -> history.group(context, epoch, node, cursor, since, now, count);
            default -> history.page(context, epoch, cursor(cursor, Long.MAX_VALUE, Long.MAX_VALUE), since, now, count);
        };
        return page.put("notificationRevision", history.previewRevision());
    }

    static FullAccessNotificationListener.Choice notificationChoice(Context context) {
        SharedPreferences prefs = settings(context);
        long observedChange = scopeChange;
        long epoch = prefs.getLong("notifications_epoch", -1);
        long since = chosenAt(context, "notifications", epoch);
        return epoch < 0 || since <= 0 ? null : new FullAccessNotificationListener.Choice(epoch, "", observedChange,
                since, notificationTextSince(context));
    }

    static boolean renewNotifications(Context context, FullAccessNotificationListener.Choice expected) {
        if (currentEpoch(context) != expected.epoch() || expected.textSince() > 0) return false;
        synchronized (LOCAL_HISTORY_LOCK) {
            if (!expected.equals(notificationChoice(context)) || !hasNotificationAccess(context)) return false;
            return settings(context).edit().putLong(FullAccessNotificationListener.TEXT_SINCE,
                    System.currentTimeMillis() + 1).commit();
        }
    }

    private static long notificationTextSince(Context context) {
        return settings(context).getLong(FullAccessNotificationListener.TEXT_SINCE, 0);
    }

    static void recordNotification(Context context, FullAccessNotificationListener.Choice expected,
            String key, String pkg, String category, long timestamp, FullAccessNotificationListener.Preview preview,
            FullAccessNotificationListener.Preview details, String icon) {
        if (currentEpoch(context) != expected.epoch() || !hasNotificationAccess(context)) return;
        synchronized (LOCAL_HISTORY_LOCK) {
            FullAccessNotificationListener.Choice current = notificationChoice(context);
            if (current == null || !current.sameScope(expected) || !hasNotificationAccess(context)) return;
            long now = System.currentTimeMillis();
            if (timestamp < current.since() || timestamp > now
                    || timestamp < now - FullAccessNotificationHistory.RETENTION_MILLIS) return;
            long textSince = current.acceptedTextSince(expected, timestamp);
            try (FullAccessNotificationHistory history = new FullAccessNotificationHistory(context, false, textSince)) {
                history.record(current.epoch(), key, pkg, category, timestamp, now, preview, details, icon);
            } catch (RuntimeException unavailable) {
                // Optional collection must never log notification content.
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

    static SharedPreferences settings(Context context) {
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

    static boolean hasNotificationAccess(Context context) {
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

    static long cursor(String value, long first, long max) {
        if (value == null || value.isEmpty()) return first;
        if (value.length() > 19) throw new IllegalArgumentException("Invalid cursor");
        long parsed = Long.parseLong(value);
        if (parsed < 0 || parsed > max) throw new IllegalArgumentException("Invalid cursor");
        return parsed;
    }
}
