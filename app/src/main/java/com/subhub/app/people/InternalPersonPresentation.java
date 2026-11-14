package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;
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
        sections.add(new PersonSnapshot.Section("apps", context.getString(R.string.remote_apps),
                all ? context.getString(R.string.remote_all_apps) : context.getString(R.string.remote_app_count, apps.size()), "", apps));
        Map<String, Object> duration = config.get("duration");
        sections.add(new PersonSnapshot.Section("duration", context.getString(R.string.role_service_duration),
                ServiceDurationSettings.label(context, false, number(duration, "duration"), number(duration, "minimum"),
                        number(duration, "maximum")), ""));
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        if (Boolean.TRUE.equals(config.get("limits").get("app_timer_per_app_enabled"))) for (PersonSnapshot.App app : apps)
            allowances.add(new PersonSnapshot.Allowance(app.packageName, app.label, observed == null ? -1 : observed.usage(app.packageName).appUsedMillis,
                    ((Number) config.get("allowances").getOrDefault(app.packageName,
                            config.get("limits").get("app_timer_per_app_minutes"))).intValue()));
        for (String group : List.of("censor", "limits", "wallet"))
            sections.add(ContractFeatureDetails.section(context, group, config.get(group)));
        AtmosphereContractDetails atmosphere = AtmosphereContractDetails.internal(context, config);
        sections.add(new PersonSnapshot.Section("atmosphere", context.getString(R.string.atmosphere_title),
                atmosphere.summary, atmosphere.details));
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

    static PersonSnapshot summary(Context context, InternalSubRecord record) {
        ProfileData profile=InternalProfileAdapter.toProfileData(record.profile);
        return new PersonSnapshot(DummySubPreview.ID, profile.name, context.getString(R.string.internal_sub_device), "",
                context.getString(R.string.role_unknown_data), PersonSnapshot.Connection.LOCAL,
                PersonSnapshot.Freshness.CURRENT, 0, Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                (String)record.configuration().get("wallet_display").get("wallet_currency"), 0, 0, 0, 0, false,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN,
                        PersonSnapshot.Freshness.UNKNOWN, "", "")).withProfile(profile, Collections.emptyMap());
    }
    private static String appLabel(Context context, String name) {
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return name; }
    }
}
