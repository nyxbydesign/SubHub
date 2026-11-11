package com.subhub.app.service;

import android.os.Handler;
import android.os.Looper;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.popup.PopupRuntimeBinding;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeEnvironment;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.subliminal.SubliminalOverlayController;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Shared exact INTERNAL Limits/Whispers/Popup dependencies, not a permission/start capability. */
public final class InternalAccessibilityModules {
    public static final String COMPONENT = "internal_accessibility_modules";
    public static final String LIMITS = "internal_foreground";
    public static final String WHISPERS = "internal_whispers";
    public static final String POPUPS = "internal_popups";
    private final RuntimeEnvironment runtime;
    private final RuntimeCaptureHost.Connection connection;
    private final RuntimeCaptureHost.InternalResources resources;
    private final RuntimeExecution<RuntimeConfiguration>.Component component, whisperComponent;
    private volatile InternalLimitsBinding limits;
    private volatile PopupRuntimeBinding popups;
    private final Runnable captureReconciliation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final AtomicBoolean sealed = new AtomicBoolean();
    private final CompletableFuture<Void> prepared = new CompletableFuture<>();
    private volatile SubliminalOverlayController whispers;
    private volatile boolean constructionFailed;
    private boolean activated, retryQueued, hintsBound;

    public InternalAccessibilityModules(RuntimeEnvironment runtime, InternalOwnerDataBroker.Session data,
            InternalStorageScope storage, RuntimeCaptureHost.Connection accessibility,
            RuntimeCaptureHost.InternalResources resources, Executor ownerWorker,
            ExecutorService ownedForegroundReads, Executor artworkWorker,
            BooleanSupplier protectionEligible, Runnable captureReconciliation,
            InternalLimitsBinding.LocalIntervention localNotice) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Register INTERNAL modules on owner worker");
        this.runtime = Objects.requireNonNull(runtime); connection = Objects.requireNonNull(accessibility);
        this.resources = Objects.requireNonNull(resources);
        this.captureReconciliation = Objects.requireNonNull(captureReconciliation);
        Objects.requireNonNull(protectionEligible);
        if (connection.kind != RuntimeCaptureHost.Kind.ACCESSIBILITY || !connection.isConnected()
                || !runtime.identity().equals(resources.owner) || !runtime.identity().equals(data.identity()))
            throw new SecurityException("Modules require exact owner and connected Accessibility host");
        component = runtime.registerComponent(COMPONENT);
        component.installShutdown(this::closeFromRuntime);
        component.installConfigurationReceiver(new RuntimeExecution.ConfigurationReceiver<RuntimeConfiguration>() {
            @Override public void apply(RuntimeConfiguration snapshot) {
                requireMain();
                if (sealed.get()) throw new SecurityException("Module assembly sealed");
                if (whispers != null) whispers.setEligible(false);
            }
            @Override public void acknowledged(RuntimeConfiguration snapshot) { postReconciliation(); }
        });
        try {
            limits = new InternalLimitsBinding(runtime, data, connection, LIMITS, ownedForegroundReads,
                    protectionEligible, this::eligibilityChanged, localNotice);
            popups = PopupRuntimeBinding.prepare(connection.accessibility().service(), runtime, POPUPS, storage,
                    owner -> {
                        if (!runtime.identity().equals(owner)) throw new SecurityException("Different Popup accounting owner");
                        OwnerObservation observed = OwnerObservation.capture(System.currentTimeMillis());
                        data.executeNormal(() -> data.recordPopupImpression(observed)).whenComplete((ignored, failure) -> {
                            if (failure != null) runtime.requestStop();
                        });
                    }, ownerWorker, artworkWorker);
            whisperComponent = runtime.registerComponent(WHISPERS);
        } catch (RuntimeException | Error failed) { constructionFailed = true; component.failed(); throw failed; }
        boolean accepted;
        try { accepted = work.dispatch(main::post, this::prepareOnMain); }
        catch (RuntimeException | Error uncertain) {
            prepared.completeExceptionally(uncertain); component.failed(); throw uncertain;
        }
        if (!accepted) {
            whisperComponent.usesNoConfiguration();
            whisperComponent.installShutdown(Runnable::run);
            prepared.completeExceptionally(new IllegalStateException("Module main queue unavailable"));
            component.failed();
        }
    }
    private void prepareOnMain() {
        requireMain();
        try {
            whispers = runtime.bindWhispers(connection.accessibility().service(), whisperComponent);
            if (sealed.get() || !connection.isConnected()) throw new SecurityException("Module source retired");
            limits.start();
            OwnedCaptureLifetime.Ticket readiness = work.begin();
            if (readiness == null) throw new SecurityException("Module preparation sealed");
            component.readyAsync(result -> {
                try {
                    if (result == ActiveRuntimeCoordinator.Result.OK) prepared.complete(null);
                    else { prepared.completeExceptionally(new IllegalStateException("Module preparation not acknowledged")); component.failed(); }
                } finally { readiness.close(); }
            });
        } catch (RuntimeException | Error failure) { prepared.completeExceptionally(failure); component.failed(); }
    }
    public CompletableFuture<Void> prepared() {
        CompletableFuture<Void> response = new CompletableFuture<>();
        response.whenComplete((ignored, failure) -> { if (response.isCancelled()) runtime.requestStop(); });
        prepared.whenComplete((ignored, failure) -> {
            boolean delivered = failure == null ? response.complete(null) : response.completeExceptionally(failure);
            if (!delivered) runtime.requestStop();
        });
        return response;
    }
    public void activationAcknowledged() {
        requireMain(); if (sealed.get()) return;
        activated = true; limits.activationAcknowledged(); reconcile();
    }
    public void sourceChanged() { requireMain(); if (!sealed.get()) limits.sourceChanged(); }
    public boolean eligible(RuntimeLease.Identity owner, RuntimeConfiguration configuration,
            String packageName, int focusedWindowId) {
        requireMain(); return !sealed.get() && limits.eligible(owner, configuration, packageName, focusedWindowId);
    }
    public InternalLimitsBinding.Evidence evidence() { return limits.evidence(); }
    public boolean eligible(InternalLimitsBinding.Evidence evidence) {
        requireMain(); return !sealed.get() && limits.eligible(evidence);
    }
    private void eligibilityChanged() {
        requireMain(); if (sealed.get()) return;
        try {
            reconcile();
            if (!sealed.get()) captureReconciliation.run();
        } catch (RuntimeException | Error failed) {
            component.failed(); throw failed;
        }
    }
    private void reconcile() {
        requireMain(); if (sealed.get() || !activated || whispers == null) return;
        boolean eligible = connection.isConnected() && limits.eligible(limits.evidence());
        whispers.setEligible(eligible);
        boolean settled = popups.setEligible(runtime.configuration(), eligible);
        if (!hintsBound) hintsBound = popups.bindDetectionHints(() -> resources.popupHints.read(Collections.emptyList()));
        settled &= hintsBound;
        settled &= popups.activate();
        if (!settled) postReconciliation();
    }
    private void postReconciliation() {
        if (sealed.get()) return;
        try {
            if (!work.dispatch(main::post, () -> {
                if (sealed.get() || retryQueued) return;
                retryQueued = true;
                try {
                    if (!work.dispatch(command -> main.postDelayed(command, 16L), () -> {
                        retryQueued = false; if (!sealed.get()) reconcile();
                    })) component.failed();
                } catch (RuntimeException | Error uncertain) { component.failed(); }
            })) component.failed();
        } catch (RuntimeException | Error uncertain) { component.failed(); }
    }
    private void closeFromRuntime(Runnable afterDrained) {
        sealed.set(true);
        work.seal(afterDrained);
    }
    public boolean managedCallbacksDrained() { return work.drained(); }
    /** Core initiates every child independently. Never await the parent/global owner here. */
    public boolean managedResourcesDrained() {
        requireMain();
        return !constructionFailed && sealed.get() && work.drained()
                && limits != null && limits.managedResourcesDrained()
                && popups != null && popups.managedResourcesDrained()
                && whispers != null && whispers.managedResourcesDrained();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Module callback requires main");
    }
}
