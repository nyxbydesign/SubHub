package com.subhub.app.service;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android ownership adapter for the bounded viewport-anchor geometry core. */
@SuppressWarnings("deprecation") // Every obtained node is recycled or transferred exactly once.
final class AndroidViewportAnchors {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FETCHES = 192;
    private static final int MAX_VISITS = 192;
    private static final int MAX_CANDIDATES = 8;
    private static final int SELECTED_ANCHORS = 3;
    private static final int TOP_EXCLUSION_PERCENT = 28;
    private static final int MAX_AREA_PERCENT = 20;
    private static final long COLLECTION_DEADLINE_MS = 2_000L;

    private AndroidViewportAnchors() { }

    /**
     * Consumes {@code ownedRoot}. Three selected nodes transfer to the returned anchors; every
     * other fetched node is recycled before return, including on failure.
     */
    static List<AsyncViewportAnchorSampler.Anchor> collect(
            AccessibilityNodeInfo ownedRoot,
            String expectedPackage,
            int windowId,
            int viewportWidth,
            int viewportHeight) {
        return collect(ownedRoot, expectedPackage, windowId, viewportWidth, viewportHeight,
                SystemClock.uptimeMillis() + COLLECTION_DEADLINE_MS, MAX_FETCHES);
    }

    private static List<AsyncViewportAnchorSampler.Anchor> collect(
            AccessibilityNodeInfo ownedRoot, String expectedPackage, int windowId,
            int viewportWidth, int viewportHeight, long deadline, int fetchLimit) {
        if (ownedRoot == null) throw new NullPointerException("ownedRoot");
        ArrayDeque<NodeAtDepth> pending = new ArrayDeque<>();
        List<Candidate> candidates = new ArrayList<>(MAX_CANDIDATES);
        pending.addLast(new NodeAtDepth(ownedRoot, 0));
        try {
            if (expectedPackage == null || expectedPackage.isEmpty()
                    || windowId < 0 || viewportWidth <= 0 || viewportHeight <= 0
                    || !matches(ownedRoot, expectedPackage, windowId)) {
                return Collections.emptyList();
            }
            Rect viewport = new Rect(0, 0, viewportWidth, viewportHeight);
            int fetches = 1;
            int visits = 0;
            while (!pending.isEmpty() && visits < MAX_VISITS
                    && candidates.size() < MAX_CANDIDATES) {
                if (SystemClock.uptimeMillis() >= deadline) break;
                NodeAtDepth entry = pending.removeLast();
                AccessibilityNodeInfo node = entry.node;
                boolean retained = false;
                visits++;
                try {
                    int childCount = node.getChildCount();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (childCount == 0
                            && qualifies(node, bounds, viewport, expectedPackage, windowId)
                            && isDistinct(candidates, bounds)) {
                        candidates.add(new Candidate(node, bounds));
                        retained = true;
                    } else if (entry.depth < MAX_DEPTH) {
                        List<NodeAtDepth> viewportChildren = new ArrayList<>();
                        try {
                            for (int childIndex = 0; childIndex < childCount; childIndex++) {
                                if (fetches >= fetchLimit
                                        || SystemClock.uptimeMillis() >= deadline) {
                                    break;
                                }
                                // Count every attempted child fetch, including null results.
                                fetches++;
                                AccessibilityNodeInfo child = node.getChild(childIndex);
                                if (child == null) continue;
                                boolean childTransferred = false;
                                try {
                                    if (mayContainViewportAnchor(
                                            child, viewport, expectedPackage, windowId)) {
                                        viewportChildren.add(
                                                new NodeAtDepth(child, entry.depth + 1));
                                        childTransferred = true;
                                    }
                                } finally {
                                    if (!childTransferred) child.recycle();
                                }
                            }
                            // Tail-pop depth-first traversal while preserving child index order.
                            for (int index = viewportChildren.size() - 1; index >= 0; index--) {
                                pending.addLast(viewportChildren.get(index));
                            }
                            viewportChildren.clear();
                        } finally {
                            for (NodeAtDepth child : viewportChildren) child.node.recycle();
                        }
                    }
                } finally {
                    if (!retained) node.recycle();
                }
            }
            return selectDispersedThree(candidates, expectedPackage, windowId);
        } finally {
            while (!pending.isEmpty()) pending.removeLast().node.recycle();
            for (Candidate candidate : candidates) candidate.recycleIfOwned();
        }
    }

    private static boolean mayContainViewportAnchor(
            AccessibilityNodeInfo node,
            Rect viewport,
            String expectedPackage,
            int windowId) {
        if (!node.isVisibleToUser() || !matches(node, expectedPackage, windowId)) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        // Empty virtual containers may still own visible descendants.
        return bounds.isEmpty() || Rect.intersects(bounds, viewport);
    }

    private static boolean qualifies(
            AccessibilityNodeInfo node,
            Rect bounds,
            Rect viewport,
            String expectedPackage,
            int windowId) {
        if (!node.isVisibleToUser() || !matches(node, expectedPackage, windowId)
                || bounds.isEmpty() || !Rect.intersects(bounds, viewport)
                || bounds.width() < 4 || bounds.height() < 4) {
            return false;
        }
        int topCutoff = viewport.top
                + viewport.height() * TOP_EXCLUSION_PERCENT / 100;
        if (bounds.centerY() <= topCutoff) return false;
        long viewportArea = (long) viewport.width() * viewport.height();
        long anchorArea = (long) bounds.width() * bounds.height();
        return anchorArea * 100L < viewportArea * MAX_AREA_PERCENT;
    }

