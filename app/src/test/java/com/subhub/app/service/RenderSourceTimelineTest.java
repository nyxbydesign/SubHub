package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.RenderSourceReference.Origin;

import org.junit.Test;

public final class RenderSourceTimelineTest {
    private static final Origin ORIGIN = origin(1L);

    @Test public void freshCaptureUsesInterpolatedAnchorPlusNonzeroDocumentCamera() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 96L, 100L, 20.0, -10.0);
        timeline.record(ORIGIN, 116L, 120L, 60.0, 30.0);

        RenderSourceReference reference = timeline.resolve(ORIGIN, 110L, 1_000.0, 2_000.0);

        assertTrue(reference.isKnown());
        assertEquals(1_040.0, reference.biasX(), 0.0);
        assertEquals(2_010.0, reference.biasY(), 0.0);
        assertEquals(110L, reference.sourceUptimeMillis());
    }

    @Test public void exactReadEndIsAValidSnapshotWithoutExtrapolation() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 84L, 100L, 25.0, 50.0);

        RenderSourceReference reference = timeline.resolve(ORIGIN, 100L, 5.0, 10.0);

        assertTrue(reference.isKnown());
        assertEquals(30.0, reference.biasX(), 0.0);
        assertEquals(60.0, reference.biasY(), 0.0);
    }

    @Test public void originsNeverBlend() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        Origin other = origin(2L);
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);
        timeline.record(other, 106L, 110L, 9_000.0, 9_000.0);
        timeline.record(ORIGIN, 116L, 120L, 30.0, 40.0);

        RenderSourceReference reference = timeline.resolve(ORIGIN, 110L, 1.0, 2.0);

        assertTrue(reference.isKnown());
        assertEquals(21.0, reference.biasX(), 0.0);
        assertEquals(32.0, reference.biasY(), 0.0);
        assertEquals(3, timeline.size());
    }

    @Test public void oversizedBracketGapIsUnknown() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);
        timeline.record(ORIGIN, 156L, 160L, 30.0, 40.0);

        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 130L, 0.0, 0.0));
    }

    @Test public void outOfOrderReadsMakeOriginUnknown() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 116L, 120L, 30.0, 40.0);
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);

        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 110L, 0.0, 0.0));
    }

    @Test public void invalidReadSpanAndNonfiniteMeasurementsAreUnknown() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 80L, 100L, 10.0, 20.0);
        timeline.record(ORIGIN, 116L, 120L, 30.0, 40.0);
        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 110L, 0.0, 0.0));

        timeline.clear();
        timeline.record(ORIGIN, 96L, 100L, Double.NaN, 20.0);
        timeline.record(ORIGIN, 116L, 120L, 30.0, 40.0);
        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 110L, 0.0, 0.0));

        RenderSourceTimeline finiteTimeline = new RenderSourceTimeline();
        finiteTimeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);
        assertSame(RenderSourceReference.UNKNOWN,
                finiteTimeline.resolve(ORIGIN, 100L, Double.POSITIVE_INFINITY, 0.0));
    }

    @Test public void noPastOrFutureOnlyExtrapolation() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);
        timeline.record(ORIGIN, 116L, 120L, 30.0, 40.0);

        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 99L, 0.0, 0.0));
        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 121L, 0.0, 0.0));
        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(origin(2L), 110L, 0.0, 0.0));
    }

    @Test public void documentCameraIsTakenAtResolveTime() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);

        RenderSourceReference first = timeline.resolve(ORIGIN, 100L, 100.0, 200.0);
        RenderSourceReference late = timeline.resolve(ORIGIN, 100L, 500.0, 800.0);

        assertEquals(110.0, first.biasX(), 0.0);
        assertEquals(220.0, first.biasY(), 0.0);
        assertEquals(510.0, late.biasX(), 0.0);
        assertEquals(820.0, late.biasY(), 0.0);
    }

    @Test public void timelineRetainsOnlyNewest128Samples() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        for (int index = 0; index < 129; index++) {
            long end = 100L + index;
            timeline.record(ORIGIN, end, end, index, -index);
        }

        assertEquals(128, timeline.size());
        assertSame(RenderSourceReference.UNKNOWN,
                timeline.resolve(ORIGIN, 100L, 0.0, 0.0));
        RenderSourceReference newest = timeline.resolve(ORIGIN, 228L, 1.0, 2.0);
        assertTrue(newest.isKnown());
        assertEquals(129.0, newest.biasX(), 0.0);
        assertEquals(-126.0, newest.biasY(), 0.0);
    }

    @Test public void clearDropsOwnedSamples() {
        RenderSourceTimeline timeline = new RenderSourceTimeline();
        timeline.record(ORIGIN, 96L, 100L, 10.0, 20.0);

        timeline.clear();

        assertEquals(0, timeline.size());
        assertFalse(timeline.resolve(ORIGIN, 100L, 0.0, 0.0).isKnown());
    }

    private static Origin origin(long anchorOrigin) {
        return new Origin(0L, 0L, 7, 1_080, 2_400, anchorOrigin);
    }
}
