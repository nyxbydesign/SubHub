package com.subhub.app.service;

import android.app.Activity;
import android.app.Service;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.RectF;

import com.subhub.app.detection.OwnerDetectorProviderCache;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.DeviceRuntimeEffects;
import com.subhub.app.runtime.AndroidDeviceStatisticsSource;
import com.subhub.app.runtime.RuntimeEnvironment;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeTarget;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inactive service/gateway contract. This class neither reserves a runtime nor starts a service.
 * The sole gateway reserves BEFORE any permission prompt, prepares a registered attachment on its
 * owner worker and delivers it to the exact still-connected OS shell. A connection, plan, identity
 * or Intent token is routing metadata, never permission or effect authority. Existing DEVICE entry
 * points have not yet been migrated to this contract; INTERNAL Start remains unavailable.
 */
public final class RuntimeCaptureHost {
    private RuntimeCaptureHost() { }
    public enum Kind { PROJECTION, ACCESSIBILITY }
    public enum Mode { PROJECTION, ACCESSIBILITY_DISPLAY, ACCESSIBILITY_WINDOW, ACCESSIBILITY_MODULES }
    public enum Rejection {
        NO_RESERVATION, STALE_CONNECTION, ALREADY_CLAIMED, CONFLICT, PERMISSION_DENIED,
        UNSUPPORTED, DISPATCH_UNCERTAIN, ATTACHMENT_FAILED, CLEANUP_UNCERTAIN
    }

    /** Selected capture mode, never a fallback decision or a mutable preference source. */
    public static final class CapturePlan {
        public final Mode mode;
        public final int sdkInt;
        public CapturePlan(Mode mode, int sdkInt) {
            this.mode = Objects.requireNonNull(mode); this.sdkInt = sdkInt;
            if (sdkInt < 26 || mode == Mode.ACCESSIBILITY_DISPLAY && sdkInt < 30
                    || mode == Mode.ACCESSIBILITY_WINDOW && sdkInt < 34)
                throw new IllegalArgumentException("Unsupported capture plan");
        }
        public Kind kind() { return mode == Mode.PROJECTION ? Kind.PROJECTION : Kind.ACCESSIBILITY; }
        /** Modules require a real Accessibility host on API26+, but provide no screenshot readiness. */
        public boolean capturesPixels() { return mode != Mode.ACCESSIBILITY_MODULES; }
    }

    /** Real framework Service only. Never pass an editor Context or an owner-data Context wrapper. */
    public interface ProjectionHost {
        Service service();
        /** Must run before projection acquisition; no DEVICE privacy/stop-policy/data constructors. */
        void showNeutralForegroundNotification();
        /** Called only after this exact attachment drained, never to stop a newer service start. */
        void stopAfterDrain();
    }
    public interface AccessibilityHost {
        AccessibilityService service();
    }

    /** One actual service connection, retained through any outstanding delivery/cleanup. */
    public static final class Connection {
        private static final AtomicLong NEXT = new AtomicLong();
        public final long generation;
        public final Kind kind;
        private final Object host;
        private boolean connected = true;
        private Selection selected;
        private Connection(Kind kind, Object host) {
            this.kind = Objects.requireNonNull(kind); this.host = Objects.requireNonNull(host);
            generation = NEXT.incrementAndGet();
            if (generation <= 0) throw new IllegalStateException("Connection generation exhausted");
        }
        // Only service-package hosts mint actual connections. No identity/string reconstruction.
        static Connection projection(ProjectionHost host) { return new Connection(Kind.PROJECTION, host); }
        static Connection accessibility(AccessibilityHost host) { return new Connection(Kind.ACCESSIBILITY, host); }
        public synchronized boolean isConnected() { return connected; }
        synchronized void disconnected() { connected = false; }
        public synchronized boolean occupied() { return selected != null; }
        private synchronized boolean claim(Selection candidate) {
            Request request = candidate.request;
            if (!connected || selected != null || request.connection != this
                    || !request.claimed.compareAndSet(false, true)) return false;
            selected = candidate;
            return true;
        }
        private synchronized boolean consume(ProjectionGrant grant, Request expected) {
            return connected && selected != null && selected.request == expected
                    && selected.projection == grant && grant.request == expected
                    && grant.consumed.compareAndSet(false, true);
        }
        /** Service receives the exact closure receipt, then verifies actual drain before retiring. */
        boolean releaseAfterDrain(Selection candidate) {
            Objects.requireNonNull(candidate);
            // Do not invoke external resource code under the connection monitor.
            if (!candidate.attachment.managedResourcesDrained()) return false;
            synchronized (this) {
                if (selected != candidate) return false;
                selected = null;
                return true;
            }
        }
        public ProjectionHost projection() {
            if (kind != Kind.PROJECTION) throw new IllegalStateException("Not a projection host");
            return (ProjectionHost) host;
        }
        public AccessibilityHost accessibility() {
            if (kind != Kind.ACCESSIBILITY) throw new IllegalStateException("Not an accessibility host");
            return (AccessibilityHost) host;
        }
    }

