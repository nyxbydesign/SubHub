package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AtmosphereContractTest {
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @org.junit.Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void subContractKeepsAtmosphereReadOnlyAndSettingsInTheSharedHeader() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.people.ContractActivity> scenario =
                     ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            long deadline = android.os.SystemClock.uptimeMillis() + 10000;
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            do {
                scenario.onActivity(activity -> ready.set(activity.findViewById(android.R.id.content)
                        .findViewWithTag("contract_section:atmosphere") != null));
                if (!ready.get()) android.os.SystemClock.sleep(30);
            } while (!ready.get() && android.os.SystemClock.uptimeMillis() < deadline);
            org.junit.Assert.assertTrue(ready.get());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                    org.hamcrest.Matchers.is((Object) "contract_section:atmosphere")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(android.R.id.content)
                        .findViewWithTag("contract_details:atmosphere").getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.primary_header_settings).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
            });
        }
    }

    @Test public void atmosphereHubIsEditableOnlyInDomSpace() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AtmosphereActivity> scenario =
                     ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.GONE,
                        activity.findViewById(R.id.primary_header_subtitle).getVisibility());
                assertNotNull(activity.findViewById(R.id.whispers_card));
                assertNotNull(activity.findViewById(R.id.popup_storm_card));
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.primary_header_settings).getVisibility());
            });
        }
    }
}
