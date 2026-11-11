package com.subhub.app.runtime;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.commitment.PactDuration;
import com.subhub.app.settings.CaptureMethod;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Application boundary for the common physical owner. This facade does not provision, infer IDLE,
 * grant permissions or install itself. The Android owner factory must qualify and install its one
 * physical backend before exposing INTERNAL Start. Until installation, only the preexisting DEVICE
 * entry path exists. After installation, unavailable/pending/rejected results NEVER use that path.
 */
public final class AppRuntimeGateway {
    public enum StartState { ACTIVE, CANCELLED, NOT_READY, OCCUPIED, RECOVERY_REQUIRED, FAILED }
    public enum RearmReason { BOOT, PAUSE_RESTORE, COMMITMENT, EXPIRY, RESUME }
    public enum Mutation { ARM, DISARM, SAVE_ARMED, SAVE_DISARMED, PROJECTION_STARTED, PROJECTION_STOPPED }
    /** New managed physical lifecycle only; ordinary legacy rearm keeps its separate API. */
    public enum ManagedDevicePhase { STARTUP, CLEANUP }

    /** User-selected duration/capture values; neither this object nor its fields grant authority. */
    public static final class DeviceStartSpec {
        public final CaptureMethod capture;
        public final long minimum, maximum;
        public final boolean hidden;
        public DeviceStartSpec(CaptureMethod capture, long minimum, long maximum, boolean hidden) {
            this.capture = Objects.requireNonNull(capture);
            if (minimum != 0 || maximum != 0) PactDuration.validate(minimum, maximum);
            this.minimum = minimum; this.maximum = maximum; this.hidden = hidden;
        }
    }
    /** The factory derives these from the exact admitted DEVICE config, never from a peer/INTERNAL. */
    public static final class DevicePlan {
        public final CaptureMethod capture;
        public final boolean requiresAccessibility, requiresOverlay, requestNotifications;
        DevicePlan(CaptureMethod capture, boolean accessibility, boolean overlay, boolean notifications) {
            this.capture = Objects.requireNonNull(capture); requiresAccessibility = accessibility;
            requiresOverlay = overlay; requestNotifications = notifications;
        }
    }
    public static final class StartResult {
        public final StartState state;
        public final RuntimeLease.Identity owner;
        StartResult(StartState state, RuntimeLease.Identity owner) {
            this.state = Objects.requireNonNull(state); this.owner = owner;
            if (owner != null && owner.target.kind != RuntimeTarget.Kind.DEVICE)
                throw new SecurityException("Not a DEVICE start result");
            if (state == StartState.ACTIVE && owner == null) throw new IllegalArgumentException("Missing active owner");
        }
    }
    public interface DeviceStart {
        RuntimeLease.Identity identity();
        DeviceStartSpec specification();
        DevicePlan plan();
        CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt.Permission kind);
        /**
         * Invoke only after the exact real callback has returned, including a stale/destroyed host.
         * The factory distinguishes optional notification denial from required permission denial;
         * callers pass the actual grant, never turn it into success themselves. Projection data is
         * accepted only for that prompt and retained process-locally for the one-use service claim.
         * A launcher exception is NOT a result callback or proof no dialog opened: call cancel only.
         */
        CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt.Prompt prompt, boolean granted,
                int resultCode, Intent projectionData);
        /** Completes ACTIVE only after journal/component ACK; not model/first-frame readiness. */
        CompletableFuture<StartResult> start();
        /** Nonblocking fence; outstanding prompts/native resources remain owned until actual return. */
        boolean cancel();
    }
    public interface DeviceMutationPermit extends AutoCloseable {
        /** Holds exact counted DEVICE authority through the actual synchronous mutation return. */
        @Override void close();
    }
    public static final class DeviceAdmissionException extends SecurityException {
        private static final long serialVersionUID = 1L;
        public DeviceAdmissionException(String message) { super(message); }
    }
    /** Implemented only by the physical Android owner factory; no public setter or fallback hook. */
    interface Backend {
        CompletableFuture<DeviceStart> deviceStart(DeviceStartSpec specification);
        /**
         * expected is actual immutable component evidence, not authority or a caller-trusted string.
         * Verify current exact DEVICE lease + actual component identities. Hold counted authority
         * until admittedLegacyAction returns; its child service/OS actions retain their own leases.
         * Unknown/missing legacy ownership enters explicit recovery, never an IDLE reservation.
         */
        CompletableFuture<StartResult> rearmDevice(RearmReason reason,
                RuntimeSessionIdentity.ComponentObservation expected, Runnable admittedLegacyAction);
        /** Short main-safe capability check only; never journal IO, waiting or acquiring RuntimeMutex. */
        DeviceMutationPermit admitDeviceMutation(Mutation mutation);
        default DeviceMutationPermit admitManagedDeviceMutation(RuntimeLease.Identity expected,
                Mutation mutation, ManagedDevicePhase phase) {
            throw new DeviceAdmissionException("Managed DEVICE lifecycle authority is unavailable");
        }
        /** Required before installation; absent routing must fail, never revive the legacy path. */
        default ServiceAccess serviceAccess() { throw new DeviceAdmissionException("Service ownership is not qualified"); }
    }
    private static volatile AppRuntimeGateway installed;
    static final RuntimeMutex PHYSICAL_MUTEX = new RuntimeMutex(RuntimeSessionIdentity.LOCK);
    private static final RuntimeInstallationBarrier BOOTSTRAP = new RuntimeInstallationBarrier(PHYSICAL_MUTEX);
    private static Application bootstrapApplication;
    private static RuntimeInstallationBarrier.Installation preparedInstallation;
    private final Application application;
    private final Backend backend;
    private AppRuntimeGateway(Application application, Backend backend) {
        this.application = Objects.requireNonNull(application); this.backend = Objects.requireNonNull(backend);
    }
    static synchronized RuntimeInstallationBarrier.Installation prepareInstallation(Application application) {
        bindPhysicalApplication(application);
        // Owner worker holds PHYSICAL_MUTEX, closes admission before independent quiescence proof.
        RuntimeInstallationBarrier.Installation proof = BOOTSTRAP.prepareInstallation();
        if (proof != null) preparedInstallation = proof;
        return proof;
    }
    static synchronized void install(Application application, Backend qualifiedBackend,
            RuntimeInstallationBarrier.Installation proof) {
        bindPhysicalApplication(application);
        if (installed != null) throw new IllegalStateException("Physical runtime gateway already installed");
        if (proof == null || proof != preparedInstallation)
            throw new SecurityException("Installation proof is not from the physical bootstrap barrier");
        AppRuntimeGateway value = new AppRuntimeGateway(application, qualifiedBackend);
        proof.installed();
        installed = value;
    }
    private static synchronized void bindPhysicalApplication(Application application) {
        Objects.requireNonNull(application);
        if (bootstrapApplication == null) bootstrapApplication = application;
        else if (bootstrapApplication != application) throw new SecurityException("Different physical application");
    }
    /**
     * Inactive migration counting only, not start/role/permission authority. Existing legacy starts
     * retain this before their first action, fork before every asynchronous handoff, and close each
     * child only after its exact actual callback/resource return. A thrown dispatch is not proof.
     * Known installed/failed/preparing state refuses rather than falling back to legacy behavior.
     */
    public static LegacyWork beginLegacyWork(Context physical) {
        Context context = Objects.requireNonNull(physical).getApplicationContext();
        if (!(context instanceof Application)) throw new SecurityException("Physical application required");
        bindPhysicalApplication((Application) context);
        if (installed != null) throw new DeviceAdmissionException("Common runtime requires an admitted DEVICE continuation");
        return new LegacyWork(BOOTSTRAP.legacyWork());
    }
    public static final class LegacyWork implements AutoCloseable {
        private final RuntimeInstallationBarrier.LegacyWork counted;
        private LegacyWork(RuntimeInstallationBarrier.LegacyWork counted) { this.counted = counted; }
        public LegacyWork fork() { return new LegacyWork(counted.fork()); }
        public LegacyPermission permission() { return new LegacyPermission(fork()); }
        @Override public void close() { counted.close(); }
    }
    /** A preinstall permission owns both actual launch return and its exact result callback. */
    public static final class LegacyPermission {
        private final LegacyWork counted;
        private boolean launched, launchReturned, resultReturned, closed;
        private LegacyPermission(LegacyWork counted) { this.counted = counted; }
        public boolean launch(Runnable action) {
            Objects.requireNonNull(action);
            synchronized (this) { if (closed || launched) return false; launched = true; }
            try { action.run(); return true; }
            finally {
                boolean release;
                synchronized (this) { launchReturned = true; release = retireIfReturned(); }
                if (release) counted.close();
            }
        }
        public boolean cancelBeforeLaunch() {
            synchronized (this) { if (launched || closed) return false; closed = true; }
            counted.close(); return true;
        }
        /** Call after the exact real callback returns, not on launcher exception/Activity removal. */
        public void resultReturned() {
            boolean release;
            synchronized (this) {
                if (!launched || resultReturned) throw new SecurityException("Different or repeated legacy permission result");
                resultReturned = true; release = retireIfReturned();
            }
            if (release) counted.close();
        }
        private boolean retireIfReturned() {
            if (!closed && launchReturned && resultReturned) { closed = true; return true; }
            return false;
        }
    }
    /** Cached facade only. null means this inactive integration has not installed a common gateway. */
    public static AppRuntimeGateway current(Context physical) {
        Context application = Objects.requireNonNull(physical).getApplicationContext();
        if (!(application instanceof Application)) throw new SecurityException("Physical application required");
        AppRuntimeGateway value = installed;
        if (value != null && value.application != application) throw new SecurityException("Different physical application");
        return value;
    }
    /** Null only before installation. Failure of an installed route never means legacy fallback. */
    public static ServiceAccess services(Context physical) {
        AppRuntimeGateway gateway = current(physical);
        if (gateway == null) return null;
        ServiceAccess result = Objects.requireNonNull(gateway.backend.serviceAccess());
        if (result.application != gateway.application) throw new SecurityException("Different physical service access");
        return result;
    }
    /**
     * Typed service ingress onto the same serial owner lane. Receipts mean the gateway method
     * actually returned, not that its later main delivery, native resource or service drained.
     * The transport/selection registry obligations remain with that exact factory throughout.
     */
    public static final class ServiceAccess {
        private final Application application;
        private final com.subhub.app.service.RuntimeCaptureHost.Gateway gateway;
        private final java.util.concurrent.Executor owner;
        private final java.util.function.Consumer<Throwable> failureFence;
        ServiceAccess(Application application, com.subhub.app.service.RuntimeCaptureHost.Gateway gateway,
                java.util.concurrent.Executor owner, java.util.function.Consumer<Throwable> failureFence) {
            this.application=Objects.requireNonNull(application); this.gateway=Objects.requireNonNull(gateway);
            this.owner=Objects.requireNonNull(owner); this.failureFence=Objects.requireNonNull(failureFence);
        }
        public CompletableFuture<Void> accessibilityConnected(com.subhub.app.service.RuntimeCaptureHost.Connection connection,
                com.subhub.app.service.RuntimeCaptureHost.Completion completion) {
            Objects.requireNonNull(connection);Objects.requireNonNull(completion);
            return dispatch(()->gateway.accessibilityConnected(connection,completion));
        }
        public CompletableFuture<Void> claimProjection(com.subhub.app.service.RuntimeCaptureHost.Connection connection,
                String token,com.subhub.app.service.RuntimeCaptureHost.Completion completion) {
            Objects.requireNonNull(connection);Objects.requireNonNull(token);Objects.requireNonNull(completion);
            return dispatch(()->gateway.claimProjection(connection,token,completion));
        }
        public CompletableFuture<Void> disconnected(com.subhub.app.service.RuntimeCaptureHost.Connection connection) {
            Objects.requireNonNull(connection);return dispatch(()->gateway.disconnected(connection));
        }
        public CompletableFuture<Void> rejected(com.subhub.app.service.RuntimeCaptureHost.Connection connection,
                com.subhub.app.service.RuntimeCaptureHost.Request request,com.subhub.app.service.RuntimeCaptureHost.Rejection reason) {
            Objects.requireNonNull(connection);Objects.requireNonNull(request);Objects.requireNonNull(reason);
            return dispatch(()->gateway.rejected(connection,request,reason));
        }
        public CompletableFuture<Void> attachmentDrained(com.subhub.app.service.RuntimeCaptureHost.Connection connection,
                com.subhub.app.service.RuntimeCaptureHost.Selection selection) {
            Objects.requireNonNull(connection);Objects.requireNonNull(selection);
            return dispatch(()->gateway.attachmentDrained(connection,selection));
        }
        private CompletableFuture<Void> dispatch(Runnable action) {
            Ingress ingress=new Ingress(action);
            Throwable failed=null;
            try {owner.execute(ingress::run);}
            catch(RuntimeException|Error uncertain){failed=uncertain;fence(uncertain);}
            ingress.dispatched(failed);return ingress.result;
        }
        private void fence(Throwable failure) {
            // A broken observer must not convert unknown dispatch into an admitted success.
            try {failureFence.accept(failure);}catch(RuntimeException|Error observerFailure){
                if(observerFailure!=failure)try{failure.addSuppressed(observerFailure);}catch(RuntimeException|Error ignored){/* Failure receipt still wins. */}
            }
        }
        private final class Ingress {
            final Runnable action;final CompletableFuture<Void> result=new CompletableFuture<>();
            final java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean();
            boolean returned,dispatched,completed;Throwable actionFailure,dispatchFailure;
            Ingress(Runnable action){this.action=action;result.whenComplete((v,f)->{if(result.isCancelled())fence(new java.util.concurrent.CancellationException("Service ingress response withdrawn"));});}
            void run(){
                if(!entered.compareAndSet(false,true))return;
                Throwable failure=null;
                try {action.run();}catch(RuntimeException|Error failed){failure=failed;fence(failed);}
                synchronized(this){actionFailure=failure;returned=true;}finish();
            }
            void dispatched(Throwable failure){synchronized(this){dispatchFailure=failure;dispatched=true;}finish();}
            void finish(){
                Throwable failure;
                synchronized(this){
                    if(completed||!dispatched||!returned&&dispatchFailure==null)return;
                    completed=true;failure=dispatchFailure!=null?dispatchFailure:actionFailure;
                }
                if(failure==null)result.complete(null);else result.completeExceptionally(failure);
            }
        }
    }
    public CompletableFuture<DeviceStart> deviceStart(DeviceStartSpec specification) {
        return Objects.requireNonNull(backend.deviceStart(Objects.requireNonNull(specification)));
    }
    public CompletableFuture<StartResult> rearmDevice(RearmReason reason,
            RuntimeSessionIdentity.ComponentObservation expected, Runnable admittedLegacyAction) {
        return Objects.requireNonNull(backend.rearmDevice(Objects.requireNonNull(reason),
                Objects.requireNonNull(expected), Objects.requireNonNull(admittedLegacyAction)));
    }
    /**
     * Entry writers call this before entering their existing Runtime lock and hold it through all
     * writes/synchronous effects. Installed mode rejects a missing exact continuation capability;
     * a normal role flag, captured signature or active DEVICE ID alone is insufficient.
     */
    public static DeviceMutationPermit admitDeviceMutation(Context physical, Mutation mutation) {
        Objects.requireNonNull(mutation);
        AppRuntimeGateway gateway = current(physical);
        if (gateway == null) {
            LegacyWork counted = beginLegacyWork(physical);
            return counted::close;
        }
        return Objects.requireNonNull(gateway.backend.admitDeviceMutation(mutation));
    }
    /**
     * Strict new managed lifecycle boundary. A copied identity only constrains comparison; the
     * installed backend must find an already-held exact executable phase/owner continuation.
     * Unlike generic old setters, managed transitions can never use preinstallation legacy work.
     */
    public static DeviceMutationPermit admitManagedDeviceMutation(Context physical,RuntimeLease.Identity expected,
            Mutation mutation,ManagedDevicePhase phase) {
        Objects.requireNonNull(expected);Objects.requireNonNull(mutation);Objects.requireNonNull(phase);
        if(expected.target.kind!=RuntimeTarget.Kind.DEVICE)throw new DeviceAdmissionException("Not a DEVICE lifecycle owner");
        AppRuntimeGateway gateway=current(physical);
        if(gateway==null)throw new DeviceAdmissionException("Managed DEVICE lifecycle requires the installed owner");
        return Objects.requireNonNull(gateway.backend.admitManagedDeviceMutation(expected,mutation,phase));
    }

}
