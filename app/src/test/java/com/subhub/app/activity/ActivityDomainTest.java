package com.subhub.app.activity;

import com.subhub.app.people.PersonId;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ActivityDomainTest {
    private static ActivityEvent fact(ActivityEvent.Kind kind, String app, long cents, String currency, int minutes) {
        return new ActivityEvent("event-1", kind, 1_700_000_000_000L, app, cents, currency, "", minutes);
    }
    private static ActivityEntry entry(String key, boolean unread, String label) {
        return new ActivityEntry(key, new PersonId("peer:synthetic-alex"), "Synthetic Alex", "Test device",
                fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 0, "", 0), 1_700_000_000_100L, unread, label);
    }

    @Test public void fourTypedFactsRetainTheirObservedAmountsAppsAndLimits() {
        ActivityEvent opened = fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 0, "", 0);
        ActivityEvent tribute = new ActivityEvent("tribute-1", ActivityEvent.Kind.TRIBUTE_TRIGGERED,
                1_700_000_000_000L, "", 125, "EUR", "watched_app_open", 0);
        ActivityEvent appLimit = fact(ActivityEvent.Kind.APP_LIMIT_REACHED, "com.android.settings", 0, "", 40);
        ActivityEvent shared = fact(ActivityEvent.Kind.SHARED_LIMIT_REACHED, "", 0, "", 60);
        assertEquals("com.android.chrome", opened.packageName);
        assertEquals(125, tribute.amountCents);
        assertEquals("EUR", tribute.currency);
        assertEquals("watched_app_open", tribute.reason);
        assertEquals(40, appLimit.limitMinutes);
        assertEquals(60, shared.limitMinutes);
    }

    @Test public void missingAppNonpositiveLimitAndExtraneousFinancialFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.APP_OPENED, "", 0, "", 0));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.APP_LIMIT_REACHED, "com.android.chrome", 0, "", 0));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.SHARED_LIMIT_REACHED, "", 0, "", -1));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 1, "EUR", 0));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 0, "", 10));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", 0, "EUR", 0));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", 125, "ZZZ", 0));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.TRIBUTE_TRIGGERED, "", 125, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new ActivityEvent("id", ActivityEvent.Kind.APP_OPENED, -1,
                "com.android.chrome", 0, "", "", 0));
    }

    @Test public void peerTextCannotInjectControlOrDirectionalCharacters() {
        for (String invalid : List.of("name\nmessage", "name\u202ehidden", "name\u2066hidden", "x".repeat(161)))
            assertThrows(IllegalArgumentException.class, () -> new ActivityEntry("key", new PersonId("peer"), invalid,
                    "test", fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 0, "", 0), 0, true, "Chrome"));
        assertThrows(IllegalArgumentException.class, () -> fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome;cmd", 0, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new ActivityEntry("", new PersonId("peer"), "Alex", "",
                fact(ActivityEvent.Kind.APP_OPENED, "com.android.chrome", 0, "", 0), 0, true, "Chrome"));
    }

    @Test public void markingReadPreservesFactIdentityAndPackageLabelFallback() {
        ActivityEntry original = entry("relationship/event-1", true, "");
        ActivityEntry read = original.read();
        assertTrue(original.unread);
        assertFalse(read.unread);
        assertEquals(original.key, read.key);
        assertSame(original.event, read.event);
        assertEquals(original.personId, read.personId);
        assertEquals(original.receivedAt, read.receivedAt);
        assertEquals("com.android.chrome", read.appLabel);
        assertSame(read, read.read());
    }

    @Test public void pageCopiesItsInputAndRejectsDuplicateKeysAndOversizedCursors() {
        List<ActivityEntry> input = new ArrayList<>();
        input.add(entry("one", true, "Chrome"));
        ActivityRepository.Page page = new ActivityRepository.Page(input, "next");
        input.clear();
        assertEquals(1, page.entries.size());
        ActivityRepository.Page located = new ActivityRepository.Page(page.entries, "older", "located-cursor");
        assertEquals("older", located.nextCursor);
        assertEquals("located-cursor", located.cursor);
        assertThrows(UnsupportedOperationException.class, () -> page.entries.clear());
        assertThrows(IllegalArgumentException.class, () -> new ActivityRepository.Page(
                List.of(entry("one", true, "Chrome"), entry("one", false, "Chrome")), null));
        assertThrows(IllegalArgumentException.class, () -> new ActivityRepository.Page(List.of(), "x".repeat(2049)));
        assertThrows(IllegalArgumentException.class, () -> new ActivityRepository.Page(List.of(), null, "x".repeat(2049)));
        List<ActivityEntry> oversized = new ArrayList<>();
        for (int i = 0; i < 101; i++) oversized.add(entry("key-" + i, true, "Chrome"));
        assertThrows(IllegalArgumentException.class, () -> new ActivityRepository.Page(oversized, null));
    }
}
