package com.subhub.app.penance;

import static org.junit.Assert.*;
import com.subhub.app.configuration.DraftPreferences;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class InternalPenanceRulesTest {
    @Test public void constructingAndReadingRulesDoesNotMigrateOrWriteAnything() {
        DraftPreferences preferences = new DraftPreferences();
        preferences.edit().putString("wallet_currency", "USD")
                .putInt("detection_batch_remainder", 7).putString("events_v1", "owned-test-sentinel").commit();
        Map<String, ?> before = preferences.getAll();
        InternalPenanceRules rules = new InternalPenanceRules(preferences);
        assertEquals("USD", rules.getCurrency());
        assertTrue(rules.isInfractionEnabled(PenanceInfraction.NEW_DETECTION));
        assertEquals(100, rules.getInfractionCents(PenanceInfraction.NEW_DETECTION));
        assertEquals(before, preferences.getAll());
    }

    @Test public void saveChangesOnlyRuleConfigurationNotLedgerOrFinancialState() {
        DraftPreferences preferences = new DraftPreferences();
        preferences.edit().putInt("detection_batch_remainder", 7)
                .putString("events_v1", "owned-test-sentinel").putString("active_order_id", "owned-test-order").commit();
        InternalPenanceRules rules = new InternalPenanceRules(preferences);
        rules.configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125, PenanceInfraction.TASK_FAILURE, 200),
                500, 2000, 10, 15, 3, 8);
        assertEquals(125, rules.getInfractionCents(PenanceInfraction.NEW_DETECTION));
        assertEquals(200, rules.getInfractionCents(PenanceInfraction.TASK_FAILURE));
        assertTrue(rules.isInfractionEnabled(PenanceInfraction.TASK_FAILURE));
        assertFalse(rules.isInfractionEnabled(PenanceInfraction.CENSORED_DWELL));
        assertEquals(3, rules.getDetectionBatch());
        assertEquals(7, preferences.getInt("detection_batch_remainder", -1));
        assertEquals("owned-test-sentinel", preferences.getString("events_v1", ""));
        assertEquals("owned-test-order", preferences.getString("active_order_id", ""));
        assertFalse(preferences.contains("rule_paid_pause_enabled"));
        assertFalse(preferences.contains("strike_cents"));
        assertFalse(preferences.contains("rule_participation_migrated_v1"));
    }

    @Test public void disablingRuleDoesNotResetRuntimeAccumulator() {
        DraftPreferences preferences = new DraftPreferences();
        preferences.edit().putInt("detection_batch_remainder", 4).commit();
        new InternalPenanceRules(preferences).disableInfraction(PenanceInfraction.NEW_DETECTION);
        assertFalse(preferences.getBoolean("rule_new_detection_enabled", true));
        assertEquals(4, preferences.getInt("detection_batch_remainder", -1));
        assertEquals(Set.of("detection_batch_remainder", "rule_new_detection_enabled"), preferences.getAll().keySet());
    }

    @Test public void pauseConfigurationHasNoActivationSettlementOrScheduleState() {
        DraftPreferences preferences = new DraftPreferences();
        PaidPauseRuleSettings rules = PaidPauseRuleSettings.configuration(preferences);
        rules.configure(true, 300, 7);
        assertTrue(rules.isEnabled()); assertEquals(300, rules.getPriceCents()); assertEquals(7, rules.getDurationMinutes());
        assertEquals(Set.of("paid_pause_enabled", "paid_pause_price_cents", "paid_pause_duration_minutes"),
                preferences.getAll().keySet());
    }
}
