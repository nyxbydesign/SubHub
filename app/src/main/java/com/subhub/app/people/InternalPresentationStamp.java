package com.subhub.app.people;

import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.stats.OwnerObservation;
import java.util.Objects;

/** Cache identity only. A match never grants authority and may represent unavailable statistics (-1). */
final class InternalPresentationStamp {
    private final InternalSubRecord record;
    private final long dataRevision, controller;
    private final OwnerObservation observed;
    InternalPresentationStamp(InternalSubRecord record,long dataRevision,long controller) {
        this(record,dataRevision,controller,OwnerObservation.capture(System.currentTimeMillis()));
    }
    InternalPresentationStamp(InternalSubRecord record,long dataRevision,long controller,OwnerObservation observed) {
        this.record=Objects.requireNonNull(record);this.dataRevision=dataRevision;this.controller=controller;
        this.observed=Objects.requireNonNull(observed);
        if(dataRevision < -1)throw new IllegalArgumentException("Invalid internal data revision hint");
    }
    boolean current(InternalSubRecord value,long currentDataRevision,long currentController) {
        return current(value,currentDataRevision,currentController,OwnerObservation.capture(System.currentTimeMillis()));
    }
    boolean current(InternalSubRecord value,long currentDataRevision,long currentController,OwnerObservation now) {
        return value!=null&&value.visible&&record.equals(value)&&dataRevision==currentDataRevision&&controller==currentController
                &&InternalPresentationCalendar.current(observed,now);
    }
}
