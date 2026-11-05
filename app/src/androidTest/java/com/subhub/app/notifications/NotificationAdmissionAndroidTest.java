package com.subhub.app.notifications;

import static org.junit.Assert.*;
import static com.subhub.app.notifications.NotificationPreferences.*;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** A platform adapter holds accepted posts invisible while observing exact cancel ordering. */
@RunWith(AndroidJUnit4.class)
public final class NotificationAdmissionAndroidTest {
    private static final Role ROLE = Role.SUB;
    private static final Event EVENT = Event.CONTRACT_CHANGED;
    private final Set<String> stores = new HashSet<>();
    private Context target, context;
    private String prefix;
    private QueueOperations queue;
    private final OptionalNotificationDispatch.Admissions admissions = new OptionalNotificationDispatch.Admissions();
    @Before public void prepare() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "admission-fixture-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name); return target.getSharedPreferences(prefix + name, mode);
            }
        };
        queue = new QueueOperations();
        queue.pending.add("unrelated-mandatory:7");
    }
    @After public void clean() {
        if (queue != null) admissions.cancelTracked(ROLE, EVENT, queue);
        if (target != null) for (String store : stores) target.deleteSharedPreferences(store);
    }
    @Test public void disableCancelsExactAdmittedKeyBeforeAndroidCanListItsPost() {
        String tag = prefix + "invisible";
        admissions.post(ROLE, EVENT, tag, 8, new Notification(), queue);
        assertTrue(queue.visible.isEmpty());
        assertTrue(admissions.tracked(ROLE, EVENT, tag, 8));
        assertTrue(queue.pending.contains(tag + ":8"));
        assertTrue(NotificationPreferences.setWithCancellation(context, ROLE, EVENT, false,
                () -> admissions.cancelTracked(ROLE, EVENT, queue)));
        assertFalse(enabled(context, ROLE, EVENT));
        assertFalse(queue.pending.contains(tag + ":8"));
        assertTrue(queue.cancelled.contains(tag + ":8"));
        assertEquals(0, admissions.trackedCount(ROLE, EVENT));
        assertEquals(Collections.singleton("unrelated-mandatory:7"), queue.pending);
    }
    @Test public void boundedAdmissionsCancelOldestBeforeEvictionAndReuseDuplicateKeys() {
        for (int index = 0; index < OptionalNotificationDispatch.MAX_ADMITTED_PER_TYPE; index++)
            admissions.post(ROLE, EVENT, prefix + index, 1, new Notification(), queue);
        admissions.post(ROLE, EVENT, prefix + "127", 1, new Notification(), queue);
        assertTrue(queue.cancelled.isEmpty());
        assertEquals(128, admissions.trackedCount(ROLE, EVENT));
        admissions.post(ROLE, EVENT, prefix + "128", 1, new Notification(), queue);
        assertEquals(Collections.singletonList(prefix + "0:1"), queue.cancelled);
        assertFalse(admissions.tracked(ROLE, EVENT, prefix + "0", 1));
        assertFalse(queue.pending.contains(prefix + "0:1"));
        assertEquals(128, admissions.trackedCount(ROLE, EVENT));
        admissions.cancelTracked(ROLE, EVENT, queue);
        assertEquals(0, admissions.trackedCount(ROLE, EVENT));
        assertEquals(Collections.singleton("unrelated-mandatory:7"), queue.pending);
    }
    @Test public void sameNumericActivityIdKeepsIndependentExactAdmissionsPerKind() {
        OptionalNotificationDispatch.Admissions selected = new OptionalNotificationDispatch.Admissions();
        Set<String> pending = new LinkedHashSet<>();
        OptionalNotificationDispatch.Operations platform = new OptionalNotificationDispatch.Operations() {
            public void post(String tag, int id, Notification notice) { pending.add(tag + ":" + id); }
            public void cancel(String tag, int id) { pending.remove(tag + ":" + id); }
        };
        String opened = prefix + "opened", paid = prefix + "paid";
        selected.post(Role.DOM, Event.TRACKED_APP_OPENED, opened, 1, new Notification(), platform);
        selected.post(Role.DOM, Event.TRIBUTE_PAID, paid, 1, new Notification(), platform);
        selected.cancelTracked(Role.DOM, Event.TRACKED_APP_OPENED, platform);
        assertEquals(Collections.singleton(paid + ":1"), pending);
        assertFalse(selected.tracked(Role.DOM, Event.TRACKED_APP_OPENED, opened, 1));
        assertTrue(selected.tracked(Role.DOM, Event.TRIBUTE_PAID, paid, 1));
        selected.cancelTracked(Role.DOM, Event.TRIBUTE_PAID, platform);
        assertTrue(pending.isEmpty());
    }
    private final class QueueOperations implements OptionalNotificationDispatch.Operations {
        final Set<String> pending = new LinkedHashSet<>(), visible = new LinkedHashSet<>();
        final List<String> cancelled = new ArrayList<>();
        @Override public void post(String tag, int id, Notification notification) {
            assertTrue("Registration must precede the platform post", admissions.tracked(ROLE, EVENT, tag, id));
            assertTrue(admissions.trackedCount(ROLE, EVENT) <= 128);
            pending.add(tag + ":" + id);
        }
        @Override public void cancel(String tag, int id) {
            assertTrue("Only fixture exact keys may be cancelled", tag.startsWith(prefix));
            assertTrue("Cancel must precede forgetting the exact admission", admissions.tracked(ROLE, EVENT, tag, id));
            cancelled.add(tag + ":" + id); pending.remove(tag + ":" + id); visible.remove(tag + ":" + id);
        }
    }
}
