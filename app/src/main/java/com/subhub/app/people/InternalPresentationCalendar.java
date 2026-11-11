package com.subhub.app.people;

import com.subhub.app.stats.OwnerObservation;
import java.time.LocalDate;
import java.util.Objects;

/** Calendar validity of immutable observations. It neither grants authority nor reads owner data. */
public final class InternalPresentationCalendar {
    private InternalPresentationCalendar() { }
    public static boolean current(OwnerObservation observed, OwnerObservation now) {
        return observed != null && now != null && observed.zone.equals(now.zone)
                && observed.day().equals(now.day());
    }
    public static LocalDate day(OwnerObservation observed) {
        return LocalDate.parse(Objects.requireNonNull(observed).day());
    }
}
