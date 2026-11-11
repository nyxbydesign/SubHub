package com.subhub.app.popup;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeTarget;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Inactive integration seam for the admitted INTERNAL runtime. It cannot reserve/start a session,
 * resolve a current owner, impersonate LOCAL, or borrow the DEVICE singleton. Only the future
 * shared gateway may prepare this with its real RuntimeExecution and activate after all receipts.
 */
public final class PopupRuntimeBinding {
    public interface Counters {
        /**
         * Admit exactly one delta to the exact-owner writer before returning. That writer retains
         * its own registered component/permit through disk acknowledgment. Throw on enqueue failure;
         * do not drop a busy write or retry an already accepted delta. No remote/financial effects.
         */
        void popupImpression(RuntimeLease.Identity owner);
    }

    private final Context physicalHost;
    private final RuntimeExecution<RuntimeConfiguration> runtime;
    private final RuntimeExecution<RuntimeConfiguration>.Component component;
    private final InternalStorageScope storage;
    private final Counters counters;
    private final Executor ownerWorker, artworkWorker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PopupWorkScope work = new PopupWorkScope();
    private volatile RuntimeConfiguration configuration;
    private volatile PopupArtworkManifest artworkManifest;
    private PopupStormManager manager;
    private ScopedDetectionHints<RectF>.Binding hints;
    private boolean insideEffect;

