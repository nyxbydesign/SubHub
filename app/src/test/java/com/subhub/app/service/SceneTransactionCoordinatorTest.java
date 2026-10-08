package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SceneTransactionCoordinatorTest {
    @Test public void settledFastThenQualityCommitsOneFusedSnapshot() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(1);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING,
                coordinator.fastReady(key, List.of("fast"), 1_100L).status());
        SceneTransactionCoordinator.Transition<String> fused =
                coordinator.qualityReady(key, List.of("quality"), 1_200L);

        assertEquals(SceneTransactionCoordinator.Status.COMMITTED, fused.status());
        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                fused.commit().kind());
        assertEquals(List.of("fast"), fused.commit().fastObservations());
        assertEquals(List.of("quality"), fused.commit().qualityObservations());
        assertTrue(fused.commit().includesQuality());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.deadline(key, 1_300L).status());
    }

    @Test public void settledQualityThenFastAlsoCommitsOneFusedSnapshot() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(2);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING,
                coordinator.qualityReady(key, List.of("quality"), 1_100L).status());
        SceneTransactionCoordinator.Transition<String> fused =
                coordinator.fastReady(key, List.of("fast"), 1_200L);

        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                fused.commit().kind());
        assertTrue(coordinator.isPresentationCurrent(fused.commit()));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.qualityReady(key, List.of("later"), 1_201L).status());
    }

    @Test public void deadlineCommitsFastOnceAndPermanentlyDropsLateQuality() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(3);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        coordinator.fastReady(key, List.of("fast"), 1_100L);

        SceneTransactionCoordinator.Transition<String> deadline = coordinator.deadline(key, 1_300L);

        assertEquals(SceneTransactionCoordinator.CommitKind.DEADLINE_FAST,
                deadline.commit().kind());
        assertEquals(List.of("fast"), deadline.commit().fastObservations());
        assertTrue(deadline.commit().qualityObservations().isEmpty());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.qualityReady(key, List.of("too-late"), 1_301L).status());
    }

    @Test public void deadlineBeforeFastClosesQualityButDoesNotSacrificeSafetyCoverage() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(4);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING_FOR_FAST,
                coordinator.deadline(key, 1_300L).status());
        assertEquals(SceneTransactionCoordinator.Status.WAITING_FOR_FAST,
                coordinator.qualityReady(key, List.of("too-late"), 1_301L).status());
        SceneTransactionCoordinator.Transition<String> fast =
                coordinator.fastReady(key, List.of("safe-fast"), 1_350L);

        assertEquals(SceneTransactionCoordinator.CommitKind.DEADLINE_FAST,
                fast.commit().kind());
        assertEquals(List.of("safe-fast"), fast.commit().fastObservations());
        assertTrue(fast.commit().qualityObservations().isEmpty());
    }

    @Test public void activeMotionNeverWaitsForOrAcceptsQuality() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(5);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.ACTIVE_FAST, true, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.DROPPED_POLICY,
                coordinator.qualityReady(key, List.of("quality"), 1_050L).status());
        SceneTransactionCoordinator.Transition<String> fast =
                coordinator.fastReady(key, List.of("fast"), 1_060L);

        assertEquals(SceneTransactionCoordinator.CommitKind.ACTIVE_FAST, fast.commit().kind());
        assertFalse(fast.commit().includesQuality());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.qualityReady(key, List.of("late"), 1_070L).status());
    }

    @Test public void settledSceneWithoutAQualityEngineCommitsFastImmediately() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(6);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_FAST_ONLY, 1_300L);

        SceneTransactionCoordinator.Transition<String> fast =
                coordinator.fastReady(key, List.of("fast"), 1_050L);

        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FAST_ONLY,
                fast.commit().kind());
    }

    @Test public void aNewExactSceneSupersedesEveryCallbackFromTheOldScene() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey oldKey = key(7);
        SceneTransactionCoordinator.SceneKey newKey = key(8);
        coordinator.begin(oldKey, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        coordinator.fastReady(oldKey, List.of("old-fast"), 1_100L);

        SceneTransactionCoordinator.BeginResult replacement = coordinator.begin(
                newKey, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_301L);

        assertEquals(oldKey, replacement.superseded());
        assertEquals(newKey, replacement.started());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_STALE,
                coordinator.qualityReady(oldKey, List.of("old-quality"), 1_200L).status());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_STALE,
                coordinator.deadline(oldKey, 1_300L).status());
        coordinator.fastReady(newKey, List.of("new-fast"), 1_150L);
        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                coordinator.qualityReady(newKey, List.of("new-quality"), 1_200L)
                        .commit().kind());
    }

    @Test public void invalidationClosesTheExactSceneAndPresentationToken() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(9);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        coordinator.fastReady(key, List.of("fast"), 1_100L);
        SceneTransactionCoordinator.Commit<String> commit =
                coordinator.qualityReady(key, List.of("quality"), 1_200L).commit();
        assertTrue(coordinator.isPresentationCurrent(commit));

        assertTrue(coordinator.invalidate(key));
        assertFalse(coordinator.isPresentationCurrent(commit));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.fastReady(key, List.of("late"), 1_210L).status());
        assertFalse(coordinator.invalidate(key));
    }

    @Test public void invalidateCurrentIsIdempotentAndReportsItsKey() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(10);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertEquals(key, coordinator.invalidateCurrent());
        assertNull(coordinator.invalidateCurrent());
    }

    @Test public void duplicateLaneCallbacksCannotReplaceAnObservation() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(11);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        coordinator.fastReady(key, List.of("first-fast"), 1_100L);
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_DUPLICATE,
                coordinator.fastReady(key, List.of("second-fast"), 1_101L).status());
        coordinator.qualityReady(key, List.of("first-quality"), 1_200L);

        // The first quality callback committed the scene; every later callback is closed.
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.qualityReady(key, List.of("second-quality"), 1_201L).status());
    }

    @Test public void emptyObservationIsReadyRatherThanMissing() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(12);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING,
                coordinator.fastReady(key, Collections.emptyList(), 1_100L).status());
        SceneTransactionCoordinator.Transition<String> fused =
                coordinator.qualityReady(key, List.of("quality"), 1_200L);

        assertTrue(fused.committed());
        assertTrue(fused.commit().fastObservations().isEmpty());
        assertEquals(List.of("quality"), fused.commit().qualityObservations());
    }

    @Test public void deadlineAndQualityRaceHasOneCommitInEitherLinearization() {
        SceneTransactionCoordinator<String> qualityWins = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey qualityKey = key(13);
        qualityWins.begin(
                qualityKey, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        qualityWins.fastReady(qualityKey, List.of("fast"), 1_100L);
        SceneTransactionCoordinator.Transition<String> fused =
                qualityWins.qualityReady(qualityKey, List.of("quality"), 1_299L);
        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                fused.commit().kind());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                qualityWins.deadline(qualityKey, 1_300L).status());

        SceneTransactionCoordinator<String> deadlineWins = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey deadlineKey = key(14);
        deadlineWins.begin(
                deadlineKey, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        deadlineWins.fastReady(deadlineKey, List.of("fast"), 1_100L);
        SceneTransactionCoordinator.Transition<String> fastOnly =
                deadlineWins.deadline(deadlineKey, 1_300L);
        assertEquals(SceneTransactionCoordinator.CommitKind.DEADLINE_FAST,
                fastOnly.commit().kind());
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                deadlineWins.qualityReady(deadlineKey, List.of("quality"), 1_300L).status());
    }

    @Test public void concurrentDeadlineAndQualityRaceCommitsExactlyOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                SceneTransactionCoordinator<String> coordinator =
                        new SceneTransactionCoordinator<>();
                SceneTransactionCoordinator.SceneKey key = key(100 + iteration);
                coordinator.begin(
                        key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC,
                        true, 1_300L);
                coordinator.fastReady(key, List.of("fast"), 1_100L);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger commits = new AtomicInteger();
                workers.execute(() -> {
                    await(start);
                    if (coordinator.qualityReady(
                            key, List.of("quality"), 1_299L).committed()) {
                        commits.incrementAndGet();
                    }
                    done.countDown();
                });
                workers.execute(() -> {
                    await(start);
                    if (coordinator.deadline(key, 1_300L).committed()) {
                        commits.incrementAndGet();
                    }
                    done.countDown();
                });
                start.countDown();
                assertTrue(done.await(2L, TimeUnit.SECONDS));
                assertEquals(1, commits.get());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void commitListsAreDefensiveAndImmutable() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(15);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        List<String> fastSource = new ArrayList<>(Arrays.asList("fast-a", "fast-b"));
        List<String> qualitySource = new ArrayList<>(List.of("quality"));
        coordinator.fastReady(key, fastSource, 1_100L);
        SceneTransactionCoordinator.Commit<String> commit =
                coordinator.qualityReady(key, qualitySource, 1_200L).commit();

        fastSource.clear();
        qualitySource.clear();
        assertEquals(List.of("fast-a", "fast-b"), commit.fastObservations());
        assertEquals(List.of("quality"), commit.qualityObservations());
        assertThrows(UnsupportedOperationException.class,
                () -> commit.fastObservations().add("mutation"));
    }

    @Test public void anEarlyDeadlineCallbackCannotCloseQuality() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(16);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        coordinator.fastReady(key, List.of("fast"), 1_100L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING,
                coordinator.deadline(key, 1_299L).status());
        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                coordinator.qualityReady(key, List.of("quality"), 1_299L).commit().kind());
    }

    @Test public void aSceneKeyCannotBeRestartedWhileItIsCurrent() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(17);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);

        assertThrows(IllegalStateException.class, () -> coordinator.begin(
                key, SceneTransactionCoordinator.Mode.ACTIVE_FAST, false, 1_300L));
    }

    @Test public void integrationApiUsesAnInjectedMonotonicClock() {
        AtomicLong now = new AtomicLong(1_100L);
        SceneTransactionCoordinator<String> coordinator =
                new SceneTransactionCoordinator<>(now::get);
        SceneTransactionCoordinator.SceneKey key = key(18);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, 1_300L);

        assertEquals(SceneTransactionCoordinator.Status.WAITING,
                coordinator.submitFast(key, List.of("fast")).status());
        now.set(1_200L);
        SceneTransactionCoordinator.Transition<String> fused =
                coordinator.submitQuality(key, List.of("quality"));

        assertTrue(fused.optionalCommit().isPresent());
        assertEquals(SceneTransactionCoordinator.CommitKind.SETTLED_FUSED,
                fused.optionalCommit().get().kind());
        assertEquals(key, coordinator.invalidate("motion-generation"));
        assertFalse(coordinator.isPresentationCurrent(fused.commit()));
    }

    @Test public void continuousFastSurvivesReversalsBeforeAndAfterCommit() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(90);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.SETTLED_FAST_ONLY, false, 1_300L);
        for (int reversal = 0; reversal < 8; reversal++) {
            assertEquals(null, coordinator.invalidateForMotion(true));
        }
        SceneTransactionCoordinator.Transition<String> ready =
                coordinator.fastReady(key, List.of("reprojectable-fast"), 1_200L);
        assertTrue(ready.committed());
        assertEquals(null, coordinator.invalidateForMotion(true));
        assertTrue(coordinator.isPresentationCurrent(ready.commit()));
        // A real navigation fence still rejects an already queued publication.
        assertEquals(key, coordinator.invalidate("document-changed"));
        assertFalse(coordinator.isPresentationCurrent(ready.commit()));
    }

    @Test public void motionRetentionNeverResurrectsASupersededFastScene() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey old = key(91);
        coordinator.begin(old, SceneTransactionCoordinator.Mode.ACTIVE_FAST, false, 1_300L);
        SceneTransactionCoordinator.Commit<String> commit =
                coordinator.fastReady(old, List.of("old"), 1_200L).commit();
        coordinator.begin(key(92), SceneTransactionCoordinator.Mode.ACTIVE_FAST, false, 1_300L);
        coordinator.invalidateForMotion(true);
        assertFalse(coordinator.isPresentationCurrent(commit));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_STALE,
                coordinator.fastReady(old, List.of("old"), 1_250L).status());
    }

    @Test public void motionStillClosesNonReprojectableAndAtomicScenes() {
        SceneTransactionCoordinator<String> coordinator = new SceneTransactionCoordinator<>();
        SceneTransactionCoordinator.SceneKey key = key(93);
        coordinator.begin(key, SceneTransactionCoordinator.Mode.ACTIVE_FAST, false, 1_300L);
        assertEquals(key, coordinator.invalidateForMotion(false));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.fastReady(key, List.of("stale"), 1_200L).status());
        SceneTransactionCoordinator.SceneKey atomic = key(94);
        coordinator.begin(atomic, SceneTransactionCoordinator.Mode.SETTLED_ATOMIC, true, 1_300L);
        assertEquals(atomic, coordinator.invalidateForMotion(true));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator.qualityReady(atomic, List.of("stale-quality"), 1_200L).status());
    }

    private static SceneTransactionCoordinator.SceneKey key(long sequence) {
        return new SceneTransactionCoordinator.SceneKey(4L, sequence, 7L, 1_000L);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
