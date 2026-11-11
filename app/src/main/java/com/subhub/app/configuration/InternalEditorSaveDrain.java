package com.subhub.app.configuration;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One serial prepare/ACK drain per editor draft, independent of replaceable Activity bindings.
 * All calls and delivered completions run on the owning UI executor; persistence runs in the broker.
 */
final class InternalEditorSaveDrain {
    enum State { SAVED, SAVING, FAILED, REVOKED }
    interface Driver {
        boolean current();
        boolean hasChanges();
        /** Freeze exactly once here, then submit that image for independent broker admission. */
        CompletionStage<?> prepareAndSubmit();
    }
    interface Observation extends AutoCloseable { @Override void close(); }
    private final Driver driver;
    private final Executor delivery;
    private final Set<Consumer<State>> observers = new LinkedHashSet<>();
    private State state = State.SAVED;
    private boolean pending;
    private Throwable failure;

    InternalEditorSaveDrain(Driver driver, Executor delivery) {
        this.driver = Objects.requireNonNull(driver); this.delivery = Objects.requireNonNull(delivery);
    }
    State state() { return state; }
    boolean pending() { return pending; }
    Throwable failure() { return failure; }
    Observation observe(Consumer<State> observer) {
        Objects.requireNonNull(observer); observers.add(observer); notify(observer, state);
        return () -> observers.remove(observer);
    }
    void request() {
        if (state == State.FAILED || state == State.REVOKED || pending) return;
        try {
            if (!driver.current()) { publish(State.REVOKED); return; }
            if (!driver.hasChanges()) { publish(State.SAVED); return; }
            // Set pending before preparing/registering a possibly already-completed future.
            pending = true; publish(State.SAVING);
            CompletionStage<?> saved = Objects.requireNonNull(driver.prepareAndSubmit());
            saved.whenComplete((ignored, problem) -> delivery.execute(() -> settled(problem)));
        } catch (RuntimeException problem) {
            pending = false; failure = problem; publish(State.FAILED);
        }
    }
    private void settled(Throwable problem) {
        pending = false;
        if (problem != null) { failure = problem; publish(State.FAILED); return; }
        // Only after this ACK may a later immutable image be prepared from the draft's new base.
        // Closing all Views does not cancel an admitted write or discard already-staged edits.
        request();
    }
    private void publish(State value) {
        state = value;
        for (Consumer<State> observer : new java.util.ArrayList<>(observers)) notify(observer, value);
    }
    private static void notify(Consumer<State> observer, State state) {
        try { observer.accept(state); } catch (RuntimeException detachedView) { /* A View cannot change persistence ordering. */ }
    }
}
