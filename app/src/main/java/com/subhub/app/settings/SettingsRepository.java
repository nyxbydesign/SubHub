package com.subhub.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.NudeNetClassCatalog;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.overlay.CensorPhrases;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Typed access to current detector and appearance preferences. */
public final class SettingsRepository {
    public static final String PREFERENCES_NAME = "betablocker_settings";
    public static final String KEY_ENABLED_CATEGORIES = "enabled_categories";
    public static final String KEY_CONFIDENCE = "detection_confidence_percent";
    public static final String KEY_CENSOR_TYPE = "censor_type";
    public static final String KEY_CENSOR_COVERAGE = "censor_coverage";
    public static final String KEY_CENSOR_INTENSITY = "censor_intensity";
    public static final String KEY_SHOW_BORDER = "show_border";
    public static final String KEY_SHOW_TEXT = "show_text";
    public static final String KEY_BORDER_COLOR = "border_color";
    public static final String KEY_GRADIENT_START = "border_gradient_start";
    public static final String KEY_GRADIENT_END = "border_gradient_end";
    public static final String KEY_DETECTION_PRESET = "detection_quality";
    public static final String KEY_CENSOR_SIZE_PADDING = "censor_size_padding";
    public static final String KEY_ANIMATE_BORDER = "animate_border";
    public static final String KEY_BORDER_EFFECT = "border_effect";
    public static final String KEY_ENABLED_PHRASE_CATEGORIES = "enabled_phrase_categories";
    public static final String KEY_CUSTOM_PHRASES = "custom_phrases";
    public static final String KEY_REVERSE_MODE = "reverse_mode";
    public static final String KEY_REVERSE_STRENGTH = "reverse_strength";
    public static final String KEY_REVERSE_CUTOUT_SHAPE = "reverse_cutout_shape";
    public static final String KEY_ERROR_TITLE = "error_popup_title";
    public static final String KEY_ERROR_TEXT = "error_popup_text";
    public static final String KEY_TEXT_SMUT_ENABLED = "text_smut_enabled";
    public static final String KEY_TEXT_SMUT_SENSITIVITY = "text_smut_sensitivity";
    public static final String KEY_TEXT_SMUT_CATEGORIES = "text_smut_categories";
    public static final String KEY_CAPTURE_METHOD = "capture_method";
    public static final String KEY_EFFECT_PALETTE_PREFIX = "effect_palette_";

    private final SharedPreferences preferences;

    /** Explicit settings source for isolated exports; the default constructor remains live. */
    private SettingsRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public static SettingsRepository forPreferences(SharedPreferences preferences) {
        return new SettingsRepository(preferences);
    }

