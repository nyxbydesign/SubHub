package com.subhub.app.service;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityWindowInfo;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.StatsRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * API26+ owner-local Limits and focused-window observation, shared by every capture mode. The
 * factory must retain the explicit Accessibility connection even for projection. Construction
 * alone neither starts a session nor supplies permission. No DEVICE data or remote side effect.
 */
public final class InternalLimitsBinding {
    public interface LocalIntervention {
        /**
         * Main NORMAL local UI only, never a peer/payment notifier. The actual limit withdrawal
         * has already admitted its counted event. Reentrant Stop/source/config changes are valid;
         * callback return grants no permission for another accounting or Home action.
         */
        void reached(RuntimeLease.Identity owner, String packageName, AppTimerManager.LimitStatus status);
    }
    public enum Stage { PREPARED, WAITING, ACKNOWLEDGED, LIMITED, STOPPING, DRAINED, FAILED }

    /** Immutable acknowledged observation, not an effect capability or a mutable usage provider. */
    public static final class Evidence {
        public final RuntimeLease.Identity owner;
        public final RuntimeConfiguration configuration;
        public final String packageName;
        public final int windowId;
        public final long sourceEpoch, elapsedRealtime;
        public final OwnerObservation calendar;
        public final AppTimerManager.UsageSnapshot usage;
        public final AppTimerManager.LimitStatus limit;
        public final boolean blocked;
        private Evidence(RuntimeLease.Identity owner, Assessed assessed,
                InternalLimitsAdmission.Decision<RuntimeConfiguration> decision) {
            this.owner = owner; configuration = decision.observation.configuration;
            packageName = decision.observation.packageName; windowId = decision.observation.windowId;
            sourceEpoch = decision.observation.epoch; elapsedRealtime = decision.observation.monotonic;
            calendar = decision.observation.calendar; usage = assessed.usage; limit = assessed.status;
            blocked = decision.blocked;
        }
    }
    private final RuntimeExecution<RuntimeConfiguration> runtime;
    private final InternalOwnerDataBroker.Session data;
    private final StatsRepository.SessionStamp session;
    private final RuntimeCaptureHost.Connection accessibility;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final ExecutorService reads;
    private final BooleanSupplier protectionEligible;
    private final Runnable eligibilityChanged;
    private final LocalIntervention notice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final InternalLimitsAdmission<RuntimeConfiguration> admission;
    private final AtomicBoolean sealed = new AtomicBoolean(), started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean(), drained = new AtomicBoolean();
    private final AtomicInteger cleanupCallbacks = new AtomicInteger();
    private volatile RuntimeConfiguration adopted, acknowledgedConfiguration;
    private volatile Evidence evidence;
    private volatile Stage stage = Stage.PREPARED;
    private volatile boolean cleanupFailed;
    private volatile Runnable afterDrained;
    private boolean active, pipeline, tickQueued, urgent; // Main only.
    private String lastBlockedPackage = "";
    private long lastBlockedMonotonic = -1;