    /**
     * Called on the gateway's serial owner worker. The gateway owns token lookup/consumption,
     * durable reserve/STARTING, queue admission, module registration and recovery. An unknown token
     * MUST reject; it must not use any DEVICE factory. No process-death reconstruction is allowed.
     * All results are posted to main with their exact Connection and Request still retained.
     */
    public interface Gateway {
        void accessibilityConnected(Connection connection, Completion completion);
        void claimProjection(Connection connection, String opaqueToken, Completion completion);
        void disconnected(Connection connection);
        /** Rejection initiates exact cleanup. It is not a drain receipt or permission to free owner. */
        void rejected(Connection connection, Request request, Rejection reason);
        /** Verify exact attachment + actual drain on owner worker before releasing the host slot. */
        void attachmentDrained(Connection connection, Selection selection);
    }
    public interface Completion {
        /** The caller retains an accepted/uncertain dispatch until this delivery actually returns. */
        void selected(Selection selection);
        void rejected(Rejection reason);
    }

    /**
     * Prepared on the owner worker BEFORE posting main creation. Required component registration,
     * shutdown and configuration receiver must already be installed, even if attach later throws.
     * Returned callbacks/identity do not replace the runtime's STARTUP/NORMAL effect admission.
     */
    public interface Attachment {
        Request request();
        /** Main only. Retain partial native/window creation on this object if it fails. */
        void attach(ProjectionGrant projection);
        /**
         * Main continuation after the owner worker acknowledged ACTIVE. STARTUP construction and
         * readyAsync are not normal-capture permission. Retain one exact-owner/revision wake when
         * ordinary admission is busy; seal cancels demand without inventing accepted-work drain.
         * Configuration adoption/fencing remains separate from model or first-frame readiness.
         */
        void activationAcknowledged();
        /** Immediate, nonblocking read/draw/command fence. Does not free resources or occupancy. */
        void seal();
        /**
         * Nonblocking initiation. Receipt follows real accepted callbacks/native work/raw borrowers,
         * windows/cache/accounting teardown AND attach's actual return. Cancel/timeout is not proof.
         * Cleanup failure retains ownership and never calls the receipt. Safe to call after a partial
         * attach failure. The gateway may not install a successor until this receipt is verified.
         */
        void beginClose(Runnable afterActualDrain);
        /** Cached observation only, no journal IO, waiting or inference from a global running flag. */
        boolean physicalOccupancy();
        boolean managedResourcesDrained();
    }
    public interface DeviceFactory { Attachment prepare(DeviceRequest request); }
    public interface InternalFactory { Attachment prepare(InternalRequest request); }

    public abstract static class Request {
        public final Connection connection;
        public final CapturePlan plan;
        private final RuntimeExecution<?> execution;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean preparationStarted = new AtomicBoolean();
        private Request(Connection connection, CapturePlan plan, RuntimeExecution<?> execution) {
            this.connection = Objects.requireNonNull(connection); this.plan = Objects.requireNonNull(plan);
            this.execution = Objects.requireNonNull(execution);
            if (connection.kind != plan.kind()) throw new IllegalArgumentException("Capture host/mode mismatch");
        }
        public final RuntimeLease.Identity owner() { return execution.identity(); }
        public final RuntimeExecution<?> execution() { return execution; }
        public final Attachment prepare() {
            if (!preparationStarted.compareAndSet(false, true))
                throw new IllegalStateException("Capture attachment preparation is single-use");
            return requireAttachment(this, prepareAttachment());
        }
        protected abstract Attachment prepareAttachment();
    }

    /** Existing DEVICE dependencies remain behind this factory and never enter InternalRequest. */
    public static final class DeviceRequest extends Request {
        public final DeviceRuntimeEffects effects;
        private final AndroidDeviceStatisticsSource statistics;
        private final DeviceFactory dependencies;
        /**
         * Source-less transport/renderer contract only. It cannot supply physical statistics and
         * the concrete runtime factory refuses to deliver it as a managed DEVICE attachment.
         */
        public DeviceRequest(Connection connection, CapturePlan plan, RuntimeExecution<?> execution,
                DeviceRuntimeEffects effects, DeviceFactory dependencies) {
            super(connection, plan, execution); this.dependencies = Objects.requireNonNull(dependencies);
            this.effects = Objects.requireNonNull(effects);
            statistics = null;
            if (owner().target.kind != RuntimeTarget.Kind.DEVICE || !owner().equals(effects.owner()))
                throw new SecurityException("DEVICE attachment requires exact DEVICE execution");
        }
        public DeviceRequest(Connection connection, CapturePlan plan, RuntimeExecution<?> execution,
                DeviceRuntimeEffects effects, AndroidDeviceStatisticsSource statistics, DeviceFactory dependencies) {
            super(connection, plan, execution); this.dependencies = Objects.requireNonNull(dependencies);
            this.effects = Objects.requireNonNull(effects); this.statistics = Objects.requireNonNull(statistics);
            if (owner().target.kind != RuntimeTarget.Kind.DEVICE || !owner().equals(effects.owner()))
                throw new SecurityException("DEVICE attachment requires exact DEVICE execution");
            statistics.requireOwner(owner());
        }
        /** No lookup or fallback to the device-global current stamp. */
        public AndroidDeviceStatisticsSource requireStatisticsSource() {
            if (statistics == null) throw new SecurityException("DEVICE statistics source was not supplied");
            return statistics;
        }
        @Override protected Attachment prepareAttachment() { return dependencies.prepare(this); }
    }

