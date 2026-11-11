package com.subhub.app.runtime;

import com.subhub.app.internal.InternalConfigurationStore;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.StatsRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concrete INTERNAL session assembly. Only the common factory may construct this after STARTING
 * was acknowledged. This class does not install that factory, grant start authority, create owner
 * storage, activate a session or select a physical service. It binds the already-open shared data
 * broker to the exact lease and keeps its required accounting component through durable cleanup.
 */
final class InternalRuntimeOwner {
    static final String ACCOUNTING_COMPONENT = "internal_accounting";
    final RuntimeEnvironment runtime;
    final OwnerWriteQueue writes;
    final OwnerObservation startObservation;
    final long startElapsed;
    final com.subhub.app.service.RuntimeCaptureHost.InternalResources sourceResources;
    private final OwnedResourceRegistry resources;
    private final RuntimeLease lease;
    RuntimeFenceObservation firstFence() { return resources.firstFence(lease); }
    private final AtomicReference<InternalOwnerDataBroker.Session> data = new AtomicReference<>();
    private final CompletableFuture<InternalOwnerDataBroker.SessionObservation> prepared = new CompletableFuture<>();
    private final CompletableFuture<Void> initializationReturned = new CompletableFuture<>();
    private final RuntimeExecution<RuntimeConfiguration>.Component accounting;
    private boolean sourceResourcesRetired;

    /** Owner worker, after the factory observes durable IDLE and all component receipts. */
    void retireSourceResources() {
        if (sourceResourcesRetired) return;
        sourceResources.providers.close();
        sourceResourcesRetired = true;
    }

