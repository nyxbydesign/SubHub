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
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemoteSessionRuntime;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private TextView reviewConsent;
    private HardcoreReviewState hardcoreReview;
    private AsyncUiScope consentWork;

    public static final class HardcoreReviewState extends ViewModel {
        RemoteSessionRuntime.HardcoreReview review;
        boolean dialogPending, accepted;
        CompletableFuture<Boolean> accepting;
    }


    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        binding = ActivityGlobalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (showRequestedIncludedApps()) return;
        restoreSubAfterHardcore = saved != null && saved.getBoolean("hardcore_restore_sub", false);
        hardcoreAwaitingAndroid = saved != null && saved.getBoolean("hardcore_awaiting_android", false);
        hardcore = new HardcoreModeManager(this);
        hardcoreReview = new ViewModelProvider(this).get(HardcoreReviewState.class);
        consentWork = AsyncUiScope.forPage(this);
        hardcoreActivation = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
            hardcore.finishActivation();
            completeHardcoreActivation();
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
        if (hardcoreReview.accepting != null) observeHardcoreAcceptance();
        else if (hardcoreReview.dialogPending) binding.getRoot().post(() -> {
            if (hardcoreReview.review != null) showHardcoreConsent();
            else loadHardcoreReview();
        });
        requestedGroup = getIntent().getStringExtra("settings_group");
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> toggleController());
        if (restoreSubAfterHardcore && !hardcoreAwaitingAndroid) finishHardcoreFlow();
        else applyState();
        com.subhub.app.util.InputFocusPolicy.openActivityForReading(this);
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
        LinearLayout device = PersonUi.card(body);
        device.setTag("settings_device");
        role = new DeviceRoleView(this, () -> {
            recreate();
        });
        device.addView(role, new LinearLayout.LayoutParams(-1, -2));
        action(device, R.string.keyholder_home_title, this::openConnectionOrKeyholder)
                .setId(R.id.settings_connections_security);

        heading(body, R.string.device_settings_privacy, "privacy");
        privacy = PersonUi.card(body);
        com.subhub.app.privacy.PrivacyControls.bind(this, privacy, this::applyState);

        heading(body, R.string.device_settings_permissions, "permissions");
        permissionsHeading = body.findViewWithTag("settings:permissions");
        detach(binding.hardcoreCard);
        binding.hardcoreCard.setBackgroundResource(R.drawable.bg_card);
        binding.hardcoreCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        binding.hardcoreCard.getChildAt(0).setVisibility(View.GONE);
        binding.switchHardcoreMode.setOnCheckedChangeListener((control, enabled) -> {
            if (!updatingHardcore) requestHardcoreChange(enabled);
        });
        reviewConsent = PersonUi.action(binding.hardcoreCard, R.string.hardcore_review_consent,
                this::loadHardcoreReview, false);
        reviewConsent.setTag("hardcore_review_consent");
        permissions = PersonUi.card(body);
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
        LinearLayout help = PersonUi.card(body);
        help.setTag("settings_help_actions");
        detach(binding.buttonHelp);
        help.addView(binding.buttonHelp, new LinearLayout.LayoutParams(-1, -2));
        binding.buttonHelp.setOnClickListener(view -> startActivity(new Intent(this, HelpActivity.class)));
        action(help, R.string.settings_updates,
                () -> startActivity(new Intent(this, com.subhub.app.update.UpdatesActivity.class)));
        action(help, R.string.tour_replay,
                () -> startActivity(new Intent(this, com.subhub.app.onboarding.OnboardingActivity.class)
                        .putExtra(com.subhub.app.onboarding.OnboardingActivity.REPLAY, true)));
        action(help, R.string.settings_language,
                () -> startActivity(new Intent(this, HelpActivity.class)
                        .putExtra(HelpActivity.EXTRA_LANGUAGE, true))).setTag("settings_language");
        detach(binding.buttonDiagnostics);
        help.addView(binding.buttonDiagnostics, new LinearLayout.LayoutParams(-1, -2));
        binding.buttonDiagnostics.setOnClickListener(view -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        PersonUi.text(help, getString(R.string.settings_build_summary, com.subhub.app.BuildConfig.VERSION_NAME,
                com.subhub.app.BuildConfig.BUILD_DATE), false).setTag("settings_build_info");
    }

    private void openConnectionOrKeyholder() {
        if (hasAssignedConnection()) com.subhub.app.people.People.connectionUi().openConnection(this, PersonId.LOCAL);
        else startActivity(new Intent(this, com.subhub.app.security.AuthenticatorActivity.class));
    }

    private boolean hasAssignedConnection() {
        try {
            com.subhub.app.security.KeyholderProvider.State state = com.subhub.app.people.People.keyholder().state();
            return !DeviceRolePreferences.isDomWorkspace(this)
                    && (state == com.subhub.app.security.KeyholderProvider.State.READY
                        || state == com.subhub.app.security.KeyholderProvider.State.PENDING)
                    && com.subhub.app.people.People.connectionUi() != null;
        } catch (RuntimeException unavailable) { return false; }
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
        ((TextView) findViewById(R.id.settings_connections_security)).setText(
                hasAssignedConnection() ? R.string.role_connection : R.string.keyholder_home_title);
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
        PrimaryHeader.bindPrimary(binding.getRoot(), R.drawable.ic_tab_settings, R.string.global_settings_title, 0);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        if (requestedGroup != null) {
            String target = "appearance".equals(requestedGroup) || "pacts".equals(requestedGroup) ? "privacy" : requestedGroup;
            requestedGroup = null;
            View anchor = binding.settingsSections.findViewWithTag("settings:" + target);
            if (anchor != null) anchor.post(() -> anchor.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, anchor.getWidth(), anchor.getHeight()), false));
        }
    }
    private boolean localSubConsent() {
        return binding != null && !isFinishing() && !isDestroyed()
                && DeviceRolePreferences.supportsSub(this) && !DeviceRolePreferences.isDomWorkspace(this);
    }

    private void requestHardcoreChange(boolean enabled) {
        refreshHardcoreState();
        if (!localSubConsent()) return;
        if (enabled) { loadHardcoreReview(); return; }
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.hardcore_release_title).setMessage(R.string.hardcore_release_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.hardcore_release, (dialog, ignored) -> {
                    if (!localSubConsent()) return;
                    hardcoreReview.review = null; hardcoreReview.accepted = false;
                    hardcore.disable(); finishHardcoreFlow();
                }).show();
    }

    private void loadHardcoreReview() {
        if (!localSubConsent() || hardcoreReview.accepting != null
                || consentWork.isPending("hardcore-review")) return;
        hardcoreReview.dialogPending = true;
        RemoteFeature backend = RemoteFeature.get(getApplicationContext());
        consentWork.load("hardcore-review", () -> backend.reviewHardcoreStart().get(15, TimeUnit.SECONDS), review -> {
            if (!localSubConsent() || !hardcoreReview.dialogPending) return;
            hardcoreReview.review = review;
            showHardcoreConsent();
        }, failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            android.util.Log.w("HardcoreConsent", "Local review unavailable: " + cause.getClass().getSimpleName());
            hardcoreReview.dialogPending = false; hardcoreReview.review = null;
            if (localSubConsent()) {
                android.widget.Toast.makeText(this, R.string.hardcore_review_unavailable, android.widget.Toast.LENGTH_LONG).show();
                refreshHardcoreState();
            }
        });
    }

    private void showHardcoreConsent() {
        if (!localSubConsent() || !hardcoreReview.dialogPending) return;
        boolean existing = hardcore.isEnabled();
        RemoteSessionRuntime.HardcoreReview reviewed = hardcoreReview.review;
        String scope = reviewed == null ? getString(R.string.hardcore_unpaired_start_consent)
                : getString(R.string.hardcore_remote_start_consent, reviewed.domName);
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(existing ? R.string.hardcore_review_consent : R.string.hardcore_consent_title)
                .setMessage(getString(existing ? R.string.hardcore_review_body : R.string.hardcore_consent_body) + "\n\n" + scope)
                .setNegativeButton(android.R.string.cancel, (dialog, ignored) -> cancelHardcoreReview())
                .setOnCancelListener(ignored -> cancelHardcoreReview())
                .setPositiveButton(existing ? R.string.hardcore_accept_consent : R.string.hardcore_consent_enable,
                        (dialog, ignored) -> {
                    if (!localSubConsent() || hardcoreReview.review != reviewed) { cancelHardcoreReview(); return; }
                    hardcoreReview.dialogPending = false; hardcoreReview.accepted = true;
                    if (hardcore.isEnabled()) { completeHardcoreActivation(); return; }
                    // A linked review must keep its policy revision unchanged through Android's flow.
                    if (reviewed == null && !com.subhub.app.security.SubFeaturePolicy.consentHardcore(this, true)) {
                        cancelHardcoreReview(); return;
                    }
                    hardcore.beginActivation(); hardcoreAwaitingAndroid = true;
                    if (new AppModeManager(this).isAccessibilityEnabled()) hardcoreActivation.launch(hardcore.activationIntent());
                    else hardcoreAccessibility.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).show();
    }

    private void cancelHardcoreReview() {
        hardcoreReview.dialogPending = false; hardcoreReview.accepted = false; hardcoreReview.review = null;
        if (localSubConsent()) refreshHardcoreState();
    }

    private void completeHardcoreActivation() {
        if (!localSubConsent()) { finishHardcoreFlow(); return; }
        if (!hardcore.isEnabled() || !hardcoreReview.accepted) {
            if (hardcore.isEnabled()) android.widget.Toast.makeText(this, R.string.hardcore_review_changed,
                    android.widget.Toast.LENGTH_LONG).show();
            cancelHardcoreReview(); finishHardcoreFlow(); return;
        }
        if (hardcoreReview.review == null) { cancelHardcoreReview(); finishHardcoreFlow(); return; }
        if (hardcoreReview.accepting == null)
            hardcoreReview.accepting = RemoteFeature.get(getApplicationContext()).acceptHardcoreStartReview(hardcoreReview.review);
        observeHardcoreAcceptance();
    }

    private void observeHardcoreAcceptance() {
        CompletableFuture<Boolean> accepted = hardcoreReview.accepting;
        if (accepted == null) return;
        if (localSubConsent()) refreshHardcoreState();
        consentWork.load("hardcore-accept", accepted::get, saved -> {
            hardcoreReview.accepting = null;
            if (!Boolean.TRUE.equals(saved) && localSubConsent()) android.widget.Toast.makeText(this,
                    R.string.hardcore_review_changed, android.widget.Toast.LENGTH_LONG).show();
            cancelHardcoreReview(); finishHardcoreFlow();
        }, failure -> {
            hardcoreReview.accepting = null;
            if (localSubConsent()) android.widget.Toast.makeText(this, R.string.hardcore_review_changed,
                    android.widget.Toast.LENGTH_LONG).show();
            cancelHardcoreReview(); finishHardcoreFlow();
        });
    }

    private void finishHardcoreFlow() {
        if (restoreSubAfterHardcore) ControllerPinManager.enterSubMode();
        restoreSubAfterHardcore = false;
        hardcoreAwaitingAndroid = false;
        if (!hardcore.isEnabled()) com.subhub.app.security.SubFeaturePolicy.consentHardcore(this, false);
        com.subhub.app.security.SubFeaturePolicy.changed(this);
        com.subhub.app.remote.RemoteFeature.get(this).changed();
        if (!isFinishing() && !isDestroyed()) applyState();
    }
    private void refreshHardcoreState() {
        updatingHardcore = true;
        try {
            binding.switchHardcoreMode.setChecked(hardcore.isEnabled() || hardcore.isRequested());
            boolean waiting = hardcoreAwaitingAndroid || hardcoreReview.accepting != null;
            binding.switchHardcoreMode.setEnabled(!waiting);
            reviewConsent.setVisibility(hardcore.isEnabled() ? View.VISIBLE : View.GONE);
            reviewConsent.setEnabled(!waiting);
            int status = hardcoreReview.accepting != null ? R.string.hardcore_review_saving
                    : hardcore.isRequested() && !new AppModeManager(this).isAccessibilityEnabled()
                    ? R.string.hardcore_status_accessibility : hardcore.isRequested() && !hardcore.isGuardReady()
                    ? R.string.hardcore_status_pending : 0;
            binding.hardcoreStatus.setText(status == 0 ? "" : getString(status));
            binding.hardcoreStatus.setVisibility(status == 0 ? View.GONE : View.VISIBLE);
        } finally { updatingHardcore = false; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { binding = null; super.onDestroy(); }
}
