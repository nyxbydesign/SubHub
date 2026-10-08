package com.subhub.app.pack;

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

/** Explicit portable contract shared with the in-app editor; no runtime or account state. */
public final class SubHubPackSchema {
    public static final String MODULES = "modules";
    public static final String CENSOR = "censor";
    public static final String LIMITS = "limits";
    public static final String WALLET = "wallet";
    public static final String SUBLIMINAL = "subliminal";
    public static final String POPUP = "popup";
    public static final Set<String> SECTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.List.of(MODULES, CENSOR, LIMITS, WALLET, SUBLIMINAL, POPUP)));

    private SubHubPackSchema() { }

    public static JSONObject captureMainSection(String section, SharedPreferences preferences) {
        return capture(section, preferences.getAll());
    }

    public static JSONObject captureWallet(SharedPreferences preferences) {
        return capture(WALLET, preferences.getAll());
    }

    private static JSONObject capture(String section, Map<String, ?> all) {
        JSONObject stored = new JSONObject();
        for (String key : keysFor(section)) if (all.containsKey(key)) putJson(stored, key, all.get(key));
        return PackSettingCatalog.complete(section, stored);
    }

    public static JSONObject sanitizeSection(String section, JSONObject source) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown pack section");
        return PackSettingCatalog.sanitize(section, source);
    }

    public static JSONObject sanitizeRecommendations(JSONObject source) {
        JSONObject result = new JSONObject();
        if (source == null) return result;
        if (source.has("hardcoreSuggested")) {
            if (!(source.opt("hardcoreSuggested") instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid Hardcore recommendation");
            }
            putJson(result, "hardcoreSuggested", source.opt("hardcoreSuggested"));
        }
        if (source.has("serviceDurationMillis")) {
            Object raw = source.opt("serviceDurationMillis");
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).longValue()) {
                throw new IllegalArgumentException("Invalid service-duration recommendation");
            }
            long value = ((Number) raw).longValue();
            if (value != 0L && value != -1L && value != 3_600_000L && value != 86_400_000L
                    && value != 604_800_000L && value != 2_592_000_000L) {
                return result;
            }
            if (value != 0L) putJson(result, "serviceDurationMillis", value);
        }
        return result;
    }

    public static Set<String> keysFor(String section) {
        return PackSettingCatalog.keys(section);
    }

    public static String preferenceStore(String section) {
        return WALLET.equals(section) ? PenanceManager.PREFS_NAME : SettingsRepository.PREFERENCES_NAME;
    }

    public static boolean isSecretOrRuntimeKey(String key) {
        if (key == null) return true;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("paypal") || lower.contains("secret") || lower.contains("client_id")
                || lower.contains("vault") || lower.contains("wallet_id")
                || lower.contains("selected_packages") || lower.contains("package_used")
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
            throw new IllegalArgumentException("Invalid portable setting", invalid);
        }
    }
}
