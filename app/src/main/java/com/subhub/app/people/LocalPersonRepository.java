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
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Adapts the existing on-device stores; never supplies a remote identity. */
public final class LocalPersonRepository implements PersonRepository {
    private final Context context;
    public LocalPersonRepository(Context context) { this.context = context.getApplicationContext(); }

    @Override public List<PersonId> people() { return Collections.singletonList(PersonId.LOCAL); }

    @Override public PersonSnapshot load(PersonId id) {
        if (!PersonId.LOCAL.equals(id)) throw new IllegalArgumentException("Not this device");
        SettingsRepository settings = new SettingsRepository(context);
        FeatureModuleManager modules = new FeatureModuleManager(context);
        AppModeManager apps = new AppModeManager(context);
        AppTimerManager timers = new AppTimerManager(context);
        AppTimerManager.Settings limits = timers.loadSettings();
        Set<String> packages = apps.getIncludedPackages();
        Map<String, AppTimerManager.UsageSnapshot> usage = timers.snapshots(packages, System.currentTimeMillis());
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        for (String packageName : packages) {
            String label = appLabel(packageName);
            if (limits.perAppEnabled) {
                AppTimerManager.UsageSnapshot used = usage.get(packageName);
                allowances.add(new PersonSnapshot.Allowance(packageName, label,
                        used == null ? 0 : used.appUsedMillis, timers.allowanceMinutes(packageName)));
            }
        }
        allowances.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        if (limits.totalEnabled && !packages.isEmpty()) {
            long total = usage.isEmpty() ? 0 : usage.values().iterator().next().totalUsedMillis;
            allowances.add(new PersonSnapshot.Allowance("", context.getString(R.string.role_shared_allowance),
                    total, limits.totalMinutes));
        }

        List<PersonSnapshot.Section> sections = new ArrayList<>();
        Set<String> imageCategories = settings.loadDetectorConfig().getEnabledCategories();
        com.subhub.app.detection.text.TextSmutConfig text = settings.loadTextSmutConfig();
        int filterCount = imageCategories.size() + (text.isEnabled() ? text.getEnabledCategories().size() : 0);
        CensorAppearance appearance = settings.loadAppearance();
        sections.add(new PersonSnapshot.Section("censor", context.getString(R.string.sub_censor_title),
                modules.isCensorEnabled() ? context.getString(R.string.role_filter_count, filterCount)
                        : context.getString(R.string.role_disabled),
                context.getString(R.string.role_filter_details, names(imageCategories),
                        text.isEnabled() ? names(text.getEnabledCategories()) : context.getString(R.string.role_disabled),
                        friendly(appearance.getType().name()))));
        sections.add(new PersonSnapshot.Section("limits", context.getString(R.string.sub_limits_title),
                allowances.isEmpty() ? context.getString(R.string.role_no_app_limits)
                        : context.getResources().getQuantityString(R.plurals.sub_limits_selected, allowances.size(), allowances.size()),
                ""));

        PenanceManager wallet = new PenanceManager(context);
        int rules = 0;
        for (PenanceInfraction rule : PenanceInfraction.values()) {
            if (rule != PenanceInfraction.PAID_PAUSE && wallet.isInfractionEnabled(rule)) rules++;
        }
        sections.add(new PersonSnapshot.Section("wallet", context.getString(R.string.sub_wallet_title),
                wallet.isEnabled() ? context.getString(R.string.role_wallet_rule_count, rules)
                        : context.getString(R.string.role_disabled),
                context.getString(R.string.role_wallet_details,
                        wallet.money(wallet.snapshot(System.currentTimeMillis()).getDueCents()),
                        wallet.money(wallet.getDailyCapCents()), wallet.money(wallet.getWeeklyCapCents()))));
        boolean whispers = modules.isSubliminalEnabled(), popup = PopupStormSettings.load(context).isEnabled();
        int effects = (whispers ? 1 : 0) + (popup ? 1 : 0);
        sections.add(new PersonSnapshot.Section("atmosphere", context.getString(R.string.atmosphere_title),
                context.getResources().getQuantityString(R.plurals.atmosphere_effects_active, effects, effects),
                context.getString(R.string.role_atmosphere_details,
                        context.getString(whispers ? R.string.role_enabled : R.string.role_disabled),
                        context.getString(popup ? R.string.role_enabled : R.string.role_disabled))));

        StatsSnapshot stats = new StatsRepository(context).load();
        LocalContractDetails descriptions = new LocalContractDetails(context);
        List<String> includedNames = new ArrayList<>();
        if (!apps.isAllApps()) for (String packageName : apps.getIncludedPackages())
            includedNames.add(appLabel(packageName));
        Collections.sort(includedNames, String.CASE_INSENSITIVE_ORDER);
        sections.add(0, new PersonSnapshot.Section("apps", context.getString(R.string.person_apps_title),
                apps.isAllApps() ? context.getString(R.string.app_mode_always)
                        : context.getResources().getQuantityString(R.plurals.apps_included_count,
                                includedNames.size(), includedNames.size()),
                String.join("\n", includedNames)));
        List<PersonSnapshot.Section> canonical = new ArrayList<>();
        for (PersonSnapshot.Section section : sections) {
            String detail = "apps".equals(section.key) ? section.details : descriptions.forSection(section.key);
            canonical.add(new PersonSnapshot.Section(section.key, section.title, section.summary, detail));
        }
        boolean paused = new PaidPauseManager(context).isActive();
        boolean active = paused || CommitmentManager.isActive(context) || apps.isArmed()
                || ScreenCaptureService.isRunning() || ScreenshotAccessibilityService.isRecognitionActive();
        String service = context.getString(paused ? R.string.role_service_paused
                : active ? R.string.role_service_active : R.string.role_service_inactive);
        EnumSet<PersonSnapshot.Capability> capabilities = EnumSet.allOf(PersonSnapshot.Capability.class);
        if (!People.isLocalConfigurationAllowed()) capabilities.remove(PersonSnapshot.Capability.EDIT_CONTRACT);
        return new PersonSnapshot(id, ProfileNames.get(context, false), Build.MODEL,
                context.getString(R.string.role_local_configuration),
                service, PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                capabilities, canonical, allowances, wallet.getCurrency(),
                stats.getTotalProtectedSeconds(), stats.getActivityEvents(), stats.getTributeCents(),
                stats.getSessions(), active);
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) { return packageName; }
    }

    private String names(Set<String> values) {
        List<String> names = new ArrayList<>();
        for (String value : values) names.add(friendly(value));
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names.isEmpty() ? context.getString(R.string.arrangement_none_selected) : String.join(", ", names);
    }

    private static String friendly(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
