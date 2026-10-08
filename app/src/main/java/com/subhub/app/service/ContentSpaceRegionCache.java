package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.RenderSourceReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bounded render-only memory for confirmed regions which leave and re-enter a scroll viewport.
 *
 * <p>The cache deliberately stores no pixels. Entries live in fixed parallel arrays, which keeps
 * the steady-state representation small and avoids a boxed map key for every region. A primitive
 * vertical bucket index narrows a near-viewport lookup before the (small) horizontal check. The
 * public query method still has to materialize {@link Detection} values for its caller, but it
 * does not allocate query geometry, map iterators, or per-query anchor strings.</p>
 */
final class ContentSpaceRegionCache {
    static final int MAX_ENTRIES = 2_048;
    static final int MAX_QUERY_RESULTS = 24;
    static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    static final long VISUAL_TTL_MS = 30L * 60L * 1_000L;
    static final long ANCHORED_TEXT_TTL_MS = 60L * 60L * 1_000L;

    private static final int REQUIRED_IN_VIEW_CONTRADICTIONS = 2;
    private static final float MATCH_IOU = 0.28f;

    /*
     * A world-space bucket is intentionally coarse. It keeps the table compact while making a
     * normal phone viewport touch only a handful of buckets. Very tall boxes fall back to the
     * bounded broad-phase list instead of making the index unbounded.
     */
    private static final int VERTICAL_BUCKET_SIZE = 512;
    private static final int MAX_BUCKETS_PER_ENTRY = 8;
    private static final int BUCKET_TABLE_CAPACITY = 1 << 12;
    private static final int INDEX_NODE_CAPACITY = MAX_ENTRIES * MAX_BUCKETS_PER_ENTRY;

    /* Conservative Java-heap estimate for dynamic metadata, not a pixel buffer size. */
    private static final int ENTRY_FIXED_METADATA_BYTES = 80;
    private static final int RENDER_SOURCE_REFERENCE_METADATA_BYTES = 128;
    private static final int STRING_OBJECT_BYTES = 24;
    private static final int STRING_CHAR_BYTES = 2;
    private static final int MAX_METADATA_STRING_CHARS = 128;
    private static final int MAX_SURFACE_KEY_CHARS = 512;

    private final boolean[] used = new boolean[MAX_ENTRIES];
    private final int[] liveTrackIds = new int[MAX_ENTRIES];
    private final String[] classNames = new String[MAX_ENTRIES];
    private final String[] categories = new String[MAX_ENTRIES];
    private final String[] sourceAnchors = new String[MAX_ENTRIES];
    private final String[] renderAnchors = new String[MAX_ENTRIES];
    private final float[] confidences = new float[MAX_ENTRIES];
    private final int[] worldXs = new int[MAX_ENTRIES];
    private final int[] worldYs = new int[MAX_ENTRIES];
    private final int[] worldWidths = new int[MAX_ENTRIES];
    private final int[] worldHeights = new int[MAX_ENTRIES];
    private final boolean[] nsfw = new boolean[MAX_ENTRIES];
    private final boolean[] exposed = new boolean[MAX_ENTRIES];
    private final boolean[] dormantUntilDeparture = new boolean[MAX_ENTRIES];
    private final boolean[] qualityConfirmedEntries = new boolean[MAX_ENTRIES];
    private final long[] lastEvidenceSourceUptime = new long[MAX_ENTRIES];
    private final RenderSourceReference[] renderSourceReferences = new RenderSourceReference[MAX_ENTRIES];
    private final long[] lastSeenUptimeMillis = new long[MAX_ENTRIES];
    private final int[] inViewContradictions = new int[MAX_ENTRIES];
    private final int[] entryMetadataBytes = new int[MAX_ENTRIES];

    /* Access order is maintained without LinkedHashMap nodes or boxed Long keys. */
    private final int[] lruPrevious = new int[MAX_ENTRIES];
    private final int[] lruNext = new int[MAX_ENTRIES];
    private int lruHead = -1;
    private int lruTail = -1;
    private int entryCount;

    /* A scene stamp replaces the old per-scene ArrayList<Long> matchedEntries allocation. */
    private final int[] observedSceneStamp = new int[MAX_ENTRIES];
    private int sceneStamp = 1;

    /* Candidate marks deduplicate an entry that spans several vertical buckets. */
    private final int[] candidateStamp = new int[MAX_ENTRIES];
    private final int[] queryCandidateSlots = new int[MAX_ENTRIES];
    private int candidateGeneration = 1;

    /* Primitive vertical index: bucket heads point to doubly-linked node records. */
    private final byte[] bucketState = new byte[BUCKET_TABLE_CAPACITY];
    private final int[] bucketKeys = new int[BUCKET_TABLE_CAPACITY];
    private final int[] bucketHeads = new int[BUCKET_TABLE_CAPACITY];
    private final int[] entryIndexHead = new int[MAX_ENTRIES];
    private final int[] nodeEntrySlots = new int[INDEX_NODE_CAPACITY];
    private final int[] nodeBucketSlots = new int[INDEX_NODE_CAPACITY];
    private final int[] nodeEntryNext = new int[INDEX_NODE_CAPACITY];
    private final int[] nodeNext = new int[INDEX_NODE_CAPACITY];
    private final int[] freeNodes = new int[INDEX_NODE_CAPACITY];
    private int freeNodeCount;
    /* Reused conversion scratch; all public methods are synchronized. */
    private final int[] convertedWorld = new int[4];

    private long nextEntryId = 1L;
    private int sourceWidth;
    private int sourceHeight;
    private int viewportWidth;
    private int viewportHeight;
    private long activeDocumentEpoch;
    private String activeSurfaceKey;
    private boolean contextSet;
    private int metadataByteCount;

    ContentSpaceRegionCache() {
        Arrays.fill(bucketHeads, -1);
        Arrays.fill(entryIndexHead, -1);
        Arrays.fill(renderSourceReferences, RenderSourceReference.UNKNOWN);
        resetNodePool();
    }

