package com.subhub.app.popup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.stats.StatsRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Safe, bounded reconstruction of the licensed multi-window popup renderer. */
public final class PopupStormManager {
    private static final String TAG = "PopupStorm";
    private static final int WINDOW_SLOP = 6;
    private static final int STOP_MARGIN = 16;
    public static final long PREVIEW_DURATION_MS = 10_000L;
    public enum PreviewResult { STARTED, ALREADY_RUNNING, UNAVAILABLE }
    private static final Object PHYSICAL = new Object();
    private static volatile PopupStormManager internalOwner;
    private static volatile PopupStormManager managedDeviceOwner;
    private static final PopupStormManager INSTANCE = new PopupStormManager();
    /** Only the admitted binding can create an INTERNAL instance; DEVICE keeps the singleton. */
    interface InternalEffects {
        boolean run(Runnable effect);
        void post(Runnable callback);
        void recordImpression();
        void requestStop();
    }
    private final InternalEffects internalEffects;
    private com.subhub.app.service.RuntimeCaptureHost.DeviceRequest deviceRequest;
    private com.subhub.app.runtime.DeviceRuntimeEffects.DeviceComponent deviceComponent;
    private final PopupWorkScope deviceWork = new PopupWorkScope();
    private final List<ServiceParticipation> deviceParticipants = new ArrayList<>();
    private final com.subhub.app.overlay.OwnedWindowRetirement deviceWindows =
            new com.subhub.app.overlay.OwnedWindowRetirement();
    private final List<View> deviceRetiringViews = new ArrayList<>();
    private PopupWorkScope.Ticket deviceFrameTicket;
    private boolean deviceWorkSealed, deviceCloseStarted;
    private boolean removingFromFreshMainQueue;

