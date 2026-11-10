package com.subhub.app.activity;

import com.subhub.app.people.PersonId;
import com.subhub.app.util.RemoteAppIcon;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ActivityFeedSnapshotTest {
    private static ActivityEntry entry(String key, boolean unread, String label, RemoteAppIcon icon) {
        return new ActivityEntry(key, new PersonId("peer:synthetic"), "Synthetic Sub", "Synthetic device",
                new ActivityEvent(key, ActivityEvent.Kind.APP_OPENED, 1700000000000L, "com.example.app", 0, "", "", 0),
                1700000000100L, unread, label, icon);
    }

    @Test public void separatelyLoadedIdenticalFactsDoNotRebuildRows() {
        assertTrue(ActivityFeedSnapshot.same(List.of(entry("one", true, "Example", null)),
                List.of(entry("one", true, "Example", null))));
    }

    @Test public void readStatusIdentityOrderAndCopyChangesRefreshThePresentation() {
        ActivityEntry first = entry("one", true, "Example", null), second = entry("two", true, "Example", null);
        assertFalse(ActivityFeedSnapshot.same(List.of(first), List.of(first.read())));
        assertFalse(ActivityFeedSnapshot.same(List.of(first), List.of(second)));
        assertFalse(ActivityFeedSnapshot.same(List.of(first, second), List.of(second, first)));
        assertFalse(ActivityFeedSnapshot.same(List.of(first), List.of(entry("one", true, "Renamed", null))));
        assertFalse(ActivityFeedSnapshot.same(List.of(first), List.of(first, second)));
    }

    @Test public void appIconUpdatesAreNotMistakenForUnchangedFacts() {
        RemoteAppIcon absent = new RemoteAppIcon("peer", "generation", "com.example.app", null);
        RemoteAppIcon available = new RemoteAppIcon("peer", "generation", "com.example.app",
                new RemoteAppIcon.Descriptor("a".repeat(64), 64, 1, 1));
        assertTrue(ActivityFeedSnapshot.same(List.of(entry("one", false, "Example", absent)),
                List.of(entry("one", false, "Example", new RemoteAppIcon("peer", "generation", "com.example.app", null)))));
        assertFalse(ActivityFeedSnapshot.same(List.of(entry("one", false, "Example", absent)),
                List.of(entry("one", false, "Example", available))));
    }
}
