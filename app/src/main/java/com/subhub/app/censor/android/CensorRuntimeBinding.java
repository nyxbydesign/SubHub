package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.RequiresApi;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.service.InternalRuntimeSession;
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
public final class CensorRuntimeBinding implements InternalRuntimeSession.Capture {
    public static final String COMPONENT = "internal_window_censor";
    private static final SceneEngine.Policy DEMO_SCENE_POLICY =
            new SceneEngine.Policy(16, 96, 48, 256, 750, 2_500, .5);
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
    private volatile AccessibilityWindowReader.Window approvedForeground;
    private boolean activated;
    private long lastTapEvent, lastTapUptime;
    private CensorBindingWork.Ticket wakeTicket; // Main only.
    private final AtomicBoolean firstWindowFrameReported = new AtomicBoolean();

    public static CensorRuntimeBinding forDemo(InternalRuntimeSession session, LocalNotice notices) {
        RuntimeCaptureHost.InternalRequest exact = session.request();
        return new CensorRuntimeBinding(exact, COMPONENT, DEMO_SCENE_POLICY, () -> {
            String ime = android.provider.Settings.Secure.getString(
                    exact.connection.accessibility().service().getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            int separator = ime == null ? -1 : ime.indexOf('/');
            return separator > 0 ? ime.substring(0, separator) : "";
        }, session::protectionEligible, AchievementManager::forInternalWindow, (owner, configuration, window) -> {
            var evidence = session.modules().evidence();
            return window != null && evidence != null && evidence.owner.equals(owner)
                    && evidence.configuration == configuration && evidence.windowId == window.id
                    && evidence.packageName.equals(window.packageName) && session.modules().eligible(evidence);
        }, notices);
    }

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
                        effect -> !sealed.get() && request.connection.isConnected()
                                && request.runtime.runEffect(effect), artwork);
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
    @Override public void activationAcknowledged() {
        requireMain();
        activated = true;
        enqueueDemand(request.runtime.configuration());
    }
    /** Exact-owner foreground/usage acknowledgement or ordinary source demand. */
    public void requestReconciliation() { enqueueDemand(request.runtime.configuration()); }

    @Override public void sourceChanged() {
        sourceEvent(() -> {
            if (approvedForeground != null && !foreground.eligible(request.owner(), adopted, approvedForeground)) {
                approvedForeground = null;
                product.stop();
            }
            product.request();
        });
    }

    @Override public void accessibilityEvent(AccessibilityEvent event) {
        Objects.requireNonNull(event);
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            foregroundChanged(event.getPackageName() == null ? "" : event.getPackageName().toString(),
                    event.getClassName() == null ? "" : event.getClassName().toString(),
                    event.getWindowId(), type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        } else if (approvedForeground != null && approvedForeground.id == event.getWindowId()
                && event.getPackageName() != null && approvedForeground.packageName.contentEquals(event.getPackageName())) {
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) scrolled(event);
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                contentChanged(event.getWindowId(), event.getContentChangeTypes());
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) recordTap(event);
        }
    }

    private void recordTap(AccessibilityEvent event) {
        sourceEvent(() -> request.runtime.runEffect(() -> {
            AccessibilityWindowReader.Window window = approvedForeground;
            RuntimeConfiguration configuration = adopted;
            if (window == null || !product.isActive() || !foreground.eligible(request.owner(), configuration, window)) return;
            long eventTime = event.getEventTime(), now = android.os.SystemClock.uptimeMillis();
            if (eventTime <= lastTapEvent || lastTapUptime > 0 && now - lastTapUptime < 500) return;
            var node = event.getSource();
            if (node == null) return;
            try {
                if (!node.isVisibleToUser() || node.getPackageName() == null
                        || !window.packageName.contentEquals(node.getPackageName())) return;
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (!request.resources.taps.matchesClickAt(bounds.left, bounds.top, bounds.right, bounds.bottom, eventTime, now)) return;
            } finally { node.recycle(); }
            lastTapEvent = eventTime;
            lastTapUptime = now;
            data.recordTap(configuration, com.subhub.app.stats.OwnerObservation.capture(System.currentTimeMillis()),
                    () -> !sealed.get() && request.connection.isConnected() && adopted == configuration
                            && approvedForeground == window);
        }));
    }

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
    @Override public void screenChanged(String action) { sourceEvent(() -> product.screenStateChanged(action)); }
    @Override public void powerChanged() { sourceEvent(() -> product.powerPolicyChanged()); }
    /** Exact owner pause/eligibility changes withdraw the held selection before a fresh lookup. */
    public void protectionChanged() { sourceEvent(() -> product.ownershipChanged()); }
    private void sourceEvent(Runnable demand) {
        requireMain();
        if (sealed.get() || product == null || !activated) return;
        if (!request.connection.isConnected()) { seal(); return; }
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
        @Override public void prepared(long requestId, PixelFrame.Kind kind, long nanos, int bytes) {
            if (kind == PixelFrame.Kind.ACCESSIBILITY_WINDOW && bytes > 0
                    && firstWindowFrameReported.compareAndSet(false, true))
                com.subhub.app.runtime.RuntimeStartupDiagnostics.demoWindowFramePrepared();
        }

        @Override public void foreground(AccessibilityWindowReader.Window window) {
            if (sealed.get()) { if (product != null) product.stop(); return; }
            RuntimeConfiguration image = adopted;
            if (!foreground.eligible(request.owner(), image, window)) {
                approvedForeground = null;
                if (product != null) product.stop();
                status = new Status(image, Stage.PENDING_FOREGROUND);
            } else approvedForeground = window;
        }
        @Override public void sessionFailure(Throwable failure) {
            com.subhub.app.runtime.RuntimeStartupDiagnostics.report(COMPONENT, failure);
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
        // The runtime owner retires its shared cache after every attached consumer returns.
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
