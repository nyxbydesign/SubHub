package com.subhub.app.commitment;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.util.StateToggle;
import java.util.UUID;
import org.junit.Test;

/** Retained native controls use an owned preference store; production configuration is untouched. */
public final class ServiceDurationRetentionAndroidTest {
    private static final long HOUR = 3_600_000L;

    @Test public void refreshedPickerAdoptsExternalSettingsAndKeepsIncompleteInput() {
        fixture((view, prefs) -> {
            EditText minimum = view.findViewById(R.id.service_duration_min_hours);
            minimum.setText("");
            for (int repeat = 0; repeat < 3; repeat++) view.reloadIfChanged();
            assertEquals("", minimum.getText().toString());
            prefs.edit().putLong("duration", 24 * HOUR).putLong("minimum", 2 * HOUR)
                    .putLong("maximum", 5 * HOUR).putBoolean("hidden", true).commit();
            view.reloadIfChanged();
            assertEquals("", minimum.getText().toString());
            assertTrue(view.findViewById(R.id.commitment_timer_24h).isSelected());
            assertTrue(view.isCountdownHidden());
            ((StateToggle) view.findViewById(R.id.service_duration_hide)).setChecked(false);
            assertEquals(24 * HOUR, prefs.getLong("duration", 0));
            assertEquals(2 * HOUR, prefs.getLong("minimum", 0));
            assertEquals(5 * HOUR, prefs.getLong("maximum", 0));
            assertFalse(prefs.getBoolean("hidden", true));
        });
    }

    @Test public void hiddenToggleCannotRestoreStaleDurationOrBoundsEvenBeforeRefresh() {
        fixture((view, prefs) -> {
            prefs.edit().putLong("duration", 7 * 24 * HOUR).putLong("minimum", 8 * HOUR)
                    .putLong("maximum", 9 * HOUR).commit();
            ((StateToggle) view.findViewById(R.id.service_duration_hide)).setChecked(true);
            assertEquals(7 * 24 * HOUR, prefs.getLong("duration", 0));
            assertEquals(8 * HOUR, prefs.getLong("minimum", 0));
            assertEquals(9 * HOUR, prefs.getLong("maximum", 0));
            assertTrue(prefs.getBoolean("hidden", false));
            assertTrue(view.findViewById(R.id.commitment_timer_7d).isSelected());
            assertEquals("8", ((EditText) view.findViewById(R.id.service_duration_min_hours)).getText().toString());
        });
    }

    @Test public void durationChoiceCannotRestoreStaleVisibilityOrBounds() {
        fixture((view, prefs) -> {
            prefs.edit().putLong("minimum", 2 * HOUR).putLong("maximum", 5 * HOUR)
                    .putBoolean("hidden", true).commit();
            view.findViewById(R.id.commitment_timer_1h).performClick();
            assertEquals(HOUR, prefs.getLong("duration", 0));
            assertEquals(2 * HOUR, prefs.getLong("minimum", 0));
            assertEquals(5 * HOUR, prefs.getLong("maximum", 0));
            assertTrue(prefs.getBoolean("hidden", false));
        });
    }

    private static void fixture(java.util.function.BiConsumer<ServiceDurationView, SharedPreferences> action) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "duration-retention-test-" + UUID.randomUUID();
        SharedPreferences prefs = target.getSharedPreferences(name, 0);
        Context source = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return prefs; }
        };
        try {
            assertTrue(prefs.edit().putLong("duration", ServiceDurationView.RANDOM).putLong("minimum", HOUR)
                    .putLong("maximum", 24 * HOUR).putBoolean("hidden", false).commit());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context themed = new android.view.ContextThemeWrapper(target, R.style.Theme_SubHub);
                action.accept(new ServiceDurationView(themed, null, source, () -> true), prefs);
            });
        } finally { assertTrue(target.deleteSharedPreferences(name)); }
    }
}
