package com.subhub.app.notifications;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static com.subhub.app.notifications.NotificationPreferences.*;
import android.Manifest;
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
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.activity.ActivityNotifications;
import com.subhub.app.people.PersonId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Pauses a real optional dispatch after its enabled read, then races disable against the OS post. */
@RunWith(AndroidJUnit4.class)
public final class NotificationDeliveryRaceAndroidTest {
    private Context target, context;
    private NotificationManager manager;
    private String prefix, blockedKey;
    private final Set<String> stores = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Boolean> channels = new HashMap<>();
    private final CountDownLatch read = new CountDownLatch(1), releaseRead = new CountDownLatch(1),
            commit = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicReference<Thread> poster = new AtomicReference<>(), disabler = new AtomicReference<>();
    @Before public void prepare() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = target.getSystemService(NotificationManager.class); assertNotNull(manager);
        assumeTrue("Owned guest must allow notifications", manager.areNotificationsEnabled());
        assumeTrue("Root owns grant/restore; fixture never changes permission", Build.VERSION.SDK_INT < 33
                || target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        prefix = "notification-race-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                stores.add(prefix + name);
                SharedPreferences saved = target.getSharedPreferences(prefix + name, mode);
                if (!STORE.equals(name)) return saved;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit());
                    Object value;
                    try { value = method.invoke(saved, args); }
                    catch (InvocationTargetException failed) { throw failed.getCause(); }
                    if (method.getName().equals("getBoolean") && blockedKey.equals(args[0])
                            && Thread.currentThread() == poster.get() && blocked.compareAndSet(false, true)) {
                        read.countDown(); assertTrue("Release enabled-read fixture", releaseRead.await(5, TimeUnit.SECONDS));
                    }
                    return value;
                });
            }
        };
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved) {
        AtomicBoolean disabling = new AtomicBoolean();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            if (method.getName().equals("putBoolean") && blockedKey.equals(args[0]) && Boolean.FALSE.equals(args[1])) disabling.set(true);
            if (method.getName().equals("commit") && disabling.get() && Thread.currentThread() == disabler.get()) {
                commit.countDown(); assertTrue("Release disable commit fixture", releaseCommit.await(5, TimeUnit.SECONDS));
            }
            Object value;
            try { value = method.invoke(saved, args); }
            catch (InvocationTargetException failed) { throw failed.getCause(); }
            return value == saved ? proxy : value;
        });
    }
    @After public void clean() {
        releaseRead.countDown(); releaseCommit.countDown();
        if (manager != null && prefix != null) {
            OptionalNotificationDispatch.cancelExact(target, Role.DOM, Event.CONTRACT_CHANGED,
                    "subhub.optional:DOM:CONTRACT_CHANGED:" + prefix + "peer", 2140 + Event.CONTRACT_CHANGED.ordinal());
            OptionalNotificationDispatch.cancelExact(target, Role.DOM, Event.ACTIVITY,
                    "subhub.activity:" + android.net.Uri.encode(prefix + "person") + ":" + prefix + "entry", 1);
            OptionalNotificationDispatch.cancelExact(target, Role.DOM, Event.TASK_REVIEW,
                    "tasks:" + prefix + "scope:" + prefix + "effect", 1);
            for (StatusBarNotification active : manager.getActiveNotifications())
                if (owned(active)) manager.cancel(active.getTag(), active.getId());
            for (Map.Entry<String, Boolean> channel : channels.entrySet()) if (!channel.getValue()) {
                boolean occupied = false;
                for (StatusBarNotification active : manager.getActiveNotifications())
                    if (!owned(active) && channel.getKey().equals(active.getNotification().getChannelId())) occupied = true;
                if (!occupied) manager.deleteNotificationChannel(channel.getKey());
            }
        }
        if (target != null) for (String name : stores) target.deleteSharedPreferences(name);
    }
    @Test public void peerDisableWaitsForAdmittedPostAndCancelsItBeforeReturning() throws Exception {
        requireType(PeerNotifications.CHANNEL, "subhub.optional:DOM:CONTRACT_CHANGED:");
        blockedKey = key(Role.DOM, Event.CONTRACT_CHANGED);
        race(Event.CONTRACT_CHANGED, () -> PeerNotifications.applied(context, Role.DOM, Event.CONTRACT_CHANGED, prefix + "peer", 1),
                R.string.notification_contract_changed);
    }
    @Test public void activityDisableWaitsForAdmittedPostAndCancelsItBeforeReturning() throws Exception {
        requireType(ActivityNotifications.CHANNEL, "subhub.activity:");
        blockedKey = key(Role.DOM, Event.ACTIVITY);
        ActivityEvent event = new ActivityEvent(prefix + "event", ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis(), "com.example.synthetic", 0, "", "", 0);
        ActivityEntry entry = new ActivityEntry(prefix + "entry", new PersonId(prefix + "person"), "Synthetic", "", event,
                System.currentTimeMillis(), true, "Synthetic app");
        race(Event.ACTIVITY, () -> assertTrue(ActivityNotifications.show(context, entry)), R.string.dom_activity_new_activity);
    }
    @Test public void taskReviewDisableWaitsForAdmittedPostAndCancelsItBeforeReturning() throws Exception {
        requireType("tasks", "tasks:");
        blockedKey = key(Role.DOM, Event.TASK_REVIEW);
        java.lang.reflect.Constructor<com.subhub.app.tasks.TaskRuntime> runtimeConstructor =
                com.subhub.app.tasks.TaskRuntime.class.getDeclaredConstructor(Context.class);
        runtimeConstructor.setAccessible(true);
        com.subhub.app.tasks.TaskRuntime runtime = runtimeConstructor.newInstance(context);
        try {
            java.lang.reflect.Constructor<com.subhub.app.remote.RemoteStore.Peer> peerConstructor =
                    com.subhub.app.remote.RemoteStore.Peer.class.getDeclaredConstructor(String.class, String.class, String.class,
                            com.subhub.app.remote.RemoteProtocol.Role.class, com.subhub.app.remote.RemoteStore.State.class, long.class, String.class);
            peerConstructor.setAccessible(true);
            com.subhub.app.remote.RemoteStore.Peer peer = peerConstructor.newInstance(
                    String.join("", Collections.nCopies(64, "a")), prefix + "generation", "Synthetic Sub",
                    com.subhub.app.remote.RemoteProtocol.Role.DOM, com.subhub.app.remote.RemoteStore.State.ACTIVE, 0L, "");
            java.lang.reflect.Constructor<com.subhub.app.tasks.TaskRepository.Target> targetConstructor =
                    com.subhub.app.tasks.TaskRepository.Target.class.getDeclaredConstructor(String.class, com.subhub.app.remote.RemoteStore.Peer.class);
            targetConstructor.setAccessible(true);
            com.subhub.app.tasks.TaskRepository.Target selected = targetConstructor.newInstance(prefix + "scope", peer);
            com.subhub.app.tasks.TaskAction action = new com.subhub.app.tasks.TaskAction(prefix + "action", "synthetic-task",
                    com.subhub.app.tasks.TaskAction.Kind.SUBMIT, java.time.LocalDate.now(), System.currentTimeMillis(),
                    "Synthetic completion", "", Collections.emptyList(), null);
            java.lang.reflect.Constructor<com.subhub.app.tasks.TaskStore.Effect> effectConstructor =
                    com.subhub.app.tasks.TaskStore.Effect.class.getDeclaredConstructor(String.class, String.class, com.subhub.app.tasks.TaskAction.class);
            effectConstructor.setAccessible(true);
            com.subhub.app.tasks.TaskStore.Effect effect = effectConstructor.newInstance(prefix + "effect", "notification", action);
            java.lang.reflect.Method notify = com.subhub.app.tasks.TaskRuntime.class.getDeclaredMethod("notifyDom",
                    com.subhub.app.tasks.TaskRepository.Target.class, com.subhub.app.tasks.TaskStore.Effect.class);
            notify.setAccessible(true);
            race(Event.TASK_REVIEW, () -> {
                try { assertEquals(Boolean.TRUE, notify.invoke(runtime, selected, effect)); }
                catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
            }, R.string.tasks_review_notification);
        } finally {
            // This fixture runtime never installs its worker or opens its SQLite helper.
            java.lang.reflect.Field executor = com.subhub.app.tasks.TaskRuntime.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ScheduledExecutorService) executor.get(runtime)).shutdownNow();
            runtime.store.close();
        }
    }
    private void race(Event event, Runnable delivery, int copy) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> posted = workers.submit(() -> { poster.set(Thread.currentThread()); delivery.run(); });
            assertTrue("Delivery reached the enabled read", read.await(5, TimeUnit.SECONDS));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<Boolean> disabled = workers.submit(() -> {
                disabler.set(Thread.currentThread()); attempted.countDown();
                return NotificationPreferences.set(context, Role.DOM, event, false);
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (disabler.get().getState() != Thread.State.BLOCKED && !disabled.isDone()
                    && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield();
            assertEquals("Disable must wait for the admitted delivery lock", Thread.State.BLOCKED, disabler.get().getState());
            releaseRead.countDown();
            assertTrue("Disable acquired the lock after delivery completed", commit.await(5, TimeUnit.SECONDS));
            posted.get(5, TimeUnit.SECONDS);
            StatusBarNotification notice = awaitNotice();
            assertEquals(context.getString(copy), notice.getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString());
            releaseCommit.countDown();
            assertTrue(disabled.get(5, TimeUnit.SECONDS));
            awaitAbsent();
            assertFalse(NotificationPreferences.enabled(context, Role.DOM, event));
        } finally {
            releaseRead.countDown(); releaseCommit.countDown(); workers.shutdownNow();
            assertTrue("No fixture delivery worker retained", workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    private void requireType(String channelId, String cancelPrefix) {
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        channels.put(channelId, channel != null);
        assumeTrue("Preserve disabled channel choice", channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        for (StatusBarNotification active : manager.getActiveNotifications())
            assumeTrue("No unrelated notice may share the fixture cancellation type", active.getTag() == null || !active.getTag().startsWith(cancelPrefix));
    }
    private boolean owned(StatusBarNotification notice) { return notice.getTag() != null && notice.getTag().contains(prefix); }
    private StatusBarNotification find() {
        for (StatusBarNotification active : manager.getActiveNotifications()) if (owned(active)) return active;
        return null;
    }
    private StatusBarNotification awaitNotice() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do { StatusBarNotification result = find(); if (result != null) return result; Thread.yield(); }
        while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Admitted delivery was not observed in Android");
    }
    private void awaitAbsent() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do { if (find() == null) return; Thread.yield(); } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Disabled notification remained after cancel");
    }
}
