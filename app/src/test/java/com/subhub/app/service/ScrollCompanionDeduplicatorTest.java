package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.subhub.app.service.AccessibilityScrollMotionResolver.Motion.Evidence.*;

public final class ScrollCompanionDeduplicatorTest {
    private static final long SURFACE = 0x74196ed899f53f30L;
    private static final long COMPANION = 0xa6ecda25121be9b2L;

    @Test public void recorded281PixelCompanionIsAppliedOnlyOnce() {
        ScrollCompanionDeduplicator value = new ScrollCompanionDeduplicator();
        assertFalse(value.observe(23, SURFACE, SURFACE, 1344, 2992, true, false,
                ABSOLUTE, 0, 281, 336559725, 336559727));
        assertTrue(value.observe(23, SURFACE, COMPANION, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 336559737, 336559739));
        assertFalse(value.observe(23, SURFACE, COMPANION, 1344, 2992, false, true,
                EXPLICIT, 0, 360, 336559837, 336559840));
    }

    @Test public void sameProducerRepeatedDeltasAreNotDuplicates() {
        ScrollCompanionDeduplicator value = primed();
        assertFalse(value.observe(23, SURFACE, SURFACE, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 112, 114));
        assertFalse(value.observe(23, SURFACE, SURFACE, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 124, 126));
    }

    @Test public void duplicateCreditIsConsumedOnlyOnce() {
        ScrollCompanionDeduplicator value = primed();
        assertTrue(companion(value, 0, 281, 112, 114));
        assertFalse(companion(value, 0, 281, 113, 115));
    }

    @Test public void differentDisplacementsAndDirectionsPassThrough() {
        assertFalse(companion(primed(), 0, 280, 112, 114));
        assertFalse(companion(primed(), 0, -281, 112, 114));
        assertFalse(companion(primed(), 1, 281, 112, 114));
        assertFalse(companion(primed(), 281, 0, 112, 114));
    }

    @Test public void sourceAndDeliveryWindowsBothMustBeFreshAndOrdered() {
        assertFalse(companion(primed(), 0, 281, 133, 135));
        assertFalse(companion(primed(), 0, 281, 112, 140));
        assertFalse(companion(primed(), 0, 281, 99, 114));
        assertFalse(companion(primed(), 0, 281, 101, 101));
        assertFalse(companion(primed(), 0, 281, 114, 112));
    }

    @Test public void scopeAndViewportChangesNeverShareCredit() {
        assertFalse(primed().observe(24, SURFACE, COMPANION, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 112, 114));
        assertFalse(primed().observe(23, 7, COMPANION, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 112, 114));
        assertFalse(primed().observe(23, SURFACE, COMPANION, 1080, 2992, false, true,
                EXPLICIT, 0, 281, 112, 114));
    }

    @Test public void observedOrUnknownSurfaceCannotPretendToBeCompanion() {
        assertFalse(primed().observe(23, SURFACE, COMPANION, 1344, 2992, false, false,
                EXPLICIT, 0, 281, 112, 114));
        assertFalse(primed().observe(23, SURFACE, 0, 1344, 2992, false, true,
                EXPLICIT, 0, 281, 112, 114));
        ScrollCompanionDeduplicator value = new ScrollCompanionDeduplicator();
        value.observe(23, SURFACE, SURFACE, 1344, 2992, false, false,
                ABSOLUTE, 0, 281, 100, 102);
        assertFalse(companion(value, 0, 281, 112, 114));
    }

    @Test public void interveningEventAndResetBreakPair() {
        ScrollCompanionDeduplicator value = primed();
        value.observe(23, SURFACE, COMPANION, 1344, 2992, false, true,
                NONE, 0, 0, 105, 107);
        assertFalse(companion(value, 0, 281, 112, 114));
        value = primed();
        value.reset();
        assertFalse(companion(value, 0, 281, 112, 114));
    }

    @Test public void explicitThenAbsoluteIsNotBlindlyDeduplicated() {
        ScrollCompanionDeduplicator value = new ScrollCompanionDeduplicator();
        assertFalse(companion(value, 0, 281, 100, 102));
        assertFalse(value.observe(23, SURFACE, SURFACE, 1344, 2992, true, false,
                ABSOLUTE, 0, 281, 112, 114));
    }

    @Test public void reverseAndHorizontalExactPairsWorkWithoutMagnitudeThreshold() {
        ScrollCompanionDeduplicator value = new ScrollCompanionDeduplicator();
        value.observe(23, SURFACE, SURFACE, 1344, 2992, true, false,
                ABSOLUTE, -80, 0, 100, 102);
        assertTrue(companion(value, -80, 0, 112, 114));
        value.observe(23, SURFACE, SURFACE, 1344, 2992, true, false,
                ABSOLUTE, 0, -2, 200, 202);
        assertTrue(companion(value, 0, -2, 212, 214));
    }

    private static ScrollCompanionDeduplicator primed() {
        ScrollCompanionDeduplicator value = new ScrollCompanionDeduplicator();
        value.observe(23, SURFACE, SURFACE, 1344, 2992, true, false,
                ABSOLUTE, 0, 281, 100, 102);
        return value;
    }

    private static boolean companion(ScrollCompanionDeduplicator value,
            int dx, int dy, long source, long received) {
        return value.observe(23, SURFACE, COMPANION, 1344, 2992, false, true,
                EXPLICIT, dx, dy, source, received);
    }
}
