package com.subhub.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

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
    @Before public void setUpControllerPin() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
        }
        new FeatureModuleManager(context).save(true, true, true);
        ControllerPinManager.enterDomMode();
    }

    @Test public void subSpaceShowsHomeAndSettingsButHidesConfigurationNavigation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.button_edit_lock).performClick();
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.sub_dashboard).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.dom_content).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.bottom_navigation).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_settings).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.sub_atmosphere_card).getVisibility());
                assertFalse(activity.findViewById(R.id.button_censor_settings).isShown());
                assertTrue(activity.findViewById(R.id.button_protection).isEnabled());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.commitment_card).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.home_session_metrics).getVisibility());
                assertEquals(1, ((ViewGroup) activity.findViewById(
                        R.id.home_session_metrics)).getChildCount());
                assertEquals(2, ((ViewGroup) activity.findViewById(
                        R.id.home_lifetime_metrics)).getChildCount());
            });
        }
    }

    @Test public void domModeShowsAllConfigurationAreas() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterDomMode();
                activity.recreate();
            });
            scenario.onActivity(activity -> {
                assertTrue(ControllerPinManager.isDomModeActive());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.sub_dashboard).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.dom_content).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.bottom_navigation).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_protection).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.commitment_card).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.home_session_metrics).getVisibility());
                assertFalse(activity.findViewById(R.id.button_censor_settings).isShown());
            });
        }
    }

    @Test public void subDashboardOnlyShowsEnabledProductAreas() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                new FeatureModuleManager(activity).save(false, true, false);
                activity.findViewById(R.id.button_edit_lock).performClick();
                assertEquals(View.GONE,
                        activity.findViewById(R.id.sub_censor_card).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.sub_limits_card).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.sub_wallet_card).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_protection).getVisibility());
            });
        } finally {
            new FeatureModuleManager(context).save(true, true, true);
        }
    }
}
