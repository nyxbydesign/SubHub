package com.subhub.app.detection;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.service.ScreenshotAccessibilityService;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Device-only probe for the dynamic-H/W CPU fast-lane candidate. */
@RunWith(AndroidJUnit4.class)
public final class RectangularFastLaneAndroidTest {
    private static final String TAG = "RectFastLaneTest";

    @Test public void rectangularCandidatesPreserveSquareRecallAndReduceWork() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap screenshot = loadBenchmarkFrame(context);
        DetectorConfig config = new SettingsRepository(context).loadDetectorConfig()
                .toBuilder()
                .enabledCategories(allCatalogCategories())
                .inferenceResolution(320)
                .detectionIntervalMs(0L)
                .build();
        try (DetectionEngine engine = new DetectionEngine(context, config, true)) {
            engine.initializeForProvider("CPU");

            // Keep the comparison on the same provider/session. The first square run is also the
            // recall reference for this exact source frame, not a synthetic expected label set.
            List<Detection> square = engine.detect(
                    screenshot, screenshot.getWidth(), screenshot.getHeight());
            long squareMedian = medianNanos(
                    () -> engine.detect(screenshot, screenshot.getWidth(), screenshot.getHeight()));
            // The exported head upsamples stride-32 features by two. Widths that produce an odd
            // stride-16 map (144 -> 9) cannot satisfy its concat; all multiples of 32 are valid.
            // Keep the full sweep because the smallest shape may lose a small subject even while
            // a wider rectangular candidate remains both faster and recall-safe.
            int[] widths = {256, 224, 192, 160, 128};
            int[] productionShape = ScreenshotAccessibilityService.rectangularFastInputShape(
                    screenshot.getWidth(), screenshot.getHeight(), 320);
            assertNotNull("portrait corpus must exercise production rectangle", productionShape);
            long[] medians = new long[widths.length];
            long fastestSafeRectangle = Long.MAX_VALUE;
            long productionMedian = Long.MAX_VALUE;
            float productionRecall = 0f;
            float productionPrecision = 0f;
            for (int index = 0; index < widths.length; index++) {
                int width = widths[index];
                List<Detection> rectangular = engine.detectRectangular(
                        screenshot, screenshot.getWidth(), screenshot.getHeight(), width, 320);
                medians[index] = medianNanos(() -> engine.detectRectangular(
                        screenshot, screenshot.getWidth(), screenshot.getHeight(), width, 320));
                float recall = matchedRecall(square, rectangular);
                float precision = matchedRecall(rectangular, square);
                Log.i(TAG, "candidate=" + width + "x320 provider=" + engine.getActiveProvider()
                        + " medianMs=" + medians[index] / 1_000_000f
                        + " runtimeMs=" + engine.getLastRuntimeMs()
                        + " detections=" + rectangular.size()
                        + " squareRecall=" + recall
                        + " squarePrecision=" + precision);
                // This is a safety gate, not a quality claim. Keep lower-width candidates in the
                // log even when they lose recall; only a >=90% candidate may win the speed gate.
                if (recall >= 0.90f) fastestSafeRectangle = Math.min(
                        fastestSafeRectangle, medians[index]);
                if (width == productionShape[0] && productionShape[1] == 320) {
                    productionMedian = medians[index];
                    productionRecall = recall;
                    productionPrecision = precision;
                }
            }
            long fastestRectangle = Arrays.stream(medians).min().orElse(Long.MAX_VALUE);
            Log.i(TAG, "square=320x320 medianMs=" + squareMedian / 1_000_000f
                    + " fastestRectangleMs=" + fastestRectangle / 1_000_000f
                    + " fastestSafeRectangleMs=" + fastestSafeRectangle / 1_000_000f
                    + " source=" + screenshot.getWidth() + "x" + screenshot.getHeight());
            assertTrue("at least one rectangular candidate must retain >=90% recall",
                    fastestSafeRectangle < Long.MAX_VALUE);
            assertTrue("a recall-safe rectangular candidate must beat padded square work",
                    fastestSafeRectangle < squareMedian);
            assertTrue("production rectangle must preserve >=90% square recall",
                    productionRecall >= 0.90f);
            assertTrue("production rectangle must avoid unmatched extra detections",
                    productionPrecision >= 0.90f);
            assertTrue("production rectangle must beat the square path",
                    productionMedian < squareMedian);
        } finally {
            screenshot.recycle();
        }
    }

    @Test public void rectangularPathRejectsAcceleratorProviders() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig config = new SettingsRepository(context).loadDetectorConfig()
                .toBuilder().enabledCategories(allCatalogCategories())
                .inferenceResolution(320).detectionIntervalMs(0L).build();
        Bitmap frame = Bitmap.createBitmap(144, 320, Bitmap.Config.ARGB_8888);
        try (DetectionEngine engine = new DetectionEngine(context, config, true)) {
            try {
                engine.initializeForProvider("NNAPI");
            } catch (Exception unavailable) {
                Assume.assumeNoException("NNAPI unavailable on this device", unavailable);
                return;
            }
            boolean rejected = false;
            try {
                engine.detectRectangular(frame, 1080, 2400, 144, 320);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            assertTrue("dynamic rectangular path must not silently use NNAPI", rejected);
        } finally {
            frame.recycle();
        }
    }

    private static Bitmap loadBenchmarkFrame(Context context) {
        File privateFrame = new File(context.getFilesDir(), "fast-lane-benchmark.png");
        Bitmap value = privateFrame.isFile() ? BitmapFactory.decodeFile(privateFrame.getAbsolutePath())
                : InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("rectangular benchmark requires a visible display", value);
        return value;
    }

    private static Set<String> allCatalogCategories() {
        Set<String> categories = new LinkedHashSet<>();
        for (int index = 0; index < NudeNetClassCatalog.CLASS_COUNT; index++) {
            categories.addAll(NudeNetClassCatalog.byIndex(index).getCategories());
        }
        return categories;
    }

    private static long medianNanos(ThrowingSupplier work) throws Exception {
        long[] timings = new long[5];
        for (int index = 0; index < timings.length; index++) {
            long started = SystemClock.elapsedRealtimeNanos();
            assertNotNull(work.get());
            timings[index] = SystemClock.elapsedRealtimeNanos() - started;
        }
        Arrays.sort(timings);
        return timings[timings.length / 2];
    }

    private static float matchedRecall(List<Detection> expected, List<Detection> actual) {
        if (expected == null || expected.isEmpty()) return 1f;
        int matched = 0;
        for (Detection left : expected) {
            for (Detection right : actual) {
                if (left.getCategory().equals(right.getCategory())
                        && left.getBox().intersectionOverUnion(right.getBox()) >= 0.35f) {
                    matched++;
                    break;
                }
            }
        }
        return matched / (float) expected.size();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        List<Detection> get() throws Exception;
    }
}
