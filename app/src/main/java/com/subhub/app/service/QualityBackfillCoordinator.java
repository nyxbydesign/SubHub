package com.subhub.app.service;

import com.subhub.app.detection.BBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded source admission and cache-only confirmation state for quality observations.
 *
 * <p>This class intentionally stops at an immutable world-region result. It does not run a
 * detector and it has no reference to a tracker, an overlay, a statistics repository, or the
 * Accessibility motion generation. A service can use the one-slot mailbox to hand the existing
 * quality worker an old frame, then feed the worker's world-space detections back through
 * {@link #observe(BackfillStamp, BackfillContext, long, List)} before updating a render-only
 * content cache.</p>
 *
 * <p>The mailbox is latest-only: while a frame is being processed, the newest pending source
 * replaces an older pending source instead of creating a stale backlog. Every rejected or evicted
 * frame is closed by this class exactly once.</p>
 */
final class QualityBackfillCoordinator<T> implements AutoCloseable {
    static final int DEFAULT_MAX_CANDIDATES = 64;
    static final long DEFAULT_MAX_AGE_MS = 2_500L;
    static final float DEFAULT_MIN_WORLD_IOU = 0.35f;
    static final int MAX_SURFACE_KEY_CHARS = 512;
    static final int MAX_REGION_METADATA_CHARS = 128;

    private final int maxCandidates;
    private final long maxAgeMillis;
    private final float minimumWorldIou;
    private final Candidate[] candidates;
    private final int[] candidateBatchStamps;

    private BackfillFrame<T> pendingFrame;
    private int candidateCount;
    private int batchStamp = 1;
    private boolean closed;

    QualityBackfillCoordinator() {
        this(DEFAULT_MAX_CANDIDATES, DEFAULT_MAX_AGE_MS, DEFAULT_MIN_WORLD_IOU);
    }

    QualityBackfillCoordinator(int maxCandidates, long maxAgeMillis, float minimumWorldIou) {
        if (maxCandidates <= 0) throw new IllegalArgumentException("maxCandidates must be positive");
        if (maxAgeMillis <= 0L) throw new IllegalArgumentException("maxAgeMillis must be positive");
        if (Float.isNaN(minimumWorldIou)
                || minimumWorldIou < 0f || minimumWorldIou > 1f) {
            throw new IllegalArgumentException("minimumWorldIou must be in [0, 1]");
        }
        this.maxCandidates = maxCandidates;
        this.maxAgeMillis = maxAgeMillis;
        this.minimumWorldIou = minimumWorldIou;
        candidates = new Candidate[maxCandidates];
        candidateBatchStamps = new int[maxCandidates];
    }

    /**
     * Offers one source frame to the latest-only mailbox. The caller transfers ownership of the
     * frame to this method; it must not close an accepted frame itself. Rejected frames are closed
     * synchronously before this method returns.
     */
    synchronized OfferResult offer(BackfillFrame<T> frame, long nowUptimeMillis) {
        if (frame == null) return OfferResult.rejected(OfferStatus.REJECTED_NULL);
        if (closed) {
            frame.close();
            return OfferResult.rejected(OfferStatus.REJECTED_CLOSED);
        }
        if (!isFresh(frame.stamp, nowUptimeMillis)) {
            frame.close();
            return OfferResult.rejected(OfferStatus.REJECTED_STALE);
        }
        expirePending(nowUptimeMillis);
        if (pendingFrame == null) {
            pendingFrame = frame;
            return OfferResult.accepted(OfferStatus.ACCEPTED);
        }

        if (isOlder(pendingFrame.stamp, frame.stamp)) {
            pendingFrame.close();
            pendingFrame = frame;
            return OfferResult.accepted(OfferStatus.ACCEPTED_REPLACED_OLDER);
        }

        // Keep the newest source so quality never builds a delayed frame backlog.
        frame.close();
        return OfferResult.rejected(OfferStatus.REJECTED_OLDER);
    }

    /**
     * Claims the pending frame only when all non-motion fences still match. Motion generation is
     * deliberately absent from {@link BackfillContext}: a scroll can continue while a backfill is
     * running, but document/surface/transform/capture-epoch changes cannot.
     */
    synchronized PollResult<T> poll(BackfillContext current, long nowUptimeMillis) {
        if (closed) return PollResult.closed();
        if (pendingFrame == null) return PollResult.empty();

        BackfillFrame<T> frame = pendingFrame;
        pendingFrame = null;
        if (!isFresh(frame.stamp, nowUptimeMillis)) {
            frame.close();
            return PollResult.dropped(PollStatus.EXPIRED);
        }
        if (current == null || !current.accepts(frame.stamp)) {
            frame.close();
            return PollResult.dropped(PollStatus.FENCE_MISMATCH);
        }
        return PollResult.ready(frame);
    }

    /**
     * Feeds one detector result into the cache-only confirmation table. The source stamp is
     * checked again at result time because the app can switch documents or transform phases while
     * inference is running. Empty/absent detections do not remove or age a candidate; only the
     * explicit TTL and bounded-capacity policy can do that.
     *
     * <p>A region becomes ready only after results from two distinct capture sequences. Repeating
     * a result for the same sequence cannot promote it. Matching uses category family and
     * world-space IoU; motion generation is intentionally not part of either comparison.</p>
     */
    synchronized ObservationResult observe(
            BackfillStamp stamp,
            BackfillContext current,
            long nowUptimeMillis,
            List<BackfillRegion> regions) {
        if (closed) return ObservationResult.rejected(ObservationStatus.REJECTED_CLOSED);
        if (stamp != null && stamp.sourceMode == SourceMode.CURRENT_ONLY) {
            return ObservationResult.rejected(ObservationStatus.REJECTED_CURRENT_ONLY);
        }
        if (stamp == null || current == null || !current.accepts(stamp)) {
            return ObservationResult.rejected(ObservationStatus.REJECTED_FENCE);
        }
        if (!isFresh(stamp, nowUptimeMillis)) {
            return ObservationResult.rejected(ObservationStatus.REJECTED_STALE);
        }
        expireCandidates(nowUptimeMillis);
        if (regions == null || regions.isEmpty()) return ObservationResult.accepted();

        int mark = nextBatchStamp();
        int inspected = 0;
        int accepted = 0;
        int matched = 0;
        int inserted = 0;
        int newlyPromoted = 0;
        int refined = 0;
        ArrayList<BackfillRegion> ready = null;
        for (BackfillRegion region : regions) {
            if (inspected++ >= maxCandidates) break;
            if (region == null) continue;
            if (isDuplicateInBatch(stamp, region, mark)) continue;
            accepted++;
            int slot = findMatch(stamp, region, mark);
            if (slot < 0) {
                slot = allocateCandidate(nowUptimeMillis);
                if (slot < 0) continue;
                candidates[slot] = new Candidate(stamp, region, nowUptimeMillis);
                candidateBatchStamps[slot] = mark;
                candidateCount++;
                inserted++;
                continue;
            }

            matched++;
            candidateBatchStamps[slot] = mark;
            Candidate candidate = candidates[slot];
            boolean distinctCapture = candidate.observationCount < 2
                    && candidate.lastCaptureSequence != stamp.captureSequence;
            if (distinctCapture) candidate.observationCount++;
            candidate.lastCaptureSequence = stamp.captureSequence;
            candidate.lastObservedUptimeMillis = Math.max(0L, nowUptimeMillis);
            candidate.region = region;
            if (!candidate.promoted && candidate.observationCount >= 2) {
                candidate.promoted = true;
                newlyPromoted++;
                if (ready == null) ready = new ArrayList<>();
                ready.add(region);
            } else if (candidate.promoted) {
                refined++;
                if (ready == null) ready = new ArrayList<>();
                ready.add(region);
            }
        }

        ObservationStatus status = newlyPromoted > 0
                ? ObservationStatus.PROMOTED
                : refined > 0 ? ObservationStatus.REFINED : ObservationStatus.ACCEPTED;
        return new ObservationResult(status, accepted, matched, inserted,
                newlyPromoted, refined, ready == null
                        ? Collections.emptyList() : Collections.unmodifiableList(ready));
    }

    private boolean isDuplicateInBatch(
            BackfillStamp stamp, BackfillRegion region, int mark) {
        float duplicateIou = Math.max(0.70f, minimumWorldIou);
        for (int index = 0; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            if (candidate == null || candidateBatchStamps[index] != mark
                    || !sameFence(candidate.stamp, stamp)
                    || !sameCategoryFamily(candidate.region.category, region.category)) continue;
            if (worldIou(candidate.region.worldBox, region.worldBox) >= duplicateIou) return true;
        }
        return false;
    }

    /** Convenience overload for a claimed frame; ownership remains with the caller. */
    synchronized ObservationResult observe(
            BackfillFrame<?> frame,
            BackfillContext current,
            long nowUptimeMillis,
            List<BackfillRegion> regions) {
        if (frame == null || frame.isClosed()) {
            return ObservationResult.rejected(ObservationStatus.REJECTED_CLOSED);
        }
        return observe(frame.stamp, current, nowUptimeMillis, regions);
    }

    synchronized int candidateCount() {
        return candidateCount;
    }

    synchronized int pendingCount() {
        return pendingFrame == null ? 0 : 1;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** Clears pending source ownership and all non-authoritative confirmation candidates. */
    synchronized void clear() {
        if (pendingFrame != null) {
            pendingFrame.close();
            pendingFrame = null;
        }
        for (int index = 0; index < candidates.length; index++) clearCandidate(index);
        candidateCount = 0;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        clear();
        closed = true;
    }

    private int findMatch(BackfillStamp stamp, BackfillRegion region, int mark) {
        int best = -1;
        float bestIou = minimumWorldIou;
        for (int index = 0; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            if (candidate == null || candidateBatchStamps[index] == mark
                    || !sameFence(candidate.stamp, stamp)
                    || !sameCategoryFamily(candidate.region.category, region.category)) continue;
            float iou = worldIou(candidate.region.worldBox, region.worldBox);
            if (iou >= bestIou) {
                best = index;
                bestIou = iou;
            }
        }
        return best;
    }

    private int allocateCandidate(long nowUptimeMillis) {
        for (int index = 0; index < candidates.length; index++) {
            if (candidates[index] == null) return index;
        }
        int victim = 0;
        long oldest = Long.MAX_VALUE;
        for (int index = 0; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            if (candidate.lastObservedUptimeMillis < oldest) {
                victim = index;
                oldest = candidate.lastObservedUptimeMillis;
            }
        }
        // Capacity eviction is independent of detector absence. No negative observation removes
        // a candidate; this is only the hard memory bound required by the backfill plane.
        clearCandidate(victim);
        return victim;
    }

    private void expirePending(long nowUptimeMillis) {
        if (pendingFrame != null && !isFresh(pendingFrame.stamp, nowUptimeMillis)) {
            pendingFrame.close();
            pendingFrame = null;
        }
    }

    private void expireCandidates(long nowUptimeMillis) {
        long now = Math.max(0L, nowUptimeMillis);
        for (int index = 0; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            if (candidate == null) continue;
            long age = now >= candidate.lastObservedUptimeMillis
                    ? now - candidate.lastObservedUptimeMillis : 0L;
            if (age > maxAgeMillis) {
                clearCandidate(index);
            }
        }
    }

    private void clearCandidate(int index) {
        if (candidates[index] != null) {
            candidates[index] = null;
            candidateBatchStamps[index] = 0;
            candidateCount--;
        }
    }

    private int nextBatchStamp() {
        if (++batchStamp == 0) {
            java.util.Arrays.fill(candidateBatchStamps, 0);
            batchStamp = 1;
        }
        return batchStamp;
    }

    private boolean isFresh(BackfillStamp stamp, long nowUptimeMillis) {
        long now = Math.max(0L, nowUptimeMillis);
        if (stamp.captureUptimeMillis <= 0L || now < stamp.captureUptimeMillis) return false;
        return now - stamp.captureUptimeMillis <= maxAgeMillis;
    }

    private static boolean isOlder(BackfillStamp first, BackfillStamp second) {
        if (first.captureUptimeMillis != second.captureUptimeMillis) {
            return first.captureUptimeMillis < second.captureUptimeMillis;
        }
        return first.captureSequence < second.captureSequence;
    }

    private static boolean sameFence(BackfillStamp first, BackfillStamp second) {
        return first.sourceMode == second.sourceMode
                && first.captureEpoch == second.captureEpoch
                && first.documentEpoch == second.documentEpoch
                && first.transformGeneration == second.transformGeneration
                && first.phaseToken == second.phaseToken
                && first.phaseCertain == second.phaseCertain
                && first.surfaceKey.equals(second.surfaceKey);
    }

    static boolean sameCategoryFamily(String first, String second) {
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        return (first.startsWith("face_") && second.startsWith("face_"))
                || (first.startsWith("text_") && second.startsWith("text_"));
    }

    static float worldIou(BBox first, BBox second) {
        if (first == null || second == null) return 0f;
        return first.intersectionOverUnion(second);
    }

    private static String safeSurfaceKey(String value, SourceMode sourceMode) {
        if (value == null) throw new NullPointerException("surfaceKey");
        String trimmed = value.trim();
        if (trimmed.length() > MAX_SURFACE_KEY_CHARS) {
            throw new IllegalArgumentException("surfaceKey is too long");
        }
        if (sourceMode == SourceMode.CACHE_BACKFILL && trimmed.isEmpty()) {
            throw new IllegalArgumentException("cache surfaceKey is empty");
        }
        if (sourceMode == SourceMode.CURRENT_ONLY && !trimmed.isEmpty()) {
            throw new IllegalArgumentException("current-only surfaceKey must be empty");
        }
        return trimmed;
    }

    private static String safeMetadata(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > MAX_REGION_METADATA_CHARS) {
            throw new IllegalArgumentException(name + " is empty or too long");
        }
        return value;
    }

    private static final class Candidate {
        private final BackfillStamp stamp;
        private BackfillRegion region;
        private long lastCaptureSequence;
        private long lastObservedUptimeMillis;
        private int observationCount = 1;
        private boolean promoted;

        private Candidate(BackfillStamp stamp, BackfillRegion region, long nowUptimeMillis) {
            this.stamp = stamp;
            this.region = region;
            this.lastCaptureSequence = stamp.captureSequence;
            this.lastObservedUptimeMillis = Math.max(0L, nowUptimeMillis);
        }
    }

    enum OfferStatus {
        ACCEPTED,
        ACCEPTED_REPLACED_OLDER,
        REJECTED_NULL,
        REJECTED_STALE,
        REJECTED_OLDER,
        REJECTED_CLOSED
    }

    static final class OfferResult {
        private final OfferStatus status;

        private OfferResult(OfferStatus status) {
            this.status = status;
        }

        private static OfferResult accepted(OfferStatus status) {
            return new OfferResult(status);
        }

        private static OfferResult rejected(OfferStatus status) {
            return new OfferResult(status);
        }

        OfferStatus status() { return status; }
        boolean accepted() {
            return status == OfferStatus.ACCEPTED
                    || status == OfferStatus.ACCEPTED_REPLACED_OLDER;
        }
    }

    enum PollStatus {
        READY,
        EMPTY,
        EXPIRED,
        FENCE_MISMATCH,
        CLOSED
    }

    static final class PollResult<T> {
        private final PollStatus status;
        private final BackfillFrame<T> frame;

        private PollResult(PollStatus status, BackfillFrame<T> frame) {
            this.status = status;
            this.frame = frame;
        }

        private static <T> PollResult<T> ready(BackfillFrame<T> frame) {
            return new PollResult<>(PollStatus.READY, frame);
        }

        private static <T> PollResult<T> empty() {
            return new PollResult<>(PollStatus.EMPTY, null);
        }

        private static <T> PollResult<T> closed() {
            return new PollResult<>(PollStatus.CLOSED, null);
        }

        private static <T> PollResult<T> dropped(PollStatus status) {
            return new PollResult<>(status, null);
        }

        PollStatus status() { return status; }
        BackfillFrame<T> frame() { return frame; }
        boolean ready() { return status == PollStatus.READY && frame != null; }
    }

    enum ObservationStatus {
        ACCEPTED,
        PROMOTED,
        REFINED,
        REJECTED_CURRENT_ONLY,
        REJECTED_STALE,
        REJECTED_FENCE,
        REJECTED_CLOSED
    }

    static final class ObservationResult {
        private final ObservationStatus status;
        private final int accepted;
        private final int matched;
        private final int inserted;
        private final int newlyPromoted;
        private final int refined;
        private final List<BackfillRegion> readyRegions;

        private ObservationResult(
                ObservationStatus status,
                int accepted,
                int matched,
                int inserted,
                int newlyPromoted,
                int refined,
                List<BackfillRegion> readyRegions) {
            this.status = status;
            this.accepted = accepted;
            this.matched = matched;
            this.inserted = inserted;
            this.newlyPromoted = newlyPromoted;
            this.refined = refined;
            this.readyRegions = readyRegions;
        }

        private static ObservationResult accepted() {
            return new ObservationResult(ObservationStatus.ACCEPTED,
                    0, 0, 0, 0, 0, Collections.emptyList());
        }

        private static ObservationResult rejected(ObservationStatus status) {
            return new ObservationResult(status, 0, 0, 0, 0, 0,
                    Collections.emptyList());
        }

        ObservationStatus status() { return status; }
        int acceptedCount() { return accepted; }
        int matched() { return matched; }
        int inserted() { return inserted; }
        int newlyPromoted() { return newlyPromoted; }
        int refined() { return refined; }
        List<BackfillRegion> readyRegions() { return readyRegions; }
    }

    /** Immutable fence identity for one captured source frame. */
    static final class BackfillStamp {
        final SourceMode sourceMode;
        final long captureEpoch;
        final long documentEpoch;
        final String surfaceKey;
        final int applicationWindowId;
        final long transformGeneration;
        final long phaseToken;
        final boolean phaseCertain;
        final long captureUptimeMillis;
        final long motionGeneration;
        final long cameraX;
        final long cameraY;
        final long captureSequence;

        BackfillStamp(
                long captureEpoch,
                long documentEpoch,
                String surfaceKey,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain,
                long captureUptimeMillis,
                long motionGeneration,
                long captureSequence) {
            this(SourceMode.CACHE_BACKFILL, captureEpoch, documentEpoch, surfaceKey, -1,
                    transformGeneration, phaseToken, phaseCertain, captureUptimeMillis,
                    motionGeneration, 0L, 0L, captureSequence);
        }

        static BackfillStamp currentOnly(
                long captureEpoch,
                long documentEpoch,
                int applicationWindowId,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain,
                long captureUptimeMillis,
                long motionGeneration,
                long cameraX,
                long cameraY,
                long captureSequence) {
            return new BackfillStamp(
                    SourceMode.CURRENT_ONLY, captureEpoch, documentEpoch, "",
                    applicationWindowId, transformGeneration, phaseToken, phaseCertain,
                    captureUptimeMillis, motionGeneration, cameraX, cameraY, captureSequence);
        }

        private BackfillStamp(
                SourceMode sourceMode,
                long captureEpoch,
                long documentEpoch,
                String surfaceKey,
                int applicationWindowId,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain,
                long captureUptimeMillis,
                long motionGeneration,
                long cameraX,
                long cameraY,
                long captureSequence) {
            if (captureEpoch <= 0L) throw new IllegalArgumentException("captureEpoch must be positive");
            if (documentEpoch <= 0L) throw new IllegalArgumentException("documentEpoch must be positive");
            if (captureUptimeMillis <= 0L) {
                throw new IllegalArgumentException("captureUptimeMillis must be positive");
            }
            if (captureSequence <= 0L) {
                throw new IllegalArgumentException("captureSequence must be positive");
            }
            if (sourceMode == SourceMode.CURRENT_ONLY && applicationWindowId < 0) {
                throw new IllegalArgumentException(
                        "applicationWindowId must be known for current-only quality");
            }
            this.sourceMode = Objects.requireNonNull(sourceMode, "sourceMode");
            this.captureEpoch = captureEpoch;
            this.documentEpoch = documentEpoch;
            this.surfaceKey = safeSurfaceKey(surfaceKey, sourceMode);
            this.applicationWindowId = applicationWindowId;
            this.transformGeneration = transformGeneration;
            this.phaseToken = phaseToken;
            this.phaseCertain = phaseCertain;
            this.captureUptimeMillis = captureUptimeMillis;
            this.motionGeneration = motionGeneration;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.captureSequence = captureSequence;
        }

        SourceMode sourceMode() { return sourceMode; }
        long captureEpoch() { return captureEpoch; }
        long documentEpoch() { return documentEpoch; }
        String surfaceKey() { return surfaceKey; }
        int applicationWindowId() { return applicationWindowId; }
        long transformGeneration() { return transformGeneration; }
        long phaseToken() { return phaseToken; }
        boolean phaseCertain() { return phaseCertain; }
        long captureUptimeMillis() { return captureUptimeMillis; }
        long motionGeneration() { return motionGeneration; }
        long cameraX() { return cameraX; }
        long cameraY() { return cameraY; }
        long captureSequence() { return captureSequence; }
    }

    /** Current scene identity used for claim/result-time fences. */
    static final class BackfillContext {
        final SourceMode sourceMode;
        final long captureEpoch;
        final long documentEpoch;
        final String surfaceKey;
        final int applicationWindowId;
        final long transformGeneration;
        final long phaseToken;
        final boolean phaseCertain;
        final long motionGeneration;
        final long cameraX;
        final long cameraY;
        final long captureSequence;

        BackfillContext(
                long captureEpoch,
                long documentEpoch,
                String surfaceKey,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain) {
            this(SourceMode.CACHE_BACKFILL, captureEpoch, documentEpoch, surfaceKey, -1,
                    transformGeneration, phaseToken, phaseCertain, 0L, 0L, 0L, 0L);
        }

        static BackfillContext currentOnly(
                long captureEpoch,
                long documentEpoch,
                int applicationWindowId,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain,
                long motionGeneration,
                long cameraX,
                long cameraY,
                long captureSequence) {
            return new BackfillContext(
                    SourceMode.CURRENT_ONLY, captureEpoch, documentEpoch, "",
                    applicationWindowId, transformGeneration, phaseToken, phaseCertain,
                    motionGeneration, cameraX, cameraY, captureSequence);
        }

        private BackfillContext(
                SourceMode sourceMode,
                long captureEpoch,
                long documentEpoch,
                String surfaceKey,
                int applicationWindowId,
                long transformGeneration,
                long phaseToken,
                boolean phaseCertain,
                long motionGeneration,
                long cameraX,
                long cameraY,
                long captureSequence) {
            if (captureEpoch <= 0L) throw new IllegalArgumentException("captureEpoch must be positive");
            if (documentEpoch <= 0L) throw new IllegalArgumentException("documentEpoch must be positive");
            if (sourceMode == SourceMode.CURRENT_ONLY) {
                if (applicationWindowId < 0) {
                    throw new IllegalArgumentException(
                            "applicationWindowId must be known for current-only quality");
                }
                if (captureSequence <= 0L) {
                    throw new IllegalArgumentException(
                            "captureSequence must be positive for current-only quality");
                }
            }
            this.sourceMode = Objects.requireNonNull(sourceMode, "sourceMode");
            this.captureEpoch = captureEpoch;
            this.documentEpoch = documentEpoch;
            this.surfaceKey = safeSurfaceKey(surfaceKey, sourceMode);
            this.applicationWindowId = applicationWindowId;
            this.transformGeneration = transformGeneration;
            this.phaseToken = phaseToken;
            this.phaseCertain = phaseCertain;
            this.motionGeneration = motionGeneration;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.captureSequence = captureSequence;
        }

        boolean accepts(BackfillStamp stamp) {
            if (stamp == null || sourceMode != stamp.sourceMode
                    || !phaseCertain || !stamp.phaseCertain) return false;
            boolean common = captureEpoch == stamp.captureEpoch
                    && documentEpoch == stamp.documentEpoch
                    && transformGeneration == stamp.transformGeneration
                    && phaseToken == stamp.phaseToken;
            if (!common) return false;
            if (sourceMode == SourceMode.CACHE_BACKFILL) {
                return surfaceKey.equals(stamp.surfaceKey);
            }
            return surfaceKey.isEmpty() && stamp.surfaceKey.isEmpty()
                    && applicationWindowId == stamp.applicationWindowId
                    && motionGeneration == stamp.motionGeneration
                    && cameraX == stamp.cameraX
                    && cameraY == stamp.cameraY
                    && captureSequence >= stamp.captureSequence;
        }
    }

    enum SourceMode { CACHE_BACKFILL, CURRENT_ONLY }

    /** One source resource whose ownership moves through offer -> poll -> caller close. */
    static final class BackfillFrame<T> implements AutoCloseable {
        private volatile T resource;
        private final FrameReleaser<? super T> releaser;
        private final BackfillStamp stamp;
        private final AtomicBoolean closed = new AtomicBoolean();

        BackfillFrame(T resource, BackfillStamp stamp, FrameReleaser<? super T> releaser) {
            this.resource = Objects.requireNonNull(resource, "resource");
            this.stamp = Objects.requireNonNull(stamp, "stamp");
            this.releaser = releaser;
        }

        T resource() { return closed.get() ? null : resource; }
        BackfillStamp stamp() { return stamp; }
        boolean isClosed() { return closed.get(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            T value = resource;
            resource = null;
            if (value != null && releaser != null) releaser.release(value);
        }
    }

    interface FrameReleaser<T> {
        void release(T value);
    }

    /** A world-space model result; it contains no source pixels and is safe to retain briefly. */
    static final class BackfillRegion {
        final String className;
        final String category;
        final float confidence;
        final BBox worldBox;
        final boolean nsfw;
        final boolean exposed;
        final String anchorKey;

        BackfillRegion(
                String className,
                String category,
                float confidence,
                BBox worldBox,
                boolean nsfw,
                boolean exposed,
                String anchorKey) {
            this.className = safeMetadata(className, "className");
            this.category = safeMetadata(category, "category");
            if (worldBox == null || worldBox.getArea() <= 0L) {
                throw new IllegalArgumentException("worldBox must have area");
            }
            if (anchorKey != null && anchorKey.length() > MAX_REGION_METADATA_CHARS) {
                throw new IllegalArgumentException("anchorKey is too long");
            }
            this.confidence = confidence;
            this.worldBox = worldBox;
            this.nsfw = nsfw;
            this.exposed = exposed;
            this.anchorKey = anchorKey;
        }

        String className() { return className; }
        String category() { return category; }
        float confidence() { return confidence; }
        BBox worldBox() { return worldBox; }
        boolean nsfw() { return nsfw; }
        boolean exposed() { return exposed; }
        String anchorKey() { return anchorKey; }
    }
}
