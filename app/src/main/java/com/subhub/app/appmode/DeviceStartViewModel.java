package com.subhub.app.appmode;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.ViewModel;
import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.runtime.RuntimeStartAttempt;
import com.subhub.app.settings.CaptureMethod;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Retains the exact process-local DEVICE attempt across Home recreation. Nothing is saved in a
 * Bundle. Separate start-only launchers return here even after role loss or cancellation; a new
 * Activity may present the same attempt, but cannot reconstruct one from preferences or an Intent.
 */
public final class DeviceStartViewModel extends ViewModel {
    public interface Ui {
        boolean available();
        boolean granted(RuntimeStartAttempt.Permission permission);
        /** Register the exact result route and prepare its Intent, without launching Android. */
        Runnable prepare(PromptRoute route);
        void changed();
        void completed(AppRuntimeGateway.StartState state);
    }
    /** Must enqueue on the main queue, never run inline, including for synchronous test results. */
    interface MainQueue { boolean post(Runnable action); }
    private final MainQueue main;
    private final EnumSet<RuntimeStartAttempt.Permission> attempted =
            EnumSet.noneOf(RuntimeStartAttempt.Permission.class);
    private volatile AppRuntimeGateway.DeviceStart attempt;
    private volatile boolean cleared, dispatchFailed;
    private Ui ui;
    private PromptRoute route;
    private boolean active, reserving, issuing, waitingResult, starting, cancelled;
    private boolean projectionApproved;
    private AppRuntimeGateway.StartState terminal;

    public DeviceStartViewModel() {
        Handler handler = new Handler(Looper.getMainLooper());
        main = handler::post;
    }
    DeviceStartViewModel(MainQueue main) { this.main = Objects.requireNonNull(main); }

    public boolean busy() { return active || dispatchFailed; }

    /** Re-register this same object/key after recreation; a saved key cannot reconstruct it. */
    public PromptRoute pendingRoute() { return route; }

    /**
     * One real registration/launch identity, never a lookup of whichever permission is current.
     * After settlement this becomes an inert tombstone: no model, attempt, prompt or Intent remains.
     */
    public static final class PromptRoute {
        public final String registrationKey = "device-start:" + UUID.randomUUID();
        public final RuntimeStartAttempt.Permission permission;
        private final AtomicBoolean submitted = new AtomicBoolean();
        private volatile boolean launched;
        private Delivery delivery;
        private final Map<Runnable, Boolean> retired = new IdentityHashMap<>();
        private PromptRoute(DeviceStartViewModel model, AppRuntimeGateway.DeviceStart owner,
                RuntimeStartAttempt.Prompt prompt) {
            permission = prompt.permission; delivery = new Delivery(model, owner, prompt);
        }
        /** The callback registered for this exact key calls this even after host/role retirement. */
        public void returned(BooleanSupplier granted, int resultCode, Intent projectionData) {
            Delivery exact;
            synchronized (this) {
                exact = delivery;
                if (exact == null || !launched || !submitted.compareAndSet(false, true)) return;
            }
            exact.model.permissionReturned(this, exact, granted, resultCode, projectionData);
        }
        synchronized boolean isRetired() { return delivery == null; }
        AutoCloseable whenRetired(Runnable observer) {
            synchronized (this) {
                if (delivery != null) { retired.put(observer, Boolean.TRUE); return () -> remove(observer); }
            }
            observer.run(); return () -> { };
        }
        private synchronized void remove(Runnable observer) { retired.remove(observer); }
        private void retire() {
            ArrayList<Runnable> observers;
            synchronized (this) {
                delivery = null;
                observers = new ArrayList<>(retired.keySet()); retired.clear();
            }
            for (Runnable observer : observers) observer.run();
        }
    }
    private static final class Delivery {
        final DeviceStartViewModel model;
        final AppRuntimeGateway.DeviceStart owner;
        final RuntimeStartAttempt.Prompt prompt;
        Delivery(DeviceStartViewModel model, AppRuntimeGateway.DeviceStart owner, RuntimeStartAttempt.Prompt prompt) {
            this.model = model; this.owner = owner; this.prompt = prompt;
        }
    }

    public void attach(Ui next) {
        if (cleared) return;
        ui = Objects.requireNonNull(next);
        changed();
        advance();
    }
    public void detach(Ui previous) { if (ui == previous) ui = null; }

