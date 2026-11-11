package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.stats.AchievementManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exact modern INTERNAL product attachment. It has no independent start/reservation, DEVICE
 * singleton, telemetry or payment path. API26–33 use separate legacy bindings, never this guard.
 */
@RequiresApi(34)
public final class CensorRuntimeBinding implements RuntimeCaptureHost.Attachment {
    public interface ForegroundAdmission {
        /**
         * Main-thread cached, exact-owner decision only. False withdraws Censor immediately while
         * owned usage/limits work is pending. That owner calls requestReconciliation() after its actual ACK.
         */
        boolean eligible(RuntimeLease.Identity owner, RuntimeConfiguration configuration,
                AccessibilityWindowReader.Window foreground);
    }
    public interface LocalNotice {
        /** Actual callback runs under counted owner accounting, never a physical notifier. */
        void assessed(RuntimeLease.Identity owner, com.subhub.app.penance.PenanceInfraction kind,
                int cents, String currency, com.subhub.app.stats.OwnerObservation observed,
                BooleanSupplier stillCurrent);
    }
    public enum Stage { PREPARED, ADOPTED_AWAITING_ACK, PENDING_RECONCILIATION, PENDING_FOREGROUND,
        STOPPING, DRAINED, FAILED }
    /** This status never claims model readiness, first-frame pixels or actual compositor visibility. */
    public static final class Status {
        public final RuntimeLease.Identity owner;
        public final long configurationRevision;
        public final Stage stage;
        private Status(RuntimeConfiguration configuration, Stage stage) {
            owner = configuration.owner; configurationRevision = configuration.configRevision; this.stage = stage;
        }
    }
    private final RuntimeCaptureHost.InternalRequest request;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final SceneEngine.Policy policy;
    private final ForegroundAdmission foreground;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CensorBindingWork callbacks = new CensorBindingWork();
    private final AtomicBoolean sealed = new AtomicBoolean(), attached = new AtomicBoolean(), closing = new AtomicBoolean();
    private final AtomicBoolean drained = new AtomicBoolean(), acknowledgementQueued = new AtomicBoolean();
    private final AtomicInteger closeCallbacks = new AtomicInteger();
    private final List<Runnable> drainObservers = new ArrayList<>();
    private final OwnedCensorProductData data;
    private final OwnedCensorArtwork artwork;
    private volatile RuntimeConfiguration adopted;
    private volatile Status status;
    private volatile boolean cleanupFailed, nativeDrainProved;
    private volatile Runnable registrationClosed;
    private CensorProductSession product; // Main only, retained through actual native drain.
    private RuntimeConfiguration wanted; // Main only; exact acknowledged demand.
    private CensorBindingWork.Ticket wakeTicket; // Main only.

    /** The factory is called on the same serial owner worker, after the statistics STARTUP ACK. */
    public CensorRuntimeBinding(RuntimeCaptureHost.InternalRequest request, String componentName,
            SceneEngine.Policy policy, Supplier<String> inputMethod,
            BooleanSupplier protectionEligible,
            Function<RuntimeConfiguration, AchievementManager.OwnerFacts> capturedFacts,
            ForegroundAdmission foreground, LocalNotice notices) {
        requireWorker(); this.request = Objects.requireNonNull(request);
        if (request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW || request.plan.sdkInt < 34)
            throw new IllegalArgumentException("Modern Censor requires the selected API34 window route");
        this.policy = Objects.requireNonNull(policy); Objects.requireNonNull(notices);
        this.foreground = Objects.requireNonNull(foreground); adopted = request.initialConfiguration;
        status = new Status(adopted, Stage.PREPARED);
        // These constructors only bind immutable data and in-memory helpers. No OS resources yet.
        data = new OwnedCensorProductData(request.runtime, request.data, adopted, inputMethod,
                request.resources.popupHints, protectionEligible, capturedFacts, notices::assessed);
        artwork = new OwnedCensorArtwork(request.storage, adopted);
        component = request.runtime.registerComponent(componentName);
        component.installConfigurationReceiver(new RuntimeExecution.ConfigurationReceiver<RuntimeConfiguration>() {
            @Override public void apply(RuntimeConfiguration configuration) {
                requireMain(); requireOpen();
                if (product == null) throw new IllegalStateException("Censor product not attached");
                CensorProductSession.Adoption receipt = product.adoptOwnedConfiguration(data, artwork, configuration);
                if (receipt.configuration != configuration) throw new IllegalStateException("Different Censor adoption receipt");
                adopted = configuration; status = new Status(configuration, Stage.ADOPTED_AWAITING_ACK);
                wanted = null; // A queued older wake can never re-adopt its old settings.
            }
            @Override public void acknowledged(RuntimeConfiguration configuration) {
                requireWorker(); requireOpen();
                if (adopted != configuration) throw new SecurityException("Unadopted Censor accounting revision");
                data.acknowledged(configuration);
                enqueueDemand(configuration); // Counted; may run before core ordinary admission reopens.
            }
        });
        component.installShutdown(this::closeFromRuntime);
    }
    @Override public RuntimeCaptureHost.Request request() { return request; }
    public Status status() { return status; }

