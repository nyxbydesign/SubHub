package com.subhub.app.stats;

import java.util.Objects;

/** A request records its observation before dispatch, so slow work is not repeatedly cancelled. */
final class DailyStatsObservationGate {
    private Object requested;
    private boolean hasRequest;
    boolean changed(Object current) { return !hasRequest||!Objects.equals(requested,current); }
    void requested(Object key) { requested=key;hasRequest=true; }
    boolean accepts(Object captured,Object current,boolean accessible) {
        return accessible&&Objects.equals(captured,current);
    }
}
