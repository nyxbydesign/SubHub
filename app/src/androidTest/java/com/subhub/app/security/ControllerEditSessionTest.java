package com.subhub.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies the product-wide Dom/Sub presentation boundary. */
@RunWith(AndroidJUnit4.class)
public final class ControllerEditSessionTest {
    @org.junit.Rule public final com.subhub.app.UiAutomationStateRule automation =
            new com.subhub.app.UiAutomationStateRule();

    private static android.content.Intent homeIntent() {
        return new android.content.Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN);
    }

    @Before
    public void setUpControllerPin() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
        }
        new FeatureModuleManager(context).save(true, true, true);
        ControllerPinManager.enterDomMode();
    }

    @Test
    public void subSpaceShowsHomeAndSettingsButHidesConfigurationNavigation() {
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(homeIntent())) {
            scenario.onActivity(
                    activity -> {
                        activity.findViewById(R.id.button_edit_lock).performClick();
                        assertFalse(ControllerPinManager.isDomModeActive());
                        assertEquals(
                                View.GONE,
                                activity.findViewById(R.id.sub_dashboard).getVisibility());
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.home_sub_summary).getVisibility());
                        assertEquals(View.GONE, activity.findViewById(R.id.home_dom_overview).getVisibility());
                        assertEquals(
                                View.VISIBLE,
                                activity.findViewById(R.id.bottom_navigation).getVisibility());
                        assertEquals(
                                View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                        assertEquals(
                                View.GONE, activity.findViewById(R.id.nav_censor).getVisibility());
                        assertEquals(
                                View.GONE, activity.findViewById(R.id.nav_limits).getVisibility());
                        assertEquals(
                                View.GONE, activity.findViewById(R.id.nav_money).getVisibility());
                        assertEquals(
                                View.GONE,
                                activity.findViewById(R.id.nav_atmosphere).getVisibility());
                        assertEquals(View.GONE, activity.findViewById(R.id.nav_people).getVisibility());
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_stats).getVisibility());
                        assertEquals(
                                View.VISIBLE,
                                activity.findViewById(R.id.nav_settings).getVisibility());
                        assertTrue(activity.findViewById(R.id.button_protection).isEnabled());
                        assertEquals(
                                View.VISIBLE,
                                activity.findViewById(R.id.commitment_card).getVisibility());
                        assertTrue(activity.findViewById(R.id.home_service_widget).isShown());
                    });
        }
    }

    @Test
    public void domModeShowsAllConfigurationAreas() {
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(homeIntent())) {
            scenario.onActivity(
                    activity -> {
                        ControllerPinManager.enterDomMode();
                        activity.recreate();
                    });
            scenario.onActivity(
                    activity -> {
                        assertTrue(ControllerPinManager.isDomModeActive());
                        assertEquals(
                                View.GONE,
                                activity.findViewById(R.id.sub_dashboard).getVisibility());
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.home_dom_overview).getVisibility());
                        assertEquals(View.GONE, activity.findViewById(R.id.home_sub_summary).getVisibility());
                        assertEquals(
                                View.VISIBLE,
                                activity.findViewById(R.id.bottom_navigation).getVisibility());
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_people).getVisibility());
                        assertEquals(View.GONE, activity.findViewById(R.id.nav_contract).getVisibility());
                        assertEquals(View.GONE, activity.findViewById(R.id.nav_stats).getVisibility());
                        assertFalse(activity.findViewById(R.id.home_service_widget).isShown());
                    });
        }
    }

    @Test
    public void subContractKeepsAllConfiguredProductAreasVisible() throws Exception {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        new FeatureModuleManager(context).save(false, true, false);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.people.ContractActivity> scenario =
                ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            long until = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean rendered = new java.util.concurrent.atomic.AtomicBoolean();
            while (!rendered.get() && android.os.SystemClock.uptimeMillis() < until) {
                scenario.onActivity(activity -> rendered.set(contains(activity.findViewById(R.id.role_page_body),
                        activity.getString(R.string.atmosphere_title))));
                if (!rendered.get()) android.os.SystemClock.sleep(25);
            }
            assertTrue("Contract did not render", rendered.get());
            scenario.onActivity(activity -> {
                View contract = activity.findViewById(R.id.role_page_body);
                for (int label : new int[] {R.string.sub_censor_title, R.string.sub_limits_title,
                        R.string.sub_wallet_title, R.string.atmosphere_title})
                    assertTrue(contains(contract, activity.getString(label)));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
            });
        } finally {
            new FeatureModuleManager(context).save(true, true, true);
        }
    }

    private static boolean contains(View view, String value) {
        if (view instanceof android.widget.TextView
                && value.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                if (contains(group.getChildAt(index), value)) return true;
        }
        return false;
    }
}
