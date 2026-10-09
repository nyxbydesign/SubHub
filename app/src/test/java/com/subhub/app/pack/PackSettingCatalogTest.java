package com.subhub.app.pack;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PackSettingCatalogTest {
    @Test public void everyTransferableFieldHasUniqueTypedDefaultAndRoundTrips() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        Map<String, JSONObject> sections = new LinkedHashMap<>();
        for (String section : SubHubPackSchema.SECTIONS) {
            JSONObject defaults = PackSettingCatalog.defaults(section);
            JSONObject clean = PackSettingCatalog.sanitize(section, defaults);
            assertEquals(PackSettingCatalog.fields(section).size(), clean.length());
            for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
                assertTrue(field.key, keys.add(field.key));
                assertFalse(field.key, SubHubPackSchema.isSecretOrRuntimeKey(field.key));
                assertTrue(field.label > 0 && field.group > 0);
                assertEquals(field.defaultValue().toString(), clean.get(field.key).toString());
                for (String choice : field.choices) assertTrue(PackSettingCatalog.choiceLabel(field, choice) > 0);
            }
            sections.put(section, clean);
        }
        assertEquals(114, keys.size());
        assertNull(PackSettingCatalog.field("censor", "app_mode_kind"));
        assertTrue(SubHubPackSchema.isSecretOrRuntimeKey("app_included_packages_v1"));
        SubHubPack source = SubHubPack.blank("synthetic-creator");
        for (Map.Entry<String, JSONObject> section : sections.entrySet()) source.setSection(section.getKey(), section.getValue());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SubHubPackArchive.write(source, bytes);
        SubHubPack imported = SubHubPackArchive.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(sections.keySet(), imported.getIncludedSections());
        for (String section : sections.keySet()) assertEquals(PackVerifier.canonicalize(sections.get(section)),
                PackVerifier.canonicalize(imported.getSection(section)));
        assertFalse(imported.manifestWithoutIntegrity(Map.of()).has("lockGroups"));
        assertEquals(4, imported.manifestWithoutIntegrity(Map.of()).getInt("schemaVersion"));
    }

    @Test public void decimalMoneyAndPercentEditorsDoNotChangeUnitsOrPreferenceTypes() {
        PackSettingCatalog.Field money = PackSettingCatalog.field("wallet", "rule_new_detection_cents");
        assertEquals(123, money.parseText("1.23"));
        assertEquals(123, money.parseText("1,23"));
        assertEquals("1.23", money.displayText(123));
        assertThrows(IllegalArgumentException.class, () -> money.parseText("1.234"));
        PackSettingCatalog.Field percent = PackSettingCatalog.field("censor", "censor_size_padding");
        assertEquals(.25f, ((Number) percent.parseText("25")).floatValue(), .00001f);
        assertEquals("25", percent.displayText(.25f));
        assertTrue(PackSettingCatalog.field("censor", "detection_confidence_percent").normalize(30) instanceof Integer);
        assertTrue(PackSettingCatalog.field("subliminal", "subliminal_visible_ms").normalize(2000) instanceof Long);
        assertTrue(PackSettingCatalog.field("popup", "popup_storm_spawn_rate").normalize(2) instanceof Float);
    }

    @Test public void invalidKnownValuesFailRatherThanSilentlyChangingThePack() throws Exception {
        for (Object invalid : new Object[] {"true", 1, JSONObject.NULL}) {
            JSONObject value = new JSONObject().put("show_border", invalid);
            assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.sanitize("censor", value));
        }
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.field("censor", "censor_intensity").normalize(5.5));
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.field("popup", "popup_storm_spawn_rate").normalize(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.field("censor", "censor_type").normalize("unknown"));
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.field("censor", "border_color").normalize("red"));
    }

    @Test public void selectionsRemainArraysAndAreCopiedAndDeduplicated() throws Exception {
        JSONArray phrases = new JSONArray().put("First").put("Second").put("First");
        JSONObject clean = PackSettingCatalog.sanitize("censor", new JSONObject().put("custom_phrases", phrases));
        assertEquals(2, clean.getJSONArray("custom_phrases").length());
        phrases.put("Later");
        assertEquals(2, clean.getJSONArray("custom_phrases").length());
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.field("censor", "custom_phrases")
                .normalize(new JSONArray().put("two\nlines")));
    }

    @Test public void retiredWalletAliasesAreNotMigrated() throws Exception {
        JSONObject values = new JSONObject().put("rule_enabled_new_detection", true)
                .put("rule_cents_new_detection", 123).put("strike_cents", 999);
        JSONObject clean = PackSettingCatalog.sanitize("wallet", values);
        assertEquals(0, clean.length());
        values.put("rule_new_detection_cents", 789);
        assertEquals(789, PackSettingCatalog.sanitize("wallet", values).getInt("rule_new_detection_cents"));
    }

    @Test public void relationshipsAndCompletedDefaultsAreValidated() throws Exception {
        JSONObject timing = new JSONObject().put("subliminal_min_interval_ms", 50000).put("subliminal_max_interval_ms", 10000);
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.sanitize("subliminal", timing));
        JSONObject caps = new JSONObject().put("daily_cap_cents", 1000).put("weekly_cap_cents", 500);
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.sanitize("wallet", caps));
        JSONObject price = new JSONObject().put("daily_cap_cents", 10);
        JSONObject completed = PackSettingCatalog.complete("wallet", price);
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.sanitize("wallet", completed));
        JSONObject phrases = new JSONObject().put("subliminal_custom_phrases", "x".repeat(121));
        assertThrows(IllegalArgumentException.class, () -> PackSettingCatalog.sanitize("subliminal", phrases));
    }

    @Test public void credentialsAssignmentsRuntimeAndConsentAreNeverPortable() throws Exception {
        JSONObject values = new JSONObject().put("show_border", false).put("app_mode_armed", true)
                .put("selected_packages", new JSONArray().put("private.app"))
                .put("popup_storm_acknowledged", true).put("controller_pin", "synthetic")
                .put("wallet_currency", "USD").put("total_paid_cents", 999).put("paypal_secret", "synthetic");
        for (String section : SubHubPackSchema.SECTIONS) {
            JSONObject clean = PackSettingCatalog.sanitize(section, values);
            assertEquals("censor".equals(section) ? 1 : 0, clean.length());
        }
    }

    @Test public void draftSnapshotCannotMutateOriginalSettingsOrImageBytes() throws Exception {
        SubHubPack original = SubHubPack.blank("synthetic");
        original.setSection("censor", new JSONObject().put("show_border", true));
        byte[] image = {1, 2, 3};
        original.putAsset("assets/censor/test.png", image);
        image[0] = 9;
        SubHubPack copy = original.snapshot();
        copy.getAsset("assets/censor/test.png")[0] = 8;
        copy.setSection("censor", new JSONObject().put("show_border", false));
        copy.removeAsset("assets/censor/test.png");
        assertTrue(original.getSection("censor").getBoolean("show_border"));
        assertArrayEquals(new byte[] {1, 2, 3}, original.getAsset("assets/censor/test.png"));
    }
}
