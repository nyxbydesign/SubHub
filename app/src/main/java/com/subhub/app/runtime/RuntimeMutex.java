package com.subhub.app.runtime;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Ownership serialization without Android dependencies. The Android adapter must supply
 * RuntimeSessionIdentity.LOCK. Order: policy -> remote store -> runtime -> owner-store transaction.
 */
public final class RuntimeMutex {
    private final Object monitor;
    private final ThreadLocal<Boolean> editorTransaction = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> ownerTransaction = ThreadLocal.withInitial(() -> false);

    public RuntimeMutex(Object monitor) { this.monitor = Objects.requireNonNull(monitor); }

    public <T> T runtime(Supplier<T> work) {
        if (editorTransaction.get() || ownerTransaction.get())
            throw new IllegalStateException("Storage transaction cannot enter runtime");
        synchronized (monitor) { return work.get(); }
    }

    /** Editor adapters finish their database transaction before publishing runtime changes. */
    public <T> T editorTransaction(Supplier<T> work) {
        if (Thread.holdsLock(monitor) || editorTransaction.get() || ownerTransaction.get())
            throw new IllegalStateException("Editor transaction lock inversion");
        editorTransaction.set(true);
        try { return work.get(); }
        finally { editorTransaction.remove(); }
    }

    <T> T ownerTransaction(Supplier<T> work) {
        requireHeld();
        if (ownerTransaction.get() || editorTransaction.get())
            throw new IllegalStateException("Nested owner transaction");
        ownerTransaction.set(true);
        try { return work.get(); }
        finally { ownerTransaction.remove(); }
    }

    void requireHeld() {
        if (!Thread.holdsLock(monitor)) throw new IllegalStateException("Runtime lock required");
    }
}