    private OwnedPopupArtwork ownedArtwork;
    private boolean internalClosed;
    private boolean internalEligible;
    private boolean framePosted;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PopupRunOwnership ownership = new PopupRunOwnership();
    private Runnable previewTimeout;
    private final AtomicLong ids = new AtomicLong();
    private final CopyOnWriteArrayList<LivePopup> popups = new CopyOnWriteArrayList<>();
    private final Random random = new Random();
    private volatile List<RectF> detections = List.of();
    private final ScopedDetectionHints<RectF> detectionHints = new ScopedDetectionHints<>();
    private Context context;
    private PopupBitmapCache bitmapCache;
    private PopupLibrary library;
    private volatile PopupStormSettings settings;
    private WindowManager windowManager;
    private TextView stopControl;
    private volatile boolean running;
    private volatile boolean startRequested;
    private volatile boolean physicalWindowsOutstanding;
    private boolean deviceRemovalUncertain;
    private long lastTick;
    private long lastSpawn;
    private long burstStarted;
    private long burstUntil;
    private int screenWidth;
    private int screenHeight;
    private int reservedTop;
    private volatile String scheduledLibrarySignature = "";

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            framePosted = false;
            PopupWorkScope.Ticket delivered = deviceFrameTicket;
            deviceFrameTicket = null;
            try {
            if (deviceRequest != null && !deviceAllowsEffects()) return;
            if (!running || internalClosed || internalEffects != null && !internalEligible) return;
            if (deviceRequest != null) deviceComponent.normal(PopupStormManager.this::tick);
            else if (internalEffects == null) tick();
            else internalEffects.run(PopupStormManager.this::tick);
            if (running && !internalClosed && (internalEffects == null || internalEligible)) postFrame();
            } finally { if (delivered != null) delivered.close(); }
        }
    };

    private PopupStormManager() { internalEffects = null; }

    private PopupStormManager(Context physicalHost, PopupStormSettings initial,
            InternalEffects effects) {
        internalEffects = java.util.Objects.requireNonNull(effects);
        context = java.util.Objects.requireNonNull(physicalHost).getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        settings = java.util.Objects.requireNonNull(initial);
        ownedArtwork = OwnedPopupArtwork.empty();
        updateDisplayBounds();
    }

    static PopupStormManager forInternal(Context physicalHost, PopupStormSettings initial,
            InternalEffects effects) {
        requireMain();
        synchronized (PHYSICAL) {
            synchronized (INSTANCE.ownership) {
                if (internalOwner != null || managedDeviceOwner != null || INSTANCE.running || INSTANCE.startRequested
                        || INSTANCE.physicalWindowsOutstanding || INSTANCE.ownership.hasOwner())
                    throw new IllegalStateException("Physical Popup renderer is already owned");
                return internalOwner = new PopupStormManager(physicalHost, initial, effects);
            }
        }
    }

    /** Release only after the binding's windows, workers and posted callbacks really drained. */
    void releaseInternalSlot() {
        synchronized (PHYSICAL) {
            if (internalOwner != this || !internalClosed)
                throw new IllegalStateException("Popup physical ownership mismatch");
            internalOwner = null;
        }
    }

    boolean ownsInternalArtwork(OwnedPopupArtwork artwork) { return ownedArtwork == artwork; }

    /** Compatibility entry for bindings that activate before using explicit participation. */
    void activateInternal() {
        applyInternalParticipation(true);
    }

    /** Called inside the exact runtime's admitted effect, never by a persisted owner identity. */
    void applyInternalParticipation(boolean eligible) {
        requireInternal();
        internalEligible = eligible;
        startRequested = eligible;
        if (eligible) startIfReady();
        else stopOnMain(); // Retain immutable artwork and the exact physical owner slot.
    }

    /** Main-confined memory fence; actual window withdrawal still requires the ordinary gate. */
    void revokeInternalParticipation() {
        requireInternal();
        internalEligible = false;
        startRequested = false;
    }

    void applyInternalSettings(PopupStormSettings next, boolean artworkChanged) {
        requireInternal();
        settings = java.util.Objects.requireNonNull(next);
        if (artworkChanged) {
            // Removed/disabled art disappears at the applied revision, before later decoding.
            stopOnMain();
            ownedArtwork.close();
            ownedArtwork = OwnedPopupArtwork.empty();
        } else if (!next.isEnabled() || !next.isAcknowledged()) {
            stopOnMain();
        } else {
            startIfReady(); // Scalar settings do not reopen image I/O or reset existing popups.
        }
    }

    void applyInternalArtwork(OwnedPopupArtwork replacement) {
        requireInternal();
        stopOnMain();
        ownedArtwork.close();
        ownedArtwork = java.util.Objects.requireNonNull(replacement);
        startIfReady();
    }

    void closeInternal() {
        requireMain();
        if (internalEffects == null) throw new IllegalStateException("Not an owned popup renderer");
        internalClosed = true;
        internalEligible = false;
        startRequested = false;
        stopOnMain(); // Throws on unacknowledged detach; retain assets and owner occupancy.
        ownedArtwork.close();
    }

    boolean internalResourcesDrained() {
        requireMain();
        return internalEffects != null && internalClosed && !running && !framePosted
                && popups.isEmpty() && stopControl == null && ownedArtwork.count() == 0;
    }

    /** Draw callbacks belong to this exact instance and are fenced after revocation. */
    void drawPopup(Runnable draw) {
        if (deviceRequest != null) {
            if (deviceAllowsEffects()) deviceComponent.normal(draw);
        } else if (internalEffects == null) draw.run();
        else if (!internalClosed && internalEligible) internalEffects.run(draw);
    }

    private void requireInternal() {
        requireMain();
        if (internalEffects == null || internalClosed)
            throw new IllegalStateException("Owned popup renderer unavailable");
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Popup windows require the main thread");
    }
    private void postFrame() {
        if (deviceRequest != null) {
            if (!deviceAllowsEffects()) return;
            deviceFrameTicket = deviceWork.begin();
            if (deviceFrameTicket == null) return;
        }
        framePosted = true;
        try { Choreographer.getInstance().postFrameCallback(frameCallback); }
        catch (RuntimeException | Error uncertain) {
            if (deviceRequest != null) { deviceWork.failed(); if (deviceFrameTicket != null) deviceFrameTicket.close(); }
            throw uncertain;
        }
    }
    public static PopupStormManager get() { return INSTANCE; }

    /** Pure physical-occupancy witness for shared admission. No Context, disk or platform calls. */
    public static boolean physicalResourcesOccupied() {
        return internalOwner != null || managedDeviceOwner != null || INSTANCE.startRequested || INSTANCE.physicalWindowsOutstanding;
    }

    public boolean isRunning() { return running; }
    public boolean isPreviewing() { return ownership.isPreview(); }
    /** Foreground/service reevaluation must not reset a preview or restart image I/O each event. */
    public void syncServiceParticipation(Context source, boolean eligible) {
        if (deviceRequest != null) throw new IllegalStateException("Use exact managed participant eligibility");
        if (eligible) {
            startOwned(source);
        } else {
            long stopped;
            synchronized (ownership) {
                stopped = ownership.isService() ? ownership.stop() : 0;
                if (stopped != 0) startRequested = false;
            }
            if (stopped != 0) stopAfterClaim(stopped);
        }
    }
    public long remainingPreviewMillis() {
        return ownership.remainingPreviewMillis(SystemClock.uptimeMillis());
    }
    public int libraryImageCount() { return internalEffects != null ? ownedArtwork.count() : library == null ? 0 : library.count(); }

    public void reloadSettings(Context source) {
        if (deviceRequest != null) {
            if (!deviceWork.isOpen()) return;
            if (!deviceComponent.normal(() -> { if (deviceWork.isOpen()) reloadSettingsNow(source); }))
                postDevice(() -> reloadSettingsNow(source));
        } else reloadSettingsNow(source);
    }
    private void reloadSettingsNow(Context source) {
        initialize(source);
        settings = PopupStormSettings.load(source);
        rescanAsync();
        if (!settings.isEnabled() && !ownership.isPreview() && running) stop();
    }

    public boolean canStart(Context source) {
        initialize(source);
        PopupStormSettings current = settings == null ? PopupStormSettings.load(source) : settings;
        return current.isEnabled() && canRender(source);
    }

    private boolean canRender(Context source) {
        PopupStormSettings current = settings == null ? PopupStormSettings.load(source) : settings;
        return current.isAcknowledged()
                && Settings.canDrawOverlays(source) && (internalEffects != null
                        ? ownedArtwork.count() > 0 : library != null && !library.isEmpty());
    }

    public void start(Context source) {
        startOwned(source);
    }

    /** Exact DEVICE service participant. Legacy tokens have no managed lifecycle receipt. */
    public static final class ServiceParticipation {
        private final long token;
        private final String signature;
        private final PopupStormManager manager;
        private boolean eligible, sealed, closed;
        private ServiceParticipation(long token, String signature) {
            this.token = token; this.signature = signature; manager = null;
        }
        private ServiceParticipation(PopupStormManager manager) {
            this.manager = manager; token = 0; signature = "";
        }
        public PopupStormManager manager() {
            if (manager == null) throw new IllegalStateException("Legacy popup participation");
            return manager;
        }
        public void setEligible(boolean value) {
            requireMain();
            PopupStormManager owner = manager();
            synchronized (owner.deviceParticipants) {
                if (sealed || closed) return;
                eligible = value;
            }
            if (!owner.deviceComponent.normal(owner::applyManagedEligibility))
                owner.postDevice(owner::applyManagedEligibility);
        }
        /** Memory-only revocation; never chooses a current runtime or successor. */
        public void seal() {
            PopupStormManager owner = manager();
            synchronized (owner.deviceParticipants) {
                sealed = true; eligible = false;
                boolean all = true;
                for (ServiceParticipation participant : owner.deviceParticipants) all &= participant.sealed;
                if (all && !owner.deviceWorkSealed) {
                    owner.deviceWorkSealed = true;
                    owner.deviceWork.close(() -> { });
                }
            }
        }
        /** Invoked inside this participant's real Cleanup, on a fresh main Handler dispatch. */
        public void closeFromFreshMainQueue() {
            requireMain();
            PopupStormManager owner = manager();
            seal();
            boolean allClosed = true;
            synchronized (owner.deviceParticipants) {
                closed = true;
                for (ServiceParticipation participant : owner.deviceParticipants) allClosed &= participant.closed;
            }
            if (allClosed) owner.closeManagedDeviceFromFreshMainQueue();
            else if (!owner.deviceAllowsEffects()) {
                // A registered but ineligible sibling is not permission to leave our windows up.
                // This is withdrawal under the caller's real CLEANUP, not terminal owner ACK.
                owner.withdrawManagedDeviceFromFreshMainQueue();
            }
        }
        /** Cached actual receipts only; safe for the owner's off-main drain observation. */
        public boolean managedResourcesDrained() {
            PopupStormManager owner = manager();
            synchronized (owner.deviceParticipants) { if (!closed) return false; }
            if (!owner.deviceWork.isDrained()) return false;
            synchronized (PHYSICAL) {
                if (managedDeviceOwner == owner) managedDeviceOwner = null;
            }
            return true;
        }
    }

    /** Caller holds this exact component's STARTUP permit; no current-runtime lookup occurs. */
    public static ServiceParticipation managedDevice(Context context,
            com.subhub.app.service.RuntimeCaptureHost.DeviceRequest request,
            com.subhub.app.runtime.DeviceRuntimeEffects.DeviceComponent component) {
        requireMain();
        if (!request.owner().equals(component.owner())) throw new SecurityException("Popup owner mismatch");
        synchronized (PHYSICAL) {
            PopupStormManager owner = managedDeviceOwner;
            if (owner == null) {
                if (internalOwner != null || INSTANCE.running || INSTANCE.startRequested
                        || INSTANCE.physicalWindowsOutstanding || INSTANCE.ownership.hasOwner())
                    throw new IllegalStateException("Popup renderer already occupied");
                owner = new PopupStormManager();
                owner.deviceRequest = request; owner.deviceComponent = component;
                managedDeviceOwner = owner;
                owner.initialize(context);
            } else if (owner.deviceRequest.execution() != request.execution()
                    || !owner.deviceRequest.owner().equals(request.owner())) {
                throw new SecurityException("Different Popup runtime already owns windows");
            }
            synchronized (owner.deviceParticipants) {
                if (owner.deviceWorkSealed || !owner.deviceWork.isOpen())
                    throw new IllegalStateException("Popup owner closing");
                ServiceParticipation result = new ServiceParticipation(owner);
                owner.deviceParticipants.add(result);
                return result;
            }
        }
    }

    private boolean deviceAllowsEffects() {
        synchronized (deviceParticipants) {
            if (deviceWorkSealed || !deviceWork.isOpen()) return false;
            for (ServiceParticipation participant : deviceParticipants)
                if (!participant.sealed && participant.eligible) return true;
            return false;
        }
    }

    private void applyManagedEligibility() {
        startRequested = deviceAllowsEffects();
        if (startRequested) { rescanAsync(); startIfReady(); } else stopOnMain();
    }

    private void postDevice(Runnable action) {
        PopupWorkScope.Ticket ticket = deviceWork.begin();
        if (ticket == null) return;
        Runnable delivery = new Runnable() {
            @Override public void run() {
                boolean finished = true;
                try {
                    if (!ticket.isCurrent()) return;
                    if (!deviceComponent.normal(() -> { if (ticket.isCurrent()) action.run(); })) {
                        // Admission refusal is not completion; retain the same counted delivery.
                        if (main.postDelayed(this, 16L)) finished = false;
                        else deviceWork.failed();
                    }
                } catch (RuntimeException | Error uncertain) { deviceWork.failed(); throw uncertain; }
                finally { if (finished) ticket.close(); }
            }
        };
        try {
            if (!main.post(delivery)) { deviceWork.failed(); ticket.close(); }
        } catch (RuntimeException | Error uncertain) { deviceWork.failed(); ticket.close(); throw uncertain; }
    }

    private void closeManagedDeviceFromFreshMainQueue() {
        if (deviceCloseStarted) return;
        deviceCloseStarted = true;
        withdrawManagedDeviceFromFreshMainQueue();
        try {
            bitmapCache.clear(); // Reference-only cache eviction; never recycle View-submitted pixels.
            library = null;
            deviceWork.windowsClosed();
        } catch (RuntimeException | Error uncertain) { deviceWork.failed(); throw uncertain; }
    }

    private void withdrawManagedDeviceFromFreshMainQueue() {
        startRequested = false;
        removingFromFreshMainQueue = true;
        try {
            cancelPreviewTimeout();
            stopOnMain();
            for (View view : new ArrayList<>(deviceRetiringViews)) retireDeviceWindow(view);
            if (!deviceWindows.isEmpty()) throw new IllegalStateException("Popup windows remain");
        } catch (RuntimeException | Error uncertain) {
            deviceWork.failed(); throw uncertain;
        } finally { removingFromFreshMainQueue = false; }
    }

    private void retireDeviceWindow(View view) {
        deviceWindows.removeFromFreshMainQueue(windowManager, view);
        deviceRetiringViews.remove(view);
        if (deviceWork.isOpen() && deviceAllowsEffects()) startIfReady();
    }

    public ServiceParticipation startOwned(Context source) {
        return startOwned(source, com.subhub.app.appmode.RuntimeSessionIdentity.componentSignature(source));
    }

    /** A capture instance passes its original signature rather than adopting whatever replaced it. */
    public ServiceParticipation startOwned(Context source, String expectedSignature) {
        if (deviceRequest != null) throw new IllegalStateException("Use exact managed participation");
        try (var admitted = com.subhub.app.runtime.AppRuntimeGateway.tryBeginLegacyWork(source)) {
            return admitted == null ? null : startLegacyParticipation(source, expectedSignature);
        }
    }

    private ServiceParticipation startLegacyParticipation(Context source, String expectedSignature) {
        // Claim before posting so an already queued preview timeout cannot stop
        // an effect that a real service has adopted from another thread.
        long token;
        com.subhub.app.appmode.RuntimeSessionIdentity.ComponentObservation observed =
                com.subhub.app.appmode.RuntimeSessionIdentity.componentObservation(source);
        if (!observed.signature.equals(expectedSignature)) return null;
        synchronized (PHYSICAL) {
            if (internalOwner != null || managedDeviceOwner != null) return null;
            synchronized (ownership) {
                // Runtime is released before these locks, which also guard rendering ownership.
                if (!observed.isCurrent()) return null;
                token = ownership.serviceToken(expectedSignature);
                if (token != 0) return new ServiceParticipation(token, expectedSignature);
                token = ownership.adoptService(expectedSignature);
                startRequested = true;
            }
        }
        ServiceParticipation participation = new ServiceParticipation(token, expectedSignature);
        initialize(source);
        settings = PopupStormSettings.load(source);
        rescanAsync();
        main.post(() -> {
            synchronized (ownership) {
                if (!ownership.isService(participation.token)) return;
                cancelPreviewTimeout();
                startIfReady();
            }
        });
        return participation;
    }

    public void stopOwnedService(ServiceParticipation participation) {
        if (participation == null) return;
        long stopped;
        synchronized (ownership) {
            stopped = ownership.stopService(participation.token);
            if (stopped != 0) startRequested = false;
        }
        if (stopped != 0) stopAfterClaim(stopped);
    }

    /** Starts at most ten seconds of editor preview without changing service state. */
    public PreviewResult preview(Context source) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Popup previews must be requested on the main thread");
        }
        try (var admitted = com.subhub.app.runtime.AppRuntimeGateway.tryBeginLegacyWork(source)) {
            return admitted == null ? PreviewResult.UNAVAILABLE : previewLegacy(source);
        }
    }

    private PreviewResult previewLegacy(Context source) {
        initialize(source);
        settings = PopupStormSettings.load(source);
        if (!settings.isAcknowledged()
                || !Settings.canDrawOverlays(source)) return PreviewResult.UNAVAILABLE;
        synchronized (PHYSICAL) {
            if (internalOwner != null || managedDeviceOwner != null) return PreviewResult.ALREADY_RUNNING;
            synchronized (ownership) {
                if (running || ownership.hasOwner()) return PreviewResult.ALREADY_RUNNING;
                long token = ownership.beginPreview(SystemClock.uptimeMillis(), PREVIEW_DURATION_MS);
                startRequested = true;
                cancelPreviewTimeout();
                previewTimeout = () -> {
                    synchronized (ownership) {
                        if (!ownership.expirePreview(token, SystemClock.uptimeMillis())) return;
                        startRequested = false;
                        previewTimeout = null;
                        stopOnMain();
                    }
                };
                main.postDelayed(previewTimeout, PREVIEW_DURATION_MS);
                rescanAsync();
                startIfReady();
            }
        }
        return PreviewResult.STARTED;
    }

    public void stop() {
        if (deviceRequest != null) {
            if (!deviceWork.isOpen()) return;
            // Stop intent cannot disappear merely because the effect permit is busy. Revoke
            // eligibility now, but keep every platform effect behind its exact admitted callback.
            synchronized (deviceParticipants) {
                for (ServiceParticipation participant : deviceParticipants) participant.eligible = false;
            }
            if (Looper.myLooper() != Looper.getMainLooper()
                    || !deviceComponent.normal(this::applyManagedEligibility))
                postDevice(this::applyManagedEligibility);
            // Reconciliation reads current participation, so an old queued Stop cannot overwrite
            // a newer explicit eligibility request. Pending callbacks still occupy this owner.
            return;
        }
        if (internalEffects != null) { internalEffects.requestStop(); return; }
        long token;
        synchronized (ownership) {
            token = ownership.stop();
            startRequested = false;
        }
        stopAfterClaim(token);
    }

    private void stopAfterClaim(long token) {
        Runnable stopRequest = () -> {
            synchronized (ownership) {
                // An older queued stop must not remove a newly owned effect.
                if (!ownership.isStopped(token)) return;
                cancelPreviewTimeout();
                stopOnMain();
            }
        };
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(stopRequest);
        } else {
            stopRequest.run();
        }
    }

    private void cancelPreviewTimeout() {
        if (previewTimeout == null) return;
        main.removeCallbacks(previewTimeout);
        previewTimeout = null;
    }

    public void updateDetections(List<RectF> boxes) {
        if (deviceRequest != null && !deviceWork.isOpen()) return;
        List<RectF> copies = new ArrayList<>();
        for (RectF box : boxes) copies.add(new RectF(box));
        detections = Collections.unmodifiableList(copies);
    }

    /** Replacement metadata is queried at placement, not copied into the legacy detection cache. */
    public ScopedDetectionHints<RectF>.Binding bindDetectionHints(Supplier<List<RectF>> source) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main hint ownership required");
        detections = List.of(); // Do not resurrect a former legacy window after this owner closes.
        return detectionHints.bind(source);
    }

    public void updateTrackedObjects(List<TrackedObject> tracks, int frameWidth, int frameHeight) {
        if (deviceRequest != null && !deviceWork.isOpen()) return;
        if (frameWidth <= 0 || frameHeight <= 0) return;
        float scaleX = screenWidth / (float) frameWidth;
        float scaleY = screenHeight / (float) frameHeight;
        List<RectF> boxes = new ArrayList<>();
        for (TrackedObject track : tracks) {
            if (!track.isActive()) continue;
            BBox box = track.getBox();
            boxes.add(new RectF(box.getX() * scaleX, box.getY() * scaleY,
                    box.getRight() * scaleX, box.getBottom() * scaleY));
        }
        updateDetections(boxes);
    }

    void onPopupTapped(Popup target) {
        Runnable tapped = () -> {
            if (internalClosed) return;
            PopupStormSettings current = settings;
            if (current != null && current.isClickDismissesAll()) clearPopups();
            else for (LivePopup live : popups) if (live.popup == target) {
                remove(live);
                popups.remove(live);
                break;
            }
        };
        if (deviceRequest != null) postDevice(tapped);
        else if (internalEffects == null) main.post(tapped);
        else internalEffects.post(tapped);
    }

    private synchronized void initialize(Context source) {
        if (context != null) return;
        context = source.getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        bitmapCache = new PopupBitmapCache(context);
        library = new PopupLibrary(bitmapCache);
        settings = PopupStormSettings.load(context);
        updateDisplayBounds();
    }

    private void rescanAsync() {
        PopupStormSettings snapshot = settings;
        PopupLibrary target = library;
        if (snapshot == null || target == null) return;
        String signature = snapshot.getPackImageDir() + "\n" + String.join("\n", snapshot.getFolders());
        if (signature.equals(scheduledLibrarySignature)) return;
        scheduledLibrarySignature = signature;
        PopupWorkScope.Ticket scanTicket = deviceRequest == null ? null : deviceWork.begin();
        if (deviceRequest != null && scanTicket == null) return;
        Thread scan = new Thread(() -> {
            try {
                if (scanTicket == null || scanTicket.isCurrent()) {
                    if (deviceRequest == null) target.rescan(snapshot.getPackImageDir(), snapshot.getFolders());
                    else if (!deviceComponent.normal(() -> {
                        if (scanTicket.isCurrent()) target.rescan(snapshot.getPackImageDir(), snapshot.getFolders());
                    })) { scheduledLibrarySignature = ""; postDevice(this::rescanAsync); return; }
                    if (deviceRequest == null) main.post(this::startIfReady);
                    else postDevice(this::startIfReady);
                }
            } catch (RuntimeException | Error uncertain) {
                if (deviceRequest != null) deviceWork.failed();
                else throw uncertain;
            } finally { if (scanTicket != null) scanTicket.close(); }
        }, "PopupStorm-library");
        scan.setDaemon(true);
        try { scan.start(); }
        catch (RuntimeException | Error uncertain) {
            if (scanTicket != null) { deviceWork.failed(); scanTicket.close(); }
            throw uncertain;
        }
    }

    private void startIfReady() {
        synchronized (ownership) {
            long preview = ownership.previewToken();
            if (preview != 0 && ownership.expirePreview(preview, SystemClock.uptimeMillis())) {
                startRequested = false;
                cancelPreviewTimeout();
                stopOnMain();
                return;
            }
            if (deviceRequest != null && (!deviceAllowsEffects() || !deviceRetiringViews.isEmpty())) return;
            if (running || internalClosed || !startRequested
                    || (internalEffects == null && deviceRequest == null && !ownership.hasOwner())
                    || context == null || (!ownership.isPreview() && !settings.isEnabled())
                    || !canRender(context)) return;
            updateDisplayBounds();
            long now = System.currentTimeMillis();
            lastTick = now;
            lastSpawn = now;
            burstStarted = 0;
            burstUntil = 0;
            running = true;
            showStopControl();
            if (running) postFrame();
        }
    }

    private void stopOnMain() {
        if (running || framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
        if (deviceFrameTicket != null) { deviceFrameTicket.close(); deviceFrameTicket = null; }
        running = false;
        clearPopups();
        if (stopControl != null) {
            detach(stopControl);
            stopControl = null;
        }
        detections = List.of();
        physicalWindowsOutstanding = internalEffects == null && deviceRemovalUncertain;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float seconds = Math.max(0, Math.min(.1f, (now - lastTick) / 1000f));
        lastTick = now;
        PopupStormSettings current = settings;
        if (current == null || (!ownership.isPreview() && !current.isEnabled())
                || !current.isAcknowledged()) {
            if (internalEffects == null) stop(); else stopOnMain();
            return;
        }
        for (LivePopup live : popups) {
            if (now >= live.popup.expiresAt()) {
                remove(live);
                popups.remove(live);
                continue;
            }
            if (current.isBouncing()) {
                live.popup.integrate(seconds, screenWidth, screenHeight, reservedTop);
                reposition(live);
            }
            live.view.postInvalidateOnAnimation();
        }
        float rate = effectiveRate(current, now);
        long interval = rate <= 0 ? Long.MAX_VALUE : Math.max(50, (long) (1000f / rate));
        if (popups.size() < current.getMaxSimultaneous() && now - lastSpawn >= interval
                && spawnOne(current, now)) lastSpawn = now;
    }

    private float effectiveRate(PopupStormSettings current, long now) {
        if (!current.isBurstEnabled()) return current.getSpawnRate();
        if (now > burstUntil && now > burstStarted + (long) (current.getBurstFrequency() * 1000)) {
            burstStarted = now;
            burstUntil = now + (long) (current.getBurstDuration() * 1000);
        }
        if (now < burstUntil) {
            float progress = Math.max(0, Math.min(1,
                    (now - burstStarted) / (current.getBurstDuration() * 1000f)));
            return Math.min(PopupStormSettings.MAX_SPAWN_RATE,
                    current.getSpawnRate() * (1 + (current.getBurstMultiplier() - 1) * progress));
        }
        return current.getSpawnRate();
    }

    private boolean spawnOne(PopupStormSettings current, long now) {
        // Include queued retirements, not just the active list, in the physical window bound.
        if (deviceRequest != null && deviceWindows.count() >= current.getMaxSimultaneous() + 1) return false;
        PopupLibrary.BitmapSource source = internalEffects == null
                ? library.randomBitmap() : ownedArtwork.randomBitmap();
        if (source == null) return false;
        Bitmap bitmap = source.bitmap;
        int requested = "fixed".equals(current.getSizeMode()) ? current.getFixedSize()
                : randomBetween(current.getMinSize(), current.getMaxSize());
        float scale = requested / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
        int width = Math.max(40, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(40, Math.round(bitmap.getHeight() * scale));
        width = Math.min(width, screenWidth);
        height = Math.min(height, Math.max(40, screenHeight - reservedTop));
        float[] position = choosePosition(current, width, height);
        float rotation = current.isRandomRotation()
                ? randomBetween(-current.getRotationMax(), current.getRotationMax()) : 0;
        float velocityX = 0;
        float velocityY = 0;
        if (current.isBouncing()) {
            double angle = random.nextDouble() * Math.PI * 2;
            velocityX = (float) Math.cos(angle) * current.getBouncingSpeed();
            velocityY = (float) Math.sin(angle) * current.getBouncingSpeed();
        }
        Bitmap denial = null;
        String caption = null;
        if (random.nextInt(100) < current.getDenialChance()) {
            boolean pixelate = "pixelate".equals(current.getDenialStyle())
                    || ("mixed".equals(current.getDenialStyle()) && random.nextBoolean());
            denial = pixelate ? DenialFilter.pixelate(bitmap, current.getDenialIntensity())
                    : DenialFilter.blur(bitmap, current.getDenialIntensity());
            if (current.isDenialCaption()) caption = current.getDenialCaptionText();
        }
        Popup popup = new Popup(ids.incrementAndGet(), source.path, bitmap, denial, caption,
                width, height, rotation, now, (long) (current.getDisplayDuration() * 1000),
                current.getFadeInMs(), current.getFadeOutMs(), position[0], position[1],
                velocityX, velocityY);
        LivePopup live = attach(popup);
        if (live == null) { popup.recycleDerived(); return false; }
        popups.add(live);
        if (internalEffects == null) new StatsRepository(context).recordPopupImpression();
        else internalEffects.recordImpression();
        return true;
    }

    private float[] choosePosition(PopupStormSettings current, int width, int height) {
        List<RectF> boxes = detectionHints.read(detections);
        if ("cover".equals(current.getDetectionMode()) && !boxes.isEmpty()) {
            RectF box = boxes.get(random.nextInt(boxes.size()));
            return clampPosition(box.centerX() - width / 2f, box.centerY() - height / 2f, width, height);
        }
        if ("center".equals(current.getPositionMode())) {
            return clampPosition((screenWidth - width) / 2f, (screenHeight - height) / 2f, width, height);
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            float x = random.nextInt(Math.max(1, screenWidth - width + 1));
            float y = reservedTop + random.nextInt(Math.max(1, screenHeight - reservedTop - height + 1));
            if (!"avoid".equals(current.getDetectionMode())
                    || avoidsDetections(x, y, width, height, current.getAvoidPadding(), boxes)) {
                return new float[]{x, y};
            }
        }
        return clampPosition((screenWidth - width) / 2f, (screenHeight - height) / 2f, width, height);
    }

    private boolean avoidsDetections(float x, float y, int width, int height, int padding,
            List<RectF> boxes) {
        RectF candidate = new RectF(x - padding, y - padding,
                x + width + padding, y + height + padding);
        for (RectF box : boxes) if (RectF.intersects(candidate, box)) return false;
        return true;
    }

    private float[] clampPosition(float x, float y, int width, int height) {
        return new float[]{Math.max(0, Math.min(screenWidth - width, x)),
                Math.max(reservedTop, Math.min(screenHeight - height, y))};
    }

    private LivePopup attach(Popup popup) {
        PopupOverlayView view = new PopupOverlayView(context, this, popup);
        WindowManager.LayoutParams params = paramsFor(popup);
        LivePopup live = new LivePopup(popup, view, params);
        try {
            if (deviceRequest != null) deviceWindows.adding(view);
            windowManager.addView(view, params);
            return live;
        } catch (RuntimeException error) {
            if (internalEffects != null || deviceRequest != null) {
                if (deviceRequest != null) deviceWork.failed();
                // Retain even a partially attached window until its real detach is acknowledged.
                popups.add(live);
                throw error;
            }
            Log.w(TAG, "Could not attach popup", error);
            return null;
        }
    }

    private WindowManager.LayoutParams paramsFor(Popup popup) {
        double radians = Math.toRadians(popup.rotation);
        float cosine = (float) Math.abs(Math.cos(radians));
        float sine = (float) Math.abs(Math.sin(radians));
        int outerWidth = Math.max(1, Math.round(popup.width * cosine + popup.height * sine) + WINDOW_SLOP * 2);
        int outerHeight = Math.max(1, Math.round(popup.width * sine + popup.height * cosine) + WINDOW_SLOP * 2);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                outerWidth, outerHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle(context.getString(R.string.popup_image_window_title));
        params.x = Math.round(popup.x - (outerWidth - popup.width) / 2f);
        params.y = Math.round(popup.y - (outerHeight - popup.height) / 2f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private void reposition(LivePopup live) {
        WindowManager.LayoutParams next = paramsFor(live.popup);
        live.params.x = next.x;
        live.params.y = next.y;
        try { windowManager.updateViewLayout(live.view, live.params); }
        catch (RuntimeException failure) { if (internalEffects != null) throw failure; }
    }

    private void detach(View view) {
        if (deviceRequest != null) {
            if (!deviceRetiringViews.contains(view)) deviceRetiringViews.add(view);
            if (removingFromFreshMainQueue) retireDeviceWindow(view);
            else postDevice(() -> {
                // A sibling's fresh Cleanup may already have withdrawn this exact window.
                if (deviceRetiringViews.contains(view)) retireDeviceWindow(view);
            });
            return;
        }
        if (internalEffects == null) {
            try { windowManager.removeViewImmediate(view); } catch (Exception ignored) { }
            if (view.isAttachedToWindow() || view.getParent() != null) deviceRemovalUncertain = true;
            return;
        }
        if (view.isAttachedToWindow() || view.getParent() != null) windowManager.removeViewImmediate(view);
        if (view.isAttachedToWindow() || view.getParent() != null)
            throw new IllegalStateException("Owned popup window did not detach");
    }

    private void remove(LivePopup live) {
        detach(live.view);
        if (deviceRequest == null) live.popup.recycleDerived(); // Managed View pixels use native refcount retirement.
    }

    private void clearPopups() {
        for (LivePopup live : popups) {
            remove(live);
            popups.remove(live);
        }
    }

    private void showStopControl() {
        if (stopControl != null) return;
        TextView stop = new TextView(context);
        stop.setText(R.string.popup_stop_now);
        stop.setTextColor(Color.WHITE);
        com.subhub.app.util.UiIdentity.textSize(stop, R.dimen.ui_text_body);
        stop.setGravity(Gravity.CENTER);
        stop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stop.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.accent_dark));
        background.setStroke(dp(2), context.getColor(R.color.accent));
        background.setCornerRadius(dp(24));
        stop.setBackground(background);
        stop.setOnClickListener(view -> stop());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setTitle(context.getString(R.string.popup_stop_window_title));
        params.x = dp(STOP_MARGIN);
        params.y = dp(STOP_MARGIN);
        try {
            physicalWindowsOutstanding = true;
            if (internalEffects != null || deviceRequest != null) stopControl = stop;
            if (deviceRequest != null) deviceWindows.adding(stop);
            windowManager.addView(stop, params);
            stopControl = stop;
            reservedTop = dp(80);
        } catch (RuntimeException error) {
            running = false;
            if (internalEffects != null || deviceRequest != null) {
                if (deviceRequest != null) deviceWork.failed();
                throw error;
            }
            physicalWindowsOutstanding = stop.isAttachedToWindow() || stop.getParent() != null;
            Log.w(TAG, "Persistent stop control unavailable; aborting Popup Storm", error);
        }
    }

    private void updateDisplayBounds() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        reservedTop = dp(80);
    }

    private int randomBetween(int minimum, int maximum) {
        if (maximum <= minimum) return minimum;
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class LivePopup {
        final Popup popup;
        final PopupOverlayView view;
        final WindowManager.LayoutParams params;
        LivePopup(Popup popup, PopupOverlayView view, WindowManager.LayoutParams params) {
            this.popup = popup;
            this.view = view;
            this.params = params;
        }
    }
}
