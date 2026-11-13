package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.internal.InternalProfileAdapter;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.penance.PenanceInfraction;
import java.util.*;

/** No physical profile, financial ledger, relationship, or aggregate statistics enters this view. */
public final class InternalPersonPresentation {
    private InternalPersonPresentation() { }
    public static PersonSnapshot snapshot(Context context, InternalRuntimePresentationSnapshot observed) {
        return snapshot(context, observed.record, observed);
    }
    static PersonSnapshot snapshot(Context context, InternalSubRecord record) { return snapshot(context, record, null); }
    static PersonSnapshot snapshot(Context context, InternalSubRecord record, InternalRuntimePresentationSnapshot observed) {
        if (observed != null && !observed.record.equals(record)) throw new IllegalArgumentException("Different internal observation");
        Map<String, Map<String, Object>> config = record.configuration();
        List<PersonSnapshot.Section> sections = new ArrayList<>();
        List<PersonSnapshot.App> apps = new ArrayList<>();
        @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) config.get("apps").get("app_included_packages_v1");
        if (Boolean.TRUE.equals(config.get("apps").get("app_include_all_v1"))) {
            @SuppressWarnings("unchecked") Set<String> catalog = (Set<String>) config.get("apps").get("app_include_all_packages_v1");
            selected = catalog;
        }
        for (String name : selected) apps.add(new PersonSnapshot.App(name, appLabel(context, name)));
        apps.sort(Comparator.comparing(value -> value.label, String.CASE_INSENSITIVE_ORDER));
        boolean all = Boolean.TRUE.equals(config.get("apps").get("app_include_all_v1"));
        sections.add(new PersonSnapshot.Section("apps", context.getString(R.string.person_apps_title),
                all ? context.getString(R.string.app_mode_always) : context.getResources().getQuantityString(
                        R.plurals.apps_included_count, apps.size(), apps.size()), "", apps));
        Map<String, Object> duration = config.get("duration");
        sections.add(new PersonSnapshot.Section("duration", context.getString(R.string.role_service_duration),
                ServiceDurationSettings.label(context, false, number(duration, "duration"), number(duration, "minimum"),
                        number(duration, "maximum")), ""));
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        if (Boolean.TRUE.equals(config.get("limits").get("app_timer_per_app_enabled"))) for (PersonSnapshot.App app : apps)
            allowances.add(new PersonSnapshot.Allowance(app.packageName, app.label, observed == null ? -1 : observed.usage(app.packageName).appUsedMillis,
                    ((Number) config.get("allowances").getOrDefault(app.packageName,
                            config.get("limits").get("app_timer_per_app_minutes"))).intValue()));
        int limitCount = allowances.size();
        if (!apps.isEmpty() && enabled(config, "limits", "app_timer_total_enabled")) limitCount++;
        sections.add(new PersonSnapshot.Section("censor", context.getString(R.string.sub_censor_title),
                censorSummary(context, config), details(context, config, "censor")));
        sections.add(new PersonSnapshot.Section("limits", context.getString(R.string.sub_limits_title),
                limitCount == 0 ? context.getString(R.string.role_no_app_limits)
                        : context.getResources().getQuantityString(R.plurals.sub_limits_selected, limitCount, limitCount),
                limitsDetails(context, config, allowances)));
        int walletRules = 0;
        for (PenanceInfraction rule : PenanceInfraction.values())
            if (rule != PenanceInfraction.PAID_PAUSE && enabled(config, "wallet", "rule_" + rule.preferenceKey() + "_enabled"))
                walletRules++;
        sections.add(new PersonSnapshot.Section("wallet", context.getString(R.string.sub_wallet_title),
                walletRules == 0 ? context.getString(R.string.role_disabled) : context.getString(R.string.role_wallet_rule_count, walletRules),
                details(context, config, "wallet")));
        String whispers = effectState(context, enabled(config, "modules", "module_subliminal_enabled"));
        String popup = effectState(context, enabled(config, "popup", "popup_storm_enabled"));
        String gag = context.getString(R.string.gag_status_unknown);
        sections.add(new PersonSnapshot.Section("atmosphere", context.getString(R.string.atmosphere_title),
                context.getString(R.string.contract_atmosphere_states, whispers, popup, gag),
                context.getString(R.string.arrangement_detail_line, context.getString(R.string.overview_whispers), whispers)
                        + "\n\n" + context.getString(R.string.arrangement_detail_line, context.getString(R.string.popup_title), popup)
                        + "\n\n" + context.getString(R.string.arrangement_detail_line, context.getString(R.string.gag_title), gag)));
        ProfileData profile = InternalProfileAdapter.toProfileData(record.profile);
        PersonSnapshot.SyncState sync = new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                observed == null ? PersonSnapshot.Freshness.UNKNOWN : PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.UNKNOWN,
                Long.toString(record.configurationRevision), "");
        Set<PersonSnapshot.Capability> capabilities=EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.EDIT_CONTRACT);
        if(observed!=null)capabilities.add(PersonSnapshot.Capability.VIEW_STATS);
        return new PersonSnapshot(DummySubPreview.ID, profile.name, context.getString(R.string.internal_sub_device), "",
                context.getString(R.string.role_unknown_data), PersonSnapshot.Connection.LOCAL,
                PersonSnapshot.Freshness.CURRENT, observed == null ? 0 : observed.observed.at, capabilities, sections, allowances,
                (String) config.get("wallet_display").get("wallet_currency"),
                observed == null ? 0 : observed.statistics().getTotalProtectedSeconds(),
                observed == null ? 0 : observed.statistics().getActivityEvents(),
                observed == null ? 0 : observed.assessed((String)config.get("wallet_display").get("wallet_currency")),
                observed == null ? 0 : observed.statistics().getSessions(), false, sync)
                .withProfile(profile, Collections.emptyMap());
    }
    private static long number(Map<String, Object> fields, String key) { return ((Number) fields.get(key)).longValue(); }

    private static boolean enabled(Map<String, Map<String, Object>> config, String section, String key) {
        return Boolean.TRUE.equals(config.get(section).get(key));
    }

    private static String effectState(Context context, boolean enabled) {
        return context.getString(enabled ? R.string.control_state_on : R.string.popup_off);
    }

    private static String censorSummary(Context context, Map<String, Map<String, Object>> config) {
        if (!enabled(config, "modules", "module_censor_enabled")) return context.getString(R.string.role_disabled);
        Map<String, Object> censor = config.get("censor");
        int count = ((Set<?>) censor.get("enabled_categories")).size();
        if (enabled(config, "censor", "text_smut_enabled")) count += ((Set<?>) censor.get("text_smut_categories")).size();
        return context.getString(R.string.role_filter_count, count);
    }

    private static String limitsDetails(Context context, Map<String, Map<String, Object>> config,
            List<PersonSnapshot.Allowance> allowances) {
        String shared = enabled(config, "limits", "app_timer_total_enabled")
                ? context.getString(R.string.arrangement_minutes, number(config.get("limits"), "app_timer_total_minutes"))
                : context.getString(R.string.popup_off);
        List<String> individual = new ArrayList<>();
        individual.add(effectState(context, enabled(config, "limits", "app_timer_per_app_enabled")));
        for (PersonSnapshot.Allowance allowance : allowances)
            individual.add(context.getString(R.string.arrangement_app_allowance, allowance.label, allowance.dailyMinutes));
        return context.getString(R.string.arrangement_detail_line, context.getString(R.string.arrangement_limits_shared), shared)
                + "\n\n" + context.getString(R.string.arrangement_detail_line, context.getString(R.string.arrangement_limits_individual),
                        String.join("\n", individual));
    }
    static PersonSnapshot summary(Context context, InternalSubRecord record) {
        ProfileData profile=InternalProfileAdapter.toProfileData(record.profile);
        return new PersonSnapshot(DummySubPreview.ID, profile.name, context.getString(R.string.internal_sub_device), "",
                context.getString(R.string.role_unknown_data), PersonSnapshot.Connection.LOCAL,
                PersonSnapshot.Freshness.CURRENT, 0, Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                (String)record.configuration().get("wallet_display").get("wallet_currency"), 0, 0, 0, 0, false,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN,
                        PersonSnapshot.Freshness.UNKNOWN, "", "")).withProfile(profile, Collections.emptyMap());
    }
    private static String details(Context context, Map<String, Map<String, Object>> config, String section) {
        List<String> rows = new ArrayList<>();
        for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(section)) {
            Object value = config.get(section).get(field.key);
            String shown;
            if (value instanceof Boolean) shown = effectState(context, (Boolean) value);
            else if (value instanceof Set<?>) {
                List<String> labels = new ArrayList<>();
                for (Object item : (Set<?>) value) labels.add(choice(context, field, item.toString()));
                shown = labels.isEmpty() ? context.getString(R.string.arrangement_none_selected) : String.join(", ", labels);
            } else if (value != null) shown = choice(context, field, value.toString());
            else continue;
            rows.add(context.getString(R.string.arrangement_detail_line, context.getString(field.label), shown));
        }
        return String.join("\n\n", rows);
    }

    private static String choice(Context context, ConfigurationSettingCatalog.Field field, String value) {
        int label = ConfigurationSettingCatalog.choiceLabel(field, value);
        return label == 0 ? value : context.getString(label);
    }
    private static String appLabel(Context context, String name) {
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return name; }
    }
}
