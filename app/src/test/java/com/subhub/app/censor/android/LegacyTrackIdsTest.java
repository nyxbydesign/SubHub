package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LegacyTrackIdsTest {
    @Test public void duplicateTrackStaysSameAndResetStartsAfterAllPriorIds() {
        LegacyTrackIds ids = new LegacyTrackIds(); LegacyTrackIds.Epoch first = ids.nextEpoch();
        assertEquals(1, first.accountingId(1)); assertEquals(9, first.accountingId(9));
        assertEquals(1, first.accountingId(1));
        LegacyTrackIds.Epoch second = ids.nextEpoch();
        assertEquals(10, second.accountingId(1)); assertEquals(13, second.accountingId(4));
        assertThrows(IllegalStateException.class, () -> first.accountingId(2));
    }
    @Test public void unusedEpochConsumesNoIdsAndGeometryIdsNeedNotBeContiguous() {
        LegacyTrackIds ids = new LegacyTrackIds(); ids.nextEpoch(); ids.nextEpoch();
        LegacyTrackIds.Epoch epoch = ids.nextEpoch();
        assertEquals(17, epoch.accountingId(17)); assertEquals(2, epoch.accountingId(2));
        assertEquals(18, ids.nextEpoch().accountingId(1));
    }
    @Test public void invalidAndOverflowIdentitiesNeverWrapOrAlias() {
        LegacyTrackIds ids = new LegacyTrackIds(); LegacyTrackIds.Epoch first = ids.nextEpoch();
        assertThrows(IllegalArgumentException.class, () -> first.accountingId(0));
        assertThrows(IllegalArgumentException.class, () -> first.accountingId(-1));
        assertEquals(Integer.MAX_VALUE, first.accountingId(Integer.MAX_VALUE));
        LegacyTrackIds.Epoch exhausted = ids.nextEpoch();
        assertThrows(ArithmeticException.class, () -> exhausted.accountingId(1));
        assertThrows(ArithmeticException.class, () -> ids.nextEpoch().accountingId(1));
    }
}
