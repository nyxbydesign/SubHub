package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Source-image cache, separate from the legacy event-coordinate cache and display camera. */
final class SpatialRegionCache {
    private final SpatialFrameMap map = new SpatialFrameMap();
    private final Object registrationLock = new Object();
    private final ContentSpaceRegionCache regions = new ContentSpaceRegionCache();
    private final boolean retainTrackingImages;
    private Frame latest;
    private Frame lastRegistered;
    private Frame appliedFrame;

    SpatialRegionCache() { this(false); }
    SpatialRegionCache(boolean retainTrackingImages) { this.retainTrackingImages = retainTrackingImages; }

    Frame register(int[] pixels, int width, int height, int top, int bottom,
            RowMotionObserver.Scope scope, long id, long receipt, boolean motionHint,
            long eventX, long eventY, int viewportWidth, int viewportHeight) {
        synchronized (registrationLock) {
        Frame previous;
        synchronized (this) { previous = lastRegistered; }
        SpatialFrameMap.Result result = map.observe(pixels, width, height, top, bottom,
                scope, id, receipt, motionHint);
        if (previous != null && scope != null && scope.matches(previous.scope)
                && result.status != SpatialFrameMap.Status.INVALID && result.status != SpatialFrameMap.Status.STALE
                && id > previous.id && eventX != previous.eventX) {
            // This registrar estimates vertical translation only. Horizontal events cannot be
            // silently interpreted as a valid unchanged horizontal source-image origin.
            map.clear();
            result = map.observe(pixels, width, height, top, bottom, scope, id, receipt, motionHint);
        }
        Frame frame = new Frame(this, result, scope, id, receipt, eventX, eventY,
                viewportWidth, viewportHeight, height > 0 && scope != null
                ? (int) Math.ceil(top * (double) scope.sourceHeight / height) : 0,
                height > 0 && scope != null
                ? (int) Math.floor(bottom * (double) scope.sourceHeight / height) : 0,
                retainTrackingImages && scope != null && scope.valid()
                        && result.status != SpatialFrameMap.Status.INVALID && result.status != SpatialFrameMap.Status.STALE
                        ? SourcePatchMatcher.Image.copyOf(pixels, width, height) : null);
        // The registration work above never holds the cache lock used by UI scroll queries.
        synchronized (this) {
            if (result.status == SpatialFrameMap.Status.BASELINE) regions.clear();
            boolean newerInvalid = result.status == SpatialFrameMap.Status.INVALID && scope != null
                    && scope.valid() && (latest == null || latest.scope != null
                    && (scope.captureEpoch > latest.scope.captureEpoch
                    || scope.captureEpoch == latest.scope.captureEpoch && scope.document > latest.scope.document
                    || scope.matches(latest.scope) && id > latest.id && receipt >= latest.receipt));
            if (result.status != SpatialFrameMap.Status.STALE
                    && (result.status != SpatialFrameMap.Status.INVALID || newerInvalid)) latest = frame;
            if (result.pose != null) lastRegistered = frame;
        }
        return frame;
        }
    }

    synchronized Frame latest() { return latest; }

    synchronized boolean motionHintForReference(RowMotionObserver.Scope scope, long lastEvent, long receipt) {
        if (lastEvent <= 0 || lastEvent > receipt) return false;
        return receipt - lastEvent < 750 || lastRegistered != null
                && lastRegistered.scope.matches(scope) && lastEvent >= lastRegistered.receipt;
    }

    /** Called only after a cache replacement was delivered to the overlay view, not at inference. */
    synchronized void markApplied(Frame frame, long now, int admittedCacheRegions) {
        appliedFrame = admittedCacheRegions > 0 && fresh(frame, now) ? frame : null;
    }

    synchronized boolean retainAppliedCoverage(Frame frame, long now) {
        return compatible(frame) && frame == latest && appliedFrame != null
                && appliedFrame.scope.matches(frame.scope)
                && appliedFrame.result.pose.mapGeneration == frame.result.pose.mapGeneration
                && now >= frame.receipt && now - frame.receipt > 750
                && now >= appliedFrame.receipt && now - appliedFrame.receipt <= 6000;
    }

