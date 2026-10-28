package com.subhub.app.configuration;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashSet;

import java.util.Set;

public final class ConfigurationSettingCatalogTest {
    @Test public void everyConfigurationFieldHasUniqueTypedDefaultAndRoundTripsJson() throws Exception {
        Set<String> keys = new LinkedHashSet<>();

        for (String section : ConfigurationSchema.SECTIONS) {
            JSONObject defaults = ConfigurationSettingCatalog.defaults(section);
            JSONObject clean = ConfigurationSettingCatalog.sanitize(section, defaults);
            JSONObject roundTrip = ConfigurationSchema.sanitizeSection(section, new JSONObject(clean.toString()));
            assertEquals(ConfigurationSettingCatalog.fields(section).size(), clean.length());
            for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(section)) {
                assertTrue(field.key, keys.add(field.key));
                assertFalse(field.key, ConfigurationSchema.isSecretOrRuntimeKey(field.key));
                assertTrue(field.label > 0 && field.group > 0);
                assertEquals(field.defaultValue().toString(), clean.get(field.key).toString());
                assertEquals(clean.get(field.key).toString(), roundTrip.get(field.key).toString());
                for (String choice : field.choices) assertTrue(ConfigurationSettingCatalog.choiceLabel(field, choice) > 0);
            }

        }
        assertEquals(113, keys.size());
        assertNull(ConfigurationSettingCatalog.field("censor", "app_mode_kind"));
        assertTrue(ConfigurationSchema.isSecretOrRuntimeKey("app_included_packages_v1"));
    }

    @Test public void decimalMoneyAndPercentEditorsDoNotChangeUnitsOrPreferenceTypes() {
        ConfigurationSettingCatalog.Field money = ConfigurationSettingCatalog.field("wallet", "rule_new_detection_cents");
        assertEquals(123, money.parseText("1.23"));
        assertEquals(123, money.parseText("1,23"));
        assertEquals("1.23", money.displayText(123));
        assertThrows(IllegalArgumentException.class, () -> money.parseText("1.234"));
        ConfigurationSettingCatalog.Field percent = ConfigurationSettingCatalog.field("censor", "censor_size_padding");
        assertEquals(.25f, ((Number) percent.parseText("25")).floatValue(), .00001f);
        assertEquals("25", percent.displayText(.25f));
        assertTrue(ConfigurationSettingCatalog.field("censor", "detection_confidence_percent").normalize(30) instanceof Integer);
        assertTrue(ConfigurationSettingCatalog.field("subliminal", "subliminal_visible_ms").normalize(2000) instanceof Long);
        assertTrue(ConfigurationSettingCatalog.field("popup", "popup_storm_spawn_rate").normalize(2) instanceof Float);
    }

    @Test public void invalidKnownValuesFailRatherThanSilentlyChangingConfiguration() throws Exception {
        for (Object invalid : new Object[] {"true", 1, JSONObject.NULL}) {
            JSONObject value = new JSONObject().put("show_border", invalid);
            assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.sanitize("censor", value));
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.field("censor", "censor_intensity").normalize(5.5));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.field("popup", "popup_storm_spawn_rate").normalize(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.field("censor", "censor_type").normalize("unknown"));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.field("censor", "border_color").normalize("red"));
    }

    @Test public void selectionsRemainArraysAndAreCopiedAndDeduplicated() throws Exception {
        JSONArray phrases = new JSONArray().put("First").put("Second").put("First");
        JSONObject clean = ConfigurationSettingCatalog.sanitize("censor", new JSONObject().put("custom_phrases", phrases));
        assertEquals(2, clean.getJSONArray("custom_phrases").length());
        phrases.put("Later");
        assertEquals(2, clean.getJSONArray("custom_phrases").length());
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.field("censor", "custom_phrases")
                .normalize(new JSONArray().put("two\nlines")));
    }

    @Test public void retiredWalletAliasesAreNotMigrated() throws Exception {
        JSONObject values = new JSONObject().put("rule_enabled_new_detection", true)
                .put("rule_cents_new_detection", 123).put("strike_cents", 999);
        JSONObject clean = ConfigurationSettingCatalog.sanitize("wallet", values);
        assertEquals(0, clean.length());
        values.put("rule_new_detection_cents", 789);
        assertEquals(789, ConfigurationSettingCatalog.sanitize("wallet", values).getInt("rule_new_detection_cents"));
    }

    @Test public void relationshipsAndCompletedDefaultsAreValidated() throws Exception {
        JSONObject timing = new JSONObject().put("subliminal_min_interval_ms", 50000).put("subliminal_max_interval_ms", 10000);
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.sanitize("subliminal", timing));
        JSONObject caps = new JSONObject().put("daily_cap_cents", 1000).put("weekly_cap_cents", 500);
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.sanitize("wallet", caps));
        JSONObject price = new JSONObject().put("daily_cap_cents", 10);
        JSONObject completed = ConfigurationSettingCatalog.complete("wallet", price);
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.sanitize("wallet", completed));
        JSONObject phrases = new JSONObject().put("subliminal_custom_phrases", "x".repeat(121));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSettingCatalog.sanitize("subliminal", phrases));
    }

    @Test public void credentialsAssignmentsRuntimeAndConsentAreExcluded() throws Exception {
        JSONObject values = new JSONObject().put("show_border", false).put("app_mode_armed", true)
                .put("selected_packages", new JSONArray().put("private.app"))
                .put("popup_storm_acknowledged", true).put("controller_pin", "synthetic")
                .put("wallet_currency", "USD").put("total_paid_cents", 999).put("paypal_secret", "synthetic");
        for (String section : ConfigurationSchema.SECTIONS) {
            JSONObject clean = ConfigurationSettingCatalog.sanitize(section, values);
            assertEquals("censor".equals(section) ? 1 : 0, clean.length());
        }
    }

}
