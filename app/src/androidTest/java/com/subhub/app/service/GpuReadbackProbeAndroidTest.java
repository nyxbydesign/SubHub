package com.subhub.app.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ApplicationProvider;
import com.subhub.app.detection.*;
import com.subhub.app.settings.SettingsRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Isolated probe only: no service, protection settings, tracker or production renderer changes. */
@RunWith(AndroidJUnit4.class)
public final class GpuReadbackProbeAndroidTest {
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test public void compareSmallGpuReadbackWithSoftwareReference() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        Bitmap source = Bitmap.createBitmap(1344, 2992, Bitmap.Config.ARGB_8888);
        int[] row = new int[1344];
        for (int y = 0; y < 2992; y++) {
            for (int x = 0; x < row.length; x++) {
                row[x] = Color.rgb((x * 255 / 1344), y * 255 / 2992,
                        ((x / 19 + y / 23) % 2) * 255);
            }
            source.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
        compare(source, null);
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test public void realCorpusPreservesProductionDetectorOutputs() throws Exception {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        File[] files = new File(context.getFilesDir(), "gpu-readback-corpus-v2").listFiles(
                (dir, name) -> name.endsWith(".png"));
        assumeTrue("Explicit private corpus required", files != null && files.length >= 3);
        Arrays.sort(files);
        Set<String> categories = new LinkedHashSet<>();
        for (int i = 0; i < NudeNetClassCatalog.CLASS_COUNT; i++)
            categories.addAll(NudeNetClassCatalog.byIndex(i).getCategories());
        DetectorConfig config = new SettingsRepository(context).loadDetectorConfig().toBuilder()
                .enabledCategories(categories).inferenceResolution(320).detectionIntervalMs(0).build();
        try (DetectionEngine engine = new DetectionEngine(context, config, true)) {
            DetectorTestAccess.initializeCpu(engine);
            int baselineDetections = 0;
            for (File file : files) {
                Bitmap frame = BitmapFactory.decodeFile(file.getAbsolutePath());
                assertNotNull(frame);
                baselineDetections += compare(frame, engine);
            }
            assertTrue("Corpus needs positive detection evidence as well as negative controls",
                    baselineDetections >= 3);
        }
    }

    @androidx.annotation.RequiresApi(29)
    private static int compare(Bitmap source, DetectionEngine engine) throws Exception {
        int baselineDetections = 0;
        int[] dimensions = InferenceBitmapPreparer.targetDimensions(source.getWidth(), source.getHeight(), 320);
        int width = dimensions[0], height = dimensions[1];
        Bitmap hardware = source.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        long[] gpuTimes = new long[9], cpuTimes = new long[9];
        try (GpuBitmapPreparer candidatePreparer = new GpuBitmapPreparer()) {
            double worstMae = 0;
            for (int i = -2; i < 9; i++) {
                long start = System.nanoTime();
                InferenceBitmapPreparer.Prepared cpu = InferenceBitmapPreparer.prepare(hardware, 320, false);
                long cpuTime = System.nanoTime() - start;
                assertNotNull(cpu);
                Bitmap gpu = null;
                try {
                    start = System.nanoTime();
                        InferenceBitmapPreparer.Prepared candidate = candidatePreparer.prepare(hardware, 320, false);
                        assertNotNull("Production candidate returned fallback", candidate);
                        gpu = candidate.bitmap;
                    long gpuTime = System.nanoTime() - start;
                    int[] a = new int[width * height], b = new int[a.length];
                    cpu.bitmap.getPixels(a, 0, width, 0, 0, width, height);
                    gpu.getPixels(b, 0, width, 0, 0, width, height);
                    long error = 0;
                    for (int p = 0; p < a.length; p++) {
                        error += Math.abs(Color.red(a[p]) - Color.red(b[p]));
                        error += Math.abs(Color.green(a[p]) - Color.green(b[p]));
                        error += Math.abs(Color.blue(a[p]) - Color.blue(b[p]));
                    }
                    worstMae = Math.max(worstMae, error / (double)(a.length * 3));
                    if (i >= 0) { gpuTimes[i] = gpuTime; cpuTimes[i] = cpuTime; }
                    if (engine != null && i == 8) {
                        int[] shape = ScreenshotAccessibilityService.rectangularFastInputShape(
                                source.getWidth(), source.getHeight(), 320);
                        assertNotNull(shape);
                        List<Detection> expected = engine.detectRectangular(cpu.bitmap,
                                source.getWidth(), source.getHeight(), shape[0], shape[1]);
                        List<Detection> actual = engine.detectRectangular(gpu,
                                source.getWidth(), source.getHeight(), shape[0], shape[1]);
                        baselineDetections = expected.size();
                        int matches = matched(expected, actual);
                        Log.i("GpuReadbackProbe", "GPU_DETECT expected=" + expected.size()
                                + " actual=" + actual.size() + " matched=" + matches);
                        assertEquals("GPU lost or displaced baseline detections", expected.size(), matches);
                        assertEquals("GPU added unmatched detections", actual.size(), matches);
                    }
                } finally {
                    cpu.bitmap.recycle();
                    if (gpu != null) gpu.recycle();
                }
            }
            Arrays.sort(gpuTimes); Arrays.sort(cpuTimes);
            Log.i("GpuReadbackProbe", "GPU_PROBE cpuMedianMs=" + cpuTimes[4] / 1e6
                    + " gpuMedianMs=" + gpuTimes[4] / 1e6 + " gpuMaxMs=" + gpuTimes[8] / 1e6
                    + " worstChannelMae=" + worstMae);
            assertTrue("GPU image fidelity mismatch", worstMae < 3.0);
        } finally {
            hardware.recycle(); source.recycle();
        }
        return baselineDetections;
    }

    private static int matched(List<Detection> expected, List<Detection> actual) {
        boolean[] used = new boolean[actual.size()];
        int matched = 0;
        for (Detection left : expected) {
            int best = -1; float bestIou = .75f;
            for (int i = 0; i < actual.size(); i++) {
                Detection right = actual.get(i);
                float iou = left.getBox().intersectionOverUnion(right.getBox());
                if (!used[i] && left.getCategory().equals(right.getCategory()) && iou >= bestIou) {
                    best = i; bestIou = iou;
                }
            }
            if (best >= 0) { used[best] = true; matched++; }
        }
        return matched;
    }
}
