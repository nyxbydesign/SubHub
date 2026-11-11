package com.subhub.app.runtime;

import com.subhub.app.stats.OwnerObservation;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Capture at the actual impression; the returned action never resamples the writer's calendar. */
final class WhisperObservation {
    private WhisperObservation() { }
    static Runnable capture(LongSupplier clock, Consumer<OwnerObservation> durableCounter) {
        Objects.requireNonNull(durableCounter);
        OwnerObservation observed = OwnerObservation.capture(Objects.requireNonNull(clock).getAsLong());
        return () -> durableCounter.accept(observed);
    }
}
