package com.subhub.app.notifications;

import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** In-memory platform queue fixtures never post, cancel or change permissions on the actual OS. */
@RunWith(AndroidJUnit4.class)
public final class InternalNotificationAdmissionAndroidTest {
    private final OptionalNotificationDispatch.Admissions admissions = new OptionalNotificationDispatch.Admissions();
    private final Set<String> pending = new LinkedHashSet<>();
    private final List<String> cancelled = new ArrayList<>();
    private final OptionalNotificationDispatch.Operations platform = new OptionalNotificationDispatch.Operations() {
        @Override public void post(String tag, int id, Notification notification) {
            assertTrue(admissions.tracked(Role.SUB, Event.TASKS, tag, id)
                    || admissions.tracked(Role.DOM, Event.TASK_REVIEW, tag, id));
            pending.add(tag + ":" + id);
        }
        @Override public void cancel(String tag, int id) { cancelled.add(tag + ":" + id); pending.remove(tag + ":" + id); }
    };
    private OptionalNotificationDispatch.InternalTaskOwner owner(String generation, AtomicBoolean visible) {
        return new OptionalNotificationDispatch.InternalTaskOwner(generation, visible::get);
    }
    private final String a = "00000000-0000-0000-0000-000000000001";
    private final String b = "00000000-0000-0000-0000-000000000002";
    private boolean post(OptionalNotificationDispatch.InternalTaskOwner owner, String key) {
        return admissions.postInternal(Role.SUB, Event.TASKS, owner, key, new Notification(), platform);
    }
    @Test public void hideCancelsAcceptedInvisiblePostsWithoutTouchingRealNotices() {
        OptionalNotificationDispatch.InternalTaskOwner owner = owner(a, new AtomicBoolean(true));
        admissions.post(Role.SUB, Event.TASKS, "real-task", 2160, new Notification(), platform);
        assertTrue(post(owner, "start:one")); admissions.cancelInternal(owner, platform);
        assertEquals(Set.of("real-task:2160"), pending);
        assertFalse(post(owner, "queued:old"));
        assertEquals(0, admissions.internalTrackedCount(Role.SUB, Event.TASKS, owner));
    }
    @Test public void preferenceDisableCancelsBothBucketsBeforePostsBecomeVisible() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "internal-notice-fixture-" + UUID.randomUUID() + "-";
        Set<String> stores = new HashSet<>();
        Context owned = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name); return target.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            OptionalNotificationDispatch.InternalTaskOwner owner = owner(a, new AtomicBoolean(true));
            admissions.post(Role.SUB, Event.TASKS, "real-task", 2160, new Notification(), platform);
            assertTrue(post(owner, "start:one"));
            assertTrue(NotificationPreferences.setWithCancellation(owned, Role.SUB, Event.TASKS, false,
                    () -> admissions.cancelTracked(Role.SUB, Event.TASKS, platform)));
            assertTrue(pending.isEmpty()); assertFalse(enabled(owned, Role.SUB, Event.TASKS));
            assertEquals(0, admissions.internalTrackedCount(Role.SUB, Event.TASKS, owner));
        } finally { for (String store : stores) target.deleteSharedPreferences(store); }
    }
    @Test public void internalCapEvictsOnlyItsOwnOldestAndDoesNotConsumeRealCapacity() {
        OptionalNotificationDispatch.InternalTaskOwner owner = owner(a, new AtomicBoolean(true));
        for (int i = 0; i < OptionalNotificationDispatch.MAX_ADMITTED_PER_TYPE; i++)
            admissions.post(Role.SUB, Event.TASKS, "real:" + i, 2160, new Notification(), platform);
        for (int i = 0; i < OptionalNotificationDispatch.MAX_INTERNAL_ADMITTED_PER_TYPE; i++) assertTrue(post(owner, "owned:" + i));
        assertTrue(cancelled.isEmpty()); assertTrue(post(owner, "owned:next"));
        assertEquals(List.of(owner.tag(Role.SUB, Event.TASKS, "owned:0") + ":2161"), cancelled);
        assertEquals(128, admissions.trackedCount(Role.SUB, Event.TASKS));
        assertEquals(64, admissions.internalTrackedCount(Role.SUB, Event.TASKS, owner));
        admissions.cancelInternal(owner, platform);
        assertEquals(128, pending.size()); assertTrue(pending.contains("real:0:2160"));
    }
    @Test public void staleGenerationCannotPostAndRetiringOldOwnerCannotCancelNewOwner() {
        AtomicBoolean visibleA = new AtomicBoolean(true), visibleB = new AtomicBoolean(true);
        OptionalNotificationDispatch.InternalTaskOwner first = owner(a, visibleA), second = owner(b, visibleB);
        assertTrue(post(first, "same")); visibleA.set(false); assertFalse(post(first, "late"));
        assertTrue(post(second, "same")); admissions.cancelInternal(first, platform);
        assertEquals(Set.of(second.tag(Role.SUB, Event.TASKS, "same") + ":2161"), pending);
        assertTrue(post(second, "still-current"));
    }
    @Test public void internalOwnerCountIsBoundedWithoutEvictingAnyOtherOwner() {
        for (int i = 0; i < OptionalNotificationDispatch.MAX_INTERNAL_OWNERS_PER_TYPE; i++)
            assertTrue(post(owner(new UUID(0, i + 1).toString(), new AtomicBoolean(true)), "one"));
        assertFalse(post(owner(new UUID(0, 99).toString(), new AtomicBoolean(true)), "overflow"));
        assertTrue(cancelled.isEmpty()); assertEquals(OptionalNotificationDispatch.MAX_INTERNAL_OWNERS_PER_TYPE, pending.size());
    }
}
