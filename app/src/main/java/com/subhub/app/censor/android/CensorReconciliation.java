package com.subhub.app.censor.android;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Only a refused owner gate is deferral. Actual read/commit/cleanup errors remain terminal. */
final class CensorReconciliation {
    private CensorReconciliation() { }
    static final class Selection<T> {
        private final T value;
        private final boolean deferred;
        private Selection(T value, boolean deferred) { this.value = value; this.deferred = deferred; }
    }
    static <T> Selection<T> read(Supplier<T> read) {
        try { return new Selection<>(Objects.requireNonNull(read.get()), false); }
        catch (CensorEffectScope.Unavailable refused) { return new Selection<>(null, true); }
    }
    static <T> void commit(Selection<T> selected, Consumer<T> commit, Runnable defer) {
        Objects.requireNonNull(selected);
        if (selected.deferred) { defer.run(); return; }
        try { commit.accept(selected.value); }
        catch (CensorEffectScope.Unavailable refused) { defer.run(); }
    }
}