    /** Owner lifecycle worker only. The immutable start clock was captured at admitted start. */
    InternalRuntimeOwner(ActiveRuntimeCoordinator coordinator, RuntimeLease lease,
            InternalConfigurationStore configuration, InternalSubRecord acknowledged,
            InternalOwnerDataBroker broker, Executor ownerLifecycle, Runnable requireOwnerWorker,
            OwnerObservation started) {
        // Existing direct accounting fixtures have no clock consumer. Production session
        // assembly uses the explicit paired observation captured by the common factory.
        this(coordinator, lease, configuration, acknowledged, broker, ownerLifecycle,
                requireOwnerWorker, started, -1);
    }
    InternalRuntimeOwner(ActiveRuntimeCoordinator coordinator, RuntimeLease lease,
            InternalConfigurationStore configuration, InternalSubRecord acknowledged,
            InternalOwnerDataBroker broker, Executor ownerLifecycle, Runnable requireOwnerWorker,
            OwnerObservation started, long startedElapsed) {
        Objects.requireNonNull(requireOwnerWorker).run();
        if (startedElapsed < -1) throw new IllegalArgumentException("Invalid admitted monotonic clock");
        startObservation = Objects.requireNonNull(started); startElapsed = startedElapsed;
        Objects.requireNonNull(broker); Objects.requireNonNull(started);
        RuntimeConfiguration initial = RuntimeConfiguration.internal(lease, configuration, acknowledged);
        AtomicReference<RuntimeEnvironment> execution = new AtomicReference<>();
        this.lease = Objects.requireNonNull(lease);
        resources = coordinator.resources(lease);
        sourceResources = new com.subhub.app.service.RuntimeCaptureHost.InternalResources(lease.identity);
        writes = new OwnerWriteQueue(lease, resources, broker.runtimeWorker(), failure -> {
            prepared.completeExceptionally(failure);
            RuntimeEnvironment exact = execution.get();
            if (exact != null) exact.requestStop();
        });
        runtime = new RuntimeEnvironment(coordinator, lease, initial, writes, (owner, observed) -> {
            InternalOwnerDataBroker.Session session = session();
            if (!owner.equals(session.identity())) throw new SecurityException("Different accounting owner");
            session.recordWhisperImpression(observed);
        }, ownerLifecycle);
        execution.set(runtime);
        accounting = runtime.registerComponent(ACCOUNTING_COMPONENT);
        // There is no mutable accounting-rule source here. Every event carries the admitted
        // immutable facts/rules; renderer receivers update those only after their journal ACK.
        accounting.usesNoConfiguration();
        accounting.installShutdown(afterDrained -> {
            RuntimeFenceObservation stopped = resources.firstFence(lease);
            // STARTUP work is separately counted by the writer. A closer may be invoked before
            // its queued initialization begins, so decide the binding only after its actual ACK/
            // failure returns. Never enqueue an empty cleanup ahead of a later session begin.
            initializationReturned.whenComplete((initialized, initializationFailure) -> {
                try {
                    InternalOwnerDataBroker.Session session = data.get();
                    CompletableFuture<Void> closed = session == null
                            ? writes.closeAfterDrain(() -> { }, afterDrained)
                            : session.finishAndRelease(new OwnerObservation(stopped.at, stopped.zone), afterDrained);
                    closed.whenComplete((ignored, failure) -> {
                        if (failure != null) { prepared.completeExceptionally(failure); accounting.cleanupFailed(); }
                    });
                } catch (RuntimeException | Error uncertain) {
                    prepared.completeExceptionally(uncertain); accounting.cleanupFailed();
                }
            });
        });
        try {
            // Bind only inside an actually admitted STARTUP callback. A definite pre-dispatch
            // refusal cannot leave an unstarted broker binding that has no cleanup path.
            AtomicReference<InternalOwnerDataBroker.SessionObservation> receipt = new AtomicReference<>();
            writes.submitStartup(lease.identity, () -> {
                InternalOwnerDataBroker.Session session = broker.bindRuntime(lease.identity, writes);
                if (!data.compareAndSet(null, session)) throw new IllegalStateException("Accounting already bound");
                receipt.set(session.beginSession(new StatsRepository.NativeSession(
                        lease.identity.session, started.at, started.zone)));
            }).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    initializationReturned.completeExceptionally(failure);
                    prepared.completeExceptionally(failure); accounting.failed(); return;
                }
                initializationReturned.complete(null);
                InternalOwnerDataBroker.SessionObservation acknowledgedStart = receipt.get();
                if (acknowledgedStart == null) {
                    prepared.completeExceptionally(new IllegalStateException("Missing STARTUP accounting receipt"));
                    accounting.failed(); return;
                }
                accounting.readyAsync(result -> {
                    if (result == ActiveRuntimeCoordinator.Result.OK) prepared.complete(acknowledgedStart);
                    else {
                        prepared.completeExceptionally(new IllegalStateException("Accounting readiness was not acknowledged: " + result));
                        accounting.failed();
                    }
                });
            });
        } catch (RuntimeException | Error failure) {
            // Even a dispatcher throw is potentially accepted. Never close/release a guessed
            // binding or repeat initialization. The queue/component retain any uncertain work.
            initializationReturned.completeExceptionally(failure);
            prepared.completeExceptionally(failure); accounting.failed();
        }
    }

    /** STARTUP statistics and component readiness ACK only, never ACTIVE/model/output readiness. */
    CompletableFuture<InternalOwnerDataBroker.SessionObservation> prepared() {
        CompletableFuture<InternalOwnerDataBroker.SessionObservation> response = new CompletableFuture<>();
        response.whenComplete((ignored, failure) -> { if (response.isCancelled()) runtime.requestStop(); });
        prepared.whenComplete((value, failure) -> {
            boolean delivered = failure == null ? response.complete(value) : response.completeExceptionally(failure);
            if (!delivered) runtime.requestStop();
        });
        return response;
    }

    /** Used to build exact typed renderers only after the acknowledged STARTUP receipt exists. */
    InternalOwnerDataBroker.Session session() {
        if (!prepared.isDone() || prepared.isCompletedExceptionally() || prepared.isCancelled())
            throw new IllegalStateException("Accounting has not acknowledged preparation");
        InternalOwnerDataBroker.Session session = data.get();
        if (session == null || !session.identity().equals(runtime.identity()))
            throw new SecurityException("Runtime data binding unavailable");
        session.acknowledgedSessionStamp();
        return session;
    }
}
