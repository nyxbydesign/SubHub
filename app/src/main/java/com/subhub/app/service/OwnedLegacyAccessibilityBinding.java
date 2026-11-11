package com.subhub.app.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import android.view.Display;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeTarget;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Unwired owned display-screenshot resource component for API30+. No Product/Controller API guard
 * is lowered, no capture mode is changed, and no legacy renderer/text/QUALITY feature is replaced.
 * The enclosing registered attachment must provide those features before INTERNAL Start is enabled.
 */
@RequiresApi(Build.VERSION_CODES.R)
public final class OwnedLegacyAccessibilityBinding<P> implements AutoCloseable {
    public static final String COMPONENT = "owned_accessibility_source";
    interface Callback<P> { void success(P pixels); void failure(); }
    interface Endpoint<P> {
        /** Android supplies exactly one terminal callback for each accepted request. */
        void request(Executor delivery, Callback<P> callback);
        void release(P pixels);
    }
    interface DrainScheduler { boolean after(Runnable callback, long delayMillis); }
    private final RuntimeExecution<?> runtime;
    private final RuntimeExecution<?>.Component component;
    private final Endpoint<P> endpoint;
    private final ExecutorService callbacks;
    private final DrainScheduler drainScheduler;
    private final OwnedCaptureLifetime work = new OwnedCaptureLifetime();
    private final AtomicBoolean started = new AtomicBoolean(), closing = new AtomicBoolean();
    private volatile boolean cleanupFailed;
    private volatile Runnable afterDrained;

