package com.subhub.app.notifications;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.subhub.app.notifications.NotificationPreferences.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NotificationPreferencesAndroidTest {
    private Context context;
    private final Set<String> stores = new HashSet<>();
    @Before public void prepare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "notification-fixture-" + java.util.UUID.randomUUID() + ":";
        context = new ContextWrapper(target) {
            @Override public Object getSystemService(String name) {
                return Context.NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name); return target.getSharedPreferences(prefix + name, mode);
            }
        };
    }
    @After public void clean() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : stores) assertTrue(target.deleteSharedPreferences(name));
    }
    @Test public void choicesAreLocalIndependentAndRoleAware() {
        Event[] dom = {Event.TRACKED_APP_OPENED, Event.APP_LIMIT_REACHED, Event.SHARED_LIMIT_REACHED,
                Event.TRIBUTE_TRIGGERED, Event.TRIBUTE_PAID, Event.TASK_REVIEW};
        for (Event event : dom) {
            assertTrue(enabled(context, Role.DOM, event));
            assertFalse(enabled(context, Role.SUB, event));
        }
        assertFalse(enabled(context, Role.DOM, Event.ACTIVITY));
        assertFalse(set(context, Role.DOM, Event.ACTIVITY, false));
        assertFalse(enabled(context, Role.SUB, Event.ACTIVITY));
        assertFalse(enabled(context, Role.SUB, Event.TASK_REVIEW));
        for (Event event : new Event[]{Event.CONTRACT_CHANGED, Event.SESSION_STARTED, Event.SESSION_STOPPED}) {
            assertTrue(enabled(context, Role.SUB, event));
            assertFalse(enabled(context, Role.DOM, event));
            assertFalse(set(context, Role.DOM, event, true));
        }
        assertTrue(set(context, Role.DOM, Event.APP_LIMIT_REACHED, false));
        assertFalse(enabled(context, Role.DOM, Event.APP_LIMIT_REACHED));
        assertTrue(enabled(context, Role.DOM, Event.TRACKED_APP_OPENED));
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll().isEmpty());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.SUB_ONLY.name()).commit());
        assertFalse(enabled(context, Role.DOM, Event.CONTRACT_CHANGED));
        assertTrue(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
    }
    @Test public void legacyAggregateFalseIsOnlyAnUnsetPerKindFallback() {
        assertTrue(context.getSharedPreferences(STORE, 0).edit().putBoolean(key(Role.DOM, Event.ACTIVITY), false).commit());
        for (Event event : Event.values()) if (isActivity(event)) assertFalse(enabled(context, Role.DOM, event));
        assertTrue(enabled(context, Role.DOM, Event.TASK_REVIEW));
        assertTrue(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
        assertTrue(set(context, Role.DOM, Event.TRIBUTE_PAID, true));
        assertTrue(enabled(context, Role.DOM, Event.TRIBUTE_PAID));
        assertFalse(enabled(context, Role.DOM, Event.TRIBUTE_TRIGGERED));
        assertTrue(context.getSharedPreferences(STORE, 0).edit().putBoolean(key(Role.DOM, Event.ACTIVITY), true).commit());
        assertTrue(set(context, Role.DOM, Event.TRIBUTE_PAID, false));
        assertFalse(enabled(context, Role.DOM, Event.TRIBUTE_PAID));
        assertTrue(enabled(context, Role.DOM, Event.TRIBUTE_TRIGGERED));
    }
    @Test public void durableSequencesRejectReplaysAndConsumeDisabledEventsWithoutBacklog() {
        assertTrue(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 3));
        assertFalse(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 3));
        assertFalse(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 2));
        assertTrue(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STOPPED, "peer:generation", 3));
        assertFalse(PeerNotifications.consume(context, Role.DOM, Event.SESSION_STARTED, "peer:generation", 3));
        assertTrue(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:new-generation", 1));
        assertTrue(set(context, Role.SUB, Event.SESSION_STARTED, false));
        assertFalse(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 4));
        assertTrue(set(context, Role.SUB, Event.SESSION_STARTED, true));
        assertFalse(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 4));
        assertTrue(PeerNotifications.consume(context, Role.SUB, Event.SESSION_STARTED, "peer:generation", 5));
    }
}
