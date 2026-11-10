package com.subhub.app.configuration;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Profile settings shared with the editor; no runtime or account state. */
public final class ConfigurationSchema {
    public static final String MODULES = "modules";
    public static final String CENSOR = "censor";
    public static final String LIMITS = "limits";
    public static final String WALLET = "wallet";
    public static final String SUBLIMINAL = "subliminal";
    public static final String POPUP = "popup";
    public static final Set<String> SECTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.List.of(MODULES, CENSOR, LIMITS, WALLET, SUBLIMINAL, POPUP)));

    private ConfigurationSchema() { }

    public static JSONObject captureSection(Context context, String section) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown configuration section");
        return capture(section, context.getSharedPreferences(preferenceStore(section), Context.MODE_PRIVATE).getAll());
    }

    public static JSONObject captureMainSection(String section, SharedPreferences preferences) {
        return capture(section, preferences.getAll());
    }

    public static JSONObject captureWallet(SharedPreferences preferences) {
        return capture(WALLET, preferences.getAll());
    }

    private static JSONObject capture(String section, Map<String, ?> all) {
        JSONObject stored = new JSONObject();
        for (String key : keysFor(section)) if (all.containsKey(key)) putJson(stored, key, all.get(key));
        return ConfigurationSettingCatalog.complete(section, stored);
    }

    public static JSONObject sanitizeSection(String section, JSONObject source) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown configuration section");
        return ConfigurationSettingCatalog.sanitize(section, source);
    }

    public static Set<String> keysFor(String section) {
        return ConfigurationSettingCatalog.keys(section);
    }

    public static String preferenceStore(String section) {
        return WALLET.equals(section) ? PenanceManager.PREFS_NAME : SettingsRepository.PREFERENCES_NAME;
    }

    public static boolean isSecretOrRuntimeKey(String key) {
        if (key == null) return true;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("paypal") || lower.contains("secret") || lower.contains("client_id")
                || lower.contains("vault") || lower.contains("wallet_id")
                || lower.contains("selected_packages") || lower.contains("included_packages")
                || lower.startsWith("app_include_all")
                || lower.contains("package_used")
                || lower.contains("allowance_minutes:") || lower.contains("armed")
                || lower.contains("commitment_") || lower.contains("hardcore_mode")
                || lower.contains("events") || lower.contains("history")
                || lower.contains("achievement") || lower.contains("stats")
                || lower.equals("wallet_currency") || lower.startsWith("total_paid_cents")
                || lower.equals(CustomImageManager.PACK_DIR_KEY)
                || lower.equals(PopupStormSettings.K_FOLDERS)
                || lower.equals(PopupStormSettings.K_PACK_DIR)
                || lower.equals(PopupStormSettings.K_ACK);
    }

    private static void putJson(JSONObject target, String key, Object value) {
        if (isSecretOrRuntimeKey(key)) return;
        try {
            target.put(key, value instanceof Set<?> ? new org.json.JSONArray((Set<?>) value) : value);
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException("Invalid configuration setting", invalid);
        }
    }
}
