package com.subhub.app.activity;

import com.subhub.app.people.PersonId;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ActivityGroupTest {
    private static ActivityEntry entry(String key, String person, ActivityEvent.Kind kind,
            String app, long time, String reason, String currency, int limit) {
        ActivityEvent event = new ActivityEvent(key, kind, time, app,
                kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED ? 125 : 0, currency, reason, limit);
        return new ActivityEntry(key, new PersonId(person), "Same display name", "Device", event, time, true, app);
    }
    private static ActivityEntry opened(String key, String person, String app, long time) {
        return entry(key, person, ActivityEvent.Kind.APP_OPENED, app, time, "", "", 0);
    }
    @Test public void interleavedRepeatedFactsKeepEveryIdentityAndNewestGroupOrder() {
        long time = 1_700_000_000_000L;
        ActivityEntry first = opened("a", "peer:one", "com.test.first", time);
        ActivityEntry other = opened("b", "peer:one", "com.test.other", time - 1);
        ActivityEntry repeated = opened("c", "peer:one", "com.test.first", time - 2);
        List<ActivityGroup> groups = ActivityGroup.from(List.of(first, other, repeated));
        assertEquals(2, groups.size());
        assertEquals(List.of(first, repeated), groups.get(0).entries);
        assertEquals(other, groups.get(1).latest());
        assertTrue(groups.get(0).contains("c"));
        assertFalse(groups.get(1).contains("c"));
        assertEquals(2, groups.get(0).unread());
        assertThrows(UnsupportedOperationException.class, () -> groups.get(0).entries.clear());
    }
    @Test public void identityKindAndLocalCalendarDayNeverCollapseTogether() {
        Calendar midnight = Calendar.getInstance();midnight.set(2026, Calendar.OCTOBER, 28, 0, 1, 0);
        long time = midnight.getTimeInMillis();
        List<ActivityGroup> groups = ActivityGroup.from(List.of(
                opened("a", "peer:generation-one", "com.test.app", time),
                opened("b", "peer:generation-two", "com.test.app", time),
                opened("c", "peer:generation-one", "com.test.app", time - 120_000),
                entry("d", "peer:generation-one", ActivityEvent.Kind.APP_LIMIT_REACHED, "com.test.app", time, "", "", 15)));
        assertEquals(4, groups.size());
    }
    @Test public void differentRulesCurrenciesAndLimitValuesRemainSeparate() {
        long time = 1_700_000_000_000L;
        List<ActivityGroup> groups = ActivityGroup.from(List.of(
                entry("a", "p", ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", time, "new_detection", "EUR", 0),
                entry("b", "p", ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", time, "censored_tap", "EUR", 0),
                entry("c", "p", ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", time, "new_detection", "USD", 0),
                entry("d", "p", ActivityEvent.Kind.SHARED_LIMIT_REACHED, "", time, "", "", 15),
                entry("e", "p", ActivityEvent.Kind.SHARED_LIMIT_REACHED, "", time, "", "", 30)));
        assertEquals(5, groups.size());
    }
    @Test public void namesAndReadChangesDoNotChangeTheExpansionIdentity() {
        ActivityEntry original = opened("a", "peer:one", "com.test.app", 1_700_000_000_000L);
        ActivityEntry renamed = new ActivityEntry(original.key, original.personId, "Renamed", "Renamed device",
                original.event, original.receivedAt, false, "Renamed app");
        ActivityGroup before = ActivityGroup.from(List.of(original)).get(0);
        ActivityGroup after = ActivityGroup.from(List.of(renamed)).get(0);
        assertEquals(before.key, after.key);
        assertEquals(0, after.unread());
    }
    @Test public void localDayUsesTheCurrentTimezoneAcrossUtcMidnight() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            long one = java.time.Instant.parse("2026-10-27T23:30:00Z").toEpochMilli();
            long two = java.time.Instant.parse("2026-10-28T00:30:00Z").toEpochMilli();
            assertEquals(1, ActivityGroup.from(List.of(opened("a", "p", "com.app", two), opened("b", "p", "com.app", one))).size());
        } finally { TimeZone.setDefault(previous); }
    }
}
