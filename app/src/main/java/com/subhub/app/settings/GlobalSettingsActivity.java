package com.subhub.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.databinding.ActivityGlobalSettingsBinding;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.studio.StudioActivity;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Always-available home for app-wide feature, safety, pack, and support settings. */
public final class GlobalSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_SHOW_INCLUDED_APPS = "show_included_apps";
    private ActivityGlobalSettingsBinding binding;
    private final Map<String, com.subhub.app.util.ExpandableSectionView> sections =
            new LinkedHashMap<>();
    private LinearLayout categoryMenu, privacyControls;
    private String selectedGroup = "";

    private FeatureModuleManager modules;
    private HardcoreModeManager hardcore;
    private AppModeManager appMode;
    private ActivityResultLauncher<Intent> hardcoreActivation;
    private ActivityResultLauncher<Intent> hardcoreAccessibility;
    private boolean updatingHardcore;
    private boolean editingUnlocked;
    private boolean appsLoaded;
    private com.subhub.app.util.AsyncUiScope uiData;
    private IncludedAppsAdapter includedApps;
    private final Set<String> includedPackages = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiData = com.subhub.app.util.AsyncUiScope.forPage(this);
        binding = ActivityGlobalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(
                binding.getRoot(), R.drawable.ic_tab_settings, R.string.global_settings_title, 0);
        arrangeSettingsSections();
        if (savedInstanceState != null)
            selectedGroup = savedInstanceState.getString("expanded_settings", "");
        modules = new FeatureModuleManager(this);
        hardcore = new HardcoreModeManager(this);
        appMode = new AppModeManager(this);
        includedPackages.addAll(appMode.getIncludedPackages());
        hardcoreActivation =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        ignored -> {
                            boolean active = hardcore.finishActivation();
                            refreshHardcoreState();
                            if (active && !new AppModeManager(this).isAccessibilityEnabled()) {
                                Toast.makeText(
                                                this,
                                                R.string.hardcore_status_accessibility,
                                                Toast.LENGTH_LONG)
                                        .show();
                            }
                        });
        hardcoreAccessibility =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        ignored -> {
                            if (appMode.isAccessibilityEnabled()) {
                                hardcoreActivation.launch(hardcore.activationIntent());
                            } else {
                                hardcore.cancelPendingActivation();
                                Toast.makeText(
                                                this,
                                                R.string.hardcore_accessibility_cancelled,
                                                Toast.LENGTH_LONG)
                                        .show();
                            }
                            refreshHardcoreState();
                        });
        binding.switchModuleCensor.setChecked(modules.isCensorEnabled());
        binding.switchModuleLimits.setChecked(modules.isLimitsEnabled());
        binding.switchModuleWallet.setChecked(modules.isWalletEnabled());
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        binding.buttonPacks.setOnClickListener(
                view -> startActivity(new Intent(this, StudioActivity.class)));
        binding.buttonHelp.setOnClickListener(
                view -> startActivity(new Intent(this, HelpActivity.class)));
        binding.buttonDiagnostics.setOnClickListener(
                view -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        binding.switchModuleCensor.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchModuleLimits.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchModuleWallet.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchHardcoreMode.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updatingHardcore) changeHardcoreMode(checked);
                });
        binding.buttonAccessibilitySettings.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        binding.buttonToggleApps.setOnClickListener(
                view -> {
                    boolean show = binding.appListContent.getVisibility() != View.VISIBLE;
                    binding.appListContent.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (show) loadApps();
                    binding.buttonToggleApps.setText(
                            show ? R.string.app_selection_collapse : R.string.app_selection_expand);
                });
        binding.appListContent.setVisibility(View.GONE);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        applyEditState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showRequestedIncludedApps();
    }

    private void showRequestedIncludedApps() {
        if (binding == null || !getIntent().getBooleanExtra(EXTRA_SHOW_INCLUDED_APPS, false)) return;
        getIntent().removeExtra(EXTRA_SHOW_INCLUDED_APPS);
        selectedGroup = "apps";
        displayGroup();
        binding.appListContent.setVisibility(View.VISIBLE);
        loadApps();
        binding.buttonToggleApps.setText(R.string.app_selection_collapse);
        binding.appsCard.post(
                () -> {
                    if (binding != null)
                        binding.appsCard.requestRectangleOnScreen(
                                new android.graphics.Rect(
                                        0,
                                        0,
                                        binding.appsCard.getWidth(),
                                        Math.min(binding.appsCard.getHeight(), dp(220))),
                                false);
                });
    }

    private void arrangeSettingsSections() {
        LinearLayout container = binding.settingsSections;
        View header = PrimaryHeader.view(binding.getRoot());
        // Keep the existing controls and their bindings; only their presentation moves.
        container.removeAllViews();
        if (header.getParent() != null)
            ((android.view.ViewGroup) header.getParent()).removeView(header);
        container.addView(header);
        categoryMenu = new LinearLayout(this);
        categoryMenu.setOrientation(LinearLayout.VERTICAL);
        container.addView(categoryMenu, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < binding.featureAreasCard.getChildCount(); i++) {
            View child = binding.featureAreasCard.getChildAt(i);
            if (child instanceof TextView && !(child instanceof android.widget.CompoundButton))
                child.setVisibility(View.GONE);
        }
        addGroup(SettingsSection.FEATURES, binding.featureAreasCard,
                binding.hardcoreCard,
                binding.buttonPacks);
        addGroup(SettingsSection.APPS, binding.appsCard);
        privacyControls = new LinearLayout(this);
        privacyControls.setOrientation(LinearLayout.VERTICAL);
        addGroup(SettingsSection.PRIVACY_PERMISSIONS, privacyControls, binding.androidAccessCard);
        sections.get("privacy")
                .content()
                .addView(
                        permissionAction(
                                getString(R.string.settings_overlay),
                                () ->
                                        startActivity(
                                                new Intent(
                                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        android.net.Uri.parse(
                                                                "package:" + getPackageName())))));
        sections.get("privacy")
                .content()
                .addView(
                        permissionAction(
                                getString(R.string.settings_notifications),
                                () ->
                                        startActivity(
                                                new Intent(
                                                                Settings
                                                                        .ACTION_APP_NOTIFICATION_SETTINGS)
                                                        .putExtra(
                                                                Settings.EXTRA_APP_PACKAGE,
                                                                getPackageName()))));
        sections.get("privacy")
                .content()
                .addView(
                        permissionAction(
                                getString(R.string.settings_battery),
                                () ->
                                        startActivity(
                                                new Intent(
                                                        Settings
                                                                .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        addGroup(SettingsSection.HELP, binding.buttonHelp);
        sections.get("help")
                .content()
                .addView(
                        settingsAction(
                                getString(R.string.tour_replay),
                                () ->
                                        startActivity(
                                                new Intent(
                                                                this,
                                                                com.subhub.app.onboarding
                                                                        .OnboardingActivity.class)
                                                        .putExtra(
                                                                com.subhub.app.onboarding
                                                                        .OnboardingActivity.REPLAY,
                                                                true))));
        sections.get("help")
                .content()
                .addView(
                        settingsAction(
                                getString(R.string.settings_updates),
                                () ->
                                        startActivity(
                                                new Intent(
                                                        this,
                                                        com.subhub.app.update.UpdatesActivity
                                                                .class))));
        if (binding.buttonDiagnostics.getParent() != null)
            ((android.view.ViewGroup) binding.buttonDiagnostics.getParent())
                    .removeView(binding.buttonDiagnostics);
        sections.get("help").content().addView(binding.buttonDiagnostics);
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new androidx.activity.OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (!selectedGroup.isEmpty()) {
                                    selectedGroup = "";
                                    displayGroup();
                                } else {
                                    setEnabled(false);
                                    getOnBackPressedDispatcher().onBackPressed();
                                }
                            }
                        });
        selectedGroup = getIntent().getStringExtra("settings_group");
        if (selectedGroup == null) selectedGroup = "";
        if (selectedGroup.equals("appearance") || selectedGroup.equals("pacts"))
            selectedGroup = "features";
        if (selectedGroup.equals("permissions")) selectedGroup = "privacy";
    }

    private TextView settingsAction(String title, Runnable action) {
        android.widget.Button row =
                (android.widget.Button)
                        getLayoutInflater()
                                .inflate(R.layout.view_ux_action, binding.settingsSections, false);
        row.setText(title);
        row.setAllCaps(false);
        row.setMinHeight(dp(48));
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        row.setLayoutParams(params);
        return row;
    }

    private TextView permissionAction(String title, Runnable action) {
        return settingsAction(title, () -> ControllerPinGate.require(this, action, false));
    }

    private void addGroup(SettingsSection definition, View... controls) {
        String key = definition.key;
        com.subhub.app.util.ExpandableSectionView section =
                new com.subhub.app.util.ExpandableSectionView(
                        this, key, definition.title, definition.icon);
        section.addControls(controls);
        section.setSummaryVisible(!key.equals("help"));
        sections.put(key, section);
        section.header()
                .setOnClickListener(
                        view -> {
                            Runnable open =
                                    () -> {
                                        selectedGroup = key.equals(selectedGroup) ? "" : key;
                                        applyEditState();
                                        if (key.equals(selectedGroup)) section.reveal();
                                    };
                            if (!key.equals(selectedGroup) && protectedGroup(key))
                                ControllerPinGate.require(this, open, false);
                            else open.run();
                        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        categoryMenu.addView(section, params);
    }

    private boolean protectedGroup(String key) {
        SettingsSection section = SettingsSection.fromKey(key);
        return section != null && section.requiresController;
    }

    private void displayGroup() {
        if (categoryMenu == null) return;
        if (!sections.containsKey(selectedGroup)
                || protectedGroup(selectedGroup) && !ControllerPinManager.isDomModeActive())
            selectedGroup = "";
        for (Map.Entry<String, com.subhub.app.util.ExpandableSectionView> section :
                sections.entrySet())
            section.getValue().setExpanded(section.getKey().equals(selectedGroup));
        if (selectedGroup.equals("privacy"))
            com.subhub.app.privacy.PrivacyControls.bind(
                    this, privacyControls, this::displayGroup);
        if (modules == null) return;
        sections.get("features")
                .summary()
                .setText(
                        getString(
                                R.string.settings_features_summary,
                                (modules.isCensorEnabled() ? 1 : 0)
                                        + (modules.isLimitsEnabled() ? 1 : 0)
                                        + (modules.isWalletEnabled() ? 1 : 0)
                                        + (hardcore.isRequested() ? 1 : 0)));
        int appCount = appMode.getIncludedPackages().size();
        sections.get("apps")
                .summary()
                .setText(
                        getResources()
                                .getQuantityString(
                                        R.plurals.apps_included_count, appCount, appCount));
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        boolean notification =
                androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
        com.subhub.app.privacy.PrivacyManager privacy =
                new com.subhub.app.privacy.PrivacyManager(this);
        sections.get("privacy")
                .summary()
                .setText(
                        getString(
                                R.string.settings_privacy_summary,
                                getString(
                                        privacy.isDiscreet()
                                                ? R.string.atmosphere_state_on
                                                : R.string.atmosphere_state_off),
                                getString(
                                        privacy.isAppLockEnabled()
                                                ? R.string.atmosphere_state_on
                                                : R.string.atmosphere_state_off))
                                + " · "
                                + getString(
                                        R.string.settings_permission_status,
                                        (appMode.isAccessibilityEnabled() ? 1 : 0)
                                                + (overlay ? 1 : 0)
                                                + (notification ? 1 : 0)));
        sections.get("help").summary().setText(R.string.settings_help_summary);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("expanded_settings", selectedGroup);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showRequestedIncludedApps();
        applyEditState();
        HardcoreReadinessNotificationManager.refresh(this);
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            ControllerPinManager.enterSubMode();
            applyEditState();
            SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        } else ControllerPinGate.require(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        editingUnlocked = ControllerPinManager.isSessionUnlocked();
        applySpaceVisibility();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        boolean modulesEditable = editingUnlocked;
        binding.switchModuleCensor.setEnabled(modulesEditable);
        binding.switchModuleLimits.setEnabled(modulesEditable);
        binding.switchModuleWallet.setEnabled(modulesEditable);
        binding.switchHardcoreMode.setEnabled(editingUnlocked);
        binding.buttonAccessibilitySettings.setEnabled(editingUnlocked);
        if (includedApps != null) includedApps.setEditing(editingUnlocked);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        refreshHardcoreState();
        refreshAccessState();
    }

    private void applySpaceVisibility() {
        boolean domSpace = ControllerPinManager.isDomModeActive();
        int domVisibility = domSpace ? View.VISIBLE : View.GONE;
        binding.settingsGroupProtection.setVisibility(domVisibility);
        binding.hardcoreCard.setVisibility(domVisibility);
        binding.featureAreasCard.setVisibility(domVisibility);
        binding.settingsGroupCoverage.setVisibility(domVisibility);
        binding.appsCard.setVisibility(domVisibility);
        binding.androidAccessCard.setVisibility(View.VISIBLE);
        binding.appListCard.setVisibility(domVisibility);
        binding.buttonHelp.setVisibility(View.VISIBLE);
        binding.buttonDiagnostics.setVisibility(View.VISIBLE);
        binding.settingsGroupServices.setVisibility(View.VISIBLE);
        binding.appSettingsCard.setVisibility(View.VISIBLE);
        binding.buttonPacks.setVisibility(View.VISIBLE);
        displayGroup();
    }

    private void saveIncludedApps() {
        if (!ControllerPinManager.isDomModeActive()) return;
        appMode.saveIncludedPackages(includedPackages);
        renderSelectedCount();
    }

    private void refreshAccessState() {
        if (binding == null || appMode == null) return;
        int status;
        if (!appMode.isAccessibilityEnabled()) status = R.string.apps_accessibility_off;
        else if (ScreenshotAccessibilityService.isRunning()) {
            status = R.string.apps_accessibility_ready;
        } else status = R.string.apps_accessibility_reconnecting;
        binding.serviceStatus.setText(status);
    }

    private void changeHardcoreMode(boolean enabled) {
        if (!ControllerPinManager.isDomModeActive()) {
            refreshHardcoreState();
            return;
        }
        if (enabled) {
            com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(R.string.hardcore_consent_title)
                    .setMessage(R.string.hardcore_consent_body)
                    .setNegativeButton(
                            android.R.string.cancel, (dialog, which) -> refreshHardcoreState())
                    .setPositiveButton(
                            R.string.hardcore_consent_enable,
                            (dialog, which) -> {
                                hardcore.beginActivation();
                                refreshHardcoreState();
                                if (appMode.isAccessibilityEnabled()) {
                                    hardcoreActivation.launch(hardcore.activationIntent());
                                } else {
                                    hardcoreAccessibility.launch(
                                            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                }
                            })
                    .show();
        } else {
            com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(R.string.hardcore_release_title)
                    .setMessage(R.string.hardcore_release_body)
                    .setNegativeButton(
                            android.R.string.cancel, (dialog, which) -> refreshHardcoreState())
                    .setPositiveButton(
                            R.string.hardcore_release,
                            (dialog, which) -> {
                                hardcore.disable();
                                refreshHardcoreState();
                            })
                    .show();
        }
    }

    private void refreshHardcoreState() {
        if (binding == null || hardcore == null) return;
        updatingHardcore = true;
        boolean active = hardcore.isEnabled();
        boolean accessibilityMissing =
                hardcore.isRequested() && !new AppModeManager(this).isAccessibilityEnabled();
        binding.switchHardcoreMode.setChecked(active || hardcore.isRequested());
        if (accessibilityMissing) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_accessibility);
        } else if (hardcore.isGuardReady()) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_active);
        } else if (hardcore.isRequested()) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_pending);
        } else {
            binding.hardcoreStatus.setText(R.string.hardcore_status_off);
        }
        updatingHardcore = false;
    }

    private void saveModules() {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        boolean censor = binding.switchModuleCensor.isChecked();
        modules.save(
                censor,
                binding.switchModuleLimits.isChecked(),
                binding.switchModuleWallet.isChecked());
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
    }

    private void loadApps() {
        if (appsLoaded || binding == null || uiData.isPending("apps")) return;
        android.content.Context app = getApplicationContext();
        uiData.load(
                "apps",
                () -> InstalledAppCatalog.load(app),
                this::renderApps,
                failure -> {
                    if (binding != null) {
                        binding.loadingApps.setVisibility(View.GONE);
                        Toast.makeText(this, R.string.settings_apps_unavailable, Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    private void renderApps(List<InstalledAppCatalog.Entry> entries) {
        if (binding == null) return;
        appsLoaded = true;
        binding.loadingApps.setVisibility(View.GONE);
        includedApps =
                new IncludedAppsAdapter(
                        this,
                        entries,
                        includedPackages,
                        (packageName, selected) -> {
                            if (!ControllerPinManager.isDomModeActive()) return;
                            if (selected) includedPackages.add(packageName);
                            else includedPackages.remove(packageName);
                            saveIncludedApps();
                        });
        binding.appList.setAdapter(includedApps);
        includedApps.setEditing(editingUnlocked);
        renderSelectedCount();
    }

    private void renderSelectedCount() {
        String count =
                getResources()
                        .getQuantityString(
                                R.plurals.apps_included_count,
                                includedPackages.size(),
                                includedPackages.size());
        if (binding != null)
            binding.selectedCount.setText(count);
        sections.get("apps").summary().setText(count);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (uiData != null) uiData.close();
        binding = null;
        super.onDestroy();
    }
}