    @Override public void attach(RuntimeCaptureHost.ProjectionGrant projection) {
        requireMain(); requireOpen();
        if (projection != null || !attached.compareAndSet(false, true))
            throw new IllegalStateException("Censor attachment is single-use and not projection");
        CensorBindingWork.Ticket call = callbacks.begin();
        if (call == null) throw new SecurityException("Censor attachment sealed");
        boolean[] creationEntered = { false };
        try {
            AccessibilityService host = request.connection.accessibility().service();
            if (!request.runtime.runStartupEffect(() -> {
                creationEntered[0] = true;
                product = new CensorProductSession(host, policy, request.resources.taps, observer,
                        (context, lane, configured, partition, fallback) -> VisualDetectorWorker.initializeOwned(
                                context, lane, configured, partition, fallback, request.resources.providers, request.owner()),
                        () -> {}, () -> data, () -> {},
                        effect -> !sealed.get() && request.runtime.runEffect(effect), artwork);
                product.adoptOwnedConfiguration(data, artwork, adopted);
            })) throw new SecurityException("Censor startup capability revoked");
            CensorBindingWork.Ticket ready = callbacks.begin();
            if (ready == null) { component.failed(); return; }
            component.readyAsync(result -> {
                try { if (result != ActiveRuntimeCoordinator.Result.OK) component.failed(); }
                finally { ready.close(); }
            });
        } catch (RuntimeException | Error failed) {
            // If constructor/attach failed after partial creation without returning an object,
            // retain occupancy rather than asserting those native resources never existed.
            if (creationEntered[0] && product == null) cleanupFailed = true;
            status = new Status(adopted, Stage.FAILED); component.failed(); throw failed;
        } finally { call.close(); }
    }

    /** Gateway calls only after ACTIVE journal ACK. This is demand, never an activation capability. */
    @Override public void activationAcknowledged() { enqueueDemand(request.runtime.configuration()); }
    /** Exact-owner foreground/usage acknowledgement or ordinary source demand. */
    public void requestReconciliation() { enqueueDemand(request.runtime.configuration()); }

    /** Service event forwarding for this exact attachment. Raw events are never retained here. */
    public void foregroundChanged(String packageName, String className, int windowId, boolean windowStateChange) {
        sourceEvent(() -> product.foregroundEvent(packageName, className, windowId, windowStateChange));
    }
    public void contentChanged(int windowId, int contentChangeTypes) {
        sourceEvent(() -> product.contentChanged(windowId, contentChangeTypes));
    }
    public void scrolled(android.view.accessibility.AccessibilityEvent event) {
        Objects.requireNonNull(event);
        // The actual reader gates copying, worker native reads and late delivery independently.
        // If admission is busy it owns an ordered gap/retry, not a retained recyclable raw event.
        sourceEvent(() -> product.scrolled(event));
    }
    public void screenChanged(String action) { sourceEvent(() -> product.screenStateChanged(action)); }
    public void powerChanged() { sourceEvent(() -> product.powerPolicyChanged()); }
    /** Exact owner pause/eligibility changes withdraw the held selection before a fresh lookup. */
    public void protectionChanged() { sourceEvent(() -> product.ownershipChanged()); }
    private void sourceEvent(Runnable demand) {
        requireMain();
        if (sealed.get() || product == null) return;
        CensorBindingWork.Ticket ticket = callbacks.begin();
        if (ticket == null) return;
        try {
            // These are demand/invalidation operations. Native source reads/draw/publication
            // retain the Product/Controller's exact runtime gate at actual consumption.
            if (ticket.current() && !sealed.get()) demand.run();
        } catch (RuntimeException | Error failure) { component.failed(); throw failure; }
        finally { ticket.close(); }
    }

