package com.subhub.app.penance;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class AutoCashoutPolicyTest {
    private PenanceEvent event(int amount, long due, PenanceEvent.Status status) {
        return new PenanceEvent("test", 1, due, amount, 1, status, "");
    }
    @Test public void belowMinimumDoesNotScheduleRepeatedWakeups() {
        assertEquals(0, AutoCashoutPolicy.nextEligibleAt(List.of(event(1499, 10, PenanceEvent.Status.OPEN)), 100));
    }
    @Test public void exactAndAboveMinimumBecomeEligible() {
        for (int amount : new int[]{1500, 1501}) {
            assertEquals(100, AutoCashoutPolicy.nextEligibleAt(List.of(event(amount, 10, PenanceEvent.Status.OPEN)), 100));
        }
    }
    @Test public void thresholdWaitsForEnoughMercyWindowsAndIgnoresPaidOrForgivenEntries() {
        List<PenanceEvent> entries = List.of(event(1000, 300, PenanceEvent.Status.OPEN),
                event(500, 200, PenanceEvent.Status.OPEN), event(10000, 10, PenanceEvent.Status.PAID),
                event(10000, 10, PenanceEvent.Status.FORGIVEN), event(10000, 10, PenanceEvent.Status.CHECKOUT));
        assertEquals(300, AutoCashoutPolicy.nextEligibleAt(entries, 100));
        assertEquals(400, AutoCashoutPolicy.nextEligibleAt(entries, 400));
    }
}
