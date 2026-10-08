package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.studio.StudioActivity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native interaction and hierarchy checks; never creates a payment or starts service. */
@RunWith(AndroidJUnit4.class)
public final class UxFlowRegressionAndroidTest {
    private Context context;
    private final Map<SharedPreferences, Map<String, ?>> original = new LinkedHashMap<>();
    private boolean originalDom;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        originalDom = ControllerPinManager.isDomModeActive();
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME,
                PenanceManager.PREFS_NAME}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            original.put(prefs, new LinkedHashMap<>(prefs.getAll()));
        }
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true);
        new AppModeManager(context).setArmed(false);
    }

    @After public void tearDown() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> snapshot : original.entrySet()) {
            SharedPreferences.Editor editor = snapshot.getKey().edit().clear();
            for (Map.Entry<String, ?> entry : snapshot.getValue().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> set = (Set<String>) value;
                    editor.putStringSet(key, new LinkedHashSet<>(set));
                }
            }
            editor.commit();
        }
        if (originalDom) ControllerPinManager.enterDomMode();
        else ControllerPinManager.enterSubMode();
    }

    @Test public void walletLockFlushesSameFrameValidEdit() {
        PenanceManager manager = new PenanceManager(context);
        manager.configure(true, 100, 500, 2000, 0);
        try (ActivityScenario<PenanceActivity> scenario = ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                EditText amount = activity.findViewById(R.id.rule_detection_amount);
                amount.setText("2.00");
                activity.findViewById(R.id.button_edit_lock).performClick();
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(200, manager.getInfractionCents(PenanceInfraction.NEW_DETECTION));
            });
        }
    }

    @Test public void unassignedLimitsCannotLookEnabledAndOfferChooseApps() {
        AppModeManager apps = new AppModeManager(context);
        apps.save(false, AppModePolicy.Mode.SELECTED_APPS, Collections.emptySet());
        apps.saveAppSelections(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(false, 30, false, 120);
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            scenario.onActivity(activity -> {
                CompoundButton perApp = activity.findViewById(R.id.per_app_limit_enabled);
                CompoundButton total = activity.findViewById(R.id.total_limit_enabled);
                perApp.performClick();
                assertFalse(perApp.isChecked());
                total.performClick();
                assertFalse(total.isChecked());
                assertFalse(timers.loadSettings().perAppEnabled);
                assertFalse(timers.loadSettings().totalEnabled);
                View action = activity.findViewById(R.id.per_app_allowances_list)
                        .findViewWithTag("limits_choose_apps");
                assertNotNull(action);
                assertTrue(action.isClickable());
            });
        }
    }

    @Test public void appAssignmentDestinationExpandsWithoutChangingScope() {
        AppModeManager apps = new AppModeManager(context);
        apps.save(false, AppModePolicy.Mode.SELECTED_APPS, Collections.emptySet());
        apps.saveAppSelections(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                GlobalSettingsActivity.class.getName(), null, false);
        Activity destination = null;
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.per_app_allowances_list)
                    .findViewWithTag("limits_choose_apps").performClick());
            destination = monitor.waitForActivityWithTimeout(3000);
            assertNotNull(destination);
            instrumentation.waitForIdleSync();
            Activity opened = destination;
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, opened.findViewById(R.id.app_list_content).getVisibility());
                assertEquals(AppModePolicy.Mode.SELECTED_APPS, apps.getMode());
                assertFalse(apps.isArmed());
            });
        } finally {
            if (destination != null) {
                Activity opened = destination;
                instrumentation.runOnMainSync(opened::finish);
            }
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void perAppAllowanceIsLabelledWithItsApp() {
        AppModeManager apps = new AppModeManager(context);
        Set<String> selected = Collections.singleton(context.getPackageName());
        apps.save(false, AppModePolicy.Mode.SELECTED_APPS, selected);
        apps.saveAppSelections(selected, selected, Collections.emptySet());
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup list = activity.findViewById(R.id.per_app_allowances_list);
                ViewGroup row = (ViewGroup) list.getChildAt(0);
                TextView label = (TextView) row.getChildAt(0);
                EditText input = (EditText) row.getChildAt(1);
                assertEquals(input.getId(), label.getLabelFor());
                assertTrue(input.getContentDescription().toString().contains(label.getText()));
            });
        }
    }

    @Test public void walletUsesOneScrollChildAndActionSafeguardOrder() {
        try (ActivityScenario<PenanceActivity> scenario = ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                View balance = activity.findViewById(R.id.balance_card);
                ViewGroup sections = (ViewGroup) balance.getParent();
                View historyCard = (View) activity.findViewById(R.id.history).getParent();
                View[] order = {balance, activity.findViewById(R.id.checkout_card),
                        activity.findViewById(R.id.safety_config_card),
                        activity.findViewById(R.id.rule_config_card),
                        activity.findViewById(R.id.paid_pause_config_card), historyCard,
                        activity.findViewById(R.id.corrections_card)};
                int previous = -1;
                for (View section : order) {
                    assertEquals(sections, section.getParent());
                    assertTrue(sections.indexOfChild(section) > previous);
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) section.getLayoutParams();
                    int expectedMargin = activity.getResources().getDimensionPixelSize(R.dimen.page_margin);
                    assertEquals(expectedMargin, margins.leftMargin);
                    assertEquals(expectedMargin, margins.rightMargin);
                    assertEquals(balance.getLeft(), section.getLeft());
                    assertEquals(balance.getRight(), section.getRight());
                    previous = sections.indexOfChild(section);
                }
                assertEquals(1, ((ViewGroup) sections.getParent()).getChildCount());
            });
        }
    }

    @Test public void settingsFollowSetupBeforeHardcoreAndPayments() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup sections = activity.findViewById(R.id.settings_sections);
                int previous = -1;
                for (int id : new int[] {R.id.feature_areas_card, R.id.apps_card,
                        R.id.hardcore_card, R.id.app_settings_card, R.id.paypal_card}) {
                    View card = activity.findViewById(id);
                    assertEquals(sections, card.getParent());
                    assertTrue(sections.indexOfChild(card) > previous);
                    previous = sections.indexOfChild(card);
                }
                assertEquals(activity.findViewById(R.id.apps_card),
                        activity.findViewById(R.id.android_access_card).getParent());
            });
        }
    }

    @Test public void studioExposesExplicitBackAction() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                GlobalSettingsActivity.class.getName(), null, false);
        Activity destination = null;
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> {
                View back = activity.findViewById(R.id.button_back);
                assertEquals(View.VISIBLE, back.getVisibility());
                assertTrue(back.hasOnClickListeners());
                back.performClick();
            });
            destination = monitor.waitForActivityWithTimeout(3000);
            assertNotNull(destination);
            assertTrue(destination instanceof GlobalSettingsActivity);
        } finally {
            if (destination != null) {
                Activity opened = destination;
                instrumentation.runOnMainSync(opened::finish);
            }
            instrumentation.removeMonitor(monitor);
        }
    }
}
