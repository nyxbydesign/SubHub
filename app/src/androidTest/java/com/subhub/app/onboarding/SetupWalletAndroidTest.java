package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.penance.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.StateToggle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SetupWalletAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> original = new java.util.HashMap<>();
    private final String[] prefs = {"subhub_onboarding", SettingsRepository.PREFERENCES_NAME,
            PenanceManager.PREFS_NAME, "subhub_controller_auth"};

    @Before public void prepare() {
        for (String name : prefs) original.put(name, context.getSharedPreferences(name, 0).getAll());
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .remove("controller_pin_salt").remove("controller_pin_hash")
                .remove("controller_keyholder_optional").commit();
        ControllerPinManager.enterSubMode();
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.NEW_DETECTION, 100);
        new PenanceManager(context).configure(true, rules, 700, 2500, 17, 10, 1, 5);
    }

    @After public void restore() {
        for (String name : prefs) SharedPreferenceTestRestore.restore(
                context.getSharedPreferences(name, 0), original.get(name));
        ControllerPinManager.enterSubMode();
    }

    @Test public void compactRulesSurviveRecreationAndSaveWithoutChangingHiddenPolicy() {
        try (ActivityScenario<OnboardingActivity> tour = walletPage()) {
            tour.onActivity(a -> {
                SetupWalletRulesView panel = panel(a);
                assertNotNull(panel.findViewWithTag("setup-wallet-graphic"));
                assertNull(a.findViewById(R.id.daily_cap));
                assertNull(a.findViewById(R.id.weekly_cap));
                assertNull(a.findViewById(R.id.mercy_minutes));
                ScrollView scroll = a.findViewById(R.id.tour_scroll);
                assertTrue("Normal setup fits one page", scroll.getChildAt(0).getHeight() <= scroll.getHeight());
                toggle(panel, PenanceInfraction.CENSORED_TAP).setChecked(true);
                amount(panel, PenanceInfraction.CENSORED_TAP).setText("2.35");
                amount(panel, PenanceInfraction.NEW_DETECTION).setText("1.25");
                ((EditText) panel.findViewWithTag("setup-wallet-timing:new_detection")).setText("3");
                capture(a, "setup-wallet-native.png");
            });
            tour.recreate();
            tour.onActivity(a -> {
                assertTrue(toggle(panel(a), PenanceInfraction.CENSORED_TAP).isChecked());
                assertEquals("2.35", amount(panel(a), PenanceInfraction.CENSORED_TAP).getText().toString());
            });
            onView(withId(R.id.tour_next)).perform(click());
            PenanceManager saved = new PenanceManager(context);
            assertEquals(235, saved.getInfractionCents(PenanceInfraction.CENSORED_TAP));
            assertEquals(125, saved.getStrikeCents());
            assertEquals(3, saved.getDetectionBatch());
            assertEquals(700, saved.getDailyCapCents());
            assertEquals(2500, saved.getWeeklyCapCents());
            assertEquals(17, saved.getMercyMinutes());
        }
    }

    @Test public void incompleteRawDraftResumesAndInvalidAmountsCannotAdvance() {
        try (ActivityScenario<OnboardingActivity> tour = walletPage()) {
            tour.onActivity(a -> amount(panel(a), PenanceInfraction.NEW_DETECTION).setText(""));
        }
        try (ActivityScenario<OnboardingActivity> resumed = ActivityScenario.launch(OnboardingActivity.class)) {
            resumed.onActivity(a -> assertEquals("", amount(panel(a), PenanceInfraction.NEW_DETECTION).getText().toString()));
            onView(withId(R.id.tour_next)).perform(click());
            resumed.onActivity(a -> {
                assertNotNull(panel(a));
                assertNotNull(amount(panel(a), PenanceInfraction.NEW_DETECTION).getError());
                amount(panel(a), PenanceInfraction.NEW_DETECTION).setText("7.01");
            });
            onView(withId(R.id.tour_next)).perform(click());
            assertEquals(700, new PenanceManager(context).getDailyCapCents());
            resumed.onActivity(a -> {
                assertNotNull(panel(a));
                amount(panel(a), PenanceInfraction.NEW_DETECTION).setText("1.20");
            });
            onView(withId(R.id.tour_next)).perform(click());
            assertEquals(120, new PenanceManager(context).getStrikeCents());
        }
    }

    @Test public void replayInSubModeCannotChangeSavedRules() {
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_next)).perform(click());
            tour.onActivity(a -> {
                assertFalse(toggle(panel(a), PenanceInfraction.NEW_DETECTION).isEnabled());
                assertFalse(amount(panel(a), PenanceInfraction.NEW_DETECTION).isEnabled());
                amount(panel(a), PenanceInfraction.NEW_DETECTION).setText("5.00");
            });
            onView(withId(R.id.tour_next)).perform(click());
            assertEquals(100, new PenanceManager(context).getStrikeCents());
        }
    }

    @Test public void legacyAppDraftResumesOnAppsInTheReorderedFlow() {
        SharedPreferences draft = context.getSharedPreferences("subhub_onboarding", 0);
        draft.edit().putBoolean("in_progress", true).putInt("draft_step", 1).commit();
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class)) {
            tour.onActivity(a -> assertEquals(a.getString(R.string.tour_select_apps),
                    ((android.widget.TextView) a.findViewById(R.id.tour_step_title)).getText().toString()));
        }
    }

    private ActivityScenario<OnboardingActivity> walletPage() {
        ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class);
        onView(withId(R.id.tour_next)).perform(click());
        onView(withId(R.id.tour_next)).perform(click());
        return tour;
    }

    private static SetupWalletRulesView panel(OnboardingActivity activity) {
        return activity.findViewById(android.R.id.content).findViewWithTag("setup-wallet-rules");
    }
    private static StateToggle toggle(SetupWalletRulesView panel, PenanceInfraction rule) {
        return panel.findViewWithTag("setup-wallet-toggle:" + rule.preferenceKey());
    }
    private static EditText amount(SetupWalletRulesView panel, PenanceInfraction rule) {
        return panel.findViewWithTag("setup-wallet-amount:" + rule.preferenceKey());
    }
    private static void capture(OnboardingActivity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }
}
