package com.subhub.app.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import com.subhub.app.capture.CustomImagePool;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.android.OwnedCensorArtworkSource;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.diagnostics.CensorLabLog;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.DeviceRuntimeEffects;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit legacy renderer inputs and lifetime, not reservation, attachment or ACTIVE readiness. */
public final class LegacyRenderDependencies implements AutoCloseable {
    public static final class Unavailable extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        Unavailable() { super("Exact legacy renderer admission is unavailable"); }
    }
    final CustomImagePool images;
    final CensorTapTracker taps;
    private final boolean internal, managed;
    private volatile DeviceOwned selectedDevice;
    private volatile DeviceArtwork wantedDeviceArtwork, loadedDeviceArtwork;
    final Callbacks callbacks = new Callbacks();
    private volatile Owned selected;
    private volatile RuntimeConfiguration acknowledged, loaded;
    private volatile boolean artworkWanted;
    private volatile boolean nativeRetiring;
    private volatile long artworkEpoch;
    private final AtomicBoolean renderDemand = new AtomicBoolean();
    private final AtomicBoolean resourceFailure = new AtomicBoolean();
    private static final class Owned {
        final RuntimeCaptureHost.InternalRequest request;
        final OwnedCensorArtworkSource source;
        final RuntimeConfiguration configuration;
        Owned(RuntimeCaptureHost.InternalRequest request, OwnedCensorArtworkSource source, RuntimeConfiguration configuration) {
            this.request = request; this.source = source; this.configuration = configuration;
        }
    }
    private static final class DeviceOwned {
        final RuntimeCaptureHost.DeviceRequest request;
        final DeviceRuntimeEffects.DeviceComponent component;
        final SettingsRepository settings;
        final CustomImageManager source;
        DeviceOwned(RuntimeCaptureHost.DeviceRequest request, DeviceRuntimeEffects.DeviceComponent component,
                SettingsRepository settings, CustomImageManager source) {
            this.request = request; this.component = component; this.settings = settings; this.source = source;
        }
    }
    private static final class DeviceArtwork {
        final DeviceOwned owner;
        final DetectionPreset preset;
        DeviceArtwork(DeviceOwned owner, DetectionPreset preset) { this.owner = owner; this.preset = preset; }
    }
    /** The default DEVICE constructor preserves the old unbound image/settings/shared-tap path. */
    static LegacyRenderDependencies device(Context context) {
        return new LegacyRenderDependencies(new CustomImagePool(context), CensorTapTracker.shared(), null, null);
    }
    /** Caller owns this exclusive executor until managedResourcesDrained; no editor Context. */
    public static LegacyRenderDependencies owned(RuntimeCaptureHost.InternalRequest request, ExecutorService loader) {
        Objects.requireNonNull(request); Objects.requireNonNull(loader);
        Owned state = new Owned(request, new OwnedCensorArtworkSource(request.storage, request.initialConfiguration),
                request.initialConfiguration);
        return new LegacyRenderDependencies(CustomImagePool.owned(loader), request.resources.taps, state, null);
    }
    /**
     * Exact DEVICE attachment only, under its STARTUP effect. All physical inputs are explicitly
     * selected by that service factory; this method has no Context-based data fallback.
     */
    public static LegacyRenderDependencies managedDevice(RuntimeCaptureHost.DeviceRequest request,
            DeviceRuntimeEffects.DeviceComponent component, SettingsRepository settings,
            CustomImageManager source, CensorTapTracker taps, ExecutorService loader) {
        Objects.requireNonNull(request); Objects.requireNonNull(component);
        if (!request.owner().equals(component.owner())) throw new SecurityException("Foreign DEVICE render component");
        DeviceOwned state = new DeviceOwned(request, component, Objects.requireNonNull(settings), Objects.requireNonNull(source));
        return new LegacyRenderDependencies(CustomImagePool.owned(Objects.requireNonNull(loader)),
                Objects.requireNonNull(taps), null, state);
    }
    private LegacyRenderDependencies(CustomImagePool images, CensorTapTracker taps, Owned owned, DeviceOwned device) {
        this.images = images; this.taps = taps; selected = owned; selectedDevice = device;
        internal = owned != null; managed = internal || device != null;
        acknowledged = owned == null ? null : owned.configuration;
        renderDemand.set(managed);
    }
    void requireMain() {
        if (managed && android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("Owned legacy renderer belongs on main");
    }
    void requireHost(Context context) {
        requireMain();
        RuntimeCaptureHost.Request request = internal ? requireOwned().request : requireDevice().request;
        Context expected = request.connection.kind == RuntimeCaptureHost.Kind.PROJECTION
                ? request.connection.projection().service() : request.connection.accessibility().service();
        if (context != expected) throw new SecurityException("Renderer requires the exact real service host");
    }
    /** Construction-only snapshot. Initial accounting ACK already belongs to this exact request. */
    RuntimeConfiguration initialConfiguration() {
        requireMain();
        Owned state = requireOwned();
        if (state.configuration != state.request.initialConfiguration
                || acknowledged != state.configuration)
            throw new IllegalStateException("Owned renderer must install its initial snapshot before adoption");
        return state.configuration;
    }
    SettingsRepository initialDeviceSettings() { requireMain(); return requireDevice().settings; }
    private DeviceOwned requireDevice() {
        DeviceOwned state = selectedDevice;
        if (!managed || internal || state == null || !callbacks.open()) throw new Unavailable();
        if (!state.request.connection.isConnected()) { fail(); throw new Unavailable(); }
        return state;
    }
    boolean internal() { return internal; }
    boolean managed() { return managed; }
    boolean open() { return callbacks.open(); }
    boolean labActive() { return !internal && CensorLabCensorSession.isActive(); }
    void trace(String tag, String message) { if (!internal) CensorLabLog.i(tag, message); }

    /** Main, inside the registered configuration-adoption receiver. Never starts decode before ACK. */
    public void adopt(RuntimeConfiguration configuration) {
        requireMain();
        Owned before = requireOwned(); Objects.requireNonNull(configuration);
        if (!before.request.owner().equals(configuration.owner)
                || configuration.configRevision < before.configuration.configRevision)
            throw new SecurityException("Different/stale legacy render configuration");
        before.source.adopt(configuration);
        acknowledged = null; loaded = null;
        renderDemand.set(true);
        images.invalidate(); // Old artwork cannot paint under newly adopted settings.
        selected = new Owned(before.request, before.source, configuration);
    }
    /** Owner-worker after the real accounting ACK. This is demand, not effect authority. */
    public void acknowledged(RuntimeConfiguration configuration) {
        Owned state = requireOwned();
        if (state.configuration != configuration) throw new SecurityException("Unadopted legacy artwork ACK");
        acknowledged = configuration;
    }
    private Owned requireOwned() {
        Owned state = selected;
        if (!internal || state == null || !callbacks.open()) throw new Unavailable();
        if (!state.request.connection.isConnected()) { fail(); throw new Unavailable(); }
        return state;
    }
    void appearanceChanged(Runnable ready) {
        if (internal) artworkWanted = true;
        else if (managed) {
            DeviceOwned state = requireDevice();
            DeviceArtwork next = new DeviceArtwork(state, state.settings.loadDetectionPreset());
            // Match normal reloadAsync publication fencing immediately, not just its ready signal.
            // The old displayed set stays available until the next admitted decode is ready.
            images.supersedePending();
            wantedDeviceArtwork = next;
        } else images.reloadAsync(ready);
    }
    /**
     * Explicit post-ACK/ACTIVE reconciliation. False means pending/busy; the attachment retains its
     * exact-revision wake and calls again. No first-frame/model-readiness claim is made here.
     */
    public boolean reconcileArtwork(Runnable ready) {
        requireMain();
        if (!internal && managed) return reconcileDeviceArtwork(ready);
        Owned state = requireOwned();
        if (!artworkWanted) return true;
        if (images.cleanupFailed()) {
            fail(); throw new IllegalStateException("Owned artwork cleanup is uncertain");
        }
        if (acknowledged != state.configuration || state.request.runtime.configuration() != state.configuration
                || state.request.runtime.appliedConfigurationRevision() != state.configuration.configRevision) return false;
        if (loaded == state.configuration && !images.needsRetry()) return true;
        if (images.pending()) return false;
        final long epoch = artworkEpoch;
        DetectionPreset preset = SettingsRepository.forPreferences(state.configuration.main).loadDetectionPreset();
        images.loadOwnedAsync(obsolete -> {
            if (!artworkCurrent(state, epoch) || obsolete.getAsBoolean()) return Collections.emptyList();
            java.util.concurrent.atomic.AtomicReference<List<Bitmap>> output = new java.util.concurrent.atomic.AtomicReference<>();
            if (!state.request.runtime.runEffect(() -> {
                if (!artworkCurrent(state, epoch) || obsolete.getAsBoolean()) output.set(Collections.emptyList());
                else output.set(state.source.load(preset.getCustomImageDimension(), preset.getCustomImageCount(),
                        state.configuration.artworkRevision, () -> !artworkCurrent(state, epoch) || obsolete.getAsBoolean()));
            })) throw new CustomImagePool.Deferred();
            return output.get();
        }, () -> {
            if (artworkCurrent(state, epoch)) {
                loaded = state.configuration;
                if (ready != null) ready.run();
            }
        });
        return false;
    }

    private boolean reconcileDeviceArtwork(Runnable ready) {
        DeviceOwned state = requireDevice();
        if (images.cleanupFailed()) { fail(); throw new IllegalStateException("DEVICE artwork cleanup is uncertain"); }
        DeviceArtwork demand = wantedDeviceArtwork;
        if (demand == null || demand == loadedDeviceArtwork && !images.needsRetry()) return true;
        if (images.pending()) return false;
        final long epoch = artworkEpoch;
        images.loadOwnedAsync(obsolete -> {
            if (!deviceArtworkCurrent(demand, epoch) || obsolete.getAsBoolean()) return Collections.emptyList();
            java.util.concurrent.atomic.AtomicReference<List<Bitmap>> output = new java.util.concurrent.atomic.AtomicReference<>();
            if (!state.component.normal(() -> {
                if (!deviceArtworkCurrent(demand, epoch) || obsolete.getAsBoolean()) output.set(Collections.emptyList());
                else output.set(state.source.loadEnabledBitmaps(demand.preset.getCustomImageDimension(), demand.preset.getCustomImageCount()));
            })) throw new CustomImagePool.Deferred();
            return output.get();
        }, () -> {
            if (deviceArtworkCurrent(demand, epoch)) {
                loadedDeviceArtwork = demand;
                if (ready != null) ready.run();
            }
        });
        return false;
    }
    private boolean deviceArtworkCurrent(DeviceArtwork demand, long epoch) {
        if (!demand.owner.request.connection.isConnected()) {
            try { demand.owner.request.execution().requestStop(); }
            finally { seal(); }
            return false;
        }
        return selectedDevice == demand.owner && wantedDeviceArtwork == demand
                && artworkEpoch == epoch && callbacks.open();
    }

    /** Worker consumption, decoder checkpoints and completion all retain the exact connection. */
    private boolean artworkCurrent(Owned state, long epoch) {
        if (!state.request.connection.isConnected()) {
            try { state.request.runtime.requestStop(); }
            finally { seal(); }
            return false;
        }
        return selected == state && artworkEpoch == epoch && callbacks.open()
                && acknowledged == state.configuration
                && state.request.runtime.configuration() == state.configuration
                && state.request.runtime.appliedConfigurationRevision() == state.configuration.configRevision;
    }

    void fail() {
        Owned state = selected; DeviceOwned device = selectedDevice;
        try {
            if (state != null) state.request.runtime.requestStop();
            if (device != null) device.request.execution().requestStop();
        } finally { if (managed) seal(); }
    }
    boolean tryNormal(Runnable effect) {
        Owned observed = selected; DeviceOwned deviceObserved = selectedDevice;
        if (managed && (images.cleanupFailed()
                || observed != null && !observed.request.connection.isConnected()
                || deviceObserved != null && !deviceObserved.request.connection.isConnected())) { fail(); return false; }
        AtomicBoolean ran = new AtomicBoolean();
        try { callbacks.direct(() -> {
            Owned state = selected;
            DeviceOwned device = selectedDevice;
            if (!managed) { effect.run(); ran.set(true); }
            else if (device != null) {
                device.component.normal(() -> {
                    if (selectedDevice == device && callbacks.open() && device.request.connection.isConnected()) {
                        effect.run(); ran.set(true);
                    }
                });
            } else if (state != null && acknowledged == state.configuration
                    && state.request.runtime.configuration() == state.configuration
                    && state.request.runtime.appliedConfigurationRevision() == state.configuration.configRevision) {
                state.request.runtime.runEffect(() -> {
                    // Recheck inside the gate: the owner worker may acknowledge or seal between
                    // the optimistic read above and this nonblocking admission.
                    if (selected == state && callbacks.open() && acknowledged == state.configuration
                            && state.request.connection.isConnected()
                            && state.request.runtime.configuration() == state.configuration
                            && state.request.runtime.appliedConfigurationRevision() == state.configuration.configRevision) {
                        effect.run(); ran.set(true);
                    }
                });
            }
        }); } catch (RuntimeException | Error uncertain) {
            if (managed) cleanupUncertain();
            throw uncertain;
        }
        return ran.get();
    }
    void command(Runnable effect) { requireMain(); if (!tryNormal(effect)) throw new Unavailable(); }
    <T> T value(Supplier<T> effect) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        command(() -> result.set(effect.get())); return result.get();
    }
    /** A refused draw/timing callback is demand for a fresh render, never permission to replay it. */
    void deferRendering() { if (managed && callbacks.open()) renderDemand.set(true); }
    boolean takeRenderDemand() { return renderDemand.getAndSet(false); }
    boolean needsRenderReconciliation() { return renderDemand.get(); }
    /** Immediate fence only; already admitted draw/load/callback work stays counted. */
    public void seal() {
        if (managed) { nativeRetiring = true; images.abandonSubmittedImages(); }
        callbacks.seal(); selected = null; selectedDevice = null;
        acknowledged = null; loaded = null; wantedDeviceArtwork = null; loadedDeviceArtwork = null; renderDemand.set(false);
    }
    boolean nativeRetiring() { return nativeRetiring; }
    void transientArtworkRetirement() {
        if (artworkEpoch == Long.MAX_VALUE) throw new IllegalStateException("Artwork epoch exhausted");
        artworkEpoch++;
        loaded = null; loadedDeviceArtwork = null;
        images.invalidate();
    }
    void cleanupUncertain() { if (managed) { resourceFailure.set(true); fail(); } }
    public boolean cleanupFailed() { return resourceFailure.get() || images.cleanupFailed(); }
    public boolean managedResourcesDrained() {
        return !resourceFailure.get() && callbacks.drained() && images.managedResourcesDrained();
    }
    @Override public void close() { seal(); images.close(); }

    /** Detached callbacks count submission AND actual callback return. No removal/cancel receipts. */
    static final class Callbacks {
        interface Poster<T> { boolean post(Consumer<T> callback); }
        private final Set<Task<?>> pending = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean sealed;
        synchronized boolean open() { return !sealed; }
        synchronized boolean drained() { return sealed && pending.isEmpty(); }
        synchronized void seal() {
            sealed = true;
            for (Task<?> task : pending) { task.target = null; task.key = null; }
        }
        synchronized void cancel(Object key) {
            for (Task<?> task : pending) if (task.key == key) { task.target = null; task.key = null; }
        }
        boolean direct(Runnable action) {
            return dispatch(action, ignored -> action.run(), callback -> { callback.accept(null); return true; });
        }
        <T> boolean dispatch(Object key, Consumer<T> action, Poster<T> poster) {
            Task<T> task;
            synchronized (this) {
                if (sealed) return false;
                task = new Task<>(this, key, action); pending.add(task);
            }
            boolean accepted = true;
            try {
                accepted = poster.post(task::run);
                if (!accepted && task.entered.get())
                    throw new IllegalStateException("Renderer dispatcher refused after actual delivery");
                return accepted;
            }
            finally {
                synchronized (this) {
                    task.submissionReturned = true;
                    if (!accepted && !task.entered.get()) { task.bodyReturned = true; task.target = null; task.key = null; }
                    finish(task);
                }
            }
        }
        private void finish(Task<?> task) {
            if (task.submissionReturned && task.bodyReturned) pending.remove(task);
        }
        private static final class Task<T> {
            final Callbacks book;
            final AtomicBoolean entered = new AtomicBoolean();
            Object key; Consumer<T> target;
            boolean submissionReturned, bodyReturned;
            Task(Callbacks book, Object key, Consumer<T> target) {
                this.book = book; this.key = key; this.target = Objects.requireNonNull(target);
            }
            void run(T value) {
                if (!entered.compareAndSet(false, true)) return;
                Invocation<T> invocation;
                synchronized (book) { invocation = new Invocation<>(target); target = null; key = null; }
                try { invocation.deliver(value); }
                finally {
                    invocation.target = null; // The target call has actually returned/unwound.
                    synchronized (book) { bodyReturned = true; book.finish(this); }
                }
            }
        }
        private static final class Invocation<T> {
            Consumer<T> target;
            Invocation(Consumer<T> target) { this.target = target; }
            void deliver(T value) { if (target != null) target.accept(value); }
        }
    }
}
