package com.subhub.app.detection;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.settings.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
public final class DetectionEngineUltraAndroidTest {
    private static final String TAG = "UltraInferenceTest";

    @Test public void warmedRealtimeLaneIsFasterThanSettledQuality() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectionPreset.HIGH
                .applyTo(DetectorConfig.builder()).build();
        DetectorConfig fastConfig = qualityConfig.toBuilder()
                .inferenceResolution(320).detectionIntervalMs(0L).build();
        Bitmap qualityFrame = Bitmap.createBitmap(230, 512, Bitmap.Config.ARGB_8888);
        Bitmap fastFrame = Bitmap.createBitmap(144, 320, Bitmap.Config.ARGB_8888);
        new Canvas(qualityFrame).drawColor(Color.rgb(74, 20, 95));
        new Canvas(fastFrame).drawColor(Color.rgb(74, 20, 95));
        try {
            try (DetectionEngine quality = new DetectionEngine(context, qualityConfig, false);
                 DetectionEngine fast = new DetectionEngine(context, fastConfig, true)) {
                quality.initialize();
                fast.initialize();
                long qualityMedian = medianNanos(quality, qualityFrame);
                long fastMedian = medianNanos(fast, fastFrame);
                Log.i(TAG, "quality=" + qualityConfig.getInferenceResolution() + "@"
                        + quality.getActiveProvider() + ':' + qualityMedian / 1_000_000f
                        + " ms(pre=" + quality.getLastPreprocessMs()
                        + ", runtime=" + quality.getLastRuntimeMs() + ')'
                        + " ms, fast=" + fastConfig.getInferenceResolution() + "@"
                        + fast.getActiveProvider() + ':' + fastMedian / 1_000_000f
                        + " ms(pre=" + fast.getLastPreprocessMs()
                        + ", runtime=" + fast.getLastRuntimeMs() + ")");
                assertTrue("320px real-time lane must beat 512px quality refinement",
                        fastMedian < qualityMedian);
            }
        } finally {
            qualityFrame.recycle();
            fastFrame.recycle();
        }
    }

    @Test public void fastLanePreemptsInFlightQualityInsteadOfCompeting() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectionPreset.HIGH
                .applyTo(DetectorConfig.builder()).build();
        DetectorConfig fastConfig = qualityConfig.toBuilder()
                .inferenceResolution(320).detectionIntervalMs(0L).build();
        Bitmap qualityFrame = Bitmap.createBitmap(230, 512, Bitmap.Config.ARGB_8888);
        Bitmap fastFrame = Bitmap.createBitmap(144, 320, Bitmap.Config.ARGB_8888);
        new Canvas(qualityFrame).drawColor(Color.rgb(74, 20, 95));
        new Canvas(fastFrame).drawColor(Color.rgb(74, 20, 95));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            try (DetectionEngine quality = new DetectionEngine(context, qualityConfig, false);
                 DetectionEngine fast = new DetectionEngine(context, fastConfig, true)) {
                quality.initialize();
                fast.initialize();
                quality.detect(qualityFrame, 1080, 2400);
                fast.detect(fastFrame, 1080, 2400);
                long[] unpreemptedFast = new long[15];
                long[] preemptedFast = new long[15];
                long[] cancellation = new long[15];
                Random order = new Random(23L);
                int pairedWins = 0;
                for (int index = 0; index < unpreemptedFast.length; index++) {
                    if (order.nextBoolean()) {
                        preemptedFast[index] = measureFastDuringQuality(
                                workers, quality, fast, qualityFrame, fastFrame,
                                true, cancellation, index);
                        unpreemptedFast[index] = measureFastDuringQuality(
                                workers, quality, fast, qualityFrame, fastFrame,
                                false, cancellation, index);
                    } else {
                        unpreemptedFast[index] = measureFastDuringQuality(
                                workers, quality, fast, qualityFrame, fastFrame,
                                false, cancellation, index);
                        preemptedFast[index] = measureFastDuringQuality(
                                workers, quality, fast, qualityFrame, fastFrame,
                                true, cancellation, index);
                    }
                    if (preemptedFast[index] < unpreemptedFast[index]) pairedWins++;
                }
                Arrays.sort(unpreemptedFast);
                Arrays.sort(preemptedFast);
                Arrays.sort(cancellation);
                long unpreemptedFastMedian = unpreemptedFast[7];
                long preemptedFastMedian = preemptedFast[7];
                long cancellationMedian = cancellation[7];
                Log.i(TAG, "unpreemptedFast=" + unpreemptedFastMedian / 1_000_000f
                        + " ms preemptedFast=" + preemptedFastMedian / 1_000_000f
                        + " ms unpreemptedP95=" + unpreemptedFast[14] / 1_000_000f
                        + " ms preemptedP95=" + preemptedFast[14] / 1_000_000f
                        + " ms cancellation=" + cancellationMedian / 1_000_000f
                        + " ms pairedWins=" + pairedWins + '/' + unpreemptedFast.length
                        + " ms fastProvider=" + fast.getActiveProvider()
                        + " qualityProvider=" + quality.getActiveProvider());
                assertTrue("Preemption must reduce fast-lane contention",
                        preemptedFastMedian < unpreemptedFastMedian);
                assertTrue("Preemption must win a majority of paired trials",
                        pairedWins >= 9);
            }
        } finally {
            workers.shutdownNow();
            qualityFrame.recycle();
            fastFrame.recycle();
        }
    }

    @Test public void staleAdmissionTokenCancelsQualityBeforeNativeExecution() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectionPreset.HIGH
                .applyTo(DetectorConfig.builder()).build();
        Bitmap frame = Bitmap.createBitmap(230, 512, Bitmap.Config.ARGB_8888);
        new Canvas(frame).drawColor(Color.rgb(74, 20, 95));
        try (DetectionEngine quality = new DetectionEngine(context, qualityConfig, false)) {
            quality.initialize();
            List<Detection> result = quality.detect(frame, 1080, 2400, () -> true);
            assertTrue(result.isEmpty());
            assertTrue(quality.wasLastRunCancelled());
            assertTrue(!quality.isNativeInferenceRunning());
        } finally {
            frame.recycle();
        }
    }

    @Test public void benchmarksNnapiFp16RelaxationWithoutEnablingItBlindly() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectionPreset.HIGH
                .applyTo(DetectorConfig.builder()).build();
        Bitmap qualityFrame = Bitmap.createBitmap(230, 512, Bitmap.Config.ARGB_8888);
        new Canvas(qualityFrame).drawColor(Color.rgb(74, 20, 95));
        try {
            ProviderTiming strict = measureProviderVariant(
                    context, qualityConfig, qualityFrame, false);
            ProviderTiming relaxed = measureProviderVariant(
                    context, qualityConfig, qualityFrame, true);
            Log.i(TAG, "nnapiStrict=" + strict.provider + ':'
                    + strict.medianNanos / 1_000_000f + "ms detections=" + strict.detections
                    + " nnapiFp16=" + relaxed.provider + ':'
                    + relaxed.medianNanos / 1_000_000f + "ms detections=" + relaxed.detections);
            assertTrue(strict.medianNanos > 0L);
            assertTrue(relaxed.medianNanos > 0L);
        } finally {
            qualityFrame.recycle();
        }
    }

    @Test public void benchmarksFastLaneResolutionOnCurrentDisplay() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File privateFrame = new File(context.getFilesDir(), "fast-lane-benchmark.png");
        Bitmap screenshot = privateFrame.isFile()
                ? BitmapFactory.decodeFile(privateFrame.getAbsolutePath())
                : InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Pixel benchmark requires a visible display", screenshot);
        DetectorConfig base = new SettingsRepository(context).loadDetectorConfig()
                .toBuilder().detectionIntervalMs(0L).build();
        int[] resolutions = {320, 288, 256, 224, 192};
        List<Detection> baseline = null;
        try {
            for (int resolution : resolutions) {
                DetectorConfig config = base.toBuilder()
                        .inferenceResolution(resolution).build();
                try (DetectionEngine engine = new DetectionEngine(context, config, true)) {
                    engine.initializeForProvider("CPU");
                    List<Detection> detections = engine.detect(
                            screenshot, screenshot.getWidth(), screenshot.getHeight());
                    long median = medianNanos(
                            engine, screenshot, screenshot.getWidth(), screenshot.getHeight());
                    if (baseline == null) baseline = detections;
                    float recall = matchedRecall(baseline, detections);
                    Log.i(TAG, "fastResolution=" + resolution
                            + " provider=" + engine.getActiveProvider()
                            + " medianMs=" + median / 1_000_000f
                            + " runtimeMs=" + engine.getLastRuntimeMs()
                            + " detections=" + detections.size()
                            + " baselineRecall=" + recall);
                    assertTrue(median > 0L);
                }
            }
        } finally {
            screenshot.recycle();
        }
    }

    @Test public void benchmarksAtomicSceneObservationTopologies() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectionPreset.HIGH
                .applyTo(DetectorConfig.builder()).build();
        DetectorConfig fastConfig = qualityConfig.toBuilder()
                .inferenceResolution(320).detectionIntervalMs(0L).build();
        Bitmap fullQuality = preparedFrame(230, 512, Color.rgb(74, 20, 95));
        Bitmap fullFast = preparedFrame(144, 320, Color.rgb(74, 20, 95));
        Bitmap topTile = preparedFrame(418, 512, Color.rgb(82, 24, 102));
        Bitmap bottomTile = preparedFrame(418, 512, Color.rgb(68, 17, 88));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            try (DetectionEngine fast = new DetectionEngine(context, fastConfig, true);
                 DetectionEngine qualityA = new DetectionEngine(context, qualityConfig, false);
                 DetectionEngine qualityB = new DetectionEngine(context, qualityConfig, false);
                 DetectionEngine qualityXnn = new DetectionEngine(
                         context, qualityConfig, false)) {
                fast.initializeForProvider("NNAPI");
                qualityA.initializeForProvider("NNAPI");
                qualityB.initializeForProvider("NNAPI");
                qualityXnn.initializeForProvider("XNNPACK");
                fast.detect(fullFast, 1344, 2992);
                qualityA.detect(fullQuality, 1344, 2992);
                qualityB.detect(topTile, 1344, 1646);
                qualityXnn.detect(bottomTile, 1344, 1646);

                long[] qualityOnly = new long[7];
                long[] sequential = new long[7];
                long[] parallel = new long[7];
                long[] tiledNnapi = new long[7];
                long[] tiledMixed = new long[7];
                for (int index = 0; index < qualityOnly.length; index++) {
                    if ((index & 1) == 0) {
                        qualityOnly[index] = measure(() ->
                                qualityA.detect(fullQuality, 1344, 2992));
                        sequential[index] = measure(() -> {
                            fast.detect(fullFast, 1344, 2992);
                            qualityA.detect(fullQuality, 1344, 2992);
                        });
                        parallel[index] = measureParallel(workers,
                                () -> fast.detect(fullFast, 1344, 2992),
                                () -> qualityA.detect(fullQuality, 1344, 2992));
                        tiledNnapi[index] = measureParallel(workers,
                                () -> qualityA.detect(topTile, 1344, 1646),
                                () -> qualityB.detect(bottomTile, 1344, 1646));
                        tiledMixed[index] = measureParallel(workers,
                                () -> qualityA.detect(topTile, 1344, 1646),
                                () -> qualityXnn.detect(bottomTile, 1344, 1646));
                    } else {
                        tiledMixed[index] = measureParallel(workers,
                                () -> qualityA.detect(topTile, 1344, 1646),
                                () -> qualityXnn.detect(bottomTile, 1344, 1646));
                        tiledNnapi[index] = measureParallel(workers,
                                () -> qualityA.detect(topTile, 1344, 1646),
                                () -> qualityB.detect(bottomTile, 1344, 1646));
                        parallel[index] = measureParallel(workers,
                                () -> fast.detect(fullFast, 1344, 2992),
                                () -> qualityA.detect(fullQuality, 1344, 2992));
                        sequential[index] = measure(() -> {
                            fast.detect(fullFast, 1344, 2992);
                            qualityA.detect(fullQuality, 1344, 2992);
                        });
                        qualityOnly[index] = measure(() ->
                                qualityA.detect(fullQuality, 1344, 2992));
                    }
                }
                TimingSummary qualityOnlySummary = summarize(qualityOnly);
                TimingSummary sequentialSummary = summarize(sequential);
                TimingSummary parallelSummary = summarize(parallel);
                TimingSummary tiledNnapiSummary = summarize(tiledNnapi);
                TimingSummary tiledMixedSummary = summarize(tiledMixed);
                Log.i(TAG, "atomicSceneTopology qualityOnly=" + qualityOnlySummary
                        + " sequentialFastQuality=" + sequentialSummary
                        + " parallelFastQuality=" + parallelSummary
                        + " tiledNnapi=" + tiledNnapiSummary
                        + " tiledNnapiXnnpack=" + tiledMixedSummary
                        + " fastProvider=" + fast.getActiveProvider()
                        + " qualityAProvider=" + qualityA.getActiveProvider()
                        + " qualityBProvider=" + qualityB.getActiveProvider()
                        + " qualityXnnProvider=" + qualityXnn.getActiveProvider());
                assertTrue(qualityOnlySummary.medianNanos > 0L);
                assertTrue(sequentialSummary.medianNanos > 0L);
                assertTrue(parallelSummary.medianNanos > 0L);
                assertTrue(tiledNnapiSummary.medianNanos > 0L);
                assertTrue(tiledMixedSummary.medianNanos > 0L);
            }
        } finally {
            workers.shutdownNow();
            fullQuality.recycle();
            fullFast.recycle();
            topTile.recycle();
            bottomTile.recycle();
        }
    }

    private static long medianNanos(DetectionEngine engine, Bitmap frame) throws Exception {
        return medianNanos(engine, frame, 1080, 2400);
    }

    private static long medianNanos(
            DetectionEngine engine,
            Bitmap frame,
            int sourceWidth,
            int sourceHeight) throws Exception {
        long[] timings = new long[5];
        for (int index = 0; index < timings.length; index++) {
            long started = SystemClock.elapsedRealtimeNanos();
            assertNotNull(engine.detect(frame, sourceWidth, sourceHeight));
            timings[index] = SystemClock.elapsedRealtimeNanos() - started;
        }
        Arrays.sort(timings);
        return timings[2];
    }

    private static float matchedRecall(
            List<Detection> baseline, List<Detection> candidate) {
        if (baseline == null || baseline.isEmpty()) return 1f;
        int matched = 0;
        for (Detection expected : baseline) {
            for (Detection actual : candidate) {
                if (expected.getCategory().equals(actual.getCategory())
                        && expected.getBox().intersectionOverUnion(actual.getBox()) >= 0.35f) {
                    matched++;
                    break;
                }
            }
        }
        return matched / (float) baseline.size();
    }

    private static Bitmap preparedFrame(int width, int height, int color) {
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(frame).drawColor(color);
        return frame;
    }

    private static long measure(ThrowingRunnable work) throws Exception {
        long started = SystemClock.elapsedRealtimeNanos();
        work.run();
        return SystemClock.elapsedRealtimeNanos() - started;
    }

    private static long measureParallel(
            ExecutorService workers, ThrowingRunnable first, ThrowingRunnable second)
            throws Exception {
        long started = SystemClock.elapsedRealtimeNanos();
        Future<?> firstRun = workers.submit(() -> {
            first.run();
            return null;
        });
        Future<?> secondRun = workers.submit(() -> {
            second.run();
            return null;
        });
        firstRun.get();
        secondRun.get();
        return SystemClock.elapsedRealtimeNanos() - started;
    }

    private static TimingSummary summarize(long[] timings) {
        long[] sorted = timings.clone();
        Arrays.sort(sorted);
        return new TimingSummary(sorted[sorted.length / 2], sorted[sorted.length - 1]);
    }

    private static long measureFastDuringQuality(
            ExecutorService workers,
            DetectionEngine quality,
            DetectionEngine fast,
            Bitmap qualityFrame,
            Bitmap fastFrame,
            boolean preempt,
            long[] cancellations,
            int index) throws Exception {
        Future<List<Detection>> qualityRun = workers.submit(() ->
                quality.detect(qualityFrame, 1080, 2400));
        awaitNativeInference(quality, qualityRun);
        long cancellationStarted = 0L;
        if (preempt) {
            cancellationStarted = SystemClock.elapsedRealtimeNanos();
            assertTrue("An in-flight quality run must accept preemption",
                    quality.cancelActiveInference());
        }
        long fastStarted = SystemClock.elapsedRealtimeNanos();
        fast.detect(fastFrame, 1080, 2400);
        long fastElapsed = SystemClock.elapsedRealtimeNanos() - fastStarted;
        List<Detection> qualityOutput = qualityRun.get();
        if (preempt) {
            cancellations[index] = SystemClock.elapsedRealtimeNanos() - cancellationStarted;
            assertTrue("Cancelled quality output must be reported as cancelled",
                    quality.wasLastRunCancelled());
            assertTrue("Cancelled quality output must never escape the engine",
                    qualityOutput.isEmpty());
            assertTrue("A completed cancellation must close its preemption window",
                    !quality.cancelActiveInference());
        }
        return fastElapsed;
    }

    private static void awaitNativeInference(
            DetectionEngine engine,
            Future<?> run) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 2_000L;
        while (!engine.isNativeInferenceRunning()
                && !run.isDone()
                && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(1L);
        }
        assertTrue("Quality inference must still be running for the contention probe",
                engine.isNativeInferenceRunning());
    }

    private static ProviderTiming measureProviderVariant(
            Context context,
            DetectorConfig config,
            Bitmap frame,
            boolean nnapiFp16) throws Exception {
        try (DetectionEngine engine = new DetectionEngine(
                context, config, false, nnapiFp16)) {
            engine.initializeForProvider("NNAPI");
            List<Detection> detections = engine.detect(frame, 1080, 2400);
            return new ProviderTiming(
                    engine.getActiveProvider(), medianNanos(engine, frame), detections.size());
        }
    }

    private static final class ProviderTiming {
        final String provider;
        final long medianNanos;
        final int detections;

        ProviderTiming(String provider, long medianNanos, int detections) {
            this.provider = provider;
            this.medianNanos = medianNanos;
            this.detections = detections;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class TimingSummary {
        final long medianNanos;
        final long maximumNanos;

        TimingSummary(long medianNanos, long maximumNanos) {
            this.medianNanos = medianNanos;
            this.maximumNanos = maximumNanos;
        }

        @Override public String toString() {
            return medianNanos / 1_000_000f + "ms(max="
                    + maximumNanos / 1_000_000f + "ms)";
        }
    }
}
