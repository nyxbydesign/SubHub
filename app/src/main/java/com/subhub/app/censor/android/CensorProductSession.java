package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.engine.LatestReconciliation;
import com.subhub.app.censor.engine.BoundedLatestWork;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.ViewportDwellEpisode;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.settings.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import android.content.Context;

/** Product eligibility and one replacement lifetime, independent of legacy censor state. */
@RequiresApi(30)
public final class CensorProductSession implements AutoCloseable {
    public interface Observer extends AccessibilityCensorController.Observer {
        /** Common foreground features commit first, and may revoke this reconciliation reentrantly. */
        default void foreground(AccessibilityWindowReader.Window window) { }
        default void activeChanged(boolean active) { }
        default void sessionFailure(Throwable failure) { }
        /** Sanitized accounting metadata, not a native-owner or rendering failure. May be off-main. */
        default void accounting(AccountingResult result) { }
        /** Source-scoped viewport episode ledger outcome, not inferred paint coverage. */
        default void dwell(DwellResult result) { }
    }
    public enum AccountingPhase { DISPATCH, COUNT, LEDGER, ACHIEVEMENTS, NOTIFICATION }
    public enum AccountingStatus { REJECTED, NO_NEW_EVIDENCE, COMMITTED, APPLIED, UNACKNOWLEDGED, UNCERTAIN }
    public static final class AccountingResult {
        public final CensorEngine.EvidenceReceipt receipt;
        public final AccountingPhase phase;
        public final AccountingStatus status;
        /** Only acknowledged counter and ledger amounts; never inferred from drawn masks. */
        public final int count, chargedCents;
        public final long pendingDrops;
        private AccountingResult(CensorEngine.EvidenceReceipt receipt, AccountingPhase phase,
                AccountingStatus status, int count, int chargedCents, long pendingDrops) {
            this.receipt = receipt; this.phase = phase; this.status = status;
            this.count = count; this.chargedCents = chargedCents; this.pendingDrops = pendingDrops;
        }
    }
    public static final class DwellResult {
        public final CensorEngine.EvidenceReceipt observation;
        public final AccessibilityCensorController.WindowCoverage coverage;
        public final AccountingStatus status;
        public final int chargedCents;
        public final long pendingDrops;
        private DwellResult(DwellInput input, AccountingStatus status, int charged, long drops) {
            observation = input == null ? null : input.observation;
            coverage = input == null ? null : input.coverage;
            this.status = status; chargedCents = charged; pendingDrops = drops;
        }
    }
    private final AccessibilityService service;
    private final CensorScreenState screen;
    private boolean screenSuspended;
    private long observedScreenGeneration;
    private final CensorProductData data;
    private final CensorTapTracker taps;
    private final Observer observer;
    private final Runnable selectionObserved;
    private final AccessibilityCensorController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lookup = Executors.newSingleThreadExecutor(action -> new Thread(action, "Censor-product-window"));
    private final LatestReconciliation<CensorReconciliation.Selection<Desired>> reconciliation;
    private final DeferredCensorDemand deferredDemand;
    private final CensorProductData.AccountingWorker accountingWorker;
    private final BoundedLatestWork<AccountingInput> accounting;
    private final BoundedLatestWork<DwellInput> dwellAccounting;
    private final ViewportDwellEpisode dwellEpisode = new ViewportDwellEpisode();
    private ScopedDetectionHints<RectF>.Binding popupHints;
    private final BoundedLatestWork<CapturedEvidence> evidenceAdmission;
    private CensorProductData.Ledger penance; // Migration/construction stays in the explicit lookup adapter.
    private volatile String preferredPackage = "";
    private volatile String inputMethodPackage = "";
    private volatile Desired selected;
    private ProtectionScope protection;
    private List<Object> unavailableInference;
    private long scopeToken;
    private volatile boolean active, closed, failed;
    private volatile boolean accountingQuarantined;

