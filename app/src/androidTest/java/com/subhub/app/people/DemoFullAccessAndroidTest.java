package com.subhub.app.people;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.OwnedUiIntegrationFixture;
import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.fullaccess.DemoFullAccessActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.remote.DummyPreviewEndpointFixture;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;

/** Uses the installed apps on an owned Android guest, without granting any sensitive permission. */
public final class DemoFullAccessAndroidTest {
    @Rule public final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture(false);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void demoUsesInstalledPhoneAppsInOnePageAndInvalidatesOnLock() throws Exception {
        String choices = "demo_full_access_choices";
        String[] stores = {choices, SettingsRepository.PREFERENCES_NAME, "subhub_controller_auth",
                "subhub_onboarding", "subhub_home"};
        Map<String, Map<String, ?>> previous = new LinkedHashMap<>();
        for (String name : stores) previous.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        boolean wasDom = ControllerPinManager.isDomModeActive();
        try {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
            assertTrue(ControllerPinManager.verify(context, "2468"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            OnboardingState.complete(context);
            try (InternalSubUiFixture demo = new InternalSubUiFixture(context);
                 DummyPreviewEndpointFixture endpoint = new DummyPreviewEndpointFixture(context)) {
                assertTrue(DummySubPreview.toggle(context));
                String generation = demo.data.acknowledged().generation;
                assertTrue(context.getSharedPreferences(choices, 0).edit().clear()
                        .putString("enabled_generation", generation).commit());
                Map<String, List<String>> relationshipBefore = endpoint.snapshot();
                try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
                    scenario.onActivity(DummySubPreview::open);
                    PersonActivity profile = awaitResumed(PersonActivity.class);
                    await(() -> onMain(() -> profile.findViewById(android.R.id.content)
                            .findViewWithTag("person_tab:full_access") != null));
                    instrumentation.runOnMainSync(() -> {
                        View row = profile.findViewById(android.R.id.content).findViewWithTag("person_tab:full_access");
                        assertNotNull(row);
                        assertTrue(row.performClick());
                    });
                    DemoFullAccessActivity viewer = awaitResumed(DemoFullAccessActivity.class);
                    List<ApplicationInfo> installed = new ArrayList<>(context.getPackageManager().getInstalledApplications(0));
                    installed.removeIf(app -> (app.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
                    installed.sort(Comparator.comparing(app -> app.packageName));
                    assertFalse(installed.isEmpty());
                    String realApp = installed.get(0).loadLabel(context.getPackageManager()).toString();
                    await(() -> onMain(() -> {
                        View root = viewer.findViewById(android.R.id.content);
                        View apps = root.findViewWithTag("full_access_section:apps");
                        return apps != null && contains(apps, realApp);
                    }));
                    instrumentation.runOnMainSync(() -> {
                        View root = viewer.findViewById(android.R.id.content);
                        for (String scope : List.of("contacts", "notifications", "sms", "apps", "files", "screen"))
                            assertNotNull(scope, root.findViewWithTag("full_access_section:" + scope));
                        assertNull(root.findViewWithTag("full_access_tabs"));
                        assertTrue(contains(root, context.getString(R.string.blackmail_messages)));
                        assertTrue(contains(root, context.getString(R.string.full_access_browse_files)));
                        assertTrue(contains(root, context.getString(R.string.full_access_view_screen)));
                        assertNull(MediaProjectionLeaseRegistry.owner());
                    });
                    assertEquals("Viewing phone data must not write to the peer endpoint", relationshipBefore, endpoint.snapshot());
                    // The explicit workspace change itself updates linking authority.
                    instrumentation.runOnMainSync(ControllerPinManager::enterSubMode);
                    await(() -> onMain(viewer::isFinishing));
                    instrumentation.runOnMainSync(profile::finish);
                }
                integration.drainReaders();
            }
        } finally {
            for (String name : stores)
                SharedPreferenceTestRestore.restore(context.getSharedPreferences(name, 0), previous.get(name));
            if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        }
    }

    private <T extends Activity> T awaitResumed(Class<T> kind) {
        AtomicReference<T> found = new AtomicReference<>();
        await(() -> onMain(() -> {
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (kind.isInstance(candidate)) {
                    found.set(kind.cast(candidate));
                    return true;
                }
            }
            return false;
        }));
        return found.get();
    }

    private boolean onMain(BooleanSupplier condition) {
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(() -> result.set(condition.getAsBoolean()));
        return result.get();
    }

    private static boolean contains(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++)
            if (contains(group.getChildAt(index), text)) return true;
        return false;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Demo Full Access state did not appear");
    }
}
