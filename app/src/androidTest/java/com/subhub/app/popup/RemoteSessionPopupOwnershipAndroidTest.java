package com.subhub.app.popup;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises manager dispatch/claims with rendering unacknowledged; no overlay permission is granted. */
public class RemoteSessionPopupOwnershipAndroidTest {
    private Context target, context;
    private String prefix;
    private PopupStormManager manager;
    private PopupRunOwnership ownership;
    private final Set<String> names = java.util.Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger acknowledgementReads = new AtomicInteger();
    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext(); prefix = "popup-owner-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                names.add(name); SharedPreferences saved = target.getSharedPreferences(prefix + name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBoolean") && args[0].equals(PopupStormSettings.K_ACK)) acknowledgementReads.incrementAndGet();
                    try { return method.invoke(saved, args); } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }
        };
        Constructor<PopupStormManager> constructor = PopupStormManager.class.getDeclaredConstructor(); constructor.setAccessible(true);
        manager = constructor.newInstance(); ownership = (PopupRunOwnership) field("ownership");
        replaceStats();
    }
    @After public void cleanup() {
        if (manager != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(manager::stop);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        for (String name : names) target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
    }
    private Object field(String name) throws Exception {
        Field field = PopupStormManager.class.getDeclaredField(name); field.setAccessible(true); return field.get(manager);
    }
    private long token(PopupStormManager.ServiceParticipation participation) throws Exception {
        assertNotNull(participation); Field token = participation.getClass().getDeclaredField("token"); token.setAccessible(true);
        return token.getLong(participation);
    }
    private void replaceStats() {
        synchronized (RuntimeSessionIdentity.LOCK) {
            assertTrue(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit()
                    .putString("active_session_identity", UUID.randomUUID().toString()).commit());
            RuntimeSessionIdentity.changed();
        }
    }
    @Test public void oldCaptureCleanupCannotStopNewSessionParticipation() throws Exception {
        String oldSignature = RuntimeSessionIdentity.componentSignature(context);
        PopupStormManager.ServiceParticipation first = manager.startOwned(context, oldSignature);
        replaceStats(); PopupStormManager.ServiceParticipation later = manager.startOwned(context);
        assertNotEquals(token(first), token(later));
        manager.stopOwnedService(first); assertTrue(ownership.isService(token(later)));
        assertNull(manager.startOwned(context, oldSignature)); assertTrue(ownership.isService(token(later)));
        assertFalse(manager.isRunning());
    }
    @Test public void unchangedSessionReevaluationDoesNotReloadPopupSettings() throws Exception {
        PopupStormManager.ServiceParticipation first = manager.startOwned(context);
        int reads = acknowledgementReads.get(); Object library = field("library");
        PopupStormManager.ServiceParticipation again = manager.startOwned(context);
        manager.syncServiceParticipation(context, true);
        assertEquals(token(first), token(again)); assertEquals(reads, acknowledgementReads.get());
        assertSame(library, field("library")); assertTrue(ownership.isService(token(first)));
    }
    @Test public void queuedOldStopRetainsTheReplacementClaim() throws Exception {
        PopupStormManager.ServiceParticipation first = manager.startOwned(context);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture main release timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        PopupStormManager.ServiceParticipation later;
        try {
            manager.stopOwnedService(first); replaceStats(); later = manager.startOwned(context);
        } finally { release.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(ownership.isService(token(later))); assertEquals(Boolean.TRUE, field("startRequested"));
    }
    @Test public void waitingForRenderingOwnershipDoesNotRetainRuntimeAndCannotAdoptAChangedScope() throws Exception {
        manager.startOwned(context); String expected = RuntimeSessionIdentity.componentSignature(context);
        AtomicReference<PopupStormManager.ServiceParticipation> late = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread claimant = new Thread(() -> {
            try { late.set(manager.startOwned(context, expected)); } catch (Throwable error) { failure.set(error); }
        });
        CountDownLatch runtimeAvailable = new CountDownLatch(1);
        Thread probe = new Thread(() -> { synchronized (RuntimeSessionIdentity.LOCK) { runtimeAvailable.countDown(); } });
        try {
            synchronized (ownership) {
                claimant.start(); long deadline = SystemClock.uptimeMillis() + 3000;
                while (claimant.getState() != Thread.State.BLOCKED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5);
                assertEquals(Thread.State.BLOCKED, claimant.getState()); probe.start();
                assertTrue("Runtime must remain available while rendering ownership is held", runtimeAvailable.await(3, TimeUnit.SECONDS));
                replaceStats();
            }
        } finally { claimant.join(4000); if (probe.getState() != Thread.State.NEW) probe.join(4000); }
        assertFalse(claimant.isAlive()); assertNull(failure.get()); assertNull(late.get());
        PopupStormManager.ServiceParticipation current = manager.startOwned(context); assertTrue(ownership.isService(token(current)));
    }
}
