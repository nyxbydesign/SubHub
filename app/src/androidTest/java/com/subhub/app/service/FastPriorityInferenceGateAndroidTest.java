package com.subhub.app.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class FastPriorityInferenceGateAndroidTest {
    @Test public void realOrtRunsObeyTheNoOverlapGate() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig qualityConfig = DetectorConfig.builder()
                .inferenceResolution(512).inferenceThreads(4).build();
        DetectorConfig fastConfig = qualityConfig.toBuilder().inferenceResolution(320).build();
        FastPriorityInferenceGate gate = new FastPriorityInferenceGate();
        Bitmap fastFrame = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        Bitmap qualityFrame = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        fastFrame.eraseColor(Color.BLACK);
        qualityFrame.eraseColor(Color.BLACK);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DetectionEngine fast = new DetectionEngine(context, fastConfig, true);
             DetectionEngine quality = new DetectionEngine(context, qualityConfig, false)) {
            fast.initialize();
            quality.initializeWithoutBenchmark(fast.getActiveProvider());
            fast.detect(fastFrame, 320, 320);
            quality.detect(qualityFrame, 512, 512);

            CountDownLatch qualityAdmitted = new CountDownLatch(1);
            Future<?> qualityRun = worker.submit(() -> {
                FastPriorityInferenceGate.QualityAdmission admission =
                        gate.tryAcquireQuality(1_000L, 200L);
                assertTrue(admission.admitted());
                try (FastPriorityInferenceGate.Lease ignored = admission.lease()) {
                    qualityAdmitted.countDown();
                    quality.detect(qualityFrame, 512, 512, gate::hasFastDemand);
                }
                return null;
            });

            assertTrue(qualityAdmitted.await(2L, TimeUnit.SECONDS));
            assertTrue(awaitNativeInference(quality, 2_000L));
            FastPriorityInferenceGate.FastDemand demand = gate.registerFastDemand();
            quality.cancelActiveInference();
            try (FastPriorityInferenceGate.Lease ignored = demand.acquire()) {
                assertFalse(quality.isNativeInferenceRunning());
                fast.detect(fastFrame, 320, 320);
            }
            qualityRun.get(5L, TimeUnit.SECONDS);
            assertTrue(quality.wasLastRunCancelled());
            assertTrue(gate.activeLane() == FastPriorityInferenceGate.Lane.IDLE);
        } finally {
            worker.shutdownNow();
            fastFrame.recycle();
            qualityFrame.recycle();
        }
    }

    private static boolean awaitNativeInference(
            DetectionEngine engine, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (engine.isNativeInferenceRunning()) return true;
            SystemClock.sleep(1L);
        }
        return false;
    }
}
