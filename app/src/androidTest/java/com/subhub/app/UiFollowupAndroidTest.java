package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.people.LocalAppsActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import org.junit.*;

/** Focused follow-up contracts and captures; never starts a real payment or service. */
public class UiFollowupAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private String overlayMode;

    @Before public void fixture() throws Exception {
        preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        original = preferences.getAll();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        Set<String> selected = Set.of("com.android.chrome", "com.android.settings");
        for (String name : selected) context.getPackageManager().getApplicationInfo(name, 0);
        AppModeManager assignments = new AppModeManager(context);
        assignments.setAllAppsEnabled(false);
        assignments.saveIncludedPackages(selected);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(selected, Map.of("com.android.chrome", 35, "com.android.settings", 75));
        assertEquals(selected, assignments.getIncludedPackages());
        assertEquals(35, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(75, timers.allowanceMinutes("com.android.settings"));
    }

    @After public void restore() throws Exception {
        try {
            SharedPreferenceTestRestore.restore(preferences, original);
        } finally {
            try {
                if (overlayMode != null) shell("appops set " + context.getPackageName()
                        + " SYSTEM_ALERT_WINDOW " + overlayMode);
            } finally { ControllerPinManager.enterSubMode(); }
        }
    }

    @Test public void settingsOwnsBuildPrivacyAndSecurityWhileAppsHasOnePersonEditor() {
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            page.onActivity(a -> {
                com.subhub.app.util.ExpandableSectionView help = section(a, "help");
                assertEquals("Help & About", ((TextView) help.findViewById(R.id.section_title)).getText().toString());
                assertEquals("Build: " + BuildConfig.VERSION_NAME + " | " + BuildConfig.BUILD_DATE,
                        help.summary().getText().toString());
                assertTrue(help.summary().isShown());
                com.subhub.app.util.ExpandableSectionView privacy = section(a, "privacy");
                assertEquals("Privacy & Permissions", ((TextView) privacy.findViewById(R.id.section_title)).getText().toString());
                assertFalse(privacy.summary().getText().toString().contains("access settings"));
                assertNull(a.findViewById(R.id.feature_areas_card));
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertFalse(privacy.header().isClickable());
                assertFalse(help.header().isClickable());
                assertNull(a.findViewById(R.id.button_packs));
                assertNull(a.findViewById(R.id.apps_card));
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("settings:apps"));
                assertEquals(View.GONE, a.findViewById(R.id.settings_connection).getVisibility());
                assertEquals(context.getString(R.string.role_security),
                        ((TextView) a.findViewById(R.id.settings_connections_security)).getText().toString());
                capture(a, "settings-overview");
            });
            page.recreate();
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.app_list_content));
                android.widget.ScrollView scroll = (android.widget.ScrollView) a.findViewById(R.id.settings_sections).getParent();
                scroll.scrollTo(0, Integer.MAX_VALUE);
                capture(a, "settings-help");
            });
        }
        try (ActivityScenario<LocalAppsActivity> apps = ActivityScenario.launch(new Intent(context, LocalAppsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            awaitPicker(apps);
            apps.onActivity(a -> {
                assertTrue(a.findViewById(R.id.app_list_content).isShown());
                assertNotNull(a.findViewById(R.id.all_apps));
                assertNull(a.findViewById(R.id.button_toggle_apps));
                assertEquals(PersonId.LOCAL, PersonRoutes.requireId(a.getIntent()));
                capture(a, "person-apps");
            });
            apps.recreate();
            awaitPicker(apps);
            apps.onActivity(a -> assertTrue(a.findViewById(R.id.app_list_content).isShown()));
            assertEquals(Set.of("com.android.chrome", "com.android.settings"), new AppModeManager(context).getIncludedPackages());
        }
    }

    @Test public void atmosphereContainsEffectsAndSubCannotReachTheirEditors() {
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).commit();
        new FeatureModuleManager(context).setSubliminalEnabled(true);
        try (ActivityScenario<AtmosphereActivity> page = ActivityScenario.launch(new Intent(context, AtmosphereActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.switch_whispers));
                assertNull(a.findViewById(R.id.switch_popup_storm));
                assertEquals("Enabled", ((TextView) a.findViewById(R.id.whispers_status)).getText().toString());
                assertEquals("Disabled", ((TextView) a.findViewById(R.id.popup_storm_status)).getText().toString());
                assertNotNull(a.findViewById(R.id.whispers_card));
                assertNotNull(a.findViewById(R.id.popup_storm_card));
                for (int id : new int[] {R.id.rituals_keyholder_card, R.id.rituals_packs_card,
                        R.id.rituals_gallery_card, R.id.achievements_home_card}) assertNull(a.findViewById(id));
                capture(a, "atmosphere-effects");
            });
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AtmosphereActivity> page = ActivityScenario.launch(new Intent(context, AtmosphereActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            long end = android.os.SystemClock.uptimeMillis() + 5000;
            while (page.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED
                    && android.os.SystemClock.uptimeMillis() < end) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                android.os.SystemClock.sleep(25);
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, page.getState());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
        onView(androidx.test.espresso.matcher.ViewMatchers.withHint(R.string.controller_pin_label))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        try (ActivityScenario<com.subhub.app.security.AuthenticatorActivity> page =
                ActivityScenario.launch(com.subhub.app.security.AuthenticatorActivity.class)) {
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.keyholder_pin_change_button));
                assertNull(a.findViewById(R.id.keyholder_pair_button));
                assertNull(a.findViewById(R.id.keyholder_pair_device));
                assertFalse(ControllerPinManager.isDomModeActive());
                capture(a, "security-sub");
            });
        }
    }

    @Test public void detailTogglesPersistAndPopupConsentCannotBeSkipped() throws Exception {
        new FeatureModuleManager(context).setSubliminalEnabled(false);
        try (ActivityScenario<SubliminalSettingsActivity> page = ActivityScenario.launch(SubliminalSettingsActivity.class)) {
            onView(withId(R.id.switch_whispers)).perform(scrollTo(), click());
            assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.switch_whispers)).isChecked());
                capture(a, "subliminal-toggle");
                ControllerPinManager.enterSubMode();
                ((CompoundButton) a.findViewById(R.id.switch_whispers)).setChecked(false);
                assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            });
        }
        ControllerPinManager.enterDomMode();
        prepareOverlayPermission();
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false)
                .putBoolean(PopupStormSettings.K_ACK, false).commit();
        try (ActivityScenario<PopupStormActivity> page = ActivityScenario.launch(PopupStormActivity.class)) {
            onView(withId(R.id.switch_popup_storm)).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).perform(click());
            assertFalse(PopupStormSettings.load(context).isEnabled());
            assertFalse(PopupStormSettings.load(context).isAcknowledged());
            onView(withId(R.id.switch_popup_storm)).perform(scrollTo(), click());
            assertFalse(PopupStormSettings.load(context).isEnabled());
            onView(withId(android.R.id.button1))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            assertTrue(PopupStormSettings.load(context).isAcknowledged());
            assertTrue(PopupStormSettings.load(context).isEnabled());
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.switch_popup_storm)).isChecked());
                capture(a, "popup-toggle");
                ControllerPinManager.enterSubMode();
                ((CompoundButton) a.findViewById(R.id.switch_popup_storm)).setChecked(false);
                assertTrue(PopupStormSettings.load(context).isEnabled());
            });
        }
    }

    private void prepareOverlayPermission() throws Exception {
        if (android.provider.Settings.canDrawOverlays(context)) return;
        String current = shell("appops get " + context.getPackageName() + " SYSTEM_ALERT_WINDOW");
        java.util.regex.Matcher mode = java.util.regex.Pattern
                .compile("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)").matcher(current);
        if (mode.find()) overlayMode = mode.group(1);
        else if (current.contains("No operations")) overlayMode = "default";
        else throw new AssertionError("Unknown overlay permission fixture state");
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        assertTrue("Overlay permission fixture must be ready", android.provider.Settings.canDrawOverlays(context));
    }

    private static String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).executeShellCommand(command);
                java.io.FileInputStream input = new java.io.FileInputStream(descriptor.getFileDescriptor());
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test public void tourShowsTheDetailedMaskedFigure() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            onView(withId(R.id.tour_next)).perform(click());
            page.onActivity(a -> capture(a, "tour-figure"));
        }
    }

    private static com.subhub.app.util.ExpandableSectionView section(Activity a, String key) {
        View header = a.findViewById(android.R.id.content).findViewWithTag("settings:" + key);
        return (com.subhub.app.util.ExpandableSectionView) header.getParent();
    }

    private static void awaitPicker(ActivityScenario<LocalAppsActivity> page) {
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            page.onActivity(a -> {
                android.widget.ListView list = a.findViewById(R.id.app_list);
                loaded.set(list != null && list.getAdapter() != null && list.getAdapter().getCount() > 0);
            });
            if (!loaded.get()) android.os.SystemClock.sleep(25);
        } while (!loaded.get() && android.os.SystemClock.uptimeMillis() < end);
        assertTrue("Person app catalog did not load", loaded.get());
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Runnable save = () -> {
            Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "followup-" + name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { image.recycle(); }
        };
        if (root.getWidth() > 0 && root.getHeight() > 0) save.run();
        else root.post(save);
    }
}