    /** Typed owner-local resources. No physical store, shared taps, telemetry or financial sink. */
    public static final class InternalResources {
        public final RuntimeLease.Identity owner;
        public final OwnerDetectorProviderCache providers;
        public final CensorTapTracker taps;
        public final ScopedDetectionHints<RectF> popupHints;
        public InternalResources(RuntimeLease.Identity owner) {
            this.owner = Objects.requireNonNull(owner);
            providers = new OwnerDetectorProviderCache(owner);
            taps = new CensorTapTracker();
            popupHints = new ScopedDetectionHints<>();
        }
        // Deliberately no generic close(): native/model consumers must actually drain first.
    }

    public static final class InternalRequest extends Request {
        public final RuntimeEnvironment runtime;
        public final InternalOwnerDataBroker.Session data;
        public final InternalStorageScope storage;
        public final RuntimeConfiguration initialConfiguration;
        public final InternalResources resources;
        private final InternalFactory factory;
        public InternalRequest(Connection connection, CapturePlan plan, RuntimeEnvironment runtime,
                InternalOwnerDataBroker.Session data, InternalStorageScope storage,
                InternalResources resources, InternalFactory factory) {
            super(connection, plan, runtime);
            this.runtime = runtime; this.data = Objects.requireNonNull(data);
            this.storage = Objects.requireNonNull(storage); this.resources = Objects.requireNonNull(resources);
            this.factory = Objects.requireNonNull(factory); initialConfiguration = runtime.configuration();
            if (owner().target.kind != RuntimeTarget.Kind.INTERNAL || !owner().equals(data.identity())
                    || !owner().equals(resources.owner) || !owner().target.id.equals(storage.targetId())
                    || !owner().equals(initialConfiguration.owner)
                    || initialConfiguration.configRevision != runtime.appliedConfigurationRevision())
                throw new SecurityException("INTERNAL attachment dependencies belong to another owner/revision");
            resources.providers.forOwner(owner()); // Validate without opening any physical cache.
        }
        @Override protected Attachment prepareAttachment() { return factory.prepare(this); }
    }

    private static Attachment requireAttachment(Request request, Attachment attachment) {
        if (attachment == null || attachment.request() != request)
            throw new SecurityException("Attachment did not retain its exact request");
        return attachment;
    }

    /**
     * One process-local permission result, bound to the reserved request before service delivery.
     * Only the gateway constructs this after validating that request's permission callback. It never
     * accepts data parsed from a service Intent as permission authority. No Serializable/Parcelable.
     */
    public static final class ProjectionGrant {
        private final Request request;
        private final Intent resultData;
        private final AtomicBoolean consumed = new AtomicBoolean();
        public ProjectionGrant(Request request, int resultCode, Intent resultData) {
            this.request = Objects.requireNonNull(request);
            if (request.plan.mode != Mode.PROJECTION || resultCode != Activity.RESULT_OK)
                throw new IllegalArgumentException("Projection was not approved for this request");
            this.resultData = new Intent(Objects.requireNonNull(resultData));
        }
        /** Service-package consumption after exact delivery claim and neutral foreground startup. */
        Intent consume(Request expected, Connection observed) {
            if (expected != request || observed != request.connection || !observed.consume(this, expected))
                throw new SecurityException("Stale, foreign or reused projection grant");
            return new Intent(resultData);
        }
    }

    /** One prepared result retained even when attach/main dispatch throws after partial execution. */
    public static final class Selection {
        public final Request request;
        public final Attachment attachment;
        public final ProjectionGrant projection;
        public Selection(Request request, Attachment attachment, ProjectionGrant projection) {
            this.request = Objects.requireNonNull(request);
            this.attachment = requireAttachment(request, attachment); this.projection = projection;
            if ((request.plan.mode == Mode.PROJECTION) != (projection != null)
                    || projection != null && projection.request != request)
                throw new SecurityException("Projection grant does not match exact request");
        }
        /** Routing claim only; factories and OS operations still need exact runtime effect scopes. */
        boolean claim(Connection observed) {
            return observed == request.connection && observed.claim(this);
        }
    }
}
