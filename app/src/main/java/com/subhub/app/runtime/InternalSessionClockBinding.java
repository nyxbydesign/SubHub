package com.subhub.app.runtime;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.internal.InternalRuntimePreferences;
import com.subhub.app.penance.InternalPenanceRules;
import com.subhub.app.penance.PaidPauseRuleSettings;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Inactive until explicitly assembled and ACTIVE-acknowledged by the owning factory. All timed
 * work stays registered through actual callback return. Stop does not guess Handler cancellation,
 * infer drain from a timeout, close the shared writer, or touch physical alarms/payment/peer state.
 * The accounting component's one Session.finishAndRelease owns durable clock + statistics cleanup.
 */
public final class InternalSessionClockBinding {
    public static final String COMPONENT = "internal_session_clock";
    interface Dispatcher {
        /** False proves no enqueue. A throw has unknown acceptance and must retain the callback. */
        boolean post(Runnable callback, long delayMillis);
    }
    interface Clock {
        InternalSessionClock.Sample now();
        default long elapsed() { return now().elapsed; }
    }
    private final RuntimeExecution<RuntimeConfiguration> runtime;
    private final InternalOwnerDataBroker.Session data;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final Supplier<RuntimeFenceObservation> firstFence;
    private final Dispatcher dispatcher;
    private final Clock clock;
    private final Runnable eligibilityChanged;
    private final CompletableFuture<InternalSessionClock.Snapshot> prepared = new CompletableFuture<>();
    private final AtomicBoolean sealed = new AtomicBoolean(), activated = new AtomicBoolean(), tickQueued = new AtomicBoolean();
    private volatile RuntimeConfiguration adopted, acknowledged;
    private volatile InternalSessionClock.State state;
    private volatile InternalSessionClock.Snapshot snapshot;
    private volatile boolean failed, drained;
    private final Object workLock = new Object();
    private int outstanding;
    private boolean closing, completionStarted;
    private Runnable afterDrained;