    synchronized Update observeCommittedScene(
            long documentEpoch,
            String surfaceKey,
            long nowUptimeMillis,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int currentViewportWidth,
            int currentViewportHeight,
            boolean unifiedScene,
            List<Observation> observations) {
        return observeCommittedScene(documentEpoch, surfaceKey, nowUptimeMillis,
                cameraX, cameraY, sourceWidth, sourceHeight, currentViewportWidth,
                currentViewportHeight, Evidence.full(unifiedScene ? Evidence.COMPLETE : Evidence.NONE,
                        nowUptimeMillis), observations);
    }

    synchronized Update observeCommittedScene(
            long documentEpoch, String surfaceKey, long nowUptimeMillis,
            long cameraX, long cameraY, int sourceWidth, int sourceHeight,
            int currentViewportWidth, int currentViewportHeight,
            Evidence evidence, List<Observation> observations) {
        if (evidence == null || evidence.sourceUptime < 0
                || evidence.sourceUptime > nowUptimeMillis) return Update.EMPTY;
        if (evidence.lane == Evidence.QUALITY && (evidence.inspected.getX() < 0
                || evidence.inspected.getY() < 0 || evidence.inspected.getRight() > sourceWidth
                || evidence.inspected.getBottom() > sourceHeight)) return Update.EMPTY;
        String safeSurface = safeSurface(surfaceKey);
        if (safeSurface.isEmpty()) return Update.EMPTY;
        if (!ensureGeometry(sourceWidth, sourceHeight,
                currentViewportWidth, currentViewportHeight)) {
            return new Update(0, 0, 0, true);
        }

        long now = Math.max(0L, nowUptimeMillis);
        int evicted = activateContext(documentEpoch, safeSurface);
        evicted += evictExpired(now);
        int stamp = nextSceneStamp();
        int inserted = 0;
        int updated = 0;

        if (observations != null) {
            for (Observation observation : observations) {
                if (observation == null || observation.screenBox == null
                        || observation.framesMissing != 0) continue;
                String className = safeMetadataString(observation.className);
                String category = safeMetadataString(observation.category);
                String sourceAnchor = safeAnchor(observation.anchorKey);
                if (className == null || category == null
                        || (isAnchoredText(category) && sourceAnchor == null)) continue;
                if (!convertScreenToWorld(observation.screenBox, cameraX, cameraY,
                        this.sourceWidth, this.sourceHeight,
                        this.viewportWidth, this.viewportHeight, convertedWorld)) continue;
                int worldX = convertedWorld[0];
                int worldY = convertedWorld[1];
                int worldWidth = convertedWorld[2];
                int worldHeight = convertedWorld[3];
                int match = findMatch(documentEpoch, safeSurface, observation,
                        category, worldX, worldY, worldWidth, worldHeight);
                if (match >= 0) {
                    if (evidence.sourceUptime < lastEvidenceSourceUptime[match]
                            || evidence.sourceUptime == lastEvidenceSourceUptime[match]
                            && observedSceneStamp[match] != stamp) continue;
                    observedSceneStamp[match] = stamp;
                    lastEvidenceSourceUptime[match] = evidence.sourceUptime;
                    inViewContradictions[match] = 0;
                    // A fresh unpromoted hit can contradict a miss, but cannot create a
                    // durable region, move its geometry or renew its positive lifetime.
                    if (!observation.cacheable()) continue;
                    int newBytes = estimateEntryBytes(className, category,
                            sourceAnchor, renderAnchors[match], observation.renderSourceReference);
                    int delta = newBytes - entryMetadataBytes[match];
                    int roomEvictions = ensureAdditionalMetadataRoom(delta, match);
                    if (roomEvictions < 0) continue;
                    evicted += roomEvictions;
                    updateEntry(match, observation, className, category, sourceAnchor,
                            worldX, worldY, worldWidth, worldHeight, now, newBytes);
                    updated++;
                    continue;
                }

                if (!observation.cacheable()) continue;

                int newBytes = estimateEntryBytes(className, category, sourceAnchor, null, observation.renderSourceReference);
                int roomEvictions = ensureInsertRoom(newBytes);
                if (roomEvictions < 0) continue;
                evicted += roomEvictions;
                int slot = findUnusedSlot();
                if (slot < 0) continue; // Defensive: ensureInsertRoom should make one available.
                long entryId = takeEntryId();
                String renderAnchor = "world-cache:" + entryId;
                /* The generated anchor is included in the budget before retaining it. */
                int exactBytes = estimateEntryBytes(className, category, sourceAnchor, renderAnchor, observation.renderSourceReference);
                if (exactBytes > newBytes) {
                    int extra = ensureAdditionalMetadataRoom(exactBytes - newBytes, -1);
                    if (extra < 0) continue;
                    evicted += extra;
                }
                insertEntry(slot, entryId, renderAnchor, observation, className, category,
                        sourceAnchor, worldX, worldY, worldWidth, worldHeight, now, exactBytes);
                observedSceneStamp[slot] = stamp;
                qualityConfirmedEntries[slot] = observation.qualityConfirmed;
                lastEvidenceSourceUptime[slot] = evidence.sourceUptime;
                inserted++;
            }
        }

        if (evidence.lane != Evidence.NONE) {
            long viewLeft = cameraX;
            long viewTop = cameraY;
            long viewRight = safeAdd(cameraX, viewportWidth);
            long viewBottom = safeAdd(cameraY, viewportHeight);
            int[] inspectedWorld = new int[4];
            BBox inspected = evidence.inspected == null
                    ? new BBox(0, 0, sourceWidth, sourceHeight) : evidence.inspected;
            boolean knownCoverage = convertScreenToWorld(inspected, cameraX, cameraY,
                    this.sourceWidth, this.sourceHeight, viewportWidth, viewportHeight, inspectedWorld);
            for (int slot = 0; slot < MAX_ENTRIES; slot++) {
                if (!used[slot] || dormantUntilDeparture[slot]
                        || observedSceneStamp[slot] == stamp
                        || evidence.sourceUptime <= lastEvidenceSourceUptime[slot]
                        || evidence.lane == Evidence.FAST && qualityConfirmedEntries[slot]
                        || evidence.lane == Evidence.QUALITY && !qualityConfirmedEntries[slot]
                        || !knownCoverage
                        || evidence.lane == Evidence.QUALITY && !fullyCovered(slot, inspectedWorld)
                        || !intersects(worldXs[slot], worldYs[slot], worldWidths[slot],
                        worldHeights[slot], viewLeft, viewTop, viewRight, viewBottom)) continue;
                lastEvidenceSourceUptime[slot] = evidence.sourceUptime;
                inViewContradictions[slot]++;
                if (inViewContradictions[slot] >= REQUIRED_IN_VIEW_CONTRADICTIONS) {
                    removeEntry(slot);
                    evicted++;
                }
            }
        }
        return new Update(inserted, updated, evicted, false);
    }