    public SettingsRepository(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences preferences() { return preferences; }

    public DetectorConfig loadDetectorConfig() {
        Set<String> stored = preferences.getStringSet(KEY_ENABLED_CATEGORIES, null);
        Set<String> categories = stored == null
                ? new LinkedHashSet<>(NudeNetClassCatalog.DEFAULT_ENABLED)
                : new LinkedHashSet<>(stored);
        DetectionPreset preset = loadDetectionPreset();
        float confidence = preferences.contains(KEY_CONFIDENCE)
                ? preferences.getInt(KEY_CONFIDENCE, Math.round(preset.getConfidence() * 100)) / 100f
                : preset.getConfidence();
        DetectorConfig.Builder builder = preset.applyTo(DetectorConfig.builder());
        return builder
                .censorCoverage(loadCensorCoverage())
                .enabledCategories(categories)
                .confidenceThreshold(confidence)
                .build();
    }

    public com.subhub.app.detection.CensorCoverage loadCensorCoverage() {
        try {
            return com.subhub.app.detection.CensorCoverage.fromPreference(
                    preferences.getString(KEY_CENSOR_COVERAGE, "detected_areas"));
        } catch (ClassCastException invalidPackValue) {
            return com.subhub.app.detection.CensorCoverage.DETECTED_AREAS;
        }
    }

    public DetectionPreset loadDetectionPreset() {
        return DetectionPreset.fromPreference(
                preferences.getString(KEY_DETECTION_PRESET, DetectionPreset.MEDIUM.preferenceValue()));
    }

    public TextSmutConfig loadTextSmutConfig() {
        Set<String> stored = preferences.getStringSet(
                KEY_TEXT_SMUT_CATEGORIES, TextSmutConfig.DEFAULT_CATEGORIES);
        return new TextSmutConfig(
                preferences.getBoolean(KEY_TEXT_SMUT_ENABLED, true),
                preferences.getInt(
                        KEY_TEXT_SMUT_SENSITIVITY, TextSmutConfig.SENSITIVITY_BALANCED),
                stored == null ? TextSmutConfig.DEFAULT_CATEGORIES : stored);
    }

    public void saveTextSmutConfig(TextSmutConfig config) {
        preferences.edit()
                .putBoolean(KEY_TEXT_SMUT_ENABLED, config.isEnabled())
                .putInt(KEY_TEXT_SMUT_SENSITIVITY, config.getSensitivity())
                .putStringSet(KEY_TEXT_SMUT_CATEGORIES,
                        new LinkedHashSet<>(config.getEnabledCategories()))
                .apply();
    }

    public void saveDetectionPreset(DetectionPreset preset) {
        preferences.edit()
                .putString(KEY_DETECTION_PRESET, preset.preferenceValue())
                .putInt(KEY_CONFIDENCE, Math.round(preset.getConfidence() * 100))
                .apply();
    }

    public CaptureMethod loadCaptureMethod() {
        return CaptureMethod.fromPreference(preferences.getString(
                KEY_CAPTURE_METHOD, CaptureMethod.APP_MODE.preferenceValue()));
    }

    public void saveCaptureMethod(CaptureMethod method) {
        CaptureMethod safe = method == null ? CaptureMethod.APP_MODE : method;
        preferences.edit().putString(KEY_CAPTURE_METHOD, safe.preferenceValue()).apply();
    }

    public CensorAppearance loadAppearance() {
        int borderColor;
        try {
            borderColor = Color.parseColor(preferences.getString(KEY_BORDER_COLOR, "#9860BE"));
        } catch (IllegalArgumentException error) {
            borderColor = Color.rgb(255, 0, 128);
        }
        CensorAppearance.Type type = CensorAppearance.Type.fromPreference(
                preferences.getString(KEY_CENSOR_TYPE, "box"));
        return new CensorAppearance(
                type,
                preferences.getInt(KEY_CENSOR_INTENSITY, 50),
                preferences.getFloat(KEY_CENSOR_SIZE_PADDING, 0.20f),
                preferences.getBoolean(KEY_SHOW_BORDER, true),
                preferences.getBoolean(KEY_ANIMATE_BORDER, false),
                CensorAppearance.BorderEffect.fromPreference(
                        preferences.getString(KEY_BORDER_EFFECT, "classic")),
                preferences.getBoolean(KEY_SHOW_TEXT, true),
                borderColor,
                loadEffectPalette(type),
                CensorPhrases.build(
                        preferences.getStringSet(
                                KEY_ENABLED_PHRASE_CATEGORIES, CensorPhrases.DEFAULT_ENABLED),
                        preferences.getStringSet(KEY_CUSTOM_PHRASES, Collections.emptySet())),
                preferences.getBoolean(KEY_REVERSE_MODE, false),
                Math.round(preferences.getFloat(KEY_REVERSE_STRENGTH, 1f) * 100),
                preferences.getString(KEY_REVERSE_CUTOUT_SHAPE, "rectangle"),
                preferences.getString(KEY_ERROR_TITLE, "SubHub"),
                preferences.getString(KEY_ERROR_TEXT, "Access blocked."),
                readColor(KEY_GRADIENT_START, Color.rgb(152, 96, 190)),
                readColor(KEY_GRADIENT_END, Color.rgb(76, 216, 235)));
    }

    public EffectPalette loadEffectPalette(CensorAppearance.Type type) {
        EffectPalette defaults = EffectPalette.defaultsFor(type);
        return new EffectPalette(
                readColor(paletteKey(type, 1), defaults.first()),
                readColor(paletteKey(type, 2), defaults.second()),
                readColor(paletteKey(type, 3), defaults.third()));
    }

    public void saveEffectPalette(CensorAppearance.Type type, EffectPalette palette) {
        if (type == null || palette == null) return;
        preferences.edit()
                .putString(paletteKey(type, 1), colorString(palette.first()))
                .putString(paletteKey(type, 2), colorString(palette.second()))
                .putString(paletteKey(type, 3), colorString(palette.third()))
                .apply();
    }

    public static String paletteKey(CensorAppearance.Type type, int slot) {
        CensorAppearance.Type safe = type == null ? CensorAppearance.Type.BOX : type;
        return KEY_EFFECT_PALETTE_PREFIX + safe.getPreferenceValue() + "_" + slot;
    }

    public static List<String> palettePreferenceKeys() {
        List<String> keys = new ArrayList<>();
        for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
            for (int slot = 1; slot <= 3; slot++) keys.add(paletteKey(type, slot));
        }
        return Collections.unmodifiableList(keys);
    }

    private int readColor(String key, int fallback) {
        try {
            return Color.parseColor(preferences.getString(key, colorString(fallback)));
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    public static String colorString(int color) {
        return String.format(Locale.ROOT, "#%08X", color);
    }

    public void saveAppearance(
            CensorAppearance.Type type,
            int intensity,
            boolean showBorder,
            boolean showText) {
        preferences.edit()
                .putString(KEY_CENSOR_TYPE, type.getPreferenceValue())
                .putInt(KEY_CENSOR_INTENSITY, intensity)
                .putBoolean(KEY_SHOW_BORDER, showBorder)
                .putBoolean(KEY_SHOW_TEXT, showText)
                .apply();
    }

    public void saveDetection(int confidencePercent, Set<String> categories) {
        preferences.edit()
                .putInt(KEY_CONFIDENCE, confidencePercent)
                .putStringSet(KEY_ENABLED_CATEGORIES, new LinkedHashSet<>(categories))
                .apply();
    }
}
