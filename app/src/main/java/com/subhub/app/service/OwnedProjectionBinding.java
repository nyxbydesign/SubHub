package com.subhub.app.service;

import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.capture.ScreenCaptureManager;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeTarget;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Unwired API26+ owned projection resource component, not an enforcement/renderer replacement.
 * The gateway must already own/consume the exact projection grant and retain its physical lease.
 * The containing attachment owns foreground notification, permission, geometry validity and the
 * producer executor until this component actually drains. No DEVICE store or global state lookup.
 */
public final class OwnedProjectionBinding<P> implements AutoCloseable {
    public static final String COMPONENT = "owned_projection_source";
    interface Endpoint<P> {
        default void foreground() { }
        void start(RuntimeCaptureHost.ProjectionGrant grant, Runnable revoked);
        void paused(boolean paused);
        P acquire();
        void release(P pixels);
        /** Irreversible source acquisition/reissue fence, not a resource-drain receipt. */
        default void seal() { }
        void close();
        boolean drained();
    }
    interface DrainScheduler { boolean after(Runnable callback, long delayMillis); }
    private final RuntimeExecution<?> runtime;
    private final RuntimeExecution<?>.Component component;
    private final Endpoint<P> endpoint;
    private final ExecutorService producer;
    private final DrainScheduler drainScheduler;
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final AtomicBoolean started = new AtomicBoolean(), closing = new AtomicBoolean(), sealed = new AtomicBoolean();
    private volatile boolean cleanupFailed;
    private volatile Runnable afterDrained;

