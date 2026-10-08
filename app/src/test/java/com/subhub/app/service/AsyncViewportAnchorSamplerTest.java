package com.subhub.app.service;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class AsyncViewportAnchorSamplerTest {
    @Test public void discoveryStopsAfterOneOverBudgetNodeAndReleasesAllHandles() {
        Fixture f = new Fixture(); f.source.readCost = 40;
        f.sampler.start(); f.step();
        assertEquals(1, f.source.reads);
        assertEquals(3, f.source.closes);
        assertEquals(1, f.sampler.stats().discoveryBudgetDrops);
        assertEquals(40, f.sampler.stats().maxNodeMs);
        assertEquals(0, f.sampler.stats().invalidDrops);
        f.source.readCost = 1; f.step(); f.step(); f.step();
        assertEquals(1, f.results.size());
    }

    @Test public void distinguishesSingleBlockingCallFromAccumulatedGroupCost() {
        Fixture f = new Fixture(); f.arm();
        f.source.readCost = 7; f.step();
        assertEquals(1, f.sampler.stats().aggregateBudgetDrops);
        assertEquals(0, f.sampler.stats().singleNodeBudgetDrops);
        f.source.readCost = 40; f.step();
        assertEquals(1, f.sampler.stats().singleNodeBudgetDrops);
        assertEquals(2, f.sampler.stats().slowDrops);
        assertEquals(40, f.sampler.stats().maxNodeMs);
        assertEquals(0, f.source.closes);
    }

    @Test public void rejectionDiagnosticsSeparateClippingFromTranslationDisagreement() {
        Fixture clipped = new Fixture(); clipped.arm(); clipped.source.clip = true; clipped.step();
        assertEquals("RESIZED_OR_CLIPPED:1", clipped.sampler.stats().rejectionCounts);
        Fixture inconsistent = new Fixture(); inconsistent.arm();
        inconsistent.source.skew = true; inconsistent.step();
        assertEquals("INCONSISTENT_TRANSLATION:1", inconsistent.sampler.stats().rejectionCounts);
        assertTrue(clipped.results.isEmpty());
        assertTrue(inconsistent.results.isEmpty());
    }

    @Test public void rejectionDiagnosticsNeverIncludeProviderExceptionMessages() {
        Fixture f = new Fixture(); f.arm();
        f.source.onRead = () -> { throw new IllegalStateException("private provider text"); };
        f.step();
        assertEquals("UNCLASSIFIED_EXCEPTION:1", f.sampler.stats().rejectionCounts);
        assertEquals(1, f.sampler.stats().invalidDrops);
        for (AsyncViewportAnchorSampler.ReadFailure reason : AsyncViewportAnchorSampler.ReadFailure.values()) {
            Fixture typed = new Fixture(); typed.arm();
            typed.source.onRead = () -> { throw new AsyncViewportAnchorSampler.AnchorReadException(reason); };
            typed.step();
            assertEquals(reason.name() + ":1", typed.sampler.stats().rejectionCounts);
            assertEquals(3, typed.source.closes);
        }
    }
    @Test public void repeatedAbsoluteReadsDoNotDoubleApplyEvents() {
        Fixture f = new Fixture(); f.arm();
        f.source.dy = -25; f.step();
        assertEquals(-25, f.results.get(0).measuredY, 0);
        f.source.cameraY = -25; f.step();
        assertEquals(-25, f.results.get(1).measuredY, 0);
        f.source.dy = 15; f.source.cameraY = 15; f.step();
        assertEquals(15, f.results.get(2).measuredY, 0);
        assertTrue(f.worker.maximumQueued <= 1);
    }

    @Test public void droppedSlowReadHasNoPublicationAndNextAbsoluteReadCatchesUp() {
        Fixture f = new Fixture(); f.arm();
        f.step();
        long baseline = f.results.get(0).baselineIdentity;
        f.results.clear();
        f.source.readCost = 7; f.source.dy = -20; f.step();
        assertTrue(f.results.isEmpty());
        assertEquals(1, f.sampler.stats().slowDrops);
        assertEquals(16, f.worker.lastDelay);
        f.source.lastMotion = f.time; // Idle-only discovery would refuse this recovery.
        f.source.readCost = 1; f.source.dy = -120; f.source.cameraY = -120; f.step();
        assertEquals(-120, f.results.get(0).measuredY, 0);
        assertEquals(baseline, f.results.get(0).baselineIdentity);
        assertEquals(1, f.source.acquisitions);
        assertEquals(0, f.source.closes);
        assertTrue(f.time - f.source.lastMotion < 200);
        assertEquals(21, f.sampler.stats().maxReadMs);
    }

    @Test public void repeatedSlowReadsRetainBaselineWithCappedBackoff() {
        for (int frameInterval : new int[]{8, 16, 33}) {
            Fixture f = new Fixture(); f.source.frameInterval = frameInterval;
            f.arm(); f.source.readCost = 7;
            long expectedDelay = Math.max(16, frameInterval);
            for (int failure = 0; failure < 12; failure++) {
                long priorTime = f.time;
                long priorDelay = f.worker.lastDelay;
                f.source.lastMotion = priorTime;
                f.step();
                assertEquals(expectedDelay, f.worker.lastDelay);
                assertTrue(f.time >= priorTime + priorDelay);
                assertEquals(1, f.worker.jobs.size());
                assertTrue(f.worker.maximumQueued <= 1);
                assertEquals(0, f.source.closes);
                assertEquals(0, f.sampler.stats().resets);
                assertEquals(1, f.source.acquisitions);
                assertTrue(f.results.isEmpty());
                expectedDelay = Math.min(250, expectedDelay * 2);
            }
            assertEquals(12, f.sampler.stats().slowDrops);
            f.source.readCost = 1; f.source.dy = -180;
            // Keep source motion active at read time even after the capped delay.
            f.source.onRead = () -> f.source.lastMotion = f.time;
            f.step();
            assertEquals(-180, f.results.get(0).measuredY, 0);
            f.source.readCost = 7; f.step();
            assertEquals(Math.max(16, frameInterval), f.worker.lastDelay);
        }
    }

    @Test public void invalidGeometryStillDiscardsBaselineAfterSlowReads() {
        Fixture f = new Fixture(); f.arm(); f.source.readCost = 7;
        f.step(); f.step(); f.step();
        f.source.readCost = 1; f.source.clip = true; f.step();
        assertEquals(3, f.source.closes);
        assertEquals(1, f.sampler.stats().resets);
        assertEquals(1, f.sampler.stats().invalidDrops);
        assertTrue(f.results.isEmpty());
        assertEquals(250, f.worker.lastDelay);
    }

    @Test public void sourceChangeDuringReadRejectsWholeResultAndClosesOnWorker() {
        Fixture f = new Fixture(); f.arm();
        f.source.onRead = () -> f.source.epoch++;
        f.step();
        assertTrue(f.results.isEmpty());
        assertEquals(3, f.source.closes);
        assertEquals(1, f.sampler.stats().invalidDrops);
    }

    @Test public void closeInsideBlockedReadDoesNotCloseNodesUntilReadReturns() {
        Fixture f = new Fixture(); f.arm();
        f.source.onRead = () -> {
            f.sampler.close();
            assertEquals(0, f.source.closes);
        };
        f.step();
        assertTrue(f.results.isEmpty());
        assertEquals(3, f.source.closes);
        assertEquals(1, f.worker.shutdowns);
        f.sampler.close();
        assertEquals(3, f.source.closes);
    }

    @Test public void movingDiscoveryCannotDefineAnArbitraryBaseline() {
        Fixture f = new Fixture(); f.sampler.start(); f.step();
        f.source.dy = -12; f.step();
        assertTrue(f.results.isEmpty());
        assertEquals(3, f.source.closes);
    }

    @Test public void noDiscoveryDuringRecentMotionAndNoSourceWorkBeforeWorkerRuns() {
        Fixture f = new Fixture(); f.source.lastMotion = f.time;
        f.sampler.start(); f.sampler.start();
        assertEquals(0, f.source.acquisitions);
        f.step();
        assertEquals(0, f.source.acquisitions);
        assertEquals(1, f.worker.jobs.size());
    }

    @Test public void clippedAnchorInvalidatesInsteadOfSteeringToItsChangedCenter() {
        Fixture f = new Fixture(); f.arm(); f.source.clip = true; f.step();
        assertTrue(f.results.isEmpty());
        assertEquals(3, f.source.closes);
    }

    @Test public void acquisitionFailureRecoversWithoutUnboundedQueuedWork() {
        Fixture f = new Fixture(); f.source.failAcquire = true;
        f.sampler.start(); f.step();
        assertEquals(1, f.sampler.stats().invalidDrops);
        assertEquals(250, f.worker.lastDelay);
        f.source.failAcquire = false; f.step(); f.step();
        f.source.dy = -10; f.step();
        assertEquals(-10, f.results.get(0).measuredY, 0);
        assertTrue(f.worker.maximumQueued <= 1);
    }

    @Test public void closingBeforeStartNeverAcquiresAndShutsDownOnce() {
        Fixture f = new Fixture(); f.sampler.close(); f.sampler.start(); f.step();
        assertEquals(0, f.source.acquisitions);
        assertEquals(1, f.worker.shutdowns);
    }

    @Test public void clusteredLeavesCannotBecomeCameraAnchors() {
        Fixture f = new Fixture(); f.source.clustered = true;
        f.sampler.start(); f.step();
        assertEquals(3, f.source.closes);
        assertTrue(f.results.isEmpty());
    }

    @Test public void slowFirstAnchorSkipsRemainingExpensiveReads() {
        Fixture f = new Fixture(); f.arm(); f.source.readCost = 60;
        int before = f.source.reads; f.step();
        assertEquals(1, f.source.reads - before);
        assertEquals(1, f.sampler.stats().slowDrops);
        assertTrue(f.results.isEmpty());
    }

    @Test public void validConfirmationResetsSlowDropStreak() {
        Fixture f = new Fixture(); f.sampler.start(); f.step();
        f.source.readCost = 7; f.step(); f.step();
        f.source.readCost = 1; f.step(); // Successful confirmation, no publication yet.
        f.source.readCost = 7; f.step();
        assertEquals(0, f.source.closes);
        assertEquals(3, f.sampler.stats().slowDrops);
        assertEquals(16, f.worker.lastDelay);
    }

    @Test public void closeDuringReadDoesNotQueryTornDownSourceAfterward() {
        Fixture f = new Fixture(); f.arm(); int before = f.source.stateReads;
        f.source.onRead = f.sampler::close; f.step();
        assertEquals(before + 1, f.source.stateReads);
        assertEquals(3, f.source.closes);
    }

    @Test public void rejectedCleanupIsVisibleAndRetryable() {
        Fixture f = new Fixture(); f.arm(); f.worker.rejectNextExecute = true;
        try { f.sampler.close(); fail("Expected rejected cleanup to surface"); }
        catch (IllegalStateException expected) { }
        assertEquals(0, f.source.closes);
        f.sampler.close(); f.step();
        assertEquals(3, f.source.closes);
        assertEquals(1, f.worker.shutdowns);
    }

    @Test public void realWorkerCancellationDoesNotWaitForBlockedSourceRead() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(3);
        AtomicInteger reads = new AtomicInteger();
        AsyncViewportAnchorSampler.Worker worker = new AsyncViewportAnchorSampler.Worker() {
            public void execute(Runnable task) { executor.execute(task); }
            public void schedule(Runnable task, long delay) {
                executor.schedule(task, delay, TimeUnit.MILLISECONDS);
            }
            public void shutdown() { executor.shutdown(); }
        };
        AsyncViewportAnchorSampler.Source source = new AsyncViewportAnchorSampler.Source() {
            public AsyncViewportAnchorSampler.State state() {
                return new AsyncViewportAnchorSampler.State(true, 1, 1, 1000, 2000, 0, 0, 0, 16);
            }
            public List<AsyncViewportAnchorSampler.Anchor> acquire(AsyncViewportAnchorSampler.State state) {
                List<AsyncViewportAnchorSampler.Anchor> result = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    int x = index * 200;
                    result.add(new AsyncViewportAnchorSampler.Anchor() {
                        public ViewportAnchorGeometry.Bounds read() {
                            if (reads.incrementAndGet() == 7) {
                                entered.countDown();
                                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { throw new AssertionError(failure); }
                            }
                            return new ViewportAnchorGeometry.Bounds(x, 400, x + 100, 500);
                        }
                        public void close() { cleaned.countDown(); }
                    });
                }
                return result;
            }
        };
        AsyncViewportAnchorSampler sampler = new AsyncViewportAnchorSampler(
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), worker, source,
                (result, state) -> fail("Cancelled read must not publish"));
        try {
            sampler.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            sampler.close(); // Must return while the read remains blocked.
            assertEquals(3, cleaned.getCount());
            release.countDown();
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            sampler.close();
            executor.shutdownNow();
        }
    }

    private static final class Fixture {
        long time = 1000;
        final FakeWorker worker = new FakeWorker(this);
        final FakeSource source = new FakeSource(this);
        final List<ViewportAnchorGeometry.Result> results = new ArrayList<>();
        final AsyncViewportAnchorSampler sampler = new AsyncViewportAnchorSampler(
                () -> time, worker, source, (result, state) -> results.add(result));
        void step() { worker.step(); }
        void arm() { sampler.start(); step(); step(); assertTrue(results.isEmpty()); }
    }
    private static final class FakeWorker implements AsyncViewportAnchorSampler.Worker {
        final Fixture fixture;
        final List<Job> jobs = new ArrayList<>();
        int maximumQueued, shutdowns;
        boolean rejectNextExecute;
        long lastDelay;
        FakeWorker(Fixture fixture) { this.fixture = fixture; }
        public void execute(Runnable action) {
            if (rejectNextExecute) { rejectNextExecute = false; throw new IllegalStateException("rejected"); }
            add(action, 0);
        }
        public void schedule(Runnable action, long delay) { lastDelay = delay; add(action, delay); }
        private void add(Runnable action, long delay) {
            jobs.add(new Job(action, fixture.time + delay));
            maximumQueued = Math.max(maximumQueued, jobs.size());
        }
        public void shutdown() { shutdowns++; jobs.clear(); }
        void step() {
            assertFalse("No queued work", jobs.isEmpty());
            jobs.sort(Comparator.comparingLong(job -> job.at));
            Job job = jobs.remove(0); fixture.time = Math.max(fixture.time, job.at);
            job.action.run();
        }
    }
    private static final class Job {
        final Runnable action; final long at;
        Job(Runnable action, long at) { this.action = action; this.at = at; }
    }
    private static final class FakeSource implements AsyncViewportAnchorSampler.Source {
        final Fixture fixture;
        long epoch = 1, cameraY, lastMotion;
        int dy, readCost = 1, frameInterval = 16, closes, acquisitions, reads, stateReads;
        boolean clip, failAcquire, clustered, skew;
        Runnable onRead;
        FakeSource(Fixture fixture) { this.fixture = fixture; }
        public AsyncViewportAnchorSampler.State state() {
            stateReads++;
            return new AsyncViewportAnchorSampler.State(true, epoch, 42, 1000, 2000,
                    0, cameraY, lastMotion, frameInterval);
        }
        public List<AsyncViewportAnchorSampler.Anchor> acquire(AsyncViewportAnchorSampler.State state) {
            acquisitions++;
            if (failAcquire) throw new IllegalStateException("synthetic source failure");
            return Arrays.asList(anchor(0), anchor(1), anchor(2));
        }
        private AsyncViewportAnchorSampler.Anchor anchor(int index) {
            return new AsyncViewportAnchorSampler.Anchor() {
                boolean released;
                public ViewportAnchorGeometry.Bounds read() {
                    assertFalse(released);
                    reads++;
                    fixture.time += readCost;
                    Runnable action = onRead; onRead = null;
                    if (action != null) action.run();
                    int x = index * (clustered ? 5 : 200);
                    int shift = dy + (skew ? index * 20 : 0);
                    return new ViewportAnchorGeometry.Bounds(x, 400 + shift,
                            x + 100, 500 + shift - (clip && index == 0 ? 20 : 0));
                }
                public void close() { assertFalse(released); released = true; closes++; }
            };
        }
    }
}
