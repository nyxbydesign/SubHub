package com.subhub.app.people;

import android.content.Context;
import android.content.ContextWrapper;
import com.subhub.app.R;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.overlay.CensorPhrases;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceSnapshot;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.subliminal.SubliminalSettings;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Canonical read-only configuration descriptions, independent of an Activity. */
public final class LocalContractDetails extends ContextWrapper {
    public LocalContractDetails(Context context) { super(context.getApplicationContext()); }

    public String forSection(String section) {
        List<ArrangementDetail> rows;
        switch (section) {
            case "censor": rows = censorArrangementRows(); break;
            case "limits": rows = limitsArrangementDetails(); break;
            case "wallet": rows = walletArrangementDetails(); break;
            case "atmosphere": rows = atmosphereArrangementDetails(); break;
            default: throw new IllegalArgumentException("Unknown contract section");
        }
        List<String> values = new ArrayList<>();
        for (ArrangementDetail row : rows) values.add(getString(
                R.string.arrangement_detail_line, getString(row.label), row.value));
        return String.join("\n\n", values);
    }

    private List<ArrangementDetail> censorArrangementRows() {
        SettingsRepository repository = new SettingsRepository(this);
        DetectorConfig detector = repository.loadDetectorConfig();
        TextSmutConfig text = repository.loadTextSmutConfig();
        CensorAppearance appearance = repository.loadAppearance();
        List<ArrangementDetail> lines = new ArrayList<>();
        lines.add(
                detailLine(
                        R.string.arrangement_censor_look,
                        getString(
                                R.string.arrangement_censor_look_value,
                                friendlyEffectName(appearance.getType()),
                                appearance.isShowBorder()
                                        ? friendlyBorderName(appearance.getBorderEffect())
                                        : getString(R.string.arrangement_no_border))));
        lines.add(
                detailLine(
                        R.string.arrangement_censor_quality,
                        friendlyDetectionPreset(repository.loadDetectionPreset())));
        lines.add(
                detailLine(
                        R.string.arrangement_censor_capture,
                        getString(
                                repository.loadCaptureMethod() == CaptureMethod.APP_MODE
                                        ? R.string.capture_method_app_mode
                                        : R.string.capture_method_recording)));
        lines.add(
                detailLine(
                        R.string.arrangement_censor_images,
                        friendlyCategories(detector.getEnabledCategories())));
        lines.add(
                detailLine(
                        R.string.arrangement_censor_text,
                        text.isEnabled()
                                ? friendlyTextFilter(text)
                                : getString(R.string.popup_off)));
        Set<String> phraseGroups =
                repository
                        .preferences()
                        .getStringSet(
                                SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES,
                                CensorPhrases.DEFAULT_ENABLED);
        lines.add(
                detailLine(
                        R.string.arrangement_censor_phrases,
                        appearance.isShowText()
                                ? friendlyPhraseNames(phraseGroups)
                                : getString(R.string.popup_off)));
        return lines;
    }

    private List<ArrangementDetail> limitsArrangementDetails() {
        AppTimerManager timers = new AppTimerManager(this);
        AppTimerManager.Settings settings = timers.loadSettings();
        List<ArrangementDetail> lines = new ArrayList<>();
        lines.add(
                detailLine(
                        R.string.arrangement_limits_shared,
                        settings.totalEnabled
                                ? getString(R.string.arrangement_minutes, settings.totalMinutes)
                                : getString(R.string.popup_off)));
        lines.add(detailLine(R.string.arrangement_limits_individual,
                getString(settings.perAppEnabled ? R.string.control_state_on : R.string.control_state_off)
                        + "\n" + appAllowances(timers)));
        return lines;
    }