    public boolean begin(AppRuntimeGateway gateway, AppRuntimeGateway.DeviceStartSpec specification) {
        return begin(() -> gateway.deviceStart(specification));
    }
    boolean begin(Supplier<CompletableFuture<AppRuntimeGateway.DeviceStart>> admission) {
        if (busy() || cleared) return false;
        active = reserving = true;
        cancelled = projectionApproved = false;
        attempted.clear(); terminal = null;
        changed();
        try {
            Objects.requireNonNull(admission.get()).whenComplete((accepted, failure) -> {
                // A destroyed host cannot discard an asynchronously admitted owner.
                if (cleared || dispatchFailed) { cancel(accepted); return; }
                post(() -> {
                    reserving = false;
                    attempt = accepted;
                    if (failure != null || accepted == null) { abort(AppRuntimeGateway.StartState.FAILED); return; }
                    if (cleared || cancelled) { abort(AppRuntimeGateway.StartState.CANCELLED); return; }
                    advance();
                }, accepted);
            });
        } catch (RuntimeException failure) {
            reserving = false;
            abort(AppRuntimeGateway.StartState.FAILED);
        }
        return true;
    }

    /** Call only from the matching registered Activity-result callback, even if the page is gone. */
    private void permissionReturned(PromptRoute returned, Delivery delivery, BooleanSupplier granted,
            int resultCode, Intent projectionData) {
        RuntimeStartAttempt.Prompt exact = delivery.prompt;
        AppRuntimeGateway.DeviceStart owner = delivery.owner;
        boolean observed = false;
        try { observed = granted.getAsBoolean(); }
        catch (RuntimeException unavailable) {
            if (route == returned && attempt == owner) abort(AppRuntimeGateway.StartState.FAILED);
            else cancel(owner);
        }
        final boolean actualGrant = observed;
        // Handler.post is deliberately unconditional. The gateway must not discharge its prompt
        // while Android is still inside this callback, including a synchronous launcher callback.
        post(() -> {
            try {
                Objects.requireNonNull(owner.permissionResult(exact, actualGrant, resultCode, projectionData))
                        .whenComplete((usable, failure) -> post(() -> {
                            if (failure != null) {
                                if (route == returned && attempt == owner) abort(AppRuntimeGateway.StartState.RECOVERY_REQUIRED);
                                else cancel(owner);
                                return; // Keep uncertain obligations and their exact route.
                            }
                            returned.retire();
                            if (route != returned || attempt != owner) return;
                            route = null; waitingResult = false;
                            if (cleared || cancelled || !Boolean.TRUE.equals(usable)) {
                                abort(AppRuntimeGateway.StartState.CANCELLED); return;
                            }
                            if (returned.permission == RuntimeStartAttempt.Permission.PROJECTION) projectionApproved = actualGrant;
                            advance();
                        }, owner));
            } catch (RuntimeException failure) {
                // Unknown dispatch/result outcome retains the slot; never claim a physical return.
                if (route == returned && attempt == owner) abort(AppRuntimeGateway.StartState.RECOVERY_REQUIRED);
                else cancel(owner);
            }
        }, owner);
    }

