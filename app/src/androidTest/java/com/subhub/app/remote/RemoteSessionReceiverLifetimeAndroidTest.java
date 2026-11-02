package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.BootReceiver;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.appmode.SessionReceiverLifetime;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.commitment.CommitmentExpiryReceiver;
import com.subhub.app.penance.PaidPauseExpiryReceiver;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Framework ordered broadcasts exercise real PendingResult ownership; no direct receiver invocation. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionReceiverLifetimeAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static ExecutorService alarmWorker() throws Exception {
        Field field = SessionExpiryAlarms.class.getDeclaredField("WORKER"); field.setAccessible(true);
        return (ExecutorService) field.get(null);
    }
    private static int active() throws Exception {
        Field field = SessionReceiverLifetime.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
        return ((AtomicInteger) field.get(null)).get();
    }
    private static int idleWaiters() throws Exception {
        Field lock = SessionExpiryAlarms.class.getDeclaredField("LOCK"); lock.setAccessible(true);
        Field waiters = SessionExpiryAlarms.class.getDeclaredField("IDLE_WAITERS"); waiters.setAccessible(true);
        synchronized (lock.get(null)) { return ((List<?>) waiters.get(null)).size(); }
    }
    private static void awaitActive(boolean expected) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while ((active() > 0) != expected && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
        assertEquals(expected, active() > 0);
    }
    private Intent manifest(Class<?> receiver) {
        if (receiver == BootReceiver.class)
            return new Intent(context, receiver).setAction("com.subhub.app.action.RECEIVER_LIFETIME_PROBE");
        boolean pact = receiver == CommitmentExpiryReceiver.class;
        return new Intent(context, receiver).setAction(pact ? "com.subhub.app.action.PACT_EXPIRED"
                : "com.subhub.app.action.PAID_PAUSE_EXPIRED")
                .setData(Uri.parse("subhub-expiry://" + (pact ? "pact" : "pause") + "/invalid"));
    }
    private void ordered(Intent intent, CountDownLatch done, AtomicInteger completions) {
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent delivered) {
                completions.incrementAndGet(); done.countDown();
            }
        }, new Handler(Looper.getMainLooper()), android.app.Activity.RESULT_OK, null, null);
    }
    @Test public void actualManifestReceiversRetainOrderedCompletionUntilTheAlarmQueueFinishes() throws Exception {
        for (Class<?> receiver : new Class<?>[]{BootReceiver.class, CommitmentExpiryReceiver.class, PaidPauseExpiryReceiver.class}) {
            awaitActive(false); SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            AtomicInteger completions = new AtomicInteger();
            Future<?> blocked = alarmWorker().submit(() -> {
                entered.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); ordered(manifest(receiver), done, completions);
                awaitActive(true); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertFalse("Ordered result escaped queued work: " + receiver, done.await(150, TimeUnit.MILLISECONDS));
                assertFalse(blocked.isDone()); release.countDown(); blocked.get(5, TimeUnit.SECONDS);
                assertTrue(done.await(5, TimeUnit.SECONDS)); awaitActive(false); assertEquals(1, completions.get());
            } finally { release.countDown(); blocked.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test public void actualManifestReceiverFinishesAtItsBoundedDeadlineEvenWhenTheQueueIsStalled() throws Exception {
        awaitActive(false); SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        Future<?> blocked = alarmWorker().submit(() -> {
            entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); long started = android.os.SystemClock.elapsedRealtime();
            ordered(manifest(BootReceiver.class), done, completions); awaitActive(true);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            assertTrue("Premature deadline: " + elapsed, elapsed >= 7000); assertTrue("Unbounded receiver: " + elapsed, elapsed < 10500);
            assertFalse("Queue unexpectedly finished", blocked.isDone()); awaitActive(false);
            assertEquals("Timed out receiver retained its queue waiter", 0, idleWaiters());
            release.countDown(); blocked.get(5, TimeUnit.SECONDS); SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
            assertEquals(1, completions.get());
        } finally { release.countDown(); blocked.get(5, TimeUnit.SECONDS); }
    }
    @Test public void actualFrameworkFailureCompletionFinishesOnce() throws Exception {
        awaitActive(false); String action = "com.subhub.app.test.receiver." + UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1); AtomicInteger completions = new AtomicInteger();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                SessionReceiverLifetime.Work work = SessionReceiverLifetime.begin(this);
                work.failed(); work.afterQueues(); work.failed();
            }
        };
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            ordered(new Intent(action).setPackage(context.getPackageName()), done, completions);
            assertTrue(done.await(5, TimeUnit.SECONDS)); awaitActive(false); assertEquals(1, completions.get());
        } finally { context.unregisterReceiver(receiver); }
    }
    @Test public void stalledAlarmQueueBoundsWaitersAndReusesCancelledCapacity() throws Exception {
        SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        Future<?> blocked = alarmWorker().submit(() -> {
            entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 4096; index++) {
                CompletableFuture<Void> waiter = SessionExpiryAlarms.whenIdle();
                if (index < 32) { assertFalse(waiter.isDone()); pending.add(waiter); }
                else assertTrue("Overflow must fail immediately", waiter.isCompletedExceptionally());
            }
            assertEquals(32, idleWaiters());
            for (CompletableFuture<Void> waiter : pending) waiter.cancel(false);
            pending.clear();
            for (int index = 0; index < 32; index++) {
                CompletableFuture<Void> waiter = SessionExpiryAlarms.whenIdle();
                assertFalse(waiter.isDone()); pending.add(waiter);
            }
            assertEquals(32, idleWaiters()); release.countDown(); blocked.get(5, TimeUnit.SECONDS);
            for (CompletableFuture<Void> waiter : pending) waiter.get(5, TimeUnit.SECONDS);
            assertEquals(0, idleWaiters());
        } finally {
            for (CompletableFuture<Void> waiter : pending) waiter.cancel(false);
            release.countDown(); blocked.get(5, TimeUnit.SECONDS);
        }
    }
    @Test public void sharedBarrierFollowsAProducerQueuedAfterTheFirstWaiter() throws Exception {
        SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
        String prefix = "receiver-barrier-" + UUID.randomUUID() + "-", identity = UUID.randomUUID().toString();
        CountDownLatch workerEntered = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        CountDownLatch alarmEntered = new CountDownLatch(1), releaseAlarm = new CountDownLatch(1);
        Context isolated = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return context.getSharedPreferences(prefix + name, mode);
            }
            @Override public Object getSystemService(String name) {
                assertFalse("Android service queried under Runtime", Thread.holdsLock(RuntimeSessionIdentity.LOCK));
                if (Context.ALARM_SERVICE.equals(name)) {
                    alarmEntered.countDown();
                    try { assertTrue(releaseAlarm.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                return super.getSystemService(name);
            }
        };
        SharedPreferences prefs = isolated.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        Future<?> blocked = alarmWorker().submit(() -> {
            workerEntered.countDown();
            try { assertTrue(releaseWorker.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> first = SessionExpiryAlarms.whenIdle();
            synchronized (RuntimeSessionIdentity.LOCK) {
                assertTrue(prefs.edit().putString("commitment_session_identity", identity).commit());
                SessionExpiryAlarms.update(isolated, SessionExpiryAlarms.Kind.PACT, "", identity,
                        android.os.SystemClock.elapsedRealtime() + 3600000);
            }
            CompletableFuture<Void> second = SessionExpiryAlarms.whenIdle();
            releaseWorker.countDown(); blocked.get(5, TimeUnit.SECONDS);
            assertTrue(alarmEntered.await(5, TimeUnit.SECONDS));
            assertFalse("First waiter escaped later drain", first.isDone());
            assertFalse("Second waiter escaped its producer", second.isDone());
            releaseAlarm.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertNotNull(android.app.PendingIntent.getBroadcast(context, 8317,
                    SessionExpiryAlarms.intent(context, SessionExpiryAlarms.Kind.PACT, identity),
                    android.app.PendingIntent.FLAG_NO_CREATE | android.app.PendingIntent.FLAG_IMMUTABLE));
        } finally {
            releaseWorker.countDown(); releaseAlarm.countDown(); blocked.get(5, TimeUnit.SECONDS);
            synchronized (RuntimeSessionIdentity.LOCK) {
                assertTrue(prefs.edit().clear().commit());
                SessionExpiryAlarms.update(isolated, SessionExpiryAlarms.Kind.PACT, identity, "", 0L);
            }
            SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
            context.deleteSharedPreferences(prefix + SettingsRepository.PREFERENCES_NAME);
        }
    }
}
