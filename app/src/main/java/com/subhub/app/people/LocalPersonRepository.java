package com.subhub.app.people;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adapts the existing on-device stores; never supplies a remote identity. */
public final class LocalPersonRepository implements PersonRepository {
    private final Context context;
    public LocalPersonRepository(Context context) { this.context = context.getApplicationContext(); }

    @Override public List<PersonId> people() { return Collections.singletonList(PersonId.LOCAL); }

    @Override public PersonSnapshot loadSummary(PersonId id) {
        if (!PersonId.LOCAL.equals(id)) throw new IllegalArgumentException("Not this device");
        AppModeManager apps = new AppModeManager(context);
        boolean paused = new PaidPauseManager(context).isActive();
        boolean active = paused || CommitmentManager.isActive(context) || apps.isArmed()
                || ScreenCaptureService.isRunning() || ScreenshotAccessibilityService.isRecognitionActive();
        String service = context.getString(paused ? R.string.role_service_paused
                : active ? R.string.role_service_active : R.string.role_service_inactive);
        StatsSnapshot stats = new StatsRepository(context).load();
        return new PersonSnapshot(id, ProfileNames.get(context, false), Build.MODEL,
                context.getString(R.string.role_unknown_data), service,
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                new PenanceManager(context).getCurrency(), stats.getTotalProtectedSeconds(),
                stats.getActivityEvents(), stats.getTributeCents(), stats.getSessions(), active,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.UNKNOWN,
                        PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "", ""))
                .withProfile(ProfileStore.load(context, false), Collections.emptyMap());
    }

    @Override public PersonSnapshot load(PersonId id) {
        if (!PersonId.LOCAL.equals(id)) throw new IllegalArgumentException("Not this device");
        SettingsRepository settings = new SettingsRepository(context);
        FeatureModuleManager modules = new FeatureModuleManager(context);
        AppModeManager apps = new AppModeManager(context);
        AppTimerManager timers = new AppTimerManager(context);
        AppTimerManager.Settings limits = timers.loadSettings();
        Set<String> packages = apps.getIncludedPackages();
        long usageAt = System.currentTimeMillis();
        Map<String, AppTimerManager.UsageSnapshot> usage = timers.snapshots(packages, usageAt);
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        for (String packageName : packages) {
            String label = appLabel(packageName);
            if (limits.perAppEnabled) {
                AppTimerManager.UsageSnapshot used = usage.get(packageName);
                allowances.add(new PersonSnapshot.Allowance(packageName, label,
                        used == null ? 0 : used.appUsedMillis, timers.allowanceMinutes(packageName), null,
                        com.subhub.app.remote.ExtraTimeRequests.allowance(context, packageName, usageAt)
                                .individualBudget(timers.allowanceMinutes(packageName) * 60_000L)));
            }
        }

        if (limits.totalEnabled && !packages.isEmpty()) {
            long total = usage.isEmpty() ? 0 : usage.values().iterator().next().totalUsedMillis;
            allowances.add(new PersonSnapshot.Allowance("", context.getString(R.string.role_shared_allowance),
                    total, limits.totalMinutes));
        }

        allowances = PersonSnapshot.Allowance.ordered(allowances);
        PenanceManager wallet = new PenanceManager(context);
        List<PersonSnapshot.Section> sections = contractSections(settings, modules, apps.isAllApps(),
                packages, allowances.size(), wallet, false);
        StatsSnapshot stats = new StatsRepository(context).load();
        boolean paused = new PaidPauseManager(context).isActive();
        boolean active = paused || CommitmentManager.isActive(context) || apps.isArmed()
                || ScreenCaptureService.isRunning() || ScreenshotAccessibilityService.isRecognitionActive();
        String service = context.getString(paused ? R.string.role_service_paused
                : active ? R.string.role_service_active : R.string.role_service_inactive);
        EnumSet<PersonSnapshot.Capability> capabilities = localCapabilities();
        return withContractOwner(new PersonSnapshot(id, ProfileNames.get(context, false), Build.MODEL,
                context.getString(R.string.role_local_configuration),
                service, PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                capabilities, sections, allowances, wallet.getCurrency(),
                stats.getTotalProtectedSeconds(), stats.getActivityEvents(), stats.getTributeCents(),
                stats.getSessions(), active).withProfile(ProfileStore.load(context, false), localConsent(),
                        com.subhub.app.security.SubFeaturePolicy.hardcoreOverride(context)), settings);
    }

    /** The Sub Contract reads configured values without opening usage/history or session stores. */
    PersonSnapshot loadContract() {
        SettingsRepository settings = new SettingsRepository(context);
        AppTimerManager.Settings limits = AppTimerManager.forConfiguration(settings.preferences()).loadSettings();
        AppModeManager apps = new AppModeManager(context);
        Set<String> packages = apps.getIncludedPackages();
        int allowances = !com.subhub.app.security.SubFeaturePolicy.allowed(context, "limits") ? 0
                : (limits.perAppEnabled ? packages.size() : 0) + (limits.totalEnabled && !packages.isEmpty() ? 1 : 0);
        List<PersonSnapshot.Section> sections = contractSections(settings, new FeatureModuleManager(context),
                apps.isAllApps(), packages, allowances, new PenanceManager(context), true);
        return withContractOwner(new PersonSnapshot(PersonId.LOCAL, context.getString(R.string.header_subtitle_sub),
                Build.MODEL, context.getString(R.string.role_local_configuration), "",
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0, localCapabilities(), sections,
                List.of(), "", 0, 0, 0, 0, false), settings);
    }

    private List<PersonSnapshot.Section> contractSections(SettingsRepository settings, FeatureModuleManager modules,
            boolean allApps, Set<String> packages, int allowanceCount, PenanceManager wallet, boolean contractOnly) {
        LocalContractDetails descriptions = new LocalContractDetails(context);
        List<PersonSnapshot.App> apps = new ArrayList<>();
        if (!allApps) for (String name : packages) apps.add(new PersonSnapshot.App(name, appLabel(name)));
        apps.sort(java.util.Comparator.comparing(value -> value.label, String.CASE_INSENSITIVE_ORDER));
        List<String> names = new ArrayList<>();
        for (PersonSnapshot.App app : apps) names.add(app.label);
        List<PersonSnapshot.Section> sections = new ArrayList<>();
        sections.add(new PersonSnapshot.Section("apps", context.getString(R.string.person_apps_title),
                allApps ? context.getString(R.string.app_mode_always)
                        : context.getResources().getQuantityString(R.plurals.apps_included_count, apps.size(), apps.size()),
                String.join("\n", names), apps));

        Set<String> imageCategories = settings.loadDetectorConfig().getEnabledCategories();
        com.subhub.app.detection.text.TextSmutConfig text = settings.loadTextSmutConfig();
        int filterCount = imageCategories.size() + (text.isEnabled() ? text.getEnabledCategories().size() : 0);
        sections.add(new PersonSnapshot.Section("censor", context.getString(R.string.sub_censor_title),
                modules.isCensorEnabled() ? context.getString(R.string.role_filter_count, filterCount)
                        : context.getString(R.string.role_disabled),
                descriptions.forSection("censor")));
        sections.add(new PersonSnapshot.Section("limits", context.getString(R.string.sub_limits_title),
                allowanceCount == 0 ? context.getString(R.string.role_no_app_limits)
                        : context.getResources().getQuantityString(R.plurals.sub_limits_selected, allowanceCount, allowanceCount),
                contractOnly ? descriptions.observedLimitsDetails() : descriptions.forSection("limits")));

        int rules = 0;
        for (PenanceInfraction rule : PenanceInfraction.values()) {
            if (rule != PenanceInfraction.PAID_PAUSE && wallet.isInfractionEnabled(rule)) rules++;
        }
        sections.add(new PersonSnapshot.Section("wallet", context.getString(R.string.sub_wallet_title),
                wallet.isEnabled() ? context.getString(R.string.role_wallet_rule_count, rules)
                        : context.getString(R.string.role_disabled),
                descriptions.forSection("wallet")));
        boolean whispers = modules.isSubliminalEnabled(), popup = PopupStormSettings.load(context).isEnabled();
        int effects = (whispers ? 1 : 0) + (popup ? 1 : 0);
        sections.add(new PersonSnapshot.Section("atmosphere", context.getString(R.string.atmosphere_title),
                context.getResources().getQuantityString(R.plurals.atmosphere_effects_active, effects, effects),
                descriptions.forSection("atmosphere")));
        return sections;
    }

    private static EnumSet<PersonSnapshot.Capability> localCapabilities() {
        EnumSet<PersonSnapshot.Capability> capabilities = EnumSet.allOf(PersonSnapshot.Capability.class);
        if (!People.isLocalConfigurationAllowed()) capabilities.remove(PersonSnapshot.Capability.EDIT_CONTRACT);
        return capabilities;
    }

    private PersonSnapshot withContractOwner(PersonSnapshot person, SettingsRepository settings) {
        boolean localDomOwner = person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && !settings.preferences().contains("remote_active_configuration");
        return person.withContractDom(localDomOwner ? ProfileStore.load(context, true)
                        : ProfileData.empty(context.getString(R.string.header_subtitle_dom)),
                        localDomOwner && com.subhub.app.security.ControllerPinManager.isConfigured(context)
                                ? new PersonId("journal_local_dom") : null);
    }

    private Map<String, Boolean> localConsent() {
        Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        for (String key : com.subhub.app.security.SubFeaturePolicy.FEATURES)
            result.put(key, com.subhub.app.security.SubFeaturePolicy.allowed(context, key));
        return result;
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) { return packageName; }
    }

}
