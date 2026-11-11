package com.subhub.app.stats;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;

/** Lane contracts only. Runtime permits/durable multi-store backend have their own integration tests. */
@RunWith(AndroidJUnit4.class)
public final class OwnerStatsRepositoryAndroidTest {
    private Store first, second;
    private Context app;
    @Before public void setup() {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        first = new Store(app); second = new Store(app);
    }
    @After public void close() {
        if (first != null) first.close();
        if (second != null) second.close();
    }

    @Test public void ownedCountersAndTokensNeverEnterTheDeviceOrAnotherLane() {
        Map<String, ?> physical = app.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getAll();
        StatsRepository.SessionStamp physicalStamp = StatsRepository.currentSessionStamp();
        StatsRepository a = StatsRepository.forOwner(first, new StatsRepository.SessionState());
        StatsRepository b = StatsRepository.forOwner(second, new StatsRepository.SessionState());
        long now = System.currentTimeMillis();
        StatsRepository.NativeSession session = session(now - 10_000);
        assertTrue(a.beginOwnedSession(session));
        StatsRepository.SessionStamp stamp = a.observedSessionStamp();
        assertNotNull(stamp); assertTrue(stamp.isCurrent()); assertFalse(stamp.isDeviceSession());
        assertTrue(a.ownsSessionStamp(stamp)); assertFalse(b.ownsSessionStamp(stamp));
        a.recordSubliminalImpression(); a.recordLimitedAppUsage(100); a.recordSourceTribute(stamp, 50, "EUR");
        assertEquals(1, a.load().getSubliminalImpressions()); assertEquals(100, a.load().getLimitedAppMillis());
        assertEquals(50, a.tributeCents("EUR", true));
        b.recordSourceTribute(stamp, 500, "EUR");
        assertEquals(0, b.load().getTributeEvents()); assertEquals(0, b.load().getSubliminalImpressions());
        assertEquals(physical, app.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getAll());
        assertSame(physicalStamp, StatsRepository.currentSessionStamp());
        assertTrue(a.finishOwnedSession(session.identity, now)); assertFalse(stamp.isCurrent());
        assertNull(a.observedSessionStamp()); assertEquals(1, a.getSessionHistory().size());
        assertTrue(a.finishOwnedSession(session.identity, now));
        assertFalse(a.beginOwnedSession(session)); assertEquals(1, a.getSessionHistory().size());
        assertEquals(1, a.load().getSessions()); assertTrue(first.checkpoints > 0);
        assertTrue(b.beginOwnedSession(session(now)));
        assertFalse(stamp.isCurrent()); assertNotSame(stamp, b.observedSessionStamp());
    }

    @Test public void persistentRowsCannotRecreateRuntimeAuthorityAndDeviceLifecycleIsRejected() {
        StatsRepository a = StatsRepository.forOwner(first, new StatsRepository.SessionState());
        StatsRepository.NativeSession session = session(System.currentTimeMillis() - 1000);
        assertTrue(a.beginOwnedSession(session));
        StatsRepository unbound = StatsRepository.forOwner(first, new StatsRepository.SessionState());
        assertNull(unbound.sessionStamp()); assertNull(unbound.observedSessionStamp());
        assertEquals(0, unbound.load().getCurrentSessionSeconds());
        assertFalse(unbound.beginOwnedSession(session));
        assertThrows(SecurityException.class, unbound::startSession);
        assertThrows(SecurityException.class, () -> unbound.ensureNativeSession(session));
        assertThrows(SecurityException.class, unbound::endSession);
    }

    @Test public void deniedWriteCannotConsumeTrackIdsOrChangeObservedCounters() {
        StatsRepository a = StatsRepository.forOwner(first, new StatsRepository.SessionState());
        assertTrue(a.beginOwnedSession(session(System.currentTimeMillis())));
        first.admitted = false;
        assertThrows(SecurityException.class, () -> a.recordTrackIds(Collections.singletonList(7), null));
        assertEquals(0, a.load().getTotalBlocks());
        first.admitted = true;
        assertEquals(1, a.recordTrackIds(Collections.singletonList(7), null));
        assertEquals(1, a.load().getTotalBlocks());
    }

    @Test public void editorCountersRemainOwnedWhenNoSessionRuns() {
        StatsRepository a = StatsRepository.forOwner(first, new StatsRepository.SessionState());
        a.incrementCensorStyleChanges(); a.recordCensorStyleTried("blur"); a.setCustomPhrasesCount(12);
        assertEquals(1, a.load().getCensorStyleChanges()); assertEquals(12, a.load().getCustomPhrases());
        assertTrue(a.load().getCensorStylesTried().contains("blur")); assertNull(a.observedSessionStamp());
        StatsRepository b = StatsRepository.forOwner(second, new StatsRepository.SessionState());
        assertEquals(0, b.load().getCustomPhrases());
    }

    private static StatsRepository.NativeSession session(long at) {
        return new StatsRepository.NativeSession(UUID.randomUUID().toString(), at, "UTC");
    }
    private static final class Store implements StatsRepository.OwnerStorage {
        final Context app; final String name; final SharedPreferences actual, guarded;
        boolean admitted = true; int checkpoints;
        Store(Context app) {
            this.app = app; name = "owner_stats_fixture_" + UUID.randomUUID(); actual = app.getSharedPreferences(name, 0);
            guarded = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return method.invoke(actual, args);
                        requireWrite(); SharedPreferences.Editor edit = actual.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, values) -> {
                                    if (operation.getName().equals("commit") || operation.getName().equals("apply")) {
                                        requireWrite(); boolean committed = edit.commit();
                                        if (!committed) throw new IllegalStateException("Fixture commit failed");
                                        return operation.getName().equals("commit") ? Boolean.TRUE : null;
                                    }
                                    Object value = operation.invoke(edit, values);
                                    return value == edit ? editor : value;
                                });
                    });
        }
        @Override public SharedPreferences counters() { return guarded; }
        @Override public void requireWrite() { if (!admitted) throw new SecurityException("No exact owner admission"); }
        @Override public void requireSessionWrite() { requireWrite(); }
        @Override public void afterCommit(Runnable publication) { publication.run(); }
        @Override public void checkpoint(Map<String, ?> snapshot, long observedAt, boolean durations) { requireWrite(); checkpoints++; }
        void close() { admitted = true; actual.edit().clear().commit(); app.deleteSharedPreferences(name); }
    }
}
