package com.subhub.app.onboarding;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiRefreshWitness;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** A changing opaque marker shares the content's scroll transform and proves captured composition. */
final class OnboardingFrameWitness {
    private static final AtomicInteger sequence = new AtomicInteger();
    private OnboardingFrameWitness() { }

    static JSONObject capture(OnboardingActivity activity, String run, String name) throws Exception {
        assertTrue(run.matches("[A-Za-z0-9_-]+") && name.matches("[A-Za-z0-9_-]+"));
        File folder = new File(activity.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiRefreshWitness.frame(activity);
        int identity = sequence.incrementAndGet();
        int color = Color.rgb(31 + identity * 37 % 192, 47 + identity * 71 % 176, 63 + identity * 43 % 160);
        ColorDrawable marker = new ColorDrawable(color);
        AtomicReference<View> host = new AtomicReference<>();
        AtomicReference<Rect> screen = new AtomicReference<>();
        AtomicInteger targetScroll = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            View body = activity.findViewById(R.id.tour_content);
            ScrollView scroll = activity.findViewById(R.id.tour_scroll);
            int size = Math.max(6, Math.round(6 * activity.getResources().getDisplayMetrics().density));
            int left = body.getWidth() - body.getPaddingRight() / 2 - size / 2;
            int top = scroll.getScrollY() + scroll.getHeight() / 2 - size / 2;
            int[] location = new int[2]; body.getLocationOnScreen(location);
            marker.setBounds(left, top, left + size, top + size);
            screen.set(new Rect(location[0] + left, location[1] + top,
                    location[0] + left + size, location[1] + top + size));
            targetScroll.set(scroll.getScrollY()); host.set(body);
            body.getOverlay().add(marker); body.invalidate();
        });
        Rect bounds = screen.get();
        int x = bounds.centerX(), y = bounds.centerY(), attempts = 0, observed = 0;
        boolean composed = false;
        long end = SystemClock.uptimeMillis() + 8000;
        JSONObject witness = new JSONObject().put("drawIdentity", identity).put("expectedArgb", color)
                .put("sampleX", x).put("sampleY", y).put("scrollY", targetScroll.get())
                .put("opaqueScreenBounds", bounds.flattenToString());
        try {
            do {
                UiRefreshWitness.frame(activity);
                Bitmap pixels = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull(pixels);
                try {
                    assertTrue(x >= 0 && y >= 0 && x < pixels.getWidth() && y < pixels.getHeight());
                    observed = pixels.getPixel(x, y); attempts++;
                    composed = observed == color;
                    if (composed) save(pixels, new File(folder, name + ".png"));
                    else {
                        File first = new File(folder, "first-uncomposed-frame.png");
                        if (!first.exists()) save(pixels, first);
                        if (SystemClock.uptimeMillis() >= end)
                            save(pixels, new File(folder, name + "-composition-failure.png"));
                    }
                } finally { pixels.recycle(); }
            } while (!composed && SystemClock.uptimeMillis() < end);
            witness.put("attempts", attempts).put("observedArgb", observed).put("composed", composed);
            java.nio.file.Files.write(new File(folder, name + ".composition.json").toPath(),
                    witness.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue("Captured pixels must contain this content frame: " + witness, composed);
            instrumentation.runOnMainSync(() -> assertEquals("Scroll remains bound to captured pixels",
                    targetScroll.get(), ((ScrollView) activity.findViewById(R.id.tour_scroll)).getScrollY()));
            return witness;
        } finally {
            instrumentation.runOnMainSync(() -> host.get().getOverlay().remove(marker));
        }
    }

    private static void save(Bitmap pixels, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
}
