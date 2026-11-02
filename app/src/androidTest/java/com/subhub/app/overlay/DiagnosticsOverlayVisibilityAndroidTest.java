package com.subhub.app.overlay;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.StatsRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Native visibility witnesses exercise real preference consumers without a capture permission. */
public final class DiagnosticsOverlayVisibilityAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> originals = new LinkedHashMap<>();
    private SettingsRepository settings;
    private final Map<Field, Object> originalTelemetry = new LinkedHashMap<>();

    @Before public void prepare() throws Exception {
        assertFalse("Owned inactive guest required", RuntimeSessionIdentity.active(context));
        for (String name : List.of(SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME, SubFeaturePolicy.STORE))
            originals.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        synchronized (DiagnosticsRepository.class) {
            for (Field field : DiagnosticsRepository.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true); originalTelemetry.put(field, field.get(null));
                }
            }
        }
        settings = new SettingsRepository(context);
        settings.saveCaptureMethod(CaptureMethod.APP_MODE);
        new FeatureModuleManager(context).setCensorEnabled(true);
        new AppModeManager(context).saveIncludedPackages(java.util.Set.of("fixture.target"));
        new AppModeManager(context).setAllAppsEnabled(false);
        ControllerPinManager.enterSubMode();
    }

    @After public void restore() throws Exception {
        new AppModeManager(context).setArmed(false);
        for (Map.Entry<String, Map<String, ?>> entry : originals.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
        synchronized (DiagnosticsRepository.class) {
            for (Map.Entry<Field, Object> entry : originalTelemetry.entrySet()) entry.getKey().set(null, entry.getValue());
        }
        ControllerPinManager.enterSubMode();
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subHidesVisibleDiagnosticsWhileSessionAndCensorPixelsRemainInBothConsumers() throws Exception {
        for (boolean accessibility : new boolean[]{true, false}) {
            String name = accessibility ? "accessibility" : "projection";
            settings.preferences().edit().putBoolean(DiagnosticsRepository.PREF_OVERLAY, true).commit();
            AppModeManager mode = new AppModeManager(context); mode.setArmed(true);
            String identity = RuntimeSessionIdentity.current(context);
            assertFalse(identity.isEmpty()); assertTrue(mode.shouldRecognize("fixture.target"));
            Map<String, ?> consent = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
            // Seed metrics for the synthetic renderer; no capture service or collector is started.
            DiagnosticsRepository.begin("OverlayFixture", 320);
            DiagnosticsRepository.ready("OverlayFixture", "Fixture", "fixture.tflite", 320);
            DiagnosticsRepository.recordFrame("OverlayFixture", 6, 1, 1000, 1000);
            DiagnosticsRepository.Snapshot diagnostics = DiagnosticsRepository.snapshot();
            assertFalse(DiagnosticsRepository.overlayText(diagnostics).isEmpty());
            try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
                AtomicReference<OverlayController> controller = new AtomicReference<>();
                AtomicReference<CensorOverlayView> view = new AtomicReference<>();
                AtomicReference<Service> adapter = new AtomicReference<>();
                AtomicReference<SharedPreferences.OnSharedPreferenceChangeListener> consumer = new AtomicReference<>();
                try {
                    scenario.onActivity(activity -> {
                        try {
                            OverlayController overlay = new OverlayController(activity, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
                            controller.set(overlay); overlay.setAppearance(new CensorAppearance(
                                    CensorAppearance.Type.BOX, 1, 0f, false, false,
                                    CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                                    List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
                            ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
                            overlay.update(tracker.update(List.of(new Detection("EXPOSED_TEST", "EXPOSED", 1f,
                                    new BBox(650, 320, 900, 470), true, true))), 1000, 1000, null);
                            overlay.setDiagnostics(DiagnosticsRepository.overlayText(diagnostics)); overlay.show();
                            Field rendering = OverlayController.class.getDeclaredField("view"); rendering.setAccessible(true);
                            CensorOverlayView renderingView = (CensorOverlayView) rendering.get(overlay); view.set(renderingView);
                            renderingView.setRenderTimeForTest(SystemClock.uptimeMillis());
                            Service service = accessibility ? new ScreenshotAccessibilityService() : new ScreenCaptureService();
                            adapter.set(service);
                            Field store = service.getClass().getDeclaredField("settings"); store.setAccessible(true); store.set(service, settings);
                            Field target = service.getClass().getDeclaredField("overlay"); target.setAccessible(true); target.set(service, overlay);
                            Field callback = service.getClass().getDeclaredField(accessibility ? "listener" : "settingsListener"); callback.setAccessible(true);
                            SharedPreferences.OnSharedPreferenceChangeListener listener =
                                    (SharedPreferences.OnSharedPreferenceChangeListener) callback.get(service);
                            consumer.set(listener); settings.preferences().registerOnSharedPreferenceChangeListener(listener);
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    });
                    capture(view.get(), name + "-before", true);
                    scenario.onActivity(activity -> {
                        CompoundButton toggle = activity.findViewById(R.id.switch_diagnostics_overlay);
                        assertTrue(toggle.isEnabled()); assertTrue(toggle.isChecked());
                        assertFalse(activity.findViewById(R.id.switch_replacement_candidate).isEnabled());
                    });
                    onView(withId(R.id.switch_diagnostics_overlay)).perform(scrollTo(), click());
                    capture(view.get(), name + "-off", false);
                    scenario.onActivity(activity -> {
                        try {
                            java.lang.reflect.Method publish = adapter.get().getClass().getDeclaredMethod("publishDiagnostics", String.class);
                            publish.setAccessible(true);
                            publish.invoke(adapter.get(), DiagnosticsRepository.overlayText(diagnostics));
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    });
                    capture(view.get(), name + "-late-frame-off", false);
                    assertFalse(settings.preferences().getBoolean(DiagnosticsRepository.PREF_OVERLAY, true));
                    assertTrue(mode.isArmed()); assertTrue(RuntimeSessionIdentity.active(context));
                    assertEquals(identity, RuntimeSessionIdentity.current(context));
                    assertTrue(mode.shouldRecognize("fixture.target")); assertFalse(ControllerPinManager.isDomModeActive());
                    assertEquals(consent, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
                    assertEquals(diagnostics.getFrames(), DiagnosticsRepository.snapshot().getFrames());
                    assertEquals(diagnostics.getMode(), DiagnosticsRepository.snapshot().getMode());
                    scenario.onActivity(activity -> {
                        controller.get().close(); controller.set(null);
                        settings.preferences().unregisterOnSharedPreferenceChangeListener(consumer.get()); consumer.set(null);
                    });
                    scenario.recreate();
                    scenario.onActivity(activity -> {
                        CompoundButton toggle = activity.findViewById(R.id.switch_diagnostics_overlay);
                        assertFalse(toggle.isChecked()); assertFalse(toggle.isEnabled());
                        toggle.setChecked(true);
                        assertFalse("Sub cannot turn diagnostics back on", toggle.isChecked());
                        assertFalse(settings.preferences().getBoolean(DiagnosticsRepository.PREF_OVERLAY, true));
                    });
                    assertEquals(identity, RuntimeSessionIdentity.current(context));
                } finally {
                    scenario.onActivity(activity -> {
                        if (controller.get() != null) controller.get().close();
                        if (consumer.get() != null) settings.preferences().unregisterOnSharedPreferenceChangeListener(consumer.get());
                    });
                }
            } finally { mode.setArmed(false); }
        }
    }

    @Test public void inactiveSubCanTurnOffAndDomKeepsItsExistingEnableControl() {
        settings.preferences().edit().putBoolean(DiagnosticsRepository.PREF_OVERLAY, true).commit();
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            onView(withId(R.id.switch_diagnostics_overlay)).perform(scrollTo(), click());
            scenario.recreate();
            scenario.onActivity(activity -> {
                CompoundButton toggle = activity.findViewById(R.id.switch_diagnostics_overlay);
                assertFalse(toggle.isChecked()); assertFalse(toggle.isEnabled());
            });
            assertFalse(RuntimeSessionIdentity.active(context));
        }
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.switch_diagnostics_overlay).isEnabled()));
            onView(withId(R.id.switch_diagnostics_overlay)).perform(scrollTo(), click());
            assertTrue(settings.preferences().getBoolean(DiagnosticsRepository.PREF_OVERLAY, false));
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((CompoundButton) activity.findViewById(R.id.switch_diagnostics_overlay)).isChecked()));
        }
    }

    private void capture(CensorOverlayView view, String name, boolean visible) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("evidence_run");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        AtomicReference<int[]> geometry = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.getViewTreeObserver().registerFrameCommitCallback(ready::countDown); view.invalidate();
        });
        assertTrue("Native overlay frame must commit", ready.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] location = new int[2]; view.getLocationOnScreen(location);
            float density = view.getResources().getDisplayMetrics().density;
            geometry.set(new int[]{location[0] + Math.round(13.5f * density), location[1] + Math.round(42 * density),
                    location[0] + view.getWidth() * 3 / 4, location[1] + view.getHeight() * 39 / 100});
        });
        int[] points = geometry.get(); int accent = context.getColor(R.color.accent);
        JSONObject witness = new JSONObject().put("visible", visible).put("diagnosticsX", points[0]).put("diagnosticsY", points[1])
                .put("maskX", points[2]).put("maskY", points[3]).put("expectedAccent", accent)
                .put("sessionActive", RuntimeSessionIdentity.active(context)).put("captureServiceBound", false);
        boolean matched = false; int panel = 0, mask = 0; Bitmap pixels = null;
        long until = SystemClock.uptimeMillis() + 5000;
        try {
            do {
                if (pixels != null) pixels.recycle();
                pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(pixels);
                panel = pixels.getPixel(points[0], points[1]); mask = pixels.getPixel(points[2], points[3]);
                matched = (visible == (panel == accent)) && mask == Color.BLACK;
                if (!matched) SystemClock.sleep(30);
            } while (!matched && SystemClock.uptimeMillis() < until);
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            witness.put("observedPanelArgb", panel).put("observedMaskArgb", mask).put("matched", matched);
            Files.write(new File(folder, name + ".pixels.json").toPath(), witness.toString(2).getBytes(StandardCharsets.UTF_8));
            assertTrue("Diagnostics visibility and opaque enforcement pixels: " + witness, matched);
        } finally { if (pixels != null) pixels.recycle(); }
    }
}
