package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.UiRefreshWitness;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.security.DeviceRolePreferences.Mode;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;

public final class RoleOnboardingAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private Map<String, ?> penance;

    @Before public void prepare() {
        assertFalse(LocalSessionControls.isActive(context));
        assertFalse(new AppModeManager(context).isArmed());
        assertFalse(new HardcoreModeManager(context).isRequested());
        assertFalse(new HardcoreModeManager(context).isAdminActive());
        penance = new LinkedHashMap<>(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0).getAll());
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .remove("controller_pin_salt").remove("controller_pin_hash").remove("controller_keyholder_optional")
                .remove("has_seen_onboarding").putString(DeviceRolePreferences.KEY_MODE, Mode.BOTH.name()).commit();
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0), penance);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void firstPageContainsOnlyRoleChoiceAndNecessaryNavigation() throws Exception {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            assertEquals(OnboardingFlow.ROLE, OnboardingTestSupport.pageId(page));
            page.onActivity(activity -> {
                assertEquals(2, ((ViewGroup) activity.findViewById(R.id.tour_content)).getChildCount());
                assertEquals(3, ((ViewGroup) activity.findViewById(R.id.onboarding_role_choice)).getChildCount());
                assertNull(activity.findViewById(R.id.onboarding_graphic));
                assertFalse(activity.findViewById(R.id.tour_next).isEnabled());
                assertEquals(View.GONE, activity.findViewById(R.id.tour_skip).getVisibility());
                assertFalse(activity.findViewById(R.id.tour_progress).isShown());
            });
            capture(page, "role-choice"); assertTextAndFooter(page);
        }
        assertFalse(OnboardingState.completed(context));
    }

    @Test public void repeatedRoleChoiceAndRecreationKeepTheSelectedJourneyWithoutUnlockingDom() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(page, Mode.SUB_ONLY);
            assertEquals(Mode.SUB_ONLY, DeviceRolePreferences.get(context)); page.recreate();
            assertEquals("sub_connect_dom", OnboardingTestSupport.pageId(page));
            pressBack(); OnboardingTestSupport.choose(page, Mode.DOM_ONLY);
            assertEquals("dom_security", OnboardingTestSupport.pageId(page));
            assertEquals(Mode.DOM_ONLY, DeviceRolePreferences.get(context));
            pressBack(); OnboardingTestSupport.choose(page, Mode.BOTH);
            OnboardingTestSupport.goTo(page, "both_dom_handoff", Mode.BOTH); page.recreate();
            assertEquals("both_dom_handoff", OnboardingTestSupport.pageId(page));
            assertEquals(Mode.BOTH, DeviceRolePreferences.get(context));
            assertFalse(ControllerPinManager.isDomModeActive()); assertFalse(ControllerPinManager.hasCredentials(context));
        }
    }

    @Test public void backAtRoleChoiceLeavesAResumableIncompleteSetup() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            UiDevice.getInstance(instrumentation).pressBack(); instrumentation.waitForIdleSync();
        }
        assertFalse(OnboardingState.completed(context)); assertTrue(OnboardingState.inProgress(context));
        assertTrue(OnboardingState.shouldStart(context));
        try (ActivityScenario<OnboardingActivity> resumed = ActivityScenario.launch(OnboardingActivity.class)) {
            assertEquals(OnboardingFlow.ROLE, OnboardingTestSupport.pageId(resumed));
            onView(withId(R.id.tour_next)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        }
    }

    @Test public void existingCompletedInstallDoesNotRestartSetupOrResetSettings() {
        assertTrue(ControllerPinManager.setPin(context, "2468")); OnboardingState.complete(context);
        new SettingsRepository(context).saveAppearance(com.subhub.app.settings.CensorAppearance.Type.GLITCH, 71, false, false);
        Map<String, ?> settings = new LinkedHashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll());
        assertFalse(OnboardingState.shouldStart(context)); assertFalse(OnboardingState.inProgress(context));
        assertEquals(settings, context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll());
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertEquals(com.subhub.app.settings.CensorAppearance.Type.GLITCH, new SettingsRepository(context).loadAppearance().getType());
    }

    @Test public void changingEstablishedRoleRequiresAuthenticationAndCancelPreservesIt() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(page, Mode.SUB_ONLY); assertTrue(ControllerPinManager.setPin(context, "2468"));
            pressBack();
            onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());
            awaitPickerWindow(page);
            assertEquals(Mode.SUB_ONLY, DeviceRolePreferences.get(context));
            onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertEquals(Mode.DOM_ONLY, DeviceRolePreferences.get(context));
            onView(withId(R.id.tour_next)).perform(click()); assertEquals("dom_security", OnboardingTestSupport.pageId(page));
        }
    }

    private void awaitPickerWindow(ActivityScenario<OnboardingActivity> page) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            page.onActivity(activity -> focused.set(activity.getWindow().getDecorView().hasWindowFocus()
                    && activity.findViewById(R.id.onboarding_role_choice) != null));
            if (focused.get()) return;
            SystemClock.sleep(25);
        }
        fail("Role picker window did not regain focus after authentication cancellation");
    }

    @Test public void pairingActionsUseTheCorrectDirectionAndDomAuthentication() {
        final List<Boolean> opened = new ArrayList<>();
        People.installConnectionUi(new ConnectionUi() {
            @Override public void openPairing(android.app.Activity activity) { fail("Pairing direction is required"); }
            @Override public void openPairing(android.app.Activity activity, boolean dom) { opened.add(dom); }
            @Override public void openConnection(android.app.Activity activity, PersonId person) { fail("No existing connection selected"); }
        });
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(page, Mode.SUB_ONLY);
            onView(withTagValue(is("onboarding_action:sub_connect_dom"))).perform(scrollTo(), click());
            assertEquals(Collections.singletonList(false), opened); assertFalse(ControllerPinManager.isDomModeActive());
            pressBack(); OnboardingTestSupport.choose(page, Mode.DOM_ONLY);
            OnboardingTestSupport.goTo(page, "dom_add_sub", Mode.DOM_ONLY);
            onView(withTagValue(is("onboarding_action:dom_add_sub"))).perform(scrollTo(), click());
            assertEquals("dom_security", OnboardingTestSupport.pageId(page)); assertEquals(1, opened.size());
            assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterSubMode();
            OnboardingTestSupport.goTo(page, "dom_add_sub", Mode.DOM_ONLY);
            onView(withTagValue(is("onboarding_action:dom_add_sub"))).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click()); assertEquals(1, opened.size());
            onView(withTagValue(is("onboarding_action:dom_add_sub"))).perform(scrollTo(), click());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            assertEquals(Arrays.asList(false, true), opened);
        }
    }

    @Test public void hardcoreActionOpensSettingsWithoutOptingInAndReturnsToTheSamePage() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.goTo(page, "sub_hardcore", Mode.SUB_ONLY);
            boolean consent = context.getSharedPreferences(SubFeaturePolicy.STORE, 0)
                    .getBoolean("hardcore_locally_consented", false);
            onView(withTagValue(is("onboarding_action:sub_hardcore"))).perform(scrollTo(), click());
            UiDevice device = UiDevice.getInstance(instrumentation);
            assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app", "settings_sections")), 8000));
            assertFalse(new HardcoreModeManager(context).isRequested()); assertFalse(new HardcoreModeManager(context).isAdminActive());
            assertEquals(consent, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getBoolean("hardcore_locally_consented", false));
            device.pressBack(); instrumentation.waitForIdleSync();
            assertEquals("sub_hardcore", OnboardingTestSupport.pageId(page));
            assertFalse(new AppModeManager(context).isArmed());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void notificationDenialKeepsSetupUsableAndDoesNotEnableOtherPermissions() {
        assertNotEquals("Controlled permission-denial guest must start without notifications", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        boolean overlay = android.provider.Settings.canDrawOverlays(context);
        boolean accessibility = new AppModeManager(context).isAccessibilityEnabled();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.goTo(page, "device_permissions", Mode.SUB_ONLY);
            onView(withTagValue(is("onboarding_permission:tour_notifications_title"))).perform(scrollTo(), click());
            UiDevice device = UiDevice.getInstance(instrumentation);
            UiObject2 deny = device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/permission_deny_button"))), 8000);
            assertNotNull("Real Android notification prompt", deny); deny.click();
            assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app", "tour_next")), 8000));
            assertEquals("device_permissions", OnboardingTestSupport.pageId(page));
            assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
            assertEquals(overlay, android.provider.Settings.canDrawOverlays(context));
            assertEquals(accessibility, new AppModeManager(context).isAccessibilityEnabled());
            onView(withId(R.id.tour_skip)).perform(click()); assertTrue(OnboardingState.completed(context));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subJourneyFitsSmallScreensAndLargeText() throws Exception { inspectJourney(Mode.SUB_ONLY); }
    @Test @SdkSuppress(minSdkVersion = 29)
    public void domJourneyFitsSmallScreensAndLargeText() throws Exception { inspectJourney(Mode.DOM_ONLY); }
    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothJourneyFitsSmallScreensAndLargeText() throws Exception { inspectJourney(Mode.BOTH); }

    private void inspectJourney(Mode role) throws Exception {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            capture(page, role.name().toLowerCase(Locale.ROOT) + "-role"); assertTextAndFooter(page);
            OnboardingTestSupport.choose(page, role);
            List<OnboardingPage> flow = OnboardingFlow.pages(role);
            for (int i = 0; i < flow.size(); i++) {
                OnboardingPage spec = flow.get(i); assertEquals(spec.id, OnboardingTestSupport.pageId(page));
                capture(page, role.name().toLowerCase(Locale.ROOT) + "-" + spec.id + "-top"); assertTextAndFooter(page);
                page.onActivity(activity -> {
                    ScrollView scroll = activity.findViewById(R.id.tour_scroll);
                    int bottom = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()
                            + scroll.getPaddingTop() + scroll.getPaddingBottom());
                    scroll.scrollTo(0, bottom);
                });
                capture(page, role.name().toLowerCase(Locale.ROOT) + "-" + spec.id + "-bottom"); assertTextAndFooter(page);
                page.onActivity(activity -> assertFalse("The final instructions can be reached",
                        activity.findViewById(R.id.tour_scroll).canScrollVertically(1)));
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(new HardcoreModeManager(context).isRequested());
                assertFalse(new AppModeManager(context).isArmed());
                if (i + 1 < flow.size()) onView(withId(R.id.tour_next)).perform(click());
            }
        }
    }

    private void assertTextAndFooter(ActivityScenario<OnboardingActivity> page) {
        page.onActivity(activity -> {
            Rect footer = new Rect(), scroll = new Rect();
            View button = activity.findViewById(R.id.tour_next);
            assertTrue(button.getGlobalVisibleRect(footer));
            assertTrue(activity.findViewById(R.id.tour_scroll).getGlobalVisibleRect(scroll));
            assertTrue("Footer does not cover the page", footer.top >= scroll.bottom);
            assertEquals("Primary navigation stays fully visible", button.getHeight(), footer.height());
            inspectCopy(activity.findViewById(android.R.id.content));
        });
    }
    private void inspectCopy(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && ("onboarding_copy".equals(view.getTag())
                || view.getId() == R.id.tour_step_title || view.getId() == R.id.tour_next
                || view.getId() == R.id.tour_skip || view instanceof RadioButton)) {
            TextView text = (TextView) view;
            assertNotNull("Native text is laid out", text.getLayout());
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                assertEquals("New setup copy must not be ellipsized", 0, text.getLayout().getEllipsisCount(line));
                assertTrue("New setup ink fits its allotted width: max=" + text.getLayout().getLineMax(line)
                        + "; width=" + width + "; text=" + text.getText(), text.getLayout().getLineMax(line) <= width + 2);
            }
            assertTrue("New setup copy fits its allotted height: " + text.getText(),
                    text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) inspectCopy(((ViewGroup) view).getChildAt(i));
    }
    private void capture(ActivityScenario<OnboardingActivity> page, String name) throws Exception {
        AtomicReference<OnboardingActivity> current = new AtomicReference<>(); page.onActivity(current::set);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "refresh");
        org.json.JSONObject composition = OnboardingFrameWitness.capture(current.get(), run, name);
        org.json.JSONArray measured = new org.json.JSONArray();
        page.onActivity(activity -> appendTextWitness(activity.findViewById(android.R.id.content), measured));
        java.io.File file = new java.io.File(context.getFilesDir(), "ui-v3/" + run + "/" + name + ".layout.json");
        java.nio.file.Files.write(file.toPath(), measured.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<org.json.JSONObject> viewport = new AtomicReference<>();
        page.onActivity(activity -> {
            ScrollView scroll = activity.findViewById(R.id.tour_scroll);
            try {
                viewport.set(new org.json.JSONObject().put("scrollY", scroll.getScrollY())
                        .put("viewportHeight", scroll.getHeight()).put("contentHeight", scroll.getChildAt(0).getHeight())
                        .put("canScrollDown", scroll.canScrollVertically(1)).put("composition", composition));
            } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
        });
        java.io.File position = new java.io.File(file.getParentFile(), name + ".scroll.json");
        java.nio.file.Files.write(position.toPath(), viewport.get().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void managedSetupCanResumeItsExistingRoleWithoutGrantingDomInvitations() {
        final List<Boolean> opened = new ArrayList<>();
        People.installConnectionUi(new ConnectionUi() {
            @Override public void openPairing(android.app.Activity activity) { fail("Direction is required"); }
            @Override public void openPairing(android.app.Activity activity, boolean dom) { opened.add(dom); }
            @Override public void openConnection(android.app.Activity activity, PersonId person) { }
        });
        for (KeyholderProvider.State state : Arrays.asList(KeyholderProvider.State.READY, KeyholderProvider.State.UNAVAILABLE)) {
            context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
            People.installKeyholder(new KeyholderProvider() {
                @Override public State state() { return state; }
                @Override public String assignedDomName() { return "Synthetic Dom"; }
                @Override public ControllerAuthenticator.Result verifyCode(String code) { return ControllerAuthenticator.Result.UNAVAILABLE; }
            });
            People.installRemoteAuthority(() -> true);
            try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
                onView(withTagValue(is("device_role:DOM_ONLY"))).perform(scrollTo(), click());
                assertEquals(Mode.BOTH, DeviceRolePreferences.get(context));
                assertEquals(OnboardingFlow.ROLE, OnboardingTestSupport.pageId(page));
                OnboardingTestSupport.choose(page, Mode.BOTH);
                assertEquals("sub_connect_dom", OnboardingTestSupport.pageId(page));
                OnboardingTestSupport.goTo(page, "dom_add_sub", Mode.BOTH);
                onView(withTagValue(is("onboarding_action:dom_add_sub"))).perform(scrollTo(), click());
                assertTrue(opened.isEmpty()); assertFalse(ControllerPinManager.isDomModeActive());
                onView(withHint(R.string.controller_pin_label)).check(doesNotExist());
                onView(withHint(R.string.authenticator_code)).check(doesNotExist());
            }
        }
    }

    @Test public void replayingAnotherRoleKeepsDeviceCapabilitiesAndPermissionScope() {
        assertTrue(DeviceRolePreferences.set(context, Mode.DOM_ONLY));
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterSubMode();
        OnboardingState.complete(context);
        Map<String, ?> completed = new LinkedHashMap<>(context.getSharedPreferences("subhub_onboarding", 0).getAll());
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            OnboardingTestSupport.goTo(page, "device_permissions", Mode.SUB_ONLY);
            assertEquals(Mode.DOM_ONLY, DeviceRolePreferences.get(context));
            onView(withText(R.string.tour_accessibility_title)).check(doesNotExist());
            onView(withText(R.string.tour_overlay_title)).check(doesNotExist());
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withId(R.id.tour_skip)).perform(click());
        }
        assertEquals(completed, context.getSharedPreferences("subhub_onboarding", 0).getAll());
        assertEquals(Mode.DOM_ONLY, DeviceRolePreferences.get(context));
        assertTrue(ControllerPinManager.isConfigured(context));
    }

    private void appendTextWitness(View view, org.json.JSONArray measured) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && ("onboarding_copy".equals(view.getTag())
                || view.getId() == R.id.tour_step_title || view.getId() == R.id.tour_next
                || view.getId() == R.id.tour_skip || view instanceof RadioButton)) {
            TextView text = (TextView) view;
            try {
                org.json.JSONObject item = new org.json.JSONObject().put("text", text.getText().toString())
                        .put("width", text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight())
                        .put("height", text.getHeight()).put("fontScale", context.getResources().getConfiguration().fontScale);
                org.json.JSONArray lines = new org.json.JSONArray();
                if (text.getLayout() != null) for (int i = 0; i < text.getLayout().getLineCount(); i++)
                    lines.put(new org.json.JSONObject().put("fullWidth", text.getLayout().getLineWidth(i))
                            .put("inkWidth", text.getLayout().getLineMax(i)).put("visibleEnd", text.getLayout().getLineVisibleEnd(i)));
                measured.put(item.put("lines", lines));
            } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            appendTextWitness(((ViewGroup) view).getChildAt(i), measured);
    }
}
