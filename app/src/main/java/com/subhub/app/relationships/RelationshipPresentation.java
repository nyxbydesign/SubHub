package com.subhub.app.relationships;

import com.subhub.app.people.PersonId;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lifecycle-bound presentation of coordinator results; authorization stays in the provider. */
public final class RelationshipPresentation {
    public enum Mode { LEGACY, LOADING, READY, UNAVAILABLE }
    public enum RecoveryState { NONE, UNKNOWN, UNAVAILABLE, REFUSED, ABSENT_NEVER_CREATED }

    public interface Journal {
        boolean authoritativeSeen();
        boolean markAuthoritativeSeen();
        /** Current UI reference; re-acknowledge before dispatching a provider operation. */
        Saved read();
        boolean replace(Saved expected, Saved value);
    }

    /** Only an unresolved request or an operation reference is retained, never review/approval data. */
    public static final class Saved {
        public final String operationKey, domRef, subRef, revision, operationId, digest;
        private Saved(String key, String dom, String sub, String revision, String id, String digest) {
            operationKey = key; domRef = dom; subRef = sub; this.revision = revision;
            operationId = id; this.digest = digest;
        }
        public static Saved preparing(String key, String dom, String sub, String revision) {
            return new Saved(required(key), required(dom), required(sub), required(revision), null, null);
        }
        public static Saved operation(String id, String digest) {
            if (digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid operation digest");
            return new Saved(null, null, null, null, required(id), digest);
        }
        public boolean unresolved() { return operationKey != null; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Saved)) return false;
            Saved value = (Saved) other;
            return Objects.equals(operationKey, value.operationKey) && Objects.equals(domRef, value.domRef)
                    && Objects.equals(subRef, value.subRef) && Objects.equals(revision, value.revision)
                    && Objects.equals(operationId, value.operationId) && Objects.equals(digest, value.digest);
        }
        @Override public int hashCode() {
            return Objects.hash(operationKey, domRef, subRef, revision, operationId, digest);
        }
    }

    public static final class Access {
        public final boolean legacy;
        public final RelationshipUi.Provider provider;
        public final RelationshipUi.Snapshot snapshot;
        public final RelationshipRecoveryUi.RecoverySnapshot recovery;
        private final Journal journal;
        private Access(boolean legacy, RelationshipUi.Provider provider, RelationshipUi.Snapshot snapshot,
                RelationshipRecoveryUi.RecoverySnapshot recovery, Journal journal) {
            this.legacy = legacy; this.provider = provider; this.snapshot = snapshot;
            this.recovery = recovery; this.journal = journal;
        }
        public boolean current(Supplier<RelationshipUi.Provider> source) {
            return provider == source.get() && (!legacy || !journal.authoritativeSeen());
        }
    }

    /** The exact unbound default is the sole compatibility path, even across process recreation. */
    public static CompletableFuture<Access> access(RelationshipUi.Provider provider, Journal journal) {
        try {
            if (provider == RelationshipUi.unavailable()) {
                if (journal.authoritativeSeen()) return failed();
                return CompletableFuture.completedFuture(new Access(true, provider, null, null, journal));
            }
            if (!journal.markAuthoritativeSeen()) return failed();
            if (provider instanceof RelationshipRecoveryUi.Provider) {
                return Objects.requireNonNull(((RelationshipRecoveryUi.Provider) provider).recoverySnapshot()).thenApply(value -> {
                    if (value == null || !value.snapshot.supported)
                        throw new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE);
                    return new Access(false, provider, value.snapshot, value, journal);
                });
            }
            return Objects.requireNonNull(provider.snapshot()).thenApply(snapshot -> {
                if (snapshot == null || !snapshot.supported) throw new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE);
                return new Access(false, provider, snapshot, null, journal);
            });
        } catch (RuntimeException failure) { return failed(); }
    }

    /** Preserve the coordinator's exact order; display names, favorites and availability add no rows. */
    public static List<PersonId> rosterIds(RelationshipUi.Snapshot snapshot) {
        List<PersonId> ids = new ArrayList<>();
        for (RelationshipUi.Edge edge : snapshot.eligibleSubs) ids.add(edge.personId);
        return Collections.unmodifiableList(ids);
    }

    private final Supplier<RelationshipUi.Provider> source;
    private final Journal journal;
    private final Executor delivery;
    private final Supplier<String> context;
    private final BooleanSupplier mayLink;
    private final Consumer<RelationshipPresentation> changed;
    private boolean attached, busy;
    private long epoch;
    private String boundContext, domChoice, subChoice;
    private RelationshipUi.Provider provider;
    private Mode mode = Mode.LOADING;
    private RelationshipUi.Snapshot snapshot;
    private RelationshipUi.LocalReview review;
    private RelationshipUi.Operation operation;
    private RelationshipRecoveryUi.RecoverySnapshot recovery;
    private RelationshipRecoveryUi.OperationSummary summary;
    private RelationshipUi.LocalReview reviewBasis;
    private RecoveryState recoveryState = RecoveryState.NONE;
    private boolean unlinkUnavailable;
    private long completedUnlinks;
    private long rosterChanges;

    public RelationshipPresentation(Supplier<RelationshipUi.Provider> source, Journal journal,
            Executor delivery, Supplier<String> context, BooleanSupplier mayLink,
            Consumer<RelationshipPresentation> changed) {
        this.source = Objects.requireNonNull(source); this.journal = Objects.requireNonNull(journal);
        this.delivery = Objects.requireNonNull(delivery); this.context = Objects.requireNonNull(context);
        this.mayLink = Objects.requireNonNull(mayLink); this.changed = Objects.requireNonNull(changed);
    }

    public Mode mode() { return mode; }
    public boolean busy() { return busy; }
    public RelationshipUi.Snapshot snapshot() { return snapshot; }
    public RelationshipUi.LocalReview review() { return review; }
    public RelationshipUi.Operation operation() { return operation; }
    public RelationshipRecoveryUi.OperationSummary summary() { return summary; }
    public List<RelationshipRecoveryUi.OperationSummary> summaries() {
        return recovery == null ? Collections.emptyList() : recovery.summaries;
    }
    public boolean recoveryAware() { return provider instanceof RelationshipRecoveryUi.Provider; }
    public RecoveryState recoveryState() { return recoveryState; }
    public long reviewEpoch() { return epoch; }
    public boolean unlinkUnavailable() { return unlinkUnavailable; }
    public long completedUnlinks() { return completedUnlinks; }
    public long rosterChanges() { return rosterChanges; }
    public Saved saved() { return journal.read(); }
    public String domChoice() { return domChoice; }
    public String subChoice() { return subChoice; }

    public void refresh() {
        boundContext = context.get(); attached = boundContext != null;
        long request = ++epoch;
        provider = source.get(); snapshot = null; review = null; operation = null;
        recovery = null; summary = null; reviewBasis = null; recoveryState = RecoveryState.NONE;
        unlinkUnavailable = false;
        busy = attached; mode = Mode.LOADING;
        if (!attached) return;
        notifyChanged();
        observe(this, access(provider, journal),
                (owner, value, failure) -> owner.loaded(request, value, failure));
    }

    private void loaded(long request, Access value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (failure != null || value == null || !value.current(source)) { unavailable(); return; }
        if (value.legacy) { mode = Mode.LEGACY; notifyChanged(); return; }
        snapshot = value.snapshot; recovery = value.recovery; mode = Mode.READY;
        Saved saved = journal.read();
        if (saved != null && saved.unresolved()) {
            domChoice = saved.domRef; subChoice = saved.subRef;
        } else {
            if (!has(domChoice, RelationshipUi.Role.DOM)) domChoice = first(RelationshipUi.Role.DOM);
            if (!has(subChoice, RelationshipUi.Role.SUB)) subChoice = first(RelationshipUi.Role.SUB);
        }
        if (saved != null && recoveryAware()) {
            if (saved.unresolved()) { lookup(saved); return; }
            summary = findSummary(saved.operationId);
            if (summary != null) {
                if (!matches(saved, summary.operation)) { unavailable(); return; }
                if (terminal(summary.operation.state)) { applyStatus(saved, summary.operation, summary, true); return; }
                operation = summary.operation;
            } else recoveryState = RecoveryState.UNKNOWN;
        }
        notifyChanged();
    }

    public void detach() {
        attached = false; ++epoch; busy = false; snapshot = null; review = null; operation = null;
        recovery = null; summary = null; reviewBasis = null; recoveryState = RecoveryState.NONE;
    }

    public void choose(String domRef, String subRef) {
        if (mode != Mode.READY || !valid(epoch) || busy) return;
        if (!has(domRef, RelationshipUi.Role.DOM) || !has(subRef, RelationshipUi.Role.SUB)) return;
        if (Objects.equals(domChoice, domRef) && Objects.equals(subChoice, subRef)) return;
        domChoice = domRef; subChoice = subRef; ++epoch; review = null; operation = null;
        summary = null;
        notifyChanged();
    }

    public boolean canPrepare() {
        Saved saved = journal.read();
        return localAllowed()
                && snapshot.governingDom == null && has(domChoice, RelationshipUi.Role.DOM)
                && has(subChoice, RelationshipUi.Role.SUB)
                && (saved == null ? !hasPendingSummary() : saved.unresolved() && !recoveryAware());
    }

    private boolean localAllowed() {
        return mode == Mode.READY && !busy && valid(epoch) && mayLink.getAsBoolean();
    }

    public boolean canConfirm() {
        Saved saved = journal.read();
        return localAllowed() && review != null && snapshot.governingDom == null
                && saved != null && !saved.unresolved() && saved.operationId.equals(review.operationId)
                && saved.digest.equals(review.requestDigest) && review.domParticipantRef.equals(domChoice)
                && review.subParticipantRef.equals(subChoice) && has(domChoice, RelationshipUi.Role.DOM)
                && has(subChoice, RelationshipUi.Role.SUB);
    }

    /** A proven absence permits only an explicit retry of the original versioned request. */
    public boolean canRetryPreparation() {
        Saved saved = journal.read();
        return recoveryAware() && localAllowed() && recoveryState == RecoveryState.ABSENT_NEVER_CREATED
                && saved != null && saved.unresolved() && saved.operationKey.startsWith("fr1.")
                && snapshot.governingDom == null && saved.domRef.equals(domChoice) && saved.subRef.equals(subChoice)
                && has(domChoice, RelationshipUi.Role.DOM) && has(subChoice, RelationshipUi.Role.SUB);
    }

    public boolean canUnlink(RelationshipUi.Edge expected) {
        if (!recoveryAware() || !localAllowed() || expected == null || expected.delivery != RelationshipUi.Delivery.LOCAL
                || !"ACTIVE".equals(expected.state) || !has(expected.domParticipantRef, RelationshipUi.Role.DOM)
                || !has(expected.subParticipantRef, RelationshipUi.Role.SUB)) return false;
        if (snapshot.governingDom == expected) return true;
        for (RelationshipUi.Edge edge : snapshot.eligibleSubs) if (edge == expected) return true;
        return false;
    }

    public void unlink(RelationshipUi.Edge expected, long expectedEpoch) {
        if (expectedEpoch != epoch || !canUnlink(expected)) return;
        long request = ++epoch;
        busy = true; review = null; unlinkUnavailable = false; notifyChanged();
        CompletableFuture<Void> future;
        try { future = ((RelationshipRecoveryUi.Provider) provider).unlink(expected.relationshipId, expected.generation); }
        catch (RuntimeException failure) { busy = false; unlinkUnavailable = true; notifyChanged(); return; }
        if (future == null) { busy = false; unlinkUnavailable = true; notifyChanged(); return; }
        observe(this, future, (owner, value, failure) -> owner.unlinked(request, failure));
    }

    private void unlinked(long request, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (failure != null) { unlinkUnavailable = true; notifyChanged(); return; }
        completedUnlinks++;
        rosterChanges++;
        refresh();
    }

    private boolean hasPendingSummary() {
        for (RelationshipUi.Operation value : snapshot.operations)
            if (value.state == RelationshipUi.OperationState.PREPARED
                    || value.state == RelationshipUi.OperationState.PENDING) return true;
        return false;
    }

    public void prepare() {
        Saved saved = journal.read();
        if (saved != null && saved.unresolved() && recoveryAware()) {
            if (!recoverAllowed(saved)) return;
            if (canRetryPreparation()) submitPreparation(saved, false);
            else lookup(saved);
            return;
        }
        if (!canPrepare()) return;
        boolean acknowledged = false;
        if (saved == null) {
            String key;
            try {
                key = recoveryAware() ? RelationshipPreparationKey.create(snapshot.revision) : UUID.randomUUID().toString();
            } catch (RuntimeException failure) { unavailable(); return; }
            saved = Saved.preparing(key, domChoice, subChoice, snapshot.revision);
            if (!journal.replace(null, saved)) { unavailable(); return; }
            acknowledged = true;
        }
        if (!saved.unresolved() || !saved.domRef.equals(domChoice) || !saved.subRef.equals(subChoice)) return;
        submitPreparation(saved, acknowledged);
    }

    private void submitPreparation(Saved saved, boolean acknowledged) {
        if (!acknowledged && !journal.replace(saved, saved)) { unavailable(); return; }
        final Saved requestSaved = saved;
        final long request = ++epoch;
        busy = true; review = null; reviewBasis = null; operation = null; summary = null; recoveryState = RecoveryState.NONE; notifyChanged();
        CompletableFuture<RelationshipUi.LocalReview> future;
        try { future = provider.prepareLocal(saved.operationKey, saved.domRef, saved.subRef, saved.revision); }
        catch (RuntimeException failure) { unavailable(); return; }
        if (future == null) { unavailable(); return; }
        observe(this, future, (owner, value, failure) -> owner.prepared(request, requestSaved, value, failure));
    }

    private void prepared(long request, Saved requestSaved, RelationshipUi.LocalReview value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (failure != null || value == null || !mayLink.getAsBoolean()
                || !requestSaved.domRef.equals(value.domParticipantRef)
                || !requestSaved.subRef.equals(value.subParticipantRef)
                || !requestSaved.domRef.equals(domChoice) || !requestSaved.subRef.equals(subChoice)
                || !journal.replace(requestSaved, Saved.operation(value.operationId, value.requestDigest))) {
            unavailable(); return;
        }
        review = value; reviewBasis = value; notifyChanged();
    }

    /** The expected object binds a credential callback to the exact review the user saw. */
    public void confirm(RelationshipUi.LocalReview expected) {
        confirm(expected, epoch);
    }
    public void confirm(RelationshipUi.LocalReview expected, long expectedEpoch) {
        Saved saved = journal.read();
        if (expectedEpoch != epoch || !canConfirm() || expected == null || expected != review || saved == null || saved.unresolved()
                || !saved.operationId.equals(expected.operationId) || !saved.digest.equals(expected.requestDigest)) return;
        run(saved, () -> provider.confirmLocal(saved.operationId, saved.digest));
    }

    public void resume() {
        resume(journal.read());
    }
    public void resume(Saved expected) {
        if (expected != null && expected.unresolved() && recoveryAware()) {
            if (recoverAllowed(expected)) lookup(expected);
            return;
        }
        if (!actionAllowed(expected) || !expected.equals(journal.read())) return;
        if (recoveryAware()) {
            RelationshipRecoveryUi.OperationSummary current = boundSummary(expected);
            if (current == null || current.kind == RelationshipRecoveryUi.OperationKind.LOCAL_LINK) {
                obtainReview(expected, current); return;
            }
        }
        run(expected, () -> provider.resume(expected.operationId));
    }

    private boolean recoverAllowed(Saved saved) {
        return saved != null && mode == Mode.READY && !busy && valid(epoch) && saved.equals(journal.read());
    }

    private void lookup(Saved saved) {
        if (!journal.replace(saved, saved)) { unavailable(); return; }
        long request = ++epoch;
        busy = true; review = null; operation = null; summary = null; recoveryState = RecoveryState.NONE; notifyChanged();
        CompletableFuture<RelationshipRecoveryUi.PreparationLookup> future;
        try {
            future = ((RelationshipRecoveryUi.Provider) provider).lookupPreparation(
                    saved.operationKey, saved.domRef, saved.subRef, saved.revision);
        } catch (RuntimeException failure) { unavailable(); return; }
        if (future == null) { unavailable(); return; }
        observe(this, future, (owner, value, failure) -> owner.lookedUp(request, saved, value, failure));
    }

    private void lookedUp(long request, Saved saved, RelationshipRecoveryUi.PreparationLookup value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (!saved.equals(journal.read())) { unavailable(); return; }
        if (failure != null || value == null) { recoveryState = RecoveryState.UNAVAILABLE; notifyChanged(); return; }
        if (value.kind != RelationshipRecoveryUi.PreparationLookup.Kind.FOUND) {
            switch (value.kind) {
                case REFUSED: recoveryState = RecoveryState.REFUSED; break;
                case UNAVAILABLE: recoveryState = RecoveryState.UNAVAILABLE; break;
                case ABSENT_NEVER_CREATED: recoveryState = RecoveryState.ABSENT_NEVER_CREATED; break;
                default: recoveryState = RecoveryState.UNKNOWN;
            }
            notifyChanged(); return;
        }
        RelationshipRecoveryUi.OperationSummary found = value.summary;
        RelationshipRecoveryUi.OperationSummary listed = findSummary(found.operation.operationId);
        if (found.kind != RelationshipRecoveryUi.OperationKind.LOCAL_LINK
                || found.delivery != RelationshipUi.Delivery.LOCAL || !saved.domRef.equals(found.domParticipantRef)
                || !saved.subRef.equals(found.subParticipantRef) || listed != null && !sameScope(listed, found)) {
            recoveryState = RecoveryState.REFUSED; notifyChanged(); return;
        }
        Saved reference = Saved.operation(found.operation.operationId, found.operation.requestDigest);
        if (!journal.replace(saved, reference)) { unavailable(); return; }
        summary = found; domChoice = found.domParticipantRef; subChoice = found.subParticipantRef;
        if (found.operation.state == RelationshipUi.OperationState.PREPARED) obtainReview(reference, found);
        else applyStatus(reference, found.operation, found);
    }

    private void obtainReview(Saved saved, RelationshipRecoveryUi.OperationSummary expected) {
        if (!journal.replace(saved, saved)) { unavailable(); return; }
        if (reviewBasis != null && !saved.operationId.equals(reviewBasis.operationId)) reviewBasis = null;
        summary = expected;
        long request = ++epoch;
        busy = true; review = null; operation = null; recoveryState = RecoveryState.NONE; notifyChanged();
        CompletableFuture<RelationshipRecoveryUi.ReviewResult> future;
        try { future = ((RelationshipRecoveryUi.Provider) provider).reviewLocal(saved.operationId, saved.digest); }
        catch (RuntimeException failure) { unavailable(); return; }
        if (future == null) { unavailable(); return; }
        observe(this, future, (owner, value, failure) -> owner.reviewed(request, saved, expected, value, failure));
    }

    private void reviewed(long request, Saved saved, RelationshipRecoveryUi.OperationSummary expected,
            RelationshipRecoveryUi.ReviewResult value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (!saved.equals(journal.read())) { unavailable(); return; }
        if (failure != null || value == null) { recoveryState = RecoveryState.UNAVAILABLE; notifyChanged(); return; }
        if (value.kind != RelationshipRecoveryUi.ReviewResult.Kind.REVIEW
                && value.kind != RelationshipRecoveryUi.ReviewResult.Kind.STATUS) {
            recoveryState = value.kind == RelationshipRecoveryUi.ReviewResult.Kind.REFUSED ? RecoveryState.REFUSED
                    : value.kind == RelationshipRecoveryUi.ReviewResult.Kind.UNAVAILABLE ? RecoveryState.UNAVAILABLE : RecoveryState.UNKNOWN;
            notifyChanged(); return;
        }
        RelationshipRecoveryUi.OperationSummary current = value.summary;
        if (!matches(saved, current.operation) || expected != null && !sameScope(expected, current)
                || reviewBasis != null && (current.kind != RelationshipRecoveryUi.OperationKind.LOCAL_LINK
                        || current.delivery != RelationshipUi.Delivery.LOCAL
                        || reviewBasis.originalDeadline != current.operation.originalDeadline
                        || !reviewBasis.domParticipantRef.equals(current.domParticipantRef)
                        || !reviewBasis.subParticipantRef.equals(current.subParticipantRef))) {
            recoveryState = RecoveryState.REFUSED; notifyChanged(); return;
        }
        if (value.kind == RelationshipRecoveryUi.ReviewResult.Kind.STATUS) {
            applyStatus(saved, current.operation, current); return;
        }
        RelationshipUi.LocalReview fresh = value.review;
        if (!mayLink.getAsBoolean() || !has(fresh.domParticipantRef, RelationshipUi.Role.DOM)
                || !has(fresh.subParticipantRef, RelationshipUi.Role.SUB)
                || snapshot.governingDom != null || reviewBasis != null && !sameReviewScope(reviewBasis, fresh)) {
            recoveryState = RecoveryState.REFUSED; notifyChanged(); return;
        }
        summary = current; operation = current.operation; review = fresh; reviewBasis = fresh;
        domChoice = fresh.domParticipantRef; subChoice = fresh.subParticipantRef;
        notifyChanged();
    }

    /** A user may observe an exact current summary without preparing or authorizing a new link. */
    public void resumeSummary(RelationshipUi.Operation expected) {
        if (mode != Mode.READY || busy || !valid(epoch) || snapshot == null) return;
        if (recoveryAware()) {
            if (recovery == null) return;
            for (int index = 0; index < snapshot.operations.size(); index++)
                if (snapshot.operations.get(index) == expected) { resumeSummary(recovery.summaries.get(index)); return; }
            return;
        }
        if (mode != Mode.READY || busy || !valid(epoch) || journal.read() != null || expected == null
                || expected.state != RelationshipUi.OperationState.PREPARED
                        && expected.state != RelationshipUi.OperationState.PENDING) return;
        boolean current = false;
        for (RelationshipUi.Operation value : snapshot.operations) if (value == expected) current = true;
        if (!current) return;
        Saved reference = Saved.operation(expected.operationId, expected.requestDigest);
        if (!journal.replace(null, reference)) { unavailable(); return; }
        resume(reference);
    }

    public void resumeSummary(RelationshipRecoveryUi.OperationSummary expected) {
        if (mode != Mode.READY || busy || !valid(epoch) || journal.read() != null || expected == null || recovery == null
                || !pending(expected.operation.state) || !recovery.summaries.contains(expected)) return;
        Saved reference = Saved.operation(expected.operation.operationId, expected.operation.requestDigest);
        if (!journal.replace(null, reference)) { unavailable(); return; }
        summary = expected;
        resume(reference);
    }

    public void cancel() {
        cancel(journal.read());
    }
    public void cancel(Saved expected) {
        if (!actionAllowed(expected) || !expected.equals(journal.read())) return;
        run(expected, () -> provider.cancel(expected.operationId));
    }

    private boolean actionAllowed(Saved saved) {
        return saved != null && !saved.unresolved() && mode == Mode.READY && !busy && valid(epoch);
    }

    private void run(Saved saved, Supplier<CompletableFuture<RelationshipUi.Operation>> action) {
        if (!journal.replace(saved, saved)) { unavailable(); return; }
        long request = ++epoch;
        busy = true; review = null; operation = null; recoveryState = RecoveryState.NONE; notifyChanged();
        CompletableFuture<RelationshipUi.Operation> future;
        try { future = action.get(); }
        catch (RuntimeException failure) { unavailable(); return; }
        if (future == null) { unavailable(); return; }
        observe(this, future, (owner, value, failure) -> owner.completed(request, saved, value, failure));
    }

    private void completed(long request, Saved saved, RelationshipUi.Operation value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        RelationshipRecoveryUi.OperationSummary expected = boundSummary(saved);
        if (failure != null || value == null || !matches(saved, value) || !saved.equals(journal.read())
                || expected != null && expected.operation.originalDeadline != value.originalDeadline
                || reviewBasis != null && reviewBasis.originalDeadline != value.originalDeadline) {
            unavailable(); return;
        }
        applyStatus(saved, value, expected);
    }

    private void applyStatus(Saved saved, RelationshipUi.Operation value, RelationshipRecoveryUi.OperationSummary typed) {
        applyStatus(saved, value, typed, false);
    }

    private void applyStatus(Saved saved, RelationshipUi.Operation value,
            RelationshipRecoveryUi.OperationSummary typed, boolean authoritativeSnapshot) {
        if (terminal(value.state) && !journal.replace(saved, null)) { unavailable(); return; }
        operation = value; summary = typed; review = null; recoveryState = RecoveryState.NONE;
        if (value.state == RelationshipUi.OperationState.ACTIVE) {
            rosterChanges++;
            if (!authoritativeSnapshot) { refresh(); return; }
        }
        notifyChanged();
    }

    private static boolean terminal(RelationshipUi.OperationState state) {
        return !pending(state) && state != RelationshipUi.OperationState.UNAVAILABLE;
    }

    private static boolean pending(RelationshipUi.OperationState state) {
        return state == RelationshipUi.OperationState.PREPARED || state == RelationshipUi.OperationState.PENDING;
    }
    private static boolean matches(Saved saved, RelationshipUi.Operation value) {
        return saved.operationId.equals(value.operationId) && saved.digest.equals(value.requestDigest);
    }
    private RelationshipRecoveryUi.OperationSummary findSummary(String id) {
        if (recovery != null) for (RelationshipRecoveryUi.OperationSummary value : recovery.summaries)
            if (value.operation.operationId.equals(id)) return value;
        return null;
    }
    private RelationshipRecoveryUi.OperationSummary boundSummary(Saved saved) {
        if (summary != null && summary.operation.operationId.equals(saved.operationId)) return summary;
        return findSummary(saved.operationId);
    }
    private static boolean sameScope(RelationshipRecoveryUi.OperationSummary first, RelationshipRecoveryUi.OperationSummary second) {
        return first.operation.operationId.equals(second.operation.operationId)
                && first.operation.requestDigest.equals(second.operation.requestDigest)
                && first.operation.originalDeadline == second.operation.originalDeadline
                && first.kind == second.kind && first.delivery == second.delivery
                && Objects.equals(first.domParticipantRef, second.domParticipantRef)
                && Objects.equals(first.subParticipantRef, second.subParticipantRef);
    }
    private static boolean sameReviewScope(RelationshipUi.LocalReview first, RelationshipUi.LocalReview second) {
        return first.operationId.equals(second.operationId) && first.requestDigest.equals(second.requestDigest)
                && first.originalDeadline == second.originalDeadline && first.scopeDigest.equals(second.scopeDigest)
                && first.domParticipantRef.equals(second.domParticipantRef) && first.subParticipantRef.equals(second.subParticipantRef)
                && first.scopeSummary.equals(second.scopeSummary);
    }

    private interface Completion<T> {
        void accept(RelationshipPresentation owner, T value, Throwable failure);
    }

    /** Pending provider work must not retain a detached page through its presentation observer. */
    private static <T> void observe(RelationshipPresentation owner, CompletableFuture<T> future, Completion<T> completion) {
        WeakReference<RelationshipPresentation> reference = new WeakReference<>(owner);
        future.whenComplete((value, failure) -> {
            RelationshipPresentation current = reference.get();
            if (current != null) current.delivery.execute(() -> completion.accept(current, value, failure));
        });
    }

    private boolean valid(long request) {
        return attached && epoch == request && Objects.equals(boundContext, context.get())
                && provider == source.get();
    }
    private void unavailable() {
        mode = Mode.UNAVAILABLE; busy = false; snapshot = null; review = null; operation = null;
        recovery = null; summary = null;
        notifyChanged();
    }
    private void notifyChanged() { if (attached) changed.accept(this); }
    private boolean has(String ref, RelationshipUi.Role role) {
        if (snapshot == null || ref == null) return false;
        for (RelationshipUi.Participant value : snapshot.localParticipants)
            if (value.ref.equals(ref) && value.role == role) return true;
        return false;
    }
    private String first(RelationshipUi.Role role) {
        for (RelationshipUi.Participant value : snapshot.localParticipants)
            if (value.role == role) return value.ref;
        return null;
    }
    private static String required(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing operation reference");
        return value;
    }
    private static <T> CompletableFuture<T> failed() {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE));
        return result;
    }
}