    private boolean fullyCovered(int slot, int[] coverage) {
        return worldXs[slot] >= coverage[0] && worldYs[slot] >= coverage[1]
                && (long) worldXs[slot] + worldWidths[slot] <= (long) coverage[0] + coverage[2]
                && (long) worldYs[slot] + worldHeights[slot] <= (long) coverage[1] + coverage[3];
    }

    /** Negative evidence describes the model and actual inspected crop, not render cadence. */
    static final class Evidence {
        static final int NONE = 0, FAST = 1, QUALITY = 2, COMPLETE = 3;
        final int lane;
        final long sourceUptime;
        final BBox inspected;

        private Evidence(int lane, long sourceUptime, BBox inspected) {
            this.lane = lane; this.sourceUptime = sourceUptime; this.inspected = inspected;
        }

        static Evidence full(int lane, long sourceUptime) {
            if (lane != NONE && lane != FAST && lane != COMPLETE) {
                throw new IllegalArgumentException("Full evidence must name a valid lane");
            }
            return new Evidence(lane, sourceUptime, null);
        }

        static Evidence quality(long sourceUptime, BBox inspected) {
            if (inspected == null || inspected.getArea() <= 0) {
                throw new IllegalArgumentException("Quality evidence needs an inspected crop");
            }
            return new Evidence(QUALITY, sourceUptime, inspected);
        }
    }

    synchronized List<Detection> queryNearAsScreenDetections(
            long documentEpoch,
            String surfaceKey,
            long nowUptimeMillis,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int currentViewportWidth,
            int currentViewportHeight) {
        String safeSurface = safeSurface(surfaceKey);
        if (safeSurface.isEmpty()) return Collections.emptyList();
        if (!geometryMatches(sourceWidth, sourceHeight,
                currentViewportWidth, currentViewportHeight)) {
            /* Geometry changes are a hard fence, including query-only rotation changes. */
            if (viewportWidth != 0 || viewportHeight != 0) {
                setGeometry(sourceWidth, sourceHeight, currentViewportWidth, currentViewportHeight);
                clearEntries();
            }
            return Collections.emptyList();
        }
        activateContext(documentEpoch, safeSurface);
        evictExpired(Math.max(0L, nowUptimeMillis));
        if (entryCount == 0) return Collections.emptyList();
        armDormantAfterViewportDeparture(cameraX, cameraY);

        long horizontalMargin = Math.max(viewportWidth / 2L, 1L);
        long verticalMargin = Math.max(viewportHeight * 2L, 1L);
        long nearLeft = safeSubtract(cameraX, horizontalMargin);
        long nearTop = safeSubtract(cameraY, verticalMargin);
        long nearRight = safeAdd(cameraX, (long) viewportWidth + horizontalMargin);
        long nearBottom = safeAdd(cameraY, (long) viewportHeight + verticalMargin);

        int stamp = nextCandidateGeneration();
        int candidateCount = 0;
        long minBucketLong = floorDiv(nearTop, VERTICAL_BUCKET_SIZE);
        long maxBucketLong = floorDiv(Math.max(nearTop, nearBottom - 1L), VERTICAL_BUCKET_SIZE);
        if (maxBucketLong - minBucketLong + 1L <= BUCKET_TABLE_CAPACITY) {
            for (long bucket = minBucketLong; bucket <= maxBucketLong; bucket++) {
                int bucketSlot = findBucketSlot(saturatingInt(bucket), false);
                if (bucketSlot < 0) continue;
                for (int node = bucketHeads[bucketSlot]; node >= 0; node = nodeNext[node]) {
                    int slot = nodeEntrySlots[node];
                    if (candidateStamp[slot] == stamp) continue;
                    candidateStamp[slot] = stamp;
                    if (!used[slot] || suppressUntilViewportDeparture(slot, cameraX, cameraY)
                            || !intersects(worldXs[slot], worldYs[slot], worldWidths[slot],
                            worldHeights[slot], nearLeft, nearTop, nearRight, nearBottom)) continue;
                    queryCandidateSlots[candidateCount++] = slot;
                }
            }
        } else {
            /* A malformed/huge viewport should remain bounded and correct, not loop forever. */
            for (int slot = 0; slot < MAX_ENTRIES; slot++) {
                if (candidateStamp[slot] == stamp) continue;
                candidateStamp[slot] = stamp;
                if (!used[slot] || suppressUntilViewportDeparture(slot, cameraX, cameraY)
                        || !intersects(worldXs[slot], worldYs[slot], worldWidths[slot],
                        worldHeights[slot], nearLeft, nearTop, nearRight, nearBottom)) continue;
                queryCandidateSlots[candidateCount++] = slot;
            }
        }

        /* Entries too tall for the bucket index are a bounded broad-phase fallback. */
        for (int slot = 0; slot < MAX_ENTRIES; slot++) {
            if (!used[slot] || candidateStamp[slot] == stamp || !isBroadPhase(slot)
                    || suppressUntilViewportDeparture(slot, cameraX, cameraY)
                    || !intersects(worldXs[slot], worldYs[slot], worldWidths[slot],
                    worldHeights[slot], nearLeft, nearTop, nearRight, nearBottom)) continue;
            candidateStamp[slot] = stamp;
            queryCandidateSlots[candidateCount++] = slot;
        }

        // The result cap must never let offscreen prefetch displace an entry which is already
        // visible. The first pass emits current-viewport coverage; the second fills spare slots
        // with near history so the renderer can move it in without another inference pass.
        long viewLeft = cameraX;
        long viewTop = cameraY;
        long viewRight = safeAdd(cameraX, viewportWidth);
        long viewBottom = safeAdd(cameraY, viewportHeight);
        ArrayList<Detection> result = null;
        for (int visibilityPass = 0; visibilityPass < 2; visibilityPass++) {
            boolean wantVisible = visibilityPass == 0;
            for (int index = 0; index < candidateCount; index++) {
                int slot = queryCandidateSlots[index];
                boolean visible = intersects(worldXs[slot], worldYs[slot], worldWidths[slot],
                        worldHeights[slot], viewLeft, viewTop, viewRight, viewBottom);
                if (visible != wantVisible) continue;
                if (result == null) result = new ArrayList<>(MAX_QUERY_RESULTS);
                result.add(toScreenDetection(slot, cameraX, cameraY));
                touch(slot);
                if (result.size() >= MAX_QUERY_RESULTS) return immutableResult(result);
            }
        }
        return result == null ? Collections.emptyList() : immutableResult(result);
    }