    /** Owner worker: no capture occurs here; COMPONENT must already be required by the gateway. */
    OwnedLegacyAccessibilityBinding(RuntimeExecution<?> runtime, Endpoint<P> endpoint,
            ExecutorService callbacks, DrainScheduler drainScheduler) {
        this.runtime = Objects.requireNonNull(runtime); this.endpoint = Objects.requireNonNull(endpoint);
        this.callbacks = Objects.requireNonNull(callbacks); this.drainScheduler = Objects.requireNonNull(drainScheduler);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL)
            throw new SecurityException("Owned accessibility source requires INTERNAL execution");
        component = runtime.registerComponent(COMPONENT);
        component.usesNoConfiguration(); // No mutable capture settings; every request retains its exact image.
        component.installShutdown(done -> { afterDrained = done; close(); pollDrain(); });
    }

    /** Real service identity only. No synthetic editor Context or DEVICE dependencies are opened. */
    public static OwnedLegacyAccessibilityBinding<AccessibilityService.ScreenshotResult> forDisplay(
            RuntimeCaptureHost.InternalRequest request, AccessibilityService service,
            ExecutorService ownedCallbacks, Handler main) {
        Objects.requireNonNull(request); Objects.requireNonNull(service); Objects.requireNonNull(main);
        if (Build.VERSION.SDK_INT < 30 || request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY
                || request.plan.sdkInt < 30 || !request.connection.isConnected()
                || request.connection.accessibility().service() != service)
            throw new IllegalArgumentException("Not this exact display-capture connection");
        return new OwnedLegacyAccessibilityBinding<>(request.runtime, new AndroidEndpoint(service),
                ownedCallbacks, main::postDelayed);
    }

    /** Infrastructure receipt only; no screenshot request, model readiness or first-frame claim. */
    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Accessibility source already started");
        if (!runtime.runStartupEffect(() -> { })) { runtime.requestStop(); return; }
        component.readyAsync(result -> { if (result != ActiveRuntimeCoordinator.Result.OK) runtime.requestStop(); });
    }

    /**
     * Invoke on the owned capture worker, never from a blocking main-loop poll. False means no API
     * request was invoked; retry exact-owner demand ordinarily. An API throw is ambiguous and holds
     * the accepted request until a real callback arrives, without automatic replay or timeout release.
     */
    public boolean requestFrame(Consumer<Frame> receiver) {
        Objects.requireNonNull(receiver);
        OwnedCaptureLifetime.Ticket invocation = work.begin();
        if (invocation == null) return false;
        AtomicBoolean invoked = new AtomicBoolean();
        try {
            runtime.runEffect(() -> {
                OwnedCaptureLifetime.Ticket pending = work.begin();
                if (pending == null) return;
                Pending request = new Pending(pending, receiver, runtime.configuration(), runtime.appliedConfigurationRevision());
                invoked.set(true);
                endpoint.request(command -> {
                    try {
                        boolean accepted = work.dispatchContinuation(pending,
                                task -> { callbacks.execute(task); return true; }, command);
                        if (!accepted) throw new IllegalStateException("Native callback was refused");
                    } catch (RuntimeException | Error uncertain) { failCleanup(); throw uncertain; }
                }, request);
            });
            return invoked.get();
        } catch (RuntimeException | Error uncertain) {
            runtime.requestStop(); throw uncertain;
        } finally { invocation.close(); }
    }

    private final class Pending implements Callback<P> {
        final OwnedCaptureLifetime.Ticket pending;
        final Consumer<Frame> receiver;
        final Object configuration;
        final long revision;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private P delivered;
        Pending(OwnedCaptureLifetime.Ticket pending, Consumer<Frame> receiver, Object configuration, long revision) {
            this.pending = pending; this.receiver = receiver; this.configuration = configuration; this.revision = revision;
        }
        @Override public void success(P pixels) {
            if (pixels == null) { failure(); return; }
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) {
                    // Never close a live borrow when a faulty endpoint repeats its exact object.
                    if (delivered != pixels) releaseUnpublished(pixels);
                    return;
                }
                delivered = pixels;
            }
            AtomicBoolean transferred = new AtomicBoolean();
            try {
                runtime.runEffect(() -> {
                    if (!work.open() || configuration != runtime.configuration()
                            || revision != runtime.appliedConfigurationRevision()) return;
                    OwnedCaptureLifetime.Ticket held = work.begin();
                    if (held == null) return;
                    Frame frame = new Frame(pixels, held, configuration, revision);
                    transferred.set(true);
                    try { receiver.accept(frame); }
                    catch (RuntimeException | Error failure) { frame.close(); throw failure; }
                });
            } finally {
                // Failure completion is bookkeeping, not owner effect permission. Deliver it even
                // after revision/Stop fencing so the enclosing capture request cannot hang.
                if (!transferred.get()) {
                    releaseUnpublished(pixels); // Failure deliberately retains the pending obligation.
                    try { receiver.accept(null); }
                    finally { pending.close(); }
                } else pending.close();
                // The separately counted executor continuation still owns the SDK Runnable until
                // actual return; closing this request ticket is not early callback drain.
            }
        }
        @Override public void failure() {
            if (!terminal.compareAndSet(false, true)) return;
            try { receiver.accept(null); }
            finally { pending.close(); }
        }
    }
    private void releaseUnpublished(P pixels) {
        try { endpoint.release(pixels); }
        catch (RuntimeException | Error failure) { failCleanup(); throw failure; }
    }
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        runtime.requestStop();
        work.seal(callbacks::shutdown); // Accepted callback and all transferred frame tickets remain.
    }
    public boolean managedResourcesDrained() {
        return closing.get() && work.drained() && callbacks.isTerminated() && !cleanupFailed;
    }
    private void failCleanup() { cleanupFailed = true; component.cleanupFailed(); }
    private void pollDrain() {
        if (cleanupFailed) return;
        if (managedResourcesDrained()) {
            Runnable done = afterDrained; afterDrained = null;
            if (done != null) done.run();
        } else if (!drainScheduler.after(this::pollDrain, 16)) failCleanup();
    }

    /** Read and dispose the exact ScreenshotResult/HardwareBuffer only through this owned borrow. */
    public final class Frame implements AutoCloseable {
        private final P pixels;
        private final OwnedCaptureLifetime.Ticket held;
        private final Object configuration;
        public final long configurationRevision;
        private int readers;
        private boolean retired, disposalStarted;
        Frame(P pixels, OwnedCaptureLifetime.Ticket held, Object configuration, long revision) {
            this.pixels = pixels; this.held = held; this.configuration = configuration; configurationRevision = revision;
        }
        /** Native wraps/copies must return and dispose temporary wrappers before this scope returns. */
        public boolean read(Consumer<P> read) {
            Objects.requireNonNull(read); AtomicBoolean entered = new AtomicBoolean();
            runtime.runEffect(() -> {
                synchronized (this) {
                    if (retired || !held.current() || configuration != runtime.configuration()
                            || configurationRevision != runtime.appliedConfigurationRevision()) return;
                    readers++; entered.set(true);
                }
                try { read.accept(pixels); }
                finally {
                    boolean dispose;
                    synchronized (this) { readers--; dispose = retireIfUnused(); }
                    if (dispose) dispose();
                }
            });
            return entered.get();
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
                callbacks.execute(() -> {
                    try { endpoint.release(pixels); held.close(); }
                    catch (RuntimeException | Error failure) { failCleanup(); }
                });
            } catch (RuntimeException | Error uncertain) { failCleanup(); }
        }
    }
    private static final class AndroidEndpoint implements Endpoint<AccessibilityService.ScreenshotResult> {
        private final AccessibilityService service;
        AndroidEndpoint(AccessibilityService service) { this.service = service; }
        @Override public void request(Executor executor, Callback<AccessibilityService.ScreenshotResult> callback) {
            if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("Display screenshots require API30");
            if (Looper.myLooper() == Looper.getMainLooper())
                throw new IllegalStateException("Dispatch owned screenshots off main");
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) { callback.success(result); }
                @Override public void onFailure(int errorCode) { callback.failure(); }
            });
        }
        @Override public void release(AccessibilityService.ScreenshotResult pixels) {
            if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("Screenshot buffers require API30");
            pixels.getHardwareBuffer().close();
        }
    }
}
