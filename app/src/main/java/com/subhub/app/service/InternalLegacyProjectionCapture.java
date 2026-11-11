package com.subhub.app.service;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.subhub.app.censor.android.LegacyTrackIds;
import com.subhub.app.censor.android.OwnedLegacyCensorData;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.overlay.LegacyRenderDependencies;
import com.subhub.app.overlay.OverlayController;
import com.subhub.app.penance.DwellInfractionTracker;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.OwnerObservation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actual owner-local API26+ projection pipeline. This preserves projection's ordinary single
 * visual lane, not Accessibility DISPLAY's separate QUALITY/text/scroll policy. No installer,
 * global settings/stats/penance/taps, physical notification or current-owner fallback exists here.
 */
public final class InternalLegacyProjectionCapture implements InternalRuntimeSession.Capture {
    public static final String COMPONENT = "internal_legacy_projection";
    private final RuntimeCaptureHost.InternalRequest request;
    private final InternalRuntimeSession session;
    private final OwnedLegacyCensorData data;
    private final OwnedLegacyCensorData.LocalNotice notice;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final OwnedProjectionBinding<Bitmap> source;
    private final ExecutorService inference, artwork;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final LatestFrameBroker.Async<Candidate> frames;
    private final AtomicBoolean sealed = new AtomicBoolean(), closing = new AtomicBoolean();
    private final List<Runnable> observers = new ArrayList<>();
    private final DwellInfractionTracker dwell = new DwellInfractionTracker();
    private final int displayWidth, displayHeight;
    private volatile Snapshot adopted, acknowledged;
    private volatile boolean sourceEligible, failed, drained, nativeClosed;
    private volatile long sourceEpoch;
    private volatile DetectionEngine detector;
    private volatile Snapshot modelConfiguration;
    private volatile List<RectF> popupHints = Collections.emptyList();
    private LegacyRenderDependencies rendering;
    private OverlayController overlay;
    private CaptureLoadGovernor governor;
    private ScopedDetectionHints<RectF>.Binding hints;
    private InternalLimitsBinding.Evidence evidence;
    private ObjectTracker tracker;
    private LegacyTrackIds.Epoch trackIds;
    private long trackerSource = -1, lastTapEvent, lastTapUptime;
    private boolean attached, activated, tickQueued, initializing, sourcePaused = true, pausePending;
    private Runnable afterCoreDrain;

