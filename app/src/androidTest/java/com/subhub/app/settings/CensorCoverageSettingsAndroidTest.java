package com.subhub.app.settings;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import android.widget.RadioButton;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.detection.CensorCoverage;
import com.subhub.app.pack.SubHubPackSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class CensorCoverageSettingsAndroidTest {
    @Test public void lockedUiCannotSaveCoverageEvenFromProgrammaticToggle() {
        SettingsRepository settings = new SettingsRepository(ApplicationProvider.getApplicationContext());
        String key = SettingsRepository.KEY_CENSOR_COVERAGE;
        boolean existed = settings.preferences().contains(key);
        String previous = settings.preferences().getString(key, "detected_areas");
        settings.preferences().edit().putString(key, "detected_areas").commit();
        ControllerPinManager.lockNow();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                RadioButton areas = activity.findViewById(R.id.radio_coverage_areas);
                RadioButton person = activity.findViewById(R.id.radio_coverage_person);
                assertFalse(areas.isEnabled());
                assertFalse(person.isEnabled());
                assertTrue(areas.isChecked());
                assertEquals("Whole person (requires body-capable model)", person.getText().toString());
                person.setChecked(true);
                assertEquals(CensorCoverage.DETECTED_AREAS, settings.loadCensorCoverage());
            });
        } finally {
            if (existed) settings.preferences().edit().putString(key, previous).commit();
            else settings.preferences().edit().remove(key).commit();
        }
    }

    @Test public void defaultRoundTripInvalidValueAndPortableKey() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsRepository settings = new SettingsRepository(context);
        String key = SettingsRepository.KEY_CENSOR_COVERAGE;
        boolean existed = settings.preferences().contains(key);
        String previous = settings.preferences().getString(key, "detected_areas");
        try {
            settings.preferences().edit().remove(key).commit();
            assertEquals(CensorCoverage.DETECTED_AREAS, settings.loadDetectorConfig().getCensorCoverage());
            settings.preferences().edit().putString(key, "whole_person").commit();
            assertEquals(CensorCoverage.WHOLE_PERSON, settings.loadDetectorConfig().getCensorCoverage());
            settings.preferences().edit().putString(key, "unknown").commit();
            assertEquals(CensorCoverage.DETECTED_AREAS, settings.loadDetectorConfig().getCensorCoverage());
            assertTrue(SubHubPackSchema.keysFor(SubHubPackSchema.CENSOR).contains(key));
            settings.preferences().edit().putBoolean(key, true).commit();
            assertEquals(CensorCoverage.DETECTED_AREAS, settings.loadDetectorConfig().getCensorCoverage());
        } finally {
            if (existed) settings.preferences().edit().putString(key, previous).commit();
            else settings.preferences().edit().remove(key).commit();
        }
    }
}
