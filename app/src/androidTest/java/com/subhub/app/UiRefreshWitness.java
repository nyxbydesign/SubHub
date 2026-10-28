package com.subhub.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native composition and captured pixels for refresh/scroll assertions. */
public final class UiRefreshWitness {
    private UiRefreshWitness() { }
    public static ScrollView scroll(View descendant) {
        for (ViewParent parent = descendant.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof ScrollView) return (ScrollView) parent;
        throw new AssertionError("Missing scrolling parent");
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public static void frame(Activity activity) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        CountDownLatch frame = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            if (android.os.Build.VERSION.SDK_INT < 29) throw new AssertionError("Frame witnesses require API29");
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            root.invalidate();
        });
        assertTrue("No hardware frame committed", frame.await(5, TimeUnit.SECONDS));
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public static void capture(Activity activity, String name) throws Exception {
        frame(activity);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "refresh");
        assertTrue(run.matches("[A-Za-z0-9_-]+") && name.matches("[A-Za-z0-9_-]+"));
        File folder = new File(activity.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(pixels);
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { pixels.recycle(); }
    }
}
