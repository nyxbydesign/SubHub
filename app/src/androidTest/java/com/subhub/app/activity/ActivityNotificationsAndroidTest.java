package com.subhub.app.activity;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class ActivityNotificationsAndroidTest {
    private final KeyholderPresentationAndroidTest.IntegrationFixture integration = new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final RuleChain rules = RuleChain.outerRule(integration).around(new UiAutomationStateRule());
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private NotificationManager manager;
    private boolean channelExisted;
    private ActivityRepository original;
    private DomActivityAndroidTest.FeedRepository repository;
    private final List<ActivityEntry> fixtures = new ArrayList<>();

    @Before public void prepare() {
        manager = context.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        channelExisted = manager.getNotificationChannel(ActivityNotifications.CHANNEL) != null;
        new com.subhub.app.settings.SettingsRepository(context).preferences().edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,
                        com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()).commit();
        original = ActivitySources.repository();
        repository = new DomActivityAndroidTest.FeedRepository();
        ActivitySources.install(repository);
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        ControllerPinManager.enterDomMode();
    }
    @After public void restore() {
        try {
            for (ActivityEntry entry : fixtures) ActivityNotifications.cancel(context, entry);
            if (!channelExisted) manager.deleteNotificationChannel(ActivityNotifications.CHANNEL);
        } finally { ActivitySources.install(original); }
    }

    @Test public void repeatedDeliveryKeepsOnePrivateNotificationAndAnImmutableExplicitDestination() throws Exception {
        assumeTrue("Actual guest notification permission and channel must allow native posting", ActivityNotifications.enabled(context));
        ActivityEntry first = fixture("native-notification-one");
        ActivityEntry second = fixture("native-notification-two");
        assertTrue(ActivityNotifications.show(context, first));
        assertTrue(ActivityNotifications.show(context, first));
        assertTrue(ActivityNotifications.show(context, second));
        StatusBarNotification[] posted = awaitFixtureCount(2);
        assertEquals(2, posted.length);
        Notification notification = null;
        for (StatusBarNotification item : posted) {
            assertEquals(1, item.getId());
            assertEquals(Notification.VISIBILITY_PRIVATE, item.getNotification().visibility);
            assertNotNull(item.getNotification().publicVersion);
            String publicCopy = item.getNotification().publicVersion.extras.toString();
            assertFalse(publicCopy.contains("Synthetic Alex"));
            assertFalse(publicCopy.contains("Chrome"));
            assertTrue((item.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
            if (item.getTag().endsWith(first.key)) notification = item.getNotification();
        }
        assertNotNull(notification);
        assertEquals(context.getString(com.subhub.app.R.string.app_name), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(context.getString(com.subhub.app.R.string.dom_activity_new_activity), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse(notification.extras.toString().contains("Synthetic Alex"));
        assertFalse(notification.extras.toString().contains("Chrome"));
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable());

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(DomActivityActivity.class.getName(), null, false);
        Activity opened = null;
        try {
            // A mutable PendingIntent would admit these replacement extras. Component and original key must survive.
            notification.contentIntent.send(context, 0, new Intent(context, MainActivity.class)
                    .putExtra(DomActivityActivity.EXTRA_ENTRY_KEY, "replaced-fixture-key"));
            opened = monitor.waitForActivityWithTimeout(8000);
            assertNotNull("Notification opens the protected Activity destination", opened);
            Activity actual = opened;
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (repository.locations.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield();
            assertEquals(List.of(first.key), repository.locations);
            instrumentation.runOnMainSync(() -> {
                assertEquals(DomActivityActivity.class, actual.getClass());
                assertEquals(DomActivityActivity.class.getName(), actual.getIntent().getComponent().getClassName());
                // The Activity consumes its focus extra, so immutable request identity is witnessed by its data URI.
                assertEquals(first.key, actual.getIntent().getData().getLastPathSegment());
            });
        } finally {
            if (opened != null) { Activity actual = opened; instrumentation.runOnMainSync(actual::finish); }
            instrumentation.removeMonitor(monitor);
        }
        ActivityNotifications.cancel(context, first);
        assertEquals(1, awaitFixtureCount(1).length);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void deniedPermissionGateReturnsFalseWithoutPostingToTheRealNotificationManager() {
        ActivityEntry entry = fixture("native-notification-denied");
        int originalGrant = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
        Context denied = new ContextWrapper(context) {
            @Override public int checkSelfPermission(String permission) {
                return Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
            }
        };
        assertFalse(ActivityNotifications.enabled(denied));
        assertFalse(ActivityNotifications.show(denied, entry));
        for (StatusBarNotification item : manager.getActiveNotifications()) assertFalse(item.getTag() != null && item.getTag().endsWith(entry.key));
        assertEquals("Permission gate fixture never changes the OS grant", originalGrant,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test public void readEntriesNeverCreateAnOsNotification() {
        ActivityEntry entry = fixture("native-notification-read");
        assertFalse(ActivityNotifications.show(context, entry.read()));
        for (StatusBarNotification item : manager.getActiveNotifications()) assertFalse(item.getTag() != null && item.getTag().endsWith(entry.key));
    }

    private ActivityEntry fixture(String key) {
        ActivityEntry entry = DomActivityAndroidTest.entry(key, ActivityEvent.Kind.APP_OPENED, true);
        fixtures.add(entry); return entry;
    }
    private StatusBarNotification[] awaitFixtureCount(int expected) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            List<StatusBarNotification> matched = new ArrayList<>();
            for (StatusBarNotification item : manager.getActiveNotifications()) {
                for (ActivityEntry entry : fixtures) {
                    String tag = "subhub.activity:" + Uri.encode(entry.personId.value()) + ":" + entry.key;
                    if (tag.equals(item.getTag())) matched.add(item);
                }
            }
            if (matched.size() == expected) return matched.toArray(new StatusBarNotification[0]);
            // Binder observations are polled to a deadline; no fixed delay claims OS delivery.
            Thread.yield();
        }
        throw new AssertionError("Expected " + expected + " settled synthetic Activity notifications");
    }
}
