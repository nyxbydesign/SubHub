package com.subhub.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ForegroundAppState;
import com.subhub.app.appmode.ProtectionSessionManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.capture.ScreenCaptureManager;
import com.subhub.app.censor.android.CensorScreenState;
import com.subhub.app.censor.android.OwnedLegacyCensorData;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.overlay.OverlayController;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.DwellInfractionTracker;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.ProtectionStopPolicy;

import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground pipeline: capture -> local ONNX inference -> tracking -> touch-through overlay. */
public final class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "subhub_protection";
    private static final int NOTIFICATION_ID = 1701;
    private static final String ACTION_START = "com.subhub.app.action.START";
    private static final String ACTION_STOP = "com.subhub.app.action.STOP";
    private static final String ACTION_ATTACH_OWNER = "com.subhub.app.action.ATTACH_RUNTIME_OWNER";
    private static final String EXTRA_OWNER_TOKEN = "runtime_owner_token";
    private static final String ACTION_STOP_OWNED = "com.subhub.app.action.STOP_OWNED_CAPTURE";
    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String DIAGNOSTICS_MODE = "MediaProjection";
    private static final CaptureRunOwnership ownership = new CaptureRunOwnership();
    private static volatile ScreenCaptureService liveInstance;
    private final String instanceIdentity = UUID.randomUUID().toString();
    private final Object resources = new Object();
    private volatile CaptureRunOwnership.Token captureOwner;
    private volatile boolean destroyed;
    private volatile boolean destructionComplete;
    private volatile boolean resourceClosureFailed;
    private volatile int lastStartId;
    private PopupStormManager.ServiceParticipation popupParticipation;
    private PopupStormManager.ServiceParticipation managedPopup;
    private RuntimeCaptureHost.Connection runtimeConnection;
    private RuntimeCaptureHost.Selection runtimeSelection;
    private boolean managedOwnerRoute;
    private final java.util.Map<RuntimeCaptureHost.Attachment, RuntimeCaptureHost.Selection> unclaimedSelections =
            new java.util.IdentityHashMap<>();
    private com.subhub.app.runtime.AppRuntimeGateway.ServiceAccess runtimeServices;
    private volatile DeviceProjectionAttachment managedAttachment;
    private com.subhub.app.overlay.LegacyRenderDependencies managedRenderer;
    private ExecutorService managedArtwork;
    private ManagedPreferences managedPreferences;
    private ManagedReceiver managedReceiver;
    private ManagedProjectionCallback managedProjectionCallback;
    private boolean managedPreferencesAttempted, managedReceiverAttempted;
    private volatile boolean managedNativeFailure, managedBackClosed;
    private CaptureRunOwnership.Token managedClosingOwner;


    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean firstFrameReported = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> postPipeline(() -> {
                if (destroyed) return;
                if (DiagnosticsRepository.PREF_OVERLAY.equals(key)) {
                    refreshDiagnosticsOverlay();
                } else reloadSettings();
            });
    private ScheduledExecutorService executor;
    private ExecutorService inferenceExecutor;
    private LatestFrameBroker<ProjectionFrame> frameBroker;
    private LatestFrameBroker.Async<ProjectionFrame> managedFrames;
    private CaptureLoadGovernor loadGovernor;
    private MediaProjection projection;
    private ScreenCaptureManager capture;
    private ForegroundAppState.Snapshot clearedScope, processedScope;
    private volatile DetectionEngine detector;
    private ObjectTracker tracker;
    private OverlayController overlay;
    private SettingsRepository settings;
    private StatsRepository stats;
    private PenanceManager penance;
    private final DwellInfractionTracker dwellTracker = new DwellInfractionTracker();
    // DEVICE dependencies are selected only after a recognized DEVICE start.
    private CensorTapTracker tapTracker;
    private boolean deviceInitialized;
    private volatile DetectorConfig detectorConfig;
    private volatile boolean overlayNeedsSourceFrame;
    private volatile boolean explicitlyStoppedByController;
    private final AtomicBoolean projectionLeaseHeld = new AtomicBoolean();
    private CensorScreenState censorScreen;
    private volatile boolean pipelineActive;
    private volatile boolean projectionRevoked;
    private volatile long startingGeneration = -1;
    private volatile long pipelineGeneration;
    private volatile boolean nativePipelineOwned;
    private volatile long detectorRetirement;
    private long appliedDetectorRetirement;
    private long observedScreenGeneration;
    private boolean powerSave;
    private ScheduledFuture<?> captureSchedule;
    private boolean screenReceiverRegistered;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            onCaptureEnvironmentChanged(intent.getAction());
        }
    };
    private final Runnable screenTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            reconcileCensorScreen();
            postPipelineDelayed(this, 1000);
        }
    };

    public static Intent startIntent(Context context, int resultCode, Intent resultData) {
        return new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
    }

    /** Inactive owner transport only. The token is routing metadata, never a grant or lease. */
    public static Intent ownerStartIntent(Context physical, String opaqueToken) {
        if (opaqueToken == null || !UUID.fromString(opaqueToken).toString().equals(opaqueToken))
            throw new IllegalArgumentException("Missing or malformed runtime owner token");
        return new Intent(physical, ScreenCaptureService.class).setAction(ACTION_ATTACH_OWNER)
                .putExtra(EXTRA_OWNER_TOKEN, opaqueToken);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP);
    }

    public static boolean isRunning() { return ownership.isRunning(); }
    public static boolean isCensorRuntimeActive() {
        ScreenCaptureService service = liveInstance;
        return service != null && !service.projectionRevoked && service.pipelineActive && service.isInstanceRunning()
                && service.censorScreen != null && service.censorScreen.eligible() && service.censorConfigured();
    }

    private void reconcileCensorScreen() {
        if (destroyed || censorScreen == null) return;
        censorScreen.refresh(null);
        boolean usable = censorScreen.eligible();
        long screenGeneration = censorScreen.generation();
        if (screenGeneration != observedScreenGeneration) durationChanged();
        if (screenGeneration != observedScreenGeneration) suspendCensorPipeline();
        observedScreenGeneration = screenGeneration;
        String observed = usable ? ScreenshotAccessibilityService.observedCensorForeground() : "";
        syncPopupParticipation(observed);
        boolean eligible = !observed.isEmpty() && observed.equals(ForegroundAppState.snapshot().packageName)
                && new AppModeManager(this).shouldCensorForeground(observed);
        if (projectionRevoked || !isInstanceRunning() || !usable) {
            suspendCensorPipeline();
            return;
        }
        if (!eligible) { pauseCensorPipeline(powerSave); return; }
        long generation = censorScreen.generation();
        if (pipelineActive || startingGeneration == generation) return;
        startingGeneration = generation;
        nativePipelineOwned = true;
        long transition = ++pipelineGeneration;
        executeCapture(() -> executeInference(() -> startPipeline(generation, transition)));
    }

    private void suspendCensorPipeline() {
        syncPopupParticipation("");
        pauseCensorPipeline(true);
    }

    private void onCaptureEnvironmentChanged(String action) {
        if (destroyed) return;
        boolean powerChanged = PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action);
        if (powerChanged) powerSave = loadGovernor.powerSaveEnabled();
        else {
            censorScreen.refresh(action);
            durationChanged();
        }
        reconcileCensorScreen();
        if (powerChanged && (pipelineActive || startingGeneration >= 0)) {
            long generation = censorScreen.generation(), transition = pipelineGeneration;
            // Keep this behind any startup already submitted through the capture queue.
            executeCapture(() -> {
                DeviceProjectionAttachment exact = managedAttachment;
                if (exact == null) executeInference(() -> refreshDetectorPowerPolicy(generation, transition));
                else exact.executeNative(inferenceExecutor, () -> pipelineGeneration == transition,
                        current -> { if (current) refreshDetectorPowerPolicy(generation, transition); }, () -> {});
            });
        }
    }

    private void refreshDetectorPowerPolicy(long generation, long transition) {
        synchronized (resources) {
            if (pipelineGeneration != transition || !isInstanceRunning() || !censorScreen.isCurrent(generation)
                    || !censorConfigured() || detector == null) return;
            try { detector.refreshPowerPolicy(); }
            catch (Exception | LinkageError failure) {
                failDetectorTransition(failure, DetectionEngine.resourceCleanupFailed(failure));
            }
        }
    }

    private void syncPopupParticipation(String observed) {
        boolean eligible = !projectionRevoked && censorScreen.eligible() && !observed.isEmpty()
                && observed.equals(ForegroundAppState.snapshot().packageName) && ownsCurrentRuntime()
                && new AppModeManager(this).shouldShowPopups(observed);
        if (managedAttachment != null) {
            if (managedPopup != null) managedPopup.setEligible(eligible);
            return;
        }
        if (eligible) popupParticipation = PopupStormManager.get().startOwned(this, popupSignature());
        else {
            PopupStormManager.get().stopOwnedService(popupParticipation);
            popupParticipation = null;
        }
    }

    private void pauseCensorPipeline(boolean retireNative) {
        boolean hadWork = pipelineActive || startingGeneration >= 0;
        boolean retire = retireNative && nativePipelineOwned;
        if (retire) {
            nativePipelineOwned = false;
            detectorRetirement++;
        }
        pipelineActive = false;
        startingGeneration = -1;
        if (hadWork) durationChanged();
        if (hadWork) pipelineGeneration++;
        long transition = pipelineGeneration;
        if (managedAttachment != null) managedAttachment.cancelCaptureLoop();
        if (captureSchedule != null) { captureSchedule.cancel(false); captureSchedule = null; }
        ScreenCaptureManager source = capture;
        if (source != null) {
            try { source.pauseSurface(true); }
            catch (RuntimeException failure) {
                projectionRevoked = true;
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure);
                stopSelfResult(lastStartId);
            }
        }
        if ((!hadWork && !retire) || executor == null || executor.isShutdown()) return;
        if (overlay != null) overlay.clear();
        if (ownsCurrentRuntime()) popupManager().updateDetections(java.util.Collections.emptyList());
        executeCapture(() -> executeInference(() -> {
            synchronized (resources) {
                retireRequestedDetector();
                if (pipelineGeneration == transition) {
                    tracker = null;
                    processedScope = null;
                    dwellTracker.clear();
                }
            }
        }));
    }

    /** The next startup also owes this retirement if managed callbacks are reordered. */
    private boolean retireRequestedDetector() {
        long requested = detectorRetirement;
        if (appliedDetectorRetirement == requested) return true;
        DetectionEngine retired = detector;
        detector = null;
        try {
            if (retired != null) retired.close();
            appliedDetectorRetirement = requested;
            return true;
        } catch (RuntimeException | Error failure) {
            failDetectorTransition(failure, true);
            return false;
        }
    }

    private void failDetectorTransition(Throwable failure, boolean cleanupFailed) {
        projectionRevoked = true;
        if (cleanupFailed) resourceClosureFailed = true;
        updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure));
        Log.e(TAG, "Could not continue on-device protection", failure);
        DeviceProjectionAttachment exact = managedAttachment;
        if (exact != null) {
            if (cleanupFailed) {
                managedNativeFailure = true;
                exact.cleanupFailed();
            }
            exact.stop();
        } else postPipeline(() -> { if (ownership.owns(captureOwner)) stopSelfResult(lastStartId); });
    }

    private void durationChanged() {
        com.subhub.app.stats.DailyDurationRecorder.changed(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /** An admitted capture effect, separate from peer authorization and the enclosing runtime identity. */
    public static final class StopTarget {
        private final CaptureRunOwnership.Token owner;
        private StopTarget(CaptureRunOwnership.Token owner) { this.owner = owner; }
        public String identity() { return owner.activation; }
        /** Historical closure of this capture; it says nothing about a replacement runtime. */
        public boolean isClosed() { return owner.isClosed(); }
    }

    /** Pure app-owned observation; the command controller captures this before committing End. */
    @Nullable public static StopTarget observedStopTarget(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            CaptureRunOwnership.Token owner = ownership.current();
            ScreenCaptureService service = liveInstance;
            if (owner == null || service == null || service.destroyed || !owner.equals(service.captureOwner)) return null;
            try { return owner.components.equals(components(context)) ? new StopTarget(owner) : null; }
            catch (IllegalArgumentException | ClassCastException corrupt) { return null; }
        }
    }

    /** Delivers only to the already observed instance; it never creates or globally stops a service. */
    public static boolean requestStop(StopTarget target) {
        if (target == null) return false;
        if (Thread.holdsLock(RuntimeSessionIdentity.LOCK) || Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK))
            throw new IllegalStateException("Capture dispatch must follow authority validation");
        ScreenCaptureService service = liveInstance;
        return service != null && service.instanceIdentity.equals(target.owner.instance)
                && service.postPipeline(() -> service.stopOwned(target.owner));
    }

    public static Intent targetedStopIntent(Context context, StopTarget target) {
        if (target == null) throw new IllegalArgumentException("Missing capture stop target");
        CaptureRunOwnership.Token owner = target.owner;
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP_OWNED)
                .putExtra("capture_instance", owner.instance).putExtra("capture_activation", owner.activation)
                .putExtra("capture_start_id", owner.startId).putExtra("capture_statistics", owner.components.statistics)
                .putExtra("capture_pact", owner.components.pact).putExtra("capture_pause", owner.components.pause);
    }

    private static CaptureRunOwnership.Token stopTarget(Intent intent) {
        return new CaptureRunOwnership.Token(intent.getStringExtra("capture_instance"), intent.getStringExtra("capture_activation"),
                intent.getIntExtra("capture_start_id", 0), new CaptureRunOwnership.Components(
                        intent.getStringExtra("capture_statistics"), intent.getStringExtra("capture_pact"), intent.getStringExtra("capture_pause")));
    }

    private static CaptureRunOwnership.Components components(Context context) {
        SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        return new CaptureRunOwnership.Components(
                context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getString("active_session_identity", ""),
                main.getString("commitment_session_identity", ""),
                context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).getString("paid_pause_session_identity", ""));
    }

    private boolean ownsRuntimeLocked() {
        try { return !destroyed && ownership.mayAffectRuntime(captureOwner, components(this)); }
        catch (IllegalArgumentException | ClassCastException corrupt) { return false; }
    }

    private boolean isInstanceRunning() { return !destroyed && ownership.owns(captureOwner) && !ownership.isStopping(captureOwner); }

    private boolean ownsCurrentRuntime() {
        synchronized (RuntimeSessionIdentity.LOCK) { return ownsFrameRuntimeLocked(); }
    }

    private boolean ownsFrameRuntimeLocked() {
        try { return isInstanceRunning() && captureOwner.components.equals(components(this)); }
        catch (IllegalArgumentException | ClassCastException corrupt) { return false; }
    }

    private void updateDiagnostics(Runnable update) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (ownsFrameRuntimeLocked()) update.run();
        }
    }

    final class FrameScope {
        private final long screenGeneration = censorScreen.generation();
        private final long pipelineTransition = pipelineGeneration;
        private final CaptureRunOwnership.Token owner = captureOwner;
        private final RuntimeSessionIdentity.ComponentObservation components = RuntimeSessionIdentity.componentObservation(ScreenCaptureService.this);
        private final ForegroundAppState.Snapshot foreground;
        final StatsRepository.SessionStamp statistics = stats.sessionStamp();
        final long tapScope = tapTracker.scope();
        private FrameScope(ForegroundAppState.Snapshot foreground) { this.foreground = foreground; }
        boolean isCurrent() {
            return !destroyed && !projectionRevoked && pipelineActive && pipelineGeneration == pipelineTransition && censorScreen.isCurrent(screenGeneration) && ForegroundAppState.isCurrent(foreground) && components.isCurrent() && statistics != null && statistics.isCurrent()
                    && ownership.owns(owner) && !ownership.isStopping(owner);
        }
    }

    FrameScope observeFrameScope() {
        return observeFrameScope(ForegroundAppState.snapshot());
    }

    private FrameScope observeFrameScope(ForegroundAppState.Snapshot foreground) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Frame admission requires runtime ownership");
        if (!ownsFrameRuntimeLocked() || !ForegroundAppState.isCurrent(foreground)) return null;
        FrameScope scope = new FrameScope(foreground);
        return scope.isCurrent() ? scope : null;
    }

    void postFramePublication(FrameScope scope, Runnable render, Runnable release) {
        DeviceProjectionAttachment exact = managedAttachment;
        if (exact != null) {
            exact.postMain(() -> {
                if (scope == null || !scope.isCurrent()) { release.run(); return; }
                try { render.run(); }
                catch (RuntimeException | Error failed) {
                    exact.stop(); // Fence the source pool before abandoning any partial View transfer.
                    release.run(); throw failed;
                }
            }, release);
            return;
        }
        if (!postPipeline(() -> {
            if (scope != null && scope.isCurrent()) render.run();
            else release.run();
        })) release.run();
    }

    /** Managed callbacks never choose the process-wide legacy popup singleton. */
    private PopupStormManager popupManager() {
        if (managedAttachment == null) return PopupStormManager.get();
        if (managedPopup == null) throw new IllegalStateException("Managed popup participation not attached");
        return managedPopup.manager();
    }

    private String popupSignature() {
        CaptureRunOwnership.Components value = captureOwner.components;
        return value.pact + ":" + value.pause + ":" + value.statistics;
    }

    private boolean stopOwned(CaptureRunOwnership.Token target) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (destroyed || !target.equals(captureOwner)) return false;
            boolean affectRuntime;
            try { affectRuntime = ownership.mayAffectRuntime(target, components(this)); }
            catch (IllegalArgumentException | ClassCastException corrupt) { affectRuntime = false; }
            if (!ownership.beginStop(target)) return false;
            explicitlyStoppedByController = true;
            if (affectRuntime && managedAttachment == null) {
                ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
                new AppModeManager(this).setArmed(false);
            }
        }
        if (managedAttachment != null) {
            managedAttachment.stop();
            return true; // Backend owns durable End/rearm policy, never this resource child.
        }
        // Android start IDs fence later deliveries, while the activation token fences later ownership.
        if (!stopSelfResult(lastStartId)) Log.d(TAG, "Capture retirement awaits a newer start delivery");
        durationChanged();
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    /** Inactive integration seam: no INTERNAL request may enter this DEVICE-only initializer. */
    private void initializeDeviceRuntime() {
        if (deviceInitialized) return;
        if (destroyed) throw new IllegalStateException("Capture service already destroyed");
        // Mark before the first allocation so partial DEVICE construction still gets DEVICE cleanup.
        deviceInitialized = true;
        tapTracker = CensorTapTracker.shared();
        executor = Executors.newSingleThreadScheduledExecutor();
        inferenceExecutor = Executors.newSingleThreadExecutor();
        if (managedAttachment == null) loadGovernor = new CaptureLoadGovernor(this);
        else { loadGovernor = CaptureLoadGovernor.prepareOwned(this); loadGovernor.startOwned(); }
        settings = new SettingsRepository(this);
        censorScreen = new CensorScreenState(this);
        observedScreenGeneration = censorScreen.generation();
        android.content.IntentFilter screenFilter = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON); screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        screenFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (managedAttachment == null) {
            androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, screenFilter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            screenReceiverRegistered = true;
        } else {
            DeviceProjectionAttachment exact = managedAttachment;
            managedReceiver = new ManagedReceiver(action -> exact.postMain(() -> onCaptureEnvironmentChanged(action), () -> {}));
            managedReceiverAttempted = true;
            androidx.core.content.ContextCompat.registerReceiver(getApplicationContext(), managedReceiver, screenFilter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        powerSave = loadGovernor.powerSaveEnabled();
        postPipeline(screenTick);
        stats = com.subhub.app.remote.RemoteSessionNative.statistics(this);
        penance = new PenanceManager(this);
        if (managedAttachment == null) settings.preferences().registerOnSharedPreferenceChangeListener(settingsListener);
        else {
            DeviceProjectionAttachment exact = managedAttachment;
            managedPreferences = new ManagedPreferences(key -> exact.postMain(() -> {
                if (DiagnosticsRepository.PREF_OVERLAY.equals(key)) refreshDiagnosticsOverlay(); else reloadSettings();
                reconcileManagedRenderer(exact);
            }, () -> {}));
            managedPreferencesAttempted = true;
            settings.preferences().registerOnSharedPreferenceChangeListener(managedPreferences);
        }
        liveInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent != null && ACTION_ATTACH_OWNER.equals(intent.getAction())) {
            receiveManagedOwner(intent.getStringExtra(EXTRA_OWNER_TOKEN));
            return START_NOT_STICKY;
        }
        if (managedAttachment != null) {
            DeviceProjectionAttachment exact = managedAttachment;
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                exact.postMain(() -> {
                    if (ProtectionStopPolicy.decision(this) == ProtectionStopPolicy.Decision.ALLOW) {
                        explicitlyStoppedByController = true; exact.stop();
                    }
                }, () -> {});
            } else if (intent != null && ACTION_STOP_OWNED.equals(intent.getAction())) {
                exact.postMain(() -> { stopOwned(stopTarget(intent)); }, () -> {});
            }
            return START_NOT_STICKY;
        }
        if (managedOwnerRoute) {
            if (runtimeConnection == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())
                && com.subhub.app.runtime.AppRuntimeGateway.current(this) != null) {
            stopSelfResult(startId); return START_NOT_STICKY; // Installed ownership never falls back.
        }
        // AMS may register this ID before an earlier targeted Stop sees its delivery.
        if (!destroyed && ownership.isStopping(captureOwner)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent == null) {
            if (!deviceInitialized) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_OWNED.equals(intent.getAction())) {
            try { stopOwned(stopTarget(intent)); }
            catch (IllegalArgumentException | ClassCastException malformed) { Log.w(TAG, "Invalid capture stop target"); }
            if (captureOwner == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            // A stop-only delivery must not open settings, statistics, penance or shared taps.
            if (!deviceInitialized) { stopSelfResult(startId); return START_NOT_STICKY; }
            if (ProtectionStopPolicy.decision(this)
                    != ProtectionStopPolicy.Decision.ALLOW) return START_STICKY;
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || isRunning()) {
            if (!deviceInitialized) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try (var admitted = com.subhub.app.runtime.AppRuntimeGateway.tryBeginLegacyWork(this)) {
            if (admitted == null) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            return startLegacyProjection(intent, startId);
        }
    }

    private int startLegacyProjection(Intent intent, int startId) {
        legacyProjectionAttempted = true;
        initializeDeviceRuntime();
        if (!MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.PROTECTION)) {
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        projectionLeaseHeld.set(true);

        startForeground(NOTIFICATION_ID, buildNotification());
        Intent projectionData = projectionData(intent);
        if (projectionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0), projectionData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        AtomicReference<CaptureRunOwnership.Token> projectionOwner = new AtomicReference<>();
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                projectionRevoked = true;
                suspendCensorPipeline();
                CaptureRunOwnership.Token owner = projectionOwner.get();
                if (owner == null) stopSelf(startId);
                else if (ownership.owns(owner) && owner.equals(captureOwner)) stopSelfResult(lastStartId);
            }
        }, mainHandler);

        synchronized (RuntimeSessionIdentity.LOCK) {
            ProtectionSessionManager.markMediaProjectionStarted(this);
            new AppModeManager(this).setArmed(true);
            stats.startSession();
            CaptureRunOwnership.Components current = components(this);
            if (new AppModeManager(this).isArmed() && !current.statistics.isEmpty()) {
                captureOwner = ownership.activate(instanceIdentity, startId, current);
                projectionOwner.set(captureOwner);
            }
        }
        if (captureOwner == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        durationChanged();
        overlay = new OverlayController(this);
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        overlay.setAppearance(appearance);
        overlay.setMaxExtrapolationMs(settings.loadDetectorConfig().getMaxExtrapolationMs());
        if (censorScreen.eligible()) overlay.show();
        reconcileCensorScreen();
        return START_NOT_STICKY;
    }

    private static volatile boolean legacyProjectionAttempted;

    public static boolean legacyProjectionNeedsHandoff() { return legacyProjectionAttempted; }

    private void startPipeline(long generation, long transition) {
        synchronized (resources) {
            try {
                if (projectionRevoked || !isInstanceRunning() || pipelineGeneration != transition || !censorScreen.isCurrent(generation) || !censorConfigured()) return;
                if (!retireRequestedDetector() || pipelineGeneration != transition) return;
                DetectorConfig config = settings.loadDetectorConfig();
                detectorConfig = config;
                updateDiagnostics(() -> DiagnosticsRepository.begin(DIAGNOSTICS_MODE, config.getInferenceResolution()));
                if (detector == null) {
                    detector = new DetectionEngine(this, config);
                    if (managedAttachment != null) detector.requireStrictResourceCleanup();
                    detector.initialize();
                } else {
                    detector.setConfig(config);
                    detector.refreshPowerPolicy();
                }
                String provider = detector.getActiveProvider(), model = detector.getActiveModel();
                updateDiagnostics(() -> DiagnosticsRepository.ready(DIAGNOSTICS_MODE, provider,
                        model, config.getInferenceResolution()));
                if (projectionRevoked || !isInstanceRunning() || pipelineGeneration != transition || !censorScreen.isCurrent(generation) || !censorConfigured()) return;
                tracker = new ObjectTracker(config);

                DisplayInfo display = displayInfo();
                if (capture == null) capture = new ScreenCaptureManager(
                        projection,
                        display.width,
                        display.height,
                        display.densityDpi,
                        config.getInferenceResolution(),
                        config.getCaptureScale());
                capture.start(true);
                if (managedAttachment == null) {
                    if (frameBroker == null) frameBroker = new LatestFrameBroker<>(
                            command -> executeInference(command), this::processFrame, ProjectionFrame::close);
                } else if (managedFrames == null) {
                    managedFrames = LatestFrameBroker.async(inferenceExecutor, this::processManagedFrame, ProjectionFrame::close);
                }
                postPipeline(() -> {
                    if (destroyed || projectionRevoked || pipelineGeneration != transition || startingGeneration != generation || !censorScreen.isCurrent(generation)
                            || !censorConfigured() || !isInstanceRunning()) return;
                    try { capture.pauseSurface(false); }
                    catch (RuntimeException failure) {
                        projectionRevoked = true; suspendCensorPipeline();
                        DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure); stopSelfResult(lastStartId); return;
                    }
                    pipelineActive = true;
                    startingGeneration = -1;
                    durationChanged();
                    if (overlay != null) overlay.show();
                    if (managedAttachment == null)
                        captureSchedule = executor.scheduleWithFixedDelay(this::captureLatestFrame, 0, 16, TimeUnit.MILLISECONDS);
                    else managedAttachment.startCaptureLoop(executor, this::captureLatestFrame);
                });
            } catch (Exception error) { failDetectorTransition(error, DetectionEngine.resourceCleanupFailed(error)); }
        }
    }

    private void reloadSettings() {
        if (!isInstanceRunning()) return;
        reconcileCensorScreen();
        durationChanged();
        if (overlay != null) overlay.clearPersonCoverage();
        if (!censorConfigured()) {
            if (overlay != null) overlay.clear();
            if (tracker != null) tracker.clear();
            synchronized (RuntimeSessionIdentity.LOCK) { if (ownsFrameRuntimeLocked()) tapTracker.clear(); }
            dwellTracker.clear();
            if (ownsCurrentRuntime()) popupManager().updateDetections(java.util.Collections.emptyList());
        }
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        if (overlay != null) overlay.setAppearance(appearance);
        if (overlay != null) publishDiagnostics(diagnosticsOverlayText());
        DetectorConfig config = settings.loadDetectorConfig();
        detectorConfig = config;
        if (overlay != null) overlay.setMaxExtrapolationMs(config.getMaxExtrapolationMs());
        DetectionEngine currentDetector = detector;
        if (currentDetector != null) {
            synchronized (currentDetector) {
                // Retirement clears the reference before taking this monitor to close the model.
                if (detector == currentDetector) currentDetector.setConfig(config);
            }
        }
        ObjectTracker currentTracker = tracker;
        if (currentTracker != null) currentTracker.setConfig(config);
        if (!ownsCurrentRuntime()) return;
        popupManager().reloadSettings(this);
        syncPopupParticipation(censorScreen.eligible() ? ScreenshotAccessibilityService.observedCensorForeground() : "");
    }

    private void captureLatestFrame() {
        if (!pipelineActive || !censorScreen.eligible() || !isInstanceRunning() || capture == null) return;
        long screenGeneration = censorScreen.generation(), transition = pipelineGeneration;
        ForegroundAppState.Snapshot scope = ForegroundAppState.snapshot();
        if (!censorConfigured()) {
            if (clearedScope != scope) {
                clearedScope = scope;
                postPipeline(
                        () -> {
                            if (overlay != null && !censorConfigured()) overlay.clear();
                        });
            }
            return;
        }
        clearedScope = null;
        DetectorConfig currentConfig = detectorConfig;
        if (loadGovernor != null && !loadGovernor.shouldCapture(
                SystemClock.uptimeMillis(),
                currentConfig == null ? 0L : currentConfig.getDetectionIntervalMs())) return;
        try {
            Bitmap frame = capture.acquireLatestFrame();
            LatestFrameBroker<ProjectionFrame> broker = frameBroker;
            if (frame == null) return;
            if (!pipelineActive || pipelineGeneration != transition || !censorScreen.isCurrent(screenGeneration)) {
                capture.releaseFrame(frame); return;
            }
            if (broker == null && managedFrames == null) frame.recycle();
            else {
                OwnedCaptureLifetime.Ticket borrow = managedAttachment == null ? null : managedAttachment.borrowFrame();
                if (managedAttachment != null && borrow == null) { capture.releaseFrame(frame); return; }
                ProjectionFrame value = new ProjectionFrame(frame, capture, SystemClock.uptimeMillis(), scope, screenGeneration, transition,
                        managedAttachment, borrow);
                if (managedFrames != null) managedFrames.submit(value); else broker.submit(value);
            }
        } catch (Exception error) {
            updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
            Log.w(TAG, "Frame capture failed", error);
        }
    }

    private void processFrame(ProjectionFrame candidate) {
        Bitmap frame = candidate.bitmap();
        try {
            if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration) || !isInstanceRunning() || !censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)
                    || frame == null || frame.isRecycled()) return;
            if (processedScope != candidate.scope) {
                tracker.clear();
                synchronized (RuntimeSessionIdentity.LOCK) { if (ownsFrameRuntimeLocked()) tapTracker.clear(); }
                dwellTracker.clear();
                processedScope = candidate.scope;
            }
            List<Detection> detections = detector.detect(frame);
            if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration) || !ownsCurrentRuntime() || !censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)) return;
            List<TrackedObject> tracks = tracker.update(detections);
            DetectorConfig currentConfig = detectorConfig;
            finishProcessedFrame(candidate, tracks, currentConfig, new FrameMetrics(detector),
                    capture.getCaptureWidth(), capture.getCaptureHeight(), null);
        } catch (Exception error) {
            updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
            Log.w(TAG, "Frame processing failed", error);
        }
    }

    /** Shared accounting/render publication algorithm, always inside fresh managed NORMAL. */
    private void finishProcessedFrame(ProjectionFrame candidate, List<TrackedObject> tracks,
            DetectorConfig currentConfig, FrameMetrics metrics, int width, int height, FrameScope requiredScope) {
            int recordedBlocks;
            FrameScope admitted;
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration)) return;
                admitted = requiredScope == null ? observeFrameScope(candidate.scope) : requiredScope;
                if (requiredScope != null && !requiredScope.isCurrent()) return;
                if (admitted == null) return;
                recordedBlocks = stats.recordTracks(tracks, currentConfig == null ? null : currentConfig.getEnabledCategories());
            }
            long now = System.currentTimeMillis();
            if (recordedBlocks > 0) {
                int charged = penance.recordSourceDetectionResult(recordedBlocks, now, admitted.statistics, admitted::isCurrent).chargedCents;
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.NEW_DETECTION, charged, now, admitted::isCurrent);
                if (admitted.isCurrent()) new AchievementManager(this).checkAchievements(stats.load());
            }
            int dwellInfractions = dwellTracker.update(
                    tracks, now, penance.getDwellSeconds() * 1_000L);
            if (dwellInfractions > 0) {
                int charged = penance.recordSourceDwellResult(dwellInfractions, now, admitted.statistics, admitted::isCurrent).chargedCents;
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.CENSORED_DWELL, charged, now, admitted::isCurrent);
            }
            if (!ownsCurrentRuntime()) return;
            popupManager().updateTrackedObjects(tracks, width, height);
            DiagnosticsRepository.Snapshot diagnostics;
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!ownsFrameRuntimeLocked() || !admitted.isCurrent()) return;
                diagnostics = DiagnosticsRepository.recordFrame(
                    DIAGNOSTICS_MODE,
                    metrics.inference,
                    metrics.preprocess,
                    metrics.runtime,
                    metrics.postprocess,
                    SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis,
                    managedFrames != null ? managedFrames.droppedCount() : frameBroker == null ? 0L : frameBroker.droppedCount(),
                    tracks.size(),
                    width,
                    height);
            }
            String diagnosticText = diagnosticsOverlayText(diagnostics);
            Bitmap overlayFrame = overlayNeedsSourceFrame ? candidate.detachBitmap() : null;
            Runnable overlayFrameRelease = overlayFrame == null ? null
                    : () -> candidate.releaseDetachedBitmap(overlayFrame);
            if (firstFrameReported.compareAndSet(false, true)) {
                Log.i(TAG, "First frame processed with "
                        + metrics.model + " on " + metrics.provider
                        + " in " + metrics.inference + " ms at "
                        + width + "x" + height);
            }
            Runnable releaseOverlayFrame = () -> {
                if (overlayFrameRelease != null) overlayFrameRelease.run();
                else if (overlayFrame != null) overlayFrame.recycle();
            };
            postFramePublication(admitted, () -> {
                if (isInstanceRunning() && overlay != null && censorConfigured()
                                && ForegroundAppState.isCurrent(candidate.scope)) {
                    publishDiagnostics(diagnosticText);
                    overlay.setTapSource(false, admitted.tapScope);
                    if (overlayFrameRelease == null) {
                        overlay.update(tracks, width, height, overlayFrame);
                    } else {
                        overlay.updatePooledFrame(
                                tracks, width, height, overlayFrame, overlayFrameRelease);
                    }
                    updateDiagnostics(() -> DiagnosticsRepository.recordPublishDelay(DIAGNOSTICS_MODE,
                            SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis));
                }
                else releaseOverlayFrame.run();
            }, releaseOverlayFrame);
    }

    /** Native-only phase retains exact facts; no global effect gate is held over ONNX execution. */
    private void processManagedFrame(ProjectionFrame candidate, LatestFrameBroker.Completion completion) {
        DeviceProjectionAttachment exact = managedAttachment;
        if (exact == null) throw new IllegalStateException("Managed frame without exact attachment");
        Runnable discarded = () -> completion.complete(() -> {});
        exact.executeNative(inferenceExecutor, () -> {
            synchronized (RuntimeSessionIdentity.LOCK) {
                FrameScope scope = observeFrameScope(candidate.scope);
                boolean valid = scope != null && pipelineGeneration == candidate.pipelineTransition
                        && censorScreen.isCurrent(candidate.screenGeneration) && censorConfigured()
                        && candidate.bitmap() != null && !candidate.bitmap().isRecycled()
                        && detector != null && tracker != null && capture != null;
                return new NativeFrameFacts(valid, scope, detector, tracker, detectorConfig, candidate.bitmap(),
                        capture == null ? 0 : capture.getCaptureWidth(), capture == null ? 0 : capture.getCaptureHeight());
            }
        }, facts -> {
            if (!facts.valid) { discarded.run(); return; }
            try {
                List<Detection> detections = java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<>(facts.detector.detect(facts.frame)));
                FrameMetrics metrics = new FrameMetrics(facts.detector);
                exact.executeNormal(inferenceExecutor, () -> completion.complete(() -> {
                    if (!facts.scope.isCurrent() || facts.detector != detector || facts.tracker != tracker
                            || facts.config != detectorConfig || !censorConfigured()) return;
                    if (processedScope != candidate.scope) {
                        facts.tracker.clear();
                        synchronized (RuntimeSessionIdentity.LOCK) { if (ownsFrameRuntimeLocked()) tapTracker.clear(); }
                        dwellTracker.clear(); processedScope = candidate.scope;
                    }
                    List<TrackedObject> tracks = facts.tracker.update(detections);
                    finishProcessedFrame(candidate, tracks, facts.config, metrics, facts.width, facts.height, facts.scope);
                }), discarded);
            } catch (Exception error) {
                if (DetectionEngine.resourceCleanupFailed(error)) { exact.cleanupFailed(); exact.stop(); }
                exact.executeNormal(inferenceExecutor, () -> completion.complete(() -> {
                    updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
                    Log.w(TAG, "Frame processing failed", error);
                }), discarded);
            }
        }, discarded);
    }
    private static final class NativeFrameFacts {
        final boolean valid;
        final FrameScope scope;
        final DetectionEngine detector;
        final ObjectTracker tracker;
        final DetectorConfig config;
        final Bitmap frame;
        final int width, height;
        NativeFrameFacts(boolean valid, FrameScope scope, DetectionEngine detector, ObjectTracker tracker,
                DetectorConfig config, Bitmap frame, int width, int height) {
            this.valid = valid; this.scope = scope; this.detector = detector; this.tracker = tracker;
            this.config = config; this.frame = frame; this.width = width; this.height = height;
        }
    }
    private static final class FrameMetrics {
        final long inference, preprocess, runtime, postprocess;
        final String model, provider;
        FrameMetrics(DetectionEngine engine) {
            inference = engine.getLastInferenceMs(); preprocess = engine.getLastPreprocessMs();
            runtime = engine.getLastRuntimeMs(); postprocess = engine.getLastPostprocessMs();
            model = engine.getActiveModel(); provider = engine.getActiveProvider();
        }
    }

    private DisplayInfo displayInfo() {
        WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(getResources().getDisplayMetrics());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            return new DisplayInfo(bounds.width(), bounds.height(), metrics.densityDpi);
        }
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    private Intent projectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        @SuppressWarnings("deprecation")
        Intent value = source.getParcelableExtra(EXTRA_RESULT_DATA);
        return value;
    }

    private void refreshDiagnosticsOverlay() {
        postPipeline(() -> { if (overlay != null) publishDiagnostics(diagnosticsOverlayText()); });
    }

    /** A delayed frame must not restore diagnostics after visibility was switched off. */
    private void publishDiagnostics(String value) {
        if (overlay != null) overlay.setDiagnostics(settings != null && settings.preferences()
                .getBoolean(DiagnosticsRepository.PREF_OVERLAY, false) ? value : "");
    }

    private String diagnosticsOverlayText() {
        return diagnosticsOverlayText(DiagnosticsRepository.snapshot());
    }

    private String diagnosticsOverlayText(DiagnosticsRepository.Snapshot snapshot) {
        return settings != null && settings.preferences().getBoolean(
                DiagnosticsRepository.PREF_OVERLAY, false)
                ? DiagnosticsRepository.overlayText(snapshot) : "";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setSilent(true);
        if (ProtectionStopPolicy.showNotificationStop(this)) {
            builder.addAction(0, getString(R.string.notification_stop), stop);
        }
        return com.subhub.app.privacy.PrivacyNotifications.present(this, NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private boolean censorConfigured() {
        return !projectionRevoked && censorScreen != null && censorScreen.eligible() && settings != null && new AppModeManager(this)
                        .shouldCensorForeground(ForegroundAppState.snapshot().packageName);
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        if (managedAttachment != null) {
            destroyed = true; managedAttachment.stop();
            if (runtimeConnection != null) runtimeConnection.disconnected();
            if (runtimeServices != null) runtimeServices.disconnected(runtimeConnection);
            super.onDestroy(); // Java/OS shell death is not managed resource closure.
            return;
        }
        if (!deviceInitialized) {
            destroyed = true;
            if (runtimeConnection != null) {
                runtimeConnection.disconnected();
                if (runtimeServices != null) runtimeServices.disconnected(runtimeConnection);
            }
            // No selected data owner or resources exist. Never perform DEVICE cleanup/rearming.
            super.onDestroy();
            destructionComplete = true;
            return;
        }
        pipelineActive = false;
        mainHandler.removeCallbacks(screenTick);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        CaptureRunOwnership.Token original = captureOwner;
        boolean cleanup;
        synchronized (RuntimeSessionIdentity.LOCK) {
            cleanup = ownsRuntimeLocked();
            destroyed = true;
            if (cleanup) {
                new AppModeManager(this).setArmed(false);
                if (stats != null && original.components.statistics.equals(stats.activeSessionIdentity()))
                    stats.endSessionForIdentity(original.components.statistics);
                DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
                tapTracker.clear();
            }
            ownership.release(original);
            if (liveInstance == this) liveInstance = null;
        }
        durationChanged();
        if (settings != null) {
            settings.preferences().unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (executor != null) executor.shutdownNow();
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            try {
                if (!inferenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) resourceClosureFailed = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                resourceClosureFailed = true;
            }
        }
        synchronized (resources) {
            if (frameBroker != null) releaseResource("frame broker", frameBroker::close);
            frameBroker = null;
            if (capture != null) releaseResource("capture", capture::close);
            capture = null;
            if (loadGovernor != null) releaseResource("load governor", loadGovernor::close);
            loadGovernor = null;
            if (detector != null) releaseResource("detector", detector::close);
            detector = null;
            dwellTracker.clear();
            OverlayController activeOverlay = overlay;
            overlay = null;
            if (activeOverlay != null) releaseResource("overlay", activeOverlay::close);
            PopupStormManager.get().stopOwnedService(popupParticipation);
            popupParticipation = null;
            if (projection != null) releaseResource("projection", projection::stop);
            projection = null;
            if (projectionLeaseHeld.compareAndSet(true, false)) {
                MediaProjectionLeaseRegistry.release(
                        MediaProjectionLeaseRegistry.Owner.PROTECTION);
            }
        }
        if (executor != null) {
            try { if (!executor.awaitTermination(2, TimeUnit.SECONDS)) resourceClosureFailed = true; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); resourceClosureFailed = true; }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (cleanup && !explicitlyStoppedByController) reinforceOwnedRuntime(original);
        super.onDestroy();
        if (original != null && !resourceClosureFailed) original.acknowledgeClosure();
        destructionComplete = true;
    }

    private void releaseResource(String name, Runnable release) {
        try { release.run(); }
        catch (RuntimeException failure) { resourceClosureFailed = true; Log.w(TAG, "Could not close owned " + name, failure); }
    }

    /** Observe Android outside Runtime, then fence the only rearming write to the original components. */
    private void reinforceOwnedRuntime(CaptureRunOwnership.Token original) {
        boolean hardcore = new HardcoreModeManager(this).isEnabled();
        com.subhub.app.remote.RemoteSessionClock.Sample clock = new com.subhub.app.remote.RemoteSessionClock.Sample(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1));
        RuntimeSessionIdentity.ComponentObservation notificationScope;
        boolean notify;
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (ownership.isRunning()) return;
            try { if (!original.components.permitsCleanupOf(components(this))) return; }
            catch (IllegalArgumentException | ClassCastException corrupt) { return; }
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(this, clock);
            boolean pactRearm = current.pact && !current.paused;
            if (pactRearm) new AppModeManager(this).setArmed(true);
            notify = pactRearm || hardcore && new AppModeManager(this).isArmed() && !current.paused;
            notificationScope = RuntimeSessionIdentity.componentObservation(this);
        }
        if (!notificationScope.isCurrent()) return;
        if (notify) ResumeNotificationManager.show(this);
        if (hardcore && notificationScope.isCurrent()) HardcoreAutoPayManager.schedule(this);
    }

    /** Owner-worker factory: component registration happens before any physical construction. */
    public RuntimeCaptureHost.Attachment prepareDeviceAttachment(RuntimeCaptureHost.DeviceRequest request) {
        return new DeviceProjectionAttachment(this, request);
    }

    /** Geometry and allocation for the exact neutral projection service selected by the owner. */
    public InternalLegacyProjectionCapture prepareInternalAttachment(RuntimeCaptureHost.InternalRequest request,
            InternalRuntimeSession session, OwnedLegacyCensorData data, OwnedLegacyCensorData.LocalNotice notice) {
        if (request.connection.projection().service() != this || !request.connection.isConnected())
            throw new SecurityException("Different projection service");
        DisplayInfo display = displayInfo();
        ExecutorService producer = Executors.newSingleThreadExecutor();
        ExecutorService inference = Executors.newSingleThreadExecutor();
        ExecutorService artwork = Executors.newSingleThreadExecutor();
        try {
            return new InternalLegacyProjectionCapture(request, session, data, notice,
                    display.width, display.height, display.densityDpi, producer, inference, artwork);
        } catch (RuntimeException | Error failure) {
            producer.shutdown();
            inference.shutdown();
            artwork.shutdown();
            throw failure;
        }
    }

    private boolean postPipeline(Runnable action) {
        DeviceProjectionAttachment exact = managedAttachment;
        return exact == null ? mainHandler.post(action) : exact.postMain(action, () -> {});
    }
    private boolean postPipelineDelayed(Runnable action, long delay) {
        DeviceProjectionAttachment exact = managedAttachment;
        return exact == null ? mainHandler.postDelayed(action, delay) : exact.postMainAfter(action, delay);
    }
    private void executeCapture(Runnable action) {
        DeviceProjectionAttachment exact = managedAttachment;
        if (exact == null) executor.execute(action); else exact.execute(executor, action);
    }
    private void executeInference(Runnable action) {
        DeviceProjectionAttachment exact = managedAttachment;
        if (exact == null) inferenceExecutor.execute(action); else exact.execute(inferenceExecutor, action);
    }

    private void receiveManagedOwner(String token) {
        if (destroyed || deviceInitialized) return; // An owner token cannot replace a DEVICE capture.
        if (managedOwnerRoute) {
            if (runtimeConnection == null) stopSelfResult(lastStartId);
            return;
        }
        managedOwnerRoute = true;
        runtimeServices = com.subhub.app.runtime.AppRuntimeGateway.services(this);
        if (runtimeServices == null || token == null || token.isEmpty()) { stopSelfResult(lastStartId); return; }
        runtimeConnection = RuntimeCaptureHost.Connection.projection(new RuntimeCaptureHost.ProjectionHost() {
            @Override public Service service() { return ScreenCaptureService.this; }
            @Override public void showNeutralForegroundNotification() { showNeutralForeground(); }
            @Override public void stopAfterDrain() { stopSelfResult(lastStartId); }
        });
        RuntimeCaptureHost.Connection exact = runtimeConnection;
        showNeutralForeground();
        runtimeServices.claimProjection(exact, token, new RuntimeCaptureHost.Completion() {
            @Override public void selected(RuntimeCaptureHost.Selection selection) {
                if (runtimeSelection != null || destroyed || !selection.claim(exact)) {
                    unclaimedSelections.put(selection.attachment, selection);
                    selection.attachment.seal();
                    runtimeServices.rejected(exact, selection.request, RuntimeCaptureHost.Rejection.STALE_CONNECTION);
                    return;
                }
                runtimeSelection = selection; // Retain before any partial physical attach.
                try { selection.attachment.attach(selection.projection); }
                catch (RuntimeException | Error failure) {
                    selection.attachment.seal();
                    runtimeServices.rejected(exact, selection.request, RuntimeCaptureHost.Rejection.ATTACHMENT_FAILED);
                    throw failure;
                }
            }
            @Override public void rejected(RuntimeCaptureHost.Rejection reason) {
                if (runtimeSelection == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStartId); }
            }
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                DeviceProjectionAttachment selected = managedAttachment;
                if (selected != null) selected.stop();
                else mainHandler.post(() -> stopSelfResult(lastStartId));
            }
        });
    }
    private void showNeutralForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text)).setOngoing(true).setSilent(true).build();
        startForeground(NOTIFICATION_ID, notification);
    }
    void attachManagedDevice(DeviceProjectionAttachment exact, RuntimeCaptureHost.ProjectionGrant grant) {
        if (managedAttachment != null || deviceInitialized || destroyed || runtimeSelection == null
                || runtimeSelection.attachment != exact || exact.request().connection != runtimeConnection)
            throw new SecurityException("Projection service is not the selected fresh DEVICE host");
        managedAttachment = exact;
        if (!MediaProjectionLeaseRegistry.acquire(MediaProjectionLeaseRegistry.Owner.PROTECTION))
            throw new IllegalStateException("Another projection source still owns capture");
        projectionLeaseHeld.set(true);
        showNeutralForeground();
        Intent permission = Objects.requireNonNull(grant).consume(exact.request(), runtimeConnection);
        initializeDeviceRuntime();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = Objects.requireNonNull(manager).getMediaProjection(android.app.Activity.RESULT_OK, permission);
            if (projection == null) throw new IllegalStateException("Approved projection is unavailable");
            managedProjectionCallback = new ManagedProjectionCallback(exact::stop);
            projection.registerCallback(managedProjectionCallback, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException | Error uncertain) { managedNativeFailure = true; throw uncertain; }
        synchronized (RuntimeSessionIdentity.LOCK) {
            // Backend has already ACKed whole DEVICE startup before attachment delivery.
            CaptureRunOwnership.Components current = components(this);
            if (new AppModeManager(this).isArmed() && !current.statistics.isEmpty())
                captureOwner = ownership.activate(instanceIdentity, lastStartId, current);
        }
        if (captureOwner == null) throw new IllegalStateException("DEVICE session did not acknowledge startup");
        managedPopup = PopupStormManager.managedDevice(this, exact.request(), exact.component);
        managedArtwork = Executors.newSingleThreadExecutor();
        managedRenderer = com.subhub.app.overlay.LegacyRenderDependencies.managedDevice(exact.request(), exact.component,
                settings, new com.subhub.app.capture.CustomImageManager(this), tapTracker, managedArtwork);
        overlay = OverlayController.managedDevice(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, managedRenderer);
        overlayNeedsSourceFrame = settings.loadAppearance().requiresSourceFrame();
        startForeground(NOTIFICATION_ID, buildNotification());
        // The registered component is infrastructure-ready only. No frame starts before ACTIVE.
    }
    void activateManagedDevice(DeviceProjectionAttachment exact) {
        requireManaged(exact);
        if (censorScreen.eligible() && overlay != null) overlay.show();
        reconcileCensorScreen(); reconcileManagedRenderer(exact);
    }
    private void reconcileManagedRenderer(DeviceProjectionAttachment exact) {
        if (exact != managedAttachment || !exact.active() || overlay == null) return;
        boolean artwork = overlay.reconcileArtwork(), rendering = overlay.reconcileRendering();
        if (!artwork || !rendering) exact.postMainAfter(() -> reconcileManagedRenderer(exact), 16);
    }
    void sealManagedDevice(DeviceProjectionAttachment exact) {
        if (managedAttachment != exact) return; // Prepared but not attached has no physical state.
        projectionRevoked = true; pipelineActive = false; exact.cancelCaptureLoop();
        if (capture != null) capture.sealSubmittedFrames();
        if (managedRenderer != null) managedRenderer.seal();
        if (managedPopup != null) managedPopup.seal();
        if (loadGovernor != null) loadGovernor.sealOwned();
        if (managedPreferences != null) managedPreferences.relay.retire();
        if (managedReceiver != null) managedReceiver.relay.retire();
        if (managedProjectionCallback != null) managedProjectionCallback.relay.retire();
    }
    /** Runs only in the attachment's newly posted main cleanup callback, never in View traversal. */
    void closeManagedFront(DeviceProjectionAttachment exact) {
        if (managedAttachment != exact) return;
        managedCleanupAttempt(() -> stopSelfResult(lastStartId)); // Actual onDestroy separately required.
        managedClosingOwner = captureOwner;
        if (managedPreferencesAttempted)
            managedCleanupAttempt(() -> settings.preferences().unregisterOnSharedPreferenceChangeListener(managedPreferences));
        if (managedReceiverAttempted)
            managedCleanupAttempt(() -> getApplicationContext().unregisterReceiver(managedReceiver));
        if (loadGovernor != null) managedCleanupAttempt(loadGovernor::close);
        if (frameBroker != null) managedCleanupAttempt(frameBroker::close);
        if (managedFrames != null) managedCleanupAttempt(managedFrames::close);
        if (overlay != null) managedCleanupAttempt(overlay::closeFromFreshMainQueue);
        else if (managedRenderer != null) managedCleanupAttempt(managedRenderer::close);
        else if (managedArtwork != null) managedCleanupAttempt(managedArtwork::shutdown);
        // Backend owns durable arm/statistics/pact/projection-intent lifetime. This child only
        // withdraws its local diagnostics/taps and resource participation.
        if (managedClosingOwner != null) managedCleanupAttempt(() -> {
            DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
            if (tapTracker != null) tapTracker.clear();
        });
        if (managedPopup != null) managedCleanupAttempt(managedPopup::closeFromFreshMainQueue);
        managedCleanupAttempt(() -> stopForeground(STOP_FOREGROUND_REMOVE));
        if (managedNativeFailure) exact.cleanupFailed();
    }
    private void managedCleanupAttempt(Runnable close) {
        try { close.run(); }
        catch (RuntimeException | Error uncertain) { managedNativeFailure = true; }
    }
    void closeManagedBack(DeviceProjectionAttachment exact, Runnable returned) {
        if (managedAttachment != exact) { returned.run(); return; }
        Runnable close = () -> {
            boolean clean = true;
            try {
                if (capture != null) {
                    try { capture.sealSubmittedFrames(); capture.close(); }
                    catch (RuntimeException | Error uncertain) { clean = false; managedNativeFailure = true; }
                }
                if (detector != null) {
                    try { detector.close(); }
                    catch (RuntimeException | Error uncertain) { clean = false; managedNativeFailure = true; }
                }
                if (projection != null) {
                    if (managedProjectionCallback != null) {
                        try { projection.unregisterCallback(managedProjectionCallback); }
                        catch (RuntimeException | Error uncertain) { clean = false; managedNativeFailure = true; }
                    }
                    try { projection.stop(); }
                    catch (RuntimeException | Error uncertain) { clean = false; managedNativeFailure = true; }
                }
                dwellTracker.clear(); managedBackClosed = clean;
                if (!clean) exact.cleanupFailed();
            } finally {
                try { if (executor != null) executor.shutdown(); }
                catch (RuntimeException | Error uncertain) { managedNativeFailure = true; exact.cleanupFailed(); }
                try { if (inferenceExecutor != null) inferenceExecutor.shutdown(); }
                catch (RuntimeException | Error uncertain) { managedNativeFailure = true; exact.cleanupFailed(); }
                returned.run();
            }
        };
        if (inferenceExecutor == null) exact.cleanupDispatch(callback -> mainHandler.post(callback), close);
        else exact.cleanupDispatch(callback -> { inferenceExecutor.execute(callback); return true; }, close);
    }
    boolean managedProjectionFrontendRetired(DeviceProjectionAttachment exact) {
        return managedAttachment != exact || overlay == null || overlay.nativeResourcesRetired();
    }
    boolean managedProjectionFrontendPending(DeviceProjectionAttachment exact) {
        return managedAttachment == exact && overlay != null && !overlay.cleanupFailed() && !overlay.nativeResourcesRetired();
    }
    boolean managedProjectionResourcesDrained(DeviceProjectionAttachment exact) {
        if (managedAttachment != exact) return true; // No attach occurred, hence no physical resources.
        return destroyed && managedBackClosed && !managedNativeFailure
                && (executor == null || executor.isTerminated()) && (inferenceExecutor == null || inferenceExecutor.isTerminated())
                && (capture == null || capture.managedResourcesDrained())
                && (managedFrames == null || managedFrames.managedResourcesDrained())
                && (managedArtwork == null || managedArtwork.isTerminated())
                && (managedRenderer == null || managedRenderer.managedResourcesDrained())
                && (overlay == null || overlay.managedResourcesDrained() && overlay.nativeResourcesRetired())
                && (managedPopup == null || managedPopup.managedResourcesDrained())
                && (loadGovernor == null || loadGovernor.managedResourcesDrained())
                && (managedPreferences == null || managedPreferences.relay.drained())
                && (managedReceiver == null || managedReceiver.relay.drained())
                && (managedProjectionCallback == null || managedProjectionCallback.relay.drained());
    }
    void managedProjectionDrained(DeviceProjectionAttachment exact) {
        if (managedAttachment == exact) {
            if (projectionLeaseHeld.compareAndSet(true, false)) MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.PROTECTION);
            ownership.release(managedClosingOwner);
            if (managedClosingOwner != null) managedClosingOwner.acknowledgeClosure();
            if (liveInstance == this) liveInstance = null;
            destructionComplete = true;
        }
        RuntimeCaptureHost.Selection selected = runtimeSelection != null && runtimeSelection.attachment == exact
                ? runtimeSelection : unclaimedSelections.get(exact);
        if (selected == null || selected.attachment != exact)
            throw new IllegalStateException("Unknown exact service attachment drain");
        RuntimeCaptureHost.Connection connection = selected.request.connection;
        if (connection.occupied() && !connection.releaseAfterDrain(selected))
            throw new IllegalStateException("Different service attachment drain");
        RuntimeCaptureHost.Selection retained = selected;
        runtimeServices.attachmentDrained(connection, selected).whenComplete((ignored, failure) -> {
            if (failure != null) { exact.cleanupFailed(); return; }
            mainHandler.post(() -> unclaimedSelections.remove(exact, retained));
        });
        // Unexpected-loss versus controller-End policy is owned by the exact backend lifecycle.
        // No generic post-release DEVICE rearm is permitted here.
    }
    private void requireManaged(DeviceProjectionAttachment exact) {
        if (managedAttachment != exact) throw new SecurityException("Different managed projection owner");
    }
    private static final class SignalRelay {
        final OwnedProjectionBinding.CallbackRelay counted;
        private String value;
        SignalRelay(java.util.function.Consumer<String> target) { counted = new OwnedProjectionBinding.CallbackRelay(() -> target.accept(value)); }
        synchronized void accept(String value) { this.value = value; counted.run(); }
        void retire() { counted.retire(); }
        boolean drained() { return counted.drained(); }
    }
    private static final class ManagedPreferences implements SharedPreferences.OnSharedPreferenceChangeListener {
        final SignalRelay relay;
        ManagedPreferences(java.util.function.Consumer<String> target) { relay = new SignalRelay(target); }
        @Override public void onSharedPreferenceChanged(SharedPreferences ignored, String key) { relay.accept(key); }
    }
    private static final class ManagedReceiver extends android.content.BroadcastReceiver {
        final SignalRelay relay;
        ManagedReceiver(java.util.function.Consumer<String> target) { relay = new SignalRelay(target); }
        @Override public void onReceive(Context context, Intent intent) { relay.accept(intent.getAction()); }
    }
    private static final class ManagedProjectionCallback extends MediaProjection.Callback {
        final OwnedProjectionBinding.CallbackRelay relay;
        ManagedProjectionCallback(Runnable target) { relay = new OwnedProjectionBinding.CallbackRelay(target); }
        @Override public void onStop() { relay.run(); }
    }

    private static final class DisplayInfo {
        private final int width;
        private final int height;
        private final int densityDpi;

        private DisplayInfo(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }

    private static final class ProjectionFrame implements AutoCloseable {
        private final long screenGeneration;
        private final long pipelineTransition;
        private Bitmap bitmap;
        private final ScreenCaptureManager owner;
        private final long capturedAtUptimeMillis;

        private final ForegroundAppState.Snapshot scope;
        private final DeviceProjectionAttachment attachment;
        private final OwnedCaptureLifetime.Ticket borrow;
        private final AtomicBoolean returned = new AtomicBoolean();
        private boolean detached;

        private ProjectionFrame(
                Bitmap bitmap,
                ScreenCaptureManager owner,
                long capturedAtUptimeMillis,
                ForegroundAppState.Snapshot scope, long screenGeneration, long pipelineTransition,
                DeviceProjectionAttachment attachment, OwnedCaptureLifetime.Ticket borrow) {
            this.bitmap = bitmap;
            this.owner = owner;
            this.capturedAtUptimeMillis = capturedAtUptimeMillis;
            this.scope = scope;
            this.screenGeneration = screenGeneration;
            this.pipelineTransition = pipelineTransition;
            this.attachment = attachment; this.borrow = borrow;
        }

        private Bitmap bitmap() { return bitmap; }

        private Bitmap detachBitmap() {
            Bitmap value = bitmap;
            bitmap = null; detached = value != null;
            return value;
        }

        private void releaseDetachedBitmap(Bitmap value) { release(value); }
        private void release(Bitmap value) {
            if (!returned.compareAndSet(false, true)) return;
            try {
                if (value != null && !value.isRecycled() && (attachment == null || attachment.active())) owner.releaseFrame(value);
                if (borrow != null) borrow.close();
            } catch (RuntimeException | Error uncertain) {
                if (attachment != null) attachment.cleanupFailed();
                throw uncertain;
            }
        }
        @Override public void close() {
            Bitmap value = bitmap; bitmap = null;
            if (!detached) release(value);
        }
    }
}
