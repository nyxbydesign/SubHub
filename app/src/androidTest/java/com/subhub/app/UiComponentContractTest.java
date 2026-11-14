package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UiComponentContractTest {
    @Test
    public void statisticsOwnsAchievementsAndAtmosphereOnlyContainsEffects() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.stats.StatsActivity> scenario =
                ActivityScenario.launch(com.subhub.app.stats.StatsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.stats_achievements));
                org.junit.Assert.assertNull(activity.findViewById(R.id.achievements_home_card));
                org.junit.Assert.assertNull(activity.findViewById(R.id.achievement_progress));
                assertTrue(activity.findViewById(R.id.nav_stats).isSelected());
            });
        }
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AtmosphereActivity> scenario = ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.whispers_card).isShown());
                assertTrue(activity.findViewById(R.id.popup_storm_card).isShown());
                for (int id : new int[] {R.id.rituals_gallery_card,
                        R.id.rituals_keyholder_card, R.id.achievements_home_card})
                    org.junit.Assert.assertNull(activity.findViewById(id));
                assertTrue(activity.findViewById(R.id.nav_people).isSelected());
            });
        }
    }

    @Test
    public void retiredStudioAndPacksActivitiesAreNotRegistered() {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager packageManager = context.getPackageManager();
        for (String activity : new String[] {
                "com.subhub.app.studio.StudioActivity", "com.subhub.app.pack.PacksActivity"}) {
            ComponentName component = new ComponentName(context.getPackageName(), activity);
            assertThrows(PackageManager.NameNotFoundException.class,
                    () -> packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS));
        }
    }

    @Test
    public void subliminalChoicesAndDialogCloseHaveFullTouchTargets() {
        android.content.Context context =
                new android.view.ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(), R.style.Theme_SubHub);
        View page =
                LayoutInflater.from(context)
                        .inflate(R.layout.activity_subliminal_settings, null, false);
        int[] ids = {
            R.id.preset_slider,
            R.id.pack_obedience,
            R.id.pack_focus,
            R.id.pack_beta,
            R.id.pack_findom,
            R.id.pack_custom
        };
        int minimum = context.getResources().getDimensionPixelSize(R.dimen.control_min_height);
        for (int id : ids) {
            View choice = page.findViewById(id);
            choice.measure(
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(choice.getMeasuredHeight() >= minimum);
        }
        int[][] dialogs = {
            {R.layout.dialog_arrangement_details, R.id.arrangement_detail_close},
            {R.layout.dialog_achievement_details, R.id.achievement_detail_close}
        };
        for (int[] dialog : dialogs) {
            View close =
                    LayoutInflater.from(context)
                            .inflate(dialog[0], null, false)
                            .findViewById(dialog[1]);
            close.measure(
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(close.getMeasuredHeight() >= minimum);
        }
    }

    @Test
    public void atmosphereActionsHaveReadableTextAndFullTouchTargets() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AtmosphereActivity> scenario =
                ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                int[][] cards = {
                    {R.id.whispers_card, R.id.button_whispers, R.id.whispers_status},
                    {R.id.popup_storm_card, R.id.button_popup_storm, R.id.popup_storm_status},
                    {R.id.gag_card, R.id.button_gag, R.id.gag_status}
                };
                for (int[] ids : cards) {
                    View card = activity.findViewById(ids[0]);
                    View arrow = card.findViewById(ids[1]);
                    TextView status = card.findViewById(ids[2]);
                    assertTrue(card.isClickable());
                    assertTrue(card.isFocusable());
                    assertTrue(ViewCompat.isScreenReaderFocusable(card));
                    var node = AccessibilityNodeInfoCompat.wrap(card.createAccessibilityNodeInfo());
                    assertEquals(Button.class.getName(), node.getClassName().toString());
                    assertFalse(arrow.isClickable());
                    assertFalse(arrow.isFocusable());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            arrow.getImportantForAccessibility());
                    ViewGroup copy = (ViewGroup) status.getParent();
                    ViewGroup content = (ViewGroup) copy.getParent();
                    View icon = content.getChildAt(0);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            icon.getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            copy.getImportantForAccessibility());
                    if (card.isShown()) {
                        TextView title = (TextView) copy.getChildAt(0);
                        assertReadable(title);
                        assertReadable(status);
                        assertEquals(activity.getString(R.string.atmosphere_card_description,
                                title.getText(), status.getText()), node.getContentDescription().toString());
                        assertTrue(card.getHeight() >= activity.getResources()
                                .getDimensionPixelSize(R.dimen.control_min_height));
                        assertTrue(arrow.isShown());
                    }
                }
            });
        }
    }

    @Test
    public void moneyInputsHavePersistentLabelsAndReadableActions() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PenanceActivity> scenario =
                ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(
                    activity ->
                            activity.findViewById(android.R.id.content)
                                    .findViewWithTag("wallet:rules")
                                    .performClick());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .waitForIdleSync();
            scenario.onActivity(
                    activity -> {
                        int[] inputIds = {
                            R.id.rule_detection_amount,
                            R.id.detection_batch,
                            R.id.daily_cap,
                            R.id.weekly_cap,
                            R.id.mercy_minutes,
                            R.id.dwell_seconds
                        };
                        for (int id : inputIds) {
                            EditText input = activity.findViewById(id);
                            assertTrue((input.getGravity() & Gravity.CENTER_VERTICAL) != 0);
                            assertEquals(1, input.getMaxLines());
                            assertTrue(!input.getIncludeFontPadding());
                            assertTrue(
                                    input.getHeight()
                                            >= activity.getResources()
                                                    .getDimensionPixelSize(
                                                            R.dimen.control_min_height));
                            assertTrue(
                                    "Input must be linked to its visible label",
                                    hasLabel(activity.findViewById(android.R.id.content), id));
                        }
                    });
            scenario.onActivity(
                    activity ->
                            activity.findViewById(android.R.id.content)
                                    .findViewWithTag("wallet:corrections")
                                    .performClick());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .waitForIdleSync();
            scenario.onActivity(
                    activity -> {
                        int[] actionIds = {R.id.button_forgive_latest, R.id.button_clear_unpaid};
                        for (int id : actionIds) {
                            TextView action = activity.findViewById(id);
                            assertTrue(
                                    action.getHeight()
                                            >= activity.getResources()
                                                    .getDimensionPixelSize(
                                                            R.dimen.control_min_height));
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
        assertTrue(
                text.getLayout().getHeight()
                        <= text.getHeight()
                                - text.getCompoundPaddingTop()
                                - text.getCompoundPaddingBottom());
    }
}
