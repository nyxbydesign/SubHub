package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.internal.InternalProfileAdapter;
import com.subhub.app.internal.InternalSubRecord;
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
                all ? context.getString(R.string.app_selection_all) : context.getResources().getQuantityString(
                        R.plurals.apps_included_count, apps.size(), apps.size()), "", apps));
        String[] keys = {"censor", "limits", "wallet", "atmosphere", "duration"};
        int[] labels = {R.string.sub_censor_title, R.string.sub_limits_title, R.string.sub_wallet_title,
                R.string.atmosphere_title, R.string.role_service_duration};
        for (int i = 0; i < keys.length; i++) sections.add(new PersonSnapshot.Section(keys[i], context.getString(labels[i]),
                context.getString(R.string.internal_sub_settings), details(context, config, keys[i])));
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        if (Boolean.TRUE.equals(config.get("limits").get("app_timer_per_app_enabled"))) for (PersonSnapshot.App app : apps)
            allowances.add(new PersonSnapshot.Allowance(app.packageName, app.label, observed == null ? -1 : observed.usage(app.packageName).appUsedMillis,
                    ((Number) config.get("allowances").getOrDefault(app.packageName,
                            config.get("limits").get("app_timer_per_app_minutes"))).intValue()));
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
        if ("duration".equals(section)) return "";
        List<String> rows = new ArrayList<>();
        String[] sections = "atmosphere".equals(section) ? new String[]{"subliminal", "popup"} : new String[]{section};
        for (String key : sections) for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(key)) {
            Object value = config.get(key).get(field.key);
            String shown = value instanceof Boolean ? context.getString((Boolean) value ? R.string.role_enabled : R.string.role_disabled)
                    : value instanceof Set ? String.join(", ", (Set<String>) value) : String.valueOf(value);
            rows.add(context.getString(field.label) + ": " + shown);
        }
        return String.join("\n", rows);
    }
    private static String appLabel(Context context, String name) {
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return name; }
    }
}