    synchronized int clear() {
        int removed = entryCount;
        clearEntries();
        sourceWidth = 0;
        sourceHeight = 0;
        viewportWidth = 0;
        viewportHeight = 0;
        activeDocumentEpoch = 0L;
        activeSurfaceKey = null;
        contextSet = false;
        metadataByteCount = 0;
        return removed;
    }

    synchronized int size() { return entryCount; }

    /** Conservative live metadata estimate; it never exceeds {@link #MAX_METADATA_BYTES}. */
    synchronized int metadataBytes() { return metadataByteCount; }

    static BBox screenToWorld(
            BBox screen,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        if (screen == null) return null;
        double scaleX = Math.max(1, viewportWidth) / (double) Math.max(1, sourceWidth);
        double scaleY = Math.max(1, viewportHeight) / (double) Math.max(1, sourceHeight);
        return new BBox(
                saturatingInt(Math.round(screen.getX() * scaleX + cameraX)),
                saturatingInt(Math.round(screen.getY() * scaleY + cameraY)),
                Math.max(1, saturatingInt(Math.round(screen.getWidth() * scaleX))),
                Math.max(1, saturatingInt(Math.round(screen.getHeight() * scaleY))));
    }

    static BBox worldToScreen(
            BBox world,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        if (world == null) return null;
        double scaleX = Math.max(1, sourceWidth) / (double) Math.max(1, viewportWidth);
        double scaleY = Math.max(1, sourceHeight) / (double) Math.max(1, viewportHeight);
        return new BBox(
                saturatingInt(Math.round(((double) world.getX() - cameraX) * scaleX)),
                saturatingInt(Math.round(((double) world.getY() - cameraY) * scaleY)),
                Math.max(1, saturatingInt(Math.round(world.getWidth() * scaleX))),
                Math.max(1, saturatingInt(Math.round(world.getHeight() * scaleY))));
    }

    private Detection toScreenDetection(int slot, long cameraX, long cameraY) {
        double scaleX = sourceWidth / (double) viewportWidth;
        double scaleY = sourceHeight / (double) viewportHeight;
        BBox screen = new BBox(
                saturatingInt(Math.round(((double) worldXs[slot] - cameraX) * scaleX)),
                saturatingInt(Math.round(((double) worldYs[slot] - cameraY) * scaleY)),
                Math.max(1, saturatingInt(Math.round(worldWidths[slot] * scaleX))),
                Math.max(1, saturatingInt(Math.round(worldHeights[slot] * scaleY))));
        return new Detection(
                classNames[slot], categories[slot], confidences[slot], screen,
                nsfw[slot], exposed[slot],
                Detection.ObservationSource.EXACT_GEOMETRY,
                Detection.GeometryQuality.EXACT, renderAnchors[slot])
                .withRenderSourceReference(renderSourceReferences[slot]);
    }

    private List<Detection> immutableResult(ArrayList<Detection> result) {
        return Collections.unmodifiableList(result);
    }

    private boolean ensureGeometry(int requestedSourceWidth, int requestedSourceHeight,
            int requestedViewportWidth, int requestedViewportHeight) {
        int safeSourceWidth = Math.max(1, requestedSourceWidth);
        int safeSourceHeight = Math.max(1, requestedSourceHeight);
        int safeViewportWidth = Math.max(1, requestedViewportWidth);
        int safeViewportHeight = Math.max(1, requestedViewportHeight);
        if (viewportWidth == 0 || viewportHeight == 0) {
            setGeometry(safeSourceWidth, safeSourceHeight, safeViewportWidth, safeViewportHeight);
            return true;
        }
        if (sourceWidth == safeSourceWidth && sourceHeight == safeSourceHeight
                && viewportWidth == safeViewportWidth && viewportHeight == safeViewportHeight) {
            return true;
        }
        clearEntries();
        setGeometry(safeSourceWidth, safeSourceHeight, safeViewportWidth, safeViewportHeight);
        return false;
    }

    private boolean geometryMatches(int requestedSourceWidth, int requestedSourceHeight,
            int requestedViewportWidth, int requestedViewportHeight) {
        return sourceWidth == Math.max(1, requestedSourceWidth)
                && sourceHeight == Math.max(1, requestedSourceHeight)
                && viewportWidth == Math.max(1, requestedViewportWidth)
                && viewportHeight == Math.max(1, requestedViewportHeight);
    }

    private void setGeometry(int requestedSourceWidth, int requestedSourceHeight,
            int requestedViewportWidth, int requestedViewportHeight) {
        sourceWidth = Math.max(1, requestedSourceWidth);
        sourceHeight = Math.max(1, requestedSourceHeight);
        viewportWidth = Math.max(1, requestedViewportWidth);
        viewportHeight = Math.max(1, requestedViewportHeight);
    }

    private int activateContext(long documentEpoch, String surfaceKey) {
        int removed = 0;
        if (!contextSet) {
            contextSet = true;
        } else if (activeDocumentEpoch != documentEpoch
                || !activeSurfaceKey.equals(surfaceKey)) {
            removed = entryCount;
            clearEntries();
        }
        activeDocumentEpoch = documentEpoch;
        if (activeSurfaceKey == null || !activeSurfaceKey.equals(surfaceKey)) {
            if (activeSurfaceKey != null) metadataByteCount -= estimateStringBytes(activeSurfaceKey);
            activeSurfaceKey = surfaceKey;
            metadataByteCount += estimateStringBytes(activeSurfaceKey);
        }
        return removed;
    }

