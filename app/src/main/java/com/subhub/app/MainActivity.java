package com.subhub.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.databinding.ActivityMainBinding;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceSnapshot;
import com.subhub.app.permissions.HomePermissionPolicy;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.AchievementsActivity;
import com.subhub.app.stats.MilestoneManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.util.AppShortcuts;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Main source UI and explicit permission flow for starting on-device protection. */
public final class MainActivity extends AppCompatActivity {
    public static final String EXTRA_SUPPRESS_PERMISSION_READINESS =
            "com.subhub.app.extra.SUPPRESS_PERMISSION_READINESS";
    public static final String EXTRA_LOCAL_SESSION = "com.subhub.app.extra.LOCAL_SESSION";
    private ActivityMainBinding binding;
    private TextView editLockButton;
    private int durationDisplay = -1;
    private MediaProjectionManager projectionManager;
    private ActivityResultLauncher<Intent> projectionPermission;
    private ActivityResultLauncher<Intent> overlayPermission;
    private ActivityResultLauncher<Intent> accessibilityPermission;
    private ActivityResultLauncher<Intent> deviceAdminPermission;
    private ActivityResultLauncher<String> notificationPermission;
    private boolean startFlowAwaitingOverlay;
    private boolean startFlowAwaitingNotification;
    private boolean localSessionPage;
    private final EnumSet<HomePermissionPolicy.Requirement> attemptedPermissions =
            EnumSet.noneOf(HomePermissionPolicy.Requirement.class);
    private com.subhub.app.util.AsyncUiScope uiData;
    private final java.util.List<android.content.SharedPreferences> achievementSources =
            new java.util.ArrayList<>();
    private boolean achievementsDirty = true, uiActive, notifyProgressOnResume;
    private long achievementSeconds = Long.MIN_VALUE;
    private String achievementDay = "";
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            achievementChanged = (preferences, key) -> achievementsDirty = true;
    private final Handler uiTimer = new Handler(Looper.getMainLooper());
    private final Runnable uiTick =
            new Runnable() {
                @Override
                public void run() {
                    renderRuntimeState();
                    uiTimer.postDelayed(this, 1000L);
                }
            };

