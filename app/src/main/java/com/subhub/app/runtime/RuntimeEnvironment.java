package com.subhub.app.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.subliminal.SubliminalOverlayController;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Android components receive an OS host separately from immutable owner data and effects. */
public final class RuntimeEnvironment extends RuntimeExecution<RuntimeConfiguration> {
    public interface LocalCounters {
        /**
         * Durable owner-local work, invoked only inside this environment's counted OwnerWriteQueue
         * on its serial worker. Do not enqueue again, publish remotely or perform financial work.
         */
        void whisperImpression(RuntimeLease.Identity owner, OwnerObservation observed);
    }
    private final LocalCounters counters;
    private final OwnerWriteQueue ownerWrites;
    private final java.util.concurrent.atomic.AtomicBoolean revoked = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The writer must be registered as a required component of this exact runtime. Its shutdown
     * uses closeAfterDrain and retains occupancy through final durable accounting acknowledgment.
     */
    RuntimeEnvironment(ActiveRuntimeCoordinator coordinator, RuntimeLease lease,
            RuntimeConfiguration acknowledgedConfiguration, OwnerWriteQueue ownerWrites,
            LocalCounters counters, java.util.concurrent.Executor ownerWorker) {
        super(coordinator, lease, acknowledgedConfiguration, ownerWorker);
        if (!lease.identity.equals(acknowledgedConfiguration.owner)
                || lease.identity.initialConfigurationRevision != acknowledgedConfiguration.configRevision)
            throw new SecurityException("Initial configuration does not match admission");
        this.ownerWrites = Objects.requireNonNull(ownerWrites);
        if (!lease.identity.equals(ownerWrites.identity()))
            throw new SecurityException("Whisper writer belongs to another runtime owner");
        this.counters = Objects.requireNonNull(counters);
    }

    /** Immediate callback fence without querying the journal or waiting for RuntimeMutex. */
    @Override public ActiveRuntimeCoordinator.Result requestStop() {
        revoked.set(true);
        return super.requestStop();
    }

    public SettingsRepository censorSettings() { return SettingsRepository.forPreferences(configuration().main); }

    @Override public ActiveRuntimeCoordinator.Result applyConfiguration(String generation, long revision,
            RuntimeConfiguration acknowledged) {
        if (acknowledged == null || !identity().equals(acknowledged.owner)
                || revision != acknowledged.configRevision || !acknowledged.owner.profileGeneration.equals(generation))
            return ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED;
        return super.applyConfiguration(generation, revision, acknowledged);
    }

    @Override public java.util.concurrent.CompletableFuture<ActiveRuntimeCoordinator.Result> applyConfigurationAsync(
            String generation, long revision, RuntimeConfiguration acknowledged,
            java.util.concurrent.Executor moduleExecutor) {
        if (acknowledged == null || !identity().equals(acknowledged.owner)
                || revision != acknowledged.configRevision || !acknowledged.owner.profileGeneration.equals(generation))
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED);
        return super.applyConfigurationAsync(generation, revision, acknowledged, moduleExecutor);
    }

    /** Only bindWhispers may construct this renderer, after registering its closure obligation. */
    private SubliminalOverlayController createWhispers(Context accessibilityWindowHost) {
        return new SubliminalOverlayController(accessibilityWindowHost,
                new SubliminalSettingsRepository(configuration().main), new SubliminalOverlayController.Effects() {
                    @Override public boolean run(Runnable effect) { return runEffect(effect); }
                    @Override public boolean revoked() { return revoked.get(); }
                    @Override public void failed() { requestStop(); }
                    @Override public void recordImpression() {
                        if (!insideEffect()) throw new IllegalStateException("Unadmitted whisper accounting");
                        if (Looper.myLooper() != Looper.getMainLooper())
                            throw new IllegalStateException("Whisper observation must be captured at its main-thread impression");
                        // The writer must be retained by its own required runtime component until
                        // closeAfterDrain ACK. Admission is main-safe; persistence is never inline.
                        Runnable captured = WhisperObservation.capture(System::currentTimeMillis, observed -> {
                            if (Looper.myLooper() == Looper.getMainLooper())
                                throw new IllegalStateException("Whisper accounting cannot run on main");
                            ownerWrites.requireWrite();
                            counters.whisperImpression(identity(), observed);
                        });
                        ownerWrites.submit(identity(), captured).whenComplete((ignored, failure) -> {
                            if (failure != null) requestStop();
                        });
                    }
                });
    }

    /** Bind the real window renderer, settings updates and closure to one registered component. */
    public SubliminalOverlayController bindWhispers(Context accessibilityWindowHost, Component component) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Whisper windows must be bound on the main thread");
        requireComponent(component); // Registered on the owner worker before posting main creation.
        AtomicReference<SubliminalOverlayController> window = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());
        component.installShutdown(afterDrained -> {
            Runnable close = () -> {
                SubliminalOverlayController controller = window.get();
                try {
                    if (controller != null) {
                        controller.close(() -> {
                            try {
                                if (!controller.managedResourcesDrained())
                                    throw new IllegalStateException("Whisper resources have not drained");
                                afterDrained.run();
                            } catch (RuntimeException failure) {
                                component.cleanupFailed(); // May run after the initial close returns.
                            }
                        });
                    } else afterDrained.run();
                } catch (RuntimeException failure) {
                    component.cleanupFailed(); // A posted close failure must also retain occupancy.
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) close.run();
            else if (!main.post(close)) throw new IllegalStateException("Window cleanup queue unavailable");
        });
        component.installConfigurationReceiver(preferences -> {
            if (Looper.myLooper() != Looper.getMainLooper())
                throw new IllegalStateException("Whisper settings must apply on the main thread");
            SubliminalOverlayController controller = window.get();
            if (controller == null) throw new IllegalStateException("Whisper component is not initialized");
            if (!insideEffect()) throw new IllegalStateException("Unadmitted Whisper configuration");
            // Nested admission is reentrant within the module update effect. A refused/queued
            // renderer update is NOT an applied snapshot and cannot advance the runtime revision.
            if (!controller.updateSettings(new SubliminalSettingsRepository(preferences.main)))
                throw new IllegalStateException("Whisper configuration was not applied");
        });
        try {
            if (!runStartupEffect(() -> window.set(createWhispers(accessibilityWindowHost)))) {
                component.failed();
                throw new SecurityException("Whisper startup capability revoked");
            }
            component.readyAsync(result -> {
                if (result != ActiveRuntimeCoordinator.Result.OK) component.failed();
            });
            // The gateway activates only after all posted readiness receipts are acknowledged.
            return window.get();
        } catch (RuntimeException | Error failed) {
            component.failed();
            throw failed;
        }
    }

}
