package com.subhub.app.activity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A page-local presentation group; original facts and their read identities remain separate. */
final class ActivityGroup {
    final String key;
    final List<ActivityEntry> entries;
    private ActivityGroup(String key, List<ActivityEntry> entries) {
        this.key = key;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
    ActivityEntry latest() { return entries.get(0); }
    boolean contains(String entryKey) {
        for (ActivityEntry entry : entries) if (entry.key.equals(entryKey)) return true;
        return false;
    }
    int unread() {
        int count = 0;
        for (ActivityEntry entry : entries) if (entry.unread) count++;
        return count;
    }
    static List<ActivityGroup> from(List<ActivityEntry> entries) {
        Map<String,List<ActivityEntry>> groups = new LinkedHashMap<>();
        for (ActivityEntry entry : entries) groups.computeIfAbsent(key(entry), unused -> new ArrayList<>()).add(entry);
        List<ActivityGroup> result = new ArrayList<>();
        for (Map.Entry<String,List<ActivityEntry>> group : groups.entrySet()) result.add(new ActivityGroup(group.getKey(), group.getValue()));
        return Collections.unmodifiableList(result);
    }
    private static String key(ActivityEntry entry) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(entry.event.observedAt);
        StringBuilder key = new StringBuilder();
        for (String part : new String[]{entry.personId.value(), entry.event.kind.name(), entry.event.packageName,
                entry.event.currency, entry.event.reason, Integer.toString(entry.event.limitMinutes),
                Integer.toString(day.get(Calendar.ERA)), Integer.toString(day.get(Calendar.YEAR)),
                Integer.toString(day.get(Calendar.DAY_OF_YEAR))}) key.append(part.length()).append(':').append(part);
        return key.toString();
    }
}