    private void enqueueDemand(RuntimeConfiguration image) {
        CensorBindingWork.Ticket ticket = callbacks.begin();
        if (ticket == null) return;
        Runnable delivery = () -> {
            try {
                if (!ticket.current() || sealed.get() || adopted != image) return;
                wanted = image;
                if (wakeTicket == null) { wakeTicket = callbacks.begin(); wake(); }
            } finally { ticket.close(); }
        };
        postOwned(delivery, ticket);
    }
    private void wake() {
        requireMain();
        CensorBindingWork.Ticket ticket = wakeTicket;
        if (ticket == null) return;
        if (!ticket.current() || sealed.get() || wanted == null || adopted != wanted || product == null) {
            wakeTicket = null; ticket.close(); return;
        }
        RuntimeConfiguration image = wanted;
        try {
            if (!request.runtime.runEffect(() -> {
                if (adopted != image || request.runtime.configuration() != image)
                    throw new SecurityException("Censor wake has no acknowledged configuration");
                status = new Status(image, Stage.PENDING_RECONCILIATION);
                product.request();
            })) {
                try {
                    if (!main.postDelayed(this::wake, 16)) {
                        wakeTicket = null; ticket.close(); component.failed();
                    }
                } catch (RuntimeException | Error uncertain) {
                    // May already be enqueued. The exact wake retains its callback obligation.
                    cleanupFailed = true; component.cleanupFailed();
                }
                return;
            }
            wakeTicket = null; ticket.close();
        } catch (RuntimeException | Error failure) {
            wakeTicket = null; ticket.close(); component.failed();
        }
    }
    private void postOwned(Runnable delivery, CensorBindingWork.Ticket ticket) {
        try {
            if (!main.post(delivery)) { ticket.close(); component.failed(); }
        } catch (RuntimeException | Error uncertain) {
            // Callback may be queued. It retains its ticket until actual return.
            cleanupFailed = true; component.failed();
        }
    }
    private final CensorProductSession.Observer observer = new CensorProductSession.Observer() {
        @Override public void foreground(AccessibilityWindowReader.Window window) {
            if (sealed.get()) { if (product != null) product.stop(); return; }
            RuntimeConfiguration image = adopted;
            if (!foreground.eligible(request.owner(), image, window)) {
                if (product != null) product.stop();
                status = new Status(image, Stage.PENDING_FOREGROUND);
            }
        }
        @Override public void sessionFailure(Throwable failure) {
            status = new Status(adopted, Stage.FAILED); component.failed();
        }
    };

    @Override public void seal() { sealed.set(true); request.runtime.requestStop(); }
    @Override public void beginClose(Runnable afterActualDrain) {
        Objects.requireNonNull(afterActualDrain);
        boolean alreadyDrained;
        synchronized (drainObservers) {
            alreadyDrained = drained.get();
            if (!alreadyDrained) drainObservers.add(afterActualDrain);
        }
        if (alreadyDrained) afterActualDrain.run(); else seal();
    }
    private void closeFromRuntime(Runnable afterActualDrain) {
        sealed.set(true); status = new Status(adopted, Stage.STOPPING);
        registrationClosed = Objects.requireNonNull(afterActualDrain);
        if (!closing.compareAndSet(false, true)) return;
        // Sealed retained wakes retire their own tickets on actual return. Product independently
        // owns its retry/read/model callbacks; only real quiescence starts resource teardown.
        callbacks.seal(() -> postClose(false));
    }
    private void postClose(boolean polling) {
        closeCallbacks.incrementAndGet();
        Runnable cleanup = () -> {
            try {
                if (!polling) {
                    if (product != null) product.close();
                    else data.accountingWorker().close();
                }
                if (!cleanupFailed && (product == null ? data.accountingWorker().terminated() : product.managedResourcesDrained()))
                    nativeDrainProved = true;
                else if (!cleanupFailed) postClose(true);
            } catch (RuntimeException | Error failed) { cleanupFailed = true; component.cleanupFailed(); }
            finally {
                if (closeCallbacks.decrementAndGet() == 0 && nativeDrainProved) acknowledgeDrain();
            }
        };
        try {
            boolean posted = polling ? main.postDelayed(cleanup, 16) : main.post(cleanup);
            if (!posted) { closeCallbacks.decrementAndGet(); cleanupFailed = true; component.cleanupFailed(); }
        } catch (RuntimeException | Error uncertain) { cleanupFailed = true; component.cleanupFailed(); }
    }
    private void acknowledgeDrain() {
        requireMain();
        if (!acknowledgementQueued.compareAndSet(false, true)) return;
        if (cleanupFailed || !nativeDrainProved || closeCallbacks.get() != 0) {
            component.cleanupFailed(); return;
        }
        // All native/model work has actually drained. Cache retirement is RAM-only. The core's
        // shutdown receipt schedules its own registration/journal work off main; no extra owner
        // dispatch is needed here and no arbitrary executor exception becomes closure proof.
        try { request.resources.providers.close(); }
        catch (RuntimeException | Error failure) { cleanupFailed = true; component.cleanupFailed(); return; }
        List<Runnable> observers;
        synchronized (drainObservers) {
            drained.set(true); status = new Status(adopted, Stage.DRAINED);
            observers = new ArrayList<>(drainObservers); drainObservers.clear();
        }
        registrationClosed.run();
        // Delivery failure cannot undo actual resource closure. The service still retains its
        // exact selection until it observes managedResourcesDrained and acknowledges that slot.
        for (Runnable callback : observers) {
            try { callback.run(); }
            catch (RuntimeException failure) { component.cleanupFailed(); }
        }
    }
    @Override public boolean physicalOccupancy() { return !drained.get(); }
    @Override public boolean managedResourcesDrained() { return drained.get() && !cleanupFailed; }
    private void requireOpen() { if (sealed.get() || !callbacks.isOpen()) throw new SecurityException("Censor owner closed"); }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Censor lifecycle requires main");
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Censor owner work cannot run on main");
    }
}