    private int evictExpired(long nowUptimeMillis) {
        int removed = 0;
        for (int slot = 0; slot < MAX_ENTRIES; slot++) {
            if (!used[slot]) continue;
            long lastSeen = lastSeenUptimeMillis[slot];
            long age = nowUptimeMillis >= lastSeen ? nowUptimeMillis - lastSeen : 0L;
            long ttl = isAnchoredText(categories[slot]) && sourceAnchors[slot] != null
                    ? ANCHORED_TEXT_TTL_MS : VISUAL_TTL_MS;
            if (age > ttl) {
                removeEntry(slot);
                removed++;
            }
        }
        return removed;
    }

    private int findMatch(long documentEpoch, String surfaceKey, Observation observation,
            String category, int worldX, int worldY, int worldWidth, int worldHeight) {
        /* Context is activated before this call; the arguments fence future API changes. */
        if (!contextSet || activeDocumentEpoch != documentEpoch
                || activeSurfaceKey == null || !activeSurfaceKey.equals(surfaceKey)) return -1;
        int stamp = nextCandidateGeneration();
        int best = -1;
        float bestIou = 0f;
        long minBucket = floorDiv(worldY, VERTICAL_BUCKET_SIZE);
        long maxBucket = floorDiv(safeAdd(worldY, worldHeight) - 1L, VERTICAL_BUCKET_SIZE);
        if (maxBucket - minBucket + 1L <= MAX_BUCKETS_PER_ENTRY) {
            for (long bucket = minBucket; bucket <= maxBucket; bucket++) {
                int bucketSlot = findBucketSlot(saturatingInt(bucket), false);
                if (bucketSlot < 0) continue;
                for (int node = bucketHeads[bucketSlot]; node >= 0; node = nodeNext[node]) {
                    int candidate = nodeEntrySlots[node];
                    if (candidateStamp[candidate] == stamp) continue;
                    candidateStamp[candidate] = stamp;
                    if (!used[candidate] || !sameFamily(categories[candidate], category)) continue;
                    if (observation.liveTrackId >= 0
                            && liveTrackIds[candidate] == observation.liveTrackId) {
                        touch(candidate);
                        return candidate;
                    }
                    float iou = intersectionOverUnion(worldXs[candidate], worldYs[candidate],
                            worldWidths[candidate], worldHeights[candidate],
                            worldX, worldY, worldWidth, worldHeight);
                    if (iou >= MATCH_IOU && iou > bestIou) {
                        best = candidate;
                        bestIou = iou;
                    }
                }
            }
        } else {
            for (int candidate = 0; candidate < MAX_ENTRIES; candidate++) {
                if (!used[candidate] || !sameFamily(categories[candidate], category)) continue;
                if (observation.liveTrackId >= 0
                        && liveTrackIds[candidate] == observation.liveTrackId) {
                    touch(candidate);
                    return candidate;
                }
                float iou = intersectionOverUnion(worldXs[candidate], worldYs[candidate],
                        worldWidths[candidate], worldHeights[candidate],
                        worldX, worldY, worldWidth, worldHeight);
                if (iou >= MATCH_IOU && iou > bestIou) {
                    best = candidate;
                    bestIou = iou;
                }
            }
        }
        /* A stable tracker identity remains authoritative across a large camera jump. */
        if (observation.liveTrackId >= 0) {
            for (int candidate = 0; candidate < MAX_ENTRIES; candidate++) {
                if (used[candidate] && sameFamily(categories[candidate], category)
                        && liveTrackIds[candidate] == observation.liveTrackId) {
                    touch(candidate);
                    return candidate;
                }
            }
        }
        /* Very tall cached boxes intentionally live outside normal buckets. */
        for (int candidate = 0; candidate < MAX_ENTRIES; candidate++) {
            if (!used[candidate] || candidateStamp[candidate] == stamp || !isBroadPhase(candidate)
                    || !sameFamily(categories[candidate], category)) continue;
            candidateStamp[candidate] = stamp;
            if (observation.liveTrackId >= 0
                    && liveTrackIds[candidate] == observation.liveTrackId) {
                touch(candidate);
                return candidate;
            }
            float iou = intersectionOverUnion(worldXs[candidate], worldYs[candidate],
                    worldWidths[candidate], worldHeights[candidate],
                    worldX, worldY, worldWidth, worldHeight);
            if (iou >= MATCH_IOU && iou > bestIou) {
                best = candidate;
                bestIou = iou;
            }
        }
        if (best >= 0) touch(best);
        return best;
    }

    private void insertEntry(int slot, long entryId, String renderAnchor,
            Observation observation, String className, String category, String sourceAnchor,
            int worldX, int worldY, int worldWidth, int worldHeight,
            long nowUptimeMillis, int bytes) {
        used[slot] = true;
        dormantUntilDeparture[slot] = observation.deferUntilDeparture;
        inViewContradictions[slot] = 0;
        entryCount++;
        liveTrackIds[slot] = observation.liveTrackId;
        confidences[slot] = Float.isNaN(observation.confidence)
                ? 0f : observation.confidence;
        classNames[slot] = className;
        categories[slot] = category;
        sourceAnchors[slot] = sourceAnchor;
        renderAnchors[slot] = renderAnchor;
        entryMetadataBytes[slot] = bytes;
        metadataByteCount += bytes;
        updateEntryValues(slot, observation, worldX, worldY, worldWidth, worldHeight,
                nowUptimeMillis);
        lruPrevious[slot] = lruTail;
        lruNext[slot] = -1;
        if (lruTail >= 0) lruNext[lruTail] = slot;
        else lruHead = slot;
        lruTail = slot;
        addToIndex(slot);
    }