    public CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer) {
        this(service, policy, taps, observer, VisualDetectorWorker::initialize);
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models) {
        this(service, policy, taps, observer, models, () -> { });
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved) {
        this(service, policy, taps, observer, models, selectionObserved, StatsRepository::new);
    }
    /** Storage-result faults are isolated owned-emulator controls, never a product preference. */
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved, Function<Context, StatsRepository> statistics) {
        this(service, policy, taps, observer, models, selectionObserved, statistics, PenanceManager::new);
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved, Function<Context, StatsRepository> statistics,
            Function<Context, PenanceManager> ledgers) {
        this(service, policy, taps, observer, models, selectionObserved, statistics, ledgers, () -> { });
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved, Function<Context, StatsRepository> statistics,
            Function<Context, PenanceManager> ledgers, Runnable nativeReadSelected) {
        this(service, policy, taps, observer, models, selectionObserved,
                () -> new DeviceCensorProductData(service, statistics, ledgers), nativeReadSelected,
                CensorEffectScope.DEVICE, null);
    }
    /** Package-owned integration boundary; public constructors remain the physical-device path. */
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved,
            java.util.function.Supplier<CensorProductData> dataFactory, Runnable nativeReadSelected,
            CensorEffectScope effects, SnapshotRenderSession.ArtworkLoader artwork) {
        onMain(); this.service = Objects.requireNonNull(service); this.taps = Objects.requireNonNull(taps);
        screen = new CensorScreenState(service);
        observedScreenGeneration = screen.generation();
        this.selectionObserved = Objects.requireNonNull(selectionObserved);
        data = Objects.requireNonNull(Objects.requireNonNull(dataFactory).get());
        Objects.requireNonNull(effects);
        if ((data instanceof DeviceCensorProductData) != (effects == CensorEffectScope.DEVICE))
            throw new IllegalArgumentException("Censor data and effect ownership disagree");
        accountingWorker = Objects.requireNonNull(data.accountingWorker());
        this.observer = Objects.requireNonNull(observer);
        accounting = new BoundedLatestWork<>(accountingWorker, this::recordEvidence,
                failure -> quarantineAccounting(null, AccountingPhase.DISPATCH, AccountingStatus.UNCERTAIN, 0, 0));
        dwellAccounting = new BoundedLatestWork<>(accountingWorker, this::recordDwell,
                failure -> quarantineDwell(null, AccountingStatus.UNCERTAIN, 0));
        evidenceAdmission = new BoundedLatestWork<>(action -> {
            if (!main.post(action)) throw new RejectedExecutionException("Product main admission rejected");
        }, this::acceptEvidence,
                failure -> quarantineAccounting(null, AccountingPhase.DISPATCH, AccountingStatus.UNCERTAIN, 0, 0));
        controller = new AccessibilityCensorController(service, policy, taps,
                new AccessibilityCensorController.Observer() {
                    @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) { observer.providerReady(lane, provider, millis); }
                    @Override public void modelUnavailable(SceneEngine.Lane lane) {
                        if (lane == SceneEngine.Lane.FAST && selected != null && active) {
                            unavailableInference = selected.inference; stop();
                        }
                        observer.modelUnavailable(lane);
                    }
                    @Override public void trace(CensorEngine.Trace trace) {
                        if (trace.stage == CensorEngine.Stage.RESOURCE_FAILED) {
                            // This final controller owns the fault even if its former scope stopped.
                            main.post(() -> fail(new IllegalStateException("Replacement native owner quarantined")));
                        }
                        observer.trace(trace);
                    }
                    @Override public void scrollReceived(com.subhub.app.censor.engine.ScrollMotionLedger.Receipt receipt) {
                        observer.scrollReceived(receipt);
                    }
                    @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { observer.prepared(request, kind, nanos, bytes); }
                    @Override public void failed(long request, int code) { observer.failed(request, code); }
                    @Override public void presentationChanged() { observer.presentationChanged(); }
                    @Override public void batchAccepted(SceneEngine.Batch batch) { observer.batchAccepted(batch); }
                    @Override public void nativeTextAccepted(com.subhub.app.censor.engine.NativeTextFrame frame) { observer.nativeTextAccepted(frame); }
                    @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) { observer.nativeReadCost(value); }
                    @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) { observer.nativeReadCostStarted(value); }
                    @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) {
                        // Receipt time is engine uptime, not a calendar timestamp. Capture wall
                        // time/zone at actual admission, before either the main or writer queue.
                        evidenceAdmission.offer(new CapturedEvidence(receipt,
                                OwnerObservation.capture(System.currentTimeMillis()), data.accountingFacts()));
                        observer.evidenceAccepted(receipt);
                    }
                    @Override public void painted(com.subhub.app.censor.engine.CensorEngine.PaintReceipt receipt) { observer.painted(receipt); }
                    @Override public boolean collectSubmissions() { return observer.collectSubmissions(); }
                    @Override public void submitted(SurfaceSubmission submission) { observer.submitted(submission); }
                    @Override public void posted(SnapshotCensorSurface.Post receipt) { observer.posted(receipt); }
                    @Override public void endpointFailure() { main.post(() -> fail(new IllegalStateException("Replacement presentation endpoint failed"))); observer.endpointFailure(); }
                    @Override public void resourceFailure() { main.post(() -> fail(new IllegalStateException("Replacement resource ownership failed"))); observer.resourceFailure(); }
                    @Override public void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) {
                        main.post(() -> { if (!closed && selected != null && scopeToken == token) { stop(); request(); } });
                        observer.windowRejected(token, expected, actual);
                    }
                    @Override public void drained() { observer.drained(); }
                    @Override public void executorsTerminated(boolean disposed) { observer.executorsTerminated(disposed); }
                }, models, () -> { }, nativeReadSelected, (input, boundary) -> { }, screen, effects, artwork);
        reconciliation = new LatestReconciliation<>(lookup, main::post,
                () -> CensorReconciliation.read(this::read),
                selected -> CensorReconciliation.commit(selected, this::commit, this::deferReconciliation), this::fail);
        deferredDemand = new DeferredCensorDemand(new DeferredCensorDemand.Scheduler() {
            @Override public boolean post(Runnable command, long delay) { return main.postDelayed(command, delay); }
            @Override public void remove(Runnable command) { main.removeCallbacks(command); }
        }, this::request, () -> fail(new IllegalStateException("Deferred Censor main demand rejected")));
    }
    enum AdoptionKind { UNCHANGED, APPEARANCE, STRUCTURAL }
    /** Synchronous configuration adoption only, not model readiness or physical presentation. */
    static final class Adoption {
        final com.subhub.app.runtime.RuntimeConfiguration configuration;
        final AdoptionKind kind;
        Adoption(com.subhub.app.runtime.RuntimeConfiguration configuration, AdoptionKind kind) {
            this.configuration = configuration; this.kind = kind;
        }
    }

    /**
     * Called only inside this owner's main-thread module-adoption effect. Installs immutable data
     * and withdraws incompatible authority before returning; no lookup, model wait or capture here.
     * Accounting facts switch later, only in the core's serial acknowledged callback.
     */
    Adoption adoptOwnedConfiguration(OwnedCensorProductData boundData, OwnedCensorArtwork artwork,
            com.subhub.app.runtime.RuntimeConfiguration configuration) {
        onMain();
        if (closed || failed || data != boundData) throw new SecurityException("Different or closed Censor product");
        Objects.requireNonNull(artwork); Objects.requireNonNull(configuration);
        ProductCensorSettings before = new ProductCensorSettings(data.preferences());
        ProductCensorSettings after = new ProductCensorSettings(configuration.main);
        SettingsRepository oldSettings = SettingsRepository.forPreferences(before);
        SettingsRepository newSettings = SettingsRepository.forPreferences(after);
        DetectorConfig detector = newSettings.loadDetectorConfig();
        TextSmutConfig text = newSettings.loadTextSmutConfig();
        boolean structural = !before.sameParticipation(after)
                || !CensorConfigurationKey.inference(oldSettings.loadDetectorConfig(), oldSettings.loadTextSmutConfig())
                        .equals(CensorConfigurationKey.inference(detector, text));
        CensorAppearance appearance = newSettings.loadAppearance();
        SnapshotPaintPolicy paint = SnapshotPaintPolicy.from(newSettings.loadDetectionPreset());
        long revision = configuration.artworkRevision;
        boolean appearanceChanged = !CensorConfigurationKey.appearance(oldSettings.loadAppearance(),
                SnapshotPaintPolicy.from(oldSettings.loadDetectionPreset()), before.getLong(CustomImageManager.REVISION_KEY, 0))
                .equals(CensorConfigurationKey.appearance(appearance, paint, revision));
        // Even irrelevant edits invalidate a held old selection: it cannot replace the exact newly
        // adopted Desired after the barrier. Existing source/resource authority is retained below.
        reconciliation.invalidate(); deferredDemand.cancel();
        boundData.applyRendering(configuration); artwork.update(configuration);
        if (structural) {
            stop();
            return new Adoption(configuration, AdoptionKind.STRUCTURAL);
        }
        Desired previous = selected;
        if (appearanceChanged) controller.appearanceChanged(appearance, paint, revision);
        if (previous != null && active) {
            selected = new Desired(after, previous.lab, previous.session, previous.window, detector, text,
                    appearance, paint, revision, previous.eligible, previous.screenGeneration);
        }
        return new Adoption(configuration, appearanceChanged ? AdoptionKind.APPEARANCE : AdoptionKind.UNCHANGED);
    }

    public void request() {
        onMain(); if (closed || failed) return;
        screen.refresh(null);
        if (observedScreenGeneration != screen.generation()) suspendForScreen();
        observedScreenGeneration = screen.generation();
        if (!screen.eligible()) { suspendForScreen(); return; }
        if (screenSuspended) { screenSuspended = false; preferredPackage = ""; }
        reconciliation.request();
    }
    public void screenStateChanged(String action) {
        onMain(); if (closed || failed) return;
        screen.refresh(action);
        if (!screen.eligible()) suspendForScreen(); else request();
    }
    private void suspendForScreen() {
        if (screenSuspended) return;
        screenSuspended = true;
        preferredPackage = "";
        stop();
        controller.suspendForScreen();
    }
    /** Accepted real foreground changes withdraw old geometry before waiting for a Binder lookup. */
    public void foregroundEvent(String packageName, String className, int windowId, boolean windowStateChange) {
        onMain(); if (closed || failed) return;
        if (AppModePolicy.shouldAcceptForegroundEvent(packageName, className, service.getPackageName(), inputMethodPackage)) {
            if (!packageName.equals(preferredPackage) || (windowStateChange && selected != null
                    && windowId >= 0 && selected.window.id != windowId)) {
                // Keep the last protected window as resolver preference under app-owned overlays.
                // Own Activities and Settings are real escape transitions, not a protected overlay.
                if (packageName.equals(service.getPackageName()) || packageName.equals("com.android.settings")
                        || packageName.equals("com.google.android.settings")) preferredPackage = "";
                stop();
            }
        }
        request();
    }
    public void settingsChanged(String key) {
        onMain(); if (!ProductCensorSettings.relevant(key)) return;
        // Structural edits withdraw immediately; style-only edits retain qualified source geometry.
        if ((!com.subhub.app.capture.export.ExportSettings.allowed(key) && !CustomImageManager.REVISION_KEY.equals(key))
                || key.equals(SettingsRepository.KEY_DETECTION_PRESET)
                || key.equals(SettingsRepository.KEY_CONFIDENCE)
                || key.equals(SettingsRepository.KEY_ENABLED_CATEGORIES)
                || key.equals(SettingsRepository.KEY_CENSOR_COVERAGE)) stop();
        request();
    }
    public void ownershipChanged() { onMain(); stop(); request(); }
    public void contentChanged(int windowId) { onMain(); if (active && !closed) { controller.contentChanged(windowId); controller.requestCapture(); } }
    public void contentChanged(int windowId, int contentChangeTypes) {
        onMain();
        if (active && !closed) {
            // SUBTREE and undefined signals make the whole tree unknown, not every classified
            // same-node literal false. All other explicit content-change kinds retain a hard fence.
            boolean hard = (contentChangeTypes & ~android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0;
            controller.contentChanged(windowId, hard); controller.requestCapture();
        }
    }
    public void scrolled(int windowId) {
        onMain(); Desired value = selected;
        if (active && !closed && value != null && value.window.id == windowId) {
            controller.scrolled(windowId);
            dwellEpisode.reset(); dwellAccounting.invalidate();
        }
    }
    public void scrolled(android.view.accessibility.AccessibilityEvent event) {
        onMain(); Desired value = selected;
        if (active && !closed && value != null && value.window.id == event.getWindowId()) {
            controller.scrolled(event);
            dwellEpisode.reset(); dwellAccounting.invalidate();
        }
    }
    public void demandCapture() { onMain(); if (active && !closed) controller.requestCapture(); }
    public void powerPolicyChanged() { onMain(); if (!closed) controller.powerPolicyChanged(); }
    private void deferReconciliation() {
        stop();
        if (!closed && !failed) deferredDemand.defer();
    }
    public void stop() {
        onMain(); if (closed) return;
        deferredDemand.cancel();
        reconciliation.invalidate();
        evidenceAdmission.invalidate();
        accounting.invalidate();
        dwellAccounting.invalidate(); dwellEpisode.reset();
        releasePopupHints();
        selected = null; protection = null;
        boolean previous = active; active = false;
        controller.stop();
        if (previous) observer.activeChanged(false);
    }
    public boolean isActive() { return active && !closed && !failed && screen.eligible(); }
    public AccessibilityWindowReader.Window window() { Desired value = selected; return value == null ? null : value.window; }
    @Override public void close() {
        onMain(); if (closed) return;
        closed = true; active = false; selected = null; protection = null;
        deferredDemand.close(); reconciliation.close(); lookup.shutdown();
        evidenceAdmission.close();
        accounting.close(); accountingWorker.close();
        dwellAccounting.close(); dwellEpisode.reset(); releasePopupHints();
        try { controller.close(); } finally { observer.activeChanged(false); }
    }
    public boolean managedResourcesDrained() {
        return closed && deferredDemand.drained() && reconciliation.drained() && lookup.isTerminated() && evidenceAdmission.drained() && accounting.drained()
                && dwellAccounting.drained()
                && accountingWorker.terminated() && controller.managedResourcesDrained();
    }
    AccessibilityCensorController controller() { return controller; }
    AccessibilityService service() { return service; }

    private Desired read() {
        long screenGeneration = screen.generation();
        if (penance == null) penance = Objects.requireNonNull(data.dwellLedger());
        StatsRepository.SessionStamp session = data.sessionStamp();
        ProductCensorSettings snapshot = new ProductCensorSettings(data.preferences());
        CensorProductData.Participation lab = data.participation();
        String ime = data.inputMethodPackage();
        inputMethodPackage = ime;
        AccessibilityWindowReader.Window window = screen.isCurrent(screenGeneration)
                ? controller.observeForeground(ime, preferredPackage) : null;
        SettingsRepository settings = SettingsRepository.forPreferences(snapshot);
        DetectorConfig detector = settings.loadDetectorConfig(); TextSmutConfig text = settings.loadTextSmutConfig();
        boolean enabled = lab.lab() || (data.armed(snapshot)
                && snapshot.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                && settings.loadCaptureMethod() == CaptureMethod.APP_MODE);
        boolean eligible = screen.isCurrent(screenGeneration) && window != null && AppModePolicy.shouldRecognize(enabled, snapshot.included(),
                window.packageName, service.getPackageName(), ime);
        SnapshotPaintPolicy paint = SnapshotPaintPolicy.from(settings.loadDetectionPreset());
        CensorAppearance appearance = settings.loadAppearance();
        long revision = snapshot.getLong(CustomImageManager.REVISION_KEY, 0);
        Desired result = new Desired(snapshot, lab, session, window, detector, text, appearance, paint, revision, eligible, screenGeneration);
        selectionObserved.run(); // Adverse delivery control after all actual platform handles were released.
        return result;
    }
    private void commit(Desired value) {
        onMain(); if (closed || failed) return;
        if (!screen.isCurrent(value.screenGeneration)) { request(); return; }
        long generation = reconciliation.generation();
        if (!value.lab.current() || value.session != data.observedSessionStamp()
                || !value.settings.same(new ProductCensorSettings(data.preferences()))) { request(); return; }
        observer.foreground(value.window);
        if (!screen.isCurrent(value.screenGeneration)) { request(); return; }
        if (!reconciliation.isCurrent(generation) || closed || failed) return;
        if (!value.lab.current() || value.session != data.observedSessionStamp()
                || !value.settings.same(new ProductCensorSettings(data.preferences()))) { request(); return; }
        if (!value.eligible) { preferredPackage = ""; controller.idleSettingsChanged(value.detector); stop(); return; }
        if (value.inference.equals(unavailableInference)) { stop(); return; }
        unavailableInference = null;
        preferredPackage = value.window.packageName;
        ProtectionScope desiredProtection = value.lab.lab() ? ProtectionScope.lab(taps.scope()) : ProtectionScope.normal(taps.scope());
        if (active && selected != null && selected.screenGeneration == value.screenGeneration && selected.window.same(value.window) && selected.lab == value.lab
                && selected.session == value.session && selected.settings.sameParticipation(value.settings)
                && selected.inference.equals(value.inference) && desiredProtection.equals(protection)) {
            if (!selected.appearanceKey.equals(value.appearanceKey)) {
                controller.appearanceChanged(value.appearance, value.paint, value.artworkRevision);
            }
            if (reconciliation.isCurrent(generation) && !closed && !failed && active && screen.isCurrent(value.screenGeneration)) {
                selected = value; deferredDemand.succeeded();
            }
            return;
        }
        dwellAccounting.invalidate(); dwellEpisode.reset();
        selected = value; protection = desiredProtection; active = true;
        if (popupHints == null) popupHints = data.bindPopupHints(this::readPopupHints);
        try {
            int threads = value.detector.getInferenceThreads();
            controller.start(new AccessibilityCensorController.Configuration(++scopeToken, value.window, value.detector,
                    threads >= 3 && value.detector.getInferenceResolution() > 320, value.text,
                    threads >= 3, threads >= 4, desiredProtection, value.appearance, value.paint, value.artworkRevision));
            if (reconciliation.isCurrent(generation) && !closed && !failed && active && screen.isCurrent(value.screenGeneration)) {
                deferredDemand.succeeded(); observer.activeChanged(true);
            }
        } catch (CensorEffectScope.Unavailable refused) { throw refused; }
        catch (RuntimeException | Error error) { fail(error); }
    }
    private void fail(Throwable failure) {
        onMain(); if (closed || failed) return;
        failed = true; reconciliation.close();
        try { stop(); } finally { observer.sessionFailure(failure); }
    }
    private static final class AccountingInput {
        final Desired desired;
        final CensorEngine.EvidenceReceipt receipt;
        final OwnerObservation observed;
        final CensorProductData.AccountingFacts facts;
        AccountingInput(Desired desired, CensorEngine.EvidenceReceipt receipt, CapturedEvidence captured) {
            this.desired = desired; this.receipt = receipt; observed = captured.observed; facts = captured.facts;
        }
    }
    private static final class CapturedEvidence {
        final CensorEngine.EvidenceReceipt receipt; final OwnerObservation observed;
        final CensorProductData.AccountingFacts facts;
        CapturedEvidence(CensorEngine.EvidenceReceipt receipt, OwnerObservation observed, CensorProductData.AccountingFacts facts) {
            this.receipt = receipt; this.observed = observed; this.facts = Objects.requireNonNull(facts);
        }
    }
    private void acceptEvidence(CapturedEvidence captured) {
        CensorEngine.EvidenceReceipt receipt = captured.receipt;
        onMain(); Desired value = selected;
        if (value == null) return;
        AccountingInput input = new AccountingInput(value, receipt, captured);
        if (accountingCurrent(input)) {
            accounting.offer(input); // Preserve new-detection ledger ordering before dwell.
            AccessibilityCensorController.WindowCoverage coverage = controller.viewportCoverage();
            if (accountingCurrent(input) && controller.observationCurrent(coverage, receipt)) {
                ViewportDwellEpisode.Ticket ticket = dwellEpisode.observe(controller.dwellPresent(coverage, receipt),
                        receipt.acceptedAtMillis, captured.facts.dwellLedger().dwellSeconds() * 1000L);
                if (ticket != null) dwellAccounting.offer(new DwellInput(value, receipt, coverage, ticket, captured));
            }
        }
    }
    private boolean accountingCurrent(AccountingInput input) {
        return !accountingQuarantined && productCurrent(input.desired) && controller.evidenceCurrent(input.receipt);
    }
    private boolean productCurrent(Desired origin) {
        Desired current = selected;
        return participationCurrent(origin) && origin.session != null && origin.session.isCurrent()
                && current != null && current.session == origin.session;
    }
    private boolean participationCurrent(Desired origin) {
        Desired current = selected;
        if (closed || failed || !active || !screen.isCurrent(origin.screenGeneration) || current == null || origin.lab.lab()
                || !origin.lab.current() || current.lab != origin.lab
                || !current.window.same(origin.window) || !current.inference.equals(origin.inference)
                || !origin.settings.sameParticipation(current.settings)) return false;
        ProductCensorSettings live = new ProductCensorSettings(data.preferences());
        SettingsRepository settings = SettingsRepository.forPreferences(live);
        return origin.settings.sameParticipation(live)
                && origin.inference.equals(CensorConfigurationKey.inference(settings.loadDetectorConfig(), settings.loadTextSmutConfig()));
    }
    private List<RectF> readPopupHints() {
        onMain(); Desired value = selected;
        if (value == null || !participationCurrent(value)) return Collections.emptyList();
        AccessibilityCensorController.WindowCoverage coverage = controller.viewportCoverage();
        if (!coverage.windowQualified || !value.window.same(coverage.window)) return Collections.emptyList();
        List<RectF> hints = new ArrayList<>();
        for (AccessibilityCensorController.WindowRegion member : coverage.regions) {
            SceneEngine.Region box = member.displayRegion;
            hints.add(new RectF((float) box.left, (float) box.top, (float) box.right, (float) box.bottom));
        }
        return participationCurrent(value) && controller.coverageCurrent(coverage) ? hints : Collections.emptyList();
    }
    private void releasePopupHints() {
        if (popupHints != null) { popupHints.close(); popupHints = null; }
    }
    private static final class DwellInput {
        final Desired desired;
        final CensorEngine.EvidenceReceipt observation;
        final AccessibilityCensorController.WindowCoverage coverage;
        final ViewportDwellEpisode.Ticket ticket;
        final OwnerObservation observed;
        final CensorProductData.AccountingFacts facts;
        DwellInput(Desired desired, CensorEngine.EvidenceReceipt observation,
                AccessibilityCensorController.WindowCoverage coverage, ViewportDwellEpisode.Ticket ticket, CapturedEvidence captured) {
            this.desired = desired; this.observation = observation; this.coverage = coverage; this.ticket = ticket;
            observed = captured.observed; facts = captured.facts;
        }
    }
    private boolean dwellCurrent(DwellInput input) {
        return !accountingQuarantined && productCurrent(input.desired) && dwellEpisode.current(input.ticket)
                && controller.dwellPresent(input.coverage, input.observation);
    }
    private void recordDwell(DwellInput input) {
        int charged = 0;
        try {
            boolean admitted = input.desired.lab.admitNormal(
                    () -> dwellEpisode.claim(input.ticket, () -> dwellCurrent(input)), false);
            if (!admitted) {
                dwellEpisode.release(input.ticket); reportDwell(input, AccountingStatus.REJECTED, 0); return;
            }
            long now = System.currentTimeMillis();
            CensorProductData.Ledger penance = input.facts.dwellLedger();
            CensorProductData.Assessment ledger = penance.dwell(now, input.desired.session, input.observed);
            charged = ledger.chargedCents;
            reportDwell(input, ledger.acknowledged
                    ? AccountingStatus.COMMITTED : AccountingStatus.REJECTED, charged);
            if (charged > 0) penance.notifyCharge(PenanceInfraction.CENSORED_DWELL, charged, now, input.observed,
                    () -> !accountingQuarantined && productCurrent(input.desired) && controller.evidenceCurrent(input.observation));
        } catch (RuntimeException | Error uncertain) { quarantineDwell(input, AccountingStatus.UNCERTAIN, charged); }
    }
    private void quarantineDwell(DwellInput input, AccountingStatus status, int charged) {
        accountingQuarantined = true; accounting.close(); dwellAccounting.close(); dwellEpisode.reset();
        data.accountingFailed();
        reportDwell(input, status, charged);
    }
    private void reportDwell(DwellInput input, AccountingStatus status, int charged) {
        try { observer.dwell(new DwellResult(input, status, charged, dwellAccounting.drops())); }
        catch (RuntimeException diagnosticFailure) { /* Never replay an admitted ledger mutation. */ }
    }
    /** Short admission only: no disk, UI, observer or platform operation inside the Lab guard. */
    private boolean admitAccounting(AccountingInput input) {
        return input.desired.lab.admitNormal(() -> accountingCurrent(input), false);
    }
    private void recordEvidence(AccountingInput input) {
        AccountingPhase phase = AccountingPhase.COUNT; int count = 0, charged = 0;
        try {
            if (!accountingCurrent(input)) { reportAccounting(input, phase, AccountingStatus.REJECTED, 0, 0); return; }
            StatsRepository.EvidenceRecord record = data.recordEvidence(input.desired.session,
                    () -> input.desired.lab.admitNormal(
                            () -> accountingCurrent(input) ? controller.claimEvidence(input.receipt, input.desired.session) : null, null),
                    input.desired.detector.getEnabledCategories(), input.observed);
            if (record.status == StatsRepository.EvidenceStatus.UNACKNOWLEDGED) {
                quarantineAccounting(input, phase, AccountingStatus.UNACKNOWLEDGED, 0, 0); return;
            }
            if (record.status != StatsRepository.EvidenceStatus.COMMITTED) {
                reportAccounting(input, phase, record.status == StatsRepository.EvidenceStatus.NO_NEW_EVIDENCE
                        ? AccountingStatus.NO_NEW_EVIDENCE : AccountingStatus.REJECTED, 0, 0); return;
            }
            count = record.count;
            reportAccounting(input, phase, AccountingStatus.COMMITTED, count, 0);
            phase = AccountingPhase.LEDGER;
            if (!admitAccounting(input)) { reportAccounting(input, phase, AccountingStatus.REJECTED, count, 0); return; }
            CensorProductData.Ledger penance = input.facts.detectionLedger();
            long now = System.currentTimeMillis();
            CensorProductData.Assessment ledger = penance.detection(count, now, input.desired.session, input.observed);
            charged = ledger.chargedCents;
            // Zero cents can commit batch progress; never replay it.
            reportAccounting(input, phase, ledger.acknowledged
                    ? AccountingStatus.COMMITTED : AccountingStatus.REJECTED, count, charged);
            phase = AccountingPhase.ACHIEVEMENTS;
            if (!admitAccounting(input)) { reportAccounting(input, phase, AccountingStatus.REJECTED, count, charged); return; }
            input.facts.checkAchievements(record.snapshot, input.observed);
            // Existing achievement persistence uses apply(), not an acknowledged disk transaction.
            reportAccounting(input, phase, AccountingStatus.APPLIED, count, charged);
            phase = AccountingPhase.NOTIFICATION;
            if (charged > 0 && admitAccounting(input)) {
                penance.notifyCharge(PenanceInfraction.NEW_DETECTION, charged, now, input.observed,
                        () -> accountingCurrent(input));
            }
        } catch (RuntimeException | Error uncertain) {
            // Consumption/write may have happened. Retire accounting, never retry the source.
            quarantineAccounting(input, phase, AccountingStatus.UNCERTAIN, count, charged);
        }
    }
    private void quarantineAccounting(AccountingInput input, AccountingPhase phase, AccountingStatus status,
            int count, int charged) {
        accountingQuarantined = true; accounting.close(); dwellAccounting.close(); dwellEpisode.reset();
        data.accountingFailed();
        reportAccounting(input, phase, status, count, charged);
    }
    private void reportAccounting(AccountingInput input, AccountingPhase phase, AccountingStatus status,
            int count, int charged) {
        try { observer.accounting(new AccountingResult(input == null ? null : input.receipt,
                phase, status, count, charged, accounting.drops() + evidenceAdmission.drops())); }
        catch (RuntimeException diagnosticFailure) { /* An observer cannot replay or strand accounting. */ }
    }
    private static final class Desired {
        final ProductCensorSettings settings;
        final CensorProductData.Participation lab;
        final StatsRepository.SessionStamp session;
        final AccessibilityWindowReader.Window window;
        final DetectorConfig detector; final TextSmutConfig text;
        final CensorAppearance appearance; final SnapshotPaintPolicy paint;
        final List<Object> inference, appearanceKey;
        final long artworkRevision, screenGeneration; final boolean eligible;
        Desired(ProductCensorSettings settings, CensorProductData.Participation lab, StatsRepository.SessionStamp session,
                AccessibilityWindowReader.Window window,
                DetectorConfig detector, TextSmutConfig text, CensorAppearance appearance, SnapshotPaintPolicy paint,
                long revision, boolean eligible, long screenGeneration) {
            this.settings = settings; this.lab = lab; this.session = session;
            this.window = window; this.detector = detector; this.text = text;
            this.appearance = appearance; this.paint = paint; artworkRevision = revision; this.eligible = eligible;
            this.screenGeneration = screenGeneration;
            inference = CensorConfigurationKey.inference(detector, text);
            appearanceKey = CensorConfigurationKey.appearance(appearance, paint, revision);
        }
    }
    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main-looper product lifecycle required");
    }
}
