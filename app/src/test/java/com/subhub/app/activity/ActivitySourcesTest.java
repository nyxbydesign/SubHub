package com.subhub.app.activity;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ActivitySourcesTest {
    @Test public void observerReceivesPublisherThreadAndCloseStopsDelivery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> delivered = new AtomicReference<>();
        AutoCloseable observer = ActivitySources.observe(() -> { calls.incrementAndGet(); delivered.set(Thread.currentThread()); });
        try {
            Thread publisher = new Thread(ActivitySources::changed, "activity-source-fixture");
            publisher.start(); publisher.join(5000);
            assertFalse("Publisher terminated", publisher.isAlive());
            assertEquals(1, calls.get());
            assertSame(publisher, delivered.get());
        } finally { observer.close(); }
        ActivitySources.changed();
        assertEquals(1, calls.get());
    }

    @Test public void installingRepositoryNotifiesAndRestoresTheExactPreviousInstance() throws Exception {
        ActivityRepository original = ActivitySources.repository();
        ActivityRepository fixture = new ActivityRepository() {
            public Page load(String before, int limit) { return new Page(List.of(), null); }
            public Page locate(String key, int limit) { return new Page(List.of(), null); }
            public java.util.List<ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) {
                java.util.List<ActivityEntry> entries = new java.util.ArrayList<>();
                return entries;
            }
            public int unreadCount() { return 0; }
            public void markRead(List<String> keys) { }
        };
        AtomicInteger calls = new AtomicInteger();
        try (AutoCloseable observer = ActivitySources.observe(calls::incrementAndGet)) {
            ActivitySources.install(fixture);
            assertSame(fixture, ActivitySources.repository());
            assertEquals(1, calls.get());
        } finally { ActivitySources.install(original); }
        assertSame(original, ActivitySources.repository());
    }
}
