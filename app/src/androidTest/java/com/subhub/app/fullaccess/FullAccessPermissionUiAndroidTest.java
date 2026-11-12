package com.subhub.app.fullaccess;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.os.SystemClock;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Exercises Android views and the checklist callback without granting Device Admin or contacts/SMS. */
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
        try (ActivityScenario<FullAccessActivity> scenario = ActivityScenario.launch(FullAccessActivity.class)) {
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

    @Test public void guidedSetupDispatchesOnlyCheckedScopesIncludingExplicitSms() {
        try (ActivityScenario<FullAccessActivity> scenario = ActivityScenario.launch(FullAccessActivity.class)) {
            AtomicReference<List<String>> submitted = new AtomicReference<>();
            showGuide(scenario, submitted);
            onView(withText(R.string.full_access_contacts)).inRoot(isDialog()).perform(click());
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click());
            assertEquals(List.of("contacts"), submitted.get());

            submitted.set(null);
            showGuide(scenario, submitted);
            onView(withText(R.string.full_access_sms)).inRoot(isDialog()).perform(click());
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click());
            assertEquals(List.of("sms_access"), submitted.get());

            submitted.set(null);
            showGuide(scenario, submitted);
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            assertNull(submitted.get());
        }
    }

    private static void awaitSetup(ActivityScenario<FullAccessActivity> scenario) {
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

    private static void showGuide(ActivityScenario<FullAccessActivity> scenario,
            AtomicReference<List<String>> submitted) {
        scenario.onActivity(activity -> {
            AlertDialog dialog = FullAccessActivity.showPermissionChoices(activity,
                    List.of("contacts", "sms_access"),
                    List.of(activity.getText(R.string.full_access_contacts), activity.getText(R.string.full_access_sms)),
                    submitted::set);
            assertFalse(dialog.getListView().isItemChecked(0));
            assertFalse(dialog.getListView().isItemChecked(1));
            assertNull(submitted.get());
        });
    }
}
