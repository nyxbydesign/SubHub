package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ViewportAnchorGeometryTest {
    private static final long FENCE = 41L;
    private static final long REFERENCE_TIME = 1_000L;

    @Test public void repeatedAbsoluteSampleDoesNotAccumulate() {
        ViewportAnchorGeometry geometry = geometry(100.0, 200.0, anchors(4));
        List<ViewportAnchorGeometry.Bounds> current = screenMoved(anchors(4), 15, -30);

        ViewportAnchorGeometry.Result first = estimate(geometry, current);
        ViewportAnchorGeometry.Result repeated = estimate(geometry, current);

        assertTrue(first.accepted());
        assertEquals(115.0, first.measuredX, 0.0);
        assertEquals(170.0, first.measuredY, 0.0);
        assertEquals(first.measuredX, repeated.measuredX, 0.0);
        assertEquals(first.measuredY, repeated.measuredY, 0.0);
    }

    @Test public void externalAuthoritativeDeltaCannotAffectCore() {
        ViewportAnchorGeometry geometry = geometry(500.0, 700.0, anchors(3));
        ViewportAnchorGeometry.Result first = estimate(
                geometry, screenMoved(anchors(3), 20, 40));
        double externalAuthoritativeDelta = 9_000.0;

        ViewportAnchorGeometry.Result next = estimate(
                geometry, screenMoved(anchors(3), 35, 55));

        assertEquals(535.0, next.measuredX, 0.0);
        assertEquals(755.0, next.measuredY, 0.0);
        assertNotEquals(first.measuredY + externalAuthoritativeDelta,
                next.measuredY, 0.0);
    }

    @Test public void largeReturnAndReversalRemainBaselineRelative() {
        ViewportAnchorGeometry geometry = geometry(5_000.0, 8_000.0, anchors(5));

        ViewportAnchorGeometry.Result far = estimate(
                geometry, screenMoved(anchors(5), 3_200, 4_500));
        ViewportAnchorGeometry.Result reversed = estimate(
                geometry, screenMoved(anchors(5), -1_700, -2_300));

        assertEquals(8_200.0, far.measuredX, 0.0);
        assertEquals(12_500.0, far.measuredY, 0.0);
        assertEquals(3_300.0, reversed.measuredX, 0.0);
        assertEquals(5_700.0, reversed.measuredY, 0.0);
    }

    @Test public void upwardScreenMotionMakesAbsoluteOffsetMoreNegative() {
        List<ViewportAnchorGeometry.Bounds> baseline = Arrays.asList(
                new ViewportAnchorGeometry.Bounds(20, 100, 120, 160),
                new ViewportAnchorGeometry.Bounds(160, 100, 260, 160),
                new ViewportAnchorGeometry.Bounds(300, 100, 400, 160));
        ViewportAnchorGeometry geometry = geometry(0.0, -1_000.0, baseline);

        ViewportAnchorGeometry.Result result = estimate(
                geometry, screenMoved(baseline, 0, -25));

        assertEquals(-25.0, result.translationY(), 0.0);
        assertEquals(-1_025.0, result.measuredY, 0.0);
    }

    @Test public void resizedAnchorIsExcludedWhenThreeRigidRemain() {
        List<ViewportAnchorGeometry.Bounds> baseline = anchors(4);
        ViewportAnchorGeometry geometry = geometry(0.0, 0.0, baseline);
        List<ViewportAnchorGeometry.Bounds> current = screenMoved(baseline, 12, 25);
        ViewportAnchorGeometry.Bounds fourth = current.get(3);
        current.set(3, new ViewportAnchorGeometry.Bounds(
                fourth.left, fourth.top, fourth.right - 20, fourth.bottom));

        ViewportAnchorGeometry.Result result = estimate(geometry, current);

        assertTrue(result.accepted());
        assertEquals(3, result.inlierCount());
        assertEquals(1, result.excludedAnchorCount());
        assertEquals(12.0, result.measuredX, 0.0);
        assertEquals(25.0, result.measuredY, 0.0);
    }

    @Test public void clippingRejectsWhenFewerThanThreeRigidRemain() {
        List<ViewportAnchorGeometry.Bounds> baseline = anchors(3);
        ViewportAnchorGeometry geometry = geometry(0.0, 0.0, baseline);
        List<ViewportAnchorGeometry.Bounds> current = screenMoved(baseline, 12, 25);
        ViewportAnchorGeometry.Bounds clipped = current.get(2);
        current.set(2, new ViewportAnchorGeometry.Bounds(
                clipped.left + 30, clipped.top, clipped.right, clipped.bottom));

        ViewportAnchorGeometry.Result result = estimate(geometry, current);

        assertFalse(result.accepted());
        assertEquals(ViewportAnchorGeometry.Status.RESIZED_OR_CLIPPED, result.status);
    }

    @Test public void independentlyMovingAnchorIsExcludedWithThreeRemaining() {
        List<ViewportAnchorGeometry.Bounds> baseline = anchors(4);
        ViewportAnchorGeometry geometry = geometry(50.0, 75.0, baseline);
        List<ViewportAnchorGeometry.Bounds> current = screenMoved(baseline, 8, 16);
        current.set(3, screenMoved(Arrays.asList(baseline.get(3)), -80, 140).get(0));

        ViewportAnchorGeometry.Result result = estimate(geometry, current);

        assertTrue(result.accepted());
        assertEquals(3, result.inlierCount());
        assertEquals(1, result.excludedAnchorCount());
        assertEquals(58.0, result.measuredX, 0.0);
        assertEquals(91.0, result.measuredY, 0.0);
    }

    @Test public void independentlyMovingAnchorRejectsWithoutThreeRemaining() {
        List<ViewportAnchorGeometry.Bounds> baseline = anchors(3);
        ViewportAnchorGeometry geometry = geometry(0.0, 0.0, baseline);
        List<ViewportAnchorGeometry.Bounds> current = screenMoved(baseline, 8, 16);
        current.set(2, screenMoved(Arrays.asList(baseline.get(2)), -80, 140).get(0));

        ViewportAnchorGeometry.Result result = estimate(geometry, current);

        assertFalse(result.accepted());
        assertEquals(ViewportAnchorGeometry.Status.INCONSISTENT_TRANSLATION, result.status);
    }

    @Test public void staleFenceFutureAndNonmonotonicReadsReject() {
        ViewportAnchorGeometry geometry = geometry(0.0, 0.0, anchors(3));
        List<ViewportAnchorGeometry.Bounds> current = anchors(3);

        assertEquals(ViewportAnchorGeometry.Status.FENCE_MISMATCH,
                geometry.estimate(current, FENCE + 1, 1_100, 1_105, 1_110, 100).status);
        assertEquals(ViewportAnchorGeometry.Status.STALE_READ,
                geometry.estimate(current, FENCE, 1_100, 1_105, 1_500, 100).status);
        assertEquals(ViewportAnchorGeometry.Status.FUTURE_READ,
                geometry.estimate(current, FENCE, 1_100, 1_120, 1_110, 100).status);
        assertEquals(ViewportAnchorGeometry.Status.NON_MONOTONIC_READ,
                geometry.estimate(current, FENCE, 1_120, 1_110, 1_130, 100).status);
        assertEquals(ViewportAnchorGeometry.Status.NON_MONOTONIC_READ,
                geometry.estimate(current, FENCE, 999, 1_005, 1_010, 100).status);
    }

    @Test public void resetOwnsBoundsAndChangesBaselineIdentity() {
        List<ViewportAnchorGeometry.Bounds> source = new ArrayList<>(anchors(3));
        ViewportAnchorGeometry geometry = new ViewportAnchorGeometry();
        long firstIdentity = geometry.reset(source, 10.0, 20.0, FENCE, REFERENCE_TIME);
        source.clear();

        ViewportAnchorGeometry.Result first = estimate(geometry, anchors(3));
        long secondIdentity = geometry.reset(anchors(4), 30.0, 40.0,
                FENCE + 1, REFERENCE_TIME + 10);
        ViewportAnchorGeometry.Result second = geometry.estimate(
                anchors(4), FENCE + 1, 1_100, 1_105, 1_110, 100);

        assertEquals(1L, firstIdentity);
        assertEquals(firstIdentity, first.baselineIdentity);
        assertEquals(2L, secondIdentity);
        assertEquals(secondIdentity, second.baselineIdentity);
        geometry.clear();
        assertEquals(0L, geometry.baselineIdentity());
        assertEquals(ViewportAnchorGeometry.Status.NO_BASELINE,
                estimate(geometry, anchors(4)).status);
    }

    @Test public void baselineRequiresThreeToFiveDistinctBounds() {
        ViewportAnchorGeometry geometry = new ViewportAnchorGeometry();
        assertThrows(IllegalArgumentException.class,
                () -> geometry.reset(anchors(2), 0, 0, FENCE, REFERENCE_TIME));
        assertThrows(IllegalArgumentException.class,
                () -> geometry.reset(anchors(6), 0, 0, FENCE, REFERENCE_TIME));
        List<ViewportAnchorGeometry.Bounds> duplicates = anchors(3);
        duplicates.set(2, duplicates.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> geometry.reset(duplicates, 0, 0, FENCE, REFERENCE_TIME));
    }

    private static ViewportAnchorGeometry geometry(
            double referenceX,
            double referenceY,
            List<ViewportAnchorGeometry.Bounds> baseline) {
        ViewportAnchorGeometry geometry = new ViewportAnchorGeometry();
        geometry.reset(baseline, referenceX, referenceY, FENCE, REFERENCE_TIME);
        return geometry;
    }

    private static ViewportAnchorGeometry.Result estimate(
            ViewportAnchorGeometry geometry,
            List<ViewportAnchorGeometry.Bounds> current) {
        return geometry.estimate(current, FENCE, 1_100, 1_105, 1_110, 100);
    }

    private static List<ViewportAnchorGeometry.Bounds> anchors(int count) {
        List<ViewportAnchorGeometry.Bounds> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int left = 100 + index * 210;
            int top = 200 + index * 260;
            result.add(new ViewportAnchorGeometry.Bounds(
                    left, top, left + 120, top + 180));
        }
        return result;
    }

    /** Applies literal screen translation: positive coordinates move right and down. */
    private static List<ViewportAnchorGeometry.Bounds> screenMoved(
            List<ViewportAnchorGeometry.Bounds> baseline,
            int screenX,
            int screenY) {
        List<ViewportAnchorGeometry.Bounds> result = new ArrayList<>();
        for (ViewportAnchorGeometry.Bounds bounds : baseline) {
            result.add(new ViewportAnchorGeometry.Bounds(
                    bounds.left + screenX,
                    bounds.top + screenY,
                    bounds.right + screenX,
                    bounds.bottom + screenY));
        }
        return result;
    }
}
