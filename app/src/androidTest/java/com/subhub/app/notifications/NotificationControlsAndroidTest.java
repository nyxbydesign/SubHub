package com.subhub.app.notifications;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.Map;
import org.junit.*;
import org.junit.rules.RuleChain;
import static com.subhub.app.notifications.NotificationPreferences.*;

public final class NotificationControlsAndroidTest {
    @Rule public final RuleChain rules = RuleChain.outerRule(new KeyholderPresentationAndroidTest.IntegrationFixture())
            .around(new UiAutomationStateRule());
    private Context context;
    private Map<String, ?> original;
    private com.subhub.app.activity.ActivityRepository originalActivity;
    private android.app.Application application;
    private final java.util.Set<com.subhub.app.util.AsyncUiScope> ownedReaders =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final android.app.Application.ActivityLifecycleCallbacks readerObservation = new android.app.Application.ActivityLifecycleCallbacks() {
        public void onActivityStarted(android.app.Activity activity) {
            if (activity instanceof GlobalSettingsActivity) synchronized (ownedReaders) {
                ownedReaders.add(com.subhub.app.util.AsyncUiScope.forPage((GlobalSettingsActivity) activity));
            }
        }
        public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
        public void onActivityResumed(android.app.Activity activity) { }
        public void onActivityPaused(android.app.Activity activity) { }
        public void onActivityStopped(android.app.Activity activity) { }
        public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
        public void onActivityDestroyed(android.app.Activity activity) { }
    };
    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        original = context.getSharedPreferences(STORE, 0).getAll();
        originalActivity = com.subhub.app.activity.ActivitySources.repository();
        // Bottom navigation needs an unread count, not the canonical relationship store in this UI fixture.
        com.subhub.app.activity.ActivitySources.install(new com.subhub.app.activity.ActivityRepository() {
            public Page load(String before, int limit) { return new Page(java.util.Collections.emptyList(), null); }
            public Page locate(String key, int limit) { return new Page(java.util.Collections.emptyList(), null); }
            public java.util.List<com.subhub.app.activity.ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) {
                return java.util.Collections.emptyList();
            }
            public int unreadCount() { return 0; }
            public void markRead(java.util.List<String> keys) { }
        });
        application = (android.app.Application) context.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> application.registerActivityLifecycleCallbacks(readerObservation));
        for (android.service.notification.StatusBarNotification notice : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
            org.junit.Assume.assumeTrue("UI toggles must preserve unrelated notices of the types they disable",
                    notice.getTag() == null || !notice.getTag().startsWith("subhub.activity:")
                            && !notice.getTag().startsWith("subhub.optional:SUB:CONTRACT_CHANGED:"));
        assertTrue(context.getSharedPreferences(STORE, 0).edit().clear().commit());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
    }
    @After public void restore() throws Exception {
        boolean drained = false;
        try {
            awaitOwnedReaders();
            drained = true;
        } finally {
            try { SharedPreferenceTestRestore.restore(context.getSharedPreferences(STORE, 0), original); }
            finally {
                // A failed drain must not expose the canonical repository to an unfinished fixture lookup.
                if (drained && originalActivity != null) com.subhub.app.activity.ActivitySources.install(originalActivity);
            }
        }
    }
    private void awaitOwnedReaders() throws Exception {
        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            if (application != null) application.unregisterActivityLifecycleCallbacks(readerObservation);
        });
        instrumentation.waitForIdleSync();
        java.lang.reflect.Field worker = com.subhub.app.util.AsyncUiScope.class.getDeclaredField("worker");
        java.lang.reflect.Field serial = com.subhub.app.util.AsyncUiScope.class.getDeclaredField("serialWorker");
        worker.setAccessible(true); serial.setAccessible(true);
        java.util.List<com.subhub.app.util.AsyncUiScope> readers;
        synchronized (ownedReaders) { readers = new java.util.ArrayList<>(ownedReaders); }
        for (com.subhub.app.util.AsyncUiScope reader : readers) {
            java.util.concurrent.ExecutorService[] executors = {
                    (java.util.concurrent.ExecutorService) worker.get(reader),
                    (java.util.concurrent.ExecutorService) serial.get(reader)};
            for (java.util.concurrent.ExecutorService executor : executors) if (executor != null) {
                assertTrue("Closed Settings page must shut down its owned reader", executor.isShutdown());
                assertTrue("Settings fixture reader must drain before restoring ActivitySources",
                        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        instrumentation.waitForIdleSync();
    }
    @Test public void pairedBothLocalPinWorkspaceShowsDomChoicesWithoutProtectedDomAuthority() {
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        ControllerPinManager.enterSubMode();
        com.subhub.app.people.People.installKeyholder(KeyholderPresentationAndroidTest.provider(
                com.subhub.app.security.KeyholderProvider.State.READY, "Synthetic assigned Dom"));
        assertFalse(ControllerPinManager.isDomModeActive());
        assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
        assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());
        assertFalse(ControllerPinManager.isDomModeActive());
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> assertWorkspace(activity, Role.DOM));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertWorkspace(activity, Role.DOM);
                ControllerPinManager.enterSubMode();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(ControllerPinManager.isLocalDomWorkspaceUnlocked());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertWorkspace(activity, Role.SUB);
            });
        }
    }
    @Test public void nativeTogglePersistsAndRecreationUsesPreferencesInBothDirections() {
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        ControllerPinManager.enterDomMode();
        String tag = "notification:" + key(Role.DOM, Event.TRACKED_APP_OPENED);
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("settings:notifications"));
                assertWorkspace(activity, Role.DOM);
            });
            onView(withTagValue(is((Object) tag))).perform(revealAboveNavigation(), click());
            assertFalse(enabled(context, Role.DOM, Event.TRACKED_APP_OPENED));
            assertTrue(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertTrue(set(context, Role.DOM, Event.TRACKED_APP_OPENED, true));
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertTrue(enabled(context, Role.DOM, Event.TRACKED_APP_OPENED));
            assertTrue(set(context, Role.DOM, Event.TRACKED_APP_OPENED, false));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked()));
            assertFalse(enabled(context, Role.DOM, Event.TRACKED_APP_OPENED));
            scenario.onActivity(activity -> ControllerPinManager.enterSubMode());
            scenario.recreate();
            scenario.onActivity(activity -> assertWorkspace(activity, Role.SUB));
            String sub = "notification:" + key(Role.SUB, Event.CONTRACT_CHANGED);
            onView(withTagValue(is((Object) sub))).perform(revealAboveNavigation(), click());
            assertFalse(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
            assertTrue(enabled(context, Role.DOM, Event.APP_LIMIT_REACHED));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertWorkspace(activity, Role.SUB);
                assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(sub)).isChecked());
                assertTrue(ControllerPinManager.verify(activity, "5729"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertWorkspace(activity, Role.DOM);
                assertFalse(((CompoundButton) activity.findViewById(android.R.id.content).findViewWithTag(tag)).isChecked());
            });
            assertFalse(enabled(context, Role.SUB, Event.CONTRACT_CHANGED));
        }
    }
    private static void assertWorkspace(GlobalSettingsActivity activity, Role role) {
        android.view.View root = activity.findViewById(android.R.id.content);
        int count = 0;
        for (Role candidate : Role.values()) for (Event event : Event.values()) {
            boolean expected = candidate == role && (role == Role.DOM ? isActivity(event) || event == Event.TASK_REVIEW
                    : event == Event.CONTRACT_CHANGED || event == Event.SESSION_STARTED || event == Event.SESSION_STOPPED);
            android.view.View control = root.findViewWithTag("notification:" + key(candidate, event));
            assertEquals(candidate + ":" + event, expected, control != null);
            if (control != null) count++;
        }
        assertEquals(role == Role.DOM ? 6 : 3, count);
    }
}