    private static boolean matches(
            AccessibilityNodeInfo node, String expectedPackage, int windowId) {
        CharSequence observedPackage = node.getPackageName();
        return node.getWindowId() == windowId
                && observedPackage != null
                && expectedPackage.contentEquals(observedPackage);
    }

    private static boolean isDistinct(List<Candidate> candidates, Rect bounds) {
        for (Candidate candidate : candidates) {
            if (candidate.bounds.equals(bounds)) return false;
        }
        return true;
    }

    private static List<AsyncViewportAnchorSampler.Anchor> selectDispersedThree(
            List<Candidate> candidates,
            String expectedPackage,
            int windowId) {
        if (candidates.size() < SELECTED_ANCHORS) return Collections.emptyList();
        int first = 0;
        int second = 1;
        int third = 2;
        double bestMinimumDistance = -1.0;
        double bestTotalDistance = -1.0;
        for (int a = 0; a < candidates.size() - 2; a++) {
            for (int b = a + 1; b < candidates.size() - 1; b++) {
                for (int c = b + 1; c < candidates.size(); c++) {
                    double ab = distanceSquared(candidates.get(a), candidates.get(b));
                    double ac = distanceSquared(candidates.get(a), candidates.get(c));
                    double bc = distanceSquared(candidates.get(b), candidates.get(c));
                    double minimum = Math.min(ab, Math.min(ac, bc));
                    double total = ab + ac + bc;
                    if (minimum > bestMinimumDistance
                            || (minimum == bestMinimumDistance && total > bestTotalDistance)) {
                        first = a;
                        second = b;
                        third = c;
                        bestMinimumDistance = minimum;
                        bestTotalDistance = total;
                    }
                }
            }
        }

        int[] selected = { first, second, third };
        List<AsyncViewportAnchorSampler.Anchor> result = new ArrayList<>(SELECTED_ANCHORS);
        boolean complete = false;
        try {
            for (int index : selected) {
                Candidate candidate = candidates.get(index);
                result.add(new NodeAnchor(
                        candidate.takeNode(), expectedPackage, windowId));
            }
            List<AsyncViewportAnchorSampler.Anchor> immutable =
                    Collections.unmodifiableList(result);
            complete = true;
            return immutable;
        } finally {
            if (!complete) {
                for (AsyncViewportAnchorSampler.Anchor anchor : result) anchor.close();
            }
        }
    }

    private static double distanceSquared(Candidate first, Candidate second) {
        double dx = ((long) first.bounds.left + first.bounds.right)
                - ((long) second.bounds.left + second.bounds.right);
        double dy = ((long) first.bounds.top + first.bounds.bottom)
                - ((long) second.bounds.top + second.bounds.bottom);
        return dx * dx + dy * dy;
    }

    private static final class NodeAnchor implements AsyncViewportAnchorSampler.Anchor {
        private AccessibilityNodeInfo node;
        private final String expectedPackage;
        private final int windowId;

        NodeAnchor(AccessibilityNodeInfo node, String expectedPackage, int windowId) {
            this.node = node;
            this.expectedPackage = expectedPackage;
            this.windowId = windowId;
        }

        @Override public ViewportAnchorGeometry.Bounds read() {
            AccessibilityNodeInfo value = node;
            if (value == null) throw new AsyncViewportAnchorSampler.AnchorReadException(
                    AsyncViewportAnchorSampler.ReadFailure.CLOSED);
            if (!value.refresh()) throw new AsyncViewportAnchorSampler.AnchorReadException(
                    AsyncViewportAnchorSampler.ReadFailure.REFRESH_FAILED);
            if (!value.isVisibleToUser()) throw new AsyncViewportAnchorSampler.AnchorReadException(
                    AsyncViewportAnchorSampler.ReadFailure.INVISIBLE);
            if (!matches(value, expectedPackage, windowId))
                throw new AsyncViewportAnchorSampler.AnchorReadException(
                        AsyncViewportAnchorSampler.ReadFailure.SCOPE_CHANGED);
            Rect bounds = new Rect();
            value.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) throw new AsyncViewportAnchorSampler.AnchorReadException(
                    AsyncViewportAnchorSampler.ReadFailure.EMPTY_BOUNDS);
            return new ViewportAnchorGeometry.Bounds(
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
        }

        @Override public void close() {
            AccessibilityNodeInfo value = node;
            node = null;
            if (value != null) value.recycle();
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        final Rect bounds;

        Candidate(AccessibilityNodeInfo node, Rect bounds) {
            this.node = node;
            this.bounds = new Rect(bounds);
        }

        AccessibilityNodeInfo takeNode() {
            AccessibilityNodeInfo value = node;
            if (value == null) throw new IllegalStateException("Candidate already transferred");
            node = null;
            return value;
        }

        void recycleIfOwned() {
            AccessibilityNodeInfo value = node;
            node = null;
            if (value != null) value.recycle();
        }
    }

    private static final class NodeAtDepth {
        final AccessibilityNodeInfo node;
        final int depth;

        NodeAtDepth(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}
