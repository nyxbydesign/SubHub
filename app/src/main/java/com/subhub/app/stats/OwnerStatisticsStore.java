package com.subhub.app.stats;

import android.content.SharedPreferences;
import com.subhub.app.internal.InternalRuntimePreferences;
import com.subhub.app.penance.OwnerPenanceLedger;
import com.subhub.app.penance.PenanceRuleSettings;
import java.util.Objects;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One explicit owner data handle. The host supplies admission and serial lifetime, never Context. */
public final class OwnerStatisticsStore implements StatsRepository.OwnerStorage, OwnerAppUsage.Store, OwnerPenanceLedger.Store {
    private final InternalRuntimePreferences data;
    private final Runnable admission, sessionAdmission;
    private final java.util.function.Consumer<Throwable> persistenceFailure;
    private volatile Throwable unavailable;
    private final StatsRepository.SessionState lane;
    private final StatsRepository repository;
    private final OwnerAppUsage usage;
    private final OwnerRetainedHistory retained;
    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);

    /** Read-only construction. First provisioning/openExisting belongs to the authorized host factory. */
    public OwnerStatisticsStore(InternalRuntimePreferences data, Runnable requireAdmittedWrite, Runnable requireAdmittedSessionWrite,
            java.util.function.Consumer<Throwable> persistenceFailure) {
        this.data = Objects.requireNonNull(data); admission = Objects.requireNonNull(requireAdmittedWrite);
        sessionAdmission = Objects.requireNonNull(requireAdmittedSessionWrite);
        this.persistenceFailure = Objects.requireNonNull(persistenceFailure);
        lane = new StatsRepository.SessionState();
        repository = StatsRepository.forOwner(this, lane);
        retained = new OwnerRetainedHistory(data.preferences("daily_history"));
        usage = new OwnerAppUsage(this, repository);
    }
    /** Pure token reads are safe anywhere; all mutators require this store's transaction on its writer. */
    public StatsRepository repository() { return repository; }
    public OwnerAppUsage appUsage() { return usage; }
    public OwnerPenanceLedger ledger(Supplier<PenanceRuleSettings> liveRules, BooleanSupplier censorEnabled) {
        return new OwnerPenanceLedger(this, repository, liveRules, censorEnabled);
    }
    @Override public SharedPreferences counters() { requireAvailable(); return data.preferences("statistics"); }
    @Override public SharedPreferences usage() { requireAvailable(); return data.preferences("usage"); }
    @Override public SharedPreferences history() { requireAvailable(); return data.preferences("usage_history"); }
    @Override public SharedPreferences ledger() { requireAvailable(); return data.preferences("assessed_ledger"); }
    @Override public void requireWrite() {
        admission.run(); requireAvailable();
        if (!writing.get()) throw new SecurityException("Owner counter write requires its atomic transaction");
    }
    @Override public void requireSessionWrite() { requireWrite(); sessionAdmission.run(); }
    @Override public void afterCommit(Runnable publication) { requireWrite(); data.afterCommit(publication); }
    @Override public <T> T transaction(Supplier<T> action) {
        admission.run(); requireAvailable();
        if (writing.get()) return data.transaction(action);
        StatsRepository.OwnedMemory before = repository.captureOwnedMemory();
        try {
            return data.transaction(() -> {
                writing.set(true);
                try { return action.get(); }
                finally { writing.remove(); }
            });
        } catch (RuntimeException | Error failure) {
            // Plain action/validation failures precede the CAS and the typed transaction rolled
            // them back. Restore in-process dedup/counter memory, not executable disk authority.
            if (failure instanceof Error || !data.isAvailable()) failPersistence(failure);
            else repository.restoreOwnedMemory(before);
            throw failure;
        }
    }
    private void requireAvailable() {
        if (unavailable != null) throw new IllegalStateException("Owned accounting is unavailable", unavailable);
    }
    private void failPersistence(Throwable cause) {
        if (unavailable != null) return;
        unavailable = cause; repository.invalidateOwnedSession();
        try { persistenceFailure.accept(cause); }
        catch (Throwable observerFailure) { if (observerFailure != cause) cause.addSuppressed(observerFailure); }
    }
    public void transaction(Runnable action) { transaction(() -> { action.run(); return null; }); }
    /** The COMMITTED evidence result leaves this method only after the enclosing owner ACK. */
    public StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp expected,
            Supplier<com.subhub.app.censor.engine.CensorEngine.EvidenceClaim> claim, java.util.Set<String> categories) {
        return transaction(() -> repository.recordEvidence(expected, claim, categories));
    }
    public StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp expected,
            Supplier<com.subhub.app.censor.engine.CensorEngine.EvidenceClaim> claim, java.util.Set<String> categories,
            OwnerObservation observation) {
        return transaction(() -> repository.recordEvidence(expected, claim, categories, Objects.requireNonNull(observation)));
    }
    public void beginSession(StatsRepository.NativeSession session) {
        sessionAdmission.run();
        transaction(() -> {
            if (!repository.beginOwnedSession(session)) throw new IllegalStateException("Owner session start not acknowledged");
        });
    }
    public void finishSession(String identity, long now) {
        sessionAdmission.run();
        admission.run();
        String checkpoint = data.preferences("daily_history").getString("session:" + identity, null);
        if (checkpoint != null) {
            StatsRepository.SessionEntry known = OwnerRetainedHistory.decode(checkpoint);
            long acknowledgedEnd = Math.addExact(known.getStartMillis(), Math.multiplyExact(known.getDurationSeconds(), 1000));
            if (now < acknowledgedEnd) throw new IllegalStateException("Owner stop predates acknowledged duration");
        }
        transaction(() -> {
            usage.checkpointHistory(now);
            if (!repository.finishOwnedSession(identity, now)) throw new IllegalStateException("Owner session end not acknowledged");
        });
    }
    /** Explicit broker recovery only: stop at the last durable observation, never elapsed downtime. */
    public void reconcileInterruptedSession(String expectedIdentity) {
        if (expectedIdentity == null || !expectedIdentity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid interrupted owner session");
        transaction(() -> {
            SharedPreferences saved = data.preferences("daily_history");
            if (saved.getBoolean("closed:" + expectedIdentity, false))
                throw new IllegalStateException("Interrupted session already has a closed history receipt");
            String encoded = saved.getString("session:" + expectedIdentity, null);
            if (encoded == null) throw new IllegalStateException("Interrupted session lacks an acknowledged observation");
            StatsRepository.SessionEntry known = OwnerRetainedHistory.decode(encoded);
            if (!expectedIdentity.equals(known.identity())) throw new SecurityException("Different interrupted session");
            long last = Math.addExact(known.getStartMillis(), Math.multiplyExact(known.getDurationSeconds(), 1000));
            if (!repository.reconcileOwnedInterruptedSession(new StatsRepository.NativeSession(expectedIdentity,
                    known.getStartMillis(), known.zone()), last))
                throw new IllegalStateException("Interrupted session reconciliation refused");
        });
    }
    @Override public void checkpoint(Map<String, ?> source, long observedAt, boolean durations) {
        requireWrite(); retained.checkpoint(source, observedAt, durations);
    }
    /** Call from an admitted tick or cleanup. It never creates an independent delayed writer. */
    public void checkpoint(long now) {
        sessionAdmission.run();
        transaction(() -> { retained.checkpoint(counters().getAll(), now, true); usage.checkpointHistory(now); });
    }
    /**
     * Run only on the broker's sole serial owner worker under generic data admission, then deliver
     * the immutable result to UI. Counters and lane state must not race a begin/finish publication.
     * This projection does no IO/source mutation, but is not an arbitrary-thread cached read.
     */
    public Snapshot snapshot(long now) {
        admission.run(); requireAvailable(); InternalRuntimePreferences.requireWorker();
        if (now <= 0) throw new IllegalArgumentException("Invalid observation clock");
        com.subhub.app.internal.InternalRuntimeRecord record = data.snapshot();
        SharedPreferences observed = new OwnerPreferenceSnapshot(record.namespaces().get("statistics"));
        StatsRepository readOnly = StatsRepository.forOwner(new StatsRepository.OwnerStorage() {
            @Override public SharedPreferences counters() { return observed; }
            @Override public void requireWrite() { throw new SecurityException("Immutable owner observation"); }
            @Override public void requireSessionWrite() { throw new SecurityException("Immutable owner observation"); }
            @Override public void afterCommit(Runnable ignored) { throw new SecurityException("Immutable owner observation"); }
            @Override public void checkpoint(Map<String, ?> ignored, long at, boolean durations) {
                throw new SecurityException("Immutable owner observation");
            }
        }, lane);
        StatsSnapshot stats = readOnly.retainedSnapshot(now);
        RetainedStatistics projection = OwnerRetainedHistory.project(record.namespaces()
                .getOrDefault("daily_history", java.util.Collections.emptyMap()), now);
        long earliest = 0;
        for (StatsRepository.SessionEntry session : projection.sessions())
            earliest = ActivityDateHistory.earliest(earliest, session.getStartMillis());
        readOnly.addActivityHistory(projection, earliest);
        if (stats.getCurrentSessionSeconds() > 0) projection.session(OwnerRetainedHistory.session(observed.getAll(), now));
        for (StatsRepository.SessionEntry session : readOnly.getSessionHistory()) projection.session(session);
        return new Snapshot(record.revision, stats.withRetained(projection), projection);
    }
    /** Immutable acknowledged receipt view. It cannot publish or consume notifications. */
    public AchievementManager achievements(AchievementManager.OwnerFacts facts) {
        admission.run(); requireAvailable();
        if (writing.get()) throw new IllegalStateException("Cannot expose staged achievement receipts");
        SharedPreferences observed = new OwnerPreferenceSnapshot(data.snapshot().namespaces().get("achievements"));
        return AchievementManager.forOwner(observed, facts);
    }
    /** No mutable manager escapes before the sole durable ACK, including on failure. */
    public AchievementManager reconcileAchievements(AchievementManager.OwnerFacts ownerFacts, long now) {
        if (writing.get()) throw new IllegalStateException("Achievement reconciliation must own its outer ACK");
        transaction(() -> {
            AchievementManager manager = AchievementManager.forOwnerReconciliation(data.preferences("achievements"), ownerFacts);
            Snapshot facts = snapshot(now);
            manager.reconcileHistory(facts.history.awards(false), java.util.Collections.singleton("internal"), facts.statistics, null);
            manager.checkRetainedAchievements(facts.statistics);
        });
        return achievements(ownerFacts);
    }
    public java.util.List<AchievementManager.Achievement> takeAchievementNotifications(AchievementManager.OwnerFacts facts) {
        if (writing.get()) throw new IllegalStateException("Notification consumption must own its outer ACK");
        return transaction(() -> AchievementManager.forOwnerReconciliation(data.preferences("achievements"), facts).takePendingNotifications());
    }
    public static final class Snapshot {
        public final long revision;
        public final StatsSnapshot statistics;
        private final RetainedStatistics history;
        private Snapshot(long revision, StatsSnapshot statistics, RetainedStatistics history) {
            this.revision = revision; this.statistics = statistics; this.history = history;
        }
        public Map<String, Map<String, Long>> days() { return history.days(); }
        public java.util.List<StatsRepository.SessionEntry> sessions() { return history.sessions(); }
    }
    /** Failure revokes this lane only. Persisted facts remain intact for explicit reconciliation. */
    public void fence() { repository.invalidateOwnedSession(); }
    /** Broker teardown only, after runtime/task/editor admissions and queued work have all drained. */
    public void close() {
        admission.run();
        if (repository.observedSessionStamp() != null) throw new IllegalStateException("Active owner data cannot close");
        data.close(); repository.invalidateOwnedSession();
    }
}