    synchronized long appliedFrameId() { return appliedFrame == null ? -1 : appliedFrame.id; }

    synchronized List<Detection> revalidatePresentation(Frame frame, long now,
            List<Detection> cached, List<Detection> combined) {
        if (fresh(frame, now)) return combined;
        // Preserve independently supplied current quality coverage, but never a stale queued cache.
        List<Detection> result = new ArrayList<>(combined);
        result.removeAll(cached);
        return result;
    }

    private boolean fresh(Frame frame, long now) {
        return compatible(frame) && frame == latest && now >= frame.receipt && now - frame.receipt <= 750;
    }

    synchronized ContentSpaceRegionCache.Update observeSource(Frame frame, long now,
            boolean completeScene, List<ContentSpaceRegionCache.Observation> observations) {
        return observeSourceWithStats(frame, now, completeScene, observations).update;
    }

    synchronized WriteResult observeSourceWithStats(Frame frame, long now,
            boolean completeScene, List<ContentSpaceRegionCache.Observation> observations) {
        if (!compatible(frame) || now < frame.receipt || now - frame.receipt > 2500) {
            return new WriteResult(ContentSpaceRegionCache.Update.EMPTY, false, 0, 0, 0, 0, 0);
        }
        int input = 0, cropRejected = 0, unconfirmed = 0, faces = 0, faceCropRejected = 0;
        List<ContentSpaceRegionCache.Observation> scoped = new ArrayList<>();
        if (observations != null) for (ContentSpaceRegionCache.Observation observation : observations) {
            if (observation == null || observation.category == null || observation.category.startsWith("text_")) continue;
            input++;
            boolean face = "FACE_FEMALE".equals(observation.className) || "FACE_MALE".equals(observation.className);
            if (face) faces++;
            if (!inside(frame, observation.screenBox)) {
                cropRejected++;
                if (face) faceCropRejected++;
                continue;
            }
            if (!observation.cacheable()) unconfirmed++;
            // The box is in a registered source image, not an exact-time display coordinate basis.
            scoped.add(copy(observation, observation.screenBox));
        }
        ContentSpaceRegionCache.Update update = regions.observeCommittedScene(frame.scope.document, key(frame), now, 0,
                Math.round(frame.result.pose.sourceY), frame.scope.sourceWidth, frame.scope.sourceHeight,
                frame.scope.sourceWidth, frame.scope.sourceHeight, completeScene && frame == latest, scoped);
        return new WriteResult(update, true, input, cropRejected, unconfirmed, faces, faceCropRejected);
    }

    synchronized List<Detection> querySource(Frame frame, long now) {
        // An older fast result must not restore cache output after a newer unregistered image.
        if (!fresh(frame, now)) return Collections.emptyList();
        List<Detection> found = regions.queryNearAsScreenDetections(frame.scope.document, key(frame),
                now, 0, Math.round(frame.result.pose.sourceY), frame.scope.sourceWidth,
                frame.scope.sourceHeight, frame.scope.sourceWidth, frame.scope.sourceHeight);
        List<Detection> scoped = new ArrayList<>();
        for (Detection detection : found) if (inside(frame, detection.getBox())) scoped.add(detection);
        return scoped;
    }

    synchronized int size() { return regions.size(); }

    void clear() {
        synchronized (registrationLock) {
            synchronized (this) { latest = lastRegistered = appliedFrame = null; regions.clear(); map.clear(); }
        }
    }

    private boolean compatible(Frame frame) {
        return frame != null && frame.owner == this && latest != null
                && frame.result.pose != null && latest.result.pose != null
                && frame.scope.matches(latest.scope)
                && frame.result.pose.mapGeneration == latest.result.pose.mapGeneration
                && frame.viewportWidth == latest.viewportWidth && frame.viewportHeight == latest.viewportHeight
                && frame.viewportWidth > 0 && frame.viewportHeight > 0;
    }

