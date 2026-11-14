package com.subhub.app.fullaccess;

import java.util.LinkedHashMap;
import java.util.Map;

/** Accumulates only foreground intervals with confirmed interactive and unlocked screen state. */
final class FullAccessForegroundUsage {
    enum Event {
        SCREEN_ON, SCREEN_OFF, UNLOCKED, LOCKED, RESUMED, PAUSED, DEVICE_SHUTDOWN, DEVICE_STARTUP
    }

    static final class Totals {
        long todayMillis;
        long weekMillis;
    }

    private final long weekStart;
    private final long todayStart;
    private final Map<String, String> resumed = new LinkedHashMap<>();
    private final Map<String, Totals> totals = new LinkedHashMap<>();
    private long previous = -1;
    private long observedFrom = -1;
    private boolean interactive;
    private boolean unlocked;

    FullAccessForegroundUsage(long weekStart, long todayStart) {
        this.weekStart = weekStart;
        this.todayStart = todayStart;
    }

    void event(Event event, long timestamp, String packageName, String activity) {
        if (timestamp < 0 || timestamp < previous) return;
        // After a restart without a shutdown marker, the last activity's end time is unknown.
        if (event != Event.DEVICE_STARTUP) countUntil(timestamp);
        previous = timestamp;
        switch (event) {
            case SCREEN_ON -> interactive = true;
            case SCREEN_OFF -> {
                interactive = false;
                resumed.clear();
            }
            case UNLOCKED -> unlocked = true;
            case LOCKED -> {
                unlocked = false;
                resumed.clear();
            }
            case DEVICE_STARTUP, DEVICE_SHUTDOWN -> {
                interactive = false;
                unlocked = false;
                resumed.clear();
            }
            case RESUMED, PAUSED -> {
                if (packageName == null || packageName.isEmpty()) return;
                String key = packageName + '/' + (activity == null ? "" : activity);
                resumed.remove(key);
                if (event == Event.RESUMED && interactive && unlocked) resumed.put(key, packageName);
            }
        }
    }

    Map<String, Totals> finish(long timestamp, boolean currentlyInteractiveAndUnlocked) {
        if (currentlyInteractiveAndUnlocked) countUntil(timestamp);
        previous = Math.max(previous, timestamp);
        return totals;
    }

    long observedFrom() { return observedFrom; }

    private void countUntil(long timestamp) {
        if (!interactive || !unlocked || previous < 0 || timestamp <= previous) return;
        String packageName = null;
        for (String candidate : resumed.values()) packageName = candidate;
        long start = Math.max(previous, weekStart);
        if (packageName == null || start >= timestamp) return;
        if (observedFrom < 0) observedFrom = start;
        Totals app = totals.computeIfAbsent(packageName, ignored -> new Totals());
        app.weekMillis += timestamp - start;
        app.todayMillis += Math.max(0, timestamp - Math.max(start, todayStart));
    }
}
