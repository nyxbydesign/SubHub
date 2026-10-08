package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;
import com.subhub.app.MainActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AtmosphereContractTest {
    @Test public void atmosphereIsACompactHomeCardInSubSpace() {
        Context context = ApplicationProvider.getApplicationContext();
        new FeatureModuleManager(context).save(true, true, true, true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.sub_atmosphere_card).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_settings).getVisibility());
            });
        } finally {
            ControllerPinManager.enterDomMode();
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
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
            });
        }
    }
}
