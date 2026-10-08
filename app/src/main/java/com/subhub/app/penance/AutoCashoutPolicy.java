package com.subhub.app.penance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Internal EUR batching floor; never applies to an explicit manual cashout. */
final class AutoCashoutPolicy {
    static final int MINIMUM_CENTS = 1_500;

    static long nextEligibleAt(List<PenanceEvent> events, long now) {
        List<PenanceEvent> open = new ArrayList<>();
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.OPEN && event.getAmountCents() > 0) open.add(event);
        }
        open.sort(Comparator.comparingLong(PenanceEvent::getMercyEndsAtMillis));
        long total = 0;
        for (PenanceEvent event : open) {
            total += event.getAmountCents();
            if (total >= MINIMUM_CENTS) return Math.max(now, event.getMercyEndsAtMillis());
        }
        return 0;
    }
    private AutoCashoutPolicy() {}
}
