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
        private final Journal journal;
        private Access(boolean legacy, RelationshipUi.Provider provider, RelationshipUi.Snapshot snapshot, Journal journal) {
            this.legacy = legacy; this.provider = provider; this.snapshot = snapshot; this.journal = journal;
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
                return CompletableFuture.completedFuture(new Access(true, provider, null, journal));
            }
            if (!journal.markAuthoritativeSeen()) return failed();
            return Objects.requireNonNull(provider.snapshot()).thenApply(snapshot -> {
                if (snapshot == null || !snapshot.supported) throw new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE);
                return new Access(false, provider, snapshot, journal);
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
    public Saved saved() { return journal.read(); }
    public String domChoice() { return domChoice; }
    public String subChoice() { return subChoice; }

    public void refresh() {
        boundContext = context.get(); attached = boundContext != null;
        long request = ++epoch;
        provider = source.get(); snapshot = null; review = null; operation = null;
        busy = attached; mode = Mode.LOADING;
        if (!attached) return;
        notifyChanged();
        observe(this, access(provider, journal),
                (owner, value, failure) -> owner.loaded(request, value, failure));
    }

    private void loaded(long request, Access value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (failure != null || !value.current(source)) { unavailable(); return; }
        if (value.legacy) { mode = Mode.LEGACY; notifyChanged(); return; }
        snapshot = value.snapshot; mode = Mode.READY;
        Saved saved = journal.read();
        if (saved != null && saved.unresolved()) {
            domChoice = saved.domRef; subChoice = saved.subRef;
        } else {
            if (!has(domChoice, RelationshipUi.Role.DOM)) domChoice = first(RelationshipUi.Role.DOM);
            if (!has(subChoice, RelationshipUi.Role.SUB)) subChoice = first(RelationshipUi.Role.SUB);
        }
        notifyChanged();
    }

    public void detach() {
        attached = false; ++epoch; busy = false; snapshot = null; review = null; operation = null;
    }

    public void choose(String domRef, String subRef) {
        if (mode != Mode.READY || !valid(epoch) || busy) return;
        if (!has(domRef, RelationshipUi.Role.DOM) || !has(subRef, RelationshipUi.Role.SUB)) return;
        if (Objects.equals(domChoice, domRef) && Objects.equals(subChoice, subRef)) return;
        domChoice = domRef; subChoice = subRef; ++epoch; review = null; operation = null;
        notifyChanged();
    }

    public boolean canPrepare() {
        return mode == Mode.READY && !busy && valid(epoch) && mayLink.getAsBoolean()
                && snapshot.governingDom == null && has(domChoice, RelationshipUi.Role.DOM)
                && has(subChoice, RelationshipUi.Role.SUB)
                && (journal.read() != null || !hasPendingSummary());
    }

    private boolean hasPendingSummary() {
        for (RelationshipUi.Operation value : snapshot.operations)
            if (value.state == RelationshipUi.OperationState.PREPARED
                    || value.state == RelationshipUi.OperationState.PENDING) return true;
        return false;
    }

    public void prepare() {
        if (!canPrepare()) return;
        Saved saved = journal.read();
        boolean acknowledged = false;
        if (saved == null) {
            saved = Saved.preparing(UUID.randomUUID().toString(), domChoice, subChoice, snapshot.revision);
            if (!journal.replace(null, saved)) { unavailable(); return; }
            acknowledged = true;
        }
        if (!saved.unresolved() || !saved.domRef.equals(domChoice) || !saved.subRef.equals(subChoice)) return;
        if (!acknowledged && !journal.replace(saved, saved)) { unavailable(); return; }
        final Saved requestSaved = saved;
        final long request = ++epoch;
        busy = true; review = null; operation = null; notifyChanged();
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
        review = value; notifyChanged();
    }

    /** The expected object binds a credential callback to the exact review the user saw. */
    public void confirm(RelationshipUi.LocalReview expected) {
        Saved saved = journal.read();
        if (!canPrepare() || expected == null || expected != review || saved == null || saved.unresolved()
                || !saved.operationId.equals(expected.operationId) || !saved.digest.equals(expected.requestDigest)) return;
        run(saved, () -> provider.confirmLocal(saved.operationId, saved.digest));
    }

    public void resume() {
        resume(journal.read());
    }
    public void resume(Saved expected) {
        if (!actionAllowed(expected) || !expected.equals(journal.read())) return;
        run(expected, () -> provider.resume(expected.operationId));
    }

    /** A user may observe an exact current summary without preparing or authorizing a new link. */
    public void resumeSummary(RelationshipUi.Operation expected) {
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
        busy = true; review = null; operation = null; notifyChanged();
        CompletableFuture<RelationshipUi.Operation> future;
        try { future = action.get(); }
        catch (RuntimeException failure) { unavailable(); return; }
        if (future == null) { unavailable(); return; }
        observe(this, future, (owner, value, failure) -> owner.completed(request, saved, value, failure));
    }

    private void completed(long request, Saved saved, RelationshipUi.Operation value, Throwable failure) {
        if (!valid(request)) return;
        busy = false;
        if (failure != null || value == null || !saved.operationId.equals(value.operationId)
                || !saved.digest.equals(value.requestDigest) || !saved.equals(journal.read())) {
            unavailable(); return;
        }
        boolean terminal = value.state != RelationshipUi.OperationState.PREPARED
                && value.state != RelationshipUi.OperationState.PENDING;
        if (terminal && !journal.replace(saved, null)) { unavailable(); return; }
        operation = value; notifyChanged();
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
