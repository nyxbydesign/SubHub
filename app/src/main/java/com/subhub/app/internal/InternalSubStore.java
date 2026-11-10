package com.subhub.app.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Acknowledged independent profile/configuration transactions. Callers enforce actual controller
 * authority before writes and publish runtime availability only after these transactions finish.
 * No stored field is an authorization grant. No active-session/next-session policy is imposed here.
 */
public final class InternalSubStore {
    public enum Outcome { COMMITTED, CONFLICT, NOT_COMMITTED, UNCERTAIN }
    public interface Backend {
        InternalSubRecord read() throws Exception;
        Outcome compareAndSet(InternalSubRecord expected, InternalSubRecord next) throws Exception;
    }
    public interface RecordValidator { void validate(InternalSubRecord record); }
    public interface Listener { void acknowledged(InternalSubRecord previous, InternalSubRecord current); }
    public static final class PersistenceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final Outcome outcome;
        PersistenceException(Outcome outcome) {
            super("Internal Sub persistence was not acknowledged: " + outcome); this.outcome = outcome;
        }
    }

    private final Backend backend;
    private final RecordValidator validator;
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private boolean uncertain;

    /** Generic storage fixtures use only structural validation; production uses the catalog factory. */
    InternalSubStore(Backend backend) { this(backend, record -> { }); }
    public InternalSubStore(Backend backend, RecordValidator validator) {
        this.backend = Objects.requireNonNull(backend); this.validator = Objects.requireNonNull(validator);
    }

    static InternalSubRecord initial(InternalProfile profile, Map<String, ? extends Map<String, ?>> configuration) {
        return new InternalSubRecord(InternalStorageScope.TARGET_ID, UUID.randomUUID().toString(),
                0, 0, false, profile, configuration);
    }

    public synchronized InternalSubRecord read() {
        if (uncertain) throw new PersistenceException(Outcome.UNCERTAIN);
        try {
            InternalSubRecord record = Objects.requireNonNull(backend.read());
            validator.validate(record); return record;
        }
        catch (Exception failed) { uncertain = true; throw new PersistenceException(Outcome.UNCERTAIN); }
    }
    public synchronized void addListener(Listener listener) { listeners.add(Objects.requireNonNull(listener)); }
    public synchronized void removeListener(Listener listener) { listeners.remove(listener); }

    /** Hiding/removing and re-enabling rotate generation, preserving profile/config/assets/tasks/history. */
    public InternalSubRecord setVisible(InternalSubRecord expected, boolean visible) {
        return change(expected, old -> old.visible == visible ? old : new InternalSubRecord(old.targetId,
                UUID.randomUUID().toString(), increment(old.revision), old.configurationRevision,
                visible, old.profile, old.configuration()));
    }

    public InternalSubRecord saveProfile(InternalSubRecord expected, InternalProfile profile) {
        Objects.requireNonNull(profile);
        return change(expected, old -> old.profile.equals(profile) ? old : new InternalSubRecord(old.targetId,
                old.generation, increment(old.revision), old.configurationRevision,
                old.visible, profile, old.configuration()));
    }

    InternalSubRecord saveConfiguration(InternalSubRecord expected, Map<String, ? extends Map<String, ?>> sections) {
        return change(expected, old -> old.configuration().equals(sections) ? old : new InternalSubRecord(old.targetId,
                old.generation, increment(old.revision), increment(old.configurationRevision),
                old.visible, old.profile, sections));
    }

    private InternalSubRecord change(InternalSubRecord expected, UnaryOperator<InternalSubRecord> operation) {
        InternalSubRecord next;
        ArrayList<Listener> observers;
        synchronized (this) {
            InternalSubRecord current = read();
            if (!current.equals(Objects.requireNonNull(expected))) throw new PersistenceException(Outcome.CONFLICT);
            next = operation.apply(current);
            if (next == current) return current;
            InternalSubRecord.requireSuccessor(current, next);
            validator.validate(next);
            Outcome result;
            try { result = backend.compareAndSet(current, next); }
            catch (Exception failed) { result = Outcome.UNCERTAIN; }
            if (result != Outcome.COMMITTED) {
                if (result == null || result == Outcome.UNCERTAIN) uncertain = true;
                throw new PersistenceException(result == null ? Outcome.UNCERTAIN : result);
            }
            observers = new ArrayList<>(listeners);
        }
        // Never call runtime/policy callbacks with an editor/storage transaction held.
        // A failed observer cannot undo or misreport a successfully acknowledged save.
        for (Listener listener : observers) {
            try { listener.acknowledged(expected, next); }
            catch (RuntimeException ignored) { /* Observer must separately reconcile its own application. */ }
        }
        return next;
    }

    private static long increment(long value) { return Math.addExact(value, 1L); }
}