    private void updateEntry(int slot, Observation observation, String className, String category,
            String sourceAnchor, int worldX, int worldY, int worldWidth, int worldHeight,
            long nowUptimeMillis, int bytes) {
        metadataByteCount += bytes - entryMetadataBytes[slot];
        entryMetadataBytes[slot] = bytes;
        classNames[slot] = className;
        categories[slot] = category;
        sourceAnchors[slot] = sourceAnchor;
        removeFromIndex(slot);
        if (observation.liveTrackId >= 0 || liveTrackIds[slot] < 0) {
            liveTrackIds[slot] = observation.liveTrackId;
        }
        dormantUntilDeparture[slot] = dormantUntilDeparture[slot]
                && observation.deferUntilDeparture;
        qualityConfirmedEntries[slot] |= observation.qualityConfirmed;
        updateEntryValues(slot, observation, worldX, worldY, worldWidth, worldHeight,
                nowUptimeMillis);
        inViewContradictions[slot] = 0;
        touch(slot);
        addToIndex(slot);
    }

    private void updateEntryValues(int slot, Observation observation,
            int worldX, int worldY, int worldWidth, int worldHeight,
            long nowUptimeMillis) {
        if (!Float.isNaN(observation.confidence)) {
            confidences[slot] = Math.max(confidences[slot], observation.confidence);
        }
        worldXs[slot] = worldX;
        worldYs[slot] = worldY;
        worldWidths[slot] = worldWidth;
        worldHeights[slot] = worldHeight;
        renderSourceReferences[slot] = observation.renderSourceReference;
        nsfw[slot] = observation.nsfw;
        exposed[slot] = observation.exposed;
        lastSeenUptimeMillis[slot] = nowUptimeMillis;
    }

    private int ensureInsertRoom(int candidateBytes) {
        if (candidateBytes > MAX_METADATA_BYTES) return -1;
        int removed = 0;
        while (entryCount >= MAX_ENTRIES
                || (long) metadataByteCount + candidateBytes > MAX_METADATA_BYTES) {
            int victim = lruHead;
            if (victim < 0) return -1;
            removeEntry(victim);
            removed++;
        }
        return removed;
    }

    private int ensureAdditionalMetadataRoom(int additionalBytes, int protectedSlot) {
        if (additionalBytes <= 0) return 0;
        int removed = 0;
        while ((long) metadataByteCount + additionalBytes > MAX_METADATA_BYTES) {
            int victim = lruHead;
            if (victim == protectedSlot) victim = lruNext[victim];
            if (victim < 0) return -1;
            removeEntry(victim);
            removed++;
        }
        return removed;
    }

    private int findUnusedSlot() {
        for (int slot = 0; slot < MAX_ENTRIES; slot++) if (!used[slot]) return slot;
        return -1;
    }

    private void removeEntry(int slot) {
        if (!used[slot]) return;
        removeFromIndex(slot);
        int previous = lruPrevious[slot];
        int next = lruNext[slot];
        if (previous >= 0) lruNext[previous] = next;
        else lruHead = next;
        if (next >= 0) lruPrevious[next] = previous;
        else lruTail = previous;
        metadataByteCount -= entryMetadataBytes[slot];
        entryMetadataBytes[slot] = 0;
        classNames[slot] = null;
        categories[slot] = null;
        sourceAnchors[slot] = null;
        renderAnchors[slot] = null;
        renderSourceReferences[slot] = RenderSourceReference.UNKNOWN;
        liveTrackIds[slot] = -1;
        confidences[slot] = 0f;
        dormantUntilDeparture[slot] = false;
        inViewContradictions[slot] = 0;
        used[slot] = false;
        entryCount--;
    }

    private int clearEntries() {
        int removed = entryCount;
        for (int slot = 0; slot < MAX_ENTRIES; slot++) {
            classNames[slot] = null;
            categories[slot] = null;
            sourceAnchors[slot] = null;
            renderAnchors[slot] = null;
            renderSourceReferences[slot] = RenderSourceReference.UNKNOWN;
            liveTrackIds[slot] = -1;
            confidences[slot] = 0f;
            dormantUntilDeparture[slot] = false;
            inViewContradictions[slot] = 0;
            used[slot] = false;
            entryMetadataBytes[slot] = 0;
        }
        entryCount = 0;
        lruHead = -1;
        lruTail = -1;
        Arrays.fill(bucketState, (byte) 0);
        Arrays.fill(bucketHeads, -1);
        Arrays.fill(entryIndexHead, -1);
        resetNodePool();
        metadataByteCount = contextSet && activeSurfaceKey != null
                ? estimateStringBytes(activeSurfaceKey) : 0;
        return removed;
    }

    private void touch(int slot) {
        if (!used[slot] || lruTail == slot) return;
        int previous = lruPrevious[slot];
        int next = lruNext[slot];
        if (previous >= 0) lruNext[previous] = next;
        else lruHead = next;
        if (next >= 0) lruPrevious[next] = previous;
        lruPrevious[slot] = lruTail;
        lruNext[slot] = -1;
        if (lruTail >= 0) lruNext[lruTail] = slot;
        lruTail = slot;
    }

    private void addToIndex(int slot) {
        entryIndexHead[slot] = -1;
        long firstBucket = floorDiv(worldYs[slot], VERTICAL_BUCKET_SIZE);
        long lastBucket = floorDiv(safeAdd(worldYs[slot], worldHeights[slot]) - 1L,
                VERTICAL_BUCKET_SIZE);
        long span = lastBucket - firstBucket + 1L;
        if (span <= 0L || span > MAX_BUCKETS_PER_ENTRY || span > INDEX_NODE_CAPACITY
                || span > freeNodeCount) return;
        for (long bucket = firstBucket; bucket <= lastBucket; bucket++) {
            int bucketSlot = findBucketSlot(saturatingInt(bucket), true);
            if (bucketSlot < 0) {
                removeIndexNodes(slot);
                return;
            }
            int node = allocateNode();
            if (node < 0) {
                removeIndexNodes(slot);
                return;
            }
            nodeEntrySlots[node] = slot;
            nodeBucketSlots[node] = bucketSlot;
            nodeEntryNext[node] = entryIndexHead[slot];
            nodeNext[node] = bucketHeads[bucketSlot];
            if (bucketHeads[bucketSlot] >= 0) {
                /* The bucket chain uses nodeNext only; predecessor is found on removal. */
            }
            bucketHeads[bucketSlot] = node;
            entryIndexHead[slot] = node;
        }
    }

    private void removeFromIndex(int slot) {
        removeIndexNodes(slot);
    }

