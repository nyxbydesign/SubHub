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
import androidx.annotation.RequiresApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FullAccessAppsSource {
    private static final int MAX_EVENTS = 100_000;

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
        PackageManager manager = context.getPackageManager();
        List<ApplicationInfo> apps = new java.util.ArrayList<>(manager.getInstalledApplications(0));
        apps.removeIf(app -> isSystemApp(app.flags));
        apps.sort(Comparator.comparing(info -> info.packageName));
        Usage usage = Build.VERSION.SDK_INT >= 28 && hasUsageAccess(context) ? usage(context, now) : null;
        JSONArray rows = new JSONArray();
        int end = offset >= apps.size() ? apps.size() : offset + Math.min(limit, apps.size() - offset);
        for (int index = offset; index < end; index++) {
            ApplicationInfo info = apps.get(index);
            JSONObject item = new JSONObject().put("id", info.packageName)
                    .put("title", FullAccessDataSources.bounded(String.valueOf(info.loadLabel(manager)), 160))
                    .put("detail", info.packageName);
            if (usage != null && usage.since >= 0) {
                FullAccessForegroundUsage.Totals totals = usage.totals.get(info.packageName);
                item.put("todayMillis", totals == null ? 0 : totals.todayMillis)
                        .put("weekMillis", totals == null ? 0 : totals.weekMillis);
            }
            rows.put(item);
        }
        JSONObject result = FullAccessDataSources.response("ok", rows).put("usageAvailable", usage != null && usage.since >= 0);
        if (usage != null && usage.since >= 0) result.put("since", usage.since);
        if (end < apps.size()) result.put("nextCursor", Integer.toString(end));
        return result;
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