    private String appAllowances(AppTimerManager timers) {
        List<String> apps = new ArrayList<>();
        android.content.pm.PackageManager packages = getPackageManager();
        for (String name : new AppModeManager(this).getIncludedPackages()) {
            String label = name;
            try { label = packages.getApplicationLabel(packages.getApplicationInfo(name, 0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { /* Keep saved assignments. */ }
            apps.add(getString(R.string.arrangement_app_allowance, label, timers.allowanceMinutes(name)));
        }
        Collections.sort(apps, String.CASE_INSENSITIVE_ORDER);
        return apps.isEmpty() ? getString(R.string.arrangement_no_apps) : String.join("\n", apps);
    }

    private List<ArrangementDetail> walletArrangementDetails() {
        PenanceManager wallet = new PenanceManager(this);
        PenanceSnapshot snapshot = wallet.snapshot(System.currentTimeMillis());
        List<String> rules = new ArrayList<>();
        for (PenanceInfraction infraction : PenanceInfraction.values()) {
            if (infraction == PenanceInfraction.PAID_PAUSE
                    || !wallet.isInfractionEnabled(infraction)) continue;
            String trigger = friendlyInfraction(infraction);
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                trigger =
                        getString(
                                R.string.arrangement_detection_every,
                                trigger,
                                wallet.getDetectionBatch());
            } else if (infraction == PenanceInfraction.CENSORED_DWELL) {
                trigger =
                        getString(
                                R.string.arrangement_linger_after,
                                trigger,
                                wallet.getDwellSeconds());
            }
            rules.add(
                    getString(
                            R.string.arrangement_tribute_rule,
                            trigger,
                            wallet.money(wallet.getInfractionCents(infraction))));
        }
        List<ArrangementDetail> lines = new ArrayList<>();
        lines.add(
                detailLine(
                        R.string.arrangement_wallet_balance, wallet.money(snapshot.getDueCents())));
        lines.add(
                detailLine(
                        R.string.arrangement_wallet_rules,
                        rules.isEmpty()
                                ? getString(R.string.arrangement_none_selected)
                                : android.text.TextUtils.join("\n", rules)));
        lines.add(
                detailLine(
                        R.string.arrangement_wallet_caps,
                        getString(
                                R.string.arrangement_wallet_cap_values,
                                wallet.money(wallet.getDailyCapCents()),
                                wallet.money(wallet.getWeeklyCapCents()))));
        return lines;
    }

    private List<ArrangementDetail> subliminalArrangementDetails() {
        SubliminalSettings settings = new SubliminalSettingsRepository(this).load();
        List<String> voices = new ArrayList<>();
        for (String pack : settings.getEnabledPacks()) voices.add(friendlySubliminalPack(pack));
        Collections.sort(voices, String.CASE_INSENSITIVE_ORDER);
        List<ArrangementDetail> lines = new ArrayList<>();
        lines.add(
                detailLine(
                        R.string.arrangement_subliminal_voice,
                        voices.isEmpty()
                                ? getString(R.string.arrangement_none_selected)
                                : android.text.TextUtils.join(", ", voices)));
        lines.add(
                detailLine(
                        R.string.arrangement_subliminal_intensity,
                        friendlyPreset(settings.getPreset())));
        return lines;
    }

    private List<ArrangementDetail> atmosphereArrangementDetails() {
        FeatureModuleManager modules = new FeatureModuleManager(this);
        PopupStormSettings popup = PopupStormSettings.load(this);
        SubliminalSettings whispers = new SubliminalSettingsRepository(this).load();
        List<ArrangementDetail> lines = new ArrayList<>();
        com.subhub.app.pack.SubHubPackManager packs = new com.subhub.app.pack.SubHubPackManager(this);
        com.subhub.app.pack.SubHubPack pack = packs.activePackOverview();
        lines.add(detailLine(R.string.arrangement_active_pack, pack == null
                ? getString(packs.activePackId() == null ? R.string.arrangement_none_selected : R.string.studio_active)
                : getString(R.string.arrangement_pack_version, pack.getName(), pack.getPackVersion())));
        boolean pin = com.subhub.app.security.ControllerPinManager.isConfigured(this);
        boolean auth = new com.subhub.app.security.ControllerAuthenticator(this).isPaired();
        lines.add(detailLine(R.string.keyholder_home_title, getString(pin && auth
                ? R.string.keyholder_status_both : pin ? R.string.keyholder_status_pin
                : auth ? R.string.keyholder_status_auth : R.string.keyholder_status_none)));
        if (com.subhub.app.commitment.CommitmentManager.isActive(this)) lines.add(detailLine(
                R.string.keyholder_your_lock, com.subhub.app.commitment.CommitmentManager.countdownLabel(this)));
        lines.add(
                detailLine(
                        R.string.atmosphere_whispers_title,
                        modules.isSubliminalEnabled()
                                ? getString(
                                        R.string.arrangement_atmosphere_enabled,
                                        friendlyPreset(whispers.getPreset()))
                                : getString(R.string.popup_off)));
        lines.add(
                detailLine(
                        R.string.popup_title,
                        popup.isEnabled()
                                ? getString(R.string.atmosphere_state_on)
                                : getString(R.string.popup_off)));
        return lines;
    }

    private static final class ArrangementDetail {
        final int label;
        final String value;
        ArrangementDetail(int label, String value) { this.label = label; this.value = value; }
    }

    private ArrangementDetail detailLine(int label, String value) {
        return new ArrangementDetail(label, value);
    }

    String censorArrangementDetails() {
        List<String> lines = new ArrayList<>();
        for (ArrangementDetail row : censorArrangementRows())
            lines.add(getString(R.string.arrangement_detail_line, getString(row.label), row.value));
        return android.text.TextUtils.join("\n\n", lines);
    }

    private String friendlyCategories(Set<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return getString(R.string.arrangement_none_selected);
        }
        List<String> labels = new ArrayList<>();
        addCategoryLabel(labels, categories, "genitals_female", R.string.category_genitals_female);
        addCategoryLabel(labels, categories, "genitals_male", R.string.category_genitals_male);
        addCategoryLabel(labels, categories, "breasts", R.string.category_breasts);
        if (com.subhub.app.settings.DetectionCategorySelection.isSelected(categories, "buttocks")) {
            labels.add(getString(R.string.category_buttocks));
        }
        addCategoryLabel(labels, categories, "face", R.string.category_faces);
        addCategoryLabel(labels, categories, "male_chest", R.string.category_male_chest);
        addCategoryLabel(labels, categories, "belly", R.string.category_belly);
        addCategoryLabel(labels, categories, "feet", R.string.category_feet);
        addCategoryLabel(labels, categories, "armpits", R.string.category_armpits);
        if (containsCoveredCategory(categories)) labels.add(getString(R.string.category_covered));
        return android.text.TextUtils.join(", ", labels);
    }

    private void addCategoryLabel(
            List<String> labels, Set<String> categories, String category, int label) {
        if (categories.contains(category)) labels.add(getString(label));
    }

    private static boolean containsCoveredCategory(Set<String> categories) {
        for (String category : categories) {
            if (category != null && category.endsWith("_covered")) return true;
        }
        return false;
    }

    private String friendlyPhraseNames(Set<String> values) {
        if (values == null || values.isEmpty())
            return getString(R.string.arrangement_none_selected);
        List<String> labels = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            switch (value) {
                case "short":
                    labels.add(getString(R.string.phrase_short));
                    break;
                case "denial":
                    labels.add(getString(R.string.phrase_denial));
                    break;
                case "humiliation":
                    labels.add(getString(R.string.phrase_humiliation));
                    break;
                case "edge":
                    labels.add(getString(R.string.phrase_edge));
                    break;
                case "findom":
                    labels.add(getString(R.string.phrase_findom));
                    break;
                case "ntr":
                    labels.add(getString(R.string.phrase_ntr));
                    break;
                case "gooner":
                    labels.add(getString(R.string.phrase_gooner));
                    break;
                default:
                    String clean = value.replace('_', ' ').trim();
                    if (!clean.isEmpty())
                        labels.add(
                                clean.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                                        + clean.substring(1));
                    break;
            }
        }
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        return labels.isEmpty()
                ? getString(R.string.arrangement_none_selected)
                : android.text.TextUtils.join(", ", labels);
    }

