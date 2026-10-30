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
    private Map<String,?> namesBefore,consentBefore;
    @Before
    public void fixture() {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        namesBefore=new HashMap<>(context.getSharedPreferences("subhub_profile_names",0).getAll());
        consentBefore=new HashMap<>(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        new SettingsRepository(InstrumentationRegistry.getInstrumentation().getTargetContext()).preferences().edit()
                .remove(com.subhub.app.security.DeviceRolePreferences.KEY_MODE).commit();
        ControllerPinManager.setPin(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), "2468");
        ControllerPinManager.enterDomMode();
    }

    @After
    public void restore() {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences.Editor names=context.getSharedPreferences("subhub_profile_names",0).edit().clear();
        for(Map.Entry<String,?> entry:namesBefore.entrySet())names.putString(entry.getKey(),(String)entry.getValue());names.commit();
        android.content.SharedPreferences.Editor consent=context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).edit().clear();
        for(Map.Entry<String,?> entry:consentBefore.entrySet())consent.putBoolean(entry.getKey(),(Boolean)entry.getValue());consent.commit();
        ControllerPinManager.enterSubMode();
    }

    @Test public void inlineGroupsKeepPrivacyPermissionsAndHelpWithoutHardcore() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a -> {
                List<String> keys = new ArrayList<>();
                collect(a.findViewById(android.R.id.content), keys);
                assertEquals(Arrays.asList("settings:privacy", "settings:permissions", "settings:help"), keys);
                assertTrue(a.findViewById(R.id.settings_connections_security).isShown());
                assertNull(a.findViewById(R.id.settings_connection));
                assertNull(a.findViewById(R.id.apps_card));
                assertNull(a.findViewById(R.id.paypal_card));
                assertNull(a.findViewById(R.id.hardcore_card));
                assertNull(a.findViewById(R.id.switch_hardcore_mode));
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.android_access_card).isShown());
                int[] privacy = new int[2], permission = new int[2];
                a.findViewById(R.id.privacy_discreet_toggle).getLocationOnScreen(privacy);
                a.findViewById(R.id.android_access_card).getLocationOnScreen(permission);
                assertTrue(permission[1] > privacy[1]);
                for (String key : keys)
                    assertFalse(a.findViewById(android.R.id.content).findViewWithTag(key).isClickable());
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.android_access_card).isShown());
            });
        }
    }

    @Test public void inlineHelpKeepsBuildUpdatesAndDiagnosticsBesidePrivacy() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_diagnostics)).perform(revealAboveNavigation());
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.button_diagnostics).isShown());
                assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("settings_build_info"));
                assertNull(a.findViewById(R.id.app_list_content));
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertTrue(a.findViewById(R.id.settings_connections_security).isShown());
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
            });
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29) public void subCanOpenAndroidPermissionSettingsWithoutUnlockingDom() throws Exception {
        ControllerPinManager.enterSubMode();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String start = InstrumentationRegistry.getArguments().getString("permission_start", "accessibility");
        assertTrue(Arrays.asList("accessibility", "overlay", "notifications", "battery").contains(start));
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a -> {
                View accessibility = a.findViewById(R.id.button_accessibility_settings);
                assertTrue(accessibility.isEnabled());
                assertEquals(1f, accessibility.getAlpha(), .01f);
                assertNotNull(a.findViewById(R.id.switch_hardcore_mode));
                assertEquals(1f, a.findViewById(R.id.switch_hardcore_mode).getAlpha(), .01f);
            });
            awaitFocusedFrame(scenario);
            if ("accessibility".equals(start)) {
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_accessibility_settings))
                        .perform(revealAboveNavigation(), click());
                returnFromAndroidSettings(device, scenario, "accessibility");
            }
            int[] labels = {R.string.settings_overlay, R.string.settings_notifications, R.string.settings_battery};
            String[] keys = {"overlay", "notifications", "battery"};
            int first = "accessibility".equals(start) ? 0 : Arrays.asList(keys).indexOf(start);
            for (int index = first; index < labels.length; index++) {
                int label = labels[index];
                onView(androidx.test.espresso.matcher.ViewMatchers.withText(label))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                                androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
                        .perform(revealAboveNavigation(), click());
                returnFromAndroidSettings(device, scenario, keys[index]);
            }
            scenario.recreate();
            scenario.onActivity(a -> {
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(1f, a.findViewById(R.id.button_accessibility_settings).getAlpha(), .01f);
                assertNotNull(a.findViewById(R.id.switch_hardcore_mode));
                assertEquals(1f, a.findViewById(R.id.switch_hardcore_mode).getAlpha(), .01f);
            });
        }
    }

    @Test public void settingsKeepProfileControlsAbsentAcrossRoleVariants() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (com.subhub.app.security.DeviceRolePreferences.Mode mode : com.subhub.app.security.DeviceRolePreferences.Mode.values()) {
            new SettingsRepository(context).preferences().edit().putString(
                    com.subhub.app.security.DeviceRolePreferences.KEY_MODE, mode.name()).commit();
            ControllerPinManager.enterDomMode();
            try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
                assertNoProfileControls(page); page.recreate(); assertNoProfileControls(page);
            }
            if (mode != com.subhub.app.security.DeviceRolePreferences.Mode.DOM_ONLY) {
                ControllerPinManager.enterSubMode();
                try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
                    assertNoProfileControls(page); page.recreate(); assertNoProfileControls(page);
                }
            }
        }
    }
    @Test public void settingsRoleSwitchPreservesSavedNamesWithoutDuplicateEditors() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.subhub.app.people.ProfileNames.set(context, true, "Fixture Dom");
        com.subhub.app.people.ProfileNames.set(context, false, "Fixture Sub");
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            assertNoProfileControls(page);
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_edit_lock)).perform(click());
            assertNoProfileControls(page); page.recreate(); assertNoProfileControls(page);
            assertEquals("Fixture Dom", com.subhub.app.people.ProfileNames.get(context, true));
            assertEquals("Fixture Sub", com.subhub.app.people.ProfileNames.get(context, false));
        }
    }
    private static void assertNoProfileControls(ActivityScenario<GlobalSettingsActivity> page) {
        page.onActivity(a -> {
            View root = a.findViewById(android.R.id.content);
            assertNotNull(root.findViewWithTag("settings_device"));
            assertNull(root.findViewWithTag("settings_profile_names"));
            assertNull(root.findViewWithTag("settings_feature_choices"));
            assertNull(root.findViewWithTag("profile_name:dom"));
            assertNull(root.findViewWithTag("profile_name:sub"));
            for (String key : com.subhub.app.security.SubFeaturePolicy.FEATURES)
                assertNull(root.findViewWithTag("sub_feature:" + key));
        });
    }

    @Test public void hardcoreConsentIsLocalAndDoesNotRequireAControllerUnlock() throws Exception {
        ControllerPinManager.enterSubMode();
        try(ActivityScenario<GlobalSettingsActivity> page=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.switch_hardcore_mode)).perform(revealAboveNavigation(),click());
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.hardcore_consent_title))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel)).perform(click());
            assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(InstrumentationRegistry.getInstrumentation().getTargetContext()));
        }
    }
    @androidx.annotation.RequiresApi(29)
    private void capture(ActivityScenario<GlobalSettingsActivity> page,String name) throws Exception {
        java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);
        page.onActivity(a->{android.view.View root=a.getWindow().getDecorView();root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);root.invalidate();});
        assertTrue(frame.await(5,java.util.concurrent.TimeUnit.SECONDS));
        java.io.File directory=new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"ui-v3-captures");directory.mkdirs();
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(new java.io.File(directory,name+".png")));
    }

    @androidx.annotation.RequiresApi(29)
    private static void returnFromAndroidSettings(UiDevice device, ActivityScenario<GlobalSettingsActivity> scenario,
            String key) throws Exception {
        Object settings = device.wait(Until.findObject(By.pkg("com.android.settings")), 8000);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "settings-native");
        assertTrue(run.matches("[a-zA-Z0-9-]+")); assertTrue(key.matches("[a-z]+"));
        java.io.File directory = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(), "ui-v3/" + run);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        assertTrue(device.takeScreenshot(new java.io.File(directory, "permission-" + key + "-observed.png")));
        assertNotNull("The permission action must open Android Settings: " + key, settings);
        assertFalse(ControllerPinManager.isDomModeActive());
        device.pressBack();
        assertNotNull("Back must return to SubHub without a role change",
                device.wait(Until.findObject(By.pkg("com.subhub.app")), 8000));
        awaitFocusedFrame(scenario);
        assertFalse(ControllerPinManager.isDomModeActive());
    }

    @androidx.annotation.RequiresApi(29)
    private static void awaitFocusedFrame(ActivityScenario<GlobalSettingsActivity> scenario) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 8000;
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        while (!focused.get() && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> focused.set(a.hasWindowFocus()));
            if (!focused.get()) android.os.SystemClock.sleep(25);
        }
        assertTrue("Returned Settings host must own the input window", focused.get());
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        scenario.onActivity(a -> {
            View root = a.getWindow().getDecorView();
            assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        assertTrue("Returned Settings host must compose a new frame", frame.await(5, java.util.concurrent.TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void collect(View v, List<String> keys) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("settings:"))
            keys.add((String) v.getTag());
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                collect(((ViewGroup) v).getChildAt(i), keys); }
    }
