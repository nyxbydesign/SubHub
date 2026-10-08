package com.subhub.app.pack;

import com.subhub.app.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One typed contract for portable settings, draft controls and preference application.
 * Runtime/account/permission state is intentionally not in this catalog.
 */
public final class PackSettingCatalog {
    public enum Kind { BOOLEAN, INTEGER, LONG, DECIMAL, RATIO, MONEY, TEXT, COLOR, CHOICE, SELECTION }
    public enum Problem { VALUE, MINIMUM_MAXIMUM, SPENDING_CAP, MESSAGE_LIMIT }
    public static final class ValidationException extends IllegalArgumentException {
        public final String key;
        public final Problem problem;
        ValidationException(String key, Problem problem) {
            super("Invalid portable setting: " + key);
            this.key = key; this.problem = problem;
        }
    }
    private static final Map<String, List<Field>> SECTIONS = new LinkedHashMap<>();
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    static {
        add("modules", "module_censor_enabled", R.string.pack_field_module_censor_enabled, R.string.pack_group_features, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("modules", "module_limits_enabled", R.string.pack_field_module_limits_enabled, R.string.pack_group_features, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("modules", "module_wallet_enabled", R.string.pack_field_module_wallet_enabled, R.string.pack_group_features, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("modules", "module_subliminal_enabled", R.string.pack_field_module_subliminal_enabled, R.string.pack_group_features, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("censor", "detection_quality", R.string.pack_field_detection_preset, R.string.pack_group_detection, Kind.CHOICE, "medium", 0, 0, 0, List.of("low", "medium", "high"));
        add("censor", "detection_confidence_percent", R.string.pack_field_confidence_threshold_percent, R.string.pack_group_detection, Kind.INTEGER, 25, 10, 80, 0, List.of());
        add("censor", "capture_method", R.string.pack_field_capture_method, R.string.pack_group_detection, Kind.CHOICE, "app_mode", 0, 0, 0, List.of("app_mode", "screen_recording"));
        add("censor", "app_mode_kind", R.string.pack_field_app_mode_kind, R.string.pack_group_detection, Kind.CHOICE, "always", 0, 0, 0, List.of("always", "selected"));
        add("censor", "censor_coverage", R.string.pack_field_censor_coverage, R.string.pack_group_detection, Kind.CHOICE, "detected_areas", 0, 0, 0, List.of("detected_areas", "whole_person"));
        add("censor", "enabled_categories", R.string.pack_field_enabled_categories, R.string.pack_group_detection, Kind.SELECTION, array("genitals_female", "genitals_male", "breasts", "buttocks", "anus"), 0, 200, 120, List.of("genitals_female", "genitals_male", "breasts", "buttocks", "anus", "genitals_covered", "breasts_covered", "buttocks_covered", "anus_covered", "male_chest", "belly", "belly_covered", "feet", "feet_covered", "armpits", "armpits_covered", "face", "face_female", "face_male"));
        add("censor", "text_smut_enabled", R.string.pack_field_text_smut_enabled, R.string.pack_group_text_filter, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("censor", "text_smut_sensitivity", R.string.pack_field_text_smut_sensitivity, R.string.pack_group_text_filter, Kind.INTEGER, 1, 0, 2, 0, List.of("0", "1", "2"));
        add("censor", "text_smut_categories", R.string.pack_field_text_smut_categories, R.string.pack_group_text_filter, Kind.SELECTION, array("explicit_language", "fetish_context", "sexual_solicitation"), 0, 200, 120, List.of("explicit_language", "fetish_context", "sexual_solicitation"));
        add("censor", "censor_type", R.string.pack_field_censor_type, R.string.pack_group_appearance, Kind.CHOICE, "box", 0, 0, 0, List.of("box", "pixelate", "blur", "custom", "static", "glitch", "tape", "error_popup"));
        add("censor", "censor_intensity", R.string.pack_field_censor_intensity, R.string.pack_group_appearance, Kind.INTEGER, 50, 0, 100, 0, List.of());
        add("censor", "censor_size_padding", R.string.pack_field_censor_size_padding, R.string.pack_group_appearance, Kind.RATIO, 0.2, 0, 1, 0, List.of());
        add("censor", "error_popup_title", R.string.pack_field_error_popup_title, R.string.pack_group_appearance, Kind.TEXT, "SubHub", 0, 0, 120, List.of());
        add("censor", "error_popup_text", R.string.pack_field_error_popup_text, R.string.pack_group_appearance, Kind.TEXT, "Access blocked.", 0, 0, 500, List.of());
        add("censor", "show_border", R.string.pack_field_show_border, R.string.pack_group_border, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("censor", "animate_border", R.string.pack_field_animate_border, R.string.pack_group_border, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("censor", "border_effect", R.string.pack_field_border_effect, R.string.pack_group_border, Kind.CHOICE, "classic", 0, 0, 0, List.of("classic", "glow", "gradient", "rainbow"));
        add("censor", "border_color", R.string.pack_field_border_color, R.string.pack_group_border, Kind.COLOR, "#9860BE", 0, 0, 0, List.of());
        add("censor", "border_gradient_start", R.string.gradient_start, R.string.pack_group_border, Kind.COLOR, "#9860BE", 0, 0, 0, List.of());
        add("censor", "border_gradient_end", R.string.gradient_end, R.string.pack_group_border, Kind.COLOR, "#4CD8EB", 0, 0, 0, List.of());
        add("censor", "show_text", R.string.pack_field_show_text, R.string.pack_group_phrases, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("censor", "enabled_phrase_categories", R.string.pack_field_enabled_phrase_categories, R.string.pack_group_phrases, Kind.SELECTION, array("short", "denial"), 0, 200, 120, List.of("short", "denial", "humiliation", "edge", "findom", "ntr", "gooner"));
        add("censor", "custom_phrases", R.string.pack_field_custom_phrases, R.string.pack_group_phrases, Kind.SELECTION, array(), 0, 200, 80, List.of());
        add("censor", "reverse_mode", R.string.pack_field_reverse_mode, R.string.pack_group_reverse, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("censor", "reverse_strength", R.string.pack_field_reverse_strength, R.string.pack_group_reverse, Kind.RATIO, 1, 0.01, 1, 0, List.of());
        add("censor", "reverse_cutout_shape", R.string.pack_field_reverse_cutout_shape, R.string.pack_group_reverse, Kind.CHOICE, "rectangle", 0, 0, 0, List.of("rectangle", "rounded", "ellipse"));
        add("censor", "effect_palette_box_1", R.string.pack_field_effect_palette_box_1, R.string.pack_group_palettes, Kind.COLOR, "#FF000000", 0, 0, 0, List.of());
        add("censor", "effect_palette_box_2", R.string.pack_field_effect_palette_box_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_box_3", R.string.pack_field_effect_palette_box_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_pixelate_1", R.string.pack_field_effect_palette_pixelate_1, R.string.pack_group_palettes, Kind.COLOR, "#FF000000", 0, 0, 0, List.of());
        add("censor", "effect_palette_pixelate_2", R.string.pack_field_effect_palette_pixelate_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_pixelate_3", R.string.pack_field_effect_palette_pixelate_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_blur_1", R.string.pack_field_effect_palette_blur_1, R.string.pack_group_palettes, Kind.COLOR, "#FF000000", 0, 0, 0, List.of());
        add("censor", "effect_palette_blur_2", R.string.pack_field_effect_palette_blur_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_blur_3", R.string.pack_field_effect_palette_blur_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_custom_1", R.string.pack_field_effect_palette_custom_1, R.string.pack_group_palettes, Kind.COLOR, "#FF000000", 0, 0, 0, List.of());
        add("censor", "effect_palette_custom_2", R.string.pack_field_effect_palette_custom_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_custom_3", R.string.pack_field_effect_palette_custom_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_static_1", R.string.pack_field_effect_palette_static_1, R.string.pack_group_palettes, Kind.COLOR, "#FF000000", 0, 0, 0, List.of());
        add("censor", "effect_palette_static_2", R.string.pack_field_effect_palette_static_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_static_3", R.string.pack_field_effect_palette_static_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_glitch_1", R.string.pack_field_effect_palette_glitch_1, R.string.pack_group_palettes, Kind.COLOR, "#FF00B4FF", 0, 0, 0, List.of());
        add("censor", "effect_palette_glitch_2", R.string.pack_field_effect_palette_glitch_2, R.string.pack_group_palettes, Kind.COLOR, "#FFFF0050", 0, 0, 0, List.of());
        add("censor", "effect_palette_glitch_3", R.string.pack_field_effect_palette_glitch_3, R.string.pack_group_palettes, Kind.COLOR, "#FFFFFFFF", 0, 0, 0, List.of());
        add("censor", "effect_palette_tape_1", R.string.pack_field_effect_palette_tape_1, R.string.pack_group_palettes, Kind.COLOR, "#FF121216", 0, 0, 0, List.of());
        add("censor", "effect_palette_tape_2", R.string.pack_field_effect_palette_tape_2, R.string.pack_group_palettes, Kind.COLOR, "#FFE53935", 0, 0, 0, List.of());
        add("censor", "effect_palette_tape_3", R.string.pack_field_effect_palette_tape_3, R.string.pack_group_palettes, Kind.COLOR, "#FFF3D33B", 0, 0, 0, List.of());
        add("censor", "effect_palette_error_popup_1", R.string.pack_field_effect_palette_error_popup_1, R.string.pack_group_palettes, Kind.COLOR, "#FFF0F0F0", 0, 0, 0, List.of());
        add("censor", "effect_palette_error_popup_2", R.string.pack_field_effect_palette_error_popup_2, R.string.pack_group_palettes, Kind.COLOR, "#FFD72630", 0, 0, 0, List.of());
        add("censor", "effect_palette_error_popup_3", R.string.pack_field_effect_palette_error_popup_3, R.string.pack_group_palettes, Kind.COLOR, "#FF0078D7", 0, 0, 0, List.of());
        add("limits", "app_timer_per_app_enabled", R.string.pack_field_app_timer_per_app_enabled, R.string.pack_group_allowances, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("limits", "app_timer_per_app_minutes", R.string.pack_field_app_timer_per_app_minutes, R.string.pack_group_allowances, Kind.INTEGER, 30, 1, 1440, 0, List.of());
        add("limits", "app_timer_total_enabled", R.string.pack_field_app_timer_total_enabled, R.string.pack_group_allowances, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("limits", "app_timer_total_minutes", R.string.pack_field_app_timer_total_minutes, R.string.pack_group_allowances, Kind.INTEGER, 120, 1, 1440, 0, List.of());
        add("wallet", "enabled", R.string.pack_field_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "rule_new_detection_enabled", R.string.pack_field_rule_new_detection_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("wallet", "rule_new_detection_cents", R.string.pack_field_rule_new_detection_cents, R.string.pack_group_tribute_rules, Kind.MONEY, 100, 1, 10000, 0, List.of());
        add("wallet", "rule_censored_dwell_enabled", R.string.pack_field_rule_censored_dwell_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "rule_censored_dwell_cents", R.string.pack_field_rule_censored_dwell_cents, R.string.pack_group_tribute_rules, Kind.MONEY, 150, 1, 10000, 0, List.of());
        add("wallet", "rule_censored_tap_enabled", R.string.pack_field_rule_censored_tap_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "rule_censored_tap_cents", R.string.pack_field_rule_censored_tap_cents, R.string.pack_group_tribute_rules, Kind.MONEY, 250, 1, 10000, 0, List.of());
        add("wallet", "rule_watched_app_open_enabled", R.string.pack_field_rule_watched_app_open_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "rule_watched_app_open_cents", R.string.pack_field_rule_watched_app_open_cents, R.string.pack_group_tribute_rules, Kind.MONEY, 50, 1, 10000, 0, List.of());
        add("wallet", "rule_tamper_attempt_enabled", R.string.pack_field_rule_tamper_attempt_enabled, R.string.pack_group_tribute_rules, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "rule_tamper_attempt_cents", R.string.pack_field_rule_tamper_attempt_cents, R.string.pack_group_tribute_rules, Kind.MONEY, 500, 1, 10000, 0, List.of());
        add("wallet", "detection_batch", R.string.pack_field_detection_batch, R.string.pack_group_tribute_rules, Kind.INTEGER, 1, 1, 100, 0, List.of());
        add("wallet", "dwell_seconds", R.string.pack_field_dwell_seconds, R.string.pack_group_tribute_rules, Kind.INTEGER, 10, 3, 60, 0, List.of());
        add("wallet", "tamper_cooldown_minutes", R.string.pack_field_tamper_cooldown_minutes, R.string.pack_group_tribute_rules, Kind.INTEGER, 5, 1, 1440, 0, List.of());
        add("wallet", "daily_cap_cents", R.string.pack_field_daily_cap_cents, R.string.pack_group_spending_caps, Kind.MONEY, 500, 1, 50000, 0, List.of());
        add("wallet", "weekly_cap_cents", R.string.pack_field_weekly_cap_cents, R.string.pack_group_spending_caps, Kind.MONEY, 2000, 1, 200000, 0, List.of());
        add("wallet", "mercy_minutes", R.string.pack_field_mercy_minutes, R.string.pack_group_corrections, Kind.INTEGER, 10, 0, 1440, 0, List.of());
        add("wallet", "paid_pause_enabled", R.string.pack_field_paid_pause_enabled, R.string.pack_group_paid_pause, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("wallet", "paid_pause_price_cents", R.string.pack_field_paid_pause_price_cents, R.string.pack_group_paid_pause, Kind.MONEY, 500, 50, 100000, 0, List.of());
        add("wallet", "paid_pause_duration_minutes", R.string.pack_field_paid_pause_duration_minutes, R.string.pack_group_paid_pause, Kind.INTEGER, 15, 1, 1440, 0, List.of());
        add("subliminal", "subliminal_preset", R.string.pack_field_subliminal_preset, R.string.pack_group_messages, Kind.CHOICE, "normal", 0, 0, 0, List.of("gentle", "normal", "strict", "ultra"));
        add("subliminal", "subliminal_advanced", R.string.pack_field_subliminal_advanced, R.string.pack_group_timing, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("subliminal", "subliminal_opacity_percent", R.string.pack_field_subliminal_opacity_percent, R.string.pack_group_timing, Kind.INTEGER, 5, 1, 15, 0, List.of());
        add("subliminal", "subliminal_visible_ms", R.string.pack_field_subliminal_visible_ms, R.string.pack_group_timing, Kind.LONG, 2000, 800, 4000, 0, List.of());
        add("subliminal", "subliminal_min_interval_ms", R.string.pack_field_subliminal_min_interval_ms, R.string.pack_group_timing, Kind.LONG, 25000, 5000, 300000, 0, List.of());
        add("subliminal", "subliminal_max_interval_ms", R.string.pack_field_subliminal_max_interval_ms, R.string.pack_group_timing, Kind.LONG, 60000, 5000, 300000, 0, List.of());
        add("subliminal", "subliminal_text_size_sp", R.string.pack_field_subliminal_text_size_sp, R.string.pack_group_timing, Kind.INTEGER, 19, 14, 28, 0, List.of());
        add("subliminal", "subliminal_enabled_packs", R.string.pack_field_subliminal_enabled_packs, R.string.pack_group_phrase_packs, Kind.SELECTION, array("obedience", "focus"), 0, 200, 120, List.of("obedience", "focus", "beta", "findom", "custom"));
        add("subliminal", "subliminal_custom_phrases", R.string.pack_field_subliminal_custom_phrases, R.string.pack_group_phrase_packs, Kind.TEXT, "", 0, 0, 24000, List.of());
        add("popup", "popup_storm_enabled", R.string.pack_field_popup_storm_enabled, R.string.pack_group_popup_general, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("popup", "popup_storm_active_preset", R.string.pack_field_popup_storm_active_preset, R.string.pack_group_popup_general, Kind.CHOICE, "MEDIUM", 0, 0, 0, List.of("GENTLE", "MEDIUM", "INTENSE", "OVERLOAD", "CUSTOM"));
        add("popup", "popup_storm_spawn_rate", R.string.pack_field_popup_storm_spawn_rate, R.string.pack_group_popup_general, Kind.DECIMAL, 2, 0, 8, 0, List.of());
        add("popup", "popup_storm_display_duration", R.string.pack_field_popup_storm_display_duration, R.string.pack_group_popup_general, Kind.DECIMAL, 1, 0.2, 8, 0, List.of());
        add("popup", "popup_storm_max_simultaneous", R.string.pack_field_popup_storm_max_simultaneous, R.string.pack_group_popup_general, Kind.INTEGER, 8, 1, 15, 0, List.of());
        add("popup", "popup_storm_position_mode", R.string.pack_field_popup_storm_position_mode, R.string.pack_group_popup_layout, Kind.CHOICE, "random", 0, 0, 0, List.of("random", "center"));
        add("popup", "popup_storm_size_mode", R.string.pack_field_popup_storm_size_mode, R.string.pack_group_popup_layout, Kind.CHOICE, "random", 0, 0, 0, List.of("random", "fixed"));
        add("popup", "popup_storm_min_size_px", R.string.pack_field_popup_storm_min_size_px, R.string.pack_group_popup_layout, Kind.INTEGER, 160, 40, 800, 0, List.of());
        add("popup", "popup_storm_max_size_px", R.string.pack_field_popup_storm_max_size_px, R.string.pack_group_popup_layout, Kind.INTEGER, 380, 40, 1200, 0, List.of());
        add("popup", "popup_storm_fixed_size_px", R.string.pack_field_popup_storm_fixed_size_px, R.string.pack_group_popup_layout, Kind.INTEGER, 260, 40, 1200, 0, List.of());
        add("popup", "popup_storm_random_rotation", R.string.pack_field_popup_storm_random_rotation, R.string.pack_group_popup_layout, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("popup", "popup_storm_rotation_max_deg", R.string.pack_field_popup_storm_rotation_max_deg, R.string.pack_group_popup_layout, Kind.INTEGER, 25, 0, 90, 0, List.of());
        add("popup", "popup_storm_fade_in_ms", R.string.pack_field_popup_storm_fade_in_ms, R.string.pack_group_popup_animation, Kind.INTEGER, 100, 0, 2000, 0, List.of());
        add("popup", "popup_storm_fade_out_ms", R.string.pack_field_popup_storm_fade_out_ms, R.string.pack_group_popup_animation, Kind.INTEGER, 200, 0, 3000, 0, List.of());
        add("popup", "popup_storm_bouncing_enabled", R.string.pack_field_popup_storm_bouncing_enabled, R.string.pack_group_popup_animation, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("popup", "popup_storm_bouncing_speed_pps", R.string.pack_field_popup_storm_bouncing_speed_pps, R.string.pack_group_popup_animation, Kind.INTEGER, 60, 10, 600, 0, List.of());
        add("popup", "popup_storm_burst_enabled", R.string.pack_field_popup_storm_burst_enabled, R.string.pack_group_popup_bursts, Kind.BOOLEAN, Boolean.FALSE, 0, 0, 0, List.of());
        add("popup", "popup_storm_burst_frequency", R.string.pack_field_popup_storm_burst_frequency, R.string.pack_group_popup_bursts, Kind.DECIMAL, 30, 5, 300, 0, List.of());
        add("popup", "popup_storm_burst_duration", R.string.pack_field_popup_storm_burst_duration, R.string.pack_group_popup_bursts, Kind.DECIMAL, 4, 1, 30, 0, List.of());
        add("popup", "popup_storm_burst_multiplier", R.string.pack_field_popup_storm_burst_multiplier, R.string.pack_group_popup_bursts, Kind.DECIMAL, 3, 1, 6, 0, List.of());
        add("popup", "popup_storm_denial_chance", R.string.pack_field_popup_storm_denial_chance, R.string.pack_group_popup_denial, Kind.INTEGER, 0, 0, 100, 0, List.of());
        add("popup", "popup_storm_denial_style", R.string.pack_field_popup_storm_denial_style, R.string.pack_group_popup_denial, Kind.CHOICE, "blur", 0, 0, 0, List.of("blur", "pixelate", "mixed"));
        add("popup", "popup_storm_denial_intensity", R.string.pack_field_popup_storm_denial_intensity, R.string.pack_group_popup_denial, Kind.INTEGER, 50, 0, 100, 0, List.of());
        add("popup", "popup_storm_denial_caption", R.string.pack_field_popup_storm_denial_caption, R.string.pack_group_popup_denial, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("popup", "popup_storm_denial_caption_text", R.string.pack_field_popup_storm_denial_caption_text, R.string.pack_group_popup_denial, Kind.TEXT, "NO", 0, 0, 32, List.of());
        add("popup", "popup_storm_click_dismisses_all", R.string.pack_field_popup_storm_click_dismisses_all, R.string.pack_group_popup_interaction, Kind.BOOLEAN, Boolean.TRUE, 0, 0, 0, List.of());
        add("popup", "popup_storm_detection_mode", R.string.pack_field_popup_storm_detection_mode, R.string.pack_group_popup_interaction, Kind.CHOICE, "off", 0, 0, 0, List.of("off", "cover", "avoid"));
        add("popup", "popup_storm_avoid_padding_px", R.string.pack_field_popup_storm_avoid_padding_px, R.string.pack_group_popup_interaction, Kind.INTEGER, 80, 0, 400, 0, List.of());
    }

    private PackSettingCatalog() { }

    private static void add(String section, String key, int label, int group, Kind kind,
            Object defaultValue, double minimum, double maximum, int maximumLength, List<String> choices) {
        Field field = new Field(section, key, label, group, kind, defaultValue,
                minimum, maximum, maximumLength, choices);
        if (FIELDS.put(key, field) != null) throw new IllegalStateException("Duplicate pack setting");
        SECTIONS.computeIfAbsent(section, unused -> new ArrayList<>()).add(field);
    }

    private static JSONArray array(String... values) {
        return new JSONArray(java.util.Arrays.asList(values));
    }

    public static List<Field> fields(String section) {
        return Collections.unmodifiableList(SECTIONS.getOrDefault(section, List.of()));
    }

    public static Field field(String section, String key) {
        Field field = FIELDS.get(key);
        return field != null && field.section.equals(section) ? field : null;
    }

    public static Set<String> keys(String section) {
        Set<String> result = new LinkedHashSet<>();
        for (Field field : fields(section)) result.add(field.key);
        return Collections.unmodifiableSet(result);
    }

    public static JSONObject defaults(String section) {
        JSONObject result = new JSONObject();
        for (Field field : fields(section)) put(result, field.key, field.defaultValue());
        return result;
    }

    /** Missing values inherit defaults for the editor; unknown values are never copied. */
    public static JSONObject complete(String section, JSONObject values) {
        JSONObject result = defaults(section);
        JSONObject clean = sanitize(section, values);
        clean.keys().forEachRemaining(key -> put(result, key, clean.opt(key)));
        if (SubHubPackSchema.CENSOR.equals(section) && !clean.has("detection_confidence_percent")) {
            String preset = result.optString("detection_quality", "medium");
            put(result, "detection_confidence_percent", switch (preset) {
                case "low" -> 30; case "high" -> 18; default -> 25;
            });
        }
        return result;
    }

    public static JSONObject sanitize(String section, JSONObject values) {
        JSONObject result = new JSONObject();
        if (values == null) return result;
        values.keys().forEachRemaining(key -> {
            Field field = field(section, key);
            if (field == null || SubHubPackSchema.isSecretOrRuntimeKey(key)) return;
            Object value = field.normalize(values.opt(key));
            put(result, key, value);
        });
        validateRelationships(result);
        return result;
    }

    public static void validateRelationships(JSONObject values) {
        ordered(values, "subliminal_min_interval_ms", "subliminal_max_interval_ms");
        ordered(values, "popup_storm_min_size_px", "popup_storm_max_size_px");
        ordered(values, "daily_cap_cents", "weekly_cap_cents");
        if (values.has("daily_cap_cents")) {
            int daily = values.optInt("daily_cap_cents");
            for (String rule : List.of("new_detection", "censored_dwell", "censored_tap",
                    "watched_app_open", "tamper_attempt")) {
                if (Boolean.TRUE.equals(values.opt("rule_" + rule + "_enabled"))
                        && values.has("rule_" + rule + "_cents")
                        && values.optInt("rule_" + rule + "_cents") > daily) {
                    throw new ValidationException("daily_cap_cents", Problem.SPENDING_CAP);
                }
            }
        }
        if (values.has("subliminal_custom_phrases")) {
            String text = values.optString("subliminal_custom_phrases");
            String[] lines = text.split("\\R", -1);
            if (lines.length > 200) throw new ValidationException("subliminal_custom_phrases", Problem.MESSAGE_LIMIT);
            for (String line : lines) if (line.length() > 120) {
                throw new ValidationException("subliminal_custom_phrases", Problem.MESSAGE_LIMIT);
            }
        }
    }

    private static void ordered(JSONObject values, String low, String high) {
        if (values.has(low) && values.has(high)
                && values.optDouble(low) > values.optDouble(high)) {
            throw new ValidationException(high, Problem.MINIMUM_MAXIMUM);
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid pack setting", invalid); }
    }

    public static int choiceLabel(Field field, String value) {
        return switch (field.key + ":" + value) {
            case "detection_quality:low" -> R.string.pack_choice_detection_preset_low;
            case "detection_quality:medium" -> R.string.pack_choice_detection_preset_medium;
            case "detection_quality:high" -> R.string.pack_choice_detection_preset_high;
            case "capture_method:app_mode" -> R.string.pack_choice_capture_method_app_mode;
            case "capture_method:screen_recording" -> R.string.pack_choice_capture_method_screen_recording;
            case "censor_coverage:detected_areas" -> R.string.pack_choice_censor_coverage_detected_areas;
            case "censor_coverage:whole_person" -> R.string.pack_choice_censor_coverage_whole_person;
            case "enabled_categories:genitals_female" -> R.string.pack_choice_enabled_categories_genitals_female;
            case "enabled_categories:genitals_male" -> R.string.pack_choice_enabled_categories_genitals_male;
            case "enabled_categories:breasts" -> R.string.pack_choice_enabled_categories_breasts;
            case "enabled_categories:buttocks" -> R.string.pack_choice_enabled_categories_buttocks;
            case "enabled_categories:anus" -> R.string.pack_choice_enabled_categories_anus;
            case "enabled_categories:genitals_covered" -> R.string.pack_choice_enabled_categories_genitals_covered;
            case "enabled_categories:breasts_covered" -> R.string.pack_choice_enabled_categories_breasts_covered;
            case "enabled_categories:buttocks_covered" -> R.string.pack_choice_enabled_categories_buttocks_covered;
            case "enabled_categories:anus_covered" -> R.string.pack_choice_enabled_categories_anus_covered;
            case "enabled_categories:male_chest" -> R.string.pack_choice_enabled_categories_male_chest;
            case "enabled_categories:belly" -> R.string.pack_choice_enabled_categories_belly;
            case "enabled_categories:belly_covered" -> R.string.pack_choice_enabled_categories_belly_covered;
            case "enabled_categories:feet" -> R.string.pack_choice_enabled_categories_feet;
            case "enabled_categories:feet_covered" -> R.string.pack_choice_enabled_categories_feet_covered;
            case "enabled_categories:armpits" -> R.string.pack_choice_enabled_categories_armpits;
            case "enabled_categories:armpits_covered" -> R.string.pack_choice_enabled_categories_armpits_covered;
            case "enabled_categories:face" -> R.string.pack_choice_enabled_categories_face;
            case "enabled_categories:face_female" -> R.string.pack_choice_enabled_categories_face_female;
            case "enabled_categories:face_male" -> R.string.pack_choice_enabled_categories_face_male;
            case "text_smut_sensitivity:0" -> R.string.pack_choice_text_smut_sensitivity_0;
            case "text_smut_sensitivity:1" -> R.string.pack_choice_text_smut_sensitivity_1;
            case "text_smut_sensitivity:2" -> R.string.pack_choice_text_smut_sensitivity_2;
            case "text_smut_categories:explicit_language" -> R.string.pack_choice_text_smut_categories_explicit_language;
            case "text_smut_categories:fetish_context" -> R.string.pack_choice_text_smut_categories_fetish_context;
            case "text_smut_categories:sexual_solicitation" -> R.string.pack_choice_text_smut_categories_sexual_solicitation;
            case "censor_type:box" -> R.string.pack_choice_censor_type_box;
            case "censor_type:pixelate" -> R.string.pack_choice_censor_type_pixelate;
            case "censor_type:blur" -> R.string.pack_choice_censor_type_blur;
            case "censor_type:custom" -> R.string.pack_choice_censor_type_custom;
            case "censor_type:static" -> R.string.pack_choice_censor_type_static;
            case "censor_type:glitch" -> R.string.pack_choice_censor_type_glitch;
            case "censor_type:tape" -> R.string.pack_choice_censor_type_tape;
            case "censor_type:error_popup" -> R.string.pack_choice_censor_type_error_popup;
            case "border_effect:classic" -> R.string.pack_choice_border_effect_classic;
            case "border_effect:glow" -> R.string.pack_choice_border_effect_glow;
            case "border_effect:gradient" -> R.string.pack_choice_border_effect_gradient;
            case "border_effect:rainbow" -> R.string.pack_choice_border_effect_rainbow;
            case "enabled_phrase_categories:short" -> R.string.pack_choice_enabled_phrase_categories_short;
            case "enabled_phrase_categories:denial" -> R.string.pack_choice_enabled_phrase_categories_denial;
            case "enabled_phrase_categories:humiliation" -> R.string.pack_choice_enabled_phrase_categories_humiliation;
            case "enabled_phrase_categories:edge" -> R.string.pack_choice_enabled_phrase_categories_edge;
            case "enabled_phrase_categories:findom" -> R.string.pack_choice_enabled_phrase_categories_findom;
            case "enabled_phrase_categories:ntr" -> R.string.phrase_ntr;
            case "enabled_phrase_categories:gooner" -> R.string.pack_choice_enabled_phrase_categories_gooner;
            case "reverse_cutout_shape:rectangle" -> R.string.pack_choice_reverse_cutout_shape_rectangle;
            case "reverse_cutout_shape:rounded" -> R.string.pack_choice_reverse_cutout_shape_rounded;
            case "reverse_cutout_shape:ellipse" -> R.string.pack_choice_reverse_cutout_shape_ellipse;
            case "subliminal_preset:gentle" -> R.string.pack_choice_subliminal_preset_gentle;
            case "subliminal_preset:normal" -> R.string.pack_choice_subliminal_preset_normal;
            case "subliminal_preset:strict" -> R.string.pack_choice_subliminal_preset_strict;
            case "subliminal_preset:ultra" -> R.string.pack_choice_subliminal_preset_ultra;
            case "subliminal_enabled_packs:obedience" -> R.string.pack_choice_subliminal_enabled_packs_obedience;
            case "subliminal_enabled_packs:focus" -> R.string.pack_choice_subliminal_enabled_packs_focus;
            case "subliminal_enabled_packs:beta" -> R.string.pack_choice_subliminal_enabled_packs_beta;
            case "subliminal_enabled_packs:findom" -> R.string.pack_choice_subliminal_enabled_packs_findom;
            case "subliminal_enabled_packs:custom" -> R.string.pack_choice_subliminal_enabled_packs_custom;
            case "popup_storm_active_preset:GENTLE" -> R.string.pack_choice_popup_storm_active_preset_gentle;
            case "popup_storm_active_preset:MEDIUM" -> R.string.pack_choice_popup_storm_active_preset_medium;
            case "popup_storm_active_preset:INTENSE" -> R.string.pack_choice_popup_storm_active_preset_intense;
            case "popup_storm_active_preset:OVERLOAD" -> R.string.pack_choice_popup_storm_active_preset_overload;
            case "popup_storm_active_preset:CUSTOM" -> R.string.pack_choice_popup_storm_active_preset_custom;
            case "popup_storm_position_mode:random" -> R.string.pack_choice_popup_storm_position_mode_random;
            case "popup_storm_position_mode:center" -> R.string.pack_choice_popup_storm_position_mode_center;
            case "popup_storm_size_mode:random" -> R.string.pack_choice_popup_storm_size_mode_random;
            case "popup_storm_size_mode:fixed" -> R.string.pack_choice_popup_storm_size_mode_fixed;
            case "popup_storm_denial_style:blur" -> R.string.pack_choice_popup_storm_denial_style_blur;
            case "popup_storm_denial_style:pixelate" -> R.string.pack_choice_popup_storm_denial_style_pixelate;
            case "popup_storm_denial_style:mixed" -> R.string.pack_choice_popup_storm_denial_style_mixed;
            case "popup_storm_detection_mode:off" -> R.string.pack_choice_popup_storm_detection_mode_off;
            case "popup_storm_detection_mode:cover" -> R.string.pack_choice_popup_storm_detection_mode_cover;
            case "popup_storm_detection_mode:avoid" -> R.string.pack_choice_popup_storm_detection_mode_avoid;
            case "app_mode_kind:always" -> R.string.pack_choice_app_mode_kind_always;
            case "app_mode_kind:selected" -> R.string.pack_choice_app_mode_kind_selected;
            default -> throw new IllegalArgumentException("Unknown pack option");
        };
    }

    public static final class Field {
        public final String section, key;
        public final int label, group;
        public final Kind kind;
        public final double minimum, maximum;
        public final int maximumLength;
        public final List<String> choices;
        private final Object fallback;

        private Field(String section, String key, int label, int group, Kind kind, Object fallback,
                double minimum, double maximum, int maximumLength, List<String> choices) {
            this.section = section; this.key = key; this.label = label; this.group = group;
            this.kind = kind; this.fallback = fallback; this.minimum = minimum; this.maximum = maximum;
            this.maximumLength = maximumLength;
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
        }

        public Object defaultValue() {
            if (fallback instanceof JSONArray) {
                try { return new JSONArray(fallback.toString()); }
                catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
            }
            return normalize(fallback);
        }

        public Object normalize(Object value) {
            switch (kind) {
                case BOOLEAN:
                    if (value instanceof Boolean) return value;
                    break;
                case INTEGER: case LONG: case MONEY: case DECIMAL: case RATIO:
                    if (!(value instanceof Number)) break;
                    double number = ((Number) value).doubleValue();
                    if (!Double.isFinite(number) || number < minimum || number > maximum) break;
                    if (kind == Kind.DECIMAL || kind == Kind.RATIO) return ((Number) value).floatValue();
                    if (number != Math.rint(number)) break;
                    long whole = ((Number) value).longValue();
                    if (!choices.isEmpty() && !choices.contains(Long.toString(whole))) break;
                    if (kind == Kind.LONG) return whole;
                    return Math.toIntExact(whole);
                case COLOR:
                    if (value instanceof String && ((String) value).matches("#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")) {
                        return ((String) value).toUpperCase(java.util.Locale.ROOT);
                    }
                    break;
                case CHOICE:
                    if (value instanceof String && choices.contains(value)) return value;
                    break;
                case TEXT:
                    if (value instanceof String && ((String) value).length() <= maximumLength
                            && !((String) value).matches("(?s).*[\\p{Cntrl}&&[^\\r\\n\\t]].*")) return value;
                    break;
                case SELECTION:
                    JSONArray array = value instanceof Set<?> ? new JSONArray((Set<?>) value)
                            : value instanceof JSONArray ? (JSONArray) value : null;
                    if (array == null || array.length() > 200) break;
                    Set<String> selected = new LinkedHashSet<>();
                    for (int index = 0; index < array.length(); index++) {
                        Object item = array.opt(index);
                        if (!(item instanceof String) || ((String) item).length() > maximumLength
                                || (!choices.isEmpty() && !choices.contains(item))) {
                            throw invalid();
                        }
                        String text = (String) item;
                        if (text.matches("(?s).*[\\p{Cntrl}].*")) throw invalid();
                        if (!text.isEmpty()) selected.add(text);
                    }
                    return new JSONArray(selected);
            }
            throw invalid();
        }

        public Object parseText(String text) {
            if (kind == Kind.TEXT || kind == Kind.COLOR || kind == Kind.CHOICE) return normalize(text);
            try {
                BigDecimal amount = new BigDecimal(text.trim().replace(',', '.'));
                if (kind == Kind.MONEY) return normalize(amount.movePointRight(2)
                        .setScale(0, RoundingMode.UNNECESSARY).intValueExact());
                if (kind == Kind.RATIO) return normalize(amount.movePointLeft(2).floatValue());
                if (kind == Kind.DECIMAL) return normalize(amount.floatValue());
                if (kind == Kind.LONG) return normalize(amount.longValueExact());
                return normalize(amount.intValueExact());
            } catch (ArithmeticException | NumberFormatException invalid) { throw invalid(); }
        }

        public String displayText(Object value) {
            Object normalized = normalize(value);
            if (kind == Kind.MONEY) return BigDecimal.valueOf(((Number) normalized).longValue(), 2).toPlainString();
            if (kind == Kind.RATIO) return new BigDecimal(normalized.toString()).movePointRight(2)
                    .stripTrailingZeros().toPlainString();
            return String.valueOf(normalized);
        }

        private IllegalArgumentException invalid() {
            return new ValidationException(key, Problem.VALUE);
        }
    }
}
