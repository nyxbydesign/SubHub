package com.subhub.app.internal;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.penance.InternalPenanceRules;
import com.subhub.app.penance.OwnerPenanceLedger;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.DailyAppUsageStore;
import com.subhub.app.stats.OwnerStatisticsStore;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.OwnerObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real owner accounting against explicitly provisioned files, without replacing app singletons. */
@RunWith(AndroidJUnit4.class)
public final class OwnerAccountingAndroidTest {
    private Path root;
    private final List<Fixture> fixtures = new ArrayList<>();

    @Before public void setUp() throws Exception {
        // Instrumentation runs in the target process; the test APK cache may have another UID.
        // Only this freshly allocated child of the target cache is ever written or cleaned.
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path trusted = InternalStorageScope.canonicalTrustedParent(app.getCacheDir());
        root = Files.createTempDirectory(trusted, "owner-accounting-");
    }

    @After public void tearDown() throws Exception {
        Throwable problem = null;
        for (Fixture fixture : fixtures) try { fixture.close(); }
        catch (Throwable failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
        if (root != null) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
        } catch (Throwable failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
        if (problem != null) throw new AssertionError("Owned fixture cleanup failed", problem);
    }

    @Test public void countersLedgerAndUsageStayInExactOwnerAndNeverTouchDeviceLane() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, ?> physical = app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        StatsRepository.SessionStamp device = StatsRepository.currentSessionStamp();
        Fixture a = fresh(), b = fresh();
        long now = System.currentTimeMillis();
        // Even identical durable session facts are not authority to another process-local lane.
        StatsRepository.NativeSession session = session(now - 10_000, "UTC");
        a.begin(session); b.begin(session);
        a.configure("EUR", 100, 150, 1000, 2000, 1);
        b.configure("EUR", 100, 150, 1000, 2000, 1);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        assertTrue(a.stats().ownsSessionStamp(stamp)); assertFalse(b.stats().ownsSessionStamp(stamp));
        assertFalse(stamp.isDeviceSession());
        a.run(() -> a.owner.transaction(() -> {
            assertEquals(2, a.stats().recordTrackIds(List.of(7, 8, 7), null));
            a.stats().recordSubliminalImpression(); a.stats().recordPopupImpression();
        }));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 750, now));
        assertEquals(100, a.call(() -> a.ledger.recordSourceDetectionResult(1, now, stamp)).chargedCents);
        long bRevision = b.data.revision();
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                b.call(() -> b.ledger.recordSourceDetectionResult(1, now, stamp)).status);
        assertEquals(bRevision, b.data.revision());
        assertEquals(2, a.snapshot(now).statistics.getTotalBlocks());
        assertEquals(750, a.stats().load().getLimitedAppMillis());
        assertEquals(100, a.stats().tributeCents("EUR", true));
        assertEquals(1, a.stats().load().getSubliminalImpressions());
        assertEquals(1, a.stats().load().getPopupImpressions());
        assertEquals(0, b.stats().load().getTotalBlocks());
        assertEquals(0, b.stats().load().getLimitedAppMillis());
        assertTrue(b.ledger.events().isEmpty()); assertTrue(b.owner.appUsage().entries().isEmpty());
        assertEquals(physical, app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll());
        assertSame(device, StatsRepository.currentSessionStamp());
    }

    @Test public void writesRequireAdmissionAndTheOwnersAtomicTransaction() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis();
        a.begin(session(now - 1000, "UTC"));
        InternalRuntimeRecord before = a.data.snapshot();
        assertThrows(SecurityException.class, a.stats()::recordPopupImpression);
        a.run(() -> assertThrows(SecurityException.class, a.stats()::recordPopupImpression));
        a.allowed = false;
        assertThrows(SecurityException.class, () -> a.run(() -> a.owner.transaction(() -> { a.stats().recordPopupImpression(); })));
        assertThrows(SecurityException.class, () -> a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 50, now)));
        assertThrows(SecurityException.class, () -> a.run(() -> a.owner.close()));
        assertEquals(before, a.data.snapshot());
        a.allowed = true;
        a.run(() -> assertThrows(IllegalStateException.class, a.owner::close));
        assertEquals(before, a.data.snapshot());
        a.run(() -> a.owner.transaction(() -> assertEquals(1, a.stats().recordTrackIds(List.of(9), null))));
        a.run(() -> a.owner.transaction(() -> assertEquals(0, a.stats().recordTrackIds(List.of(9), null))));
        assertEquals(1, a.stats().load().getTotalBlocks());
    }

    @Test public void genericOwnerAdmissionCanEditAndAssessTasksButCannotStartRuntime() throws Exception {
        Fixture a = fresh(); a.sessionAllowed = false; long now = System.currentTimeMillis();
        a.run(() -> a.owner.transaction(() -> {
            a.rules.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, 125), 1000, 2000, 10, 7, 1, 30);
            a.stats().incrementCensorStyleChanges(); a.stats().recordCensorStyleTried("blur");
            a.stats().setCustomPhrasesCount(3);
        }));
        assertEquals(125, a.call(() -> a.ledger.recordTaskFailure("fixture-task:generic-admission", now, () -> true)).chargedCents);
        assertEquals(1, a.stats().load().getCensorStyleChanges()); assertEquals(3, a.stats().load().getCustomPhrases());
        assertTrue(a.stats().load().getCensorStylesTried().contains("blur"));
        assertEquals(125, a.stats().tributeCents("EUR", false)); assertNull(a.stats().observedSessionStamp());
        InternalRuntimeRecord before = a.data.snapshot();
        assertThrows(SecurityException.class, () -> a.begin(session(now, "UTC")));
        assertEquals(before, a.data.snapshot()); assertNull(a.stats().observedSessionStamp());
    }

    @Test public void genericOwnerAdmissionCannotSpendAnActiveRuntimeStamp() throws Exception {
        for (String operation : List.of("censor", "evidence", "usage", "whisper", "popup", "limit", "tribute", "ledger", "finish", "checkpoint")) {
            Fixture a = fresh(); long now = System.currentTimeMillis();
            StatsRepository.NativeSession session = session(now - 1000, "UTC"); a.begin(session);
            a.configure("EUR", 100, 150, 1000, 2000, 1);
            StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
            AtomicBoolean claimed = new AtomicBoolean(); a.sessionAllowed = false;
            InternalRuntimeRecord before = a.data.snapshot();
            assertThrows("Missing runtime admission must reject " + operation, SecurityException.class, () -> a.run(() -> {
                switch (operation) {
                    case "censor": a.owner.transaction(() -> a.stats().recordTrackIds(List.of(1), null)); break;
                    case "evidence": a.owner.recordEvidence(stamp, () -> { claimed.set(true); return null; }, null); break;
                    case "usage": a.owner.appUsage().recordUsage("fixture.first", 100, now); break;
                    case "whisper": a.owner.transaction(() -> { a.stats().recordSubliminalImpression(); }); break;
                    case "popup": a.owner.transaction(() -> { a.stats().recordPopupImpression(); }); break;
                    case "limit": a.owner.transaction(() -> { a.stats().recordLimitIntervention(); }); break;
                    case "tribute": a.owner.transaction(() -> a.stats().recordSourceTribute(stamp, 100, "EUR")); break;
                    case "ledger": a.ledger.recordSourceDetectionResult(1, now, stamp); break;
                    case "finish": a.owner.finishSession(session.identity, now); break;
                    case "checkpoint": a.owner.checkpoint(now); break;
                    default: throw new AssertionError("Unknown fixture action");
                }
            }));
            assertEquals(operation, before, a.data.snapshot()); assertFalse("Denied evidence must not be consumed", claimed.get());
        }
    }

    @Test public void noSessionCannotWriteRuntimeUsageOrAssessments() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis();
        assertNull(a.stats().sessionStamp());
        assertThrows(SecurityException.class, () -> a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 50, now)));
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                a.call(() -> a.ledger.recordSourceDwellResult(now, null)).status);
        a.run(() -> a.owner.transaction(() -> {
            a.stats().recordLimitedAppUsage(50); a.stats().recordPopupImpression();
            a.stats().recordSubliminalImpression(); assertEquals(0, a.stats().recordTrackIds(List.of(1), null));
        }));
        assertEquals(0, a.data.revision()); assertTrue(a.data.snapshot().namespaces().isEmpty());
        assertNull(a.stats().observedSessionStamp());
    }

    @Test public void staleAndForeignStampsFailAcrossSameClockRestartAndCleanupNeedsAdmission() throws Exception {
        Fixture a = fresh(), b = fresh(); long now = System.currentTimeMillis();
        StatsRepository.NativeSession first = session(now - 10_000, "UTC");
        a.begin(first); b.begin(first);
        a.configure("EUR", 100, 150, 1000, 2000, 1);
        StatsRepository.SessionStamp old = a.stats().observedSessionStamp();
        a.allowed = false;
        assertThrows(SecurityException.class, () -> a.run(() -> a.owner.finishSession(first.identity, now)));
        assertTrue(old.isCurrent());
        a.allowed = true; a.run(() -> a.owner.finishSession(first.identity, now));
        assertFalse(old.isCurrent()); assertNull(a.stats().observedSessionStamp());
        // Identical start clock deliberately exercises ABA instead of relying on a timestamp change.
        a.begin(session(first.startedAt, first.zone));
        StatsRepository.SessionStamp current = a.stats().observedSessionStamp();
        assertNotSame(old, current); assertTrue(current.isCurrent());
        long before = a.data.revision();
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                a.call(() -> a.ledger.recordSourceDetectionResult(1, now, old)).status);
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                a.call(() -> a.ledger.recordSourceDetectionResult(1, now, b.stats().observedSessionStamp())).status);
        assertEquals(before, a.data.revision());
        assertEquals(100, a.call(() -> a.ledger.recordSourceDetectionResult(1, now, current)).chargedCents);
        assertEquals(100, a.stats().tributeCents("EUR", true));
        assertEquals(0, b.stats().tributeCents("EUR", false));
    }

    @Test public void pendingInitializationPublishesNoStampOrCountersUntilDurableAck() throws Exception {
        Fixture a = fresh(); CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        a.backend.beforeWrite = () -> { committing.countDown(); await(release); };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> initialized = worker.submit(() -> a.begin(session(System.currentTimeMillis(), "UTC")));
            assertTrue(committing.await(5, TimeUnit.SECONDS));
            assertFalse(initialized.isDone()); assertNull(a.stats().observedSessionStamp());
            assertEquals(0, a.data.revision()); assertTrue(a.data.snapshot().namespaces().isEmpty());
            assertNull(a.data.lastReceipt());
            release.countDown(); initialized.get(5, TimeUnit.SECONDS);
            assertNotNull(a.stats().observedSessionStamp()); assertTrue(a.stats().observedSessionStamp().isCurrent());
            assertEquals(1, a.data.revision()); assertEquals(1, a.stats().load().getSessions());
            assertEquals(a.data.snapshot(), a.backend.read());
        } finally {
            release.countDown(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void serializedSnapshotWaitsForFinishAckAndRejectsUnadmittedConcurrentRead() throws Exception {
        Fixture a = fresh(); long end = System.currentTimeMillis(), start = end - 60_000;
        StatsRepository.NativeSession session = session(start, "UTC");
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> a.begin(session)).get(5, TimeUnit.SECONDS);
            OwnerStatisticsStore.Snapshot before = worker.submit(() -> a.snapshot(end)).get(5, TimeUnit.SECONDS);
            assertEquals(60, before.statistics.getTotalSessionSeconds());
            assertEquals(60, before.statistics.getCurrentSessionSeconds());
            StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
            a.backend.beforeWrite = () -> { committing.countDown(); await(release); };
            Future<?> finishing = worker.submit(() -> a.run(() -> a.owner.finishSession(session.identity, end)));
            assertTrue(committing.await(5, TimeUnit.SECONDS));
            Future<OwnerStatisticsStore.Snapshot> queued = worker.submit(() -> a.snapshot(end));
            assertFalse(finishing.isDone()); assertFalse(queued.isDone()); assertTrue(stamp.isCurrent());
            // This caller has no broker admission and must fail before touching the locked lane.
            assertThrows(SecurityException.class, () -> a.owner.snapshot(end));
            assertFalse(finishing.isDone()); assertFalse(queued.isDone());
            assertEquals(before.revision, a.data.revision());
            release.countDown(); finishing.get(5, TimeUnit.SECONDS);
            OwnerStatisticsStore.Snapshot after = queued.get(5, TimeUnit.SECONDS);
            assertEquals(before.revision + 1, after.revision);
            assertEquals(60, after.statistics.getTotalSessionSeconds());
            assertEquals(60, after.statistics.getTotalProtectedSeconds());
            assertEquals(0, after.statistics.getCurrentSessionSeconds());
            assertEquals(1, after.sessions().size()); assertEquals(60, after.sessions().get(0).getDurationSeconds());
            assertFalse(stamp.isCurrent()); assertNull(a.stats().observedSessionStamp());
            assertEquals(60, before.statistics.getCurrentSessionSeconds()); // Already published projection stays immutable.
            assertThrows(UnsupportedOperationException.class, () -> after.days().put("2024-01-01", Map.of()));
            assertThrows(UnsupportedOperationException.class, () -> after.sessions().clear());
        } finally {
            release.countDown(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void rejectedInitializationNeverMintsStampOrDurableSession() throws Exception {
        Fixture a = fresh(); a.backend.outcome = InternalRuntimePreferences.Outcome.NOT_COMMITTED;
        InternalRuntimePreferences.PersistenceException failure = assertThrows(InternalRuntimePreferences.PersistenceException.class,
                () -> a.begin(session(System.currentTimeMillis(), "UTC")));
        assertEquals(InternalRuntimePreferences.Outcome.NOT_COMMITTED, failure.outcome);
        assertNull(a.stats().observedSessionStamp()); assertTrue(a.backend.read().namespaces().isEmpty());
        assertEquals(0, a.backend.read().revision);
        a.close(); Fixture reopened = reopen(a.scope);
        assertNull(reopened.stats().sessionStamp()); assertEquals(0, reopened.stats().load().getSessions());
    }

    @Test public void persistedActiveSessionNeverRecreatesAuthorityOnReopen() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis();
        StatsRepository.NativeSession session = session(now - 1000, "UTC"); a.begin(session);
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 125, now));
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        a.configure("USD", 75, 150, 1000, 2000, 1);
        assertEquals(75, a.call(() -> a.ledger.recordSourceDetectionResult(1, now, stamp)).chargedCents);
        a.run(() -> a.owner.transaction(() -> {
            a.stats().recordSubliminalImpression(); a.stats().recordPopupImpression(); a.stats().recordLimitIntervention();
        }));
        a.close(); assertFalse(stamp.isCurrent());
        Fixture reopened = reopen(a.scope); InternalRuntimeRecord before = reopened.data.snapshot();
        assertNull(reopened.stats().observedSessionStamp()); assertNull(reopened.stats().sessionStamp());
        assertEquals(0, reopened.stats().load().getCurrentSessionSeconds());
        assertEquals(125, reopened.stats().load().getLimitedAppMillis());
        assertEquals(0, reopened.stats().load().getCurrentSessionLimitedAppMillis());
        assertEquals(0, reopened.snapshot(now).statistics.getCurrentSessionLimitedAppMillis());
        assertEquals(75, reopened.stats().tributeCents("USD", false));
        assertEquals(0, reopened.stats().tributeCents("USD", true));
        assertEquals(0, reopened.stats().load().getCurrentSessionTributeEvents());
        assertEquals(0, reopened.stats().load().getCurrentSessionSubliminals());
        assertEquals(0, reopened.stats().load().getCurrentSessionPopupImpressions());
        assertEquals(0, reopened.stats().load().getCurrentSessionLimitInterventions());
        assertThrows(SecurityException.class, () -> reopened.run(() -> reopened.owner.appUsage().recordUsage("fixture.first", 1, now)));
        assertThrows(IllegalStateException.class, () -> reopened.begin(session));
        assertNull(reopened.stats().observedSessionStamp()); assertEquals(before, reopened.data.snapshot());
    }

    @Test public void interruptedSessionRecoveryUsesOnlyExactDurableCheckpointAndAllowsFreshStart() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(), start = now - TimeUnit.DAYS.toMillis(1);
        StatsRepository.NativeSession original = session(start, "UTC"); a.begin(original);
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 300, start + 60_000));
        a.run(() -> a.owner.checkpoint(start + 120_000));
        a.close(); Fixture reopened = reopen(a.scope);
        assertNull(reopened.stats().observedSessionStamp());
        assertEquals(120, reopened.snapshot(now).statistics.getTotalSessionSeconds());
        InternalRuntimeRecord before = reopened.data.snapshot();
        assertThrows(IllegalStateException.class,
                () -> reopened.run(() -> reopened.owner.reconcileInterruptedSession(UUID.randomUUID().toString())));
        assertEquals(before, reopened.data.snapshot());
        reopened.run(() -> reopened.owner.reconcileInterruptedSession(original.identity));
        assertNull(reopened.stats().observedSessionStamp());
        assertEquals(120, reopened.stats().load().getTotalSessionSeconds());
        assertEquals(300, reopened.stats().load().getLimitedAppMillis());
        assertEquals(0, reopened.stats().load().getCurrentSessionLimitedAppMillis());
        assertEquals(1, reopened.stats().getSessionHistory().size());
        assertEquals(120, reopened.stats().getSessionHistory().get(0).getDurationSeconds());
        assertEquals(300, reopened.stats().getSessionHistory().get(0).getLimitedAppMillis());
        long revision = reopened.data.revision();
        assertThrows(IllegalStateException.class,
                () -> reopened.run(() -> reopened.owner.reconcileInterruptedSession(original.identity)));
        assertEquals(revision, reopened.data.revision());
        StatsRepository.NativeSession fresh = session(now, "UTC"); reopened.begin(fresh);
        assertNotNull(reopened.stats().observedSessionStamp());
        reopened.run(() -> reopened.owner.finishSession(fresh.identity, now + 1000));
        assertEquals(2, reopened.stats().load().getSessions());
        assertEquals(121, reopened.stats().load().getTotalSessionSeconds());
        assertEquals(2, reopened.snapshot(now + 1000).sessions().size());
    }

    @Test public void interruptedSessionWithoutLaterObservationRecoversZeroDowntime() throws Exception {
        Fixture a = fresh(); long start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        StatsRepository.NativeSession original = session(start, "UTC"); a.begin(original); a.close();
        Fixture reopened = reopen(a.scope);
        reopened.run(() -> reopened.owner.reconcileInterruptedSession(original.identity));
        assertEquals(0, reopened.stats().load().getTotalSessionSeconds());
        assertEquals(0, reopened.stats().getSessionHistory().get(0).getDurationSeconds());
        assertNull(reopened.stats().observedSessionStamp());
    }

    @Test public void failureBetweenLedgerStagingAndStatisticsRollsBackBoth() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); a.begin(session(now - 1000, "UTC"));
        a.configure("EUR", 100, 150, 1000, 2000, 1);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        InternalRuntimeRecord before = a.data.snapshot(); int writes = a.backend.writes;
        a.failAfterLedgerStage = true;
        assertThrows(SecurityException.class,
                () -> a.call(() -> a.ledger.recordSourceDetectionResult(1, now, stamp)));
        assertTrue("Failure must occur after a real ledger editor staged its event", a.sawStagedLedger);
        a.failAfterLedgerStage = false;
        assertEquals(writes, a.backend.writes); assertEquals(before, a.backend.read());
        assertEquals(before, a.data.snapshot()); assertTrue(a.ledger.events().isEmpty());
        assertEquals(0, a.stats().tributeCents("EUR", false)); assertTrue(stamp.isCurrent());
        assertTrue(a.persistenceFailures.isEmpty());
    }

    @Test public void rejectedWholeRecordAckCannotPublishOnlyLedgerOrOnlyStatistics() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); a.begin(session(now - 1000, "UTC"));
        a.configure("USD", 175, 200, 1000, 2000, 1);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        InternalRuntimeRecord before = a.data.snapshot();
        a.backend.beforeWrite = () -> {
            Map<String, Map<String, Object>> staged = a.backend.candidate.namespaces();
            assertTrue(staged.get("assessed_ledger").containsKey("assessed_events_v1"));
            assertEquals(175L, staged.get("statistics").get("tribute_cents_USD"));
            assertEquals(1L, staged.get("statistics").get("tribute_events"));
        };
        a.backend.outcome = InternalRuntimePreferences.Outcome.NOT_COMMITTED;
        assertThrows(InternalRuntimePreferences.PersistenceException.class,
                () -> a.call(() -> a.ledger.recordSourceDetectionResult(1, now, stamp)));
        assertEquals(before, a.backend.read()); assertFalse(stamp.isCurrent());
        assertNull(a.stats().observedSessionStamp());
        a.close(); Fixture reopened = reopen(a.scope);
        assertTrue(reopened.ledger.events().isEmpty()); assertEquals(0, reopened.stats().tributeCents("USD", false));
        assertEquals(0, reopened.stats().load().getTributeEvents());
    }

    @Test public void liveRulesBatchRemainderDwellAndPerCurrencyCapsUseActualAssessments() throws Exception {
        Fixture a = fresh(); long now = LocalDate.of(2024, 4, 9).atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        a.begin(session(now - 1000, ZoneId.systemDefault().getId()));
        a.configure("EUR", 100, 150, 250, 300, 3);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        OwnerPenanceLedger.Result partial = a.call(() -> a.ledger.recordSourceDetectionResult(2, now, stamp));
        assertEquals(OwnerPenanceLedger.Status.ACKNOWLEDGED, partial.status);
        assertEquals(0, partial.chargedCents); assertEquals(2, partial.batchRemainder); assertTrue(a.ledger.events().isEmpty());
        // The existing ledger must observe the new configuration, including the changed batch.
        a.configure("EUR", 100, 150, 250, 300, 2);
        OwnerPenanceLedger.Result charged = a.call(() -> a.ledger.recordSourceDetectionResult(1, now, stamp));
        assertEquals(100, charged.chargedCents); assertEquals(1, charged.batchRemainder);
        assertEquals(150, a.call(() -> a.ledger.recordSourceDwellResult(now, stamp)).chargedCents);
        assertEquals(0, a.call(() -> a.ledger.recordSourceDwellResult(now, stamp)).chargedCents);
        assertEquals(50, a.call(() -> a.ledger.recordSourceDwellResult(now + TimeUnit.DAYS.toMillis(1), stamp)).chargedCents);
        a.configure("USD", 100, 150, 250, 300, 2);
        assertEquals(150, a.call(() -> a.ledger.recordSourceDwellResult(now, stamp)).chargedCents);
        assertEquals(300, a.stats().tributeCents("EUR", false)); assertEquals(150, a.stats().tributeCents("USD", false));
        assertEquals(4, a.stats().load().getTributeEvents()); assertEquals(4, a.ledger.events().size());
        assertEquals("USD", a.ledger.events().get(3).getCurrency());
        a.censorEnabled.set(false); long before = a.data.revision();
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                a.call(() -> a.ledger.recordSourceDwellResult(now, stamp)).status);
        assertEquals(before, a.data.revision());
        a.censorEnabled.set(true);
        a.run(() -> a.owner.transaction(() -> a.rules.disableInfraction(PenanceInfraction.CENSORED_DWELL)));
        assertEquals(OwnerPenanceLedger.Status.REJECTED,
                a.call(() -> a.ledger.recordSourceDwellResult(now, stamp)).status);
    }

    @Test public void idleTaskAssessmentHasOneReceiptAndNeverInventsRuntimeOrPayment() throws Exception {
        Fixture a = fresh(), b = fresh(); long now = System.currentTimeMillis();
        a.run(() -> a.owner.transaction(() -> a.rules.configure(true,
                Map.of(PenanceInfraction.TASK_FAILURE, 125), 1000, 2000, 10, 7, 1, 30)));
        assertNull(a.stats().sessionStamp());
        assertEquals(125, a.call(() -> a.ledger.recordTaskFailure("fixture-task:occurrence-1", now, () -> true)).chargedCents);
        long revision = a.data.revision();
        assertEquals(0, a.call(() -> a.ledger.recordTaskFailure("fixture-task:occurrence-1", now, () -> true)).chargedCents);
        assertEquals(revision, a.data.revision());
        assertEquals(1, a.ledger.events().size()); assertEquals(PenanceInfraction.TASK_FAILURE, a.ledger.events().get(0).getInfraction());
        assertEquals(125, a.stats().tributeCents("EUR", false)); assertEquals(0, a.stats().tributeCents("EUR", true));
        assertEquals(1, a.stats().load().getTributeEvents()); assertEquals(0, a.stats().load().getSessions());
        assertNull(a.stats().observedSessionStamp()); assertTrue(a.snapshot(now).sessions().isEmpty());
        assertTrue(b.ledger.events().isEmpty()); assertEquals(0, b.stats().load().getTributeEvents());
        assertEquals(com.subhub.app.penance.PenanceEvent.Status.OPEN, a.ledger.events().get(0).getStatus());
        assertEquals("", a.ledger.events().get(0).getSettlementId());
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(0, reopened.call(() -> reopened.ledger.recordTaskFailure("fixture-task:occurrence-1", now, () -> true)).chargedCents);
        assertEquals(1, reopened.ledger.events().size()); assertEquals(125, reopened.stats().tributeCents("EUR", false));
        assertNull(reopened.stats().sessionStamp());
    }

    @Test public void disabledTaskOccurrenceIsAcknowledgedWithoutRetroactiveAssessment() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis();
        a.run(() -> a.owner.transaction(() -> a.rules.configure(false,
                Map.of(PenanceInfraction.TASK_FAILURE, 125), 1000, 2000, 10, 7, 1, 30)));
        assertEquals(0, a.call(() -> a.ledger.recordTaskFailure("fixture-task:disabled-rule", now, () -> true)).chargedCents);
        a.run(() -> a.owner.transaction(() -> a.rules.configure(true,
                Map.of(PenanceInfraction.TASK_FAILURE, 125), 1000, 2000, 10, 7, 1, 30)));
        assertEquals(0, a.call(() -> a.ledger.recordTaskFailure("fixture-task:disabled-rule", now, () -> true)).chargedCents);
        assertEquals(0, a.call(() -> a.ledger.recordTaskFailure("fixture-task:disabled-feature", now, () -> false)).chargedCents);
        assertEquals(0, a.call(() -> a.ledger.recordTaskFailure("fixture-task:disabled-feature", now, () -> true)).chargedCents);
        assertTrue(a.ledger.events().isEmpty()); assertEquals(0, a.stats().load().getTributeEvents());
        assertEquals(125, a.call(() -> a.ledger.recordTaskFailure("fixture-task:new-occurrence", now, () -> true)).chargedCents);
        assertEquals(1, a.ledger.events().size()); assertNull(a.stats().sessionStamp());
    }

    @Test public void actualUsageRollsOverWithoutDuplicatingCumulativeCheckpoints() throws Exception {
        Fixture a = fresh(); ZoneId zone = ZoneId.systemDefault();
        long firstDay = LocalDate.of(2024, 4, 9).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        long nextDay = LocalDate.of(2024, 4, 10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        a.begin(session(firstDay - 1000, zone.getId()));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 125, firstDay));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.second", 275, firstDay));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 100, firstDay));
        Map<String, AppTimerManager.UsageSnapshot> measured = a.owner.appUsage().snapshots(Set.of("fixture.first", "fixture.second"), firstDay);
        assertEquals(225, measured.get("fixture.first").appUsedMillis);
        assertEquals(275, measured.get("fixture.second").appUsedMillis);
        assertEquals(500, measured.get("").totalUsedMillis);
        assertEquals(500, a.stats().load().getCurrentSessionLimitedAppMillis());
        int rows = a.owner.appUsage().entries().size(); long revision = a.data.revision();
        a.run(() -> { a.owner.appUsage().checkpointHistory(firstDay); a.owner.appUsage().checkpointHistory(firstDay); });
        assertEquals(3, rows); assertEquals(rows, a.owner.appUsage().entries().size());
        assertEquals(revision, a.data.revision());
        assertEquals(0, a.owner.appUsage().snapshot("fixture.first", nextDay).totalUsedMillis);
        assertEquals(revision, a.data.revision()); // A read cannot roll over or erase yesterday.
        a.run(() -> a.owner.appUsage().recordUsage("fixture.second", 80, nextDay));
        assertEquals(0, a.owner.appUsage().snapshot("fixture.first", nextDay).appUsedMillis);
        assertEquals(80, a.owner.appUsage().snapshot("fixture.second", nextDay).totalUsedMillis);
        assertEquals(5, a.owner.appUsage().entries().size());
        // Revisiting a civil date gets a fresh epoch instead of overwriting its older retained facts.
        a.run(() -> a.owner.appUsage().recordUsage("fixture.first", 20, firstDay));
        assertEquals(7, a.owner.appUsage().entries().size());
        assertEquals(600, a.stats().load().getLimitedAppMillis());
        assertEquals(600, a.stats().load().getCurrentSessionLimitedAppMillis());
        long aggregate = 0; Set<String> epochs = new HashSet<>();
        for (DailyAppUsageStore.Entry entry : a.owner.appUsage().entries()) {
            assertEquals(zone.getId(), entry.zone); epochs.add(entry.epoch);
            if (entry.packageName.isEmpty()) aggregate += entry.millis;
        }
        assertEquals(3, epochs.size()); assertEquals(600, aggregate);
    }

    @Test public void achievementReconciliationIsOwnerLocalAndPreservesEarnedDate() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, ?> physical = app.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE).getAll();
        Fixture a = fresh(), b = fresh();
        long start = Instant.parse("2024-02-03T12:00:00Z").toEpochMilli(), now = System.currentTimeMillis();
        StatsRepository.NativeSession session = session(start, "UTC"); a.begin(session);
        a.run(() -> a.owner.finishSession(session.identity, start + 60_000));
        AchievementManager.OwnerFacts noFeatures = noConfiguredFeatures();
        AchievementManager first = a.call(() -> a.owner.reconcileAchievements(noFeatures, now));
        AchievementManager other = b.call(() -> b.owner.reconcileAchievements(noFeatures, now));
        assertTrue(first.isUnlocked("first_session")); assertFalse(other.isUnlocked("first_session"));
        assertFalse(first.isUnlocked("wallet_paid_10")); assertFalse(first.isUnlocked("paypal_vault"));
        long earned = first.getUnlockedAt("first_session"); assertTrue(earned > 0); assertTrue(earned < now);
        long revision = a.data.revision();
        AchievementManager repeated = a.call(() -> a.owner.reconcileAchievements(noFeatures, now + 1000));
        assertEquals(earned, repeated.getUnlockedAt("first_session")); assertEquals(revision, a.data.revision());
        assertTrue(b.data.preferences("achievements").getStringSet("unlocked", Set.of()).isEmpty());
        assertEquals(physical, app.getSharedPreferences("betablocker_achievements", Context.MODE_PRIVATE).getAll());
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(earned, reopened.call(() -> reopened.owner.achievements(noFeatures)).getUnlockedAt("first_session"));
        assertNull(reopened.stats().observedSessionStamp());
    }

    @Test public void unavailableAchievementFactPropagatesAndRollsBackStagedReceipts() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis();
        StatsRepository.NativeSession session = session(now - 60_000, "UTC"); a.begin(session);
        a.run(() -> a.owner.finishSession(session.identity, now));
        InternalRuntimeRecord before = a.data.snapshot(); int writes = a.backend.writes;
        IllegalStateException unavailable = new IllegalStateException("Fixture feature fact unavailable");
        AtomicInteger queries = new AtomicInteger(); AtomicBoolean sawStagedReceipts = new AtomicBoolean();
        AchievementManager.OwnerFacts noFeatures = noConfiguredFeatures();
        AchievementManager.OwnerFacts failing = new AchievementManager.OwnerFacts() {
            @Override public boolean isAccessible() { return true; }
            @Override public long current(String id) {
                if ("app_mode_guardian".equals(id) && queries.incrementAndGet() == 2) {
                    sawStagedReceipts.set(a.data.preferences("achievements").getStringSet("unlocked", Set.of()).contains("first_session"));
                    throw unavailable;
                }
                return noFeatures.current(id);
            }
        };
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> a.call(() -> a.owner.reconcileAchievements(failing, now))));
        assertTrue("The first reconciliation must have staged its receipt before the later fact failure", sawStagedReceipts.get());
        assertEquals(writes, a.backend.writes); assertEquals(before, a.data.snapshot()); assertEquals(before, a.backend.read());
        assertFalse(a.call(() -> a.owner.achievements(noFeatures)).isUnlocked("first_session"));
        assertTrue(a.data.preferences("achievements").getAll().isEmpty());
        assertTrue(a.call(() -> a.owner.reconcileAchievements(noFeatures, now)).isUnlocked("first_session"));
    }

    @Test public void retainedSessionsOutliveBoundedRecentHistoryAndSurviveReopen() throws Exception {
        Fixture a = fresh(); long start = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli();
        Set<Long> starts = new HashSet<>();
        for (int index = 0; index < 36; index++) {
            long at = start + TimeUnit.DAYS.toMillis(index); starts.add(at);
            StatsRepository.NativeSession session = session(at, "UTC"); a.begin(session);
            a.run(() -> a.owner.checkpoint(at + 30_000));
            a.run(() -> a.owner.finishSession(session.identity, at + 60_000));
        }
        long observed = start + TimeUnit.DAYS.toMillis(37);
        OwnerStatisticsStore.Snapshot saved = a.snapshot(observed);
        assertEquals(30, a.stats().getSessionHistory().size()); assertEquals(36, saved.sessions().size());
        assertEquals(36, saved.statistics.getSessions()); assertEquals(2160, saved.statistics.getTotalSessionSeconds());
        for (StatsRepository.SessionEntry entry : saved.sessions()) {
            assertTrue(starts.remove(entry.getStartMillis())); assertEquals(60, entry.getDurationSeconds());
        }
        assertTrue(starts.isEmpty());
        long revision = a.data.revision();
        a.run(() -> { a.owner.checkpoint(observed); a.owner.checkpoint(observed); });
        assertEquals(revision, a.data.revision()); assertEquals(saved.days(), a.snapshot(observed).days());
        a.close(); Fixture reopened = reopen(a.scope);
        OwnerStatisticsStore.Snapshot recovered = reopened.snapshot(observed);
        assertEquals(saved.days(), recovered.days()); assertEquals(36, recovered.sessions().size());
        assertEquals(2160, recovered.statistics.getTotalSessionSeconds()); assertNull(reopened.stats().sessionStamp());
    }

    @Test public void retainedDurationKeepsOriginalCivilCalendarAcrossDstAndRepeatedCheckpoints() throws Exception {
        Fixture a = fresh(); long start = Instant.parse("2024-03-10T04:30:00Z").toEpochMilli();
        long end = Instant.parse("2024-03-10T08:30:00Z").toEpochMilli();
        StatsRepository.NativeSession session = session(start, "America/New_York"); a.begin(session);
        a.run(() -> a.owner.checkpoint(start + TimeUnit.HOURS.toMillis(2)));
        a.run(() -> a.owner.checkpoint(start + TimeUnit.HOURS.toMillis(1))); // Older observation cannot shorten retained duration.
        assertEquals(7200, a.snapshot(start + TimeUnit.HOURS.toMillis(2)).statistics.getTotalSessionSeconds());
        a.run(() -> a.owner.finishSession(session.identity, end));
        OwnerStatisticsStore.Snapshot saved = a.snapshot(end + 1000);
        assertEquals(14_400, saved.statistics.getTotalSessionSeconds()); assertEquals(1, saved.sessions().size());
        assertEquals(Long.valueOf(1_800_000), saved.days().get("2024-03-09").get("service_ms"));
        assertEquals(Long.valueOf(12_600_000), saved.days().get("2024-03-10").get("service_ms"));
        a.run(() -> a.owner.checkpoint(end + TimeUnit.DAYS.toMillis(2)));
        assertEquals(saved.days(), a.snapshot(end + TimeUnit.DAYS.toMillis(2)).days());
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(saved.days(), reopened.snapshot(end + TimeUnit.DAYS.toMillis(2)).days());
    }

    @Test public void capturedEventCalendarNeverDoublesExactOwnerDailyAccounting() throws Exception {
        long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        for (String zone : List.of("UTC", "Pacific/Kiritimati")) {
            Fixture a = fresh(); StatsRepository.NativeSession session = session(at, "UTC"); a.begin(session);
            OwnerObservation captured = new OwnerObservation(at + 1000, zone);
            a.configure("EUR", 100, 150, 1000, 2000, 1);
            StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
            a.run(() -> a.owner.appUsage().recordUsage("fixture.delayed", 125, captured));
            assertEquals(100, a.call(() -> a.ledger.recordSourceDetectionResult(1, captured, stamp)).chargedCents);
            a.configure("USD", 150, 150, 1000, 2000, 1);
            assertEquals(150, a.call(() -> a.ledger.recordSourceDetectionResult(1, captured, stamp)).chargedCents);
            a.run(() -> a.owner.transaction(() -> {
                a.stats().recordSubliminalImpression(captured); a.stats().recordPopupImpression(captured);
            }));
            a.run(() -> a.owner.finishSession(session.identity, at + 60_000));
            a.run(() -> a.owner.transaction(() -> {
                a.data.preferences("rules").edit().putString("wallet_currency", "EUR").commit();
                a.rules.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, 75), 1000, 2000, 10, 7, 1, 30);
            }));
            assertEquals(75, a.call(() -> a.ledger.recordTaskFailure("delayed_task", captured, () -> true)).chargedCents);
            OwnerStatisticsStore.Snapshot facts = a.snapshot(System.currentTimeMillis());
            assertEquals(125, a.stats().load().getLimitedAppMillis());
            assertEquals(125, facts.statistics.getLimitedAppMillis());
            assertEquals(125, sum(facts, "limits_ms"));
            assertEquals(175, sum(facts, "assessed_EUR")); assertEquals(150, sum(facts, "assessed_USD"));
            assertEquals(1, sum(facts, "whispers")); assertEquals(1, sum(facts, "popups"));
            assertEquals(Long.valueOf(125), facts.days().get(captured.day()).get("limits_ms"));
            assertEquals(Long.valueOf(175), facts.days().get(captured.day()).get("assessed_EUR"));
            assertEquals(captured.day(), a.owner.appUsage().entries().get(0).day);
            assertEquals(at + 1000, a.ledger.retainedEvents().get(0).getCreatedAtMillis());
            a.close(); Fixture reopened = reopen(a.scope);
            assertEquals(facts.days(), reopened.snapshot(System.currentTimeMillis()).days());
            assertEquals(125, reopened.snapshot(System.currentTimeMillis()).statistics.getLimitedAppMillis());
        }
    }

    @Test public void olderFinishCannotEraseAcknowledgedDurationOrRevokeValidLane() throws Exception {
        Fixture a = fresh(); long at = System.currentTimeMillis() - 300_000;
        StatsRepository.NativeSession session = session(at, "UTC"); a.begin(session);
        a.run(() -> a.owner.checkpoint(at + 120_000));
        InternalRuntimeRecord before = a.data.snapshot(); StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        assertThrows(IllegalStateException.class, () -> a.run(() -> a.owner.finishSession(session.identity, at + 60_000)));
        assertEquals(before, a.data.snapshot()); assertTrue(stamp.isCurrent());
        assertEquals(120, a.snapshot(at + 60_000).statistics.getTotalSessionSeconds());
        assertEquals(120, a.snapshot(at + 120_000).statistics.getTotalSessionSeconds());
        a.run(() -> a.owner.finishSession(session.identity, at + 120_000));
        long revision = a.data.revision(); a.run(() -> a.owner.finishSession(session.identity, at + 120_000));
        assertEquals(revision, a.data.revision()); assertEquals(120, a.snapshot(at + 120_000).statistics.getTotalSessionSeconds());
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(120, reopened.snapshot(at + 900_000).statistics.getTotalSessionSeconds());
        assertNull(reopened.stats().observedSessionStamp());
    }

    @Test public void assessmentsArchiveWithoutLifetimeCutoffAndOldTaskReceiptsRemainIdempotent() throws Exception {
        Fixture a = fresh(); long start = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli();
        StatsRepository.NativeSession session = session(start, "UTC"); a.begin(session);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        for (int i = 0; i < 205; i++) {
            final String currency = i % 2 == 0 ? "EUR" : "USD";
            a.run(() -> a.owner.transaction(() -> {
                a.data.preferences("rules").edit().putString("wallet_currency", currency).commit();
                a.rules.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, 1, PenanceInfraction.NEW_DETECTION, 1), 2, 100, 10, 7, 1, 30);
            }));
            OwnerObservation observed = new OwnerObservation(start + TimeUnit.DAYS.toMillis(i), "UTC"); String occurrence = "archived_task_" + i;
            assertEquals(1, a.call(() -> a.ledger.recordTaskFailure(occurrence, observed, () -> true)).chargedCents);
            assertEquals(1, a.call(() -> a.ledger.recordSourceDetectionResult(1, observed, stamp)).chargedCents);
            assertEquals(0, a.call(() -> a.ledger.recordSourceDetectionResult(1, observed, stamp)).chargedCents);
        }
        assertEquals(200, a.ledger.events().size()); assertEquals(410, a.ledger.retainedEvents().size());
        assertEquals(206, a.stats().tributeCents("EUR", false)); assertEquals(204, a.stats().tributeCents("USD", false));
        assertEquals(206, sum(a.snapshot(System.currentTimeMillis()), "assessed_EUR"));
        assertEquals(204, sum(a.snapshot(System.currentTimeMillis()), "assessed_USD"));
        a.run(() -> a.owner.finishSession(session.identity, start + TimeUnit.DAYS.toMillis(205)));
        a.close(); Fixture reopened = reopen(a.scope); long revision = reopened.data.revision();
        assertEquals(0, reopened.call(() -> reopened.ledger.recordTaskFailure("archived_task_0", new OwnerObservation(start, "UTC"), () -> true)).chargedCents);
        assertEquals(revision, reopened.data.revision()); assertEquals(410, reopened.ledger.retainedEvents().size());
        assertEquals(206, reopened.stats().tributeCents("EUR", false)); assertEquals(204, reopened.stats().tributeCents("USD", false));
    }

    @Test public void reusableAchievementViewIsImmutableAcrossFailedAndBlockedAcknowledgements() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); StatsRepository.NativeSession session = session(now - 60_000, "UTC");
        a.begin(session); a.run(() -> a.owner.finishSession(session.identity, now));
        AchievementManager.OwnerFacts facts = noConfiguredFeatures();
        AchievementManager view = a.call(() -> a.owner.achievements(facts));
        assertThrows(UnsupportedOperationException.class, () -> a.run(() -> a.owner.transaction(() -> view.checkAchievements(a.stats().load()))));
        assertThrows(UnsupportedOperationException.class, view::takePendingNotifications);
        assertFalse(view.isUnlocked("first_session")); assertTrue(a.data.preferences("achievements").getAll().isEmpty());
        AchievementManager.OwnerFacts unavailable = new AchievementManager.OwnerFacts() {
            @Override public boolean isAccessible() { return true; }
            @Override public long current(String id) { throw new IllegalStateException("Unavailable fixture fact"); }
        };
        assertThrows(IllegalStateException.class, () -> a.call(() -> a.owner.reconcileAchievements(unavailable, now)));
        assertFalse(view.isUnlocked("first_session")); assertTrue(a.data.preferences("achievements").getAll().isEmpty());
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        a.backend.beforeWrite = () -> { committing.countDown(); await(release); };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<AchievementManager> pending = worker.submit(() -> a.call(() -> a.owner.reconcileAchievements(facts, now)));
            assertTrue(committing.await(5, TimeUnit.SECONDS)); assertFalse(pending.isDone());
            assertFalse(view.isUnlocked("first_session")); assertTrue(a.data.preferences("achievements").getAll().isEmpty());
            release.countDown(); AchievementManager earned = pending.get(5, TimeUnit.SECONDS);
            assertTrue(earned.isUnlocked("first_session")); assertFalse(view.isUnlocked("first_session"));
            assertThrows(UnsupportedOperationException.class, earned::takePendingNotifications);
        } finally { release.countDown(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void immutableAchievementViewsNeverInventSuccessAfterDefiniteOrUncertainFailure() throws Exception {
        for (InternalRuntimePreferences.Outcome outcome : List.of(InternalRuntimePreferences.Outcome.NOT_COMMITTED, InternalRuntimePreferences.Outcome.UNCERTAIN)) {
            Fixture a = fresh(); long now = System.currentTimeMillis(); StatsRepository.NativeSession session = session(now - 60_000, "UTC");
            a.begin(session); a.run(() -> a.owner.finishSession(session.identity, now));
            AchievementManager.OwnerFacts facts = noConfiguredFeatures(); AchievementManager view = a.call(() -> a.owner.achievements(facts));
            InternalRuntimeRecord before = a.backend.read(); a.backend.outcome = outcome;
            assertThrows(InternalRuntimePreferences.PersistenceException.class, () -> a.call(() -> a.owner.reconcileAchievements(facts, now)));
            assertFalse(view.isUnlocked("first_session")); assertEquals(before, a.backend.read());
            a.close(); Fixture reopened = reopen(a.scope);
            AchievementManager earned = reopened.call(() -> reopened.owner.reconcileAchievements(facts, now));
            assertTrue(earned.isUnlocked("first_session")); long revision = reopened.data.revision();
            assertEquals(earned.getUnlockedAt("first_session"), reopened.call(() -> reopened.owner.reconcileAchievements(facts, now)).getUnlockedAt("first_session"));
            assertEquals(revision, reopened.data.revision());
        }
    }
    private static long sum(OwnerStatisticsStore.Snapshot facts, String metric) {
        long total = 0; for (Map<String, Long> day : facts.days().values()) total = Math.addExact(total, day.getOrDefault(metric, 0L));
        return total;
    }

    @Test public void sameDateZoneRotationPreservesEachEpochAndTheWholeDayAllowance() throws Exception {
        Fixture a = fresh(); long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        long budgetClock = LocalDate.parse("2024-04-09").atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        StatsRepository.NativeSession session = session(at, "UTC"); a.begin(session);
        a.run(() -> a.owner.appUsage().recordUsage("fixture.same", 100, new OwnerObservation(at, "UTC")));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.same", 200, new OwnerObservation(at + 1000, "Europe/Berlin")));
        assertEquals(300, a.owner.appUsage().snapshot("fixture.same", budgetClock).appUsedMillis);
        assertEquals(300, a.owner.appUsage().snapshot("fixture.same", budgetClock).totalUsedMillis);
        assertEquals(300, a.owner.appUsage().snapshot("fixture.same", new OwnerObservation(at + 1000, "Europe/Berlin")).totalUsedMillis);
        assertEquals(300, a.owner.appUsage().snapshots(Set.of("fixture.same"), new OwnerObservation(at, "UTC")).get("fixture.same").appUsedMillis);
        assertEquals(0, a.owner.appUsage().snapshot("fixture.same", new OwnerObservation(at, "Pacific/Kiritimati")).totalUsedMillis);
        assertEquals(100, usageFor(a, "UTC", "")); assertEquals(200, usageFor(a, "Europe/Berlin", ""));
        assertEquals(100, usageFor(a, "UTC", "fixture.same")); assertEquals(200, usageFor(a, "Europe/Berlin", "fixture.same"));
        assertEquals(4, a.owner.appUsage().entries().size());
        a.run(() -> a.owner.appUsage().recordUsage("fixture.other", 50, new OwnerObservation(at + 2000, "UTC")));
        assertEquals(350, a.owner.appUsage().snapshot("fixture.same", budgetClock).totalUsedMillis);
        assertEquals(300, a.owner.appUsage().snapshot("fixture.same", budgetClock).appUsedMillis);
        assertEquals(50, a.owner.appUsage().snapshot("fixture.other", budgetClock).appUsedMillis);
        assertEquals(150, usageFor(a, "UTC", "")); assertEquals(200, usageFor(a, "Europe/Berlin", ""));
        long revision = a.data.revision();
        a.run(() -> { a.owner.appUsage().checkpointHistory(at + 3000); a.owner.appUsage().checkpointHistory(at + 3000); });
        assertEquals(revision, a.data.revision()); assertEquals(6, a.owner.appUsage().entries().size());
        a.run(() -> a.owner.finishSession(session.identity, at + 5000));
        assertEquals(350, a.snapshot(System.currentTimeMillis()).statistics.getLimitedAppMillis());
        assertEquals(350, sum(a.snapshot(System.currentTimeMillis()), "limits_ms"));
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(350, reopened.owner.appUsage().snapshot("fixture.same", budgetClock).totalUsedMillis);
        assertEquals(300, reopened.owner.appUsage().snapshot("fixture.same", budgetClock).appUsedMillis);
        assertEquals(150, usageFor(reopened, "UTC", "")); assertEquals(200, usageFor(reopened, "Europe/Berlin", ""));
        long reopenedRevision = reopened.data.revision(); reopened.run(() -> reopened.owner.appUsage().checkpointHistory(at + 6000));
        assertEquals(reopenedRevision, reopened.data.revision());
        assertEquals(350, reopened.snapshot(System.currentTimeMillis()).statistics.getLimitedAppMillis());
    }
    @Test public void existingSingleEpochUsageUpgradesWithoutResetOrReattribution() throws Exception {
        Fixture a = fresh(); long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        StatsRepository.NativeSession session = session(at, "UTC"); a.begin(session);
        String originalEpoch = UUID.randomUUID().toString();
        a.run(() -> a.owner.transaction(() -> {
            a.data.preferences("usage").edit().putString("day", "2024-04-09").putString("zone", "UTC")
                    .putString("epoch", originalEpoch).putLong("total_ms", 100).putLong("app:fixture.same", 100).commit();
            a.stats().recordLimitedAppUsage(100, new OwnerObservation(at, "UTC"));
        }));
        a.run(() -> a.owner.appUsage().checkpointHistory(at));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.same", 25, new OwnerObservation(at + 1000, "UTC")));
        assertEquals(originalEpoch, a.data.preferences("usage").getString("epoch", ""));
        assertEquals(125, usageFor(a, "UTC", ""));
        a.run(() -> a.owner.appUsage().recordUsage("fixture.same", 200, new OwnerObservation(at + 2000, "Europe/Berlin")));
        assertNotEquals(originalEpoch, a.data.preferences("usage").getString("epoch", ""));
        assertEquals(125, usageFor(a, "UTC", "")); assertEquals(200, usageFor(a, "Europe/Berlin", ""));
        assertEquals(325, a.data.preferences("usage").getLong("total_ms", -1));
        assertEquals(325, a.stats().load().getLimitedAppMillis());
        a.run(() -> a.owner.finishSession(session.identity, at + 3000)); a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(125, usageFor(reopened, "UTC", "")); assertEquals(200, usageFor(reopened, "Europe/Berlin", ""));
        assertEquals(325, reopened.data.preferences("usage").getLong("app:fixture.same", -1));
    }
    private static long usageFor(Fixture fixture, String zone, String packageName) {
        long total = 0;
        for (DailyAppUsageStore.Entry entry : fixture.owner.appUsage().entries())
            if (entry.zone.equals(zone) && entry.packageName.equals(packageName)) total = Math.addExact(total, entry.millis);
        return total;
    }

    @Test public void genericTaskCapacityRejectionPreservesExactActiveStampAndCleanStop() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); StatsRepository.NativeSession session = session(now - 1000, "UTC");
        a.begin(session);
        a.run(() -> a.owner.transaction(() -> {
            a.rules.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, 1), 1000, 2000, 10, 7, 1, 30);
            android.content.SharedPreferences.Editor edit = a.data.preferences("assessed_ledger").edit();
            for (int i = 0; i < 100_000; i++) edit.putInt("reserved_" + i, 0);
            edit.commit();
        }));
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        InternalRuntimeRecord before = a.data.snapshot(); int writes = a.backend.writes;
        assertThrows(IllegalArgumentException.class, () -> a.call(() -> a.ledger.recordTaskFailure("capacity_rejected", now, () -> true)));
        assertEquals(before, a.backend.read()); assertEquals(before, a.data.snapshot()); assertEquals(writes, a.backend.writes);
        assertSame(stamp, a.stats().observedSessionStamp()); assertTrue(stamp.isCurrent()); assertTrue(a.persistenceFailures.isEmpty());
        a.run(() -> a.owner.transaction(() -> { a.stats().recordPopupImpression(); }));
        assertEquals(1, a.stats().load().getPopupImpressions());
        a.run(() -> a.owner.finishSession(session.identity, System.currentTimeMillis()));
        assertNull(a.stats().observedSessionStamp()); assertTrue(a.persistenceFailures.isEmpty());
    }
    @Test public void cleanRuntimeAndEditorRollbackRestoresDedupMemoryWithoutInventingNewStamp() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); StatsRepository.NativeSession session = session(now - 1000, "UTC"); a.begin(session);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); InternalRuntimeRecord before = a.data.snapshot();
        AtomicBoolean publication = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> a.run(() -> a.owner.transaction(() -> {
            assertEquals(1, a.stats().recordTrackIds(List.of(41), null));
            a.owner.afterCommit(() -> publication.set(true));
            throw new IllegalArgumentException("Rejected after staging");
        })));
        assertEquals(before, a.data.snapshot()); assertSame(stamp, a.stats().observedSessionStamp()); assertTrue(stamp.isCurrent());
        assertFalse(publication.get()); assertEquals(0, a.stats().load().getCurrentSessionBlocks());
        assertThrows(IllegalArgumentException.class, () -> a.run(() -> a.owner.transaction(() -> {
            a.stats().recordCensorStyleTried("pixelate"); throw new IllegalArgumentException("Rejected editor update");
        })));
        assertEquals(before, a.data.snapshot()); assertSame(stamp, a.stats().observedSessionStamp()); assertTrue(a.persistenceFailures.isEmpty());
        a.run(() -> a.owner.transaction(() -> assertEquals(1, a.stats().recordTrackIds(List.of(41), null))));
        assertEquals(1, a.stats().load().getTotalBlocks());
        a.run(() -> a.owner.finishSession(session.identity, System.currentTimeMillis())); assertNull(a.stats().observedSessionStamp());
    }
    @Test public void genericPersistenceFailureExplicitlySignalsActiveRuntimeAndCannotClaimCleanDrain() throws Exception {
        for (InternalRuntimePreferences.Outcome outcome : List.of(InternalRuntimePreferences.Outcome.NOT_COMMITTED, InternalRuntimePreferences.Outcome.UNCERTAIN)) {
            Fixture a = fresh(); long now = System.currentTimeMillis(); StatsRepository.NativeSession session = session(now - 1000, "UTC"); a.begin(session);
            a.run(() -> a.owner.transaction(() -> a.rules.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, 1), 1000, 2000, 10, 7, 1, 30)));
            StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); a.backend.outcome = outcome;
            InternalRuntimePreferences.PersistenceException failed = assertThrows(InternalRuntimePreferences.PersistenceException.class,
                    () -> a.call(() -> a.ledger.recordTaskFailure("uncertain_task", now, () -> true)));
            assertEquals(List.of(failed), a.persistenceFailures); assertFalse(stamp.isCurrent()); assertNull(a.stats().observedSessionStamp());
            assertFalse(a.data.isAvailable());
            assertThrows(IllegalStateException.class, () -> a.run(() -> a.owner.transaction(() -> { a.stats().recordPopupImpression(); })));
            assertThrows(IllegalStateException.class, () -> a.run(() -> a.owner.finishSession(session.identity, System.currentTimeMillis())));
            assertEquals(1, a.persistenceFailures.size());
        }
    }
    @Test public void postAckPublicationFailureNeverRestoresAStaleMemorySnapshot() throws Exception {
        Fixture a = fresh(); long now = System.currentTimeMillis(); a.begin(session(now - 1000, "UTC"));
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        InternalRuntimePreferences.PersistenceException failed = assertThrows(InternalRuntimePreferences.PersistenceException.class,
                () -> a.run(() -> a.owner.transaction(() -> {
                    a.stats().recordCensorStyleTried("pixelate");
                    a.owner.afterCommit(() -> { throw new IllegalStateException("Publication unavailable"); });
                })));
        assertEquals(InternalRuntimePreferences.Outcome.COMMITTED, failed.outcome);
        assertEquals(List.of(failed), a.persistenceFailures); assertFalse(stamp.isCurrent()); assertNull(a.stats().observedSessionStamp());
        assertTrue(((Set<?>) a.backend.read().namespaces().get("statistics").get("censor_styles_tried")).contains("pixelate"));
        assertFalse(a.data.isAvailable());
    }

    @Test public void legacyIdsUseExactOwnerDedupAndCapturedCalendarWithoutDeviceWrites() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, ?> physical = app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        StatsRepository.SessionStamp device = StatsRepository.currentSessionStamp();
        Fixture a = fresh(), b = fresh(); long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        StatsRepository.NativeSession origin = session(at, "UTC"); a.begin(origin); b.begin(origin);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp();
        OwnerObservation observed = new OwnerObservation(at + 1000, "Pacific/Kiritimati");
        Set<String> all = Set.of("genitals_female", "genitals_male", "breasts", "buttocks", "anus", "face", "belly",
                "male_chest", "feet", "armpits", "genitals_covered", "breasts_covered", "buttocks_covered",
                "anus_covered", "belly_covered", "feet_covered", "armpits_covered");
        assertThrows(SecurityException.class, () -> b.call(() -> b.owner.recordLegacyTrackIds(stamp, List.of(1), all, observed)));
        assertEquals(2, (int) a.call(() -> a.owner.recordLegacyTrackIds(stamp, java.util.Arrays.asList(1, 1, null, 2), all, observed)));
        long revision = a.data.revision();
        assertEquals(0, (int) a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(1, 2), all, observed)));
        assertEquals(revision, a.data.revision());
        assertEquals(1, (int) a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(2, 3), Set.of("face"), observed)));
        assertEquals(3, a.stats().load().getTotalBlocks()); assertEquals(0, b.stats().load().getTotalBlocks());
        assertEquals(2, a.data.preferences("statistics").getLong("all_categories_censors", 0));
        assertTrue(a.data.preferences("statistics").getStringSet("active_date_clocks_v1", Set.of())
                .contains("Pacific/Kiritimati;2024-04-10"));
        assertEquals(Long.valueOf(3), a.snapshot(at + TimeUnit.DAYS.toMillis(1)).days().get(observed.day()).get("censors"));
        a.sessionAllowed = false;
        assertThrows(SecurityException.class, () -> a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(4), all, observed)));
        a.sessionAllowed = true; a.run(() -> a.owner.finishSession(origin.identity, at + 2000));
        assertThrows(SecurityException.class, () -> a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(4), all, observed)));
        a.begin(session(at + 3000, "UTC")); StatsRepository.SessionStamp next = a.stats().observedSessionStamp();
        assertThrows(SecurityException.class, () -> a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(4), all, observed)));
        assertEquals(1, (int) a.call(() -> a.owner.recordLegacyTrackIds(next, List.of(1), all, new OwnerObservation(at + 4000, "UTC"))));
        assertSame(device, StatsRepository.currentSessionStamp());
        assertEquals(physical, app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll());
    }

    @Test public void legacyIdsRollbackExactDedupAndReturnOnlyAfterOuterAck() throws Exception {
        Fixture a = fresh(); long at = System.currentTimeMillis(); a.begin(session(at, "UTC"));
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); OwnerObservation observed = new OwnerObservation(at + 1, "UTC");
        InternalRuntimeRecord before = a.data.snapshot();
        assertThrows(IllegalArgumentException.class, () -> a.run(() -> a.owner.transaction(() -> {
            assertEquals(1, a.stats().recordLegacyTrackIds(stamp, List.of(41), Set.of(), observed));
            throw new IllegalArgumentException("clean pre-CAS failure");
        })));
        assertEquals(before, a.data.snapshot()); assertSame(stamp, a.stats().observedSessionStamp()); assertTrue(stamp.isCurrent());
        assertThrows(IllegalStateException.class, () -> a.run(() -> a.owner.transaction(
                () -> a.owner.recordLegacyTrackIds(stamp, List.of(41), Set.of(), observed))));
        AtomicBoolean returned = new AtomicBoolean(), checked = new AtomicBoolean();
        a.backend.beforeWrite = () -> {
            assertFalse(returned.get());
            try { assertEquals(before, a.backend.actual.read()); }
            catch (Exception failure) { throw new AssertionError(failure); }
            checked.set(true);
        };
        assertEquals(1, (int) a.call(() -> a.owner.recordLegacyTrackIds(stamp, List.of(41), Set.of(), observed)));
        returned.set(true); a.backend.beforeWrite = () -> { }; assertTrue(checked.get());
        assertEquals(1, a.stats().load().getTotalBlocks()); assertTrue(a.persistenceFailures.isEmpty());
    }

    @Test public void legacyIdsUnacknowledgedWriteCannotReturnCountOrBorrowStamp() throws Exception {
        for (InternalRuntimePreferences.Outcome outcome : List.of(InternalRuntimePreferences.Outcome.NOT_COMMITTED, InternalRuntimePreferences.Outcome.UNCERTAIN)) {
            Fixture a = fresh(); long at = System.currentTimeMillis(); a.begin(session(at, "UTC"));
            StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); InternalRuntimeRecord before = a.backend.read();
            a.backend.outcome = outcome;
            assertThrows(InternalRuntimePreferences.PersistenceException.class, () -> a.call(
                    () -> a.owner.recordLegacyTrackIds(stamp, List.of(1), Set.of(), new OwnerObservation(at + 1, "UTC"))));
            assertEquals(before, a.backend.read()); assertFalse(stamp.isCurrent()); assertEquals(1, a.persistenceFailures.size());
        }
    }

    @Test public void censoredTapUsesPinnedRulesCurrencyAndEventDateWithNoDeviceEffects() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, ?> physical = app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        StatsRepository.SessionStamp device = StatsRepository.currentSessionStamp();
        Fixture a = fresh(), b = fresh(); long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        StatsRepository.NativeSession origin = session(at, "UTC"); a.begin(origin); b.begin(origin);
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); OwnerObservation observed = new OwnerObservation(at + 1, "Pacific/Kiritimati");
        a.run(() -> a.owner.transaction(() -> a.data.preferences("assessed_ledger").edit().putInt("detection_remainder_v1", 3).commit()));
        com.subhub.app.penance.PenanceRuleSettings pinned = capturedTapRules("EUR", 75);
        OwnerPenanceLedger ledger = a.owner.ledger(() -> pinned, a.censorEnabled::get);
        a.configure("USD", 400, 500, 1000, 2000, 5); // New mutable configuration cannot replace the captured event rules.
        assertEquals(OwnerPenanceLedger.Status.REJECTED, b.call(() -> b.ledger.recordSourceCensoredTapResult(observed, stamp)).status);
        a.censorEnabled.set(false);
        assertEquals(OwnerPenanceLedger.Status.REJECTED, a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)).status);
        a.censorEnabled.set(true);
        assertEquals(OwnerPenanceLedger.Status.REJECTED, a.call(() -> a.ledger.recordSourceCensoredTapResult(observed, stamp)).status); // Tap rule disabled.
        OwnerPenanceLedger.Result receipt = a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp));
        assertEquals(OwnerPenanceLedger.Status.ACKNOWLEDGED, receipt.status); assertEquals(75, receipt.chargedCents); assertEquals(3, receipt.batchRemainder);
        assertEquals(30, a.call(() -> a.owner.ledger(() -> capturedTapRules("USD", 30), () -> true)
                .recordSourceCensoredTapResult(observed, stamp)).chargedCents);
        assertEquals(75, a.stats().tributeCents("EUR", false)); assertEquals(30, a.stats().tributeCents("USD", false));
        assertEquals(Long.valueOf(75), a.snapshot(at + TimeUnit.DAYS.toMillis(1)).days().get(observed.day()).get("assessed_EUR"));
        assertEquals(PenanceInfraction.CENSORED_TAP, ledger.retainedEvents().get(0).getInfraction());
        assertEquals(observed.at, ledger.retainedEvents().get(0).getCreatedAtMillis());
        a.sessionAllowed = false;
        assertThrows(SecurityException.class, () -> a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)));
        a.sessionAllowed = true; a.run(() -> a.owner.finishSession(origin.identity, at + 1000));
        assertEquals(OwnerPenanceLedger.Status.REJECTED, a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)).status);
        a.close(); Fixture reopened = reopen(a.scope);
        assertEquals(2, reopened.ledger.retainedEvents().size());
        assertEquals(75, reopened.stats().tributeCents("EUR", false)); assertEquals(30, reopened.stats().tributeCents("USD", false));
        assertEquals(0, b.stats().tributeCents("EUR", false)); assertSame(device, StatsRepository.currentSessionStamp());
        assertEquals(physical, app.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE).getAll());
    }

    @Test public void censoredTapLedgerAndStatsRollbackTogetherAndUncertainAckFences() throws Exception {
        Fixture a = fresh(); long at = System.currentTimeMillis(); a.begin(session(at, "UTC"));
        StatsRepository.SessionStamp stamp = a.stats().observedSessionStamp(); OwnerObservation observed = new OwnerObservation(at + 1, "UTC");
        OwnerPenanceLedger ledger = a.owner.ledger(() -> capturedTapRules("EUR", 75), () -> true);
        InternalRuntimeRecord before = a.data.snapshot(); a.failAfterLedgerStage = true;
        assertThrows(SecurityException.class, () -> a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)));
        assertTrue(a.sawStagedLedger); a.failAfterLedgerStage = false;
        assertEquals(before, a.data.snapshot()); assertSame(stamp, a.stats().observedSessionStamp()); assertTrue(stamp.isCurrent());
        assertTrue(a.persistenceFailures.isEmpty());
        assertEquals(75, a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)).chargedCents);
        before = a.backend.read(); a.backend.outcome = InternalRuntimePreferences.Outcome.UNCERTAIN;
        assertThrows(InternalRuntimePreferences.PersistenceException.class, () -> a.call(() -> ledger.recordSourceCensoredTapResult(observed, stamp)));
        assertEquals(before, a.backend.read()); assertFalse(stamp.isCurrent()); assertEquals(1, a.persistenceFailures.size());
    }

    private static com.subhub.app.penance.PenanceRuleSettings capturedTapRules(String currency, int cents) {
        return new com.subhub.app.penance.PenanceRuleSettings() {
            @Override public String getCurrency() { return currency; }
            @Override public String money(long amount) { throw new UnsupportedOperationException(); }
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isInfractionEnabled(PenanceInfraction rule) { return rule == PenanceInfraction.CENSORED_TAP; }
            @Override public int getInfractionCents(PenanceInfraction rule) { return cents; }
            @Override public int getDailyCapCents() { return 1000; }
            @Override public int getWeeklyCapCents() { return 2000; }
            @Override public int getMercyMinutes() { return 10; }
            @Override public int getDwellSeconds() { return 7; }
            @Override public int getDetectionBatch() { return 5; }
            @Override public int getTamperCooldownMinutes() { return 30; }
            @Override public void disableInfraction(PenanceInfraction rule) { throw new UnsupportedOperationException(); }
            @Override public void configure(boolean enabled, Map<PenanceInfraction, Integer> costs,
                    int daily, int weekly, int mercy, int dwell, int batch, int cooldown) { throw new UnsupportedOperationException(); }
        };
    }

    private Fixture fresh() throws Exception {
        Path parent = Files.createTempDirectory(root, "owner-");
        InternalStorageScope scope = new InternalStorageScope(Files.createDirectory(parent.resolve(InternalStorageScope.TARGET_ID)));
        Fixture fixture = new Fixture(scope, FileInternalRuntimeBackend.provisionNew(scope)); fixtures.add(fixture); return fixture;
    }
    private Fixture reopen(InternalStorageScope scope) throws Exception {
        Fixture fixture = new Fixture(scope, FileInternalRuntimeBackend.openExisting(scope)); fixtures.add(fixture); return fixture;
    }
    /** These fixtures configure no feature policies, commitments, payment credentials or paid receipts. */
    private static AchievementManager.OwnerFacts noConfiguredFeatures() {
        return new AchievementManager.OwnerFacts() {
            @Override public boolean isAccessible() { return true; }
            @Override public long current(String id) {
                switch (id) {
                    case "app_mode_guardian": case "app_assignment_curator": case "limits_setter":
                    case "limits_dual_guard": case "pact_sealed": case "pact_long_haul":
                    case "hardcore_guardian": case "text_filter_enabled": case "text_filter_catalog":
                    case "wallet_keeper": case "tribute_rulesmith": case "paypal_vault": case "paid_pause":
                    case "wallet_paid_10": case "wallet_paid_100": case "wallet_paid_500": case "wallet_paid_1000":
                        return 0;
                    default: throw new AssertionError("Fixture has no explicit fact for " + id);
                }
            }
        };
    }
    private static StatsRepository.NativeSession session(long at, String zone) {
        return new StatsRepository.NativeSession(UUID.randomUUID().toString(), at, zone);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class Fixture {
        final InternalStorageScope scope;
        final ForwardingBackend backend;
        final ThreadLocal<Boolean> admitted = ThreadLocal.withInitial(() -> false);
        final List<Throwable> persistenceFailures = new ArrayList<>();
        final InternalRuntimePreferences data;
        final OwnerStatisticsStore owner;
        final InternalPenanceRules rules;
        final AtomicBoolean censorEnabled = new AtomicBoolean(true);
        final OwnerPenanceLedger ledger;
        boolean allowed = true, sessionAllowed = true, failAfterLedgerStage, sawStagedLedger, closed;
        Fixture(InternalStorageScope scope, FileInternalRuntimeBackend backend) {
            this.scope = scope; this.backend = new ForwardingBackend(backend);
            data = InternalRuntimePreferences.open(this.backend, operation -> { requireAdmission(); operation.run(); });
            owner = new OwnerStatisticsStore(data, this::requireAdmission, this::requireSessionAdmission, persistenceFailures::add);
            rules = new InternalPenanceRules(data.preferences("rules"));
            ledger = owner.ledger(() -> rules, censorEnabled::get);
        }
        StatsRepository stats() { return owner.repository(); }
        OwnerStatisticsStore.Snapshot snapshot(long now) { return call(() -> owner.snapshot(now)); }
        void begin(StatsRepository.NativeSession session) { run(() -> owner.beginSession(session)); }
        void configure(String currency, int detection, int dwell, int daily, int weekly, int batch) {
            run(() -> owner.transaction(() -> {
                data.preferences("rules").edit().putString("wallet_currency", currency).apply();
                rules.configure(true, Map.of(PenanceInfraction.NEW_DETECTION, detection, PenanceInfraction.CENSORED_DWELL, dwell),
                        daily, weekly, 10, 7, batch, 30);
            }));
        }
        void requireAdmission() {
            if (!allowed || !admitted.get()) throw new SecurityException("No admitted fixture owner writer");
            if (failAfterLedgerStage && data.preferences("assessed_ledger").contains("assessed_events_v1")) {
                sawStagedLedger = true; throw new SecurityException("Injected failure between ledger and statistic staging");
            }
        }
        void requireSessionAdmission() {
            requireAdmission();
            if (!sessionAllowed) throw new SecurityException("No exact admitted fixture runtime writer");
        }
        void run(Runnable action) { call(() -> { action.run(); return null; }); }
        <T> T call(Supplier<T> action) {
            if (admitted.get()) throw new AssertionError("Unexpected fixture reentrancy");
            admitted.set(true); try { return action.get(); } finally { admitted.remove(); }
        }
        void close() {
            if (closed) return;
            allowed = true; failAfterLedgerStage = false;
            owner.fence(); // Simulate terminal revocation without inventing or deleting persisted facts.
            run(owner::close); closed = true;
        }
    }

    /** Real file backend; fault injection changes only the named transaction's final ACK. */
    private static final class ForwardingBackend implements InternalRuntimePreferences.Backend {
        final FileInternalRuntimeBackend actual;
        Runnable beforeWrite = () -> { };
        InternalRuntimePreferences.Outcome outcome = InternalRuntimePreferences.Outcome.COMMITTED;
        InternalRuntimeRecord candidate;
        int writes;
        ForwardingBackend(FileInternalRuntimeBackend actual) { this.actual = actual; }
        @Override public InternalRuntimeRecord read() throws Exception { return actual.read(); }
        @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) throws Exception {
            candidate = next; writes++; beforeWrite.run();
            return outcome == InternalRuntimePreferences.Outcome.COMMITTED ? actual.compareAndSet(expected, next) : outcome;
        }
        @Override public void close() { actual.close(); }
    }
}
