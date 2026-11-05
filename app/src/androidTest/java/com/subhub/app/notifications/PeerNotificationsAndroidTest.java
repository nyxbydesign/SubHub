package com.subhub.app.notifications;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static com.subhub.app.notifications.NotificationPreferences.*;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real OS notices on an owned guest; preferences and cleanup are fixture-scoped. */
@RunWith(AndroidJUnit4.class)
public final class PeerNotificationsAndroidTest {
    private Context target, context;
    private NotificationManager manager;
    private String prefix;
    private final Set<String> stores = new HashSet<>(), scopes = new HashSet<>(), unrelated = new HashSet<>();
    private boolean channelExisted, createdChannel;
    @Before public void prepare() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = target.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        NotificationChannel channel = manager.getNotificationChannel(PeerNotifications.CHANNEL);
        channelExisted = channel != null;
        assumeTrue("Owned guest must allow app notifications", manager.areNotificationsEnabled());
        assumeTrue("Owned guest must grant POST_NOTIFICATIONS; fixture never changes the grant",
                Build.VERSION.SDK_INT < 33 || target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        assumeTrue("Keep existing user channel choices; relationship_changes must be absent or enabled",
                channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            // The real disable operation cancels this whole type. Require an empty owned type before exercising it.
            assumeTrue("Owned guest must have no existing Sub contract notices to preserve",
                    active.getTag() == null || !active.getTag().startsWith("subhub.optional:SUB:CONTRACT_CHANGED:"));
            unrelated.add(identity(active));
        }
        prefix = "peer-notification-os-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name); return target.getSharedPreferences(prefix + name, mode);
            }
        };
    }
    @After public void clean() {
        if (manager != null) {
            for (String scope : scopes) for (Role role : Role.values()) for (Event event : Event.values())
                OptionalNotificationDispatch.cancelExact(target, role, event,
                        "subhub.optional:" + key(role, event) + ":" + scope, 2140 + event.ordinal());
            for (StatusBarNotification active : manager.getActiveNotifications())
                if (owned(active)) manager.cancel(active.getTag(), active.getId());
            if (!scopes.isEmpty()) awaitCount(0);
            if (createdChannel) {
                boolean occupied = false;
                for (StatusBarNotification active : manager.getActiveNotifications())
                    if (!owned(active) && PeerNotifications.CHANNEL.equals(active.getNotification().getChannelId())) occupied = true;
                if (!occupied) manager.deleteNotificationChannel(PeerNotifications.CHANNEL);
            }
        }
        if (target != null) for (String store : stores) assertTrue(target.deleteSharedPreferences(store));
    }
    @Test public void realOsDeliveryPreservesRoleAndCopyRejectsReplayCancelsOnlyTypeAndRedactsDiscreet() {
        String forbidden = scope("forbidden-dom");
        for (Event event : new Event[]{Event.CONTRACT_CHANGED, Event.SESSION_STARTED, Event.SESSION_STOPPED})
            PeerNotifications.applied(context, Role.DOM, event, forbidden, 1);
        assertRemainsAbsent(forbidden);
        String subContract = scope("sub-contract"), subStart = scope("sub-start"), subStop = scope("sub-stop");
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, subContract, 1);
        createdChannel = !channelExisted && manager.getNotificationChannel(PeerNotifications.CHANNEL) != null;
        PeerNotifications.applied(context, Role.SUB, Event.SESSION_STARTED, subStart, 1);
        PeerNotifications.applied(context, Role.SUB, Event.SESSION_STOPPED, subStop, 1);
        assertEquals(3, awaitCount(3).size());
        assertCopy(await(subContract), Role.SUB, Event.CONTRACT_CHANGED, R.string.notification_contract_changed);
        assertCopy(await(subStart), Role.SUB, Event.SESSION_STARTED, R.string.notification_session_started);
        assertCopy(await(subStop), Role.SUB, Event.SESSION_STOPPED, R.string.notification_session_stopped);

        // Removing the original makes a replay visible even if Android would replace an existing tag silently.
        StatusBarNotification original = await(subContract);
        manager.cancel(original.getTag(), original.getId());
        awaitCount(2);
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, subContract, 1);
        assertEquals(2, awaitCount(2).size());
        assertRemainsAbsent(subContract);
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, subContract, 2);
        awaitCount(3);
        for (StatusBarNotification active : manager.getActiveNotifications())
            assumeTrue("Do not cancel a concurrently posted unrelated Sub contract notice",
                    active.getTag() == null || !active.getTag().startsWith("subhub.optional:SUB:CONTRACT_CHANGED:") || owned(active));
        assertTrue(NotificationPreferences.set(context, Role.SUB, Event.CONTRACT_CHANGED, false));
        awaitCount(2);
        assertNull(find(subContract));
        assertNotNull(find(subStart)); assertNotNull(find(subStop));
        assertUnrelatedRetained();
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, subContract, 3);
        assertRemainsAbsent(subContract);
        assertTrue(NotificationPreferences.set(context, Role.SUB, Event.CONTRACT_CHANGED, true));
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, subContract, 3);
        assertRemainsAbsent(subContract);

        // Isolated privacy data exercises production presentation without changing launcher aliases or other notices.
        assertTrue(context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("discreet", true).commit());
        String discreet = scope("discreet");
        PeerNotifications.applied(context, Role.SUB, Event.CONTRACT_CHANGED, discreet, 1);
        Notification redacted = await(discreet).getNotification();
        assertEquals(context.getString(R.string.privacy_discreet_name), redacted.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(context.getString(R.string.privacy_notification_body), redacted.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(R.drawable.ic_discreet_filter, redacted.getSmallIcon().getResId());
        assertEquals(Notification.VISIBILITY_SECRET, redacted.visibility);
        assertNull(redacted.publicVersion);
        assertFalse(redacted.extras.toString().contains(context.getString(R.string.notification_contract_changed)));
        assertUnrelatedRetained();
    }
    private void assertCopy(StatusBarNotification notice, Role role, Event event, int copy) {
        assertTrue(notice.getTag().startsWith("subhub.optional:" + key(role, event) + ":"));
        Notification notification = notice.getNotification();
        assertEquals(PeerNotifications.CHANNEL, notification.getChannelId());
        assertEquals(context.getString(copy), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(context.getString(event == Event.SESSION_STARTED ? R.string.notification_session_started_detail
                : event == Event.SESSION_STOPPED ? R.string.notification_session_stopped_detail : copy),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(R.drawable.ic_notification_subhub, notification.getSmallIcon().getResId());
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertNotNull(notification.contentIntent);
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable());
        assertTrue((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }
    private String scope(String suffix) { String value = prefix + suffix; scopes.add(value); return value; }
    private boolean owned(StatusBarNotification notice) {
        if (notice.getTag() == null) return false;
        for (String scope : scopes) if (notice.getTag().endsWith(":" + scope)) return true;
        return false;
    }
    private static String identity(StatusBarNotification notice) { return notice.getTag() + ":" + notice.getId(); }
    private void assertUnrelatedRetained() {
        Set<String> current = new HashSet<>();
        for (StatusBarNotification active : manager.getActiveNotifications()) if (!owned(active)) current.add(identity(active));
        assertTrue("Fixture never cancels unrelated active notifications", current.containsAll(unrelated));
    }
    private StatusBarNotification find(String scope) {
        for (StatusBarNotification active : manager.getActiveNotifications())
            if (active.getTag() != null && active.getTag().endsWith(":" + scope)) return active;
        return null;
    }
    private void assertRemainsAbsent(String scope) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 500;
        do {
            assertNull("Consumed sequence must not repost to Android", find(scope));
            android.os.SystemClock.sleep(10);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
    }
    private StatusBarNotification await(String scope) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do {
            StatusBarNotification found = find(scope); if (found != null) return found;
            Thread.yield();
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing fixture OS notification: " + scope);
    }
    private List<StatusBarNotification> awaitCount(int count) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do {
            List<StatusBarNotification> found = new ArrayList<>();
            for (StatusBarNotification active : manager.getActiveNotifications()) if (owned(active)) found.add(active);
            if (found.size() == count) return found;
            Thread.yield();
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected " + count + " fixture OS notices");
    }
}
