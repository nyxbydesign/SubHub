package com.subhub.app.fullaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.os.SystemClock;
import com.subhub.app.people.ProfileEditorActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Checks that the inline profile switch is guarded and sends no permission requests on launch. */
public final class FullAccessPermissionUiAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void enterSubWorkspace() {
        HardcoreModeManager hardcore = new HardcoreModeManager(context);
        assertFalse("Use an inactive synthetic Android guest", hardcore.isAdminActive());
        assertFalse(hardcore.isRequested());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        ControllerPinManager.enterSubMode();
        assertFalse(FullAccessGrant.isLocallyEnabled(context));
    }

    @Test public void openingSubSetupWithoutHardcoreShowsNoPermissionPrompt() {
        int sms = context.checkSelfPermission(Manifest.permission.READ_SMS);
        int contacts = context.checkSelfPermission(Manifest.permission.READ_CONTACTS);
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(ProfileEditorActivity.class)) {
            awaitSetup(scenario);
            scenario.onActivity(activity -> {
                assertTrue(activity.hasWindowFocus());
                android.widget.CompoundButton toggle = activity.findViewById(android.R.id.content)
                        .findViewWithTag("full_access_toggle");
                assertNotNull(toggle);
                assertFalse(toggle.isChecked());
                assertFalse(toggle.isEnabled());
                assertNull(activity.findViewById(android.R.id.content).findViewWithTag("full_access_guide"));
                assertNull(activity.findViewById(android.R.id.content).findViewWithTag("full_access_permission:sms_access"));
            });
            assertEquals(sms, context.checkSelfPermission(Manifest.permission.READ_SMS));
            assertEquals(contacts, context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
        }
    }

    private static void awaitSetup(ActivityScenario<ProfileEditorActivity> scenario) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> ready.set(activity.hasWindowFocus()
                    && activity.findViewById(android.R.id.content).findViewWithTag("full_access_toggle") != null));
            if (ready.get()) return;
            SystemClock.sleep(40);
        }
        fail("Full Access setup did not render in the foreground");
    }

}
