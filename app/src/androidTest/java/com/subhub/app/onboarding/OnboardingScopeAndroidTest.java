package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.*;
import com.subhub.app.penance.*;
import com.subhub.app.security.*;
import com.subhub.app.security.DeviceRolePreferences.Mode;
import com.subhub.app.settings.*;
import java.util.*;
import org.junit.*;

public final class OnboardingScopeAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> penance;

    @Before public void prepare() {
        assertFalse(LocalSessionControls.isActive(context));
        assertFalse(new AppModeManager(context).isArmed());
        penance = new LinkedHashMap<>(context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).getAll());
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        settings().edit().remove("controller_pin_salt").remove("controller_pin_hash")
                .remove("controller_keyholder_optional").remove("has_seen_onboarding")
                .putString(DeviceRolePreferences.KEY_MODE, Mode.BOTH.name()).commit();
        new SettingsRepository(context).saveAppearance(CensorAppearance.Type.GLITCH, 71, false, false);
        new PenanceManager(context).configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125),
                700, 2500, 17, 10, 1, 31);
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(PenanceManager.PREFS_NAME, 0), penance);
    }
    private SharedPreferences settings() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private Map<String, ?> wallet() { return new LinkedHashMap<>(context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).getAll()); }

    @Test public void domSettingsLessonsAreScopedStoriesAndPreserveLocalSettingsOnBackAndRecreation() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(page, Mode.DOM_ONLY);
            Map<String, ?> settings = new LinkedHashMap<>(settings().getAll()), rules = wallet();
            OnboardingTestSupport.goTo(page, "dom_add_sub", Mode.DOM_ONLY);
            onView(withId(R.id.tour_next)).perform(click()); assertEquals("dom_wallet", OnboardingTestSupport.pageId(page));
            assertStory(page); page.recreate(); assertEquals("dom_wallet", OnboardingTestSupport.pageId(page));
            onView(withId(R.id.tour_next)).perform(click()); assertEquals("dom_censor", OnboardingTestSupport.pageId(page));
            assertStory(page); page.recreate(); assertEquals("dom_censor", OnboardingTestSupport.pageId(page));
            pressBack(); assertEquals("dom_wallet", OnboardingTestSupport.pageId(page));
            assertEquals(settings, settings().getAll()); assertEquals(rules, wallet());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void replayingMovedSettingsCannotChangeLocalRulesOrStyle() {
        OnboardingState.complete(context); assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterSubMode();
        Map<String, ?> settings = new LinkedHashMap<>(settings().getAll()), rules = wallet();
        Map<String, ?> draft = new LinkedHashMap<>(context.getSharedPreferences("subhub_onboarding", 0).getAll());
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            OnboardingTestSupport.goTo(page, "dom_wallet", Mode.DOM_ONLY); assertStory(page);
            OnboardingTestSupport.goTo(page, "dom_censor", Mode.DOM_ONLY); assertStory(page); page.recreate();
            onView(withId(R.id.tour_skip)).perform(click());
        }
        assertEquals(settings, settings().getAll()); assertEquals(rules, wallet());
        assertEquals(draft, context.getSharedPreferences("subhub_onboarding", 0).getAll());
        assertFalse(ControllerPinManager.isDomModeActive()); assertTrue(ControllerPinManager.verify(context, "2468"));
    }

    @Test public void dev205StablePageDraftsResumeWithoutUsingTheirOldNumericStep() {
        Object[][] cases = {{Mode.SUB_ONLY, "sub_appearance", "sub_apps"},
                {Mode.SUB_ONLY, "sub_wallet", "sub_apps"}, {Mode.BOTH, "sub_wallet", "sub_apps"},
                {Mode.DOM_ONLY, "dom_hardcore", "dom_create_task"},
                {Mode.BOTH, "dom_add_sub", "dom_wallet"}, {Mode.BOTH, "dom_contracts", "dom_contracts"}};
        for (Object[] item : cases) {
            Mode role = (Mode) item[0]; settings().edit().putString(DeviceRolePreferences.KEY_MODE, role.name()).commit();
            seed(role, (String) item[1], 3);
            try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
                assertEquals(item[2], OnboardingTestSupport.pageId(page)); page.recreate();
                assertEquals(item[2], OnboardingTestSupport.pageId(page)); assertEquals(role, DeviceRolePreferences.get(context));
            }
            try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
                assertEquals(item[2], OnboardingTestSupport.pageId(page));
            }
        }
    }

    @Test public void staleLocalStyleAndWalletDraftsCannotApplyWhenSetupFinishes() {
        settings().edit().putString(DeviceRolePreferences.KEY_MODE, Mode.SUB_ONLY.name()).commit();
        for (String old : new String[] {"sub_appearance", "sub_wallet"}) {
            seed(Mode.SUB_ONLY, old, 3);
            context.getSharedPreferences("subhub_onboarding", 0).edit().putString("draft_style", "blur")
                    .putBoolean("draft_appearance_changed", true).putBoolean("draft_appearance_authorized", true)
                    .putString("draft_wallet_rules", "{incomplete old editor draft").commit();
            Map<String, ?> rules = wallet();
            try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
                assertEquals("sub_apps", OnboardingTestSupport.pageId(page)); page.recreate();
                onView(withId(R.id.tour_skip)).perform(click());
            }
            assertEquals(CensorAppearance.Type.GLITCH, new SettingsRepository(context).loadAppearance().getType());
            assertEquals(rules, wallet()); assertTrue(OnboardingState.completed(context));
            for (String key : context.getSharedPreferences("subhub_onboarding", 0).getAll().keySet())
                assertFalse(key, key.startsWith("draft_"));
        }
    }

    @Test public void roleMismatchCannotResumeAnotherRolesDraft() {
        settings().edit().putString(DeviceRolePreferences.KEY_MODE, Mode.SUB_ONLY.name()).commit();
        seed(Mode.DOM_ONLY, "dom_wallet", 3);
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            assertEquals(OnboardingFlow.ROLE, OnboardingTestSupport.pageId(page));
            page.onActivity(a -> assertFalse(a.findViewById(R.id.tour_next).isEnabled()));
            assertEquals(Mode.SUB_ONLY, DeviceRolePreferences.get(context));
        }
    }

    @Test public void alreadyPairedBothJourneyKeepsTheConnectionAndDoesNotAskForAnInversePairing() {
        KeyholderProvider provider = new KeyholderProvider() {
            @Override public State state() { return State.READY; }
            @Override public String assignedDomName() { return "Synthetic Dom"; }
            @Override public ControllerAuthenticator.Result verifyCode(String code) { return ControllerAuthenticator.Result.UNAVAILABLE; }
        };
        People.installKeyholder(provider); People.installRemoteAuthority(() -> true);
        List<Boolean> opened = new ArrayList<>();
        People.installConnectionUi(new ConnectionUi() {
            @Override public void openPairing(android.app.Activity activity) { fail("Direction required"); }
            @Override public void openPairing(android.app.Activity activity, boolean dom) { opened.add(dom); }
            @Override public void openConnection(android.app.Activity activity, PersonId person) { fail("No target selected"); }
        });
        Map<String, ?> settings = new LinkedHashMap<>(settings().getAll()), rules = wallet();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            OnboardingTestSupport.choose(page, Mode.BOTH);
            OnboardingTestSupport.goTo(page, "both_dom_handoff", Mode.BOTH); page.recreate();
            onView(withId(R.id.tour_next)).perform(click()); assertEquals("dom_wallet", OnboardingTestSupport.pageId(page));
            pressBack(); assertEquals("both_dom_handoff", OnboardingTestSupport.pageId(page));
            OnboardingTestSupport.goTo(page, "device_permissions", Mode.BOTH);
            onView(withTagValue(is("onboarding_action:dom_add_sub"))).check(doesNotExist());
            assertSame(provider, People.keyholder()); assertTrue(opened.isEmpty());
            assertEquals(settings, settings().getAll()); assertEquals(rules, wallet());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void legacyAppDraftStillResumesOnApps() {
        context.getSharedPreferences("subhub_onboarding", 0).edit().putBoolean("in_progress", true)
                .putInt("draft_step", 1).commit();
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(OnboardingActivity.class)) {
            assertEquals("sub_apps", OnboardingTestSupport.pageId(page));
        }
    }
    private void seed(Mode role, String page, int version) {
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().putBoolean("in_progress", true)
                .putInt("draft_flow_version", version).putInt("draft_step", 88).putString("draft_page", page)
                .putString("draft_role", role.name()).putBoolean("draft_role_chosen", true).commit();
    }
    private void assertStory(ActivityScenario<OnboardingActivity> page) {
        page.onActivity(a -> {
            ViewGroup body = a.findViewById(R.id.tour_content);
            assertNotNull(a.findViewById(R.id.onboarding_graphic)); assertNoEditor(body);
            assertNull(body.findViewWithTag("setup-wallet-rules"));
            assertNull(body.findViewWithTag("setup-style:blur"));
        });
    }
    private void assertNoEditor(View view) {
        assertFalse("Dom lessons cannot edit the local device", view instanceof EditText);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNoEditor(((ViewGroup) view).getChildAt(i));
    }
}
