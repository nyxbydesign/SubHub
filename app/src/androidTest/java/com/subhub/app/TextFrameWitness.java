package com.subhub.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** An opaque marker in unused message space binds the content's scroll layer to captured pixels. */
public final class TextFrameWitness {
    private static final AtomicInteger sequence = new AtomicInteger();
    private TextFrameWitness() { }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public static void capture(TextView content, View scroll, String run, String name) throws Exception {
        assertTrue("Safe evidence run and leaf name", run.matches("[A-Za-z0-9_-]+") && name.matches("[A-Za-z0-9_.-]+"));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        File folder = new File(content.getContext().getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        int identity = sequence.incrementAndGet();
        int color = Color.rgb(31 + identity * 37 % 192, 47 + identity * 71 % 176, 63 + identity * 43 % 160);
        ColorDrawable marker = new ColorDrawable(color);
        AtomicReference<Rect> screen = new AtomicReference<>(), clip = new AtomicReference<>(), localClip = new AtomicReference<>(), rootClip = new AtomicReference<>();
        AtomicReference<int[]> originScreen = new AtomicReference<>();
        AtomicInteger targetScroll = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            assertTrue(content.getRootView().hasWindowFocus());
            Rect local = new Rect(); assertTrue(content.getLocalVisibleRect(local)); localClip.set(new Rect(local));
            Rect inRoot = new Rect(); assertTrue(content.getGlobalVisibleRect(inRoot)); rootClip.set(inRoot);
            int size = Math.max(6, Math.round(6 * content.getResources().getDisplayMetrics().density));
            int[] origin = new int[2]; content.getLocationOnScreen(origin); originScreen.set(origin);
            Rect visible = screenClip(content); clip.set(visible);
            android.text.Layout layout = content.getLayout(); assertNotNull(layout);
            for (int line = layout.getLineCount() - 1; line >= 0; line--) {
                int top = content.getCompoundPaddingTop() + (layout.getLineTop(line) + layout.getLineBottom(line) - size) / 2;
                int left = content.getCompoundPaddingLeft() + layout.getWidth() - size - 2;
                Rect proposed = new Rect(origin[0] + left, origin[1] + top,
                        origin[0] + left + size, origin[1] + top + size);
                if (layout.getParagraphDirection(line) != android.text.Layout.DIR_LEFT_TO_RIGHT
                        || left < content.getCompoundPaddingLeft() + layout.getLineMax(line) + 2 || !visible.contains(proposed)) continue;
                marker.setBounds(left, top, left + size, top + size); screen.set(proposed); break;
            }
            targetScroll.set(scroll.getScrollY());
            if (screen.get() != null) { content.getOverlay().add(marker); content.invalidate(); }
        });
        JSONObject witness = new JSONObject().put("drawIdentity", identity).put("expectedArgb", color)
                .put("kernelRadius", 1).put("scrollY", targetScroll.get()).put("localClip", localClip.get().flattenToString())
                .put("rootClip", rootClip.get().flattenToString()).put("contentClip", clip.get().flattenToString())
                .put("viewOriginScreen", new org.json.JSONArray(originScreen.get()));
        if (screen.get() == null) {
            java.nio.file.Files.write(new File(folder, name + ".preflight.json").toPath(), witness.toString(2).getBytes(StandardCharsets.UTF_8));
            Bitmap pixels = instrumentation.getUiAutomation().takeScreenshot();
            if (pixels != null) try { save(pixels, new File(folder, name + "-preflight-failure.png")); } finally { pixels.recycle(); }
            fail("Visible message space for an opaque witness: " + witness);
        }
        Rect bounds = screen.get(); int x = bounds.centerX(), y = bounds.centerY(), observed = 0, attempts = 0;
        boolean composed = false; long deadline = SystemClock.uptimeMillis() + 8000;
        witness.put("sampleX", x).put("sampleY", y).put("opaqueScreenBounds", bounds.flattenToString());
        try {
            do {
                frame(content.getRootView()); Bitmap pixels = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull(pixels);
                try {
                    assertTrue(x > 0 && y > 0 && x < pixels.getWidth() - 1 && y < pixels.getHeight() - 1);
                    observed = pixels.getPixel(x, y); attempts++; composed = true;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
                        composed &= pixels.getPixel(x + dx, y + dy) == color;
                    if (composed) save(pixels, new File(folder, name + ".png"));
                    else {
                        File first = new File(folder, name + "-first-uncomposed.png");
                        if (!first.exists()) save(pixels, first);
                    }
                } finally { pixels.recycle(); }
            } while (!composed && SystemClock.uptimeMillis() < deadline);
            witness.put("attempts", attempts).put("observedArgb", observed).put("composed", composed);
            java.nio.file.Files.write(new File(folder, name + ".composition.json").toPath(), witness.toString(2).getBytes(StandardCharsets.UTF_8));
            assertTrue("The captured content must contain its current scroll layer: " + witness, composed);
            instrumentation.runOnMainSync(() -> assertEquals("Captured message scroll remains current", targetScroll.get(), scroll.getScrollY()));
        } finally { instrumentation.runOnMainSync(() -> content.getOverlay().remove(marker)); }
    }

    public static Rect screenClip(View view) {
        Rect value = new Rect(); assertTrue(view.getGlobalVisibleRect(value));
        int[] rootOrigin = new int[2]; view.getRootView().getLocationOnScreen(rootOrigin);
        value.offset(rootOrigin[0], rootOrigin[1]); return value;
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    private static void frame(View root) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
        });
        assertTrue("Content hardware frame did not commit", committed.await(3, TimeUnit.SECONDS));
    }

    private static void save(Bitmap pixels, File path) throws Exception {
        try (FileOutputStream output = new FileOutputStream(path)) { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output)); }
    }
}
