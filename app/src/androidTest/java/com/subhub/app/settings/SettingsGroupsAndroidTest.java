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
    @org.junit.Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
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
        SharedPreferenceTestRestore.restore(context.getSharedPreferences("subhub_profile_names",0), namesBefore);
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0), consentBefore);
        ControllerPinManager.enterSubMode();
    }

    @Test public void inlineGroupsKeepPrivacyPermissionsAndHelpWithoutHardcore() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a -> {
                List<String> keys = new ArrayList<>();
                collect(a.findViewById(android.R.id.content), keys);
                assertEquals(Arrays.asList("settings:privacy", "settings:notifications", "settings:permissions", "settings:help"), keys);
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

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void subSettingsLanguageSetupAndUpdatesKeepExistingGuardsAndBackRoutes() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ControllerPinManager.enterSubMode();
        String language=com.subhub.app.util.LocaleHelper.getLanguage(context);
        Map<String,?> choices=new HashMap<>(context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
        com.subhub.app.security.HardcoreModeManager hardcore=new com.subhub.app.security.HardcoreModeManager(context);
        boolean enabled=hardcore.isEnabled(),requested=hardcore.isRequested();
        try(ActivityScenario<GlobalSettingsActivity> page=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            page.onActivity(a->{
                assertTrue(a.findViewById(R.id.button_accessibility_settings).isEnabled());
                assertNotNull(a.findViewById(R.id.switch_hardcore_mode));
                assertTrue(a.findViewById(R.id.switch_hardcore_mode).isEnabled());
            });
            onView(withTagValue(org.hamcrest.Matchers.is((Object)"settings_language"))).perform(revealAboveNavigation());
            com.subhub.app.UiRefreshWitness.capture(resumed(GlobalSettingsActivity.class), "sub-settings-language");
            onView(withTagValue(org.hamcrest.Matchers.is((Object)"settings_language"))).perform(click());
            com.subhub.app.help.HelpActivity help=resumed(com.subhub.app.help.HelpActivity.class);
            com.subhub.app.UiRefreshWitness.capture(help, "sub-language-maintenance");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_language)).perform(click());
            assertEquals(language,com.subhub.app.util.LocaleHelper.getLanguage(context));
            assertFalse(ControllerPinManager.isDomModeActive());
            androidx.test.espresso.Espresso.pressBack();
            androidx.test.espresso.Espresso.pressBack();resumed(GlobalSettingsActivity.class);awaitFocusedFrame(page);
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.tour_replay)).perform(revealAboveNavigation(),click());
            com.subhub.app.onboarding.OnboardingActivity setup=resumed(com.subhub.app.onboarding.OnboardingActivity.class);
            assertTrue(setup.getIntent().getBooleanExtra(com.subhub.app.onboarding.OnboardingActivity.REPLAY,false));
            com.subhub.app.UiRefreshWitness.capture(setup, "sub-setup-replay");
            androidx.test.espresso.Espresso.pressBack();resumed(GlobalSettingsActivity.class);awaitFocusedFrame(page);
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.settings_updates)).perform(revealAboveNavigation(),click());
            com.subhub.app.UiRefreshWitness.capture(resumed(com.subhub.app.update.UpdatesActivity.class), "sub-updates");
            assertFalse(ControllerPinManager.isDomModeActive());
            androidx.test.espresso.Espresso.pressBack();resumed(GlobalSettingsActivity.class);awaitFocusedFrame(page);
            assertEquals(choices,context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).getAll());
            assertEquals(enabled,hardcore.isEnabled());assertEquals(requested,hardcore.isRequested());
            assertEquals(language,com.subhub.app.util.LocaleHelper.getLanguage(context));
            page.recreate();page.onActivity(a->assertNotNull(a.findViewById(android.R.id.content).findViewWithTag("settings_language")));
        }
    }

    private static <T extends android.app.Activity> T resumed(Class<T> type) {
        long end=android.os.SystemClock.uptimeMillis()+10000;
        java.util.concurrent.atomic.AtomicReference<T> found=new java.util.concurrent.atomic.AtomicReference<>();
        while(android.os.SystemClock.uptimeMillis()<end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()-> {
                for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) if(type.isInstance(a)) found.set(type.cast(a));
            });
            if(found.get()!=null)return found.get();android.os.SystemClock.sleep(20);
        }
        throw new AssertionError("Expected resumed "+type.getSimpleName());
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
        android.content.Context fixtureContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(com.subhub.app.remote.RemoteUnpairedUiFixture endpoint = new com.subhub.app.remote.RemoteUnpairedUiFixture(fixtureContext);
                ActivityScenario<GlobalSettingsActivity> page=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.switch_hardcore_mode)).perform(revealAboveNavigation(),click());
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(
                    Until.hasObject(By.text(InstrumentationRegistry.getInstrumentation().getTargetContext()
                            .getString(R.string.hardcore_consent_title))), 15_000));
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.hardcore_consent_title))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel)).perform(click());
            assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(InstrumentationRegistry.getInstrumentation().getTargetContext()));
        }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void hardcoreConsentRecreationAndCancelPreserveTheLocalChoice() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences consent = context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE, 0);
        android.content.SharedPreferences settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        Map<String, ?> before = new HashMap<>(consent.getAll());
        long revision = settings.getLong("sub_feature_policy_revision", 0);
        com.subhub.app.security.HardcoreModeManager hardcore = new com.subhub.app.security.HardcoreModeManager(context);
        boolean requested = hardcore.isRequested(), enabled = hardcore.isEnabled();
        assertFalse(requested); assertFalse(enabled); ControllerPinManager.enterSubMode();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (com.subhub.app.remote.RemoteUnpairedUiFixture endpoint = new com.subhub.app.remote.RemoteUnpairedUiFixture(context);
                ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.switch_hardcore_mode))
                    .perform(revealAboveNavigation(), click());
            boolean shown = device.wait(Until.hasObject(By.text(context.getString(R.string.hardcore_consent_title))), 15_000);
            if (!shown) {
                com.subhub.app.UiRefreshWitness.capture(resumed(GlobalSettingsActivity.class), "hardcore-review-failure");
                com.subhub.app.remote.RemoteFeature.get(context).reviewHardcoreStart().get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertTrue("Local Hardcore consent dialog must open", shown);
            assertTrue(device.hasObject(By.textContains(context.getString(R.string.hardcore_unpaired_start_consent))));
            page.recreate();
            assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.hardcore_consent_title))), 15_000));
            captureHardcoreConsent(page, device);
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel)).perform(click());
            assertEquals(before, consent.getAll()); assertEquals(revision, settings.getLong("sub_feature_policy_revision", 0));
            assertEquals(requested, hardcore.isRequested()); assertEquals(enabled, hardcore.isEnabled());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void pendingHardcoreAcceptanceSurvivesTheWaitBudgetAndRecreation() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        ControllerPinManager.enterSubMode(); long revision = settings.getLong("sub_feature_policy_revision", 0);
        java.util.concurrent.CompletableFuture<Boolean> pending = new java.util.concurrent.CompletableFuture<>();
        try (com.subhub.app.remote.RemoteUnpairedUiFixture endpoint = new com.subhub.app.remote.RemoteUnpairedUiFixture(context);
                ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            page.onActivity(a -> {
                GlobalSettingsActivity.HardcoreReviewState state = new androidx.lifecycle.ViewModelProvider(a)
                        .get(GlobalSettingsActivity.HardcoreReviewState.class);
                state.accepting = pending;
                try {
                    java.lang.reflect.Method observe = GlobalSettingsActivity.class.getDeclaredMethod("observeHardcoreAcceptance");
                    observe.setAccessible(true); observe.invoke(a);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            long until = android.os.SystemClock.elapsedRealtime() + 16_000;
            while (android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100);
            assertFalse(pending.isDone()); assertEquals(revision, settings.getLong("sub_feature_policy_revision", 0));
            page.recreate(); page.onActivity(a -> {
                GlobalSettingsActivity.HardcoreReviewState state = new androidx.lifecycle.ViewModelProvider(a)
                        .get(GlobalSettingsActivity.HardcoreReviewState.class);
                assertSame(pending, state.accepting); assertFalse(a.findViewById(R.id.switch_hardcore_mode).isEnabled());
                assertEquals(context.getString(R.string.hardcore_review_saving),
                        ((android.widget.TextView) a.findViewById(R.id.hardcore_status)).getText().toString());
            });
            page.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED);
            pending.complete(false); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("A detached observer must not finish the synthetic flow", revision,
                    settings.getLong("sub_feature_policy_revision", 0));
            assertFalse(new com.subhub.app.security.HardcoreModeManager(context).isRequested());
            assertFalse(com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(context));
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    private void captureHardcoreConsent(ActivityScenario<GlobalSettingsActivity> page, UiDevice device) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "refresh");
        java.util.concurrent.atomic.AtomicReference<android.widget.TextView> message = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<android.view.View> scrolling = new java.util.concurrent.atomic.AtomicReference<>();
        android.graphics.Rect viewport = new android.graphics.Rect();
        onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.message)).check((view, error) -> {
            if (error != null) throw error;
            message.set((android.widget.TextView) view);
            for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof android.widget.ScrollView || parent instanceof androidx.core.widget.NestedScrollView) {
                    android.view.View scroll = (android.view.View) parent;
                    viewport.set(com.subhub.app.TextFrameWitness.screenClip(scroll));
                    scrolling.set(scroll); return;
                }
            }
            fail("Consent message requires its native scroll container");
        });
        com.subhub.app.TextFrameWitness.capture(message.get(), scrolling.get(), run, "hardcore-consent-review");
        onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.message)).check((view, error) -> {
            if (error != null) throw error; assertDialogTextFits(view.getRootView());
        });
        java.util.concurrent.atomic.AtomicBoolean more = new java.util.concurrent.atomic.AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> more.set(scrolling.get().canScrollVertically(1)));
        boolean needed = more.get();
        for (int attempt = 0; more.get() && attempt < 4; attempt++) {
            int inset = Math.min(12, viewport.height() / 4);
            assertTrue(device.swipe(viewport.centerX(), viewport.bottom - inset,
                    viewport.centerX(), viewport.top + inset, 20));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> more.set(scrolling.get().canScrollVertically(1)));
        }
        if (more.get()) com.subhub.app.UiRefreshWitness.capture(resumed(GlobalSettingsActivity.class), "hardcore-consent-scope-incomplete");
        assertFalse("The complete consent scope must be reachable", more.get());
        if (needed) com.subhub.app.TextFrameWitness.capture(message.get(), scrolling.get(), run, "hardcore-consent-scope");
    }

    private static void assertDialogTextFits(android.view.View view) {
        if (view.getVisibility() != android.view.View.VISIBLE) return;
        if (view instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) view; android.text.Layout layout = text.getLayout();
            if (layout != null) for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("Dialog text must not be ellipsized: " + text.getText(), 0, layout.getEllipsisCount(line));
                int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                if (layout.getLineWidth(line) > available + 1) android.util.Log.i("DialogTextFit",
                        InstrumentationRegistry.getArguments().getString("evidence_run", "refresh") + " line=" + line
                        + " withWhitespace=" + layout.getLineWidth(line) + " visible=" + layout.getLineMax(line) + " available=" + available);
                assertTrue("Dialog text must fit its width: " + text.getText(), layout.getLineMax(line) <= available + 1);
            }
        }
        if (view instanceof android.view.ViewGroup) for (int index = 0; index < ((android.view.ViewGroup) view).getChildCount(); index++)
            assertDialogTextFits(((android.view.ViewGroup) view).getChildAt(index));
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
