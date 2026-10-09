package com.subhub.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.subhub.app.databinding.ActivityMainBinding;
import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PenanceSnapshot;
import com.subhub.app.penance.TamperTributeReporter;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.permissions.HomePermissionPolicy;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.overlay.CensorPhrases;
import com.subhub.app.settings.SettingsActivity;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.AchievementBadgeView;
import com.subhub.app.stats.AchievementsActivity;
import com.subhub.app.stats.MilestoneManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.security.ProtectionStopPolicy;
import com.subhub.app.util.AppShortcuts;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.PremiumMotion;
import com.subhub.app.util.SubHubNavigation;
import com.subhub.app.subliminal.SubliminalSettings;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Main source UI and explicit permission flow for starting on-device protection. */
public final class MainActivity extends AppCompatActivity {
    public static final String EXTRA_SUPPRESS_PERMISSION_READINESS =
            "com.subhub.app.extra.SUPPRESS_PERMISSION_READINESS";
    private static final long PACT_UNTIL_RELEASED = -1L;
    private ActivityMainBinding binding;
    private TextView editLockButton;
    private MediaProjectionManager projectionManager;
    private ActivityResultLauncher<Intent> projectionPermission;
    private ActivityResultLauncher<Intent> overlayPermission;
    private ActivityResultLauncher<Intent> accessibilityPermission;
    private ActivityResultLauncher<Intent> deviceAdminPermission;
    private ActivityResultLauncher<String> notificationPermission;
    private boolean startFlowAwaitingOverlay;
    private boolean startFlowAwaitingNotification;
    private final EnumSet<HomePermissionPolicy.Requirement> attemptedPermissions =
            EnumSet.noneOf(HomePermissionPolicy.Requirement.class);
    private long selectedPactDurationMs = PACT_UNTIL_RELEASED;
    private long selectedPactMinimumMs, selectedPactMaximumMs;
    private boolean selectedPactHidden;
    private TextView pactSelectionSummary;
    private String achievementPreviewFingerprint = "";
    private final Handler uiTimer = new Handler(Looper.getMainLooper());
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            renderRuntimeState();
            uiTimer.postDelayed(this, 1000L);
        }
    };

    private com.subhub.app.stats.DailyStatsPanel dailyStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (com.subhub.app.onboarding.OnboardingState.shouldStart(this)) {
            startActivity(new Intent(this, com.subhub.app.onboarding.OnboardingActivity.class)); finish(); return;
        }
        if (savedInstanceState != null) {
            selectedPactDurationMs = savedInstanceState.getLong("ux_pact_selection", PACT_UNTIL_RELEASED);
            selectedPactMinimumMs = savedInstanceState.getLong("ux_pact_min", 0);
            selectedPactMaximumMs = savedInstanceState.getLong("ux_pact_max", 0);
            selectedPactHidden = savedInstanceState.getBoolean("ux_pact_hidden", false);
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        dailyStats = new com.subhub.app.stats.DailyStatsPanel(this, true);
        android.widget.LinearLayout homeContent = findViewById(R.id.page_content);
        android.widget.LinearLayout.LayoutParams dailyParams = new android.widget.LinearLayout.LayoutParams(-1, -2); dailyParams.topMargin = dp(12);
        android.widget.LinearLayout serviceWidget = findViewById(R.id.home_service_widget);
        homeContent.removeView(binding.subDashboard);
        binding.subDashboard.setBackground(null); binding.subDashboard.setPadding(0,dp(14),0,0);
        serviceWidget.addView(binding.subDashboard,new android.widget.LinearLayout.LayoutParams(-1,-2));
        homeContent.addView(dailyStats,homeContent.indexOfChild(serviceWidget)+1,dailyParams);
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_tab_home, R.string.app_name,
                0);
        editLockButton = findViewById(R.id.button_edit_lock);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        projectionPermission = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // Persist the approved start before rendering the lock. The foreground
                        // service begins asynchronously, so its static running flag can lag this
                        // callback by one UI frame.
                        new AppModeManager(this).setArmed(true);
                        Intent service = ScreenCaptureService.startIntent(
                                this, result.getResultCode(), result.getData());
                        ContextCompat.startForegroundService(this, service);
                        startSelectedPact();
                        updateProtectionButton(true);
                    } else {
                        showStatus(R.string.capture_cancelled);
                    }
                });
        overlayPermission = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                ignored -> {
                    if (startFlowAwaitingOverlay) {
                        startFlowAwaitingOverlay = false;
                        if (Settings.canDrawOverlays(this)) requestProjection();
                        else showStatus(R.string.overlay_permission_needed);
                    } else {
                        continuePermissionReadinessFlow();
                    }
                });
        accessibilityPermission = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                ignored -> continuePermissionReadinessFlow());
        deviceAdminPermission = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                ignored -> {
                    HardcoreModeManager hardcore = new HardcoreModeManager(this);
                    if (hardcore.isAdminActive()) hardcore.finishActivation();
                    continuePermissionReadinessFlow();
                });
        notificationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                ignored -> {
                    if (startFlowAwaitingNotification) {
                        startFlowAwaitingNotification = false;
                        continueStartFlow();
                    } else {
                        continuePermissionReadinessFlow();
                    }
                });

        binding.buttonProtection.setOnClickListener(this::toggleProtection);
        editLockButton.setOnClickListener(view -> toggleEditSession());
        binding.buttonAccessibilityCapture.setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.HOME);
        binding.buttonCensorSettings.setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
        binding.buttonExport.setOnClickListener(view ->
                startActivity(new Intent(this, ExportActivity.class)));
        binding.buttonStatistics.setOnClickListener(view ->
                startActivity(new Intent(this, StatsActivity.class)));
        binding.achievementsHomeCard.setOnClickListener(view ->
                startActivity(new Intent(this, AchievementsActivity.class)));
        binding.buttonAchievements.setOnClickListener(view ->
                startActivity(new Intent(this, AchievementsActivity.class)));
        binding.buttonCommitmentView.setVisibility(View.GONE);
        binding.commitmentTimer1h.setOnClickListener(view ->
                selectPactDuration(60L * 60L * 1000L));
        binding.commitmentTimer24h.setOnClickListener(view ->
                selectPactDuration(24L * 60L * 60L * 1000L));
        binding.commitmentTimer7d.setOnClickListener(view ->
                selectPactDuration(7L * 24L * 60L * 60L * 1000L));
        binding.commitmentTimer30d.setOnClickListener(view ->
                selectPactDuration(30L * 24L * 60L * 60L * 1000L));
        binding.commitmentTimerPermanent.setOnClickListener(view ->
                selectPactDuration(PACT_UNTIL_RELEASED));
        binding.buttonPenanceView.setOnClickListener(view ->
                startActivity(new Intent(this, PenanceActivity.class)));
        binding.subWalletPay.setOnClickListener(view ->
                startActivity(new Intent(this, PenanceActivity.class)));
        binding.subWalletPause.setOnClickListener(view -> beginPaidPause());
        binding.subCensorCard.setOnClickListener(view -> showArrangementDetails(
                R.string.sub_censor_title, censorArrangementDetails()));
        binding.subLimitsCard.setOnClickListener(view -> showArrangementDetails(
                R.string.sub_limits_title, limitsArrangementDetails()));
        binding.subWalletCard.setOnClickListener(view -> showArrangementDetails(
                R.string.sub_wallet_title, walletArrangementDetails()));
        binding.subAtmosphereCard.setOnClickListener(view -> showArrangementDetails(
                R.string.atmosphere_title, atmosphereArrangementDetails()));
        AppShortcuts.install(this);
        ControllerPinGate.ensureConfigured(this, () -> handleShortcutIntent(getIntent()));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        intent.setAction(Intent.ACTION_MAIN);
        if (AppShortcuts.ACTION_START_PROTECTION.equals(action)) {
            AppShortcuts.reportUsed(this, "start_protection");
            binding.getRoot().post(() -> toggleProtection(binding.buttonProtection));
        }
    }

    private void toggleProtection(View view) {
        if (new PaidPauseManager(this).isActive()) {
            showStatus(R.string.paid_pause_active_protection);
            return;
        }
        FeatureModuleManager featureModules = new FeatureModuleManager(this);
        AppModeManager appMode = new AppModeManager(this);
        boolean stopping = ScreenCaptureService.isRunning() || appMode.isArmed()
                || ScreenshotAccessibilityService.isRecognitionActive();
        if (!stopping && !featureModules.hasRuntimeFeature()) {
            startActivity(new Intent(this, GlobalSettingsActivity.class));
            return;
        }
        if (stopping) {
            ProtectionStopPolicy.Decision stopDecision = ProtectionStopPolicy.decision(this);
            if (stopDecision == ProtectionStopPolicy.Decision.TIMER_LOCKED) {
                TamperTributeReporter.record(this);
                showStatus(R.string.commitment_stop_requires_dom);
                return;
            }
            if (stopDecision == ProtectionStopPolicy.Decision.REQUIRE_CONTROLLER) {
                ControllerPinGate.require(this, () -> toggleProtection(view), false);
                return;
            }
            if (CommitmentManager.isActive(this)
                    && ControllerPinManager.isDomModeActive()) {
                CommitmentManager.emergencyRelease(this);
            }
        }
        if (ScreenCaptureService.isRunning()) {
            startService(ScreenCaptureService.stopIntent(this));
            updateProtectionButton(false);
            return;
        }
        if (appMode.isArmed() || ScreenshotAccessibilityService.isRecognitionActive()) {
            appMode.setArmed(false);
            ResumeNotificationManager.cancel(this);
            updateProtectionButton(false);
            return;
        }
        if (!featureModules.isCensorEnabled()
                || new SettingsRepository(this).loadCaptureMethod() == CaptureMethod.APP_MODE) {
            if (!appMode.isAccessibilityEnabled()) {
                showStatus(R.string.capture_method_enable_accessibility);
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                updateProtectionButton(false);
                return;
            }
            appMode.setArmed(true);
            startSelectedPact();
            ResumeNotificationManager.show(this);
            updateProtectionButton(false);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startFlowAwaitingNotification = true;
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            continueStartFlow();
        }
    }

    private void continueStartFlow() {
        if (!Settings.canDrawOverlays(this)) {
            showStatus(R.string.overlay_permission_needed);
            startFlowAwaitingOverlay = true;
            overlayPermission.launch(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        if (MediaProjectionLeaseRegistry.owner() != null) {
            new AppModeManager(this).setArmed(false);
            updateProtectionButton(false);
            showStatus(R.string.screen_capture_projection_conflict);
            return;
        }
        projectionPermission.launch(projectionManager.createScreenCaptureIntent());
    }

    private void showStatus(int message) {
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    private void selectPactDuration(long durationMillis) {
        if (CommitmentManager.isActive(this)) return;
        if (durationMillis > 0) {
            com.subhub.app.commitment.PactStartDialog.show(this, durationMillis, (minimum, maximum, hidden) -> {
                selectedPactDurationMs = durationMillis;
                selectedPactMinimumMs = minimum; selectedPactMaximumMs = maximum; selectedPactHidden = hidden;
                renderPactSelection();
            });
            return;
        }
        selectedPactDurationMs = durationMillis;
        selectedPactMinimumMs = selectedPactMaximumMs = 0; selectedPactHidden = false;
        renderPactSelection();
    }

    private void startSelectedPact() {
        if (CommitmentManager.isActive(this)) return;
        if (selectedPactDurationMs > 0L) {
            boolean started = CommitmentManager.start(this,
                    selectedPactMinimumMs > 0 ? selectedPactMinimumMs : selectedPactDurationMs,
                    selectedPactMaximumMs > 0 ? selectedPactMaximumMs : selectedPactDurationMs,
                    selectedPactHidden);
            if (!started) {
                new AppModeManager(this).setArmed(false);
                startService(ScreenCaptureService.stopIntent(this));
                showStatus(R.string.pact_start_unavailable);
                return;
            }
            selectedPactDurationMs = PACT_UNTIL_RELEASED;
            selectedPactMinimumMs = selectedPactMaximumMs = 0; selectedPactHidden = false;
        }
        renderCommitmentState();
    }

    private void renderPactSelection() {
        pactButton(binding.commitmentTimer1h, 60L * 60L * 1000L);
        pactButton(binding.commitmentTimer24h, 24L * 60L * 60L * 1000L);
        pactButton(binding.commitmentTimer7d, 7L * 24L * 60L * 60L * 1000L);
        pactButton(binding.commitmentTimer30d, 30L * 24L * 60L * 60L * 1000L);
        pactButton(binding.commitmentTimerPermanent, PACT_UNTIL_RELEASED);
        if (pactSelectionSummary == null) {
            pactSelectionSummary = new TextView(this);
            pactSelectionSummary.setTextColor(getColor(R.color.text_secondary));
            pactSelectionSummary.setPadding(0, dp(8), 0, dp(4));
            binding.commitmentStartPanel.addView(pactSelectionSummary);
        }
        pactSelectionSummary.setVisibility(selectedPactDurationMs > 0 ? View.VISIBLE : View.GONE);
        if (selectedPactDurationMs > 0) {
            long minimum = selectedPactMinimumMs > 0 ? selectedPactMinimumMs : selectedPactDurationMs;
            long maximum = selectedPactMaximumMs > 0 ? selectedPactMaximumMs : selectedPactDurationMs;
            String duration = minimum == maximum ? CommitmentActivity.formatDuration(minimum)
                    : getString(R.string.pact_selection_range, CommitmentActivity.formatDuration(minimum), CommitmentActivity.formatDuration(maximum));
            pactSelectionSummary.setText(getString(R.string.pact_selection_summary, duration,
                    getString(selectedPactHidden ? R.string.pact_selection_hidden : R.string.pact_selection_visible)));
        }
    }

    private void pactButton(TextView button, long duration) {
        boolean selected = selectedPactDurationMs == duration;
        button.setBackgroundResource(selected
                ? R.drawable.bg_home_duration_selected : R.drawable.bg_home_duration_idle);
        button.setTextColor(getColor(selected ? R.color.text_primary : R.color.text_secondary));
    }

    private void renderCommitmentState() {
        if (binding == null) return;
        boolean active = CommitmentManager.isActive(this);
        AppModeManager appMode = new AppModeManager(this);
        CaptureMethod method = new SettingsRepository(this).loadCaptureMethod();
        boolean protectionAvailable = ScreenCaptureService.isRunning()
                || (appMode.isArmed() && (method == CaptureMethod.SCREEN_RECORDING
                        || appMode.isAccessibilityEnabled()));
        if (active && !protectionAvailable) {
            // A process restart or a short Accessibility reconnect must never erase a timed
            // service. Re-arm the stored arrangement and keep the original expiry intact.
            CommitmentManager.reinforceProtection(this);
        }
        binding.commitmentCard.setVisibility(View.VISIBLE);
        binding.commitmentStartPanel.setVisibility(!active ? View.VISIBLE : View.GONE);
        binding.commitmentActivePanel.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) binding.commitmentStatus.setText(CommitmentManager.isCountdownHidden(this)
                ? getString(R.string.pact_time_hidden)
                : getString(R.string.commitment_active_remaining, CommitmentManager.countdownLabel(this)));
        renderPactSelection();
    }

    private void updateProtectionButton(boolean running) {
        PaidPauseManager paidPause = new PaidPauseManager(this);
        if (paidPause.isActive()) {
            binding.buttonProtection.setEnabled(false);
            binding.buttonProtection.setText(R.string.paid_pause_button);
            return;
        }
        boolean appModeRunning = new AppModeManager(this).isArmed()
                || ScreenshotAccessibilityService.isRecognitionActive();
        binding.buttonProtection.setEnabled(true);
        binding.buttonProtection.setText(running || appModeRunning
                ? R.string.stop_protection : R.string.start_protection);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            uiTimer.removeCallbacks(uiTick);
            uiTimer.post(uiTick);
            renderCommitmentState();
            PenanceSnapshot penance = new PenanceManager(this).snapshot(System.currentTimeMillis());
            binding.penanceCard.setVisibility(View.GONE);
            binding.penanceStatus.setText(penance.isEnabled()
                    ? getString(R.string.penance_home_status,
                            new PenanceManager(this).money(penance.getDueCents()),
                            new PenanceManager(this).money(penance.getMercyCents()),
                            new PenanceManager(this).money(penance.getPaidCents()))
                    : getString(R.string.penance_home_inactive));
            showProgressUnlocks(new StatsRepository(this).load());
            renderAchievementsPreview(new StatsRepository(this).load());
            renderEditState();
            renderSubDashboard();
            renderPermissionReadiness();
            HardcoreReadinessNotificationManager.refresh(this);
        }
    }

    private void beginPermissionReadinessFlow(boolean restart) {
        if (restart) attemptedPermissions.clear();
        continuePermissionReadinessFlow();
    }

    private void continuePermissionReadinessFlow() {
        if (binding == null || isFinishing()) return;
        renderPermissionReadiness();
        for (HomePermissionPolicy.Requirement requirement : missingPermissions()) {
            if (!attemptedPermissions.add(requirement)) continue;
            switch (requirement) {
                case ACCESSIBILITY:
                    Toast.makeText(this, R.string.permission_accessibility_guidance,
                            Toast.LENGTH_LONG).show();
                    accessibilityPermission.launch(
                            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                case OVERLAY:
                    overlayPermission.launch(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    return;
                case NOTIFICATIONS:
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                case DEVICE_ADMIN:
                    deviceAdminPermission.launch(
                            new HardcoreModeManager(this).activationIntent());
                    return;
                default:
                    return;
            }
        }
    }

    private List<HomePermissionPolicy.Requirement> missingPermissions() {
        FeatureModuleManager modules = new FeatureModuleManager(this);
        boolean runtimeFeature = modules.hasRuntimeFeature();
        boolean screenRecordingCensor = modules.isCensorEnabled()
                && new SettingsRepository(this).loadCaptureMethod()
                == CaptureMethod.SCREEN_RECORDING;
        boolean notificationPermissionApplies =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        boolean notificationsReady = !notificationPermissionApplies
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        HardcoreModeManager hardcore = new HardcoreModeManager(this);
        return HomePermissionPolicy.missing(
                runtimeFeature,
                screenRecordingCensor,
                notificationPermissionApplies,
                new AppModeManager(this).isAccessibilityEnabled(),
                Settings.canDrawOverlays(this),
                notificationsReady,
                hardcore.isRequested(),
                hardcore.isAdminActive());
    }

    private void renderPermissionReadiness() {
        if (binding == null) return;
        List<HomePermissionPolicy.Requirement> missing = missingPermissions();
        String fingerprint = missing.toString(); if (missing.isEmpty()) fingerprint = "";
        final String state = fingerprint;
        com.subhub.app.onboarding.GlobalNoticeState notices = new com.subhub.app.onboarding.GlobalNoticeState(this);
        com.subhub.app.onboarding.GlobalNoticeState.Kind kind = notices.next(state);
        View host = findViewById(R.id.global_notice_host); host.setTag(kind.name());
        host.setVisibility(kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.NONE ? View.GONE : View.VISIBLE);
        if (kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.NONE) return;
        TextView title = findViewById(R.id.global_notice_title), body = findViewById(R.id.global_notice_body), action = findViewById(R.id.global_notice_action);
        if (kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.PERMISSIONS) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < missing.size(); i++) { if (i > 0) names.append(", "); names.append(getString(permissionName(missing.get(i)))); }
            title.setText(R.string.home_permission_title); body.setText(getString(R.string.home_permission_missing,names.toString())); action.setText(R.string.home_permission_action);
            action.setOnClickListener(v -> beginPermissionReadinessFlow(true));
        } else {
            title.setText(R.string.keyholder_home_title); body.setText(R.string.keyholder_intro_body); action.setText(R.string.keyholder_intro_open);
            action.setOnClickListener(v -> { notices.dismiss(kind,state); renderPermissionReadiness(); startActivity(new Intent(this,com.subhub.app.security.AuthenticatorActivity.class)); });
        }
        findViewById(R.id.global_notice_dismiss).setOnClickListener(v -> { notices.dismiss(kind,state); renderPermissionReadiness(); });
    }

    private int permissionName(HomePermissionPolicy.Requirement requirement) {
        switch (requirement) {
            case ACCESSIBILITY:
                return R.string.permission_accessibility_name;
            case OVERLAY:
                return R.string.permission_overlay_name;
            case NOTIFICATIONS:
                return R.string.permission_notifications_name;
            case DEVICE_ADMIN:
                return R.string.permission_device_admin_name;
            default:
                return R.string.home_permission_title;
        }
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isDomModeActive()) {
            ControllerPinManager.enterSubMode();
            renderEditState();
        } else ControllerPinGate.require(this, this::renderEditState, false);
    }

    private void renderEditState() {
        if (binding == null) return;
        ControllerEditMode.renderButton(this, editLockButton);
        binding.domContent.setVisibility(View.GONE);
        binding.subDashboard.setVisibility(View.VISIBLE);
        // Both spaces use the floating bottom navigation. Keep the final home
        // content fully reachable above it instead of letting Sub Space cards
        // disappear underneath the pill.
        int bottom = dp(116);
        binding.pageContent.setPaddingRelative(binding.pageContent.getPaddingStart(),
                binding.pageContent.getPaddingTop(), binding.pageContent.getPaddingEnd(), bottom);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.HOME);
        renderSubDashboard();
        renderCommitmentState();
        updateProtectionButton(ScreenCaptureService.isRunning());
    }

    private void renderSubDashboard() {
        if (binding == null) return;
        FeatureModuleManager modules = new FeatureModuleManager(this);
        boolean censorEnabled = modules.isCensorEnabled();
        boolean limitsEnabled = modules.isLimitsEnabled();
        boolean walletEnabled = modules.isWalletEnabled();
        boolean subliminalEnabled = modules.isSubliminalEnabled();
        boolean popupEnabled = PopupStormSettings.load(this).isEnabled();
        binding.subCensorCard.setVisibility(censorEnabled ? View.VISIBLE : View.GONE);
        binding.subLimitsCard.setVisibility(limitsEnabled ? View.VISIBLE : View.GONE);
        binding.subWalletCard.setVisibility(walletEnabled ? View.VISIBLE : View.GONE);
        binding.subAtmosphereCard.setVisibility(View.VISIBLE);
        binding.subModulesEmpty.setVisibility(View.GONE);
        int atmosphereCount = (subliminalEnabled ? 1 : 0) + (popupEnabled ? 1 : 0);
        binding.subAtmosphereStatus.setText(getResources().getQuantityString(
                R.plurals.atmosphere_effects_active, atmosphereCount, atmosphereCount));
        binding.buttonProtection.setVisibility(View.VISIBLE);
        binding.protectionStatusRow.setVisibility(View.GONE);
        binding.runtimeStatus.setVisibility(View.GONE);
        binding.modeHint.setVisibility(View.GONE);

        long now = System.currentTimeMillis();
        if (censorEnabled) {
            SettingsRepository settings = new SettingsRepository(this);
            DetectorConfig detector = settings.loadDetectorConfig();
            TextSmutConfig text = settings.loadTextSmutConfig();
            int censorCount = detector.getEnabledCategories().size()
                    + (text.isEnabled() ? text.getEnabledCategories().size() : 0);
            binding.subCensorVoice.setText(getResources().getQuantityString(
                    R.plurals.sub_censors_active, censorCount, censorCount));
        }

        if (limitsEnabled) {
            AppModeManager appMode = new AppModeManager(this);
            AppTimerManager timerManager = new AppTimerManager(this);
            AppTimerManager.Settings timer = timerManager.loadSettings();
            Set<String> timerPackages = appMode.getTimerPackages();
            AppTimerManager.AllowanceSummary allowances =
                    timerManager.summarizeAllowances(timerPackages);
            boolean allApps = appMode.getMode() == AppModePolicy.Mode.ALWAYS;
            int limitCount = !allApps && allowances.isEmpty() ? 0
                    : (timer.totalEnabled ? 1 : 0)
                            + (timer.perAppEnabled ? (allApps ? 1 : allowances.appCount) : 0);
            binding.subLimitsSummary.setText(getResources().getQuantityString(
                    R.plurals.sub_limits_selected, limitCount, limitCount));
        }

        if (walletEnabled) {
            PenanceManager walletManager = new PenanceManager(this);
            PenanceSnapshot wallet = walletManager.snapshot(now);
            int activeRules = 0;
            if (wallet.isEnabled()) {
                for (PenanceInfraction infraction : PenanceInfraction.values()) {
                    if (infraction != PenanceInfraction.PAID_PAUSE
                            && walletManager.isInfractionEnabled(infraction)) activeRules++;
                }
            }
            binding.subWalletVoice.setText(getResources().getQuantityString(
                    R.plurals.sub_wallet_active, activeRules, activeRules));
            boolean checkout = wallet.getCheckoutCents() > 0;
            binding.subWalletPay.setVisibility(wallet.getDueCents() > 0 || checkout
                    ? View.VISIBLE : View.GONE);
            binding.subWalletPay.setText(checkout
                    ? R.string.sub_wallet_resume : R.string.sub_wallet_pay);
            PaidPauseManager paidPause = new PaidPauseManager(this);
            boolean pauseActive = paidPause.isActive();
            boolean pauseAvailable = paidPause.canPurchase();
            binding.subWalletPause.setVisibility(
                    pauseActive || pauseAvailable ? View.VISIBLE : View.GONE);
            binding.subWalletPause.setEnabled(!pauseActive);
            binding.subWalletPause.setText(pauseActive
                    ? getString(R.string.paid_pause_active,
                            CommitmentActivity.formatDuration(paidPause.remainingMillis()))
                    : getString(R.string.paid_pause_buy, paidPause.getDurationMinutes(),
                            new PenanceManager(this).money(paidPause.getPriceCents())));
        }

    }

    private void beginPaidPause() {
        PenanceManager wallet = new PenanceManager(this);
        if (!wallet.requestPaidPause(System.currentTimeMillis())) {
            showStatus(R.string.paid_pause_unavailable);
            return;
        }
        startActivity(new Intent(this, PenanceActivity.class)
                .putExtra(PenanceActivity.EXTRA_BEGIN_PAID_PAUSE, true));
    }

    private void showArrangementDetails(int title, String details) {
        Dialog dialog = new Dialog(this);
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_arrangement_details, null, false);
        ((TextView) content.findViewById(R.id.arrangement_detail_title)).setText(title);
        TextView body = content.findViewById(R.id.arrangement_detail_body);
        body.setText(details);
        body.setMaxHeight(Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f));
        body.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        content.findViewById(R.id.arrangement_detail_close)
                .setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnShowListener(ignored -> {
            Window shown = dialog.getWindow();
            if (shown != null) shown.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(560)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        });
        PremiumMotion.styleDialog(dialog);
        dialog.show();
    }

    String censorArrangementDetails() {
        SettingsRepository repository = new SettingsRepository(this);
        DetectorConfig detector = repository.loadDetectorConfig();
        TextSmutConfig text = repository.loadTextSmutConfig();
        CensorAppearance appearance = repository.loadAppearance();
        AppModeManager appMode = new AppModeManager(this);
        List<String> lines = new ArrayList<>();
        lines.add(detailLine(R.string.arrangement_censor_look,
                getString(R.string.arrangement_censor_look_value,
                        friendlyEffectName(appearance.getType()),
                        appearance.isShowBorder()
                                ? friendlyBorderName(appearance.getBorderEffect())
                                : getString(R.string.arrangement_no_border))));
        lines.add(detailLine(R.string.arrangement_censor_quality,
                friendlyDetectionPreset(repository.loadDetectionPreset())));
        lines.add(detailLine(R.string.arrangement_censor_capture,
                getString(repository.loadCaptureMethod() == CaptureMethod.APP_MODE
                        ? R.string.capture_method_app_mode : R.string.capture_method_recording)));
        lines.add(detailLine(R.string.arrangement_censor_images,
                friendlyCategories(detector.getEnabledCategories())));
        lines.add(detailLine(R.string.arrangement_censor_text,
                text.isEnabled() ? friendlyTextFilter(text) : getString(R.string.popup_off)));
        Set<String> phraseGroups = repository.preferences().getStringSet(
                SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES, CensorPhrases.DEFAULT_ENABLED);
        lines.add(detailLine(R.string.arrangement_censor_phrases,
                appearance.isShowText() ? friendlyPhraseNames(phraseGroups)
                        : getString(R.string.popup_off)));
        lines.add(detailLine(R.string.arrangement_apps,
                appMode.getMode() == com.subhub.app.appmode.AppModePolicy.Mode.ALWAYS
                        ? getString(R.string.arrangement_all_apps)
                        : appLabels(appMode.getSelectedPackages())));
        return joinDetails(lines);
    }

    private String limitsArrangementDetails() {
        AppModeManager appMode = new AppModeManager(this);
        AppTimerManager timers = new AppTimerManager(this);
        AppTimerManager.Settings settings = timers.loadSettings();
        List<String> lines = new ArrayList<>();
        lines.add(detailLine(R.string.arrangement_limits_shared,
                settings.totalEnabled
                        ? getString(R.string.arrangement_minutes, settings.totalMinutes)
                        : getString(R.string.popup_off)));
        Set<String> packages = appMode.getTimerPackages();
        if (appMode.getMode() == AppModePolicy.Mode.ALWAYS) {
            lines.add(detailLine(R.string.arrangement_apps, getString(R.string.arrangement_all_apps)));
            if (settings.perAppEnabled) lines.add(detailLine(R.string.app_timer_default_minutes,
                    getString(R.string.arrangement_minutes, settings.perAppMinutes)));
        }
        if (settings.perAppEnabled && !packages.isEmpty()) {
            List<String> allowances = new ArrayList<>();
            for (String packageName : packages) {
                allowances.add(getString(R.string.arrangement_app_allowance,
                        appLabel(packageName), timers.allowanceMinutes(packageName)));
            }
            Collections.sort(allowances, String.CASE_INSENSITIVE_ORDER);
            lines.add(detailLine(R.string.arrangement_limits_individual,
                    android.text.TextUtils.join("\n", allowances)));
        } else {
            lines.add(detailLine(R.string.arrangement_limits_individual,
                    getString(R.string.popup_off)));
        }
        return joinDetails(lines);
    }

    private String walletArrangementDetails() {
        PenanceManager wallet = new PenanceManager(this);
        PenanceSnapshot snapshot = wallet.snapshot(System.currentTimeMillis());
        List<String> rules = new ArrayList<>();
        for (PenanceInfraction infraction : PenanceInfraction.values()) {
            if (infraction == PenanceInfraction.PAID_PAUSE
                    || !wallet.isInfractionEnabled(infraction)) continue;
            String trigger = friendlyInfraction(infraction);
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                trigger = getString(R.string.arrangement_detection_every,
                        trigger, wallet.getDetectionBatch());
            } else if (infraction == PenanceInfraction.CENSORED_DWELL) {
                trigger = getString(R.string.arrangement_linger_after,
                        trigger, wallet.getDwellSeconds());
            }
            rules.add(getString(R.string.arrangement_tribute_rule,
                    trigger,
                    wallet.money(wallet.getInfractionCents(infraction))));
        }
        List<String> lines = new ArrayList<>();
        lines.add(detailLine(R.string.arrangement_wallet_balance,
                wallet.money(snapshot.getDueCents())));
        lines.add(detailLine(R.string.arrangement_wallet_rules,
                rules.isEmpty() ? getString(R.string.arrangement_none_selected)
                        : android.text.TextUtils.join("\n", rules)));
        lines.add(detailLine(R.string.arrangement_wallet_caps,
                getString(R.string.arrangement_wallet_cap_values,
                        wallet.money(wallet.getDailyCapCents()),
                        wallet.money(wallet.getWeeklyCapCents()))));
        return joinDetails(lines);
    }

    private String subliminalArrangementDetails() {
        AppModeManager appMode = new AppModeManager(this);
        SubliminalSettings settings = new SubliminalSettingsRepository(this).load();
        List<String> voices = new ArrayList<>();
        for (String pack : settings.getEnabledPacks()) voices.add(friendlySubliminalPack(pack));
        Collections.sort(voices, String.CASE_INSENSITIVE_ORDER);
        List<String> lines = new ArrayList<>();
        lines.add(detailLine(R.string.arrangement_subliminal_voice,
                voices.isEmpty() ? getString(R.string.arrangement_none_selected)
                        : android.text.TextUtils.join(", ", voices)));
        lines.add(detailLine(R.string.arrangement_subliminal_intensity,
                friendlyPreset(settings.getPreset())));
        lines.add(detailLine(R.string.arrangement_apps,
                appMode.getMode() == AppModePolicy.Mode.ALWAYS
                        ? getString(R.string.arrangement_all_apps) : appLabels(appMode.getSubliminalPackages())));
        return joinDetails(lines);
    }

    private String atmosphereArrangementDetails() {
        FeatureModuleManager modules = new FeatureModuleManager(this);
        PopupStormSettings popup = PopupStormSettings.load(this);
        SubliminalSettings whispers = new SubliminalSettingsRepository(this).load();
        List<String> lines = new ArrayList<>();
        lines.add(detailLine(R.string.atmosphere_whispers_title,
                modules.isSubliminalEnabled()
                        ? getString(R.string.arrangement_atmosphere_enabled,
                                friendlyPreset(whispers.getPreset()))
                        : getString(R.string.popup_off)));
        lines.add(detailLine(R.string.popup_title,
                popup.isEnabled() ? getString(R.string.atmosphere_state_on)
                        : getString(R.string.popup_off)));
        lines.add(detailLine(R.string.arrangement_apps,
                new AppModeManager(this).getMode() == AppModePolicy.Mode.ALWAYS
                        ? getString(R.string.arrangement_all_apps)
                        : appLabels(new AppModeManager(this).getSubliminalPackages())));
        return joinDetails(lines);
    }

    private String detailLine(int label, String value) {
        return getString(R.string.arrangement_detail_line, getString(label), value);
    }

    private String joinDetails(List<String> lines) {
        return android.text.TextUtils.join("\n\n", lines);
    }

    private String appLabels(Set<String> packages) {
        if (packages == null || packages.isEmpty()) return getString(R.string.arrangement_no_apps);
        List<String> labels = new ArrayList<>();
        for (String packageName : packages) labels.add(appLabel(packageName));
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        return android.text.TextUtils.join(", ", labels);
    }

    private String appLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
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

    private void addCategoryLabel(List<String> labels, Set<String> categories,
            String category, int label) {
        if (categories.contains(category)) labels.add(getString(label));
    }

    private static boolean containsCoveredCategory(Set<String> categories) {
        for (String category : categories) {
            if (category != null && category.endsWith("_covered")) return true;
        }
        return false;
    }

    private String friendlyPhraseNames(Set<String> values) {
        if (values == null || values.isEmpty()) return getString(R.string.arrangement_none_selected);
        List<String> labels = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            switch (value) {
                case "short": labels.add(getString(R.string.phrase_short)); break;
                case "denial": labels.add(getString(R.string.phrase_denial)); break;
                case "humiliation": labels.add(getString(R.string.phrase_humiliation)); break;
                case "edge": labels.add(getString(R.string.phrase_edge)); break;
                case "findom": labels.add(getString(R.string.phrase_findom)); break;
                case "ntr": labels.add(getString(R.string.phrase_ntr)); break;
                case "gooner": labels.add(getString(R.string.phrase_gooner)); break;
                default:
                    String clean = value.replace('_', ' ').trim();
                    if (!clean.isEmpty()) labels.add(clean.substring(0, 1).toUpperCase(
                            java.util.Locale.ROOT) + clean.substring(1));
                    break;
            }
        }
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        return labels.isEmpty() ? getString(R.string.arrangement_none_selected)
                : android.text.TextUtils.join(", ", labels);
    }

    private String friendlyTextFilter(TextSmutConfig config) {
        int sensitivity = config.getSensitivity();
        String level = getString(sensitivity == TextSmutConfig.SENSITIVITY_STRICT
                ? R.string.text_smut_strict
                : sensitivity == TextSmutConfig.SENSITIVITY_BROAD
                        ? R.string.text_smut_broad : R.string.text_smut_balanced);
        return getString(R.string.arrangement_text_value,
                level, friendlyTextCategoryNames(config.getEnabledCategories()));
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
        return labels.isEmpty() ? getString(R.string.arrangement_none_selected)
                : android.text.TextUtils.join(", ", labels);
    }

    private String friendlyInfraction(PenanceInfraction infraction) {
        switch (infraction) {
            case NEW_DETECTION: return getString(R.string.penance_rule_detection_title);
            case CENSORED_DWELL: return getString(R.string.penance_rule_dwell_title);
            case CENSORED_TAP: return getString(R.string.penance_rule_tap_title);
            case WATCHED_APP_OPEN: return getString(R.string.penance_rule_app_open_title);
            case TAMPER_ATTEMPT: return getString(R.string.penance_rule_tamper_title);
            default: return infraction.name();
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
            case PIXELATE: return getString(R.string.style_pixelate);
            case BLUR: return getString(R.string.style_blur);
            case CUSTOM: return getString(R.string.style_custom);
            case STATIC: return getString(R.string.style_static);
            case GLITCH: return getString(R.string.style_glitch);
            case TAPE: return getString(R.string.style_tape);
            case ERROR_POPUP: return getString(R.string.style_error);
            case BOX:
            default: return getString(R.string.style_box);
        }
    }

    private String friendlyBorderName(CensorAppearance.BorderEffect effect) {
        CensorAppearance.BorderEffect resolved = effect == null
                ? CensorAppearance.BorderEffect.CLASSIC : effect;
        switch (resolved) {
            case GLOW: return getString(R.string.border_glow);
            case GRADIENT: return getString(R.string.border_gradient);
            case RAINBOW: return getString(R.string.border_rainbow);
            case CLASSIC:
            default: return getString(R.string.border_classic);
        }
    }

    private String friendlyDetectionPreset(DetectionPreset preset) {
        DetectionPreset resolved = preset == null ? DetectionPreset.MEDIUM : preset;
        switch (resolved) {
            case LOW: return getString(R.string.preset_low);
            case HIGH: return getString(R.string.preset_high);
            case MEDIUM:
            default: return getString(R.string.preset_medium);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        uiTimer.removeCallbacks(uiTick);
        super.onPause();
    }

    private void renderRuntimeState() {
        if (binding == null) return;
        boolean manual = ScreenCaptureService.isRunning();
        boolean automatic = ScreenshotAccessibilityService.isRecognitionActive();
        boolean armed = new AppModeManager(this).isArmed();
        boolean waiting = ScreenshotAccessibilityService.isRunning()
                && new AppModeManager(this).isEffectivelyArmed(System.currentTimeMillis())
                && !automatic;
        updateProtectionButton(manual);

        CaptureMethod method = new SettingsRepository(this).loadCaptureMethod();
        binding.buttonAccessibilityCapture.setText(method == CaptureMethod.APP_MODE
                ? R.string.capture_method_app_mode_button
                : R.string.capture_method_recording_button);

        int status = (manual || automatic) ? R.string.status_active
                : (waiting || armed) ? R.string.status_app_mode_waiting : R.string.status_inactive;
        binding.protectionStatus.setText(status);
        binding.protectionStatus.setTextColor(getColor(
                manual || automatic || waiting ? R.color.accent : R.color.text_muted));
        binding.protectionStatusDot.setAlpha(manual || automatic ? 1f : waiting ? 0.55f : 0.25f);

        DiagnosticsRepository.Snapshot runtime = DiagnosticsRepository.snapshot();
        if (runtime.isRunning() && runtime.isReady()) {
            binding.runtimeStatus.setText(getString(R.string.runtime_ready,
                    runtime.getProvider(), runtime.getModel()));
        } else if (runtime.isRunning() && !"None".equals(runtime.getLastFailure())) {
            binding.runtimeStatus.setText(getString(
                    R.string.runtime_failed, runtime.getLastFailure()));
        } else if (runtime.isRunning()) {
            binding.runtimeStatus.setText(R.string.runtime_initializing);
        } else {
            binding.runtimeStatus.setText(R.string.runtime_idle);
        }

        StatsSnapshot stats = new StatsRepository(this).load();
        renderStats(stats);
        renderAchievementsPreview(stats);
        renderCommitmentState();
        renderSubDashboard();
    }

    private void renderStats(StatsSnapshot stats) {
        if (dailyStats != null) dailyStats.refreshIfNeeded();
    }

    private void renderMetricRows(LinearLayout container, List<Metric> metrics, int columns) {
        android.content.res.Configuration config = getResources().getConfiguration();
        if (config.fontScale >= 1.7f) columns = 1;
        else if (config.screenWidthDp < 380 || config.fontScale >= 1.3f) columns = Math.min(columns, 2);
        container.removeAllViews();
        for (int offset = 0; offset < metrics.size(); offset += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (offset > 0) rowParams.topMargin = dp(8);
            row.setLayoutParams(rowParams);
            for (int column = 0; column < columns; column++) {
                int index = offset + column;
                if (index < metrics.size()) row.addView(metricCard(metrics.get(index), column));
                else {
                    View spacer = new View(this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(
                            0, dp(70), 1f));
                    row.addView(spacer);
                }
            }
            container.addView(row);
        }
    }

    private View metricCard(Metric metric, int column) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        card.setBackgroundResource(R.drawable.bg_home_metric);
        card.setPadding(dp(6), dp(8), dp(6), dp(8));
        card.setMinimumHeight(dp(70));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (column > 0) cardParams.leftMargin = dp(4);
        if (column < 2) cardParams.rightMargin = dp(4);
        card.setLayoutParams(cardParams);

        TextView value = new TextView(this);
        value.setText(metric.value);
        value.setTextAppearance(R.style.Widget_SubHub_HomeStatValue);
        value.setGravity(android.view.Gravity.CENTER);
        value.setSingleLine(false);
        card.addView(value);

        TextView label = new TextView(this);
        label.setText(metric.label);
        label.setTextAppearance(R.style.Widget_SubHub_HomeStatLabel);
        label.setGravity(android.view.Gravity.CENTER);
        label.setTextSize(12f);
        label.setSingleLine(false);
        card.addView(label);
        return card;
    }

    private String formatCount(long value) {
        return String.format(java.util.Locale.getDefault(), "%,d", Math.max(0L, value));
    }

    private static String formatMillis(long millis) {
        return StatsSnapshot.formatDuration(Math.max(0L, millis) / 1_000L);
    }

    private static final class Metric {
        final int label;
        final String value;
        Metric(int label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private void renderAchievementsPreview(StatsSnapshot stats) {
        if (binding == null) return;
        AchievementManager achievements = new AchievementManager(this);
        achievements.checkAchievements(stats);
        AchievementManager.Achievement next = null;
        AchievementManager.Progress nextProgress = null;
        for (AchievementManager.Achievement value : achievements.all()) {
            AchievementManager.Progress progress = achievements.progress(value, stats);
            if (!achievements.isUnlocked(value.getId()) && !value.isHidden()
                    && progress.isCountable()) {
                next = value;
                nextProgress = progress;
                break;
            }
        }
        String fingerprint = achievements.getUnlockedCount() + ":"
                + (next == null ? "complete" : next.getId() + ":"
                + nextProgress.getCurrent() + ":" + nextProgress.getTarget());
        if (fingerprint.equals(achievementPreviewFingerprint)) return;
        achievementPreviewFingerprint = fingerprint;

        binding.achievementsHomeCount.setText(getString(
                R.string.achievements_progress_compact,
                achievements.getUnlockedCount(), achievements.getTotalCount()));
        binding.achievementsHomeBadges.removeAllViews();
        Set<Integer> artwork = new LinkedHashSet<>();
        int shown = 0;
        for (AchievementManager.Achievement value : achievements.all()) {
            if (!artwork.add(value.getBadgeArtRes())) continue;
            boolean unlocked = achievements.isUnlocked(value.getId());
            boolean concealed = value.isHidden() && !unlocked;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setBackgroundResource(R.drawable.bg_achievement_preview_cell);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    dp(92), LinearLayout.LayoutParams.MATCH_PARENT);
            if (shown > 0) cellParams.leftMargin = dp(8);
            cell.setLayoutParams(cellParams);

            AchievementBadgeView badge = new AchievementBadgeView(this);
            badge.bind(value.getBadgeArtRes(), unlocked, concealed,
                    concealed ? getString(R.string.achievement_hidden_name)
                            : getString(value.getName()));
            badge.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(76)));
            cell.addView(badge);

            TextView label = new TextView(this);
            label.setText(concealed ? getString(R.string.achievement_hidden_name)
                    : getString(value.getName()));
            label.setTextColor(getColor(unlocked ? R.color.text_primary : R.color.text_muted));
            label.setTextSize(10);
            label.setGravity(android.view.Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(3);
            label.setLayoutParams(labelParams);
            cell.addView(label);
            binding.achievementsHomeBadges.addView(cell);
            shown++;
            if (shown == 4) break;
        }
        if (next == null) {
            binding.achievementsHomeNext.setText(R.string.achievements_all_complete);
            binding.achievementsHomeProgress.setProgress(100);
            binding.achievementsHomeProgressPercent.setText(
                    getString(R.string.achievements_home_progress_percent, 100));
        } else {
            binding.achievementsHomeNext.setText(getString(R.string.achievements_next_fmt,
                    getString(next.getName()), nextProgress.getCurrent() + " / "
                            + nextProgress.getTarget()));
            binding.achievementsHomeProgress.setProgress(nextProgress.percent());
            binding.achievementsHomeProgressPercent.setText(getString(
                    R.string.achievements_home_progress_percent, nextProgress.percent()));
        }
    }

    private void showProgressUnlocks(StatsSnapshot stats) {
        AchievementManager achievements = new AchievementManager(this);
        achievements.checkAchievements(stats);
        java.util.List<AchievementManager.Achievement> unlocked =
                achievements.takePendingNotifications();
        if (!unlocked.isEmpty()) {
            AchievementManager.Achievement first = unlocked.get(0);
            showAchievementUnlock(first, unlocked.size() - 1);
            return;
        }
        MilestoneManager.Result milestone = MilestoneManager.takeUnseen(this, stats.getTotalBlocks());
        if (milestone != null) {
            Snackbar notice = Snackbar.make(binding.getRoot(), milestone.getMessage(), Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.getRoot().findViewById(R.id.bottom_navigation));
            notice.setAnchorViewLayoutListenerEnabled(true);
            notice.show();
        }
    }

    private void showAchievementUnlock(AchievementManager.Achievement achievement,
            int additionalUnlocks) {
        int count = additionalUnlocks + 1;
        String message = additionalUnlocks == 0
                ? getString(R.string.achievement_notice_single, getString(achievement.getName()))
                : getResources().getQuantityString(R.plurals.achievement_notice_count, count, count);
        Snackbar notice = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.getRoot().findViewById(R.id.bottom_navigation))
                .setAction(R.string.achievement_notice_view,
                        view -> startActivity(new Intent(this, AchievementsActivity.class)));
        notice.setAnchorViewLayoutListenerEnabled(true);
        notice.show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("ux_pact_selection", selectedPactDurationMs);
        state.putLong("ux_pact_min", selectedPactMinimumMs);
        state.putLong("ux_pact_max", selectedPactMaximumMs);
        state.putBoolean("ux_pact_hidden", selectedPactHidden);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
