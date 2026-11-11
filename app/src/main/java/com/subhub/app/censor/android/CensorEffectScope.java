package com.subhub.app.censor.android;

/**
 * Synchronous effect admission. An owned adapter retains the exact runtime permit through the
 * operation's actual return, never merely checks a phase before calling it. False means nothing
 * ran (busy or revoked). Async lifetimes additionally remain owned by their registered component.
 */
public interface CensorEffectScope {
    boolean run(Runnable effect);

    /** The actual native lookup and handle disposal remain inside admission, not a phase check. */
    default <T> T read(java.util.function.Supplier<T> read) {
        java.util.Objects.requireNonNull(read);
        if (this == DEVICE) return read.get();
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        if (!run(() -> value.set(read.get()))) throw new Unavailable();
        return value.get();
    }

    final class Unavailable extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        Unavailable() { super("Censor effect is busy or revoked"); }
    }

    /** Existing device behavior only; internal bindings must supply their admitted runtime gate. */
    CensorEffectScope DEVICE = effect -> { effect.run(); return true; };
}