    /**
     * Owner worker only. Register before posting initialization or any image I/O. Executors must
     * execute off main; ownerWorker is the same serial owner journal/accounting lifecycle executor.
     * This method does not start a session or show a window, even with a saved owner record.
     */
    public static PopupRuntimeBinding prepare(Context physicalHost,
            RuntimeExecution<RuntimeConfiguration> runtime, String componentName,
            InternalStorageScope storage, Counters counters, Executor ownerWorker, Executor artworkWorker) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Popup registration requires the owner worker");
        return new PopupRuntimeBinding(physicalHost, runtime, componentName, storage,
                counters, ownerWorker, artworkWorker);
    }

    private PopupRuntimeBinding(Context physicalHost, RuntimeExecution<RuntimeConfiguration> runtime,
            String componentName, InternalStorageScope storage, Counters counters,
            Executor ownerWorker, Executor artworkWorker) {
        this.physicalHost = Objects.requireNonNull(physicalHost).getApplicationContext();
        this.runtime = Objects.requireNonNull(runtime);
        this.storage = Objects.requireNonNull(storage);
        this.counters = Objects.requireNonNull(counters);
        this.ownerWorker = Objects.requireNonNull(ownerWorker);
        this.artworkWorker = Objects.requireNonNull(artworkWorker);
        configuration = runtime.configuration();
        requireOwner(configuration);
        artworkManifest = new PopupArtworkManifest(configuration.popupAssets, configuration.disabledAssets);
        if (configuration.configRevision != runtime.appliedConfigurationRevision())
            throw new SecurityException("Popup configuration is not the applied runtime revision");
        component = runtime.registerComponent(componentName);
        component.installShutdown(this::close);
        component.installConfigurationReceiver(this::applyConfiguration);
        initializeAsync();
    }

    private void requireOwner(RuntimeConfiguration snapshot) {
        if (!runtime.identity().equals(Objects.requireNonNull(snapshot).owner)
                || snapshot.owner.target.kind != RuntimeTarget.Kind.INTERNAL
                || !snapshot.owner.target.id.equals(storage.targetId()))
            throw new SecurityException("Popup configuration/storage belongs to another runtime owner");
    }

    private void initializeAsync() {
        PopupWorkScope.Ticket ticket = work.begin();
        if (ticket == null) return;
        Runnable initialize = new Runnable() {
            @Override public void run() {
                if (!ticket.isCurrent()) { ticket.close(); return; }
                try {
                    if (!runtime.runStartupEffect(() -> manager = PopupStormManager.forInternal(
                            physicalHost, PopupStormSettings.forPreferences(configuration.main), effects))) {
                        retry(this, ticket);
                        return;
                    }
                    loadArtwork(artworkManifest);
                    PopupWorkScope.Ticket readiness = work.begin();
                    if (readiness != null) component.readyAsync(result -> {
                        try { if (result != ActiveRuntimeCoordinator.Result.OK) component.failed(); }
                        finally { readiness.close(); }
                    });
                    ticket.close();
                } catch (RuntimeException failure) {
                    ticket.close();
                    component.failed();
                }
            }
        };
        if (!main.post(initialize)) { ticket.close(); component.failed(); }
    }

    /** Main-thread gateway callback after activation; false means busy/revoked, never success. */
    public boolean activate() {
        requireMain();
        return manager != null && work.isOpen() && admitted(manager::activateInternal);
    }

    /** Exact capture endpoint only. No source lookup through global/shared DEVICE state. */
    public boolean bindDetectionHints(Supplier<List<RectF>> source) {
        requireMain();
        Objects.requireNonNull(source);
        if (manager == null || !work.isOpen()) return false;
        return admitted(() -> {
            if (hints != null) hints.close();
            hints = manager.bindDetectionHints(source);
        });
    }

    private void applyConfiguration(RuntimeConfiguration snapshot) {
        requireMain();
        requireOwner(snapshot);
        if (!work.isOpen() || manager == null)
            throw new IllegalStateException("Popup configuration receiver is not ready");
        if (snapshot.configRevision < configuration.configRevision)
            throw new SecurityException("Popup configuration revision went backwards");
        PopupArtworkManifest nextImages = new PopupArtworkManifest(snapshot.popupAssets, snapshot.disabledAssets);
        boolean artworkChanged = !artworkManifest.enabled.equals(nextImages.enabled);
        configuration = snapshot;
        // Content hashes are immutable. Unchanged artwork keeps its pending scan through scalar
        // revisions; changing away and back creates a new token and cannot revive an old callback.
        if (artworkChanged) artworkManifest = nextImages;
        manager.applyInternalSettings(PopupStormSettings.forPreferences(snapshot.main), artworkChanged);
        if (artworkChanged) loadArtwork(nextImages);
    }

    private void loadArtwork(PopupArtworkManifest manifest) {
        PopupWorkScope.Ticket ticket = work.begin();
        if (ticket == null) return;
        try {
            artworkWorker.execute(() -> {
                OwnedPopupArtwork decoded;
                try {
                    if (Looper.myLooper() == Looper.getMainLooper())
                        throw new IllegalStateException("Popup image work cannot run on main");
                    if (!current(ticket, manifest)) { ticket.close(); return; }
                    decoded = OwnedPopupArtwork.load(physicalHost, storage, manifest,
                            () -> current(ticket, manifest));
                } catch (Exception failure) {
                    ticket.close();
                    // Failure of an obsolete, already retired scan cannot stop the newer revision.
                    if (work.isOpen() && artworkManifest == manifest) component.failed();
                    return;
                }
                // The same ticket spans worker completion AND this late main callback/retries.
                Runnable publish = new Runnable() {
                    @Override public void run() {
                        if (!current(ticket, manifest)) { discard(decoded, ticket); return; }
                        try {
                            if (!admitted(() -> manager.applyInternalArtwork(decoded))) {
                                retry(this, ticket, decoded);
                                return;
                            }
                            ticket.close(); // Ownership of decoded now resides in the window manager.
                        } catch (RuntimeException failure) {
                            // Keep attached snapshots for real cleanup; dispose only unadopted data.
                            if (!manager.ownsInternalArtwork(decoded)) discard(decoded, ticket);
                            else ticket.close();
                            component.failed();
                        }
                    }
                };
                if (!main.post(publish)) {
                    discard(decoded, ticket); component.failed();
                }
            });
        } catch (RuntimeException rejected) {
            ticket.close();
            component.failed();
        }
    }

    private boolean current(PopupWorkScope.Ticket ticket, PopupArtworkManifest manifest) {
        return ticket.isCurrent() && artworkManifest == manifest;
    }

    private final PopupStormManager.InternalEffects effects = new PopupStormManager.InternalEffects() {
        @Override public boolean run(Runnable effect) {
            try { return admitted(effect); }
            catch (RuntimeException failed) { component.failed(); return false; }
        }
        @Override public void post(Runnable callback) { postEffect(callback); }
        @Override public void recordImpression() {
            if (!insideEffect) throw new SecurityException("Unadmitted popup accounting");
            counters.popupImpression(runtime.identity());
        }
        @Override public void requestStop() { runtime.requestStop(); }
    };

    private boolean admitted(Runnable effect) {
        if (!work.isOpen()) return false;
        return runtime.runEffect(() -> {
            boolean previous = insideEffect;
            insideEffect = true;
            try { effect.run(); }
            finally { insideEffect = previous; }
        });
    }

    private void postEffect(Runnable effect) {
        PopupWorkScope.Ticket ticket = work.begin();
        if (ticket == null) return;
        Runnable callback = new Runnable() {
            @Override public void run() {
                if (!ticket.isCurrent()) { ticket.close(); return; }
                try {
                    if (!admitted(effect)) { retry(this, ticket); return; }
                    ticket.close();
                } catch (RuntimeException failed) { ticket.close(); component.failed(); }
            }
        };
        if (!main.post(callback)) { ticket.close(); component.failed(); }
    }

    private void retry(Runnable callback, PopupWorkScope.Ticket ticket) { retry(callback, ticket, null); }
    private void retry(Runnable callback, PopupWorkScope.Ticket ticket, OwnedPopupArtwork unowned) {
        if (!main.postDelayed(callback, 16L)) {
            if (unowned != null) discard(unowned, ticket);
            else ticket.close();
            component.failed();
        }
    }

    private void discard(OwnedPopupArtwork artwork, PopupWorkScope.Ticket ticket) {
        try { artwork.close(); }
        catch (RuntimeException failure) {
            work.failed();
            component.cleanupFailed();
        } finally { ticket.close(); }
    }

    private void close(Runnable afterDrained) {
        work.close(() -> {
            try {
                ownerWorker.execute(() -> {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        component.cleanupFailed();
                        return;
                    }
                    try {
                        if (manager != null) manager.releaseInternalSlot();
                        afterDrained.run(); // Registration closure/journal work is never done on main.
                    } catch (RuntimeException failure) { component.cleanupFailed(); }
                });
            } catch (RuntimeException rejected) { component.cleanupFailed(); }
        });
        Runnable detach = () -> {
            try {
                if (hints != null) { hints.close(); hints = null; }
                if (manager != null) {
                    manager.closeInternal();
                    if (!manager.internalResourcesDrained())
                        throw new IllegalStateException("Popup windows have not drained");
                }
                work.windowsClosed();
            } catch (RuntimeException failure) {
                work.failed();
                component.cleanupFailed();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
        else if (!main.post(detach)) { work.failed(); component.cleanupFailed(); }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Popup window callback requires main");
    }
}
