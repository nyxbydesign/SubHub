package com.subhub.app.runtime;

import java.util.Objects;

/**
 * Exact DEVICE-only synchronous continuation used by the Android gateway's setter adapter. It
 * grants no ambient permission from a role, Identity, saved armed flag or static current owner.
 * A child thread/queued callback must obtain its own admitted continuation; this scope never
 * transfers through Runnable capture. It has no Context, persistence or platform dependencies.
 */
final class DeviceContinuationScope {
    enum Mutation { START_OR_REARM, END, CONFIGURATION }
    private enum Phase { STARTUP, NORMAL, CLEANUP }
    private static final ThreadLocal<Frame> HELD = new ThreadLocal<>();
    private final RuntimeExecution<?> runtime;
    private final RuntimeLease lease;
    private final OwnedResourceRegistry registry;

    DeviceContinuationScope(ActiveRuntimeCoordinator coordinator, RuntimeExecution<?> runtime, RuntimeLease lease) {
        this.runtime = Objects.requireNonNull(runtime); this.lease = Objects.requireNonNull(lease);
        if (lease.identity.target.kind != RuntimeTarget.Kind.DEVICE || !lease.identity.equals(runtime.identity()))
            throw new SecurityException("DEVICE continuation needs its exact physical runtime");
        registry = Objects.requireNonNull(coordinator).resources(lease); // Worker-only exact capability lookup.
    }
    boolean startup(Runnable action) { return runtime.runStartupEffect(() -> held(Phase.STARTUP, action)); }
    boolean normal(Runnable action) { return runtime.runEffect(() -> held(Phase.NORMAL, action)); }

    /**
     * Called only from the exact component's shutdown after the execution core sealed normal work.
     * The component retains actual OS resources and must not acknowledge them if this throws.
     */
    void cleanup(Runnable action) {
        try (OwnedResourceRegistry.EffectPermit permit = registry.acquireEffectPermit(lease, OwnedResourceRegistry.Effect.CLEANUP)) {
            Objects.requireNonNull(permit); held(Phase.CLEANUP, action);
        }
    }
    /** No IO/RuntimeMutex. Caller holds this through the original synchronous setter body. */
    Borrow admit(Mutation mutation) {
        Objects.requireNonNull(mutation);
        Frame frame = HELD.get();
        if (frame == null || frame.scope != this || !frame.inside)
            throw new SecurityException("Missing exact admitted DEVICE continuation");
        if (frame.phase == Phase.CLEANUP && mutation != Mutation.END)
            throw new SecurityException("Cleanup cannot arm or configure a DEVICE session");
        frame.borrows++;
        return new Borrow(frame);
    }
    /** Full identity is a constraint, never authority: the exact executable frame is mandatory. */
    Borrow admitManaged(RuntimeLease.Identity expected, Mutation mutation, boolean startup) {
        if (!lease.identity.equals(Objects.requireNonNull(expected)))
            throw new SecurityException("Managed DEVICE owner changed");
        Frame frame = HELD.get();
        Phase required = startup ? Phase.STARTUP : Phase.CLEANUP;
        if (frame == null || frame.scope != this || !frame.inside || frame.phase != required)
            throw new SecurityException("Missing exact managed DEVICE phase");
        if (startup ? mutation != Mutation.START_OR_REARM : mutation != Mutation.END)
            throw new SecurityException("Managed mutation does not match its phase");
        return admit(mutation);
    }
    private void held(Phase phase, Runnable action) {
        Objects.requireNonNull(action);
        Frame previous = HELD.get();
        if (previous != null && (previous.scope != this || previous.phase != phase))
            throw new SecurityException("Cannot borrow another owner/phase continuation");
        Frame frame = new Frame(this, phase);
        HELD.set(frame);
        try { action.run(); }
        finally {
            frame.inside = false;
            if (previous == null) HELD.remove(); else HELD.set(previous);
            if (frame.borrows != 0) {
                runtime.requestStop();
                throw new IllegalStateException("DEVICE mutation scope outlived its synchronous continuation");
            }
        }
    }
    private static final class Frame {
        final DeviceContinuationScope scope;
        final Phase phase;
        final Thread thread = Thread.currentThread();
        boolean inside = true;
        int borrows;
        Frame(DeviceContinuationScope scope, Phase phase) { this.scope = scope; this.phase = phase; }
    }
    static final class Borrow implements AutoCloseable {
        private final Frame frame;
        private boolean closed;
        private Borrow(Frame frame) { this.frame = frame; }
        @Override public void close() {
            if (Thread.currentThread() != frame.thread || HELD.get() != frame || !frame.inside)
                throw new SecurityException("DEVICE mutation returned outside its exact continuation");
            if (!closed) { closed = true; frame.borrows--; }
        }
    }
}