    private static String key(Frame frame) { return "spatial:" + frame.result.pose.mapGeneration; }

    private static boolean inside(Frame frame, BBox box) {
        return box != null && box.getArea() > 0 && box.getX() >= 0 && box.getY() >= frame.top
                && (long) box.getX() + box.getWidth() <= frame.scope.sourceWidth
                && (long) box.getY() + box.getHeight() <= frame.bottom;
    }

    /** Undo only the existing event-tail reprojection; never infer display time from registration. */
    static List<ContentSpaceRegionCache.Observation> sourceObservations(Frame frame,
            long currentEventX, long currentEventY, List<ContentSpaceRegionCache.Observation> observations) {
        if (frame == null || frame.result.pose == null || frame.viewportWidth <= 0
                || frame.viewportHeight <= 0) return Collections.emptyList();
        double dx = ((double) currentEventX - frame.eventX) * frame.scope.sourceWidth / frame.viewportWidth;
        double dy = ((double) currentEventY - frame.eventY) * frame.scope.sourceHeight / frame.viewportHeight;
        List<ContentSpaceRegionCache.Observation> result = new ArrayList<>();
        for (ContentSpaceRegionCache.Observation observation : observations) {
            if (observation == null || observation.screenBox == null) continue;
            BBox box = observation.screenBox;
            long x = Math.round(box.getX() + dx), y = Math.round(box.getY() + dy);
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE
                    || y > Integer.MAX_VALUE) continue;
            result.add(copy(observation, new BBox((int) x, (int) y, box.getWidth(), box.getHeight())));
        }
        return result;
    }

    private static ContentSpaceRegionCache.Observation copy(ContentSpaceRegionCache.Observation value, BBox box) {
        return new ContentSpaceRegionCache.Observation(value.liveTrackId, value.className, value.category,
                value.confidence, box, value.nsfw, value.exposed, value.framesTracked, value.framesMissing,
                value.qualityConfirmed, value.anchorKey, value.deferUntilDeparture);
    }

    static final class WriteResult {
        final ContentSpaceRegionCache.Update update;
        final boolean known;
        final int input, cropRejected, unconfirmed, faces, faceCropRejected;
        WriteResult(ContentSpaceRegionCache.Update update, boolean known, int input, int cropRejected,
                int unconfirmed, int faces, int faceCropRejected) {
            this.update = update; this.known = known; this.input = input; this.cropRejected = cropRejected;
            this.unconfirmed = unconfirmed; this.faces = faces; this.faceCropRejected = faceCropRejected;
        }
    }

    static final class Frame {
        private final SpatialRegionCache owner;
        final SpatialFrameMap.Result result;
        final RowMotionObserver.Scope scope;
        final long id, receipt, eventX, eventY;
        final int viewportWidth, viewportHeight, top, bottom;
        final SourcePatchMatcher.Image trackingImage;
        boolean sameMapAs(Frame other) {
            return sameScopeAs(other) && result.pose != null && other.result.pose != null
                    && result.pose.mapGeneration == other.result.pose.mapGeneration;
        }
        boolean sameScopeAs(Frame other) {
            return other != null && owner == other.owner
                    && scope != null && scope.matches(other.scope)
                    && viewportWidth > 0 && viewportHeight > 0
                    && viewportWidth == other.viewportWidth && viewportHeight == other.viewportHeight
                    && top == other.top && bottom == other.bottom;
        }
        private Frame(SpatialRegionCache owner, SpatialFrameMap.Result result, RowMotionObserver.Scope scope,
                long id, long receipt, long eventX, long eventY, int viewportWidth, int viewportHeight,
                int top, int bottom, SourcePatchMatcher.Image trackingImage) {
            this.owner = owner; this.result = result; this.scope = scope; this.receipt = receipt;
            this.id = id;
            this.eventX = eventX; this.eventY = eventY;
            this.viewportWidth = viewportWidth; this.viewportHeight = viewportHeight;
            this.top = top; this.bottom = bottom;
            this.trackingImage = trackingImage;
        }
    }
}
