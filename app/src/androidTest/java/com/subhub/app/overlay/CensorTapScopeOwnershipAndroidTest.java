package com.subhub.app.overlay;

import static org.junit.Assert.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.detection.*;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.*;

public final class CensorTapScopeOwnershipAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private static final int WIDTH = 320, HEIGHT = 480;
    private static final BBox VISUAL = new BBox(70, 80, 120, 100), TEXT = new BBox(100, 260, 120, 100);
    private static final AtomicInteger DRAW_IDS = new AtomicInteger();

    @Test @SdkSuppress(minSdkVersion = 29)
    public void staleOnlyDrawCannotReplaceANewerForeignPublisher() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(() -> { f.view.setTapSource(false, f.taps.scope()); f.visual(VISUAL); });
            f.frame("stale-initial", VISUAL);
            Object foreign = new Object();
            f.main(() -> { f.taps.clear(); f.foreign(foreign); });
            f.frame("stale-redraw", VISUAL);
            assertTrue("A stale empty redraw must preserve another publisher", f.foreignHit());
            assertFalse(f.hit(VISUAL));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void mixedVisualAndTextSourcesKeepOnlyTheCurrentSourceInBothDirections() throws Exception {
        for (boolean currentText : new boolean[]{true, false}) try (Fixture f = new Fixture()) {
            f.main(() -> {
                f.view.setTapSource(false, f.taps.scope());
                if (currentText) f.visual(VISUAL); else f.text();
                f.taps.clear(); f.view.setTapSource(false, f.taps.scope());
                if (currentText) f.text(); else f.visual(VISUAL);
            });
            f.frame(currentText ? "current-text" : "current-visual", VISUAL, TEXT);
            assertEquals(!currentText, f.hit(VISUAL)); assertEquals(currentText, f.hit(TEXT));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void scopeChangeBetweenRecordedRegionsCannotRelabelAnEarlierRegion() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(() -> { f.view.setTapSource(false, f.taps.scope()); f.visual(VISUAL, TEXT); });
            f.frame("flip-native-initial", VISUAL, TEXT);
            Bitmap raster = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            AtomicInteger effects = new AtomicInteger(); AtomicBoolean foreignHit = new AtomicBoolean(), oldHit = new AtomicBoolean();
            long before = f.taps.scope();
            try {
                f.main(() -> {
                    Canvas canvas = new Canvas(raster) {
                        @Override public void drawRoundRect(RectF bounds, float rx, float ry, Paint paint) {
                            if (effects.incrementAndGet() == 2) { f.taps.clear(); f.foreign(new Object()); }
                            super.drawRoundRect(bounds, rx, ry, paint);
                        }
                    };
                    f.view.draw(canvas); foreignHit.set(f.foreignHit()); oldHit.set(f.hit(VISUAL));
                });
                f.save(raster, "flip-controlled-raster", new JSONObject().put("authority", "controlled View.draw raster")
                        .put("beforeScope", before).put("afterScope", f.taps.scope()).put("effectCalls", effects.get())
                        .put("foreignHit", foreignHit.get()).put("oldHit", oldHit.get()));
                assertEquals(2, effects.get());
                assertEquals(Color.BLACK, raster.getPixel(VISUAL.getX() + 60, VISUAL.getY() + 50));
                assertTrue("A scope change during draw must preserve its new publisher", foreignHit.get());
                assertFalse("Previously collected geometry must not acquire the new scope", oldHit.get());
            } finally { raster.recycle(); }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void currentEmptyDrawWithdrawsItsOwnPreviousGeometry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(() -> { f.view.setTapSource(false, f.taps.scope()); f.visual(VISUAL); });
            f.frame("empty-initial", VISUAL); assertTrue(f.hit(VISUAL));
            long scope = f.taps.scope();
            f.main(() -> f.view.offsetContent(0, -800, true)); f.frame("empty-current");
            assertFalse(f.hit(VISUAL)); assertEquals(scope, f.taps.scope());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void runtimeTransitionRevokesRetainedSourcesAndAdmitsOnlyNewCurrentText() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(() -> { f.view.setTapSource(false, f.taps.scope()); f.visual(VISUAL); f.text(); });
            f.frame("runtime-initial", VISUAL, TEXT);
            assertTrue(f.hit(VISUAL)); assertTrue(f.hit(TEXT));
            long oldScope = f.taps.scope();
            f.main(com.subhub.app.appmode.RuntimeSessionIdentity::changed);
            assertNotEquals("A native runtime transition must revoke retained tap provenance", oldScope, f.taps.scope());
            f.main(() -> f.foreign(new Object()));
            f.frame("runtime-stale-redraw", VISUAL, TEXT);
            assertTrue(f.foreignHit()); assertFalse(f.hit(VISUAL)); assertFalse(f.hit(TEXT));
            f.main(() -> { f.view.setTapSource(false, f.taps.scope()); f.text(); });
            f.frame("runtime-current-text", VISUAL, TEXT);
            assertFalse(f.hit(VISUAL)); assertTrue(f.hit(TEXT));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CensorTapTracker taps = CensorTapTracker.shared();
        final ActivityScenario<CensorTapFixtureActivity> page;
        CensorOverlayView view;
        final int[] origin = new int[2];
        long foreignPublishedAt;
        Fixture() {
            taps.clear(); page = ActivityScenario.launch(CensorTapFixtureActivity.class);
            page.onActivity(activity -> {
                view = new CensorOverlayView(activity);
                view.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 100, 0f, false, false,
                        CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA, List.of(), false, 100,
                        "rectangle", "SubHub", "Blocked"));
                ((FrameLayout) activity.findViewById(android.R.id.content)).addView(view, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        void main(Runnable action) { page.onActivity(activity -> action.run()); }
        void visual(BBox... boxes) {
            List<Detection> detections = new ArrayList<>(); for (BBox box : boxes) detections.add(region(box, "face"));
            view.setTracks(new ObjectTracker(DetectorConfig.builder().build()).update(detections), WIDTH, HEIGHT, null);
        }
        void text() { view.setTextDetections(List.of(region(TEXT, "text_smut")), WIDTH, HEIGHT, 0, 0); }
        boolean hit(BBox box) {
            return taps.matchesClick(origin[0] + box.getX() + 45, origin[1] + box.getY() + 35,
                    origin[0] + box.getX() + 75, origin[1] + box.getY() + 65, WIDTH, HEIGHT, SystemClock.uptimeMillis());
        }
        void foreign(Object publisher) {
            foreignPublishedAt = SystemClock.uptimeMillis();
            taps.publishDisplayed(publisher, taps.scope(), List.of(new BBox(origin[0] + 250, origin[1] + 400, 40, 40)),
                    new BBox(origin[0], origin[1], WIDTH, HEIGHT), foreignPublishedAt, false);
        }
        // The synthetic publisher's retention is independent of its separate freshness timeout.
        boolean foreignHit() { return taps.matchesPoint(origin[0] + 270, origin[1] + 420, WIDTH, HEIGHT, foreignPublishedAt); }
        @androidx.annotation.RequiresApi(29)
        void frame(String name, BBox... opaque) throws Exception {
            String run = InstrumentationRegistry.getArguments().getString("evidence_run");
            assertNotNull(run);
            int identity = DRAW_IDS.incrementAndGet(), signature = run.hashCode() * 31 ^ identity * 0x9e3779b9;
            int color = Color.rgb(32 + (signature & 127), 32 + (signature >>> 8 & 127), 32 + (signature >>> 16 & 127));
            ColorDrawable marker = new ColorDrawable(color); marker.setBounds(8, 448, 24, 464);
            CountDownLatch commit = new CountDownLatch(1);
            AtomicReference<Rect> clip = new AtomicReference<>();
            main(() -> {
                assertTrue(view.isHardwareAccelerated()); assertTrue(view.hasWindowFocus());
                clip.set(com.subhub.app.TextFrameWitness.screenClip(view));
                view.getLocationOnScreen(origin); view.getOverlay().add(marker);
                View root = view.getRootView();
                root.getViewTreeObserver().registerFrameCommitCallback(commit::countDown);
                view.invalidate(); root.invalidate();
            });
            boolean committed = commit.await(5, TimeUnit.SECONDS);
            Bitmap pixels = null; boolean composed = false; long deadline = SystemClock.uptimeMillis() + 8000;
            try {
                do {
                    if (pixels != null) pixels.recycle();
                    pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    if (pixels != null) {
                        composed = true;
                        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
                            composed &= pixels.getPixel(origin[0] + 16 + x, origin[1] + 456 + y) == color;
                    }
                    if (!composed) Thread.sleep(50);
                } while (!composed && SystemClock.uptimeMillis() < deadline);
                JSONObject witness = new JSONObject().put("authority", "native hardware draw with composed opaque marker")
                        .put("drawIdentity", identity).put("run", run).put("markerArgb", color).put("markerX", origin[0] + 16)
                        .put("markerY", origin[1] + 456).put("composed", composed).put("frameCommitted", committed).put("scope", taps.scope())
                        .put("visibleScreenClip", clip.get().flattenToString())
                        .put("opaqueMarkerBounds", new Rect(origin[0] + 8, origin[1] + 448, origin[0] + 24, origin[1] + 464).flattenToString())
                        .put("foreignHitAtPublication", foreignHit()).put("foreignPublishedAt", foreignPublishedAt)
                        .put("visualHit", hit(VISUAL)).put("textHit", hit(TEXT));
                assertNotNull(pixels); save(pixels, name, witness);
                assertTrue("Opaque marker must remain inside the observed clip", clip.get().contains(
                        origin[0] + 8, origin[1] + 448, origin[0] + 24, origin[1] + 464));
                assertTrue("An actual hardware frame must commit", committed);
                assertTrue("Native marker must be composed", composed);
                for (BBox box : opaque) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
                    assertEquals("Opaque censor interior", Color.BLACK,
                            pixels.getPixel(origin[0] + box.getX() + box.getWidth() / 2 + x,
                                    origin[1] + box.getY() + box.getHeight() / 2 + y));
            } finally {
                if (pixels != null) pixels.recycle(); main(() -> view.getOverlay().remove(marker));
            }
        }
        void save(Bitmap bitmap, String name, JSONObject witness) throws Exception {
            String run = InstrumentationRegistry.getArguments().getString("evidence_run");
            assertNotNull(run); assertTrue(run.matches("[a-zA-Z0-9-]+"));
            File folder = new File(view.getContext().getFilesDir(), "ui-v3/" + run);
            assertTrue(folder.isDirectory() || folder.mkdirs());
            File png = new File(folder, name + ".png"), json = new File(folder, name + ".json");
            assertFalse(png.exists()); assertFalse(json.exists());
            try (FileOutputStream output = new FileOutputStream(png)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            java.nio.file.Files.write(json.toPath(), witness.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() { try { page.close(); } finally { taps.clear(); } }
    }
    private static Detection region(BBox box, String category) {
        return new Detection("TEST", category, 1f, box, true, false,
                Detection.ObservationSource.VISUAL, Detection.GeometryQuality.MODEL, null);
    }
}
