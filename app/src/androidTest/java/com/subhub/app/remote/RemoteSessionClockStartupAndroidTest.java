package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.SubFeaturePolicy;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** The application initializes its boot observation before callbacks can enter authority locks. */
public class RemoteSessionClockStartupAndroidTest {
    @Test public void freshProcessStartupSuppliesBootIdentityToThePureLocalClock() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int actualBoot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        assertTrue(actualBoot >= 0);
        long before = SystemClock.elapsedRealtime();
        RemoteSessionClock.Sample clock;
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (RuntimeSessionIdentity.LOCK) { clock = RemoteSessionClock.local(); }
        }
        assertEquals(actualBoot, clock.boot); assertTrue(clock.wall > 0);
        assertTrue(clock.elapsed >= before); assertTrue(clock.elapsed <= SystemClock.elapsedRealtime());
    }
    @Test public void optionalDeniedSeedPreservesUnknownBootWhileRequiredObservationStillFails() throws Exception {
        Field cache = RemoteSessionClock.class.getDeclaredField("observedBoot"); cache.setAccessible(true); int prior = cache.getInt(null);
        Context denied = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public ContentResolver getContentResolver() { throw new SecurityException("Synthetic provider refusal"); }
        };
        try {
            cache.setInt(null, -1); RemoteSessionClock.observeAtStartup(denied);
            assertEquals(-1, RemoteSessionClock.local().boot);
            assertThrows(SecurityException.class, () -> RemoteSessionClock.observe(denied));
            assertEquals(-1, RemoteSessionClock.local().boot);
        } finally { cache.setInt(null, prior); }
    }
    @Test public void optionalSeedDoesNotQueryAndroidWhileAuthorityLocksAreHeld() throws Exception {
        Field cache = RemoteSessionClock.class.getDeclaredField("observedBoot"); cache.setAccessible(true); int prior = cache.getInt(null);
        AtomicInteger queries = new AtomicInteger();
        Context denied = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public ContentResolver getContentResolver() { queries.incrementAndGet(); throw new AssertionError("Locked Android query"); }
        };
        try {
            cache.setInt(null, -1);
            synchronized (SubFeaturePolicy.LOCK) { RemoteSessionClock.observeAtStartup(denied); }
            synchronized (RuntimeSessionIdentity.LOCK) { RemoteSessionClock.observeAtStartup(denied); }
            assertEquals(0, queries.get()); assertEquals(-1, RemoteSessionClock.local().boot);
        } finally { cache.setInt(null, prior); }
    }
}
