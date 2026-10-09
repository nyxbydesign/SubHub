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
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared app selection, direct privacy/access controls and build/support information. */
public final class GlobalSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_SHOW_INCLUDED_APPS = "show_included_apps";
    private ActivityGlobalSettingsBinding binding;
    private final Map<String, com.subhub.app.util.ExpandableSectionView> sections =
            new LinkedHashMap<>();
    private LinearLayout categoryMenu, privacyControls;
    private String selectedGroup = "";

    private HardcoreModeManager hardcore;
    private AppModeManager appMode;
    private ActivityResultLauncher<Intent> hardcoreActivation;
    private ActivityResultLauncher<Intent> hardcoreAccessibility;
    private boolean updatingHardcore;
    private boolean editingUnlocked;
    private final java.util.List<View> domActions = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGlobalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(
                binding.getRoot(), R.drawable.ic_tab_settings, R.string.global_settings_title, 0);
        arrangeSettingsSections();
        if (savedInstanceState != null)
            selectedGroup = savedInstanceState.getString("expanded_settings", "");
        hardcore = new HardcoreModeManager(this);
        appMode = new AppModeManager(this);
        binding.appListCard.setCountOnlyForOverride(true);
        binding.appListCard.bind(this, ControllerPinManager::isDomModeActive, this::renderSelectedCount);
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
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        binding.buttonHelp.setOnClickListener(
                view -> startActivity(new Intent(this, HelpActivity.class)));
        binding.buttonDiagnostics.setOnClickListener(
                view -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        binding.switchHardcoreMode.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updatingHardcore) changeHardcoreMode(checked);
                });
        binding.buttonAccessibilitySettings.setOnClickListener(
                view -> ControllerPinGate.require(this,
                        () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), false));
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
        addGroup(SettingsSection.APPS, binding.appsCard);
        privacyControls = new LinearLayout(this);
        privacyControls.setOrientation(LinearLayout.VERTICAL);
        binding.hardcoreCard.setBackground(null);
        binding.hardcoreCard.setPadding(0, dp(8), 0, dp(8));
        binding.hardcoreCard.getChildAt(0).setVisibility(View.GONE);
        addGroup(SettingsSection.PRIVACY_PERMISSIONS, privacyControls, binding.hardcoreCard, binding.androidAccessCard);
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
        com.subhub.app.util.SelectionGrid helpActions = new com.subhub.app.util.SelectionGrid(this, null);
        addGroup(SettingsSection.HELP, helpActions);
        addHelpAction(helpActions, binding.buttonHelp);
        addHelpAction(helpActions, settingsAction(getString(R.string.tour_replay),
                () -> startActivity(new Intent(this, com.subhub.app.onboarding.OnboardingActivity.class)
                        .putExtra(com.subhub.app.onboarding.OnboardingActivity.REPLAY, true))));
        addHelpAction(helpActions, settingsAction(getString(R.string.settings_updates),
                () -> startActivity(new Intent(this, com.subhub.app.update.UpdatesActivity.class))));
        addHelpAction(helpActions, settingsAction(getString(R.string.diagnostics_lab_title),
                () -> startActivity(new Intent(this, DiagnosticsActivity.class)
                        .putExtra(DiagnosticsActivity.EXTRA_SHOW_CENSOR_LAB, true))));
        addHelpAction(helpActions, binding.buttonDiagnostics);
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
            selectedGroup = "privacy";
        if (selectedGroup.equals("permissions")) selectedGroup = "privacy";
    }

    private void addHelpAction(com.subhub.app.util.SelectionGrid actions, View action) {
        if (action.getParent() != null) ((android.view.ViewGroup) action.getParent()).removeView(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        actions.addView(action, params);
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
        TextView control = settingsAction(title, () -> ControllerPinGate.require(this, action, false));
        domActions.add(control);
        return control;
    }

    private void addGroup(SettingsSection definition, View... controls) {
        String key = definition.key;
        com.subhub.app.util.ExpandableSectionView section =
                new com.subhub.app.util.ExpandableSectionView(
                        this, key, definition.title, definition.icon);
        section.addControls(controls);
        boolean collapsible = key.equals("apps");
        section.setCollapsible(collapsible);
        section.setSummaryVisible(!key.equals("privacy"));
        section.setSummaryWhenExpanded(key.equals("apps"));
        sections.put(key, section);
        if (collapsible) section.header()
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
        if (selectedGroup.equals("apps")) binding.appListCard.load();
        com.subhub.app.privacy.PrivacyControls.bind(
                    this, privacyControls, this::displayGroup);
        if (appMode == null) return;
        renderSelectedCount();
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
                                                : R.string.atmosphere_state_off)));
        sections.get("help").summary().setText(getString(R.string.settings_build_summary,
                com.subhub.app.BuildConfig.VERSION_NAME, com.subhub.app.BuildConfig.BUILD_DATE));
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
        if (selectedGroup.equals("apps")) binding.appListCard.reload();
        HardcoreReadinessNotificationManager.refresh(this);
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            ControllerPinManager.enterSubMode();
            applyEditState();
            SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        } else ControllerPinGate.unlock(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        editingUnlocked = ControllerPinManager.isSessionUnlocked();
        applySpaceVisibility();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.switchHardcoreMode.setEnabled(true);
        binding.buttonAccessibilitySettings.setEnabled(true);
        ControllerPinGate.markLocked(binding.switchHardcoreMode);
        ControllerPinGate.markLocked(binding.buttonAccessibilitySettings);
        ControllerPinGate.markLocked(sections.get("apps").header());
        for (View control : domActions) ControllerPinGate.markLocked(control);
        binding.appListCard.refresh();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        refreshHardcoreState();
        refreshAccessState();
    }

    private void applySpaceVisibility() {
        boolean domSpace = ControllerPinManager.isDomModeActive();
        int domVisibility = domSpace ? View.VISIBLE : View.GONE;
        binding.settingsGroupProtection.setVisibility(domVisibility);
        binding.hardcoreCard.setVisibility(View.VISIBLE);
        binding.settingsGroupCoverage.setVisibility(domVisibility);
        binding.appsCard.setVisibility(domVisibility);
        binding.androidAccessCard.setVisibility(View.VISIBLE);
        binding.appListCard.setVisibility(domVisibility);
        binding.buttonHelp.setVisibility(View.VISIBLE);
        binding.buttonDiagnostics.setVisibility(View.VISIBLE);
        binding.settingsGroupServices.setVisibility(View.VISIBLE);
        binding.appSettingsCard.setVisibility(View.VISIBLE);
        displayGroup();
    }

    private void refreshAccessState() {
        if (binding == null || appMode == null) return;
        boolean connected = appMode.isAccessibilityEnabled() && ScreenshotAccessibilityService.isRunning();
        binding.serviceStatus.setVisibility(connected ? View.GONE : View.VISIBLE);
        binding.serviceStatus.setText(connected ? "" : getString(appMode.isAccessibilityEnabled()
                ? R.string.apps_accessibility_reconnecting : R.string.apps_accessibility_off));
    }

    private void changeHardcoreMode(boolean enabled) {
        if (!ControllerPinManager.isDomModeActive()) {
            ControllerPinGate.notifyLocked(this);
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
                                if (!ControllerPinManager.isDomModeActive()) {
                                    ControllerPinGate.notifyLocked(this); refreshHardcoreState(); return;
                                }
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
                                if (!ControllerPinManager.isDomModeActive()) {
                                    ControllerPinGate.notifyLocked(this); refreshHardcoreState(); return;
                                }
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
            binding.hardcoreStatus.setText("");
        } else if (hardcore.isRequested()) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_pending);
        } else {
            binding.hardcoreStatus.setText("");
        }
        binding.hardcoreStatus.setVisibility(binding.hardcoreStatus.length() == 0 ? View.GONE : View.VISIBLE);
        updatingHardcore = false;
    }

    private void renderSelectedCount() {
        if (binding != null && sections.containsKey("apps"))
            sections.get("apps").summary().setText(binding.appListCard.selectionSummary());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
