package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;

import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import static org.junit.Assert.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import org.junit.*;
import java.util.*;

public class SettingsGroupsAndroidTest {
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Before
    public void fixture() {
        ControllerPinManager.setPin(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), "2468");
        ControllerPinManager.enterDomMode();
    }

    @After
    public void restore() {
        ControllerPinManager.enterSubMode();
    }

    @Test public void settingsListsDestinationsAndPrivacyOpensAsOneFocusedPage() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a -> {
                List<String> keys = new ArrayList<>();
                collect(a.findViewById(android.R.id.content), keys);
                assertEquals(Arrays.asList("settings:privacy", "settings:help"), keys);
                assertTrue(a.findViewById(R.id.settings_connections_security).isShown());
                assertNull(a.findViewById(R.id.settings_connection));
                assertNull(a.findViewById(R.id.apps_card));
                assertNull(a.findViewById(R.id.paypal_card));
                assertFalse(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertFalse(a.findViewById(R.id.button_help).isShown());
                assertFalse(a.findViewById(R.id.hardcore_card).isShown());
            });
            open("privacy");
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(a.findViewById(R.id.hardcore_card).isShown());
                assertTrue(a.findViewById(R.id.android_access_card).isShown());
                assertFalse(a.findViewById(R.id.button_help).isShown());
                assertFalse(a.findViewById(R.id.settings_connections_security).isShown());
                int[] privacy = new int[2], permission = new int[2];
                a.findViewById(R.id.privacy_discreet_toggle).getLocationOnScreen(privacy);
                a.findViewById(R.id.android_access_card).getLocationOnScreen(permission);
                assertTrue(permission[1] > privacy[1]);
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertFalse(a.findViewById(R.id.button_help).isShown());
            });
            androidx.test.espresso.Espresso.pressBack();
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.settings_connections_security).isShown());
                assertFalse(a.findViewById(R.id.privacy_discreet_toggle).isShown());
            });
        }
    }

    @Test public void helpRestoresItsSelectionAndBackReturnsToDestinations() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            open("help");
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.button_diagnostics).isShown());
                android.widget.TextView build = a.findViewById(android.R.id.content).findViewWithTag("settings_build_info");
                assertNotNull(build);
                assertTrue(build.isShown());
                assertTrue(build.getText().toString().contains(com.subhub.app.BuildConfig.VERSION_NAME));
                assertFalse(a.findViewById(R.id.android_access_card).isShown());
            });
            scenario.recreate();
            scenario.onActivity(a -> assertTrue(a.findViewById(R.id.button_help).isShown()));
            androidx.test.espresso.Espresso.pressBack();
            scenario.onActivity(a -> {
                assertFalse(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.settings_connections_security).isShown());
                assertNull(a.findViewById(R.id.app_list_content));
            });
        }
    }

    private static void open(String key) {
        onView(withTagValue(org.hamcrest.Matchers.is("settings:" + key))).perform(revealAboveNavigation(), click());
    }

    @Test public void subCanOpenAndroidPermissionSettingsWithoutUnlockingDom() {
        ControllerPinManager.enterSubMode();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            open("privacy");
            scenario.onActivity(a -> {
                View accessibility = a.findViewById(R.id.button_accessibility_settings);
                assertTrue(accessibility.isEnabled());
                assertEquals(1f, accessibility.getAlpha(), .01f);
                assertEquals(.45f, a.findViewById(R.id.switch_hardcore_mode).getAlpha(), .01f);
            });
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_accessibility_settings))
                    .perform(revealAboveNavigation(), click());
            returnFromAndroidSettings(device);
            int[] labels = {R.string.settings_overlay, R.string.settings_notifications, R.string.settings_battery};
            for (int label : labels) {
                onView(androidx.test.espresso.matcher.ViewMatchers.withText(label))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                                androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
                        .perform(revealAboveNavigation(), click());
                returnFromAndroidSettings(device);
            }
            scenario.recreate();
            scenario.onActivity(a -> {
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(1f, a.findViewById(R.id.button_accessibility_settings).getAlpha(), .01f);
                assertEquals(.45f, a.findViewById(R.id.switch_hardcore_mode).getAlpha(), .01f);
            });
        }
    }

    private static void returnFromAndroidSettings(UiDevice device) {
        assertNotNull("The permission action must open Android Settings",
                device.wait(Until.findObject(By.pkg("com.android.settings")), 8000));
        assertFalse(ControllerPinManager.isDomModeActive());
        device.pressBack();
        assertNotNull("Back must return to SubHub without a role change",
                device.wait(Until.findObject(By.pkg("com.subhub.app")), 8000));
        assertFalse(ControllerPinManager.isDomModeActive());
    }

    private static void collect(View v, List<String> keys) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("settings:"))
            keys.add((String) v.getTag());
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                collect(((ViewGroup) v).getChildAt(i), keys); }
    }