    private com.subhub.app.people.RoleHomePresenter roleHome;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!com.subhub.app.people.PersonRoutes.isLocalTarget(getIntent())) {
            finish();
            return;
        }
        localSessionPage = getIntent().getBooleanExtra(EXTRA_LOCAL_SESSION, false);
        if (com.subhub.app.onboarding.OnboardingState.shouldStart(this)) {
            startActivity(
                    new Intent(this, com.subhub.app.onboarding.OnboardingActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            finish();
            return;
        }
        uiData = com.subhub.app.util.AsyncUiScope.forPage(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        roleHome = new com.subhub.app.people.RoleHomePresenter(this, uiData);
        roleHome.setSessionPage(localSessionPage);
        binding.commitmentStartPanel.setEditable(false);
        PrimaryHeader.bind(binding.getRoot(), 0, R.string.role_overview, 0);
        editLockButton = findViewById(R.id.button_edit_lock);
        projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        projectionPermission =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == Activity.RESULT_OK
                                    && result.getData() != null) {
                                // Persist the approved start before rendering the lock. The
                                // foreground
                                // service begins asynchronously, so its static running flag can lag
                                // this
                                // callback by one UI frame.
                                new AppModeManager(this).setArmed(true);
                                Intent service =
                                        ScreenCaptureService.startIntent(
                                                this, result.getResultCode(), result.getData());
                                ContextCompat.startForegroundService(this, service);
                                startSelectedPact();
                                updateProtectionButton(true);
                            } else {
                                showStatus(R.string.capture_cancelled);
                            }
                        });
        overlayPermission =
                registerForActivityResult(
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
        accessibilityPermission =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        ignored -> continuePermissionReadinessFlow());
        deviceAdminPermission =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        ignored -> {
                            HardcoreModeManager hardcore = new HardcoreModeManager(this);
                            if (hardcore.isAdminActive()) hardcore.finishActivation();
                            continuePermissionReadinessFlow();
                        });
        notificationPermission =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        ignored -> {
                            if (startFlowAwaitingNotification) {
                                startFlowAwaitingNotification = false;
                                continueStartFlow();
                            } else {
                                continuePermissionReadinessFlow();
                            }
                        });

        binding.commitmentCard.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (durationDisplay > 0 && right - left != oldRight - oldLeft)
                binding.commitmentCard.post(this::sizeServiceCountdown);
        });
        binding.buttonProtection.setOnClickListener(this::toggleProtection);
        editLockButton.setOnClickListener(view -> toggleEditSession());
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.HOME);
        binding.subWalletPay.setOnClickListener(
                view -> startActivity(new Intent(this, PenanceActivity.class)));
        binding.subWalletPause.setOnClickListener(view -> beginPaidPause());
        View.OnClickListener openContract = view -> startActivity(new Intent(this,
                com.subhub.app.people.ContractActivity.class));
        binding.subCensorCard.setOnClickListener(openContract);
        binding.subLimitsCard.setOnClickListener(openContract);
        binding.subWalletCard.setOnClickListener(openContract);
        binding.subAtmosphereCard.setOnClickListener(openContract);
        AppShortcuts.install(this);
        ControllerPinGate.ensureConfigured(this, () -> handleShortcutIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!com.subhub.app.people.PersonRoutes.isLocalTarget(intent)) {
            finish();
            return;
        }
        localSessionPage = intent.getBooleanExtra(EXTRA_LOCAL_SESSION, false);
        if (roleHome != null) roleHome.setSessionPage(localSessionPage);
        handleShortcutIntent(intent);
        renderEditState();
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
        FeatureModuleManager featureModules = new FeatureModuleManager(this);
        AppModeManager appMode = new AppModeManager(this);
        boolean stopping =
                CommitmentManager.isActive(this)
                        || new PaidPauseManager(this).isActive()
                        || ScreenCaptureService.isRunning()
                        || appMode.isArmed()
                        || ScreenshotAccessibilityService.isRecognitionActive();
        if (!stopping && !featureModules.hasRuntimeFeature()) {
            startActivity(new Intent(this, GlobalSettingsActivity.class));
            return;
        }
        if (stopping) {
            return;
        }
        if (!binding.commitmentStartPanel.validateSelection()) return;
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
            overlayPermission.launch(
                    new Intent(
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

    private void startSelectedPact() {
        if (CommitmentManager.isActive(this)) return;
        com.subhub.app.commitment.ServiceDurationView.Selection selection =
                binding.commitmentStartPanel.selection();
        if (selection.minimum > 0) {
            boolean started =
                    CommitmentManager.start(
                            this, selection.minimum, selection.maximum, selection.hidden);
            if (!started) {
                new AppModeManager(this).setArmed(false);
                startService(ScreenCaptureService.stopIntent(this));
                showStatus(R.string.pact_start_unavailable);
                return;
            }
        }
        renderCommitmentState();
        if (localSessionPage && ControllerPinManager.isDomModeActive()
                && com.subhub.app.people.LocalSessionControls.isActive(this)) finish();
    }

    private void renderCommitmentState() {
        if (binding == null) return;
        boolean active = CommitmentManager.isActive(this);
        AppModeManager appMode = new AppModeManager(this);
        CaptureMethod method = new SettingsRepository(this).loadCaptureMethod();
        boolean protectionAvailable =
                ScreenCaptureService.isRunning()
                        || (appMode.isArmed()
                                && (method == CaptureMethod.SCREEN_RECORDING
                                        || appMode.isAccessibilityEnabled()));
        if (active && !protectionAvailable) {
            // A process restart or a short Accessibility reconnect must never erase a timed
            // service. Re-arm the stored arrangement and keep the original expiry intact.
            CommitmentManager.reinforceProtection(this);
        }
        boolean service = active || appMode.isArmed() || ScreenCaptureService.isRunning()
                || ScreenshotAccessibilityService.isRecognitionActive() || new PaidPauseManager(this).isActive();
        int next = !service ? 0 : active ? 1 : 2;
        boolean animate = durationDisplay >= 0 && durationDisplay != next
                && binding.commitmentCard.isLaidOut() && android.animation.ValueAnimator.areAnimatorsEnabled();
        if (animate) {
            android.transition.TransitionSet change = new android.transition.TransitionSet()
                    .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
                    .addTransition(new android.transition.ChangeBounds())
                    .addTransition(new android.transition.Fade()).setDuration(380);
            android.transition.TransitionManager.beginDelayedTransition(binding.commitmentCard, change);
        }
        if (next != 0 && durationDisplay != next) sizeServiceCountdown();
        binding.commitmentCard.setVisibility(View.VISIBLE);
        binding.commitmentStartPanel.setVisibility(View.GONE);
        TextView configured = findViewById(R.id.service_configured_duration);
        configured.setVisibility(next == 0 ? View.VISIBLE : View.GONE);
        configured.setText(getString(R.string.role_service_duration) + "\n"
                + com.subhub.app.people.ServiceDurationSettings.label(this, true));
        binding.commitmentActivePanel.setVisibility(next != 0 ? View.VISIBLE : View.GONE);
        binding.serviceCountdown.setVisibility(next != 0 ? View.VISIBLE : View.GONE);
        if (next == 1) binding.serviceCountdown.setCountdown(
                CommitmentManager.remainingMillis(this), CommitmentManager.originalDurationMillis(this),
                CommitmentManager.isCountdownHidden(this), animate);
        else if (next == 2) binding.serviceCountdown.setPermanent(
                binding.commitmentStartPanel.isCountdownHidden(), animate);
        else binding.serviceCountdown.stop();
        durationDisplay = next;
    }

    private void sizeServiceCountdown() {
        if (binding == null) return;
        int height = dp(Math.round(180 * Math.max(1f, getResources().getConfiguration().fontScale)));
        android.view.ViewGroup.LayoutParams size = binding.serviceCountdown.getLayoutParams();
        if (size.height != height) { size.height = height; binding.serviceCountdown.setLayoutParams(size); }
    }

    private void updateProtectionButton(boolean running) {
        binding.buttonProtection.setAlpha(1f);
        androidx.core.view.ViewCompat.setStateDescription(binding.buttonProtection, null);
        boolean sessionActive = running || CommitmentManager.isActive(this)
                        || new PaidPauseManager(this).isActive()
                        || new AppModeManager(this).isArmed()
                        || ScreenshotAccessibilityService.isRecognitionActive();
        binding.buttonProtection.setEnabled(true);
        binding.buttonProtection.setVisibility(sessionActive ? View.GONE : View.VISIBLE);
        binding.buttonProtection.setText(R.string.role_start_session);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            binding.commitmentStartPanel.reload();
            uiTimer.removeCallbacks(uiTick);
            uiTimer.post(uiTick);
            renderCommitmentState();
            uiActive = true;
            achievementsDirty = true;
            notifyProgressOnResume = true;
            observeAchievementInputs();
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
                    Toast.makeText(
                                    this,
                                    R.string.permission_accessibility_guidance,
                                    Toast.LENGTH_LONG)
                            .show();
                    accessibilityPermission.launch(
                            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                case OVERLAY:
                    overlayPermission.launch(
                            new Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                    return;
                case NOTIFICATIONS:
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                case DEVICE_ADMIN:
                    deviceAdminPermission.launch(new HardcoreModeManager(this).activationIntent());
                    return;
                default:
                    return;
            }
        }
    }

    private List<HomePermissionPolicy.Requirement> missingPermissions() {
        FeatureModuleManager modules = new FeatureModuleManager(this);
        boolean runtimeFeature = modules.hasRuntimeFeature();
        boolean screenRecordingCensor =
                modules.isCensorEnabled()
                        && new SettingsRepository(this).loadCaptureMethod()
                                == CaptureMethod.SCREEN_RECORDING;
        boolean notificationPermissionApplies =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        boolean notificationsReady =
                !notificationPermissionApplies
                        || ContextCompat.checkSelfPermission(
                                        this, Manifest.permission.POST_NOTIFICATIONS)
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
        String fingerprint = missing.toString();
        if (missing.isEmpty()) fingerprint = "";
        final String state = fingerprint;
        com.subhub.app.onboarding.GlobalNoticeState notices =
                new com.subhub.app.onboarding.GlobalNoticeState(this);
        com.subhub.app.onboarding.GlobalNoticeState.Kind kind = notices.next(state);
        View host = findViewById(R.id.global_notice_host);
        host.setTag(kind.name());
        host.setVisibility(
                kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.NONE
                        ? View.GONE
                        : View.VISIBLE);
        if (kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.NONE) return;
        TextView title = findViewById(R.id.global_notice_title),
                body = findViewById(R.id.global_notice_body),
                action = findViewById(R.id.global_notice_action);
        if (kind == com.subhub.app.onboarding.GlobalNoticeState.Kind.PERMISSIONS) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(getString(permissionName(missing.get(i))));
            }
            title.setText(R.string.home_permission_title);
            body.setText(getString(R.string.home_permission_missing, names.toString()));
            action.setText(R.string.home_permission_action);
            action.setOnClickListener(v -> beginPermissionReadinessFlow(true));
        } else {
            title.setText(R.string.keyholder_home_title);
            body.setText(R.string.keyholder_intro_body);
            action.setText(R.string.keyholder_intro_open);
            action.setOnClickListener(
                    v -> {
                        notices.dismiss(kind, state);
                        renderPermissionReadiness();
                        startActivity(
                                new Intent(
                                        this, com.subhub.app.security.AuthenticatorActivity.class));
                    });
        }
        findViewById(R.id.global_notice_dismiss)
                .setOnClickListener(
                        v -> {
                            notices.dismiss(kind, state);
                            renderPermissionReadiness();
                        });
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
        } else ControllerPinGate.unlock(this, this::renderEditState, false);
    }

    private void renderEditState() {
        if (binding == null) return;
        ControllerEditMode.renderButton(this, editLockButton);
        PrimaryHeader.backButton(binding.getRoot()).setVisibility(localSessionPage ? View.VISIBLE : View.GONE);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.bind(binding.getRoot(), 0,
                localSessionPage ? R.string.role_session : R.string.role_overview, 0);
        binding.subDashboard.setVisibility(View.GONE);
        // Both spaces use the floating bottom navigation. Keep the final home
        // content fully reachable above it instead of letting Sub Space cards
        // disappear underneath the pill.
        int bottom = dp(116);
        binding.pageContent.setPaddingRelative(
                binding.pageContent.getPaddingStart(),
                binding.pageContent.getPaddingTop(),
                binding.pageContent.getPaddingEnd(),
                bottom);
        SubHubNavigation.bind(this, binding.getRoot(), localSessionPage && ControllerPinManager.isDomModeActive()
                ? SubHubNavigation.Screen.PEOPLE : SubHubNavigation.Screen.HOME);
        renderSubDashboard();
        renderCommitmentState();
        updateProtectionButton(ScreenCaptureService.isRunning());
        if (roleHome != null) roleHome.refresh(true);
    }

    private void renderSubDashboard() {
        if (binding == null) return;
        boolean walletEnabled = new FeatureModuleManager(this).isWalletEnabled();
        binding.subDashboard.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        {
            PenanceManager walletManager = new PenanceManager(this);
            PenanceSnapshot wallet = walletManager.snapshot(now);
            boolean checkout = wallet.getCheckoutCents() > 0;
            binding.subWalletPay.setVisibility(
                    walletEnabled && (wallet.getDueCents() > 0 || checkout) ? View.VISIBLE : View.GONE);
            binding.subWalletPay.setText(
                    checkout ? R.string.sub_wallet_resume : R.string.sub_wallet_pay);
            PaidPauseManager paidPause = new PaidPauseManager(this);
            boolean pauseActive = paidPause.isActive();
            boolean pauseAvailable = paidPause.canPurchase();
            binding.subWalletPause.setVisibility(
                    walletEnabled && (pauseActive || pauseAvailable) ? View.VISIBLE : View.GONE);
            binding.subWalletPause.setEnabled(!pauseActive);
            binding.subWalletPause.setText(
                    pauseActive
                            ? getString(
                                    R.string.paid_pause_active,
                                    CommitmentActivity.formatDuration(paidPause.remainingMillis()))
                            : getString(
                                    R.string.paid_pause_buy,
                                    paidPause.getDurationMinutes(),
                                    new PenanceManager(this).money(paidPause.getPriceCents())));
        }
    }

    private void beginPaidPause() {
        PenanceManager wallet = new PenanceManager(this);
        if (!wallet.requestPaidPause(System.currentTimeMillis())) {
            showStatus(R.string.paid_pause_unavailable);
            return;
        }
        startActivity(
                new Intent(this, PenanceActivity.class)
                        .putExtra(PenanceActivity.EXTRA_BEGIN_PAID_PAUSE, true));
    }

    String censorArrangementDetails() {
        return new com.subhub.app.people.LocalContractDetails(this).forSection("censor");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        uiActive = false;
        for (android.content.SharedPreferences source : achievementSources)
            source.unregisterOnSharedPreferenceChangeListener(achievementChanged);
        achievementSources.clear();
        uiTimer.removeCallbacks(uiTick);
        super.onPause();
    }

    private void renderRuntimeState() {
        if (binding == null) return;
        boolean manual = ScreenCaptureService.isRunning();
        boolean automatic = ScreenshotAccessibilityService.isRecognitionActive();
        boolean armed = new AppModeManager(this).isArmed();
        boolean waiting =
                ScreenshotAccessibilityService.isRunning()
                        && new AppModeManager(this).isEffectivelyArmed(System.currentTimeMillis())
                        && !automatic;
        updateProtectionButton(manual);

        int status =
                (manual || automatic)
                        ? R.string.status_active
                        : (waiting || armed)
                                ? R.string.status_app_mode_waiting
                                : R.string.status_inactive;
        binding.protectionStatus.setText(status);
        binding.protectionStatus.setTextColor(
                getColor(manual || automatic || waiting ? R.color.accent : R.color.text_muted));
        binding.protectionStatusDot.setAlpha(manual || automatic ? 1f : waiting ? 0.55f : 0.25f);

        DiagnosticsRepository.Snapshot runtime = DiagnosticsRepository.snapshot();
        if (runtime.isRunning() && runtime.isReady()) {
            binding.runtimeStatus.setText(
                    getString(R.string.runtime_ready, runtime.getProvider(), runtime.getModel()));
        } else if (runtime.isRunning() && !"None".equals(runtime.getLastFailure())) {
            binding.runtimeStatus.setText(
                    getString(R.string.runtime_failed, runtime.getLastFailure()));
        } else if (runtime.isRunning()) {
            binding.runtimeStatus.setText(R.string.runtime_initializing);
        } else {
            binding.runtimeStatus.setText(R.string.runtime_idle);
        }

        StatsSnapshot stats = new StatsRepository(this).load();
        renderAchievementsPreview(stats);
        renderCommitmentState();
        renderSubDashboard();
        if (roleHome != null) roleHome.refresh(false);
    }

    private void observeAchievementInputs() {
        if (!achievementSources.isEmpty()) return;
        for (String name :
                new String[] {
                    StatsRepository.PREFS_NAME,
                    SettingsRepository.PREFERENCES_NAME,
                    PenanceManager.PREFS_NAME,
                    com.subhub.app.penance.PayPalCredentialStore.PREFS_NAME,
                    "subhub_pack_state_v1"
                }) {
            android.content.SharedPreferences source =
                    getApplicationContext().getSharedPreferences(name, MODE_PRIVATE);
            source.registerOnSharedPreferenceChangeListener(achievementChanged);
            achievementSources.add(source);
        }
    }

    private void renderAchievementsPreview(StatsSnapshot stats) {
        if (binding == null || !uiActive || uiData.isPending("achievements")) return;
        String today = java.time.LocalDate.now().toString();
        if (!achievementsDirty
                && achievementSeconds == stats.getTotalProtectedSeconds()
                && achievementDay.equals(today)) return;
        achievementsDirty = false;
        achievementSeconds = stats.getTotalProtectedSeconds();
        achievementDay = today;
        android.content.Context app = getApplicationContext();
        uiData.load(
                "achievements",
                () -> com.subhub.app.stats.HomeAchievementPreview.load(app, stats),
                preview -> {
                    if (binding == null || !uiActive) {
                        achievementsDirty = true;
                        return;
                    }
                    if (notifyProgressOnResume) {
                        notifyProgressOnResume = false;
                        showProgressUnlocks(preview.achievements, stats);
                    }
                },
                failure -> achievementsDirty = true);
    }

    private void showProgressUnlocks(AchievementManager achievements, StatsSnapshot stats) {
        java.util.List<AchievementManager.Achievement> unlocked =
                achievements.takePendingNotifications();
        if (!unlocked.isEmpty()) {
            AchievementManager.Achievement first = unlocked.get(0);
            showAchievementUnlock(first, unlocked.size() - 1);
            return;
        }
        MilestoneManager.Result milestone =
                MilestoneManager.takeUnseen(this, stats.getTotalBlocks());
        if (milestone != null) {
            Snackbar notice =
                    Snackbar.make(binding.getRoot(), milestone.getMessage(), Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.getRoot().findViewById(R.id.bottom_navigation));
            notice.setAnchorViewLayoutListenerEnabled(true);
            notice.show();
        }
    }

    private void showAchievementUnlock(
            AchievementManager.Achievement achievement, int additionalUnlocks) {
        int count = additionalUnlocks + 1;
        String message =
                additionalUnlocks == 0
                        ? getString(
                                R.string.achievement_notice_single,
                                getString(achievement.getName()))
                        : getResources()
                                .getQuantityString(
                                        R.plurals.achievement_notice_count, count, count);
        Snackbar notice =
                Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.getRoot().findViewById(R.id.bottom_navigation))
                        .setAction(
                                R.string.achievement_notice_view,
                                view ->
                                        startActivity(
                                                new Intent(this, AchievementsActivity.class)));
        notice.setAnchorViewLayoutListenerEnabled(true);
        notice.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (uiData != null) uiData.close();
        uiTimer.removeCallbacksAndMessages(null);
        roleHome = null;
        binding = null;
        super.onDestroy();
    }
}
