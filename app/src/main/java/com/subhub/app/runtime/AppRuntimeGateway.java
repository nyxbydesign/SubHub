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
}
