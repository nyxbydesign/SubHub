package com.subhub.app.internal;

import static org.junit.Assert.*;
import com.subhub.app.runtime.*;
import com.subhub.app.stats.OwnerObservation;
import android.content.SharedPreferences;
import java.util.*;
import java.security.SecureRandom;
import org.junit.Test;

/** Actual typed transaction/ACK semantics with a deliberately failing memory backend. */
public final class InternalSessionClockPersistenceTest {
    @Test public void cleanMutationRejectionRollsBackWholeClockAndKeepsStoreAvailable() {
        Fixture f = new Fixture(); f.begin(); InternalRuntimeRecord before = f.disk.value;
        assertThrows(IllegalStateException.class, () -> f.preferences.transaction(() -> {
            f.write(f.pause()); throw new IllegalStateException("later validation failed");
        }));
        assertEquals(before, f.disk.value); assertTrue(f.preferences.isAvailable());
        assertEquals(0, f.read().pauseSequence); f.save(f.pause()); assertEquals(1, f.read().pauseSequence);
    }
    @Test public void knownNotCommittedNeverPublishesPauseAndFencesHandle() {
        Fixture f = new Fixture(); f.begin(); InternalRuntimeRecord before = f.disk.value;
        f.disk.outcome = InternalRuntimePreferences.Outcome.NOT_COMMITTED;
        InternalRuntimePreferences.PersistenceException error = assertThrows(InternalRuntimePreferences.PersistenceException.class, () -> f.save(f.pause()));
        assertEquals(InternalRuntimePreferences.Outcome.NOT_COMMITTED, error.outcome);
        assertEquals(before, f.disk.value); assertFalse(f.preferences.isAvailable());
        assertThrows(IllegalStateException.class, f::read);
    }
    @Test public void persistedUncertainPauseHasEvidenceButNoAckAndCannotReplayOnHandle() {
        Fixture f = new Fixture(); f.begin(); f.disk.persistBeforeOutcome = true;
        f.disk.outcome = InternalRuntimePreferences.Outcome.UNCERTAIN;
        assertThrows(InternalRuntimePreferences.PersistenceException.class, () -> f.save(f.pause()));
        InternalSessionClock.State saved = InternalSessionClock.decode(f.disk.value.namespaces().get(InternalSessionClock.NAMESPACE));
        assertEquals(1, saved.pauseSequence); assertEquals(500L, saved.simulatedTotals.get("EUR").longValue());
        assertFalse(f.preferences.isAvailable()); assertEquals(InternalSessionClock.Status.RECOVERY_REQUIRED, InternalSessionClock.observe(saved).status);
        assertThrows(IllegalStateException.class, () -> f.save(saved));
    }
    @Test public void cleanupClockAndCompanionLaneRollBackTogether() {
        Fixture f = new Fixture(); f.begin(); InternalRuntimeRecord before = f.disk.value;
        assertThrows(IllegalStateException.class, () -> f.preferences.transaction(() -> {
            f.write(InternalSessionClock.finish(f.read(), f.owner, new OwnerObservation(2000, "UTC")));
            f.preferences.preferences("statistics").edit().putBoolean("closed", true).apply();
            throw new IllegalStateException("companion closure failed");
        }));
        assertEquals(before, f.disk.value); assertEquals(InternalSessionClock.Phase.LIVE, f.read().phase);
        f.preferences.transaction(() -> {
            f.write(InternalSessionClock.finish(f.read(), f.owner, new OwnerObservation(2000, "UTC")));
            f.preferences.preferences("statistics").edit().putBoolean("closed", true).apply();
        });
        assertEquals(before.revision + 1, f.disk.value.revision);
        assertEquals(InternalSessionClock.Phase.STOPPED, f.read().phase);
        assertTrue(f.preferences.preferences("statistics").getBoolean("closed", false));
    }
    @Test public void dataReopenSeesOrphanButCannotMintFreshLiveClock() {
        Fixture f = new Fixture(); f.begin(); f.preferences.close();
        InternalRuntimePreferences reopened = new InternalRuntimePreferences(f.disk, Runnable::run, () -> {});
        InternalSessionClock.State orphan = InternalSessionClock.decode(reopened.preferences(InternalSessionClock.NAMESPACE).getAll());
        assertTrue(orphan.requiresRecovery()); assertFalse(InternalSessionClock.observe(orphan).protectionEligible);
        assertThrows(IllegalStateException.class, () -> InternalSessionClock.begin(orphan, f.owner, f.sample,
                new InternalSessionClock.Selection(3_600_000, 3_600_000, 3_600_000, false), new SecureRandom()));
        assertEquals(1, f.disk.value.revision);
    }
    private static final class Memory implements InternalRuntimePreferences.Backend {
        InternalRuntimeRecord value = new InternalRuntimeRecord(UUID.randomUUID().toString(), 0, Map.of());
        InternalRuntimePreferences.Outcome outcome = InternalRuntimePreferences.Outcome.COMMITTED;
        boolean persistBeforeOutcome;
        public InternalRuntimeRecord read() { return value; }
        public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) {
            if (!expected.equals(value)) return InternalRuntimePreferences.Outcome.CONFLICT;
            if (outcome == InternalRuntimePreferences.Outcome.COMMITTED || persistBeforeOutcome) value = next;
            return outcome;
        }
        public void close() { }
    }
    private static final class Fixture {
        final Memory disk = new Memory();
        final InternalRuntimePreferences preferences = new InternalRuntimePreferences(disk, Runnable::run, () -> {});
        final RuntimeLease.Identity owner = new RuntimeLease.Identity(RuntimeTarget.internal(InternalStorageScope.TARGET_ID), RuntimeOrigin.LOCAL_UI,
                "generation", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0);
        final InternalSessionClock.Sample sample = new InternalSessionClock.Sample(new OwnerObservation(1000, "UTC"), 100);
        void begin() { save(InternalSessionClock.begin(null, owner, sample,
                new InternalSessionClock.Selection(3_600_000, 3_600_000, 3_600_000, false), new SecureRandom())); }
        InternalSessionClock.State read() { return InternalSessionClock.decode(preferences.preferences(InternalSessionClock.NAMESPACE).getAll()); }
        InternalSessionClock.State pause() { return InternalSessionClock.pause(read(), owner, sample,
                new InternalSessionClock.Rules(0, true, true, true, 500, 15, "EUR"), 0, UUID.randomUUID().toString()); }
        void save(InternalSessionClock.State state) { preferences.transaction(() -> write(state)); }
        void write(InternalSessionClock.State state) {
            SharedPreferences.Editor editor = preferences.preferences(InternalSessionClock.NAMESPACE).edit().clear();
            for (Map.Entry<String,Object> item : InternalSessionClock.encode(state).entrySet()) {
                Object value = item.getValue(); String key = item.getKey();
                if (value instanceof String) editor.putString(key, (String)value);
                else if (value instanceof Long) editor.putLong(key, (Long)value);
                else if (value instanceof Integer) editor.putInt(key, (Integer)value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean)value);
                else { Set<String> copy = new TreeSet<>(); for (Object member : (Set<?>)value) copy.add((String)member); editor.putStringSet(key, copy); }
            }
            editor.apply();
        }
    }
}