    private void removeIndexNodes(int slot) {
        int node = entryIndexHead[slot];
        while (node >= 0) {
            int nextEntryNode = nodeEntryNext[node];
            int bucketSlot = nodeBucketSlots[node];
            int bucketPrevious = findBucketNodePrevious(bucketSlot, node);
            int bucketNext = nodeNext[node];
            if (bucketPrevious >= 0) nodeNext[bucketPrevious] = bucketNext;
            else if (bucketHeads[bucketSlot] == node) bucketHeads[bucketSlot] = bucketNext;
            if (bucketHeads[bucketSlot] < 0 && bucketState[bucketSlot] == 1) {
                bucketState[bucketSlot] = 2; // tombstone keeps open-addressing probes correct
            }
            releaseNode(node);
            node = nextEntryNode;
        }
        entryIndexHead[slot] = -1;
    }

    private int findBucketNodePrevious(int bucketSlot, int targetNode) {
        int previous = -1;
        for (int node = bucketHeads[bucketSlot]; node >= 0; node = nodeNext[node]) {
            if (node == targetNode) return previous;
            previous = node;
        }
        return -1;
    }

    private boolean isBroadPhase(int slot) {
        long firstBucket = floorDiv(worldYs[slot], VERTICAL_BUCKET_SIZE);
        long lastBucket = floorDiv(safeAdd(worldYs[slot], worldHeights[slot]) - 1L,
                VERTICAL_BUCKET_SIZE);
        return lastBucket - firstBucket + 1L > MAX_BUCKETS_PER_ENTRY
                || entryIndexHead[slot] < 0;
    }

    private int allocateNode() {
        if (freeNodeCount == 0) return -1;
        return freeNodes[--freeNodeCount];
    }

    private void releaseNode(int node) {
        freeNodes[freeNodeCount++] = node;
    }

    private void resetNodePool() {
        freeNodeCount = INDEX_NODE_CAPACITY;
        for (int node = 0; node < INDEX_NODE_CAPACITY; node++) freeNodes[node] = node;
    }

    private int findBucketSlot(int key, boolean create) {
        int index = mixBucketKey(key) & (BUCKET_TABLE_CAPACITY - 1);
        int firstTombstone = -1;
        for (int probe = 0; probe < BUCKET_TABLE_CAPACITY; probe++) {
            byte state = bucketState[index];
            if (state == 0) {
                if (!create) return -1;
                int target = firstTombstone >= 0 ? firstTombstone : index;
                bucketState[target] = 1;
                bucketKeys[target] = key;
                bucketHeads[target] = -1;
                return target;
            }
            if (state == 1 && bucketKeys[index] == key) return index;
            if (state == 2 && firstTombstone < 0) firstTombstone = index;
            index = (index + 1) & (BUCKET_TABLE_CAPACITY - 1);
        }
        if (create && firstTombstone >= 0) {
            bucketState[firstTombstone] = 1;
            bucketKeys[firstTombstone] = key;
            bucketHeads[firstTombstone] = -1;
            return firstTombstone;
        }
        return -1;
    }

    private static int mixBucketKey(int key) {
        int value = key * 0x9E3779B9;
        value ^= value >>> 16;
        return value;
    }

    private int nextSceneStamp() {
        if (++sceneStamp == 0) {
            Arrays.fill(observedSceneStamp, 0);
            sceneStamp = 1;
        }
        return sceneStamp;
    }

    private int nextCandidateGeneration() {
        if (++candidateGeneration == 0) {
            Arrays.fill(candidateStamp, 0);
            candidateGeneration = 1;
        }
        return candidateGeneration;
    }

    private long takeEntryId() {
        long id = nextEntryId++;
        if (id <= 0L) {
            nextEntryId = 2L;
            id = 1L;
        }
        return id;
    }

    private static boolean isAnchoredText(String category) {
        return category != null && ("text_smut".equals(category)
                || category.startsWith("text_"));
    }

    private static boolean sameFamily(String first, String second) {
        if (first == null || second == null) return false;
        return first.equals(second)
                || first.startsWith("face_") && second.startsWith("face_");
    }

    /** Backfill evidence stays invisible in its source viewport, then arms after departure. */
    private void armDormantAfterViewportDeparture(long cameraX, long cameraY) {
        long right = safeAdd(cameraX, viewportWidth);
        long bottom = safeAdd(cameraY, viewportHeight);
        for (int slot = 0; slot < MAX_ENTRIES; slot++) {
            if (!used[slot] || !dormantUntilDeparture[slot]) continue;
            if (!intersects(worldXs[slot], worldYs[slot], worldWidths[slot], worldHeights[slot],
                    cameraX, cameraY, right, bottom)) {
                dormantUntilDeparture[slot] = false;
            }
        }
    }

    /** Backfill evidence stays invisible in its source viewport, then arms after departure. */
    private boolean suppressUntilViewportDeparture(int slot, long cameraX, long cameraY) {
        if (!dormantUntilDeparture[slot]) return false;
        boolean stillInSourceViewport = intersects(
                worldXs[slot], worldYs[slot], worldWidths[slot], worldHeights[slot],
                cameraX, cameraY,
                safeAdd(cameraX, viewportWidth), safeAdd(cameraY, viewportHeight));
        if (stillInSourceViewport) return true;
        dormantUntilDeparture[slot] = false;
        return false;
    }

    private static boolean intersects(int x, int y, int width, int height,
            long left, long top, long right, long bottom) {
        long entryRight = safeAdd(x, width);
        long entryBottom = safeAdd(y, height);
        return entryRight > left && x < right && entryBottom > top && y < bottom;
    }

    private static float intersectionOverUnion(
            int firstX, int firstY, int firstWidth, int firstHeight,
            int secondX, int secondY, int secondWidth, int secondHeight) {
        long left = Math.max((long) firstX, secondX);
        long top = Math.max((long) firstY, secondY);
        long right = Math.min(safeAdd(firstX, firstWidth), safeAdd(secondX, secondWidth));
        long bottom = Math.min(safeAdd(firstY, firstHeight), safeAdd(secondY, secondHeight));
        if (right <= left || bottom <= top) return 0f;
        long intersection = (right - left) * (bottom - top);
        long firstArea = (long) firstWidth * firstHeight;
        long secondArea = (long) secondWidth * secondHeight;
        long union = firstArea + secondArea - intersection;
        return union > 0L ? (float) intersection / union : 0f;
    }

