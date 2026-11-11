package com.subhub.app.service;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Detachable registration relay, not effect authority and not an unregister receipt. The framework
 * may retain this relay after detach, but no owner target remains in it. An already-entered delivery
 * owns its local target until that body's actual return; callbacks admitted by the target separately
 * retain their exact attachment. Values must be immutable or borrowed only for this synchronous call.
 */
final class ManagedAccessibilitySignals<T> {
    private final OwnedCaptureLifetime deliveries = new OwnedCaptureLifetime();
    private Consumer<T> target;
    private boolean detached;
    private volatile boolean failed;

    ManagedAccessibilitySignals(Consumer<T> target) { this.target = Objects.requireNonNull(target); }

    void deliver(T value) {
        Consumer<T> exact;
        OwnedCaptureLifetime.Ticket invocation;
        synchronized (this) {
            exact = target;
            if (exact == null) return;
            invocation = deliveries.begin();
            if (invocation == null) throw new IllegalStateException("Live signal target without lifetime");
        }
        try { exact.accept(value); }
        catch (RuntimeException | Error uncertain) { failed = true; throw uncertain; }
        finally { invocation.close(); }
    }

    void detach() {
        synchronized (this) {
            if (detached) return;
            detached = true; target = null;
        }
        // No callback/observer runs under the relay monitor. Admission above atomically borrows
        // the old target before detach; all later deliveries observe null, never a replacement.
        deliveries.seal(() -> {});
    }

    boolean drained() { return !failed && deliveries.drained(); }
    boolean failed() { return failed; }
}
