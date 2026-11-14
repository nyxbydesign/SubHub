package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.subliminal.SubliminalSettings;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only, bounded summaries. Remote values never fall back to this device's settings. */
public final class AtmosphereContractDetails {
    public final String summary;
    public final String details;

    private AtmosphereContractDetails(Context context, String whispersState, String popupState,
            String whispers, String popup, String gagState, boolean hasDetails) {
        summary = context.getString(R.string.contract_atmosphere_states, whispersState, popupState, gagState);
        details = hasDetails ? context.getString(R.string.arrangement_detail_line,
                context.getString(R.string.overview_whispers), whispers) + "\n\n"
                + context.getString(R.string.arrangement_detail_line,
                        context.getString(R.string.popup_title), popup) + "\n\n"
                + context.getString(R.string.arrangement_detail_line,
                        context.getString(R.string.gag_title), gagState) : "";
    }

    public static AtmosphereContractDetails local(Context context) {
        return local(context, false);
    }

    static AtmosphereContractDetails subContract(Context context) {
        return local(context, true);
    }

    private static AtmosphereContractDetails local(Context context, boolean hideDisabled) {
        boolean whispersAllowed = SubFeaturePolicy.allowed(context, "subliminal");
        boolean popupAllowed = SubFeaturePolicy.allowed(context, "popup");
        boolean whispersEnabled = hideDisabled
                ? context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .getBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, false)
                : new FeatureModuleManager(context).isSubliminalEnabled();
        PopupStormSettings popup = PopupStormSettings.load(context);
        String whispersState = state(context, whispersEnabled, whispersAllowed, true);
        String popupState = state(context, popup.isEnabled(), popupAllowed, true);
        boolean showWhispers = !hideDisabled || whispersEnabled && whispersAllowed;
        boolean showPopup = !hideDisabled || popup.isEnabled() && popupAllowed;
        String whispersCopy = whispersState;
        if (showWhispers) {
            SubliminalSettings whispers = new SubliminalSettingsRepository(context).load();
            whispersCopy = whispers(context, whispersState, new ArrayList<>(whispers.getEnabledPacks()),
                    whispers.getPreset().name(), whispers.isAdvanced());
        }
        JSONObject gag;
        try { gag = RemoteFeature.get(context).appliedGag(); }
        catch (RuntimeException unavailable) { gag = null; }
        return new AtmosphereContractDetails(context, whispersState, popupState,
                whispersCopy, showPopup
                        ? popup(context, popupState, popup.getPositionMode(), popup.getDetectionMode(),
                        popup.isBurstEnabled(), popup.isBouncing(), popup.isRandomRotation(),
                        popup.getDenialChance() > 0, popup.getDenialStyle()) : popupState,
                gagStatus(context, gag), showWhispers || showPopup);
    }

    /** Sections have already passed RemoteConfiguration validation; consent has its own freshness. */
    public static AtmosphereContractDetails remote(Context context, JSONObject sections,
            Map<String, Boolean> consent, PersonSnapshot.Freshness freshness) throws JSONException {
        JSONObject whispers = sections.getJSONObject("subliminal"), popup = sections.getJSONObject("popup");
        boolean current = freshness == PersonSnapshot.Freshness.CURRENT;
        String whispersState = state(context, sections.getJSONObject("modules").getBoolean("module_subliminal_enabled"),
                consent.get("subliminal"), current);
        String popupState = state(context, popup.getBoolean(PopupStormSettings.K_ENABLED), consent.get("popup"), current);
        JSONArray selected = whispers.getJSONArray(SubliminalSettingsRepository.KEY_PACKS);
        List<String> packs = new ArrayList<>();
        for (int index = 0; index < selected.length(); index++) packs.add(selected.getString(index));
        return new AtmosphereContractDetails(context, whispersState, popupState,
                whispers(context, whispersState, packs, whispers.getString(SubliminalSettingsRepository.KEY_PRESET),
                        whispers.getBoolean(SubliminalSettingsRepository.KEY_ADVANCED))
                        + consentNote(context, consent.get("subliminal"), freshness),
                popup(context, popupState, popup.getString(PopupStormSettings.K_POSITION_MODE),
                        popup.getString(PopupStormSettings.K_DETECTION_MODE), popup.getBoolean(PopupStormSettings.K_BURST_ENABLED),
                        popup.getBoolean(PopupStormSettings.K_BOUNCING), popup.getBoolean(PopupStormSettings.K_RANDOM_ROT),
                        popup.getInt(PopupStormSettings.K_DENIAL_CHANCE) > 0, popup.getString(PopupStormSettings.K_DENIAL_STYLE))
                        + consentNote(context, consent.get("popup"), freshness),
                gagStatus(context, sections.optJSONObject(GagContractFields.SECTION)), true);
    }

    static AtmosphereContractDetails internal(Context context, Map<String, Map<String, Object>> configuration) {
        try {
            JSONObject sections = new JSONObject()
                    .put("modules", new JSONObject(configuration.get("modules")))
                    .put("subliminal", new JSONObject(configuration.get("subliminal")))
                    .put("popup", new JSONObject(configuration.get("popup")));
            Map<String, Object> gag = configuration.get(GagContractFields.SECTION);
            sections.put(GagContractFields.SECTION, gag == null ? GagContractFields.defaults() : new JSONObject(gag));
            return remote(context, sections, Map.of(), PersonSnapshot.Freshness.UNKNOWN);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Incomplete internal Atmosphere configuration", invalid);
        }
    }

    private static String gagStatus(Context context, JSONObject gag) {
        return context.getString(gag == null ? R.string.gag_status_unknown
                : gag.optBoolean("enabled") ? R.string.arrangement_configured_on : R.string.control_state_off);
    }

    private static String state(Context context, boolean enabled, Boolean allowed, boolean current) {
        if (current && Boolean.FALSE.equals(allowed)) return context.getString(R.string.sub_feature_disabled);
        if (!enabled) return context.getString(R.string.popup_off);
        return context.getString(current && Boolean.TRUE.equals(allowed)
                ? R.string.control_state_on : R.string.arrangement_configured_on);
    }

    private static String consentNote(Context context, Boolean allowed, PersonSnapshot.Freshness freshness) {
        if (freshness == PersonSnapshot.Freshness.CURRENT && allowed != null) return "";
        if (freshness == PersonSnapshot.Freshness.STALE && allowed != null)
            return "\n" + context.getString(allowed ? R.string.arrangement_consent_last_allowed
                    : R.string.arrangement_consent_last_disabled);
        return "\n" + context.getString(R.string.arrangement_consent_unknown);
    }

    private static String whispers(Context context, String state, List<String> packs, String preset, boolean advanced) {
        List<String> labels = new ArrayList<>();
        for (String pack : packs) {
            int label = switch (pack) {
                case "obedience" -> R.string.subliminal_pack_summary_obedience;
                case "focus" -> R.string.subliminal_pack_summary_focus;
                case "beta" -> R.string.subliminal_pack_summary_beta;
                case "findom" -> R.string.subliminal_pack_summary_findom;
                case "custom" -> R.string.subliminal_pack_summary_custom;
                default -> 0;
            };
            if (label != 0) labels.add(context.getString(label));
        }
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        int intensity = advanced ? R.string.arrangement_custom_intensity : switch (preset.toLowerCase(java.util.Locale.ROOT)) {
            case "gentle" -> R.string.subliminal_preset_gentle;
            case "strict" -> R.string.subliminal_preset_strict;
            case "ultra" -> R.string.subliminal_preset_ultra;
            default -> R.string.subliminal_preset_normal;
        };
        return state + "\n" + context.getString(R.string.arrangement_whisper_categories,
                labels.isEmpty() ? context.getString(R.string.arrangement_none_selected) : String.join(", ", labels))
                + "\n" + context.getString(R.string.arrangement_whisper_intensity, context.getString(intensity));
    }

    private static String popup(Context context, String state, String position, String detection,
            boolean burst, boolean bouncing, boolean rotation, boolean tease, String teaseStyle) {
        String placement = context.getString("center".equals(position) ? R.string.popup_center : R.string.popup_random);
        if (!"off".equals(detection)) placement += " · " + context.getString("cover".equals(detection)
                ? R.string.popup_cover : R.string.popup_avoid);
        List<String> effects = new ArrayList<>();
        if (burst) effects.add(context.getString(R.string.arrangement_popup_bursts));
        if (bouncing) effects.add(context.getString(R.string.popup_bouncing));
        if (rotation) effects.add(context.getString(R.string.popup_random_rotation));
        if (tease) effects.add(context.getString(R.string.arrangement_popup_tease,
                context.getString("pixelate".equals(teaseStyle) ? R.string.popup_pixelate
                        : "mixed".equals(teaseStyle) ? R.string.popup_mixed : R.string.popup_blur)));
        return state + "\n" + context.getString(R.string.arrangement_popup_placement, placement)
                + "\n" + context.getString(R.string.arrangement_popup_effects,
                        effects.isEmpty() ? context.getString(R.string.arrangement_none_selected) : String.join(", ", effects));
    }
}
