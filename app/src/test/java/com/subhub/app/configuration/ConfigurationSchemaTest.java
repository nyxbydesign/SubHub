package com.subhub.app.configuration;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

public final class ConfigurationSchemaTest {
    @Test public void captureCompletesAllowedFieldsWithoutAccountOrRuntimeState() throws Exception {
        SharedPreferences preferences = preferences(Map.of(
                "show_border", false, "enabled_categories", Set.of("face"),
                "paypal_secret", "synthetic", "app_mode_armed", true,
                "controller_pin", "synthetic", "app_included_packages_v1", Set.of("private.app")));
        JSONObject captured = ConfigurationSchema.captureMainSection("censor", preferences);
        assertFalse(captured.getBoolean("show_border"));
        assertEquals("face", captured.getJSONArray("enabled_categories").getString(0));
        assertEquals(ConfigurationSettingCatalog.keys("censor").size(), captured.length());
        for (String key : Set.of("paypal_secret", "app_mode_armed", "controller_pin", "app_included_packages_v1")) {
            assertFalse(captured.has(key));
        }
    }

    @Test public void walletCaptureKeepsRuleUnitsAndExcludesCurrencyAndLedger() throws Exception {
        JSONObject wallet = ConfigurationSchema.captureWallet(preferences(Map.of(
                "rule_new_detection_cents", 123, "wallet_currency", "USD", "total_paid_cents", 999)));
        assertEquals(123, wallet.getInt("rule_new_detection_cents"));
        assertFalse(wallet.has("wallet_currency"));
        assertFalse(wallet.has("total_paid_cents"));
        assertEquals(PenanceManager.PREFS_NAME, ConfigurationSchema.preferenceStore("wallet"));
        for (String section : ConfigurationSchema.SECTIONS) {
            if (!"wallet".equals(section)) assertEquals(SettingsRepository.PREFERENCES_NAME,
                    ConfigurationSchema.preferenceStore(section));
        }
    }

    @Test public void sanitizationRejectsUnknownSectionsAndKeepsIndependentSelections() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationSchema.sanitizeSection("unknown", new JSONObject()));
        JSONArray phrases = new JSONArray().put("First");
        JSONObject clean = ConfigurationSchema.sanitizeSection("censor",
                new JSONObject().put("custom_phrases", phrases).put("unknown", true));
        phrases.put("Second");
        assertEquals(1, clean.length());
        assertEquals(1, clean.getJSONArray("custom_phrases").length());
    }

    private SharedPreferences preferences(Map<String, ?> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) return values;
                    throw new AssertionError("Unexpected preference access: " + method.getName());
                });
    }
}
