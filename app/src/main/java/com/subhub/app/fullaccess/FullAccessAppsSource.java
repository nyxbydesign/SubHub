package com.subhub.app.fullaccess;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FullAccessAppsSource {
    private static final int MAX_EVENTS = 100_000;
    private static final long SNAPSHOT_MILLIS = 10_000;
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*");

    private FullAccessAppsSource() { }

    static boolean hasUsageAccess(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static JSONObject page(Context context, int offset, int limit, long now) throws JSONException {
        return new Reader().page(context, offset, "", limit, now, true);
    }

    static boolean validPackages(String node) {
        String[] packages = node.split(",", -1);
        if (packages.length > FullAccessDataSources.DETAIL_BATCH_SIZE) return false;
        for (String name : packages) if (!validPackage(name)) return false;
        return true;
    }

    private static boolean validPackage(String name) {
        return name.length() <= 255 && PACKAGE.matcher(name).matches();
    }

    /** Reuse source snapshots only for the lifetime of this authenticated connection. */
    static final class Reader {
        private List<ApplicationInfo> apps;
        private long appsAt;
        private Usage usage;
        private long usageAt;
        private boolean measuredUsage;

        JSONObject page(Context context, int offset, String after, int limit, long now, boolean includeUsage)
                throws JSONException {
            List<ApplicationInfo> installed = installed(context);
            int start = Math.min(offset, installed.size());
            if (!after.isEmpty()) {
                if (!validPackage(after)) throw new IllegalArgumentException("Invalid app cursor");
                while (start < installed.size() && installed.get(start).packageName.compareTo(after) <= 0) start++;
            }
            Usage values = includeUsage ? usageSnapshot(context, now) : null;
            JSONArray rows = new JSONArray();
            int end = start + Math.min(limit, installed.size() - start);
            PackageManager manager = context.getPackageManager();
            for (int index = start; index < end; index++) {
                ApplicationInfo app = installed.get(index);
                JSONObject item = new JSONObject().put("id", app.packageName)
                        .put("title", FullAccessDataSources.bounded(String.valueOf(app.loadLabel(manager)), 160))
                        .put("detail", app.packageName);
                if (hasTotals(values)) addTotals(item, values, app.packageName);
                rows.put(item);
            }
            boolean usageAvailable = includeUsage ? hasTotals(values) : hasUsageAccess(context);
            JSONObject result = FullAccessDataSources.response("ok", rows).put("usageAvailable", usageAvailable);
            if (hasTotals(values)) result.put("since", values.since);
            if (end < installed.size()) {
                String next = includeUsage ? Integer.toString(end) : installed.get(end - 1).packageName;
                result.put("nextCursor", next);
            }
            return result;
        }

        JSONObject usagePage(Context context, String node, long now) throws JSONException {
            Usage values = usageSnapshot(context, now);
            JSONArray rows = new JSONArray();
            if (hasTotals(values)) {
                Set<String> requested = new HashSet<>(List.of(node.split(",", -1)));
                for (ApplicationInfo app : installed(context)) {
                    if (!requested.remove(app.packageName)) continue;
                    rows.put(addTotals(new JSONObject().put("id", app.packageName), values, app.packageName));
                    if (requested.isEmpty()) break;
                }
            }
            JSONObject result = FullAccessDataSources.response("ok", rows).put("usageAvailable", hasTotals(values));
            if (hasTotals(values)) result.put("since", values.since);
            return result;
        }

        private List<ApplicationInfo> installed(Context context) {
            long now = SystemClock.elapsedRealtime();
            if (apps == null || now - appsAt >= SNAPSHOT_MILLIS) {
                List<ApplicationInfo> next = new ArrayList<>(context.getPackageManager().getInstalledApplications(0));
                next.removeIf(app -> isSystemApp(app.flags));
                next.sort(Comparator.comparing(info -> info.packageName));
                apps = next;
                appsAt = now;
            }
            return apps;
        }

        private Usage usageSnapshot(Context context, long now) {
            if (Build.VERSION.SDK_INT < 28 || !hasUsageAccess(context)) {
                measuredUsage = false;
                usage = null;
                return null;
            }
            long elapsed = SystemClock.elapsedRealtime();
            if (!measuredUsage || elapsed - usageAt >= SNAPSHOT_MILLIS) {
                usage = FullAccessAppsSource.usage(context, now);
                usageAt = elapsed;
                measuredUsage = true;
            }
            return usage;
        }
    }

    private static boolean hasTotals(Usage usage) { return usage != null && usage.since >= 0; }

    private static JSONObject addTotals(JSONObject item, Usage usage, String pkg) throws JSONException {
        FullAccessForegroundUsage.Totals totals = usage.totals.get(pkg);
        return item.put("todayMillis", totals == null ? 0 : totals.todayMillis)
                .put("weekMillis", totals == null ? 0 : totals.weekMillis);
    }

    static void removeUsage(JSONObject response) throws JSONException {
        response.put("usageAvailable", false).remove("since");
        JSONArray rows = response.optJSONArray("items");
        if (rows == null) return;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            item.remove("todayMillis");
            item.remove("weekMillis");
        }
    }

    static boolean isSystemApp(int flags) {
        return (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private record Usage(Map<String, FullAccessForegroundUsage.Totals> totals, long since) { }

    @RequiresApi(28)
    private static Usage usage(Context context, long now) {
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) return null;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli();
        long todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long warmup = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageEvents events;
        try {
            events = manager.queryEvents(warmup, now);
        } catch (SecurityException unavailable) {
            return null;
        }
        if (events == null) return null;
        FullAccessForegroundUsage measured = new FullAccessForegroundUsage(weekStart, todayStart);
        UsageEvents.Event entry = new UsageEvents.Event();
        int examined = 0;
        while (events.getNextEvent(entry)) {
            // A bounded scan fails closed if coverage cannot be established.
            if (++examined > MAX_EVENTS) return null;
            FullAccessForegroundUsage.Event event = event(entry.getEventType());
            if (event != null && entry.getTimeStamp() < now)
                measured.event(event, entry.getTimeStamp(), entry.getPackageName(), entry.getClassName());
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        boolean tailConfirmed = power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
        return new Usage(measured.finish(now, tailConfirmed), measured.observedFrom());
    }

    @RequiresApi(28)
    private static FullAccessForegroundUsage.Event event(int type) {
        if (Build.VERSION.SDK_INT >= 29) {
            if (type == UsageEvents.Event.DEVICE_SHUTDOWN) return FullAccessForegroundUsage.Event.DEVICE_SHUTDOWN;
            if (type == UsageEvents.Event.DEVICE_STARTUP) return FullAccessForegroundUsage.Event.DEVICE_STARTUP;
        }
        return switch (type) {
            case UsageEvents.Event.SCREEN_INTERACTIVE -> FullAccessForegroundUsage.Event.SCREEN_ON;
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE -> FullAccessForegroundUsage.Event.SCREEN_OFF;
            case UsageEvents.Event.KEYGUARD_HIDDEN -> FullAccessForegroundUsage.Event.UNLOCKED;
            case UsageEvents.Event.KEYGUARD_SHOWN -> FullAccessForegroundUsage.Event.LOCKED;
            // These numeric values also represent ACTIVITY_RESUMED and ACTIVITY_PAUSED.
            case UsageEvents.Event.MOVE_TO_FOREGROUND -> FullAccessForegroundUsage.Event.RESUMED;
            case UsageEvents.Event.MOVE_TO_BACKGROUND -> FullAccessForegroundUsage.Event.PAUSED;
            default -> null;
        };
    }
}
