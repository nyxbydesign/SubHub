package com.subhub.app.activity;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** The transport installs a durable repository; observers schedule their own UI work. */
public final class ActivitySources {
    private static volatile ActivityRepository repository = new ActivityRepository() {
        public boolean supports(com.subhub.app.people.PersonId person) { return false; }
        public Page load(String before, int limit) { throw new IllegalStateException("Activity unavailable"); }
        public Page locate(String key, int limit) { throw new IllegalStateException("Activity unavailable"); }
        public List<ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) { throw new IllegalStateException("Activity unavailable"); }
        public int unreadCount() { throw new IllegalStateException("Activity unavailable"); }
        public void markRead(List<String> keys) { throw new IllegalStateException("Activity unavailable"); }
    };
    private static final java.util.concurrent.atomic.AtomicLong authorityRevision = new java.util.concurrent.atomic.AtomicLong();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ActivitySources() {}
    public static ActivityRepository repository() { return repository; }
    public static void install(ActivityRepository source) { repository = Objects.requireNonNull(source); authorityChanged(); }
    public static AutoCloseable observe(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }
    /** Presentation invalidation only. It never grants repository or relationship access. */
    public static long authorityRevision() { return authorityRevision.get(); }

    /** Invalidate retained private facts before a relationship transition can be redisplayed. */
    public static void authorityChanged() {
        authorityRevision.incrementAndGet();
        changed();
    }

    public static void changed() { for (Runnable listener : listeners) listener.run(); }
}