    /** Owner worker, after this Session's own statistics STARTUP acknowledgement. */
    public InternalLimitsBinding(RuntimeExecution<RuntimeConfiguration> runtime,
            InternalOwnerDataBroker.Session data, RuntimeCaptureHost.Connection accessibilityConnection,
            String componentName, ExecutorService ownedReads, BooleanSupplier protectionEligible,
            Runnable eligibilityChanged, LocalIntervention localNotice) {
        requireWorker(); this.runtime = Objects.requireNonNull(runtime); this.data = Objects.requireNonNull(data);
        accessibility = Objects.requireNonNull(accessibilityConnection); reads = Objects.requireNonNull(ownedReads);
        this.protectionEligible = Objects.requireNonNull(protectionEligible);
        this.eligibilityChanged = Objects.requireNonNull(eligibilityChanged); notice = Objects.requireNonNull(localNotice);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL || !runtime.identity().equals(data.identity())
                || accessibility.kind != RuntimeCaptureHost.Kind.ACCESSIBILITY || !accessibility.isConnected())
            throw new SecurityException("Limits needs the exact INTERNAL runtime and live Accessibility source");
        session = data.acknowledgedSessionStamp();
        if (session.isDeviceSession() || !data.isCurrentStamp(session))
            throw new SecurityException("Limits has no acknowledged owner statistics session");
        adopted = runtime.configuration(); requireConfiguration(adopted); acknowledgedConfiguration = adopted;
        admission = new InternalLimitsAdmission<>(runtime.identity(), adopted);
        component = runtime.registerComponent(componentName);
        component.installConfigurationReceiver(new RuntimeExecution.ConfigurationReceiver<RuntimeConfiguration>() {
            @Override public void apply(RuntimeConfiguration configuration) {
                requireMain(); requireConfiguration(configuration);
                if (sealed.get() || configuration.configRevision < adopted.configRevision)
                    throw new SecurityException("Limits configuration is stale or closed");
                adopted = configuration; admission.adopt(configuration); evidence = null; stage = Stage.WAITING;
                urgent = true; eligibilityChanged.run();
            }
            @Override public void acknowledged(RuntimeConfiguration configuration) {
                requireWorker(); requireConfiguration(configuration);
                if (sealed.get() || adopted != configuration) throw new SecurityException("Limits configuration was not adopted");
                acknowledgedConfiguration = configuration;
                dispatchMain(() -> requestTick(0), 0);
            }
        });
        component.installShutdown(this::closeFromRuntime);
    }
    public Stage stage() { return stage; }
    public Evidence evidence() { return evidence; }

    /** Main attachment entry. This receipt is infrastructure readiness, not foreground readiness. */
    public void start() {
        requireMain();
        if (!started.compareAndSet(false, true) || sealed.get()) throw new IllegalStateException("Limits starts once");
        if (!runtime.runStartupEffect(() -> {
            if (!accessibility.isConnected()) throw new SecurityException("Limits source disconnected before attach");
        })) { component.failed(); return; }
        OwnedCaptureLifetime.Ticket ready = work.begin();
        if (ready == null) { component.failed(); return; }
        component.readyAsync(result -> {
            try { if (result != ActiveRuntimeCoordinator.Result.OK) component.failed(); }
            finally { ready.close(); }
        });
    }
    /** Main, only after the gateway's real ACTIVE acknowledgement. */
    public void activationAcknowledged() {
        requireMain(); if (sealed.get()) return;
        active = true; requestTick(0);
    }
    /** Forward actual foreground, screen/lock, connection, power and owned-pause changes here. */
    public void sourceChanged() {
        requireMain(); if (sealed.get()) return;
        admission.invalidate(true); evidence = null; stage = Stage.WAITING; urgent = true;
        eligibilityChanged.run();
        if (!accessibility.isConnected()) { component.failed(); return; }
        requestTick(0);
    }
    /** Cached exact focused-window evidence only. The caller still needs its ordinary effect gate. */
    public boolean eligible(RuntimeLease.Identity owner, RuntimeConfiguration configuration,
            String packageName, int focusedWindowId) {
        requireMain();
        if (sealed.get() || !active || !accessibility.isConnected() || cleanupFailed
                || configuration != acknowledgedConfiguration || configuration != runtime.configuration()
                || !data.isCurrentStamp(session) || !protectionEligible.getAsBoolean()) return false;
        return admission.eligible(owner, configuration, packageName, focusedWindowId,
                SystemClock.elapsedRealtime(), OwnerObservation.capture(System.currentTimeMillis()));
    }
    /** Legacy/projection may retain this exact binding-issued evidence, never a package-only guess. */
    public boolean eligible(Evidence expected) {
        requireMain(); return expected != null && evidence == expected
                && eligible(expected.owner, expected.configuration, expected.packageName, expected.windowId);
    }
    public void seal() {
        if (sealed.compareAndSet(false, true)) { admission.seal(); evidence = null; }
        runtime.requestStop();
    }
    public boolean managedResourcesDrained() { return drained.get() && !cleanupFailed; }

    private void requestTick(long delay) {
        requireMain();
        if (sealed.get() || !active || pipeline || tickQueued) return;
        tickQueued = true;
        dispatchMain(() -> {
            tickQueued = false;
            if (sealed.get() || !active || pipeline) return;
            if (adopted != acknowledgedConfiguration || adopted != runtime.configuration()) { requestTick(16); return; }
            pipeline = true; urgent = false; evidence = null; stage = Stage.WAITING;
            long epoch = admission.invalidate(false);
            eligibilityChanged.run();
            OwnedCaptureLifetime.Ticket ticket = work.begin();
            if (ticket == null) { pipeline = false; return; }
            Pending pending = new Pending(adopted, epoch, ticket);
            read(pending);
        }, delay);
    }
    private final class Pending {
        final RuntimeConfiguration configuration;
        final long epoch;
        final OwnedCaptureLifetime.Ticket ticket;
        Pending(RuntimeConfiguration configuration, long epoch, OwnedCaptureLifetime.Ticket ticket) {
            this.configuration = configuration; this.epoch = epoch; this.ticket = ticket;
        }
    }
    private void read(Pending pending) {
        if (!current(pending)) { finishOnMain(pending); return; }
        try {
            work.dispatchContinuation(pending.ticket, callback -> { reads.execute(callback); return true; }, () -> {
                try {
                    if (!current(pending)) { finishOnMain(pending); return; }
                    boolean entered = runtime.runEffect(() -> {
                        if (!current(pending)) { finishOnMain(pending); return; }
                        NativeSample sample = readNative();
                        if (!current(pending)) { finishOnMain(pending); return; }
                        boolean enabled = AppTimerManager.forConfiguration(pending.configuration.main).loadSettings().anyEnabled();
                        boolean protectedNow = protectionEligible.getAsBoolean();
                        Set<String> scope = included(pending.configuration);
                        boolean selected = AppModePolicy.shouldLimit(protectedNow, scope, sample.packageName,
                                sample.ownPackage, sample.inputMethod, sample.homePackage);
                        InternalLimitsAdmission.Observation<RuntimeConfiguration> observed = admission.observe(
                                pending.configuration, pending.epoch, sample.packageName, sample.windowId,
                                sample.monotonic, sample.calendar, enabled, selected, protectedNow);
                        if (observed == null) { finishOnMain(pending); return; }
                        account(pending, observed, selected ? Collections.singleton(sample.packageName) : Collections.emptySet());
                    });
                    if (!entered) continueMain(pending, () -> read(pending), 16);
                } catch (RuntimeException | Error failure) { fail(); }
            });
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void account(Pending pending, InternalLimitsAdmission.Observation<RuntimeConfiguration> observed,
            Set<String> scope) {
        AtomicReference<Assessed> result = new AtomicReference<>();
        try {
            CompletableFuture<Void> receipt = data.executeNormal(() -> {
                CapturedUsage usage = new CapturedUsage(data, observed.calendar);
                AppTimerManager timers = AppTimerManager.forOwner(observed.configuration.main, usage);
                if (observed.usageMillis > 0) timers.recordUsage(observed.packageName,
                        observed.usageMillis, scope, observed.calendar.at);
                // Even disabled/unselected rules use a real, available owner snapshot. Empty
                // package queries the provider's actual aggregate; no unavailable-as-zero path.
                AppTimerManager.UsageSnapshot measured = timers.snapshot(observed.packageName, observed.calendar.at);
                AppTimerManager.LimitStatus limit = timers.limitStatus(observed.packageName, scope, observed.calendar.at);
                result.set(new Assessed(observed, measured, limit));
            });
            receipt.whenComplete((ignored, failure) -> {
                if (failure != null) { fail(); return; }
                Assessed assessed = result.get();
                if (assessed == null) { fail(); return; }
                continueMain(pending, () -> publish(pending, assessed), 0);
            });
        } catch (OwnerWriteQueue.AdmissionRejected refused) {
            // This is the only synchronous definite no-enqueue case. Stop revocation is not an
            // ACK and must not make the observation eligible. Stop this owner, never retry the
            // refused usage payload as if it had been acknowledged.
            admission.invalidate(true); component.failed(); finishOnMain(pending);
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private static final class Assessed {
        final InternalLimitsAdmission.Observation<RuntimeConfiguration> observed;
        final AppTimerManager.UsageSnapshot usage;
        final AppTimerManager.LimitStatus status;
        Assessed(InternalLimitsAdmission.Observation<RuntimeConfiguration> observed,
                AppTimerManager.UsageSnapshot usage, AppTimerManager.LimitStatus status) {
            this.observed = observed; this.usage = usage; this.status = status;
        }
    }
    private void publish(Pending pending, Assessed assessed) {
        requireMain();
        if (!current(pending)) { finish(pending); return; }
        AtomicReference<InternalLimitsAdmission.Decision<RuntimeConfiguration>> intervention = new AtomicReference<>();
        boolean entered = runtime.runEffect(() -> {
            if (!current(pending)) return;
            boolean protectionCurrent = protectionEligible.getAsBoolean();
            if (!protectionCurrent || !InternalLimitsAdmission.fresh(assessed.observed,
                    SystemClock.elapsedRealtime(), OwnerObservation.capture(System.currentTimeMillis()))) {
                // Calendar changes do not reset normal monotonic qualification or erase an
                // accepted old-calendar usage write. The next actual sample reevaluates budgets.
                admission.invalidate(!protectionCurrent); evidence = null; urgent = protectionCurrent; return;
            }
            InternalLimitsAdmission.Decision<RuntimeConfiguration> decision = admission.acknowledge(
                    assessed.observed, assessed.status != AppTimerManager.LimitStatus.NONE);
            if (decision == null) return;
            evidence = new Evidence(runtime.identity(), assessed, decision);
            stage = decision.observation.packageName.isEmpty() ? Stage.WAITING
                    : decision.blocked ? Stage.LIMITED : Stage.ACKNOWLEDGED;
            eligibilityChanged.run(); // Exact module withdrawal/reconciliation only after the ACK.
            if (decision.blocked && decision.observation.accountingAllowed
                    && !decision.observation.packageName.isEmpty()) {
                intervention.set(decision);
            }
        });
        if (!entered) { continueMain(pending, () -> publish(pending, assessed), 16); return; }
        InternalLimitsAdmission.Decision<RuntimeConfiguration> decision = intervention.get();
        if (decision == null) finish(pending);
        else continueMain(pending, () -> intervene(pending, decision, assessed.status), 0);
    }
    private void intervene(Pending pending, InternalLimitsAdmission.Decision<RuntimeConfiguration> decision,
            AppTimerManager.LimitStatus status) {
        requireMain();
        if (!interventionCurrent(pending, decision)) { finish(pending); return; }
        boolean entered = runtime.runEffect(() -> {
            if (!interventionCurrent(pending, decision)) return;
            String name = decision.observation.packageName; long now = decision.observation.monotonic;
            boolean repeated = name.equals(lastBlockedPackage) && lastBlockedMonotonic >= 0
                    && now >= lastBlockedMonotonic && now - lastBlockedMonotonic < 3000;
            lastBlockedPackage = name; lastBlockedMonotonic = now;
            if (!repeated) {
                // The acknowledged spent-budget decision has already withdrawn ordinary effects.
                // Admit/count that actual withdrawal before calling arbitrary local UI. If the UI
                // stops this owner, the accepted event retains its own NORMAL permit and real ACK;
                // no fresh post-Stop accounting is attempted and no notice/Home success is invented.
                if (recordWithdrawal() && interventionCurrent(pending, decision))
                    notice.reached(runtime.identity(), name, status);
            }
        });
        if (!entered) { continueMain(pending, () -> intervene(pending, decision, status), 16); return; }
        // A separate counted continuation obtains a top-level effect after the UI callback and
        // its enclosing effect have actually returned. A nested effect could otherwise inherit
        // the core's configuration-adoption reentrancy exception while a new image is queued.
        continueMain(pending, () -> requestHome(pending, decision), 0);
    }
    private void requestHome(Pending pending, InternalLimitsAdmission.Decision<RuntimeConfiguration> decision) {
        requireMain();
        if (!interventionCurrent(pending, decision)) { finish(pending); return; }
        boolean entered = runtime.runEffect(() -> {
            if (!interventionCurrent(pending, decision)) return;
            if (accessibility.accessibility().service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                admission.homeRequested(decision); // Stale native results are inert bookkeeping.
        });
        if (!entered) { continueMain(pending, () -> requestHome(pending, decision), 16); return; }
        finish(pending);
    }
    private boolean recordWithdrawal() {
        OwnerObservation observed = OwnerObservation.capture(System.currentTimeMillis());
        OwnedCaptureLifetime.Ticket accounting = work.begin();
        if (accounting == null) return false;
        try {
            data.executeNormal(() -> data.recordLimitIntervention(observed)).whenComplete((ignored, failure) -> {
                if (failure == null) accounting.close(); else fail();
            });
            return !cleanupFailed;
        } catch (OwnerWriteQueue.AdmissionRejected refused) {
            // This synchronous typed receipt proves no executor invocation. Release precisely
            // that never-admitted obligation, stop cleanly, and do not replay it or call Home.
            accounting.close(); seal(); return false;
        } catch (RuntimeException | Error uncertain) { fail(); return false; }
    }
    private boolean interventionCurrent(Pending pending,
            InternalLimitsAdmission.Decision<RuntimeConfiguration> decision) {
        boolean protectedNow = protectionEligible.getAsBoolean();
        return protectedNow && current(pending) && admission.currentDecision(decision)
                && decision.observation.accountingAllowed && !decision.observation.packageName.isEmpty()
                && InternalLimitsAdmission.fresh(decision.observation, SystemClock.elapsedRealtime(),
                        OwnerObservation.capture(System.currentTimeMillis()));
    }
    private boolean current(Pending pending) {
        return !sealed.get() && !cleanupFailed && accessibility.isConnected() && data.isCurrentStamp(session)
                && pending.configuration == acknowledgedConfiguration && pending.configuration == runtime.configuration()
                && admission.current(pending.configuration, pending.epoch);
    }
    private void finishOnMain(Pending pending) { continueMain(pending, () -> finish(pending), 0); }
    private void finish(Pending pending) {
        requireMain(); pipeline = false; pending.ticket.close();
        if (!sealed.get()) requestTick(urgent ? 0 : 1000);
    }
    private void continueMain(Pending pending, Runnable action, long delay) {
        try {
            if (!work.dispatchContinuation(pending.ticket, callback -> main.postDelayed(callback, delay), () -> {
                try { action.run(); }
                catch (RuntimeException | Error failure) { fail(); }
            })) fail();
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void dispatchMain(Runnable action, long delay) {
        try {
            if (!work.dispatch(callback -> main.postDelayed(callback, delay), () -> {
                try { action.run(); }
                catch (RuntimeException | Error failure) { fail(); }
            }) && !sealed.get()) fail();
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void fail() {
        cleanupFailed = true; stage = Stage.FAILED; evidence = null; admission.seal(); component.cleanupFailed();
    }
    private void closeFromRuntime(Runnable completion) {
        sealed.set(true); admission.seal(); evidence = null; stage = cleanupFailed ? Stage.FAILED : Stage.STOPPING;
        afterDrained = Objects.requireNonNull(completion);
        if (!closing.compareAndSet(false, true)) return;
        // Accepted native reads, Handler callbacks and broker ACK continuations retain tickets.
        // A disconnected source, executor exception or a timeout is never a closure receipt.
        work.seal(reads::shutdown);
        pollDrain();
    }
    private void pollDrain() {
        cleanupCallbacks.incrementAndGet();
        try {
            if (!main.postDelayed(() -> {
                try {
                    if (!cleanupFailed && work.drained() && reads.isTerminated()) drained.set(true);
                    else if (!cleanupFailed) pollDrain();
                } finally {
                    if (cleanupCallbacks.decrementAndGet() == 0 && drained.get() && !cleanupFailed) {
                        stage = Stage.DRAINED; afterDrained.run();
                    }
                }
            }, 16)) { cleanupCallbacks.decrementAndGet(); fail(); }
        } catch (RuntimeException | Error uncertain) { fail(); }
    }
    private void requireConfiguration(RuntimeConfiguration configuration) {
        if (configuration == null || !runtime.identity().equals(configuration.owner))
            throw new SecurityException("Different Limits configuration owner");
    }
    private static Set<String> included(RuntimeConfiguration configuration) {
        Set<String> selected = configuration.main.getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.emptySet());
        return AppModePolicy.sanitizePackages(configuration.main.getBoolean(AppModeManager.KEY_ALL_APPS, false)
                ? configuration.main.getStringSet("app_include_all_packages_v1", selected) : selected);
    }
    private static final class CapturedUsage implements AppTimerManager.OwnerUsage {
        final InternalOwnerDataBroker.Session data;
        final OwnerObservation observed;
        CapturedUsage(InternalOwnerDataBroker.Session data, OwnerObservation observed) { this.data = data; this.observed = observed; }
        private void require(long at) { if (at != observed.at) throw new SecurityException("Usage observation clock changed"); }
        @Override public AppTimerManager.UsageSnapshot snapshot(String name, long at) { require(at); return data.usageSnapshot(name, observed); }
        @Override public Map<String, AppTimerManager.UsageSnapshot> snapshots(Set<String> names, long at) { require(at); return data.usageSnapshots(names, observed); }
        @Override public void recordUsage(String name, long elapsed, long at) { require(at); data.recordUsage(name, elapsed, observed); }
        @Override public void checkpointHistory(long at) { require(at); data.checkpointHistory(observed); }
    }
    private static final class NativeSample {
        final String packageName, ownPackage, inputMethod, homePackage;
        final int windowId;
        final long monotonic;
        final OwnerObservation calendar;
        NativeSample(String name, int windowId, String ownPackage, String inputMethod, String homePackage) {
            this.packageName = name; this.windowId = windowId; this.ownPackage = ownPackage;
            this.inputMethod = inputMethod; this.homePackage = homePackage;
            monotonic = SystemClock.elapsedRealtime(); calendar = OwnerObservation.capture(System.currentTimeMillis());
        }
    }
    @SuppressWarnings("deprecation")
    private NativeSample readNative() {
        requireWorker();
        AccessibilityService service = accessibility.accessibility().service();
        String input = Settings.Secure.getString(service.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (input == null) input = "";
        int slash = input.indexOf('/'); if (slash >= 0) input = input.substring(0, slash);
        ResolveInfo home = service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        // An unresolved escape surface cannot safely be enforced. It is unavailable, not selected.
        if (home == null || home.activityInfo == null || home.activityInfo.packageName == null)
            throw new IllegalStateException("Limits cannot identify the device Home surface");
        PowerManager power = service.getSystemService(PowerManager.class);
        KeyguardManager keyguard = service.getSystemService(KeyguardManager.class);
        boolean interactive = power != null && power.isInteractive(), locked = keyguard == null || keyguard.isKeyguardLocked();
        List<AccessibilityWindowInfo> windows = interactive && !locked ? service.getWindows() : null;
        try {
            if (windows != null && windows.size() > 32) throw new IllegalStateException("Unbounded foreground window source");
            String name = ForegroundUsageWindowReader.read(windows, input, interactive, locked);
            AccessibilityWindowInfo focused = null;
            if (!name.isEmpty() && windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.isFocused() && window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        && (focused == null || window.getLayer() > focused.getLayer())) focused = window;
            }
            int id = focused == null ? -1 : focused.getId();
            return new NativeSample(id < 0 ? "" : name, id, service.getPackageName(), input, home.activityInfo.packageName);
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows) if (window != null) window.recycle();
        }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Limits callback requires main");
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Limits native/data work cannot run on main");
    }
}
