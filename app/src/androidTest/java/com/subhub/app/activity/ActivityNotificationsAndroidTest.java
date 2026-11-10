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
import android.content.SharedPreferences;
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
import com.subhub.app.notifications.NotificationPreferences;
import static com.subhub.app.notifications.NotificationPreferences.*;
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
    private Context notificationContext;
    private String prefix;
    private final java.util.Set<String> stores = new java.util.HashSet<>();

    @Before public void prepare() {
        manager = context.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        channelExisted = manager.getNotificationChannel(ActivityNotifications.CHANNEL) != null;
        prefix = "activity-notice-" + java.util.UUID.randomUUID() + "-";
        notificationContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name); return context.getSharedPreferences(prefix + name, mode);
            }
        };
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
            for (ActivityEntry entry : fixtures) ActivityNotifications.cancel(notificationContext, entry);
            if (!fixtures.isEmpty()) awaitFixtureCount(0);
            if (!channelExisted) {
                boolean occupied = false;
                for (StatusBarNotification active : manager.getActiveNotifications())
                    if (ActivityNotifications.CHANNEL.equals(active.getNotification().getChannelId())) occupied = true;
                if (!occupied) manager.deleteNotificationChannel(ActivityNotifications.CHANNEL);
            }
            for (String store : stores) context.deleteSharedPreferences(store);
        } finally { ActivitySources.install(original); }
    }

    @Test public void repeatedDeliveryKeepsOnePrivateNotificationAndAnImmutableExplicitDestination() throws Exception {
        assumeTrue("Actual guest notification permission and channel must allow native posting", ActivityNotifications.enabled(notificationContext));
        ActivityEntry first = fixture("native-notification-one");
        ActivityEntry second = fixture("native-notification-two");
        assertTrue(ActivityNotifications.show(notificationContext, first));
        assertTrue(ActivityNotifications.show(notificationContext, first));
        assertTrue(ActivityNotifications.show(notificationContext, second));
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
        assertEquals(first.personName + " · " + ActivityText.title(context, first), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(ActivityText.title(context, first), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue(notification.extras.toString().contains("Synthetic Alex"));
        assertTrue(notification.extras.toString().contains("Chrome"));
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
        ActivityNotifications.cancel(notificationContext, first);
        assertEquals(1, awaitFixtureCount(1).length);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void deniedPermissionGateReturnsFalseWithoutPostingToTheRealNotificationManager() {
        ActivityEntry entry = fixture("native-notification-denied");
        int originalGrant = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
        Context denied = new ContextWrapper(notificationContext) {
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
        assertFalse(ActivityNotifications.show(notificationContext, entry.read()));
        for (StatusBarNotification item : manager.getActiveNotifications()) assertFalse(item.getTag() != null && item.getTag().endsWith(entry.key));
    }

    @Test public void eachActivityKindUsesItsOwnGateCopyAndSelectiveCancellation() {
        assumeTrue("Actual guest permission and channel must allow posting", ActivityNotifications.enabled(notificationContext));
        requireNoUnrelatedActivityNotices();
        for (ActivityEvent.Kind kind : ActivityEvent.Kind.values()) {
            ActivityEntry entry = fixture(kind.name(), kind);
            Event type = activityEvent(kind);
            assertTrue(set(notificationContext, Role.DOM, type, false));
            assertEquals(ActivityNotifications.Delivery.SUPPRESSED, ActivityNotifications.deliver(notificationContext, entry));
            assertTrue(set(notificationContext, Role.DOM, type, true));
            assertEquals(ActivityNotifications.Delivery.POSTED, ActivityNotifications.deliver(notificationContext, entry));
            StatusBarNotification posted = await(entry);
            assertEquals(type.name(), posted.getNotification().extras.getString(ActivityNotifications.EXTRA_TYPE));
            assertEquals(ActivityText.detail(context, entry).isEmpty() ? ActivityText.title(context, entry) : ActivityText.detail(context, entry), body(posted));
        }
        assertEquals(5, awaitFixtureCount(5).length);
        ActivityEntry selected = fixtures.get(0);
        assertTrue(set(notificationContext, Role.DOM, activityEvent(selected.event.kind), false));
        assertEquals(4, awaitFixtureCount(4).length);
        assertNull(find(selected));
        for (int index = 1; index < fixtures.size(); index++) assertNotNull(find(fixtures.get(index)));
    }

    @Test public void discreetKindsCancelIndependentlyAndPrivacyRefreshCannotResurrectOrCrossCopy() {
        assumeTrue("Actual guest permission and channel must allow posting", ActivityNotifications.enabled(notificationContext));
        // refresh() touches every app notice; never exercise it on another operation's live notifications.
        assumeTrue("Privacy refresh fixture requires no unrelated active notices", manager.getActiveNotifications().length == 0);
        assertTrue(notificationContext.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("discreet", true).commit());
        ActivityEntry opened = fixture("discreet-open", ActivityEvent.Kind.APP_OPENED);
        ActivityEntry limit = fixture("discreet-limit", ActivityEvent.Kind.SHARED_LIMIT_REACHED);
        assertTrue(ActivityNotifications.show(notificationContext, opened));
        assertTrue(ActivityNotifications.show(notificationContext, limit));
        Notification oldOpened = await(opened).getNotification();
        for (StatusBarNotification notice : awaitFixtureCount(2)) {
            assertEquals(context.getString(com.subhub.app.R.string.privacy_notification_body), body(notice));
            assertEquals(Notification.VISIBILITY_SECRET, notice.getNotification().visibility);
            assertNotNull(notice.getNotification().extras.getString(ActivityNotifications.EXTRA_TYPE));
        }
        com.subhub.app.privacy.PrivacyNotifications.refresh(notificationContext);
        assertEquals(2, awaitFixtureCount(2).length);
        assertTrue(set(notificationContext, Role.DOM, Event.TRACKED_APP_OPENED, false));
        assertEquals(1, awaitFixtureCount(1).length);
        assertNull(find(opened)); assertNotNull(find(limit));
        // Simulate an already-captured refresh snapshot after disable; dispatch must reject its old typed notice.
        com.subhub.app.notifications.OptionalNotificationPresentation.refreshActivity(notificationContext,
                tag(opened), 1, oldOpened);
        com.subhub.app.privacy.PrivacyNotifications.refresh(notificationContext);
        assertEquals(1, awaitFixtureCount(1).length);
        assertNull(find(opened));
        assertTrue(notificationContext.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("discreet", false).commit());
        com.subhub.app.privacy.PrivacyNotifications.refresh(notificationContext);
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!context.getString(com.subhub.app.R.string.notification_shared_limit_reached).equals(body(await(limit)))
                && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield();
        assertEquals(context.getString(com.subhub.app.R.string.notification_shared_limit_reached), body(await(limit)));
        assertNull(find(opened));
        assertTrue(set(notificationContext, Role.DOM, Event.TRACKED_APP_OPENED, true));
        assertTrue(ActivityNotifications.show(notificationContext, opened));
        com.subhub.app.privacy.PrivacyNotifications.refresh(notificationContext);
        assertEquals(2, awaitFixtureCount(2).length);
        assertEquals(context.getString(com.subhub.app.R.string.notification_tracked_app_opened), body(await(opened)));
        assertEquals(context.getString(com.subhub.app.R.string.notification_shared_limit_reached), body(await(limit)));
    }

    private ActivityEntry fixture(String key) {
        return fixture(key, ActivityEvent.Kind.APP_OPENED);
    }
    private ActivityEntry fixture(String key, ActivityEvent.Kind kind) {
        String id = prefix + key;
        boolean tribute = kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED || kind == ActivityEvent.Kind.TRIBUTE_PAID;
        boolean app = kind == ActivityEvent.Kind.APP_OPENED || kind == ActivityEvent.Kind.APP_LIMIT_REACHED;
        boolean limit = kind == ActivityEvent.Kind.APP_LIMIT_REACHED || kind == ActivityEvent.Kind.SHARED_LIMIT_REACHED;
        ActivityEvent event = new ActivityEvent(id, kind, System.currentTimeMillis(), app ? "com.example.synthetic" : "",
                tribute ? 125 : 0, tribute ? "EUR" : "", "", limit ? 40 : 0);
        ActivityEntry entry = new ActivityEntry(id, new com.subhub.app.people.PersonId(prefix + "person"),
                "Synthetic Alex", "Synthetic Android", event, event.observedAt + 100, true, app ? "Synthetic app" : "");
        fixtures.add(entry); return entry;
    }
    private static String tag(ActivityEntry entry) { return "subhub.activity:" + Uri.encode(entry.personId.value()) + ":" + entry.key; }
    private StatusBarNotification find(ActivityEntry entry) {
        for (StatusBarNotification active : manager.getActiveNotifications()) if (tag(entry).equals(active.getTag())) return active;
        return null;
    }
    private StatusBarNotification await(ActivityEntry entry) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do { StatusBarNotification found = find(entry); if (found != null) return found; Thread.yield(); }
        while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing fixture notification " + entry.key);
    }
    private static String body(StatusBarNotification notice) { return notice.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT).toString(); }
    private void requireNoUnrelatedActivityNotices() {
        for (StatusBarNotification active : manager.getActiveNotifications())
            assumeTrue("Selective cancel fixture must preserve unrelated Activity notices",
                    active.getTag() == null || !active.getTag().startsWith("subhub.activity:"));
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
