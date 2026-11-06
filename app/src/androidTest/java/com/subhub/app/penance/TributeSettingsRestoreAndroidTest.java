package com.subhub.app.penance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.NativeUiActions;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.People;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Runs only on a reserved, idle UI fixture; never resets protection, jobs or payment state. */
public final class TributeSettingsRestoreAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation =
            new com.subhub.app.UiAutomationStateRule();
    private static final String ENABLED = "rule_extra_time_request_enabled";
    private static final String CENTS = "rule_extra_time_request_cents";
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private SharedPreferences settings, authentication;
    private Map<String, ?> originalSettings, originalAuthentication;
    private boolean originalDom;

    @Before public void snapshot() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalDom = ControllerPinManager.isDomModeActive();
        preferences = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        original = new LinkedHashMap<>(preferences.getAll());
        settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        originalSettings = new LinkedHashMap<>(settings.getAll());
        authentication = context.getSharedPreferences("subhub_controller_auth", 0);
        originalAuthentication = new LinkedHashMap<>(authentication.getAll());
        assertEquals("Owned fresh UI fixture only", "true", InstrumentationRegistry.getArguments()
                .getString("extra_time_ui_fixture"));
        assertTrue("Owned emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
        assertFalse("Protection must remain idle", new com.subhub.app.appmode.AppModeManager(context).isArmed());
        assertFalse("Observe recovery off the main thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
        RemoteFeature feature = RemoteFeature.get(context);
        long recoveryDeadline = android.os.SystemClock.uptimeMillis() + 10_000L;
        while (!feature.recoveryReady() && android.os.SystemClock.uptimeMillis() < recoveryDeadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (!feature.recoveryReady()) android.os.SystemClock.sleep(20L);
        }
        assertTrue("Do not override unavailable authority", feature.recoveryReady());
        assertEquals("Do not override a remote keyholder", KeyholderProvider.State.NONE, People.keyholder().state());
        assertFalse("Do not replace paired credentials", new ControllerAuthenticator(context).isPaired());
        // Prepare the owned local credential instead of assuming runner setup.
        if (!ControllerPinManager.isConfigured(context))
            assertTrue("Owned local PIN setup must be acknowledged", ControllerPinManager.setPin(context, "2468"));
        assertTrue("Do not overwrite an unknown configured PIN", ControllerPinManager.verify(context, "2468"));
        assertTrue("Use the declared controller-fixture runner", ControllerPinManager.hasCredentials(context));
        ControllerPinManager.enterDomMode();
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @After public void restore() {
        try {
            if (original != null) SharedPreferenceTestRestore.restore(preferences, original);
        } finally {
            try {
                if (originalAuthentication != null)
                    SharedPreferenceTestRestore.restore(authentication, originalAuthentication);
            } finally {
                try {
                    if (originalSettings != null) SharedPreferenceTestRestore.restore(settings, originalSettings);
                } finally {
                    if (originalDom) ControllerPinManager.enterDomMode();
                    else ControllerPinManager.enterSubMode();
                }
            }
        }
    }

    @Test public void staleEnabledWidgetCannotReenableASavedOffRule() {
        assertSavedChoiceSurvivesHierarchyRestore(true, false);
    }

    @Test public void staleDisabledWidgetCannotDisableASavedOnRule() {
        assertSavedChoiceSurvivesHierarchyRestore(false, true);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void rulesAndSpendingLimitsRemainDistinctAfterNativeNavigationAndRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent target = new Intent(context, PenanceActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(target)) {
            page.onActivity(activity -> assertTrue(FeatureEditorSession.canEdit(activity, "wallet")));
            onView(withTagValue(is((Object) "wallet:rules")))
                    .perform(NativeUiActions.revealAboveNavigation(), click());
            page.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.rule_config_card).isShown());
                assertFalse(activity.findViewById(R.id.safety_config_card).isShown());
                assertTrue(activity.findViewById(R.id.tribute_group_censoring).isShown());
                assertTrue(activity.findViewById(R.id.tribute_group_apps).isShown());
                assertTrue(activity.findViewById(R.id.tribute_group_tasks).isShown());
                assertTrue(activity.findViewById(R.id.tribute_group_protection).isShown());
            });
            captureGroup(page, R.id.rule_config_card, R.id.tribute_group_apps, "tribute-rules-grouping");
            page.recreate();
            page.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.rule_config_card).isShown());
                assertFalse(activity.findViewById(R.id.safety_config_card).isShown());
            });
            onView(withId(R.id.button_back)).perform(NativeUiActions.revealAboveNavigation(), click());
            onView(withTagValue(is((Object) "wallet:limits")))
                    .perform(NativeUiActions.revealAboveNavigation(), click());
            page.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.safety_config_card).isShown());
                assertFalse(activity.findViewById(R.id.rule_config_card).isShown());
                assertTrue(activity.findViewById(R.id.daily_cap).isShown());
                assertTrue(activity.findViewById(R.id.weekly_cap).isShown());
                assertTrue(activity.findViewById(R.id.mercy_minutes).isShown());
            });
            captureGroup(page, R.id.safety_config_card, 0, "tribute-spending-limits");
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    private void captureGroup(ActivityScenario<PenanceActivity> page, int editorId,
            int anchorId, String name) throws Exception {
        AtomicReference<PenanceActivity> host = new AtomicReference<>();
        AtomicReference<TextView> anchor = new AtomicReference<>();
        AtomicReference<View> scroll = new AtomicReference<>();
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "tribute-grouping");
        assertTrue("Safe evidence run", run.matches("[A-Za-z0-9_-]+"));
        page.onActivity(activity -> {
            host.set(activity);
            ViewGroup editor = activity.findViewById(editorId);
            anchor.set(anchorId == 0 ? (TextView) editor.getChildAt(0) : activity.findViewById(anchorId));
            scroll.set(activity.findViewById(R.id.wallet_scroll));
            java.io.File folder = new java.io.File(activity.getFilesDir(), "ui-v3/" + run);
            for (String suffix : new String[] {".png", ".composition.json", ".preflight.json",
                    "-preflight-failure.png", "-first-uncomposed.png", "-geometry-failure.png"})
                assertFalse("Preserve existing visual evidence", new java.io.File(folder, name + suffix).exists());
        });
        onView(is((View) anchor.get())).perform(NativeUiActions.revealAboveNavigation());
        try {
            page.onActivity(activity -> {
                TextView text = anchor.get();
                int[] origin = new int[2]; text.getLocationOnScreen(origin);
                Rect complete = new Rect(origin[0], origin[1], origin[0] + text.getWidth(), origin[1] + text.getHeight());
                assertTrue("Complete heading must be visible", com.subhub.app.TextFrameWitness.screenClip(text).contains(complete));
                assertTrue("Complete heading must clear its scroll viewport", com.subhub.app.TextFrameWitness.screenClip(scroll.get()).contains(complete));
                Rect window = new Rect(); text.getWindowVisibleDisplayFrame(window);
                assertTrue("Complete heading must clear system insets", window.contains(complete));
                View navigation = activity.findViewById(R.id.bottom_navigation);
                if (navigation != null && navigation.isShown())
                    assertTrue("Heading must clear floating navigation", complete.bottom <= com.subhub.app.TextFrameWitness.screenClip(navigation).top);
            });
        } catch (AssertionError failure) {
            com.subhub.app.UiRefreshWitness.capture(host.get(), name + "-geometry-failure");
            throw failure;
        }
        com.subhub.app.TextFrameWitness.capture(anchor.get(), scroll.get(), run, name);
        // Retain composed pixels before an overflow assertion can fail.
        page.onActivity(activity -> assertTextFits(activity.findViewById(editorId)));
    }

    private static void assertTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (layout != null) for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("Tribute text must not be ellipsized: " + text.getText(), 0, layout.getEllipsisCount(line));
                int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                assertTrue("Tribute text must fit: " + text.getText(), layout.getLineMax(line) <= available + 1);
            }
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertTextFits(((ViewGroup) view).getChildAt(index));
    }

    private void assertSavedChoiceSurvivesHierarchyRestore(boolean oldChoice, boolean savedChoice) {
        setStored(oldChoice, 125);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent target = new Intent(context, PenanceActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(target)) {
            page.onActivity(activity -> {
                assertTrue("Fixture must retain wallet edit authority",
                        FeatureEditorSession.canEdit(activity, "wallet"));
                assertEquals(oldChoice, toggle(activity).isChecked());
                Bundle staleHierarchy = new Bundle();
                activity.onSaveInstanceState(staleHierarchy);
                setStored(savedChoice, 225);
                Map<String, ?> authoritative = new LinkedHashMap<>(preferences.getAll());
                activity.onRestoreInstanceState(staleHierarchy);
                assertEquals(savedChoice, toggle(activity).isChecked());
                assertEquals("2.25", amount(activity).getText().toString());
                assertEquals("Restoration must not write any financial setting",
                        authoritative, preferences.getAll());
            });
            page.recreate();
            page.onActivity(activity -> {
                assertEquals(savedChoice, toggle(activity).isChecked());
                assertEquals("2.25", amount(activity).getText().toString());
                assertEquals(savedChoice, preferences.getBoolean(ENABLED, !savedChoice));
                assertEquals(225, preferences.getInt(CENTS, -1));
            });
        }
    }

    private void setStored(boolean enabled, int cents) {
        assertTrue(preferences.edit()
                .putBoolean(PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED, true)
                .putBoolean(ENABLED, enabled).putInt(CENTS, cents).commit());
    }

    private static CompoundButton toggle(PenanceActivity activity) {
        return activity.findViewById(R.id.rule_extra_time_request_enabled);
    }

    private static EditText amount(PenanceActivity activity) {
        return activity.findViewById(R.id.rule_extra_time_request_amount);
    }
}
