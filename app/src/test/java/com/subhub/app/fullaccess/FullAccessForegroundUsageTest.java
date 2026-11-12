package com.subhub.app.fullaccess;

import static com.subhub.app.fullaccess.FullAccessForegroundUsage.Event.*;
import static org.junit.Assert.*;

import android.content.pm.ApplicationInfo;
import java.util.Map;
import org.junit.Test;

public final class FullAccessForegroundUsageTest {
    @Test public void hidesPreinstalledAndUpdatedSystemAppsOnly() {
        assertTrue(FullAccessAppsSource.isSystemApp(ApplicationInfo.FLAG_SYSTEM));
        assertTrue(FullAccessAppsSource.isSystemApp(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
        assertTrue(FullAccessAppsSource.isSystemApp(ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_DEBUGGABLE));
        assertFalse(FullAccessAppsSource.isSystemApp(ApplicationInfo.FLAG_DEBUGGABLE));
        assertFalse(FullAccessAppsSource.isSystemApp(0));
    }

    @Test public void countsOnlyConfirmedUnlockedForegroundAndSeparatesToday() {
        FullAccessForegroundUsage usage = new FullAccessForegroundUsage(10, 50);
        usage.event(RESUMED, 5, "locked", "LockScreen");
        usage.event(SCREEN_ON, 10, null, null);
        usage.event(UNLOCKED, 15, null, null);
        usage.event(RESUMED, 20, "reader", "ReaderActivity");
        usage.event(RESUMED, 45, "game", "GameActivity");
        usage.event(PAUSED, 49, "reader", "ReaderActivity");
        usage.event(LOCKED, 60, null, null);
        usage.event(RESUMED, 65, "locked", "LockScreen");
        usage.event(SCREEN_OFF, 70, null, null);
        Map<String, FullAccessForegroundUsage.Totals> result = usage.finish(100, false);

        assertFalse(result.containsKey("locked"));
        assertEquals(25, result.get("reader").weekMillis);
        assertEquals(0, result.get("reader").todayMillis);
        assertEquals(15, result.get("game").weekMillis);
        assertEquals(10, result.get("game").todayMillis);
        assertEquals(20, usage.observedFrom());
    }

    @Test public void unknownShutdownAndUnconfirmedTailAreExcluded() {
        FullAccessForegroundUsage usage = new FullAccessForegroundUsage(0, 0);
        usage.event(SCREEN_ON, 1, null, null);
        usage.event(UNLOCKED, 2, null, null);
        usage.event(RESUMED, 3, "stale", "Main");
        usage.event(DEVICE_STARTUP, 50, null, null);
        usage.event(SCREEN_ON, 51, null, null);
        usage.event(UNLOCKED, 52, null, null);
        usage.event(RESUMED, 53, "current", "Main");

        assertTrue(usage.finish(100, false).isEmpty());
        assertEquals(-1, usage.observedFrom());
    }

    @Test public void missingScreenAndKeyguardEvidenceDoesNotFabricateUsage() {
        FullAccessForegroundUsage usage = new FullAccessForegroundUsage(0, 0);
        usage.event(SCREEN_ON, 10, null, null);
        usage.event(RESUMED, 20, "app", "Main");
        usage.event(PAUSED, 30, "app", "Main");
        assertTrue(usage.finish(50, true).isEmpty());
    }
}
