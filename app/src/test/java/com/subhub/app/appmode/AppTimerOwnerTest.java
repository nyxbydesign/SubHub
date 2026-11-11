package com.subhub.app.appmode;

import static org.junit.Assert.*;
import com.subhub.app.configuration.DraftPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Explicit dependency tests: no Context or device repository is supplied to the owner path. */
public final class AppTimerOwnerTest {
    @Test public void ownerConfigurationCanBeEditedWithoutDevicePolicyOrRuntimeWrites() {
        DraftPreferences config = new DraftPreferences(); Usage usage = new Usage();
        AppTimerManager timers = AppTimerManager.forOwner(config, usage);
        timers.saveSettings(true, 10, true, 30);
        assertTrue(timers.loadSettings().perAppEnabled); assertEquals(10, timers.loadSettings().perAppMinutes);
        assertTrue(timers.loadSettings().totalEnabled); assertEquals(30, timers.loadSettings().totalMinutes);
        timers.updateAllowances(Map.of("com.example.one", 7));
        assertEquals(7, timers.allowanceMinutes("com.example.one")); assertEquals(0, usage.writes);
        timers.disableBudget(true); assertFalse(timers.loadSettings().perAppEnabled);
    }
    @Test public void limitChecksUseOwnedMeasuredUsageWithoutRemoteExtraTime() {
        DraftPreferences config = new DraftPreferences(); Usage usage = new Usage();
        AppTimerManager timers = AppTimerManager.forOwner(config, usage);
        timers.saveSettings(true, 1, true, 5);
        usage.app = 60_000; usage.total = 60_000;
        assertEquals(AppTimerManager.LimitStatus.PER_APP, timers.limitStatus("com.example.one", Set.of("com.example.one"), 1));
        usage.app = 1; usage.total = 300_000;
        assertEquals(AppTimerManager.LimitStatus.COMBINED, timers.limitStatus("com.example.one", Set.of("com.example.one"), 2));
        assertEquals(AppTimerManager.LimitStatus.NONE, timers.limitStatus("com.example.other", Set.of("com.example.one"), 3));
    }
    @Test public void acceptedUsageIsRecordedByExactlyTheSuppliedOwnerProvider() {
        DraftPreferences config = new DraftPreferences(); Usage usage = new Usage();
        AppTimerManager timers = AppTimerManager.forOwner(config, usage); timers.saveSettings(true, 10, false, 30);
        timers.recordUsage("com.example.one", 100, Set.of("com.example.one"), 123);
        assertEquals(1, usage.writes); assertEquals(100, usage.app); assertEquals(123, usage.lastTime);
        timers.recordUsage("com.example.other", 100, Set.of("com.example.one"), 124);
        timers.recordUsage("com.example.one", 0, Set.of("com.example.one"), 125);
        assertEquals(1, usage.writes);
        timers.checkpointHistory(126); assertEquals(126, usage.lastCheckpoint);
    }
    @Test public void unreadableOrUncertainOwnerDataIsNotConvertedToZeroOrSuccess() {
        DraftPreferences config = new DraftPreferences(); Usage usage = new Usage();
        AppTimerManager timers = AppTimerManager.forOwner(config, usage); timers.saveSettings(true, 10, false, 30);
        usage.unavailable = true;
        assertThrows(IllegalStateException.class, () -> timers.snapshot("com.example.one", 1));
        assertThrows(IllegalStateException.class, () -> timers.recordUsage("com.example.one", 1, Set.of("com.example.one"), 1));
        assertEquals(0, usage.writes);
        assertThrows(IllegalArgumentException.class, () -> AppTimerManager.UsageSnapshot.measured(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> AppTimerManager.UsageSnapshot.measured(2, 1));
    }
    @Test public void incompleteAndInconsistentAggregatesFailRatherThanInventRows() {
        DraftPreferences config = new DraftPreferences(); Usage usage = new Usage();
        AppTimerManager timers = AppTimerManager.forOwner(config, usage);
        usage.missing = true;
        assertThrows(IllegalStateException.class, () -> timers.snapshots(Set.of("com.example.one"), 1));
        usage.missing = false; usage.inconsistent = true;
        assertThrows(IllegalStateException.class, () -> timers.snapshots(Set.of("com.example.one"), 1));
        usage.inconsistent = false;
        Map<String, AppTimerManager.UsageSnapshot> snapshot = timers.snapshots(Set.of("com.example.one"), 1);
        assertEquals(Set.of("", "com.example.one"), snapshot.keySet());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
    @Test public void configurationOnlyEditorNeverPretendsUsageOrRuntimeIsAvailable() {
        DraftPreferences config = new DraftPreferences(); AppTimerManager timers = AppTimerManager.forConfiguration(config);
        timers.saveSettings(true, 7, true, 30); assertEquals(7, timers.loadSettings().perAppMinutes);
        timers.updateAllowances(Map.of("com.example.one", 4)); assertEquals(4, timers.allowanceMinutes("com.example.one"));
        assertThrows(AppTimerManager.UsageUnavailableException.class, () -> timers.snapshot("com.example.one", 1));
        assertThrows(AppTimerManager.UsageUnavailableException.class, () -> timers.snapshots(Set.of("com.example.one"), 1));
        assertThrows(AppTimerManager.UsageUnavailableException.class, () -> timers.limitStatus("com.example.one", Set.of("com.example.one"), 1));
        assertThrows(AppTimerManager.UsageUnavailableException.class, () -> timers.recordUsage("com.example.one", 1, Set.of("com.example.one"), 1));
        assertThrows(AppTimerManager.UsageUnavailableException.class, () -> timers.checkpointHistory(1));
        assertThrows(AppTimerManager.UsageUnavailableException.class, timers::clearUsageForTesting);
    }
    private static final class Usage implements AppTimerManager.OwnerUsage {
        long app, total, lastTime, lastCheckpoint; int writes;
        boolean unavailable, missing, inconsistent;
        @Override public AppTimerManager.UsageSnapshot snapshot(String name, long now) {
            if (unavailable) throw new IllegalStateException("Owned data unavailable");
            return AppTimerManager.UsageSnapshot.measured(name.isEmpty() ? 0 : app, total);
        }
        @Override public Map<String, AppTimerManager.UsageSnapshot> snapshots(Set<String> packages, long now) {
            Map<String, AppTimerManager.UsageSnapshot> result = new LinkedHashMap<>();
            result.put("", snapshot("", now));
            if (!missing) for (String name : packages) result.put(name,
                    AppTimerManager.UsageSnapshot.measured(app, inconsistent ? total + 1 : total));
            return result;
        }
        @Override public void recordUsage(String name, long elapsed, long now) {
            if (unavailable) throw new IllegalStateException("Owned write uncertain");
            writes++; app += elapsed; total += elapsed; lastTime = now;
        }
        @Override public void checkpointHistory(long now) { lastCheckpoint = now; }
    }
}