    private static boolean convertScreenToWorld(BBox screen,
            long cameraX, long cameraY,
            int sourceWidth, int sourceHeight,
            int viewportWidth, int viewportHeight,
            int[] output) {
        if (screen == null || screen.getArea() <= 0L || output == null || output.length < 4) return false;
        double scaleX = Math.max(1, viewportWidth) / (double) Math.max(1, sourceWidth);
        double scaleY = Math.max(1, viewportHeight) / (double) Math.max(1, sourceHeight);
        output[0] = saturatingInt(Math.round(screen.getX() * scaleX + cameraX));
        output[1] = saturatingInt(Math.round(screen.getY() * scaleY + cameraY));
        output[2] = Math.max(1, saturatingInt(Math.round(screen.getWidth() * scaleX)));
        output[3] = Math.max(1, saturatingInt(Math.round(screen.getHeight() * scaleY)));
        return output[2] > 0 && output[3] > 0;
    }

    private static int estimateEntryBytes(String className, String category,
            String sourceAnchor, String renderAnchor,
            RenderSourceReference renderSourceReference) {
        long bytes = ENTRY_FIXED_METADATA_BYTES
                + estimateStringBytes(className)
                + estimateStringBytes(category)
                + estimateStringBytes(sourceAnchor)
                + estimateStringBytes(renderAnchor)
                + estimateRenderSourceReferenceBytes(renderSourceReference);
        return bytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    private static int estimateRenderSourceReferenceBytes(RenderSourceReference reference) {
        return reference != null && reference.isKnown()
                ? RENDER_SOURCE_REFERENCE_METADATA_BYTES : 0;
    }

    private static int estimateStringBytes(String value) {
        if (value == null) return 0;
        long bytes = STRING_OBJECT_BYTES + (long) value.length() * STRING_CHAR_BYTES;
        return bytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    private static String safeMetadataString(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_METADATA_STRING_CHARS) return null;
        return value;
    }

    private static String safeAnchor(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_METADATA_STRING_CHARS) return null;
        return value;
    }

    private static String safeSurface(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') start++;
        while (end > start && value.charAt(end - 1) <= ' ') end--;
        if (start == end || end - start > MAX_SURFACE_KEY_CHARS) return "";
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static long floorDiv(long value, long divisor) {
        return Math.floorDiv(value, divisor);
    }

    private static long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0L && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    private static long safeSubtract(long first, long second) {
        if (second > 0L && first < Long.MIN_VALUE + second) return Long.MIN_VALUE;
        if (second < 0L && first > Long.MAX_VALUE + second) return Long.MAX_VALUE;
        return first - second;
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    static final class Observation {
        final int liveTrackId;
        final String className;
        final String category;
        final float confidence;
        final BBox screenBox;
        final boolean nsfw;
        final boolean exposed;
        final int framesTracked;
        final int framesMissing;
        final boolean qualityConfirmed;
        final String anchorKey;
        final boolean deferUntilDeparture;
        final RenderSourceReference renderSourceReference;

        Observation(
                int liveTrackId,
                String className,
                String category,
                float confidence,
                BBox screenBox,
                boolean nsfw,
                boolean exposed,
                int framesTracked,
                int framesMissing,
                boolean qualityConfirmed) {
            this(liveTrackId, className, category, confidence, screenBox, nsfw, exposed,
                    framesTracked, framesMissing, qualityConfirmed, null, false,
                    RenderSourceReference.UNKNOWN);
        }

        Observation(
                int liveTrackId,
                String className,
                String category,
                float confidence,
                BBox screenBox,
                boolean nsfw,
                boolean exposed,
                int framesTracked,
                int framesMissing,
                boolean qualityConfirmed,
                String anchorKey) {
            this(liveTrackId, className, category, confidence, screenBox, nsfw, exposed,
                    framesTracked, framesMissing, qualityConfirmed, anchorKey, false,
                    RenderSourceReference.UNKNOWN);
        }

        Observation(
                int liveTrackId,
                String className,
                String category,
                float confidence,
                BBox screenBox,
                boolean nsfw,
                boolean exposed,
                int framesTracked,
                int framesMissing,
                boolean qualityConfirmed,
                String anchorKey,
                boolean deferUntilDeparture) {
            this(liveTrackId, className, category, confidence, screenBox, nsfw, exposed,
                    framesTracked, framesMissing, qualityConfirmed, anchorKey,
                    deferUntilDeparture, RenderSourceReference.UNKNOWN);
        }

        Observation(
                int liveTrackId,
                String className,
                String category,
                float confidence,
                BBox screenBox,
                boolean nsfw,
                boolean exposed,
                int framesTracked,
                int framesMissing,
                boolean qualityConfirmed,
                String anchorKey,
                boolean deferUntilDeparture,
                RenderSourceReference renderSourceReference) {
            this.liveTrackId = liveTrackId;
            this.className = className;
            this.category = category;
            this.confidence = confidence;
            this.screenBox = screenBox;
            this.nsfw = nsfw;
            this.exposed = exposed;
            this.framesTracked = framesTracked;
            this.framesMissing = framesMissing;
            this.qualityConfirmed = qualityConfirmed;
            this.anchorKey = anchorKey;
            this.deferUntilDeparture = deferUntilDeparture;
            this.renderSourceReference = renderSourceReference == null
                    ? RenderSourceReference.UNKNOWN : renderSourceReference;
        }

        boolean cacheable() {
            return screenBox != null && screenBox.getArea() > 0L
                    && category != null
                    && framesMissing == 0
                    && (framesTracked >= 2 || qualityConfirmed)
                    && (!isAnchoredText(category) || anchorKey != null);
        }

    }

    static final class Update {
        static final Update EMPTY = new Update(0, 0, 0, false);
        final int inserted;
        final int updated;
        final int evicted;
        final boolean viewportReset;

        Update(int inserted, int updated, int evicted, boolean viewportReset) {
            this.inserted = inserted;
            this.updated = updated;
            this.evicted = evicted;
            this.viewportReset = viewportReset;
        }
    }
}