    private void advance() {
        if (!active || cleared || cancelled || dispatchFailed || reserving || issuing || waitingResult || starting) return;
        Ui host = ui;
        if (host == null || !host.available()) return;
        AppRuntimeGateway.DeviceStart owner = attempt;
        if (owner == null) { abort(AppRuntimeGateway.StartState.FAILED); return; }
        try {
            AppRuntimeGateway.DevicePlan plan = owner.plan();
            if (plan.requestNotifications && !attempted.contains(RuntimeStartAttempt.Permission.NOTIFICATIONS)
                    && !host.granted(RuntimeStartAttempt.Permission.NOTIFICATIONS)) {
                issue(RuntimeStartAttempt.Permission.NOTIFICATIONS); return;
            }
            if (plan.requiresAccessibility && !host.granted(RuntimeStartAttempt.Permission.ACCESSIBILITY)) {
                require(RuntimeStartAttempt.Permission.ACCESSIBILITY); return;
            }
            if (plan.requiresOverlay && !host.granted(RuntimeStartAttempt.Permission.OVERLAY)) {
                require(RuntimeStartAttempt.Permission.OVERLAY); return;
            }
            if (plan.capture == CaptureMethod.SCREEN_RECORDING && !projectionApproved) {
                require(RuntimeStartAttempt.Permission.PROJECTION); return;
            }
            starting = true;
            Objects.requireNonNull(owner.start()).whenComplete((result, failure) -> post(() -> {
                starting = false;
                if (failure != null || result == null) { abort(AppRuntimeGateway.StartState.FAILED); return; }
                if (result.state != AppRuntimeGateway.StartState.ACTIVE) { abort(result.state); return; }
                if (!owner.identity().equals(result.owner)) { abort(AppRuntimeGateway.StartState.FAILED); return; }
                // ACTIVE is the gateway's acknowledged transition, never a preference/role guess.
                // Cancellation may lose to ownership transfer. Retire this UI attempt without a
                // stale success announcement; it cannot stop the already-transferred runtime.
                if (cleared || cancelled) { active = false; attempt = null; terminal = null; changed(); return; }
                if (dispatchFailed) { terminal = AppRuntimeGateway.StartState.RECOVERY_REQUIRED; changed(); return; }
                active = false; attempt = null; terminal = result.state; changed();
            }, owner));
        } catch (RuntimeException failure) {
            starting = false;
            abort(AppRuntimeGateway.StartState.FAILED);
        }
    }
    private void require(RuntimeStartAttempt.Permission kind) {
        if (attempted.contains(kind)) abort(AppRuntimeGateway.StartState.NOT_READY);
        else issue(kind);
    }
    private void issue(RuntimeStartAttempt.Permission kind) {
        AppRuntimeGateway.DeviceStart owner = attempt;
        issuing = true; attempted.add(kind); changed();
        try {
            Objects.requireNonNull(owner.permission(kind)).whenComplete((issued, failure) -> post(() -> {
                issuing = false;
                if (failure != null || issued == null) { abort(AppRuntimeGateway.StartState.FAILED); return; }
                if (cleared || cancelled || dispatchFailed || issued.permission != kind
                        || !owner.identity().equals(issued.identity())) {
                    abort(AppRuntimeGateway.StartState.CANCELLED); return;
                }
                route = new PromptRoute(this, owner, issued);
                launchPending();
            }, owner));
        } catch (RuntimeException failure) {
            issuing = false;
            abort(AppRuntimeGateway.StartState.FAILED);
        }
    }
    private void launchPending() {
        Ui host = ui;
        if (route == null || waitingResult || cancelled || cleared || dispatchFailed
                || host == null || !host.available()) return;
        PromptRoute pending = route;
        Delivery exact = pending.delivery;
        final Runnable launch;
        try { launch = Objects.requireNonNull(host.prepare(pending)); }
        catch (RuntimeException preparationFailed) { abort(AppRuntimeGateway.StartState.FAILED); return; }
        waitingResult = true;
        try {
            if (!exact.prompt.launch(() -> { pending.launched = true; launch.run(); })) {
                // Only a false launch proves the OS action was never admitted.
                waitingResult = false;
                abort(AppRuntimeGateway.StartState.CANCELLED);
            }
        } catch (RuntimeException launchFailure) {
            // Android can throw after accepting the launch. Keep its exact result slot occupied.
            abort(AppRuntimeGateway.StartState.RECOVERY_REQUIRED);
        }
    }
    public void cancel() {
        if (active || attempt != null) abort(AppRuntimeGateway.StartState.CANCELLED);
    }
    private void abort(AppRuntimeGateway.StartState reason) {
        cancelled = true;
        cancel(attempt);
        if (!waitingResult && route != null && !dispatchFailed) { route.retire(); route = null; }
        if (!reserving && !issuing && !waitingResult && !starting) {
            active = false;
            if (!dispatchFailed) attempt = null;
        }
        // A start transfer can already have won. Do not announce cancellation while its result
        // is outstanding, then contradict that announcement with a late ACTIVE callback.
        if (!starting && terminal == null) terminal = reason;
        changed();
    }
    private void cancel(AppRuntimeGateway.DeviceStart exact) {
        if (exact == null) return;
        try { exact.cancel(); }
        catch (RuntimeException uncertain) { dispatchFailed = true; }
    }
    private void post(Runnable action, AppRuntimeGateway.DeviceStart exact) {
        try { if (main.post(action)) return; }
        catch (RuntimeException uncertain) { /* A queued callback may still arrive; fence it below. */ }
        // Queue failure is not cleanup proof. Retain occupancy and prevent any local retry/fallback.
        dispatchFailed = true;
        cancel(exact);
    }
    private void changed() {
        Ui host = ui;
        if (host == null || !host.available()) return;
        host.changed();
        if (terminal != null) {
            AppRuntimeGateway.StartState report = terminal;
            terminal = null;
            host.completed(report);
        }
        launchPending();
    }
    @Override protected void onCleared() {
        cleared = true; ui = null;
        cancel(); // Does not discharge any admitted prompt or claim Android has returned it.
    }
}