    /**
     * Owner-worker preparation. Factory binds the returned object to the module-only session
     * before selected delivery. Geometry is captured from the exact physical host, not owner data.
     * Producer/inference/artwork executors are exclusive and owned through actual termination.
     */
    public InternalLegacyProjectionCapture(RuntimeCaptureHost.InternalRequest request,
            InternalRuntimeSession moduleSession, OwnedLegacyCensorData data,
            OwnedLegacyCensorData.LocalNotice localNotice, int width, int height, int densityDpi,
            ExecutorService producer, ExecutorService inference, ExecutorService artwork) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Prepare on owner worker");
        this.request = Objects.requireNonNull(request); session = Objects.requireNonNull(moduleSession);
        this.data = Objects.requireNonNull(data); notice = Objects.requireNonNull(localNotice);
        this.inference = Objects.requireNonNull(inference); this.artwork = Objects.requireNonNull(artwork);
        if (producer == inference || producer == artwork || inference == artwork)
            throw new IllegalArgumentException("Projection queues must be independently owned");
        RuntimeCaptureHost.InternalRequest modules = session.request();
        if (request.plan.mode != RuntimeCaptureHost.Mode.PROJECTION
                || modules.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES
                || request.runtime != modules.runtime || request.data != modules.data || request.storage != modules.storage
                || request.resources != modules.resources || !request.connection.isConnected() || !modules.connection.isConnected())
            throw new SecurityException("Projection needs its exact same-owner Accessibility modules");
        data.requireBinding(request.runtime, request.data);
        displayWidth = width; displayHeight = height;
        adopted = new Snapshot(request.initialConfiguration); acknowledged = adopted;
        component = request.runtime.registerComponent(COMPONENT);
        component.installShutdown(this::closeFromRuntime);
        component.installConfigurationReceiver(new RuntimeExecution.ConfigurationReceiver<RuntimeConfiguration>() {
            @Override public void apply(RuntimeConfiguration configuration) {
                requireMain(); requireConfiguration(configuration);
                revokeSource();
                adopted = new Snapshot(configuration); acknowledged = null;
                data.adopt(configuration);
                if (overlay != null) overlay.adoptOwnedConfiguration(configuration);
            }
            @Override public void acknowledged(RuntimeConfiguration configuration) {
                if (sealed.get() || adopted.configuration != configuration) throw new SecurityException("Unadopted projection ACK");
                data.acknowledged(configuration);
                if (overlay != null) overlay.acknowledgedConfiguration(configuration);
                acknowledged = adopted; requestTick();
            }
        });
        // Registration precedes every allocation; any partial constructor keeps its own obligation.
        source = OwnedProjectionBinding.prepareProjection(request, width, height, densityDpi,
                Objects.requireNonNull(producer), main);
        frames = LatestFrameBroker.async(inference, this::process, Candidate::close);
    }
    @Override public RuntimeCaptureHost.InternalRequest request() { return request; }
    @Override public void attach(RuntimeCaptureHost.ProjectionGrant grant) {
        requireMain();
        if (attached || sealed.get()) throw new SecurityException("Projection attachment is single-use");
        attached = true;
        if (!request.runtime.runStartupEffect(() -> {
            rendering = LegacyRenderDependencies.owned(request, artwork, this::renderSourceCurrent);
            overlay = OverlayController.owned(request.connection.projection().service(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, rendering);
            hints = request.resources.popupHints.bind(() -> renderSourceCurrent() ? popupHints : Collections.emptyList());
            governor = CaptureLoadGovernor.prepareOwned(request.connection.projection().service());
            governor.startOwned();
        })) { fail(); return; }
        if (!source.start(Objects.requireNonNull(grant))) { fail(); return; }
        component.readyAsync(result -> { if (result != ActiveRuntimeCoordinator.Result.OK) fail(); });
    }
    @Override public void activationAcknowledged() {
        requireMain(); if (sealed.get()) return;
        activated = true; requestTick();
    }
    private boolean renderSourceCurrent() {
        Snapshot selected = adopted;
        return sourceEligible && !sealed.get() && request.connection.isConnected()
                && session.request().connection.isConnected() && acknowledged == selected
                && request.runtime.configuration() == selected.configuration
                && request.runtime.appliedConfigurationRevision() == selected.configuration.configRevision;
    }
    private void requireConfiguration(RuntimeConfiguration configuration) {
        if (configuration == null || !request.owner().equals(configuration.owner)
                || configuration.configRevision < adopted.configuration.configRevision)
            throw new SecurityException("Different or stale projection configuration");
    }
    private void revokeSource() {
        sourceEligible = false; sourceEpoch = Math.incrementExact(sourceEpoch);
        request.resources.taps.clear(); popupHints = Collections.emptyList();
        if (overlay != null) overlay.withdrawOwnedSource();
    }
    @Override public void sourceChanged() { requireMain(); if (!sealed.get()) { revokeSource(); requestTick(); } }
    @Override public void screenChanged(String action) { sourceChanged(); }
    @Override public void powerChanged() { sourceChanged(); }
    @Override public void accessibilityEvent(AccessibilityEvent event) {
        requireMain(); Objects.requireNonNull(event);
        if (sealed.get()) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) sourceChanged();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        // Raw events/nodes never escape this synchronous service callback. An unadmitted event
        // has no proven billable geometry; only a fully admitted immutable tap is queued.
        request.runtime.runEffect(() -> {
            InternalLimitsBinding.Evidence current = session.modules().evidence();
            if (!renderSourceCurrent() || !session.modules().eligible(current) || current != evidence
                    || event.getWindowId() != current.windowId || event.getPackageName() == null
                    || !current.packageName.contentEquals(event.getPackageName())) return;
            long now = SystemClock.uptimeMillis(), at = event.getEventTime();
            if (at <= lastTapEvent || lastTapUptime > 0 && now - lastTapUptime < 500) return;
            AccessibilityNodeInfo node = event.getSource();
            if (node == null) return;
            boolean matches;
            try {
                if (!node.isVisibleToUser() || node.getPackageName() == null
                        || !current.packageName.contentEquals(node.getPackageName())) return;
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                matches = request.resources.taps.matchesClickAt(bounds.left, bounds.top, bounds.right, bounds.bottom, at, now);
            } finally { node.recycle(); }
            if (!matches) return;
            lastTapEvent = at; lastTapUptime = now;
            Snapshot captured = adopted; long epoch = sourceEpoch;
            OwnedCaptureLifetime.Ticket pending = work.begin(); if (pending == null) return;
            try {
                data.recordTap(data.capture(captured.configuration), OwnerObservation.capture(System.currentTimeMillis()))
                        .whenComplete((assessment, error) -> {
                            if (error != null) { fail(); return; } // Uncertain write retains obligation, no replay.
                            normal(() -> { if (current(captured, epoch, current)) assessment.notifyLocal(notice); }, () -> {}, 0);
                            pending.close();
                        });
            } catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
        });
    }
    private void requestTick() {
        if (sealed.get()) return;
        post(() -> {
            if (!tickQueued && !sealed.get()) {
                tickQueued = true;
                normal(() -> { tickQueued = false; tick(); }, () -> { tickQueued = false; }, 0);
            }
        }, 0);
    }
    private void tick() {
        requireMain(); if (!activated || sealed.get()) return;
        Snapshot selected = adopted;
        InternalLimitsBinding.Evidence next = session.modules().evidence();
        boolean allowed = acknowledged == selected && selected.enabled && session.protectionEligible()
                && next != null && next.configuration == selected.configuration && session.modules().eligible(next);
        if (next != evidence) { revokeSource(); evidence = next; }
        if (!allowed) {
            if (sourceEligible) revokeSource();
            pauseSource(true);
        } else {
            sourceEligible = true;
            overlay.show();
            overlay.reconcileArtwork(); overlay.reconcileRendering();
            if (modelConfiguration != selected && !initializing) initialize(selected);
            pauseSource(false);
            if (!sourcePaused && !pausePending && modelConfiguration == selected && detector != null
                    && governor.shouldCapture(SystemClock.uptimeMillis(), selected.detector.getDetectionIntervalMs())) {
                long epoch = sourceEpoch; InternalLimitsBinding.Evidence captured = evidence;
                source.requestFrame(frame -> {
                    if (frame == null) return;
                    if (sealed.get()) { frame.close(); return; }
                    frames.submit(new Candidate(frame, selected, epoch, captured));
                });
            }
        }
        if (!sealed.get() && !tickQueued) {
            tickQueued = true;
            normal(() -> { tickQueued = false; tick(); }, () -> { tickQueued = false; }, 16);
        }
    }
    private void pauseSource(boolean paused) {
        if (pausePending || sourcePaused == paused) return;
        pausePending = true;
        if (!source.paused(paused, admitted -> post(() -> {
            pausePending = false; if (admitted) sourcePaused = paused; requestTick();
        }, 0))) { pausePending = false; fail(); }
    }
    private void initialize(Snapshot selected) {
        initializing = true;
        dispatch(inference, () -> {
            DetectionEngine model = detector;
            try {
                if (sealed.get()) return;
                if (model == null) {
                    model = new DetectionEngine(request.connection.projection().service(), selected.detector, true,
                            request.resources.providers.forOwner(request.owner()));
                    detector = model; // Retain before any partial native initialization.
                    model.requireStrictResourceCleanup(); model.initialize();
                } else model.setConfig(selected.detector);
                DetectionEngine ready = model;
                normal(() -> {
                    if (adopted == selected && acknowledged == selected && detector == ready) modelConfiguration = selected;
                    initializing = false; requestTick();
                }, () -> initializing = false, 0);
            } catch (Exception | LinkageError error) { fail(); }
        });
    }
    private boolean current(Snapshot selected, long epoch, InternalLimitsBinding.Evidence foreground) {
        requireMain();
        return renderSourceCurrent() && adopted == selected && sourceEpoch == epoch && evidence == foreground
                && foreground != null && session.protectionEligible() && session.modules().eligible(foreground);
    }
    private void process(Candidate candidate, LatestFrameBroker.Completion completion) {
        if (sealed.get()) { completion.complete(() -> {}); return; }
        OwnedProjectionBinding<Bitmap>.Frame.Borrow<NativeFacts> pixels = candidate.frame.borrowComputation(() ->
                new NativeFacts(detector, adopted, sourceEpoch, evidence));
        if (pixels == null) {
            if (sealed.get() || candidate.snapshot != adopted || candidate.epoch != sourceEpoch) completion.complete(() -> {});
            else post(() -> dispatch(inference, () -> process(candidate, completion)), 16);
            return;
        }
        candidate.pixels = pixels; candidate.frame.close();
        NativeFacts facts = pixels.facts;
        if (facts.detector == null || facts.snapshot != candidate.snapshot || facts.epoch != candidate.epoch
                || facts.foreground != candidate.foreground || modelConfiguration != candidate.snapshot) {
            completion.complete(() -> {}); return;
        }
        try {
            List<Detection> detections = new ArrayList<>(); int[] size = new int[2];
            pixels.use(bitmap -> {
                size[0] = bitmap.getWidth(); size[1] = bitmap.getHeight();
                try { detections.addAll(facts.detector.detect(bitmap)); }
                catch (Exception error) { throw new NativeFailure(error); }
            });
            normal(() -> account(candidate, completion, facts, detections, size[0], size[1]),
                    () -> completion.complete(() -> {}), 0);
        } catch (RuntimeException | Error error) {
            if (DetectionEngine.resourceCleanupFailed(error)) failed = true;
            fail(); completion.complete(() -> {});
        }
    }
    private void account(Candidate candidate, LatestFrameBroker.Completion completion, NativeFacts facts,
            List<Detection> detections, int width, int height) {
        if (!current(candidate.snapshot, candidate.epoch, candidate.foreground) || detector != facts.detector) {
            completion.complete(() -> {}); return;
        }
        if (tracker == null) tracker = new ObjectTracker(candidate.snapshot.detector);
        if (trackerSource != candidate.epoch) {
            tracker.clear(); tracker.setConfig(candidate.snapshot.detector); dwell.clear();
            trackIds = data.nextTrackerEpoch(); trackerSource = candidate.epoch;
        }
        List<TrackedObject> tracks = tracker.update(detections);
        List<Integer> ids = new ArrayList<>();
        for (TrackedObject track : tracks) if (track.isConfirmed() && track.getFramesMissing() == 0)
            ids.add(trackIds.accountingId(track.getId()));
        OwnedLegacyCensorData.Facts accounting = data.capture(candidate.snapshot.configuration);
        OwnerObservation observed = OwnerObservation.capture(System.currentTimeMillis());
        int dwellCount = dwell.update(tracks, observed.at, accounting.dwellSeconds * 1_000L);
        OwnedCaptureLifetime.Ticket pending = work.begin();
        if (pending == null) { completion.complete(() -> {}); return; }
        try {
            data.recordFrame(accounting, ids, candidate.snapshot.detector.getEnabledCategories(), dwellCount, observed)
                    .whenComplete((assessment, error) -> {
                        if (error != null) { fail(); return; } // Keep exact failed/uncertain payload and frame.
                        normal(() -> completion.complete(() -> {
                            if (!current(candidate.snapshot, candidate.epoch, candidate.foreground)) return;
                            publish(candidate, tracks, width, height);
                            assessment.notifyLocal(notice);
                        }), () -> completion.complete(() -> {}), 0);
                        pending.close();
                    });
        } catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
    }
    private void publish(Candidate candidate, List<TrackedObject> tracks, int width, int height) {
        List<RectF> boxes = new ArrayList<>();
        for (TrackedObject track : tracks) if (track.isActive()) {
            BBox b = track.getBox(); float sx = displayWidth / (float) width, sy = displayHeight / (float) height;
            boxes.add(new RectF(b.getX() * sx, b.getY() * sy, b.getRight() * sx, b.getBottom() * sy));
        }
        popupHints = Collections.unmodifiableList(boxes);
        overlay.setTapSource(false, request.resources.taps.scope());
        if (candidate.snapshot.sourceFrame) {
            candidate.transferred = true; // Retain even when a View mutation throws after transfer.
            candidate.pixels.use(bitmap -> overlay.updatePooledFrame(tracks, width, height, bitmap, candidate::release));
        } else overlay.update(tracks, width, height, null);
    }
    /** Retained exact NORMAL demand; a busy gate is never converted to executed work. */
    private void normal(Runnable action, Runnable discarded, long delay) {
        OwnedCaptureLifetime.Ticket ticket = work.begin();
        if (ticket == null) { discarded.run(); return; }
        final class Demand implements Runnable {
            boolean finished;
            void finish(boolean ran) { if (finished) return; finished = true; try { if (!ran) discarded.run(); } finally { ticket.close(); } }
            void postAgain(long wait) {
                try { if (!work.dispatchContinuation(ticket, callback -> main.postDelayed(callback, wait), this)) { finish(false); fail(); } }
                catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
            }
            @Override public void run() {
                if (finished) return;
                if (sealed.get()) { finish(false); return; }
                try {
                    if (request.runtime.runEffect(action)) finish(true); else postAgain(16);
                } catch (RuntimeException | Error error) { finish(true); fail(); throw error; }
            }
        }
        new Demand().postAgain(delay);
    }
    private void post(Runnable action, long delay) {
        try { if (!work.dispatch(callback -> main.postDelayed(callback, delay), action) && !sealed.get()) fail(); }
        catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
    }
    private void dispatch(ExecutorService executor, Runnable action) {
        try { if (!work.dispatch(callback -> { executor.execute(callback); return true; }, action) && !sealed.get()) fail(); }
        catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
    }
    private void fail() { request.runtime.requestStop(); }
    @Override public void seal() {
        if (!sealed.compareAndSet(false, true)) return;
        sourceEligible = false;
        source.seal(); // BEFORE any late View/source-frame release.
        if (rendering != null) rendering.seal();
        if (governor != null) governor.sealOwned();
        request.resources.taps.clear(); popupHints = Collections.emptyList();
        if (hints != null) hints.close();
        request.runtime.requestStop();
    }
    @Override public void beginClose(Runnable afterActualDrain) {
        Objects.requireNonNull(afterActualDrain);
        synchronized (observers) { if (!drained) { observers.add(afterActualDrain); afterActualDrain = null; } }
        if (afterActualDrain != null) afterActualDrain.run(); else seal();
    }
    private void closeFromRuntime(Runnable afterDrained) {
        afterCoreDrain = Objects.requireNonNull(afterDrained);
        if (!closing.compareAndSet(false, true)) throw new IllegalStateException("Projection close repeated");
        seal(); data.close(); source.close(); frames.close();
        work.seal(() -> {
            try {
                inference.execute(() -> {
                    try { if (detector != null) detector.close(); nativeClosed = true; }
                    catch (RuntimeException | Error uncertain) { cleanupFailed(); }
                    finally { inference.shutdown(); }
                });
            } catch (RuntimeException | Error uncertain) { cleanupFailed(); }
        });
        if (!main.post(() -> {
            try {
                if (governor != null) governor.close();
                if (overlay != null) overlay.closeFromFreshMainQueue();
                else if (rendering != null) rendering.close(); else artwork.shutdown();
            } catch (RuntimeException | Error uncertain) { cleanupFailed(); }
            pollDrain();
        })) cleanupFailed();
    }
    private void pollDrain() {
        if (failed || drained) return;
        boolean complete = work.drained() && nativeClosed && inference.isTerminated() && artwork.isTerminated()
                && source.managedResourcesDrained() && frames.managedResourcesDrained() && data.managedResourcesDrained()
                && (governor == null || governor.managedResourcesDrained())
                && (rendering == null || rendering.managedResourcesDrained())
                && (overlay == null || overlay.managedResourcesDrained() && overlay.nativeResourcesRetired());
        if (!complete) { if (!main.postDelayed(this::pollDrain, 16)) cleanupFailed(); return; }
        try {
            request.resources.providers.close();
            drained = true;
            List<Runnable> callbacks;
            synchronized (observers) { callbacks = new ArrayList<>(observers); observers.clear(); }
            for (Runnable callback : callbacks) callback.run();
            afterCoreDrain.run();
        } catch (RuntimeException | Error uncertain) { drained = false; cleanupFailed(); }
    }
    private void cleanupFailed() { failed = true; component.cleanupFailed(); }
    @Override public boolean physicalOccupancy() { return !drained; }
    @Override public boolean managedResourcesDrained() { return drained; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Projection UI belongs on main");
    }
    private static final class Snapshot {
        final RuntimeConfiguration configuration; final DetectorConfig detector; final boolean enabled, sourceFrame;
        Snapshot(RuntimeConfiguration configuration) {
            this.configuration = configuration;
            SettingsRepository settings = SettingsRepository.forPreferences(configuration.main);
            detector = settings.loadDetectorConfig(); sourceFrame = settings.loadAppearance().requiresSourceFrame();
            enabled = configuration.main.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true);
        }
    }
    private static final class NativeFacts {
        final DetectionEngine detector; final Snapshot snapshot; final long epoch; final InternalLimitsBinding.Evidence foreground;
        NativeFacts(DetectionEngine detector, Snapshot snapshot, long epoch, InternalLimitsBinding.Evidence foreground) {
            this.detector = detector; this.snapshot = snapshot; this.epoch = epoch; this.foreground = foreground;
        }
    }
    private static final class NativeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        NativeFailure(Throwable cause) { super(cause); }
    }
    private static final class Candidate implements AutoCloseable {
        final OwnedProjectionBinding<Bitmap>.Frame frame;
        final Snapshot snapshot; final long epoch; final InternalLimitsBinding.Evidence foreground;
        OwnedProjectionBinding<Bitmap>.Frame.Borrow<NativeFacts> pixels;
        boolean transferred;
        Candidate(OwnedProjectionBinding<Bitmap>.Frame frame, Snapshot snapshot, long epoch, InternalLimitsBinding.Evidence foreground) {
            this.frame = frame; this.snapshot = snapshot; this.epoch = epoch; this.foreground = foreground;
        }
        void release() { if (pixels != null) pixels.close(); frame.close(); }
        @Override public void close() { if (!transferred) release(); }
    }
}
