package com.subhub.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit private-corpus CPU diagnostic. No live capture or rendering measurement. */
public final class SpatialRegistrationCpuAndroidTest {
    @Test public void measureSparseCorpusRefinement() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        String name = InstrumentationRegistry.getArguments().getString("spatialFrameDirectory", "");
        assumeTrue("Explicit private corpus required", name.matches("row-motion-frames-[0-9]+"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), name);
        File[] files = directory.listFiles(file -> file.getName().matches("frame-[0-9]+\\.png"));
        assertNotNull(files);
        assertTrue(files.length >= 16 && files.length <= 64);
        Arrays.sort(files, Comparator.comparingLong(SpatialRegistrationCpuAndroidTest::timestamp));
        RowMotionObserver observer = new RowMotionObserver();
        int[] previous = null;
        List<Long> cpu = new ArrayList<>(), wall = new ArrayList<>();
        List<Long> movingCpu = new ArrayList<>();
        int accepted = 0;
        long firstCpu = -1;
        for (File file : files) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
            assertNotNull(bitmap);
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            RowMotionObserver.Sample row = observer.observe(pixels, width, height,
                    height / 5, height * 19 / 20,
                    new RowMotionObserver.Scope(1, 1, 1, width, height), timestamp(file), true);
            if (row.accepted && previous != null) {
                long wallStart = System.nanoTime(), cpuStart = Debug.threadCpuTimeNanos();
                SpatialFrameRegistration.Result result = SpatialFrameRegistration.refine(previous, pixels,
                        width, height, height / 5, height * 19 / 20, row.dy);
                long cpuUs = (Debug.threadCpuTimeNanos() - cpuStart) / 1000;
                cpu.add(cpuUs);
                if (Math.abs(row.dy) > .01) movingCpu.add(cpuUs);
                wall.add((System.nanoTime() - wallStart) / 1000);
                if (firstCpu < 0) firstCpu = cpuUs;
                if (result.accepted) accepted++;
            }
            previous = pixels;
            SystemClock.sleep(334);
        }
        assertTrue(cpu.size() >= 10);
        assertTrue(accepted >= 3);
        assertTrue(movingCpu.size() >= 3);
        cpu.sort(Long::compare);
        wall.sort(Long::compare);
        movingCpu.sort(Long::compare);
        Log.i("SpatialRegistrationCpuTest", "SPATIAL_SOURCE_CPU samples=" + cpu.size()
                + " accepted=" + accepted + " firstCpuUs=" + firstCpu
                + " medianCpuUs=" + cpu.get(cpu.size() / 2)
                + " maxCpuUs=" + cpu.get(cpu.size() - 1)
                + " medianWallUs=" + wall.get(wall.size() / 2)
                + " maxWallUs=" + wall.get(wall.size() - 1)
                + " movingSamples=" + movingCpu.size()
                + " movingMedianCpuUs=" + movingCpu.get(movingCpu.size() / 2)
                + " movingMaxCpuUs=" + movingCpu.get(movingCpu.size() - 1));
    }

    private static long timestamp(File file) {
        String name = file.getName();
        return Long.parseLong(name.substring(6, name.length() - 4));
    }
}