    private String friendlyTextFilter(TextSmutConfig config) {
        int sensitivity = config.getSensitivity();
        String level =
                getString(
                        sensitivity == TextSmutConfig.SENSITIVITY_STRICT
                                ? R.string.text_smut_strict
                                : sensitivity == TextSmutConfig.SENSITIVITY_BROAD
                                        ? R.string.text_smut_broad
                                        : R.string.text_smut_balanced);
        return getString(
                R.string.arrangement_text_value,
                level,
                friendlyTextCategoryNames(config.getEnabledCategories()));
    }

    private String friendlyTextCategoryNames(Set<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return getString(R.string.arrangement_none_selected);
        }
        List<String> labels = new ArrayList<>();
        if (categories.contains(TextSmutConfig.CATEGORY_EXPLICIT)) {
            labels.add(getString(R.string.text_smut_explicit));
        }
        if (categories.contains(TextSmutConfig.CATEGORY_FETISH)) {
            labels.add(getString(R.string.text_smut_fetish));
        }
        if (categories.contains(TextSmutConfig.CATEGORY_SOLICITATION)) {
            labels.add(getString(R.string.text_smut_solicitation));
        }
        return labels.isEmpty()
                ? getString(R.string.arrangement_none_selected)
                : android.text.TextUtils.join(", ", labels);
    }

    private String friendlyInfraction(PenanceInfraction infraction) {
        switch (infraction) {
            case NEW_DETECTION:
                return getString(R.string.penance_rule_detection_title);
            case CENSORED_DWELL:
                return getString(R.string.penance_rule_dwell_title);
            case CENSORED_TAP:
                return getString(R.string.penance_rule_tap_title);
            case WATCHED_APP_OPEN:
                return getString(R.string.penance_rule_app_open_title);
            case TAMPER_ATTEMPT:
                return getString(R.string.penance_rule_tamper_title);
            default:
                return infraction.name();
        }
    }

    private String friendlySubliminalPack(String pack) {
        if (SubliminalSettingsRepository.PACK_OBEDIENCE.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_obedience);
        }
        if (SubliminalSettingsRepository.PACK_FOCUS.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_focus);
        }
        if (SubliminalSettingsRepository.PACK_BETA.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_beta);
        }
        if (SubliminalSettingsRepository.PACK_FINDOM.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_findom);
        }
        return getString(R.string.subliminal_pack_summary_custom);
    }

    private String friendlyPreset(SubliminalSettings.Preset preset) {
        String raw = (preset == null ? SubliminalSettings.Preset.NORMAL : preset).name();
        return raw.substring(0, 1) + raw.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private String friendlyEffectName(CensorAppearance.Type type) {
        CensorAppearance.Type resolved = type == null ? CensorAppearance.Type.BOX : type;
        switch (resolved) {
            case PIXELATE:
                return getString(R.string.style_pixelate);
            case BLUR:
                return getString(R.string.style_blur);
            case CUSTOM:
                return getString(R.string.style_custom);
            case STATIC:
                return getString(R.string.style_static);
            case GLITCH:
                return getString(R.string.style_glitch);
            case TAPE:
                return getString(R.string.style_tape);
            case ERROR_POPUP:
                return getString(R.string.style_error);
            case BOX:
            default:
                return getString(R.string.style_box);
        }
    }

    private String friendlyBorderName(CensorAppearance.BorderEffect effect) {
        CensorAppearance.BorderEffect resolved =
                effect == null ? CensorAppearance.BorderEffect.CLASSIC : effect;
        switch (resolved) {
            case GLOW:
                return getString(R.string.border_glow);
            case GRADIENT:
                return getString(R.string.border_gradient);
            case RAINBOW:
                return getString(R.string.border_rainbow);
            case CLASSIC:
            default:
                return getString(R.string.border_classic);
        }
    }

    private String friendlyDetectionPreset(DetectionPreset preset) {
        DetectionPreset resolved = preset == null ? DetectionPreset.MEDIUM : preset;
        switch (resolved) {
            case LOW:
                return getString(R.string.preset_low);
            case HIGH:
                return getString(R.string.preset_high);
            case MEDIUM:
            default:
                return getString(R.string.preset_medium);
        }
    }

    private String friendlySubliminalVoice(SubliminalSettings settings) {
        Set<String> packs = settings.getEnabledPacks();
        if (packs.isEmpty()) return getString(R.string.subliminal_pack_summary_none);
        if (packs.size() != 1) return getString(R.string.subliminal_pack_summary_mixed);
        String pack = packs.iterator().next();
        if (SubliminalSettingsRepository.PACK_OBEDIENCE.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_obedience);
        }
        if (SubliminalSettingsRepository.PACK_FOCUS.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_focus);
        }
        if (SubliminalSettingsRepository.PACK_BETA.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_beta);
        }
        if (SubliminalSettingsRepository.PACK_FINDOM.equals(pack)) {
            return getString(R.string.subliminal_pack_summary_findom);
        }
        return getString(R.string.subliminal_pack_summary_custom);
    }

}
