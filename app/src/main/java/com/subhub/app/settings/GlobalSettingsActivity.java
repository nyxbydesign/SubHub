package com.subhub.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.databinding.ActivityGlobalSettingsBinding;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.people.LocalAppsActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.DeviceRoleView;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

/** Device role, privacy and Android access settings are grouped inline. */
public final class GlobalSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_SHOW_INCLUDED_APPS = "show_included_apps";
    private ActivityGlobalSettingsBinding binding;
    private DeviceRoleView role;
    private LinearLayout privacy, permissions, localPermissions;
    private View permissionsHeading;
    private HardcoreModeManager hardcore;
    private ActivityResultLauncher<Intent> hardcoreActivation, hardcoreAccessibility;
    private boolean updatingHardcore, restoreSubAfterHardcore, hardcoreAwaitingAndroid;
    private String requestedGroup;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        binding = ActivityGlobalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (showRequestedIncludedApps()) return;
        restoreSubAfterHardcore = saved != null && saved.getBoolean("hardcore_restore_sub", false);
        hardcoreAwaitingAndroid = saved != null && saved.getBoolean("hardcore_awaiting_android", false);
        hardcore = new HardcoreModeManager(this);
        hardcoreActivation = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
            hardcore.finishActivation();
            finishHardcoreFlow();
        });
        hardcoreAccessibility = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
            if (new AppModeManager(this).isAccessibilityEnabled()) hardcoreActivation.launch(hardcore.activationIntent());
            else {
                hardcore.cancelPendingActivation();
                android.widget.Toast.makeText(this, R.string.hardcore_accessibility_cancelled, android.widget.Toast.LENGTH_LONG).show();
                finishHardcoreFlow();
            }
        });
        arrangeSettings();
        requestedGroup = getIntent().getStringExtra("settings_group");
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> toggleController());
        if (restoreSubAfterHardcore && !hardcoreAwaitingAndroid) finishHardcoreFlow();
        else applyState();
    }

    @Override protected void onSaveInstanceState(Bundle output) {
        output.putBoolean("hardcore_restore_sub", restoreSubAfterHardcore);
        output.putBoolean("hardcore_awaiting_android", hardcoreAwaitingAndroid);
        super.onSaveInstanceState(output);
    }

    private void arrangeSettings() {
        LinearLayout body = binding.settingsSections;
        View header = PrimaryHeader.view(binding.getRoot());
        detach(header);
        body.removeAllViews();
        body.addView(header);
        role = new DeviceRoleView(this, () -> {
            recreate();
        });
        body.addView(role, new LinearLayout.LayoutParams(-1, -2));
        action(body, R.string.keyholder_home_title,
                () -> startActivity(new Intent(this, com.subhub.app.security.AuthenticatorActivity.class)))
                .setId(R.id.settings_connections_security);

        heading(body, R.string.device_settings_privacy, "privacy");
        privacy = PersonUi.column(this);
        body.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        com.subhub.app.privacy.PrivacyControls.bind(this, privacy, this::applyState);

        heading(body, R.string.device_settings_permissions, "permissions");
        permissionsHeading = body.findViewWithTag("settings:permissions");
        detach(binding.hardcoreCard);
        binding.hardcoreCard.setBackground(null);
        binding.hardcoreCard.setPadding(0, dp(12), 0, dp(8));
        binding.hardcoreCard.getChildAt(0).setVisibility(View.GONE);
        binding.switchHardcoreMode.setOnCheckedChangeListener((control, enabled) -> {
            if (!updatingHardcore) requestHardcoreChange(enabled);
        });
        permissions = PersonUi.column(this);
        body.addView(permissions, new LinearLayout.LayoutParams(-1, -2));
        action(permissions, R.string.settings_notifications,
                () -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        localPermissions = PersonUi.column(this);
        permissions.addView(localPermissions, new LinearLayout.LayoutParams(-1, -2));
        localPermissions.setId(R.id.android_access_card);
        detach(binding.buttonAccessibilitySettings);
        localPermissions.addView(binding.buttonAccessibilitySettings, new LinearLayout.LayoutParams(-1, -2));
        binding.buttonAccessibilitySettings.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        detach(binding.serviceStatus);
        localPermissions.addView(binding.serviceStatus, new LinearLayout.LayoutParams(-1, -2));
        action(localPermissions, R.string.settings_overlay,
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()))));
        action(localPermissions, R.string.settings_battery,
                () -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        heading(body, R.string.device_settings_help, "help");
        LinearLayout help = PersonUi.column(this);
        help.setTag("settings_help_actions");
        body.addView(help, new LinearLayout.LayoutParams(-1, -2));
        detach(binding.buttonHelp);
        help.addView(binding.buttonHelp, new LinearLayout.LayoutParams(-1, -2));
        binding.buttonHelp.setOnClickListener(view -> startActivity(new Intent(this, HelpActivity.class)));
        action(help, R.string.settings_updates,
                () -> startActivity(new Intent(this, com.subhub.app.update.UpdatesActivity.class)));
        action(help, R.string.tour_replay,
                () -> startActivity(new Intent(this, com.subhub.app.onboarding.OnboardingActivity.class)
                        .putExtra(com.subhub.app.onboarding.OnboardingActivity.REPLAY, true)));
        detach(binding.buttonDiagnostics);
        help.addView(binding.buttonDiagnostics, new LinearLayout.LayoutParams(-1, -2));
        binding.buttonDiagnostics.setOnClickListener(view -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        PersonUi.text(help, getString(R.string.settings_build_summary, com.subhub.app.BuildConfig.VERSION_NAME,
                com.subhub.app.BuildConfig.BUILD_DATE), false).setTag("settings_build_info");
    }

    private void heading(LinearLayout parent, int title, String key) {
        TextView text = PersonUi.text(parent, getString(title), true);
        text.setTag("settings:" + key);
        text.setPadding(0, dp(20), 0, dp(6));
        androidx.core.view.ViewCompat.setAccessibilityHeading(text, true);
    }
    private TextView action(LinearLayout parent, int label, Runnable open) {
        TextView view = PersonUi.action(parent, label, open, false);
        view.setMinimumHeight(dp(48));
        return view;
    }
    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private boolean showRequestedIncludedApps() {
        boolean requested = getIntent().getBooleanExtra(EXTRA_SHOW_INCLUDED_APPS, false)
                || "apps".equals(getIntent().getStringExtra("settings_group"));
        if (!requested) return false;
        getIntent().removeExtra(EXTRA_SHOW_INCLUDED_APPS);
        getIntent().removeExtra("settings_group");
        if (!DeviceRolePreferences.supportsSub(this)) return false;
        if (!getIntent().hasExtra(PersonRoutes.EXTRA_PERSON_ID)
                || PersonId.LOCAL.value().equals(getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID))) {
            startActivity(new Intent(this, LocalAppsActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
        }
        finish();
        return true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (showRequestedIncludedApps()) return;
        requestedGroup = intent.getStringExtra("settings_group");
        applyState();
    }
    @Override protected void onResume() {
        super.onResume();
        if (binding == null || isFinishing()) return;
        applyState();
        HardcoreReadinessNotificationManager.refresh(this);
    }

    private void toggleController() {
        if (ControllerPinManager.isDomModeActive()) {
            ControllerPinManager.enterSubMode();
            applyState();
        } else ControllerPinGate.unlock(this, this::applyState, false);
    }
    private void applyState() {
        if (binding == null || role == null) return;
        role.refresh();
        boolean local = DeviceRolePreferences.supportsSub(this);
        localPermissions.setVisibility(local ? View.VISIBLE : View.GONE);
        detach(binding.hardcoreCard);
        if (local && !DeviceRolePreferences.isDomWorkspace(this)) {
            int position = binding.settingsSections.indexOfChild(permissionsHeading);
            binding.settingsSections.addView(binding.hardcoreCard, position, new LinearLayout.LayoutParams(-1, -2));
            refreshHardcoreState();
        }
        boolean connected = new AppModeManager(this).isAccessibilityEnabled() && ScreenshotAccessibilityService.isRunning();
        binding.serviceStatus.setText(connected ? "" : getString(new AppModeManager(this).isAccessibilityEnabled()
                ? R.string.apps_accessibility_reconnecting : R.string.apps_accessibility_off));
        binding.serviceStatus.setVisibility(local && !connected ? View.VISIBLE : View.GONE);
        com.subhub.app.privacy.PrivacyControls.bind(this, privacy, this::applyState);
        PrimaryHeader.backButton(binding.getRoot()).setVisibility(View.GONE);
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_tab_settings, R.string.global_settings_title, 0);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        if (requestedGroup != null) {
            String target = "appearance".equals(requestedGroup) || "pacts".equals(requestedGroup) ? "privacy" : requestedGroup;
            requestedGroup = null;
            View anchor = binding.settingsSections.findViewWithTag("settings:" + target);
            if (anchor != null) anchor.post(() -> anchor.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, anchor.getWidth(), anchor.getHeight()), false));
        }
    }
    private void requestHardcoreChange(boolean enabled) {
        refreshHardcoreState();
        if (!DeviceRolePreferences.supportsSub(this) || DeviceRolePreferences.isDomWorkspace(this)) return;
        boolean wasUnlocked = ControllerPinManager.isDomModeActive();
        ControllerPinGate.authenticate(this, () -> {
            restoreSubAfterHardcore = !wasUnlocked;
            applyState();
            if (!ControllerPinManager.canEditConfiguration()) {
                ControllerPinGate.notifyLocked(this);
                finishHardcoreFlow();
                return;
            }
            androidx.appcompat.app.AlertDialog.Builder dialog = com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(enabled ? R.string.hardcore_consent_title : R.string.hardcore_release_title)
                    .setMessage(enabled ? R.string.hardcore_consent_body : R.string.hardcore_release_body)
                    .setNegativeButton(android.R.string.cancel, (which, ignored) -> finishHardcoreFlow())
                    .setOnCancelListener(ignored -> finishHardcoreFlow())
                    .setPositiveButton(enabled ? R.string.hardcore_consent_enable : R.string.hardcore_release, (which, ignored) -> {
                        if (!ControllerPinManager.canEditConfiguration() || !DeviceRolePreferences.supportsSub(this)) {
                            ControllerPinGate.notifyLocked(this); finishHardcoreFlow(); return;
                        }
                        if (enabled) {
                            hardcore.beginActivation();
                            hardcoreAwaitingAndroid = true;
                            if (new AppModeManager(this).isAccessibilityEnabled()) hardcoreActivation.launch(hardcore.activationIntent());
                            else hardcoreAccessibility.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } else {
                            hardcore.disable();
                            finishHardcoreFlow();
                        }
                    });
            dialog.show();
        }, false);
    }
    private void finishHardcoreFlow() {
        if (restoreSubAfterHardcore) ControllerPinManager.enterSubMode();
        restoreSubAfterHardcore = false;
        hardcoreAwaitingAndroid = false;
        if (!isFinishing() && !isDestroyed()) applyState();
    }
    private void refreshHardcoreState() {
        updatingHardcore = true;
        try {
            binding.switchHardcoreMode.setChecked(hardcore.isEnabled() || hardcore.isRequested());
            ControllerPinGate.markLocked(binding.switchHardcoreMode);
            int status = hardcore.isRequested() && !new AppModeManager(this).isAccessibilityEnabled()
                    ? R.string.hardcore_status_accessibility : hardcore.isRequested() && !hardcore.isGuardReady()
                    ? R.string.hardcore_status_pending : 0;
            binding.hardcoreStatus.setText(status == 0 ? "" : getString(status));
            binding.hardcoreStatus.setVisibility(status == 0 ? View.GONE : View.VISIBLE);
        } finally { updatingHardcore = false; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { binding = null; super.onDestroy(); }
}
