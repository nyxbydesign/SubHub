package com.subhub.app.people;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.service.ScreenshotAccessibilityService;
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

    @Test public void profileStartAndEndReturnTheBoundAccessibilityService() {
        Assume.assumeTrue("Run only on a disposable app install",
                "true".equals(InstrumentationRegistry.getArguments().getString("demo_handoff_fixture")));
        assertEquals("Grant notifications on the disposable guest before testing the capture handoff",
                android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        assertTrue(context.getSharedPreferences("subhub_internal_registry", 0).getAll().isEmpty());
        assertFalse(LocalSessionControls.isActive(context));
        var resolver = context.getContentResolver();
        String originalServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String originalEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED);
        assertTrue(originalServices == null || originalServices.isEmpty());
        assertNotEquals("1", originalEnabled);
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
            assertTrue(ControllerPinManager.verify(context, "2468"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            OnboardingState.complete(context);
            assertTrue(DummySubPreview.toggle(context));
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
                        clickAction(page, R.string.role_end_session);
                        await("gateway and bound legacy source to restore",
                                () -> AppRuntimeGateway.current(context) == null && ScreenshotAccessibilityService.isRunning());
                    }
                } finally { instrumentation.runOnMainSync(page::finish); }
            }
            assertFalse(DummySubPreview.toggle(context));
        } finally {
            accessibilitySettings(automation, originalServices, originalEnabled);
        }
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