    /** Owner worker after statistics STARTUP ACK. startedElapsed was captured beside started. */
    public InternalSessionClockBinding(RuntimeEnvironment runtime, InternalOwnerDataBroker.Session data,
            OwnerWriteQueue writes, OwnerObservation started, long startedElapsed,
            Supplier<RuntimeFenceObservation> firstFence, Runnable eligibilityChanged) {
        this(runtime, data, writes, new InternalSessionClock.Sample(started, startedElapsed), firstFence,
                eligibilityChanged, androidDispatcher(), new Clock() {
                    @Override public InternalSessionClock.Sample now() { return new InternalSessionClock.Sample(
                            OwnerObservation.capture(System.currentTimeMillis()), SystemClock.elapsedRealtime()); }
                    @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
                }, new SecureRandom());
    }
    /** Same real assembly, with controlled queue/clock seams for host lifecycle diagnostics. */
    InternalSessionClockBinding(RuntimeExecution<RuntimeConfiguration> runtime, InternalOwnerDataBroker.Session data,
            OwnerWriteQueue writes, InternalSessionClock.Sample started, Supplier<RuntimeFenceObservation> firstFence,
            Runnable eligibilityChanged, Dispatcher dispatcher, Clock clock, SecureRandom random) {
        InternalRuntimePreferences.requireWorker();
        this.runtime = Objects.requireNonNull(runtime); this.data = Objects.requireNonNull(data);
        this.firstFence = Objects.requireNonNull(firstFence); this.eligibilityChanged = Objects.requireNonNull(eligibilityChanged);
        this.dispatcher = Objects.requireNonNull(dispatcher); this.clock = Objects.requireNonNull(clock);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL || !runtime.identity().equals(data.identity())
                || !runtime.identity().equals(writes.identity())) throw new SecurityException("Clock needs the exact INTERNAL owner");
        data.acknowledgedSessionStamp();
        adopted = runtime.configuration(); requireConfiguration(adopted); acknowledged = adopted;
        selection(adopted);
        rules(adopted); // Reject malformed immutable rules before any durable clock initialization.
        component = runtime.registerComponent(COMPONENT);
        component.installConfigurationReceiver(new RuntimeExecution.ConfigurationReceiver<RuntimeConfiguration>() {
            @Override public void apply(RuntimeConfiguration configuration) {
                requireConfiguration(configuration); selection(configuration); rules(configuration);
                if (sealed.get() || configuration.configRevision < adopted.configRevision)
                    throw new SecurityException("Clock configuration is stale or closed");
                // Ordinary duration selectors do not rewrite an existing pact's selected duration,
                // bounds or hidden choice. Current pause grants likewise retain their original terms.
                adopted = configuration;
            }
            @Override public void acknowledged(RuntimeConfiguration configuration) {
                InternalRuntimePreferences.requireWorker(); requireConfiguration(configuration);
                if (sealed.get() || adopted != configuration) throw new SecurityException("Clock configuration was not adopted");
                acknowledged = configuration;
                // No new data/effect admission in this acknowledgment callback. The retained main
                // callback starts later and obtains its own NORMAL writer/effect admissions.
                postTick(0);
            }
        });
        component.installShutdown(this::closeFromRuntime);
        Ticket initialization = begin();
        if (initialization == null) {
            prepared.completeExceptionally(new IllegalStateException("Clock stopped before initialization")); return;
        }
        AtomicReference<InternalSessionClock.State> initialized = new AtomicReference<>();
        try {
            writes.submitStartup(runtime.identity(), () -> initialized.set(data.initializeSessionClock(runtime, started, random)))
                    .whenComplete((ignored, failure) -> {
                        try {
                            if (failure != null) { fail(failure); return; }
                            state = Objects.requireNonNull(initialized.get(), "Missing durable clock initialization");
                            if (sealed.get()) { prepared.completeExceptionally(new IllegalStateException("Clock stopped during initialization")); return; }
                            snapshot = InternalSessionClock.live(state, runtime.identity(), clock.now(), rules(acknowledged), false);
                            Ticket readiness = begin();
                            if (readiness == null) { prepared.completeExceptionally(new IllegalStateException("Clock readiness revoked")); return; }
                            component.readyAsync(result -> {
                                try {
                                    if (result == ActiveRuntimeCoordinator.Result.OK && !sealed.get()) prepared.complete(snapshot);
                                    else fail(new IllegalStateException("Clock readiness was not acknowledged: " + result));
                                } finally { readiness.close(); }
                            });
                        } catch (RuntimeException | Error uncertain) { fail(uncertain); }
                        finally { initialization.close(); }
                    });
        } catch (OwnerWriteQueue.AdmissionRejected refused) {
            initialization.close(); fail(refused); // Definite pre-dispatch refusal only.
        } catch (RuntimeException | Error uncertain) {
            fail(uncertain); // No guessed return: initialization obligation remains retained.
        }
    }
    private static Dispatcher androidDispatcher() {
        Handler main = new Handler(Looper.getMainLooper()); return main::postDelayed;
    }
    public CompletableFuture<InternalSessionClock.Snapshot> prepared() {
        CompletableFuture<InternalSessionClock.Snapshot> result = new CompletableFuture<>();
        result.whenComplete((ignored, error) -> { if (result.isCancelled()) seal(); });
        prepared.whenComplete((value, error) -> {
            boolean delivered = error == null ? result.complete(value) : result.completeExceptionally(error);
            if (!delivered) seal();
        });
        return result;
    }
    /** Fresh immutable UI projection, never IO, a scheduler mutation, or a new live admission. */
    public InternalSessionClock.Snapshot snapshot() {
        InternalSessionClock.Snapshot receipt = snapshot;
        InternalSessionClock.State known = receipt == null ? state : receipt.state;
        if ((sealed.get() || failed) && known != null) return InternalSessionClock.stopping(known);
        if (receipt == null) return null;
        RuntimeConfiguration configuration = acknowledged;
        RuntimeConfiguration visible = runtime.configuration();
        long revision = runtime.appliedConfigurationRevision();
        boolean coherent = visible.configRevision == revision;
        boolean current = coherent && adopted == configuration && configuration == visible
                && receipt.configurationRevision == revision;
        InternalSessionClock.Snapshot projected = InternalSessionClock.project(receipt, clock.elapsed(),
                coherent ? revision : -1, current);
        // Stop/configuration may race the read-only calculation. Recheck the publications before
        // returning, without ever treating this observation as a replacement for an effect gate.
        if (sealed.get() || failed) return InternalSessionClock.stopping(receipt.state);
        if (configuration != acknowledged || visible != runtime.configuration()
                || revision != runtime.appliedConfigurationRevision() || adopted != configuration)
            return InternalSessionClock.project(receipt, projected.observedElapsed, -1, false);
        return projected;
    }
    /** Cache/monotonic check only. Callers still need their own ordinary runtime effect gate. */
    public boolean protectionEligible() {
        InternalSessionClock.Snapshot current = snapshot();
        return activated.get() && current != null && current.protectionEligible;
    }
    /** Factory invokes only after the exact gateway ACTIVE acknowledgment, never on STARTUP ACK. */
    public void activationAcknowledged() {
        if (sealed.get()) return;
        if (!prepared.isDone() || prepared.isCompletedExceptionally() || prepared.isCancelled())
            throw new IllegalStateException("Clock is not durably prepared");
        if (activated.compareAndSet(false, true)) postTick(0);
    }
    /** Explicit local/nonfinancial action. UI must label it simulated, never Pay or Purchase. */
    public CompletableFuture<InternalSessionClock.Snapshot> simulatePause(InternalSessionClock.Snapshot expected) {
        CompletableFuture<InternalSessionClock.Snapshot> result = new CompletableFuture<>();
        result.whenComplete((ignored, error) -> { if (result.isCancelled()) seal(); });
        RuntimeConfiguration configuration = acknowledged;
        if (expected == null || !expected.canSimulatePause || !protectionEligible()
                || expected.configurationRevision != configuration.configRevision
                || !expected.state.session.equals(runtime.identity().session)) {
            result.completeExceptionally(new IllegalStateException("Simulated pause is no longer available")); return result;
        }
        InternalSessionClock.Rules capturedRules = rules(configuration);
        String requestId = UUID.randomUUID().toString();
        submit(configuration, () -> {
            // This is the actual admitted owner-write/runtime-effect operation, not the earlier
            // UI request. One paired sample governs eligibility and the persisted grant so queue
            // delay cannot assess a pause whose entire entitlement elapsed before it was granted.
            InternalSessionClock.Sample grantedAt = clock.now();
            InternalSessionClock.Snapshot now = InternalSessionClock.live(state, runtime.identity(), grantedAt, capturedRules, true);
            if (!now.canSimulatePause || state.pauseSequence != expected.state.pauseSequence) return null;
            return data.simulateSessionPause(configuration, grantedAt, expected.state.pauseSequence, requestId);
        }, result);
        return result;
    }
    private void postTick(long delay) {
        if (sealed.get() || !activated.get() || !tickQueued.compareAndSet(false, true)) return;
        post(() -> {
            tickQueued.set(false);
            if (sealed.get() || !activated.get()) return;
            RuntimeConfiguration configuration = acknowledged;
            if (configuration != adopted || configuration != runtime.configuration()) { postTick(16); return; }
            CompletableFuture<InternalSessionClock.Snapshot> result = new CompletableFuture<>();
            submit(configuration, () -> data.tickSessionClock(configuration, clock.now()), result);
            result.whenComplete((ignored, error) -> { if (!sealed.get()) postTick(error == null ? 1000 : 16); });
        }, delay);
    }
    private void submit(RuntimeConfiguration configuration, Supplier<InternalSessionClock.State> operation,
            CompletableFuture<InternalSessionClock.Snapshot> result) {
        Ticket ticket = begin();
        if (ticket == null) { result.completeExceptionally(new IllegalStateException("Clock is stopping")); return; }
        AtomicReference<InternalSessionClock.State> receipt = new AtomicReference<>();
        try {
            data.executeNormal(() -> {
                // The shared writer retains the callback. This separate nonblocking effect gate
                // serializes actual config validation and mutation against live configuration apply.
                runtime.runEffect(() -> {
                    if (!sealed.get() && activated.get() && configuration == adopted
                            && configuration == acknowledged && configuration == runtime.configuration())
                        receipt.set(operation.get());
                });
            }).whenComplete((ignored, error) -> {
                try {
                    if (error != null) { fail(error); result.completeExceptionally(error); return; }
                    InternalSessionClock.State saved = receipt.get();
                    if (saved == null || sealed.get()) {
                        result.completeExceptionally(new IllegalStateException("Clock action was not admitted")); return;
                    }
                    state = saved;
                    InternalSessionClock.Snapshot observed = InternalSessionClock.live(saved, runtime.identity(), clock.now(), rules(configuration), true);
                    if (configuration == acknowledged && configuration == runtime.configuration()) snapshot = observed;
                    if (observed.status == InternalSessionClock.Status.EXPIRED) {
                        seal(); result.complete(observed); return;
                    }
                    // Every posted notifier has its own closure obligation; it never borrows UI or
                    // effect authority from this completed write. No publication claims payment.
                    post(() -> { if (!sealed.get()) eligibilityChanged.run(); }, 0);
                    result.complete(observed);
                } catch (RuntimeException | Error uncertain) { fail(uncertain); result.completeExceptionally(uncertain); }
                finally { ticket.close(); }
            });
        } catch (OwnerWriteQueue.AdmissionRejected refused) {
            ticket.close(); fail(refused); result.completeExceptionally(refused);
        } catch (RuntimeException | Error uncertain) {
            fail(uncertain); result.completeExceptionally(uncertain); // Accepted outcome unknown; keep ticket.
        }
    }
    public void seal() { sealed.set(true); runtime.requestStop(); }
    public boolean managedResourcesDrained() { return drained; }
    private void fail(Throwable failure) {
        failed = true; prepared.completeExceptionally(failure); seal();
    }
    private void closeFromRuntime(Runnable completion) {
        sealed.set(true);
        // Exact first-fence evidence is memory-only here; the shared writer persists it in cleanup.
        RuntimeFenceObservation fence = Objects.requireNonNull(firstFence.get(), "Missing first clock fence");
        new OwnerObservation(fence.at, fence.zone);
        prepared.completeExceptionally(new IllegalStateException("Clock stopped"));
        synchronized (workLock) {
            if (closing) throw new IllegalStateException("Clock closure already requested");
            closing = true; afterDrained = completion;
        }
        completeIfDrained();
    }
    private Ticket begin() {
        synchronized (workLock) {
            if (sealed.get() || closing) return null;
            outstanding = Math.addExact(outstanding, 1); return new Ticket();
        }
    }
    private final class Ticket implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (workLock) { if (closed) return; closed = true; outstanding--; }
            completeIfDrained();
        }
    }
    private void completeIfDrained() {
        Runnable completion;
        synchronized (workLock) {
            if (!closing || outstanding != 0 || completionStarted) return;
            completionStarted = true; completion = afterDrained;
        }
        // The closure ACK may synchronously expose IDLE or query this component. Publish the
        // already-observed zero-work state first, never a misleading pending-cleanup snapshot.
        drained = true;
        try { completion.run(); }
        catch (RuntimeException | Error uncertain) { drained = false; failed = true; component.cleanupFailed(); }
    }
    private void post(Runnable action, long delay) {
        Ticket ticket = begin(); if (ticket == null) return;
        Dispatch dispatched = new Dispatch(ticket, action);
        try {
            boolean accepted = dispatcher.post(dispatched::run, delay);
            if (!accepted && dispatched.entered.get()) throw new IllegalStateException("Dispatcher denied an executed clock callback");
            dispatched.settled(!accepted);
            if (!accepted) fail(new IllegalStateException("Clock callback queue unavailable"));
        } catch (RuntimeException | Error uncertain) { dispatched.settled(false); fail(uncertain); }
    }
    private final class Dispatch {
        final Ticket ticket; final Runnable action; final AtomicBoolean entered = new AtomicBoolean();
        boolean callbackReturned, dispatchReturned, refused;
        Dispatch(Ticket ticket, Runnable action) { this.ticket = ticket; this.action = action; }
        void run() {
            if (!entered.compareAndSet(false, true)) return;
            try { if (!sealed.get()) action.run(); }
            catch (RuntimeException | Error uncertain) { fail(uncertain); }
            finally { synchronized (this) { callbackReturned = true; } release(); }
        }
        void settled(boolean noEnqueue) { synchronized (this) { dispatchReturned = true; refused |= noEnqueue; } release(); }
        void release() {
            synchronized (this) { if (!dispatchReturned || !callbackReturned && !refused) return; }
            ticket.close();
        }
    }
    private void requireConfiguration(RuntimeConfiguration configuration) {
        if (configuration == null || !runtime.identity().equals(configuration.owner)) throw new SecurityException("Different clock configuration owner");
    }
    private static InternalSessionClock.Selection selection(RuntimeConfiguration configuration) {
        return new InternalSessionClock.Selection(configuration.duration.getLong("duration", -1),
                configuration.duration.getLong("minimum", 3_600_000), configuration.duration.getLong("maximum", 86_400_000),
                configuration.duration.getBoolean("hidden", false));
    }
    private static InternalSessionClock.Rules rules(RuntimeConfiguration configuration) {
        InternalPenanceRules penance = new InternalPenanceRules(configuration.penance);
        PaidPauseRuleSettings pause = PaidPauseRuleSettings.configuration(configuration.penance);
        // An enabled internal rule is this local simulation's eligibility, including permanent
        // sessions. It is not a claim of DEVICE Hardcore/admin authority or a paid entitlement.
        return new InternalSessionClock.Rules(configuration.configRevision, penance.isEnabled(), pause.isEnabled(),
                pause.isEnabled(), pause.getPriceCents(), pause.getDurationMinutes(), penance.getCurrency());
    }
}
