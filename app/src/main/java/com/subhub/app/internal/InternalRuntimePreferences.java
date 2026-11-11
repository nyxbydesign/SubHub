package com.subhub.app.internal;

import android.content.SharedPreferences;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

/**
 * Owner-local typed preferences. The provider owns its serial worker and runtime registrations.
 * No identity is an admission: every outer transaction uses the supplied synchronous WriteGuard
 * across mutation, durable CAS, readback acknowledgement and publication. No disk work is queued
 * here. apply() is deliberately acknowledged just like commit(), not Android's async best effort.
 *
 * Explicit nested transactions and editor commit/apply calls join the outer transaction. Their
 * return values describe staging only; the outer return/receipt is the sole durable success. Any
 * nested failure poisons that transaction even if its caller catches the exception. Other threads
 * see only the immutable acknowledged record, without taking the writer lock or waiting on fsync.
 */
public final class InternalRuntimePreferences implements AutoCloseable {
    public enum Outcome { COMMITTED, CONFLICT, NOT_COMMITTED, UNCERTAIN }
    public interface Backend extends AutoCloseable {
        InternalRuntimeRecord read() throws Exception;
        Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) throws Exception;
        @Override void close();
    }
    /** Invoke the operation exactly once on the calling worker, under its exact live admission. */
    public interface WriteGuard { void run(Runnable operation); }
    public static final class Receipt {
        public final String identity;
        public final long previousRevision, revision;
        public final boolean changed;
        private Receipt(InternalRuntimeRecord previous, InternalRuntimeRecord current) {
            identity = current.identity; previousRevision = previous.revision; revision = current.revision;
            changed = previousRevision != revision;
        }
    }
    public static final class PersistenceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final Outcome outcome;
        /** Present only when storage ACKed but subsequent lane/guard publication failed. */
        public final transient Receipt committedReceipt;
        PersistenceException(Outcome outcome, Throwable cause, Receipt committedReceipt) {
            super("Internal runtime data requires reconciliation: " + outcome, cause);
            this.outcome = outcome; this.committedReceipt = committedReceipt;
        }
    }
    private final Backend backend;
    private final WriteGuard guard;
    private final Runnable workerCheck;
    private final Object writer = new Object();
    private final ThreadLocal<Transaction> active = new ThreadLocal<>();
    private final Map<String, Namespace> preferences = new ConcurrentHashMap<>();
    private volatile InternalRuntimeRecord acknowledged;
    private volatile Receipt lastReceipt;
    private volatile PersistenceException failed;
    private volatile boolean closed;

    public static InternalRuntimePreferences open(Backend backend, WriteGuard guard) {
        return new InternalRuntimePreferences(backend, guard, InternalRuntimePreferences::requireWorker);
    }
    InternalRuntimePreferences(Backend backend, WriteGuard guard, Runnable workerCheck) {
        this.backend = Objects.requireNonNull(backend); this.guard = Objects.requireNonNull(guard);
        this.workerCheck = Objects.requireNonNull(workerCheck); workerCheck.run();
        try { acknowledged = Objects.requireNonNull(backend.read()); }
        catch (Exception unavailable) { throw fence(Outcome.UNCERTAIN, unavailable); }
    }
    public static void requireWorker() {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Internal runtime-data writes require the owner worker");
    }
    public SharedPreferences preferences(String namespace) {
        requireOpen(); InternalRuntimeRecord.requireNamespace(namespace);
        return preferences.computeIfAbsent(namespace, Namespace::new);
    }
    /** Handle health only, not a disk validation or authority token. */
    public boolean isAvailable() { return failed == null && !closed; }

    /** Last acknowledged revision, not an authority token. Reads perform no disk IO. */
    public long revision() { requireOpen(); return acknowledged.revision; }
    /**
     * One immutable, coherent cross-namespace view with no disk IO or writer-lock acquisition.
     * On the active writer it includes staged changes and carries their base revision, not a new
     * durability claim. Other threads receive exactly one acknowledged record.
     */
    public InternalRuntimeRecord snapshot() {
        requireOpen(); Transaction transaction = active.get();
        return transaction == null ? acknowledged : new InternalRuntimeRecord(transaction.previous.identity,
                transaction.previous.revision, transaction.values);
    }
    /** Null until this handle completes its first outer transaction. */
    public Receipt lastReceipt() { requireOpen(); return lastReceipt; }
    /**
     * Register publication-only lane state while inside an outer transaction. Runs synchronously
     * under its admission after the sole durable ACK, before preference listeners. It must not
     * write source data or start another transaction. Failed transactions discard these callbacks.
     */
    public void afterCommit(Runnable publication) {
        requireOpen(); Transaction transaction = active.get();
        if (transaction == null || transaction.sealed)
            throw new IllegalStateException("Register lane publication inside an unsealed owner transaction");
        transaction.publications.add(Objects.requireNonNull(publication));
    }
    public void transaction(Runnable operation) { transaction(() -> { operation.run(); return null; }); }
    public <T> T transaction(Supplier<T> operation) {
        Objects.requireNonNull(operation); workerCheck.run(); requireOpen();
        Transaction nested = active.get();
        if (nested != null) {
            if (nested.sealed) throw new IllegalStateException("Lane publication cannot mutate acknowledged source data");
            try { return operation.get(); }
            catch (RuntimeException | Error failure) { nested.rollbackOnly = true; throw failure; }
        }
        Transaction transaction;
        synchronized (writer) {
            requireOpen();
            transaction = new Transaction(acknowledged);
            Thread ownerThread = Thread.currentThread();
            boolean[] entered = { false };
            try {
                guard.run(() -> {
                    if (Thread.currentThread() != ownerThread || entered[0])
                        throw new IllegalStateException("Runtime-data guard must invoke once synchronously");
                    entered[0] = true; requireOpen();
                    try {
                        InternalRuntimeRecord durable = backend.read();
                        if (!transaction.previous.equals(durable)) throw fence(Outcome.CONFLICT, null);
                    } catch (PersistenceException problem) { throw problem; }
                    catch (Exception unavailable) { throw fence(Outcome.UNCERTAIN, unavailable); }
                    active.set(transaction);
                    try {
                        transaction.result = operation.get();
                        if (transaction.rollbackOnly) throw new IllegalStateException("Nested runtime-data transaction failed");
                        InternalRuntimeRecord candidate = new InternalRuntimeRecord(transaction.previous.identity,
                                transaction.previous.revision, transaction.values);
                        InternalRuntimeRecord next = candidate.equals(transaction.previous) ? transaction.previous
                                : new InternalRuntimeRecord(candidate.identity, Math.addExact(candidate.revision, 1), transaction.values);
                        // Reject invalid/oversized values before asking the backend to mutate anything.
                        InternalRuntimeRecord.encode(next);
                        transaction.sealed = true;
                        if (next != transaction.previous) {
                            Outcome outcome;
                            try { outcome = backend.compareAndSet(transaction.previous, next); }
                            catch (Exception unavailable) { throw fence(Outcome.UNCERTAIN, unavailable); }
                            if (outcome != Outcome.COMMITTED) throw fence(outcome == null ? Outcome.UNCERTAIN : outcome, null);
                        }
                        transaction.current = next;
                        // Both publication and receipt are covered by the same write admission.
                        acknowledged = next; lastReceipt = new Receipt(transaction.previous, next);
                        for (Runnable publication : transaction.publications) publication.run();
                    } finally { active.remove(); }
                });
                if (!entered[0] || transaction.current == null)
                    throw new IllegalStateException("Runtime-data guard did not complete its operation");
            } catch (RuntimeException | Error problem) {
                // A guard which fails after durable success cannot invite a blind retry.
                if (transaction.current != null) throw fence(Outcome.COMMITTED, problem);
                throw problem;
            }
        }
        notifyChanges(transaction.previous, transaction.current);
        @SuppressWarnings("unchecked") T result = (T) transaction.result;
        return result;
    }
    /** Worker-only disk validation. A changed/lost established store fences this handle. */
    public void validate() {
        workerCheck.run();
        synchronized (writer) {
            requireOpen();
            try { if (!acknowledged.equals(backend.read())) throw fence(Outcome.CONFLICT, null); }
            catch (PersistenceException problem) { throw problem; }
            catch (Exception unavailable) { throw fence(Outcome.UNCERTAIN, unavailable); }
        }
    }
    /** No queued work exists here. Waits for an in-flight write; provider must drain before close. */
    @Override public void close() {
        if (active.get() != null) throw new IllegalStateException("Cannot close during an owned transaction");
        synchronized (writer) { if (!closed) { closed = true; backend.close(); } }
    }
    private void requireOpen() {
        PersistenceException problem = failed;
        if (problem != null) throw problem;
        if (closed) throw new IllegalStateException("Internal runtime data is closed");
    }
    private PersistenceException fence(Outcome outcome, Throwable cause) {
        PersistenceException problem = new PersistenceException(outcome, cause,
                outcome == Outcome.COMMITTED ? lastReceipt : null); failed = problem; return problem;
    }
    private Map<String, Object> read(String namespace) {
        requireOpen(); Transaction transaction = active.get();
        Map<String, Object> values = transaction == null ? acknowledged.namespaces().get(namespace)
                : transaction.values.get(namespace);
        return values == null ? Collections.emptyMap() : values;
    }
    private void notifyChanges(InternalRuntimeRecord previous, InternalRuntimeRecord current) {
        for (Namespace namespace : preferences.values()) {
            Map<String, Object> before = previous.namespaces().getOrDefault(namespace.name, Collections.emptyMap());
            Map<String, Object> after = current.namespaces().getOrDefault(namespace.name, Collections.emptyMap());
            Set<String> keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
            for (String key : keys) if (!Objects.equals(before.get(key), after.get(key)))
                for (SharedPreferences.OnSharedPreferenceChangeListener listener : namespace.listeners) {
                    try { listener.onSharedPreferenceChanged(namespace, key); }
                    catch (RuntimeException ignored) { /* Observer failure cannot undo or reclassify the ACK. */ }
                }
        }
    }
    private static final class Transaction {
        final InternalRuntimeRecord previous;
        final Map<String, Map<String, Object>> values = new TreeMap<>();
        InternalRuntimeRecord current;
        Object result;
        final List<Runnable> publications = new ArrayList<>();
        boolean rollbackOnly, sealed;
        Transaction(InternalRuntimeRecord previous) {
            this.previous = previous;
            for (Map.Entry<String, Map<String, Object>> entry : previous.namespaces().entrySet())
                values.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
    }
    private final class Namespace implements SharedPreferences {
        final String name;
        final Set<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArraySet<>();
        Namespace(String name) { this.name = name; }
        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(new TreeMap<>(read(name))); }
        @Override public String getString(String key, String fallback) { Object value = read(name).get(key); return value == null ? fallback : (String) value; }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = read(name).get(key);
            if (value == null) return fallback == null ? null : Collections.unmodifiableSet(new TreeSet<>(fallback));
            @SuppressWarnings("unchecked") Set<String> result = (Set<String>) value; return result;
        }
        @Override public int getInt(String key, int fallback) { Object value = read(name).get(key); return value == null ? fallback : (Integer) value; }
        @Override public long getLong(String key, long fallback) { Object value = read(name).get(key); return value == null ? fallback : (Long) value; }
        @Override public float getFloat(String key, float fallback) { Object value = read(name).get(key); return value == null ? fallback : (Float) value; }
        @Override public boolean getBoolean(String key, boolean fallback) { Object value = read(name).get(key); return value == null ? fallback : (Boolean) value; }
        @Override public boolean contains(String key) { return read(name).containsKey(key); }
        @Override public Editor edit() { requireOpen(); return new Changes(this); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { requireOpen(); listeners.add(Objects.requireNonNull(listener)); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
    }
    private final class Changes implements SharedPreferences.Editor {
        private final Namespace namespace;
        private final Map<String, Object> changes = new LinkedHashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear;
        Changes(Namespace namespace) { this.namespace = namespace; }
        private synchronized SharedPreferences.Editor put(String key, Object value) {
            InternalRuntimeRecord.requireKey(key);
            if (value == null) return remove(key);
            changes.put(key, InternalRuntimeRecord.copyValue(value)); removed.remove(key); return this;
        }
        @Override public SharedPreferences.Editor putString(String key, String value) { return put(key, value); }
        @Override public SharedPreferences.Editor putStringSet(String key, Set<String> value) { return put(key, value); }
        @Override public SharedPreferences.Editor putInt(String key, int value) { return put(key, value); }
        @Override public SharedPreferences.Editor putLong(String key, long value) { return put(key, value); }
        @Override public SharedPreferences.Editor putFloat(String key, float value) { return put(key, value); }
        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) { return put(key, value); }
        @Override public synchronized SharedPreferences.Editor remove(String key) {
            InternalRuntimeRecord.requireKey(key); changes.remove(key); removed.add(key); return this;
        }
        @Override public synchronized SharedPreferences.Editor clear() { clear = true; return this; }
        @Override public boolean commit() { apply(); return true; }
        @Override public void apply() {
            workerCheck.run(); requireOpen();
            Map<String, Object> edited; Set<String> deleted; boolean clearAll;
            synchronized (this) {
                edited = new LinkedHashMap<>(changes); deleted = new HashSet<>(removed); clearAll = clear;
                // Like Android's Editor, consume this batch once. Later editor reuse must not
                // replay an old clear/removal over data acknowledged by another transaction.
                changes.clear(); removed.clear(); clear = false;
            }
            transaction(() -> {
                Transaction transaction = Objects.requireNonNull(active.get());
                Map<String, Object> values = transaction.values.get(namespace.name);
                if (values == null) {
                    if (edited.isEmpty()) return;
                    values = new TreeMap<>(); transaction.values.put(namespace.name, values);
                }
                if (clearAll) values.clear();
                for (String key : deleted) values.remove(key);
                values.putAll(edited);
            });
        }
    }
}
