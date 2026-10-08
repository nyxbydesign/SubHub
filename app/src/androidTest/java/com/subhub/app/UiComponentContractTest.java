package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.security.ControllerPinManager;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UiComponentContractTest {
    @Test public void statsHasNoAchievementButtonAndHomePlacesAchievementsAfterLifetimeStats() {
        try (ActivityScenario<com.subhub.app.stats.StatsActivity> scenario =
                     ActivityScenario.launch(com.subhub.app.stats.StatsActivity.class)) {
            scenario.onActivity(activity -> {
                org.junit.Assert.assertNull(activity.findViewById(R.id.button_achievements));
                org.junit.Assert.assertNull("Achievement counts belong on Achievements, not Stats",
                        activity.findViewById(R.id.achievement_progress));
            });
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View lifetime = activity.findViewById(R.id.home_lifetime_stats);
                View achievements = activity.findViewById(R.id.achievements_home_card);
                assertEquals(lifetime.getParent(), achievements.getParent());
                ViewGroup parent = (ViewGroup) lifetime.getParent();
                assertTrue(parent.indexOfChild(lifetime) < parent.indexOfChild(achievements));
            });
        } finally { ControllerPinManager.enterDomMode(); }
    }
    @Test public void subliminalChoicesAndDialogCloseHaveFullTouchTargets() {
        android.content.Context context = new android.view.ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_SubHub);
        View page = LayoutInflater.from(context).inflate(
                R.layout.activity_subliminal_settings, null, false);
        int[] ids = {R.id.preset_slider, R.id.pack_obedience, R.id.pack_focus,
                R.id.pack_beta, R.id.pack_findom, R.id.pack_custom};
        int minimum = context.getResources().getDimensionPixelSize(R.dimen.control_min_height);
        for (int id : ids) {
            View choice = page.findViewById(id);
            choice.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(choice.getMeasuredHeight() >= minimum);
        }
        int[][] dialogs = {{R.layout.dialog_arrangement_details, R.id.arrangement_detail_close},
                {R.layout.dialog_achievement_details, R.id.achievement_detail_close}};
        for (int[] dialog : dialogs) {
            View close = LayoutInflater.from(context).inflate(dialog[0], null, false)
                    .findViewById(dialog[1]);
            close.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(close.getMeasuredHeight() >= minimum);
        }
    }

    @Test public void atmosphereActionsHaveReadableTextAndFullTouchTargets() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AtmosphereActivity> scenario =
                     ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                int[] ids = {R.id.button_whispers, R.id.button_popup_storm};
                for (int id : ids) {
                    TextView button = activity.findViewById(id);
                    assertEquals(Gravity.CENTER, button.getGravity());
                    assertTrue(button.isShown());
                    assertTrue(button.getHeight() >= activity.getResources()
                            .getDimensionPixelSize(R.dimen.control_min_height));
                    assertEquals(button.getPaddingTop(), button.getPaddingBottom());
                    assertReadable(button);
                }
            });
        }
    }

    @Test public void moneyInputsHavePersistentLabelsAndReadableActions() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PenanceActivity> scenario =
                     ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                int[] inputIds = {R.id.rule_detection_amount, R.id.detection_batch,
                        R.id.daily_cap, R.id.weekly_cap, R.id.mercy_minutes,
                        R.id.dwell_seconds};
                for (int id : inputIds) {
                    EditText input = activity.findViewById(id);
                    assertTrue((input.getGravity() & Gravity.CENTER_VERTICAL) != 0);
                    assertEquals(1, input.getMaxLines());
                    assertTrue(!input.getIncludeFontPadding());
                    assertTrue(input.getHeight() >= activity.getResources()
                            .getDimensionPixelSize(R.dimen.control_min_height));
                    assertTrue("Input must be linked to its visible label",
                            hasLabel(activity.findViewById(android.R.id.content), id));
                }
                int[] actionIds = {R.id.button_forgive_latest, R.id.button_clear_unpaid};
                for (int id : actionIds) {
                    TextView action = activity.findViewById(id);
                    assertTrue(action.getHeight() >= activity.getResources()
                            .getDimensionPixelSize(R.dimen.control_min_height));
                    assertReadable(action);
                }
            });
        }
    }

    private static boolean hasLabel(View view, int inputId) {
        if (view instanceof TextView && view.getLabelFor() == inputId) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (hasLabel(group.getChildAt(index), inputId)) return true;
            }
        }
        return false;
    }

    private static void assertReadable(TextView text) {
        assertNotNull(text.getLayout());
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals(0, text.getLayout().getEllipsisCount(line));
        }
        assertTrue(text.getLayout().getHeight() <= text.getHeight()
                - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
    }
}
