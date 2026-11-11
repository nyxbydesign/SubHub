package com.subhub.app.people;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.censor.android.ProductTargetFixtureActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.runtime.RuntimeOwnerStore;
import com.subhub.app.runtime.SQLiteRuntimeOwnerBackend;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.SettingsRepository;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;

/** Run on a disposable app install with no existing Demo profile or Accessibility services. */
@SdkSuppress(minSdkVersion = 34)
public final class DemoSessionHandoffAndroidTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext().getApplicationContext();

    @Test public void profileStartAndEndReturnTheBoundAccessibilityService() throws Exception {
        Assume.assumeTrue("Run only on a disposable app install",
                "true".equals(InstrumentationRegistry.getArguments().getString("demo_handoff_fixture")));
        assertEquals("Grant notifications on the disposable guest before testing the capture handoff",
                android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        boolean reuse = "true".equals(InstrumentationRegistry.getArguments().getString("demo_handoff_reuse"));
        boolean verifyCapture = "true".equals(InstrumentationRegistry.getArguments().getString("demo_handoff_capture"));
        if (reuse) {
            try (var journal = new SQLiteRuntimeOwnerBackend(context)) {
                assertEquals(RuntimeOwnerStore.Phase.IDLE, journal.read().phase);
            }
        } else assertTrue(context.getSharedPreferences("subhub_internal_registry", 0).getAll().isEmpty());
        assertFalse(LocalSessionControls.isActive(context));
        var resolver = context.getContentResolver();
        String originalServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String originalEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED);
        assertTrue(originalServices == null || originalServices.isEmpty());
        assertNotEquals("1", originalEnabled);
        Configurator automationConfig = Configurator.getInstance();
        int originalAutomationFlags = automationConfig.getUiAutomationFlags();
        int automationFlags = (originalAutomationFlags | UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                & ~UiAutomation.FLAG_DONT_USE_ACCESSIBILITY;
        automationConfig.setUiAutomationFlags(automationFlags);
        UiAutomation automation = instrumentation.getUiAutomation(automationFlags);
        try {
            if (!ControllerPinManager.isConfigured(context)) assertTrue(ControllerPinManager.setPin(context, "2468"));
            assertTrue(ControllerPinManager.verify(context, "2468"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            OnboardingState.complete(context);
            if (reuse) InternalSubData.get(context);
            if (!DummySubPreview.isEnabled(context)) assertTrue(DummySubPreview.toggle(context));
            if (verifyCapture) configureCaptureTarget();
            String component = new ComponentName(context, ScreenshotAccessibilityService.class).flattenToString();
            accessibilitySettings(automation, component, "1");
            await("ordinary Accessibility source to bind", ScreenshotAccessibilityService::isRunning);
            try (ActivityScenario<PeopleActivity> people = ActivityScenario.launch(PeopleActivity.class)) {
                people.onActivity(DummySubPreview::open);
                AtomicReference<PersonActivity> profile = new AtomicReference<>();
                await("Demo profile to resume", () -> {
                    instrumentation.runOnMainSync(() -> {
                        for (var activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                            if (activity instanceof PersonActivity person && DummySubPreview.isAllowed(person)) profile.set(person);
                    });
                    return profile.get() != null;
                });
                var page = profile.get();
                try {
                    for (int pass = 0; pass < 2; pass++) {
                        clickAction(page, R.string.role_start_session);
                        awaitAction(page, R.string.role_end_session);
                        assertNotNull(AppRuntimeGateway.current(context));
                        assertFalse("Legacy source must be parked during Demo", ScreenshotAccessibilityService.isRunning());
                        if (verifyCapture && pass == 1) verifyForegroundMask(automation);
                        clickAction(page, R.string.role_end_session);
                        await("gateway and bound legacy source to restore",
                                () -> AppRuntimeGateway.current(context) == null && ScreenshotAccessibilityService.isRunning());
                    }
                } finally { instrumentation.runOnMainSync(page::finish); }
            }
            assertFalse(DummySubPreview.toggle(context));
        } finally {
            try { accessibilitySettings(automation, originalServices, originalEnabled); }
            finally { automationConfig.setUiAutomationFlags(originalAutomationFlags); }
        }
    }

    private void configureCaptureTarget() throws Exception {
        var data = InternalSubData.get(context);
        var record = data.acknowledged();
        var draft = data.configuration.edit(record);
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit()
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Set.of(instrumentation.getContext().getPackageName()))
                .putBoolean("text_smut_enabled", true)
                .putString("censor_type", "box")
                .putInt("censor_intensity", 100)
                .apply();
        var saved = data.ownerData().submitEditor(record.generation, ControllerPinManager.domWorkspaceRevision(), access -> {
            access.requireCurrent();
            return data.configuration.flush(draft);
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(saved.configurationRevision > record.configurationRevision);
    }

    private void verifyForegroundMask(UiAutomation automation) {
        UiDevice device = UiDevice.getInstance(instrumentation);
        String testPackage = instrumentation.getContext().getPackageName();
        ComponentName target = new ComponentName(testPackage, ProductTargetFixtureActivity.class.getName());
        context.startActivity(new Intent().setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("label", "porn"));
        try {
            var label = device.wait(Until.findObject(By.pkg(testPackage).text("porn")), 12_000);
            assertNotNull("Synthetic target label must be foreground", label);
            Rect bounds = label.getVisibleBounds();
            await("Demo Censor to cover the synthetic label", () -> {
                Bitmap screenshot = automation.takeScreenshot();
                if (screenshot == null) return false;
                try { return opaqueMask(screenshot, bounds); }
                finally { screenshot.recycle(); }
            });
        } catch (AssertionError failure) {
            retainCaptureFailure(automation, failure);
            throw failure;
        } finally { assertTrue(device.pressBack()); }
    }

    private void retainCaptureFailure(UiAutomation automation, AssertionError failure) {
        java.io.File directory = context.getExternalFilesDir(null);
        if (directory == null) return;
        Bitmap screenshot = automation.takeScreenshot();
        if (screenshot == null) return;
        java.io.File output = new java.io.File(directory, "demo-handoff-capture-failure.png");
        try (var stream = new java.io.FileOutputStream(output)) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (java.io.IOException unavailable) {
            failure.addSuppressed(unavailable);
        } finally {
            screenshot.recycle();
        }
    }

    private static boolean opaqueMask(Bitmap screenshot, Rect bounds) {
        if (bounds.width() < 12 || bounds.height() < 12 || bounds.left < 1 || bounds.top < 1
                || bounds.right + 20 >= screenshot.getWidth() || bounds.bottom >= screenshot.getHeight()) return false;
        int backdrop = screenshot.getPixel(bounds.right + 20, bounds.centerY());
        if ((backdrop & 0x00ffffff) != (ProductTargetFixtureActivity.UNDERLYING & 0x00ffffff)) return false;
        int dark = 0, samples = 0;
        for (int y = bounds.top + 3; y < bounds.bottom - 3; y += 2) {
            for (int x = bounds.left + 3; x < bounds.right - 3; x += 2) {
                int pixel = screenshot.getPixel(x, y);
                if (android.graphics.Color.red(pixel) < 40 && android.graphics.Color.green(pixel) < 40
                        && android.graphics.Color.blue(pixel) < 40) dark++;
                samples++;
            }
        }
        return dark * 4 >= samples * 3;
    }

    private void accessibilitySettings(UiAutomation automation, String services, String enabled) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services));
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled));
        } finally { automation.dropShellPermissionIdentity(); }
    }

    private void clickAction(PersonActivity page, int label) {
        awaitAction(page, label);
        instrumentation.runOnMainSync(() -> assertTrue(page.findViewById(R.id.person_session_action).performClick()));
    }

    private void awaitAction(PersonActivity page, int label) {
        AtomicBoolean ready = new AtomicBoolean();
        await(context.getString(label), () -> {
            instrumentation.runOnMainSync(() -> {
                TextView action = page.findViewById(R.id.person_session_action);
                ready.set(action != null && action.isEnabled() && action.getText().toString().equals(context.getString(label)));
            });
            return ready.get();
        });
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out waiting for " + description);
    }
}