    /** Owner worker, before OS creation; COMPONENT must be in the gateway's required set. */
    OwnedProjectionBinding(RuntimeExecution<?> runtime, Endpoint<P> endpoint,
            ExecutorService producer, DrainScheduler drainScheduler) {
        this.runtime = Objects.requireNonNull(runtime); this.endpoint = Objects.requireNonNull(endpoint);
        this.producer = Objects.requireNonNull(producer);
        this.drainScheduler = Objects.requireNonNull(drainScheduler);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL)
            throw new SecurityException("Owned projection requires INTERNAL execution");
        component = runtime.registerComponent(COMPONENT);
        component.usesNoConfiguration(); // Capture-only: per-frame immutable config identity is fenced below.
        component.installShutdown(done -> {
            afterDrained = done;
            close();
            pollDrain();
        });
    }

    /**
     * Owner-worker preparation only. start consumes this request's exact process-local grant.
     * Capture scaling comes from the immutable owner configuration, never physical settings.
     * This factory does not start capture or acquire another projection registry lease.
     */
    public static OwnedProjectionBinding<Bitmap> prepareProjection(
            RuntimeCaptureHost.InternalRequest request, int width, int height, int densityDpi,
            ExecutorService ownedProducer, Handler main) {
        Objects.requireNonNull(request); Objects.requireNonNull(main);
        if (request.plan.mode != RuntimeCaptureHost.Mode.PROJECTION || request.plan.sdkInt < 26
                || width <= 0 || height <= 0 || densityDpi <= 0
                || request.initialConfiguration != request.runtime.configuration()
                || !request.connection.isConnected())
            throw new IllegalArgumentException("Invalid owned projection plan");
        DetectorConfig policy = SettingsRepository.forPreferences(request.initialConfiguration.main).loadDetectorConfig();
        int resolution = policy.getInferenceResolution(); float scale = policy.getCaptureScale();
        if (resolution <= 0 || !Float.isFinite(scale) || scale <= 0 || scale > 1)
            throw new IllegalArgumentException("Invalid owned projection capture policy");
        return new OwnedProjectionBinding<>(request.runtime, new AndroidEndpoint(request, width,
                height, densityDpi, resolution, scale), ownedProducer, main::postDelayed);
    }

    /** Queue STARTUP resource creation only. It creates a paused display, never reads a frame. */
    public boolean start(RuntimeCaptureHost.ProjectionGrant grant) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Projection source already started");
        if (!runtime.runStartupEffect(endpoint::foreground)) { runtime.requestStop(); return false; }
        return dispatch(() -> {
            if (!work.open()) return;
            if (!runtime.runStartupEffect(() -> endpoint.start(grant, () -> { close(); runtime.requestStop(); }))) {
                runtime.requestStop(); return;
            }
            component.readyAsync(result -> {
                if (result != ActiveRuntimeCoordinator.Result.OK) runtime.requestStop();
            });
        });
    }

    /** NORMAL-only after ACTIVE, including screen transitions; caller retains refused demand. */
    public boolean paused(boolean paused, Consumer<Boolean> admission) {
        Objects.requireNonNull(admission);
        return dispatch(() -> admission.accept(!sealed.get() && work.open()
                && runtime.runEffect(() -> endpoint.paused(paused))));
    }

    /**
     * Called by an owned scheduler after ACTIVE. A null delivery means ordinary unavailability;
     * this class supplies neither a capture timer nor a first-frame/configuration-readiness receipt.
     * The receiver owns a non-null Frame until close, including every accepted native/model job.
     */
    public boolean requestFrame(Consumer<Frame> receiver) {
        Objects.requireNonNull(receiver);
        return dispatch(() -> {
            if (sealed.get() || !work.open()) { receiver.accept(null); return; }
            boolean admitted = runtime.runEffect(() -> {
                if (sealed.get()) { receiver.accept(null); return; }
                P pixels = endpoint.acquire();
                if (pixels == null) { receiver.accept(null); return; }
                OwnedCaptureLifetime.Ticket held = work.begin();
                if (held == null) { endpoint.release(pixels); receiver.accept(null); return; }
                Frame frame = new Frame(pixels, held, runtime.configuration(), runtime.appliedConfigurationRevision());
                try { receiver.accept(frame); }
                catch (RuntimeException | Error failure) { frame.close(); throw failure; }
            });
            if (!admitted) receiver.accept(null);
        });
    }

    private boolean dispatch(Runnable action) {
        try {
            return work.dispatch(task -> { producer.execute(task); return true; }, () -> {
                try { action.run(); }
                catch (RuntimeException | Error failure) { runtime.requestStop(); throw failure; }
            });
        } catch (RuntimeException | Error uncertainDispatch) {
            // Own the accepted-or-unknown callback until its actual return; never replay it.
            runtime.requestStop(); throw uncertainDispatch;
        }
    }

    /** Seal before any renderer releases submitted source frames. No native closure is implied. */
    public void seal() {
        if (!sealed.compareAndSet(false, true)) return;
        try { endpoint.seal(); }
        catch (RuntimeException | Error failure) { failCleanup(); throw failure; }
    }

    /** Immediate admission seal. Previously accepted work/borrowed frames remain owned. */
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        try { seal(); }
        catch (RuntimeException | Error uncertain) { /* Already retained by failCleanup. */ }
        work.seal(() -> {
            try {
                producer.execute(() -> {
                    try { endpoint.close(); }
                    catch (RuntimeException | Error failure) { failCleanup(); }
                    finally { producer.shutdown(); }
                });
            } catch (RuntimeException | Error uncertain) { failCleanup(); }
        });
        runtime.requestStop();
    }
    public boolean managedResourcesDrained() {
        return closing.get() && work.drained() && !cleanupFailed && producer.isTerminated() && endpoint.drained();
    }
    private void failCleanup() { cleanupFailed = true; component.cleanupFailed(); }
    private void pollDrain() {
        if (cleanupFailed) return;
        if (managedResourcesDrained()) {
            Runnable done = afterDrained; afterDrained = null;
            if (done != null) done.run();
        } else if (!drainScheduler.after(this::pollDrain, 16)) failCleanup();
    }

    /** Exact raw/copy borrow. No pixel access is permitted outside read's synchronous scope. */
    public final class Frame implements AutoCloseable {
        private final P pixels;
        private final OwnedCaptureLifetime.Ticket held;
        private final Object configuration;
        public final long configurationRevision;
        private int readers;
        private boolean retired, disposalStarted;
        private Frame(P pixels, OwnedCaptureLifetime.Ticket held, Object configuration, long revision) {
            this.pixels = Objects.requireNonNull(pixels); this.held = held;
            this.configuration = configuration; configurationRevision = revision;
        }
        /** Keep the entire native call inside this synchronous callback; do not retain pixels. */
        public boolean read(Consumer<P> read) {
            Objects.requireNonNull(read);
            AtomicBoolean readEntered = new AtomicBoolean();
            runtime.runEffect(() -> {
                synchronized (this) {
                    if (sealed.get() || retired || !held.current() || configuration != runtime.configuration()
                            || configurationRevision != runtime.appliedConfigurationRevision()) return;
                    readers++;
                    readEntered.set(true);
                }
                try { read.accept(pixels); }
                finally {
                    boolean dispose;
                    synchronized (this) { readers--; dispose = retireIfUnused(); }
                    if (dispose) dispose();
                }
            });
            return readEntered.get();
        }
        /**
         * Short NORMAL capture of exact immutable facts. The returned borrow has no effect
         * authority: native computation uses it outside the gate, publication needs fresh
         * admission. Keep it until actual native/renderer return, never merely enqueue/cancel.
         * A rendered pixel reference may escape use only with this same borrow transferred to
         * that renderer's actual release callback. Existing read() remains synchronous-only.
         */
        public <T> Borrow<T> borrowComputation(java.util.function.Supplier<T> captureFacts) {
            Objects.requireNonNull(captureFacts);
            java.util.concurrent.atomic.AtomicReference<Borrow<T>> result = new java.util.concurrent.atomic.AtomicReference<>();
            runtime.runEffect(() -> {
                synchronized (Frame.this) {
                    if (sealed.get() || retired || !held.current() || configuration != runtime.configuration()
                            || configurationRevision != runtime.appliedConfigurationRevision()) return;
                    T facts = Objects.requireNonNull(captureFacts.get());
                    // A capture callback can synchronously retire its source; recheck before grant.
                    if (sealed.get() || retired || !held.current() || configuration != runtime.configuration()
                            || configurationRevision != runtime.appliedConfigurationRevision()) return;
                    readers++;
                    result.set(new Borrow<>(facts));
                }
            });
            return result.get();
        }
        public final class Borrow<T> implements AutoCloseable {
            public final T facts;
            private int uses;
            private boolean closed, returned;
            private Borrow(T facts) { this.facts = facts; }
            /** Actual accepted computation may finish after Stop; this confers no draw/write authority. */
            public boolean use(Consumer<P> action) {
                Objects.requireNonNull(action);
                synchronized (this) { if (closed) return false; uses++; }
                try { action.accept(pixels); return true; }
                finally {
                    boolean release;
                    synchronized (this) { uses--; release = returnIfUnused(); }
                    if (release) releaseReader();
                }
            }
            @Override public void close() {
                boolean release;
                synchronized (this) { closed = true; release = returnIfUnused(); }
                if (release) releaseReader();
            }
            private boolean returnIfUnused() {
                if (!closed || uses != 0 || returned) return false;
                returned = true; return true;
            }
            private void releaseReader() {
                boolean dispose;
                synchronized (Frame.this) { readers--; dispose = retireIfUnused(); }
                if (dispose) dispose();
            }
        }
        @Override public void close() {
            boolean dispose;
            synchronized (this) { retired = true; dispose = retireIfUnused(); }
            if (dispose) dispose();
        }
        private boolean retireIfUnused() {
            if (!retired || readers != 0 || disposalStarted) return false;
            disposalStarted = true; return true;
        }
        private void dispose() {
            try {
                producer.execute(() -> {
                    try { endpoint.release(pixels); held.close(); }
                    catch (RuntimeException | Error failed) { failCleanup(); }
                });
            } catch (RuntimeException | Error uncertain) { failCleanup(); }
        }
    }

    /**
     * Owner-reference boundary for platform records selected before unregisterCallback. Retirement
     * is atomic with admission; it detaches the target, but never pretends an admitted invocation
     * returned. A late platform record may still enter run(), retaining only this inert relay.
     *
     * Invocation is separate so the target's invocation stack has returned before its last relay
     * reference is cleared and drain is published. Neither a retained platform callback nor its
     * harmless relay epilogue can reach the owner after drained(). No queue sentinel is a proof.
     */
    static final class CallbackRelay implements Runnable {
        private Runnable target;
        private int admitted;
        private boolean retired;
        CallbackRelay(Runnable target) { this.target = Objects.requireNonNull(target); }
        private synchronized Invocation admit() {
            if (retired) return null;
            Invocation invocation = new Invocation(this, target);
            admitted++;
            return invocation;
        }
        synchronized void retire() { retired = true; target = null; }
        synchronized boolean drained() { return retired && admitted == 0; }
        @Override public void run() {
            Invocation invocation = admit();
            if (invocation == null) return;
            try { invocation.deliver(); }
            finally { invocation.returned(); }
        }
        private static final class Invocation {
            private final CallbackRelay relay;
            private Runnable target;
            Invocation(CallbackRelay relay, Runnable target) { this.relay = relay; this.target = target; }
            void deliver() { target.run(); }
            void returned() {
                synchronized (relay) {
                    target = null; // deliver's owner stack returned before this receipt.
                    relay.admitted--;
                }
            }
        }
    }

    /** Static: a retained Android CallbackRecord must never implicitly retain the endpoint. */
    private static final class ProjectionCallback extends MediaProjection.Callback {
        private final CallbackRelay relay;
        ProjectionCallback(Runnable revoked) { relay = new CallbackRelay(revoked); }
        @Override public void onStop() { relay.run(); }
        void retire() { relay.retire(); }
        boolean drained() { return relay.drained(); }
    }

    private static final class AndroidEndpoint implements Endpoint<Bitmap> {
        private final RuntimeCaptureHost.InternalRequest request;
        private MediaProjection projection;
        private final int width, height, density, resolution;
        private final float scale;
        private volatile ScreenCaptureManager source;
        private volatile boolean sourceSealed;
        private ProjectionCallback callback;
        private boolean closeStarted; // Producer-confined, like the owned OS resources.
        private volatile boolean resourcesClosed, startupUncertain;
        AndroidEndpoint(RuntimeCaptureHost.InternalRequest request, int width, int height, int density,
                int resolution, float scale) {
            this.request = request;
            this.width = width; this.height = height; this.density = density;
            this.resolution = resolution; this.scale = scale;
        }
        @Override public void foreground() {
            if (Looper.myLooper() != Looper.getMainLooper())
                throw new IllegalStateException("Projection foreground setup belongs on main");
            request.connection.projection().showNeutralForegroundNotification();
        }
        @Override public void start(RuntimeCaptureHost.ProjectionGrant grant, Runnable revoked) {
            // Consumption is exact-request and single-use. A refused grant created no OS resource.
            Intent permission = Objects.requireNonNull(grant).consume(request, request.connection);
            Service host = request.connection.projection().service();
            MediaProjectionManager manager = (MediaProjectionManager) host.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                projection = Objects.requireNonNull(manager).getMediaProjection(Activity.RESULT_OK, permission);
                if (projection == null) throw new IllegalStateException("Approved projection unavailable");
                callback = new ProjectionCallback(revoked);
                // CallbackRecord also retains its Handler. Do not give it a supplied Handler
                // subclass/Callback that could retain the service or owner after relay retirement.
                projection.registerCallback(callback, new Handler(Looper.getMainLooper()));
                source = new ScreenCaptureManager(projection, width, height, density, resolution, scale);
                if (sourceSealed) source.sealSubmittedFrames();
                source.start(true); // Preserve API26+ manager; leave output detached until NORMAL activation.
            } catch (RuntimeException | Error failure) { startupUncertain = true; throw failure; }
        }
        @Override public void paused(boolean paused) {
            try { source.pauseSurface(paused); }
            catch (RuntimeException | Error failure) { startupUncertain = true; throw failure; }
        }
        @Override public Bitmap acquire() {
            try { return source.acquireLatestFrame(); }
            catch (RuntimeException | Error failure) { startupUncertain = true; throw failure; }
        }
        @Override public void release(Bitmap pixels) { source.releaseFrame(pixels); }
        @Override public void seal() {
            sourceSealed = true;
            ScreenCaptureManager selected = source;
            if (selected != null) selected.sealSubmittedFrames();
        }
        @Override public void close() {
            // Never replay a native close with an uncertain outcome. The binding invokes this on
            // its sole producer only, after accepted capture jobs and frame borrows have returned.
            if (closeStarted) {
                if (!resourcesClosed) throw new IllegalStateException("Projection resource closure uncertain");
                return;
            }
            closeStarted = true;
            Throwable failure = null;
            try { seal(); } catch (RuntimeException | Error error) { failure = error; }
            // Retire before any fallible OS operation. Already admitted owner callbacks remain
            // counted; records not yet admitted lose all paths to this endpoint/binding/runtime.
            if (callback != null) callback.retire();
            try { if (source != null) source.close(); }
            catch (RuntimeException | Error error) { if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error); }
            try { if (callback != null) projection.unregisterCallback(callback); }
            catch (RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (projection != null) projection.stop(); }
            catch (RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            if (failure != null || startupUncertain)
                throw new IllegalStateException("Projection resource closure uncertain", failure);
            resourcesClosed = true;
        }
        @Override public boolean drained() {
            return resourcesClosed && (source == null || source.managedResourcesDrained())
                    && (callback == null || callback.drained());
        }
    }
}
