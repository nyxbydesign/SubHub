package com.subhub.app.runtime;

import android.app.Application;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ProtectionSessionManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.settings.CaptureMethod;
import java.util.Objects;

/**
 * Concrete acknowledged DEVICE startup, rollback, and cleanup for an admitted local End.
 * An ACTIVE capture loss must preserve pact/Hardcore continuation; until that owned
 * continuation is integrated, it retains recovery occupancy instead of silently ending protection.
 */
final class AndroidDeviceStartLifecycle {
    private final Application physical;
    private final AndroidRuntimeFactory factory;
    private final AndroidRuntimeFactory.DeviceAssembly assembly;
    private final DeviceRuntimeEffects.DeviceComponent component;
    private final AppModeManager.ManagedArm arm;
    private final CommitmentManager.ManagedPact pact;
    private final ProtectionSessionManager.ManagedProjection projection;
    private boolean attempted, startAcknowledged;

    private AndroidDeviceStatisticsSource statisticsSource;

    /** Owner worker: prepare all inert handles and register cleanup before the first mutation. */
    AndroidDeviceStartLifecycle(Application physical, AndroidRuntimeFactory factory,
            AndroidRuntimeFactory.DeviceAssembly assembly) {
        this.physical = Objects.requireNonNull(physical);
        this.factory = Objects.requireNonNull(factory); this.assembly = Objects.requireNonNull(assembly);
        DeviceConfiguration config = assembly.admission.configuration;
        arm = AppModeManager.prepareManaged(physical, assembly.runtime.identity());
        pact = config.specification.minimum == 0 ? null : CommitmentManager.prepareManaged(physical,
                assembly.runtime.identity(), config.specification.minimum, config.specification.maximum,
                config.specification.hidden);
        projection = config.plan.capture == CaptureMethod.SCREEN_RECORDING
                ? ProtectionSessionManager.prepareManaged(physical, assembly.runtime.identity()) : null;
        component = assembly.effects.component("device_start");
        component.usesNoConfiguration(); // Only immutable start choices, never DEVICE live-settings ownership.
        component.installShutdown(this::close);
    }

    /** No native service attachment may be delivered before this exact startup receipt returns. */
    void start() {
        if (attempted) throw new IllegalStateException("DEVICE transition already attempted");
        attempted = true;
        boolean admitted = component.startup(() -> {
            arm.start();
            if (pact != null) pact.start();
            if (projection != null) projection.start();
            if (!arm.startAcknowledged() || pact != null && !pact.startAcknowledged()
                    || projection != null && !projection.startAcknowledged())
                throw new IllegalStateException("DEVICE startup receipt incomplete");
            com.subhub.app.stats.StatsRepository.SessionStamp stamp = arm.statisticsStamp();
            String statisticsIdentity = arm.statisticsIdentity();
            String owner = assembly.runtime.identity().session;
            assembly.effects.bindDurationObserver((source, wall, elapsed) ->
                    com.subhub.app.stats.DailyDurationRecorder.changedDevice(source, physical, stamp,
                            statisticsIdentity, owner, wall, elapsed));
            statisticsSource = new AndroidDeviceStatisticsSource(assembly.effects, physical,
                    assembly.runtime.identity(), stamp, statisticsIdentity, arm.acknowledgedStatisticsRepository());
            startAcknowledged = true;
        });
        if (!admitted) {
            assembly.runtime.requestStop();
            throw new SecurityException("DEVICE startup admission unavailable");
        }
        factory.deviceStartObserved(assembly);
        factory.deviceStatisticsSource(assembly, statisticsSource);
        component.readyAsync(result -> {
            if (result != ActiveRuntimeCoordinator.Result.OK) assembly.runtime.requestStop();
        });
    }
    boolean startAcknowledged() { return startAcknowledged; }

    private void close(DeviceRuntimeEffects.DeviceComponent.Cleanup cleanup) {
        factory.submitDeviceCleanup(assembly, () -> {
            // Loss of a capture component is not controller consent to cancel the physical pact.
            if (!factory.deviceStartupRollbackEligible(assembly) && !factory.deviceEndCleanupEligible(assembly)) {
                component.cleanupFailed();
                assembly.endFailed(new IllegalStateException("DEVICE cleanup authority is unavailable"));
                return null;
            }
            try {
                cleanup.run(() -> {
                    if (projection != null) projection.finish();
                    if (pact != null) pact.finish();
                    arm.finish();
                });
                if (!arm.finishAcknowledged() || pact != null && !pact.finishAcknowledged()
                        || projection != null && !projection.finishAcknowledged())
                    throw new IllegalStateException("DEVICE rollback receipt incomplete");
                cleanup.drained();
            } catch (RuntimeException | Error uncertain) {
                component.cleanupFailed(); throw uncertain;
            }
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                component.cleanupFailed();
                assembly.endFailed(failure);
            }
        });
    }
}
