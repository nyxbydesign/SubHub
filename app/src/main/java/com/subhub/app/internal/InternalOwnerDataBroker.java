package com.subhub.app.internal;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.people.InternalEditorAuthority;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.InternalVisibilityBarrier;
import com.subhub.app.people.InternalRuntimePresentationSnapshot;
import com.subhub.app.penance.InternalPenanceRules;
import com.subhub.app.penance.OwnerPenanceLedger;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.OwnedResourceRegistry;
import com.subhub.app.stats.OwnerStatisticsStore;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.tasks.TaskNoticeLedger;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.StatsRepository;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Typed INTERNAL-only provider shared by Tasks, editors and successive local sessions. The host
 * supplies its already established InternalSubData instance and explicit first-create/upgrade
 * decision. A normal lookup/open must pass OPEN_EXISTING; absent data never authorizes provisioning.
 */
public final class InternalOwnerDataBroker {
    private static final String REGISTRY = "subhub_internal_owner_data_registry", STATE = "runtime_v1";
    private static final Map<InternalSubData, InternalOwnerDataBroker> OPEN = new IdentityHashMap<>();
    private final InternalSubData host;
    private final Context physical;
    private final Predicate<InternalSubRecord> taskPolicy;
    private final SerialOwnerDataBroker<Handle> broker;
    private volatile Handle observedHandle;
    private final ThreadLocal<TaskAccess> taskCommand = new ThreadLocal<>();
    private final ThreadLocal<EditorAccess> editorCommand = new ThreadLocal<>();
    private final ThreadLocal<RetiredTaskNotices> noticeCleanup = new ThreadLocal<>();

    /**
     * Memory-only attachment. Call once at host creation/open; actual storage work runs on the host
     * worker. A recognized PROFILE_ONLY_V1_UPGRADE is a host schema decision, not an open fallback.
     * Preserve this singleton until a successful whole-broker drain; never replace it per session.
     */
    public static InternalOwnerDataBroker attach(Context physical, InternalSubData host,
            OwnerDataProvisioning.Decision decision, Predicate<InternalSubRecord> taskPolicy) {
        Objects.requireNonNull(host); Objects.requireNonNull(decision); Objects.requireNonNull(taskPolicy);
        Context app = Objects.requireNonNull(physical).getApplicationContext();
        if (app instanceof InternalSubContext) throw new SecurityException("Physical host required");
        synchronized (OPEN) {
            InternalOwnerDataBroker existing = OPEN.get(host);
            if (existing != null) return existing;
            InternalOwnerDataBroker value = new InternalOwnerDataBroker(app, host, decision, taskPolicy);
            OPEN.put(host, value); return value;
        }
    }
    public static InternalOwnerDataBroker attached(InternalSubData host) {
        synchronized (OPEN) {
            InternalOwnerDataBroker value = OPEN.get(Objects.requireNonNull(host));
            if (value == null) throw new IllegalStateException("Owner-data broker not attached by the host");
            return value;
        }
    }
    private InternalOwnerDataBroker(Context physical, InternalSubData host,
            OwnerDataProvisioning.Decision decision, Predicate<InternalSubRecord> taskPolicy) {
        this.host = host; this.physical = physical; this.taskPolicy = taskPolicy;
        OwnerDataProvisioning provisioning = new OwnerDataProvisioning();
        broker = new SerialOwnerDataBroker<>(host::execute, new SerialOwnerDataBroker.Policy() {
            @Override public void task(String generation) { currentTask(generation); }
            @Override public void read(String generation) { currentOnWorker(generation); }
            @Override public void visibility(String generation, long revision) { admitVisibility(generation, revision); }
            @Override public void editor(String generation, long revision) {
                InternalEditorAuthority.admit(physical, currentOnWorker(generation), generation, revision);
            }
        }, guards -> {
            SharedPreferences registry = physical.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE);
            FileInternalRuntimeBackend backend = provisioning.open(decision, new OwnerDataProvisioning.Evidence() {
                @Override public String read() {
                    Object state = registry.getAll().get(STATE);
                    if (state == null) return "";
                    if (!(state instanceof String)) throw new IllegalStateException("Invalid owner-data registry");
                    return (String) state;
                }
                @Override public boolean commit(String state) { return registry.edit().putString(STATE, state).commit(); }
            }, new OwnerDataProvisioning.Factory<FileInternalRuntimeBackend>() {
                @Override public FileInternalRuntimeBackend openExisting() throws Exception {
                    return FileInternalRuntimeBackend.openExisting(host.storage);
                }
                @Override public FileInternalRuntimeBackend provisionNew() throws Exception {
                    return FileInternalRuntimeBackend.provisionNew(host.storage);
                }
                @Override public void validate(FileInternalRuntimeBackend backend) throws Exception { backend.read(); }
            });
            try {
                InternalRuntimePreferences preferences = InternalRuntimePreferences.open(backend, action -> {
                    guards.requireData(); action.run(); guards.requireData();
                });
                Handle handle = new Handle(preferences, new OwnerStatisticsStore(preferences, guards::requireData, guards::requireSession, guards::dataUnavailable),
                        new TaskNoticeLedger(preferences.preferences("task_notices"), guards::requireData), guards);
                observedHandle = handle; return handle;
            } catch (RuntimeException | Error failed) { backend.close(); throw failed; }
        });
    }
    private static final class Handle implements AutoCloseable {
        final InternalRuntimePreferences preferences;
        final OwnerStatisticsStore statistics;
        final TaskNoticeLedger notices;
        final SerialOwnerDataBroker.Guards guards;
        private volatile long acknowledgedRevision;
        Handle(InternalRuntimePreferences preferences, OwnerStatisticsStore statistics, TaskNoticeLedger notices,
                SerialOwnerDataBroker.Guards guards) {
            this.preferences = preferences; this.statistics = statistics; this.notices = notices; this.guards = guards;
            acknowledgedRevision = preferences.revision();
        }
        // Read revision only after the outer transaction returned, never from a post-ACK callback
        // which can still fail its enclosing guard. During IO observers retain the previous hint.
        <T> T observeAck(java.util.function.Supplier<T> operation) {
            T result = operation.get(); acknowledgedRevision = preferences.revision(); return result;
        }
        <T> T atomic(java.util.function.Supplier<T> operation) {
            try { return observeAck(() -> preferences.transaction(operation)); }
            catch (RuntimeException | Error failure) {
                if (failure instanceof Error || !preferences.isAvailable()) { statistics.fence(); guards.dataUnavailable(failure); }
                throw failure;
            }
        }
        void atomic(Runnable operation) { atomic(() -> { operation.run(); return null; }); }
        @Override public void close() { statistics.close(); }
    }
    /** Await this ACK before the host publishes first-create/upgrade readiness. Never block main. */
    public CompletableFuture<Void> initialize() { return broker.initialize(); }
    public <T> CompletableFuture<T> submitTask(String generation, Function<TaskAccess, T> command) {
        Objects.requireNonNull(command);
        return broker.submitTask(generation, handle -> {
            TaskAccess access = new TaskAccess(generation, handle);
            taskCommand.set(access);
            try { return command.apply(access); }
            finally { taskCommand.remove(); }
        });
    }
    public <T> CompletableFuture<T> submitEditor(String generation, long controllerRevision, Function<EditorAccess, T> command) {
        Objects.requireNonNull(command);
        return broker.submitEditor(generation, controllerRevision, ignored -> {
            EditorAccess access = new EditorAccess(generation, controllerRevision);
            editorCommand.set(access);
            try { return command.apply(access); }
            finally { editorCommand.remove(); }
        });
    }
    /** The only supported visibility mutation consumes the exact provider-issued proof at CAS. */
    public CompletableFuture<InternalSubRecord> submitVisibility(InternalVisibilityBarrier.Proof proof) {
        Objects.requireNonNull(proof); InternalVisibilityBarrier.Request request = proof.request();
        CompletableFuture<InternalSubRecord> result = new CompletableFuture<>();
        CompletableFuture<InternalSubRecord> mutation;
        try {
            mutation = broker.submitVisibility(request.expected.generation, request.controllerRevision, ignored -> {
                requireVisibilityRecord(request.expected); admitVisibility(request.expected.generation, request.controllerRevision);
                proof.consume(physical, request.expected, request.controllerRevision, request.visible, request.transitionAtMillis);
                return host.store.setVisible(request.expected, request.visible, request.transitionAtMillis);
            });
        } catch (RuntimeException | Error refused) {
            completeVisibility(proof, null, refused, result); return result;
        }
        // Completion belongs to actual worker settlement, not to an interrupted caller's await.
        mutation.whenComplete((saved, failure) -> completeVisibility(proof, saved, failure, result));
        return result;
    }
    private static void completeVisibility(InternalVisibilityBarrier.Proof proof, InternalSubRecord saved,
            Throwable failure, CompletableFuture<InternalSubRecord> result) {
        Throwable outcome = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        try {
            proof.complete(saved, outcome).whenComplete((ignored, completionFailure) -> {
                Throwable reported = outcome;
                if (completionFailure != null) {
                    if (reported == null) reported = completionFailure;
                    else if (reported != completionFailure) reported.addSuppressed(completionFailure);
                }
                if (reported == null) result.complete(saved); else result.completeExceptionally(reported);
            });
        } catch (RuntimeException | Error completionFailure) {
            if (outcome != null && outcome != completionFailure) { outcome.addSuppressed(completionFailure); result.completeExceptionally(outcome); }
            else result.completeExceptionally(completionFailure);
        }
    }
    private void requireVisibilityRecord(InternalSubRecord expected) {
        InternalRuntimePreferences.requireWorker();
        if (!InternalStorageScope.TARGET_ID.equals(expected.targetId) || !expected.equals(host.configuration.read()))
            throw new SecurityException("Internal visibility record changed");
    }
    private void admitVisibility(String generation, long controller) {
        InternalRuntimePreferences.requireWorker();
        InternalSubRecord record = host.configuration.read();
        if (!InternalStorageScope.TARGET_ID.equals(record.targetId) || !record.generation.equals(generation))
            throw new SecurityException("Internal visibility target changed");
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(physical)
                    || controller != com.subhub.app.security.ControllerPinManager.domWorkspaceRevision())
                throw new SecurityException("Internal visibility controller changed");
        }
    }
    /** Immutable result only. UI observers never receive a provider or an executable data capability. */
    public CompletableFuture<OwnerStatisticsStore.Snapshot> snapshot(String generation, long now) {
        return broker.submitRead(generation, handle -> handle.statistics.snapshot(now));
    }
    /**
     * One immutable profile/accounting/usage observation on the sole serial owner worker. The
     * caller captures its clock/calendar before queueing. No write or achievement reconciliation
     * occurs, and neither session history nor this result establishes runtime service readiness.
     */
    public CompletableFuture<InternalRuntimePresentationSnapshot> readProjection(String generation,
            OwnerObservation observed, java.util.Set<String> packages) {
        Objects.requireNonNull(observed);
        java.util.Set<String> capturedPackages = java.util.Set.copyOf(packages);
        return broker.submitRead(generation, handle -> {
            InternalSubRecord record = currentOnWorker(generation);
            requireObservationZone(observed);
            InternalRuntimeRecord runtime = handle.preferences.snapshot();
            OwnerStatisticsStore.Snapshot accounting = handle.statistics.snapshot(observed.at);
            requireObservationZone(observed);
            InternalPenanceRules rules = new InternalPenanceRules(
                    host.configuration.snapshotPreferences(record, PenanceManager.PREFS_NAME));
            return new InternalRuntimePresentationSnapshot(record, runtime, accounting, observed,
                    handle.statistics.appUsage().snapshots(capturedPackages, observed),
                    handle.statistics.ledger(() -> rules, () -> false).retainedEvents());
        });
    }
    private static void requireObservationZone(OwnerObservation observed) {
        // The existing accounting projection has system-zone fallbacks. Do not silently label
        // that projection with a different calendar captured before this command was queued.
        if (!observed.zone.equals(java.time.ZoneId.systemDefault().getId()))
            throw new IllegalStateException("Owner observation calendar changed; capture a new observation");
    }
    /**
     * Memory-only cache invalidation hint, not authority or readiness. -1 means no usable hint.
     * A write in flight returns its previous acknowledged revision, never a staged/new revision.
     * Recheck visibility/generation through readProjection before displaying a refreshed result.
     */
    public long acknowledgedRevisionHint(String generation) {
        Handle handle = observedHandle;
        if (handle == null || !broker.acceptsDataHint() || !handle.preferences.isAvailable()) return -1;
        try { current(generation); }
        catch (SecurityException | IllegalStateException unavailable) { return -1; }
        return handle.acknowledgedRevision;
    }
    /** Explicit bounded data repair. This neither resumes enforcement nor clears a runtime journal. */
    public CompletableFuture<Void> reconcileInterruptedSession(String generation, long controllerRevision, String session) {
        return broker.submitRecovery(generation, controllerRevision, handle -> {
            return handle.observeAck(() -> { handle.statistics.reconcileInterruptedSession(session); return null; });
        });
    }
    public final class TaskAccess {
        private final String generation;
        private final Handle handle;
        private TaskAccess(String generation, Handle handle) { this.generation = generation; this.handle = handle; }
        public InternalSubRecord record() { requireCurrent(); return host.acknowledged(); }
        public InternalStorageScope storage() { requireCurrent(); return host.storage; }
        public long resumeAfterMillis() { return record().tasksResumeAfterMillis; }
        public void requireCurrent() {
            if (taskCommand.get() != this) throw new SecurityException("Task access escaped its admitted command");
            currentTask(generation);
        }
        /** Stable occurrence identity deliberately excludes visibility generation. No payment effects. */
        public OwnerPenanceLedger.Result assessTaskFailure(String occurrence, long now) {
            return assessTaskFailure(occurrence, OwnerObservation.capture(now));
        }
        /** Prefer an observation captured before submission, preserving the event's clock and zone. */
        public OwnerPenanceLedger.Result assessTaskFailure(String occurrence, OwnerObservation observation) {
            requireCurrent();
            InternalSubRecord observed = host.acknowledged();
            InternalPenanceRules rules = new InternalPenanceRules(host.configuration.snapshotPreferences(observed, PenanceManager.PREFS_NAME));
            return handle.observeAck(() -> handle.statistics.ledger(() -> rules, () -> false).recordTaskFailure(occurrence, Objects.requireNonNull(observation), () -> {
                requireCurrent(); return true;
            }));
        }
        public java.util.List<TaskNoticeLedger.Receipt> notices() {
            requireCurrent(); java.util.List<TaskNoticeLedger.Receipt> current = new java.util.ArrayList<>();
            for (TaskNoticeLedger.Receipt receipt : handle.notices.list())
                if (generation.equals(receipt.generation)) current.add(receipt);
            return java.util.Collections.unmodifiableList(current);
        }
        /** Persist before OS post; only this admitted generation may add receipts. */
        public TaskNoticeLedger.Receipt admitNotice(TaskNoticeLedger.Receipt receipt) {
            requireCurrent();
            if (!generation.equals(receipt.generation)) throw new SecurityException("Different task notice generation");
            return handle.atomic(() -> handle.notices.admit(receipt));
        }
        /** Caller must first finish exact OS cancellation; receipt is retained on any uncertain outcome. */
        public void removeNoticeAfterCancellation(TaskNoticeLedger.Receipt receipt) {
            requireCurrent();
            if (!generation.equals(receipt.generation)) throw new SecurityException("Use retired-notice cleanup for old generations");
            handle.atomic(() -> handle.notices.removeAfterCancellation(receipt));
        }
    }
    /** Hidden/retired cleanup has no task/editor/runtime API and can never add a notice. */
    public <T> CompletableFuture<T> submitRetiredTaskNotices(Function<RetiredTaskNotices, T> command) {
        Objects.requireNonNull(command);
        return broker.submitRetiredTaskNotices(handle -> {
            RetiredTaskNotices access = new RetiredTaskNotices(handle); noticeCleanup.set(access);
            try { return command.apply(access); }
            finally { noticeCleanup.remove(); }
        });
    }
    public final class RetiredTaskNotices {
        private final Handle handle;
        private RetiredTaskNotices(Handle handle) { this.handle = handle; }
        public java.util.List<TaskNoticeLedger.Receipt> list() {
            require(); java.util.List<TaskNoticeLedger.Receipt> retired = new java.util.ArrayList<>();
            InternalSubRecord record = host.configuration.read();
            for (TaskNoticeLedger.Receipt receipt : handle.notices.list())
                if (retired(receipt, record)) retired.add(receipt);
            return java.util.Collections.unmodifiableList(retired);
        }
        public void removeAfterCancellation(TaskNoticeLedger.Receipt receipt) {
            require(); Objects.requireNonNull(receipt);
            if (!retired(receipt, host.configuration.read())) throw new SecurityException("Current task notice is not retired");
            handle.atomic(() -> handle.notices.removeAfterCancellation(receipt));
        }
        private boolean retired(TaskNoticeLedger.Receipt receipt, InternalSubRecord record) {
            if (!InternalStorageScope.TARGET_ID.equals(record.targetId)) throw new SecurityException("Different internal notice owner");
            return !record.visible || !record.generation.equals(receipt.generation)
                    || record.tasksResumeAfterMillis <= 0 || !taskPolicy.test(record);
        }
        private void require() {
            if (noticeCleanup.get() != this) throw new SecurityException("Retired-notice access escaped its command");
            InternalRuntimePreferences.requireWorker();
        }
    }
    public final class EditorAccess {
        private final String generation;
        private final long controller;
        private EditorAccess(String generation, long controller) { this.generation = generation; this.controller = controller; }
        public InternalSubRecord record() { requireCurrent(); return host.acknowledged(); }
        public InternalStorageScope storage() { requireCurrent(); return host.storage; }
        public void requireCurrent() {
            if (editorCommand.get() != this) throw new SecurityException("Editor access escaped its admitted command");
            InternalEditorAuthority.admit(physical, currentOnWorker(generation), generation, controller);
        }
    }
    /** Supply this exact executor to the one registered OwnerWriteQueue retained by the session. */
    public Executor runtimeWorker() { return broker.runtimeWorker(); }
    public Session bindRuntime(RuntimeLease.Identity expected, OwnerWriteQueue queue) {
        // Memory-only current-target check; actual runtime start authority remains the coordinator.
        current(expected.profileGeneration);
        return new Session(broker.bindRuntime(expected, queue), queue);
    }
    public final class Session {
        private final SerialOwnerDataBroker<Handle>.Binding binding;
        private final OwnerWriteQueue queue;
        private Session(SerialOwnerDataBroker<Handle>.Binding binding, OwnerWriteQueue queue) { this.binding = binding; this.queue = queue; }
        public RuntimeLease.Identity identity() { return binding.identity(); }
        private volatile SessionObservation started;
        private volatile boolean ended;
        public StatsRepository.SessionStamp acknowledgedSessionStamp() {
            binding.requireBound(); SessionObservation observation = started;
            if (ended || observation == null || !observation.stamp.isCurrent())
                throw new IllegalStateException("This runtime has no acknowledged live session");
            return observation.stamp;
        }
        public boolean isCurrentStamp(StatsRepository.SessionStamp expected) {
            try { return expected != null && expected == acknowledgedSessionStamp(); }
            catch (IllegalStateException | SecurityException unavailable) { return false; }
        }
        /** Counted NORMAL dispatch without outer held(), for callbacks with individually guarded primitives. */
        public CompletableFuture<Void> executeNormal(Runnable action) {
            Objects.requireNonNull(action);
            try { binding.requireBound(); }
            catch (SecurityException refusal) {
                // This check precedes the queue call and therefore cannot have dispatched work.
                throw new OwnerWriteQueue.AdmissionRejected("Runtime-data binding is not available", refusal);
            }
            return queue.submit(identity(), action);
        }
        /** Failures must fence the registered owning component rather than be silently dropped. */
        public Executor normalExecutor(java.util.function.Consumer<Throwable> failed) {
            Objects.requireNonNull(failed);
            return action -> executeNormal(action).whenComplete((ignored, failure) -> {
                if (failure != null) failed.accept(failure);
            });
        }
        /** Sole lifecycle entry: publishes only this binding's stamp after its own STARTUP ACK. */
        public SessionObservation beginSession(StatsRepository.NativeSession session) {
            Objects.requireNonNull(session);
            if (!binding.identity().session.equals(session.identity)) throw new SecurityException("Different owner session");
            return binding.held(OwnedResourceRegistry.Effect.STARTUP, handle -> {
                if (started != null || ended) throw new IllegalStateException("Session start already acknowledged");
                handle.observeAck(() -> { handle.statistics.beginSession(session); return null; });
                StatsRepository.SessionStamp stamp = handle.statistics.repository().observedSessionStamp();
                if (stamp == null || stamp.isDeviceSession() || !stamp.isCurrent()
                        || !handle.statistics.repository().ownsSessionStamp(stamp))
                    throw new IllegalStateException("Own STARTUP receipt unavailable");
                SessionObservation receipt = new SessionObservation(identity(), session, stamp);
                started = receipt; return receipt;
            });
        }
        private <T> T normal(StatsRepository.SessionStamp expected, Function<Handle, T> operation) {
            return binding.held(OwnedResourceRegistry.Effect.NORMAL, handle -> {
                if (expected == null || expected != acknowledgedSessionStamp()
                        || handle.statistics.repository().observedSessionStamp() != expected)
                    throw new SecurityException("Different or stale acknowledged session");
                return handle.observeAck(() -> operation.apply(handle));
            });
        }
        public StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp expected,
                java.util.function.Supplier<com.subhub.app.censor.engine.CensorEngine.EvidenceClaim> claim,
                java.util.Set<String> categories, OwnerObservation observed) {
            Objects.requireNonNull(claim); Objects.requireNonNull(observed);
            java.util.Set<String> capturedCategories = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(categories));
            return normal(expected, handle -> handle.statistics.recordEvidence(expected, claim, capturedCategories, observed));
        }
        /** The renderer captures these tracker IDs/category facts before queueing the exact NORMAL event. */
        public int recordLegacyTrackIds(StatsRepository.SessionStamp expected, java.util.List<Integer> trackIds,
                java.util.Set<String> categories, OwnerObservation observed) {
            Objects.requireNonNull(observed);
            java.util.List<Integer> capturedIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(trackIds)));
            java.util.Set<String> capturedCategories = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(Objects.requireNonNull(categories)));
            return normal(expected, handle -> handle.statistics.recordLegacyTrackIds(expected, capturedIds, capturedCategories, observed));
        }
        public OwnerPenanceLedger.Result assessCensoredTap(StatsRepository.SessionStamp expected,
                com.subhub.app.penance.PenanceRuleSettings capturedRules, boolean censorEnabled, OwnerObservation observed) {
            Objects.requireNonNull(capturedRules); Objects.requireNonNull(observed);
            return normal(expected, handle -> handle.statistics.ledger(() -> capturedRules, () -> censorEnabled)
                    .recordSourceCensoredTapResult(observed, expected));
        }
        public OwnerPenanceLedger.Result assessDetection(StatsRepository.SessionStamp expected, int count,
                com.subhub.app.penance.PenanceRuleSettings capturedRules, boolean censorEnabled, OwnerObservation observed) {
            Objects.requireNonNull(capturedRules); Objects.requireNonNull(observed);
            return normal(expected, handle -> handle.statistics.ledger(() -> capturedRules, () -> censorEnabled)
                    .recordSourceDetectionResult(count, observed, expected));
        }
        public OwnerPenanceLedger.Result assessDwell(StatsRepository.SessionStamp expected, int count,
                com.subhub.app.penance.PenanceRuleSettings capturedRules, boolean censorEnabled, OwnerObservation observed) {
            Objects.requireNonNull(capturedRules); Objects.requireNonNull(observed);
            return normal(expected, handle -> handle.statistics.ledger(() -> capturedRules, () -> censorEnabled)
                    .recordSourceDwellResult(count, observed, expected));
        }
        public void reconcileAchievements(StatsRepository.SessionStamp expected,
                com.subhub.app.stats.AchievementManager.OwnerFacts capturedFacts, OwnerObservation observed) {
            Objects.requireNonNull(capturedFacts); Objects.requireNonNull(observed);
            normal(expected, handle -> { handle.statistics.reconcileAchievements(capturedFacts, observed.at); return null; });
        }
        public void recordWhisperImpression(OwnerObservation observed) {
            Objects.requireNonNull(observed);
            normal(acknowledgedSessionStamp(), handle -> { handle.statistics.transaction(
                    () -> handle.statistics.repository().recordSubliminalImpression(observed)); return null; });
        }
        public void recordPopupImpression(OwnerObservation observed) {
            Objects.requireNonNull(observed);
            normal(acknowledgedSessionStamp(), handle -> { handle.statistics.transaction(
                    () -> handle.statistics.repository().recordPopupImpression(observed)); return null; });
        }
        public void recordLimitIntervention(OwnerObservation observed) {
            Objects.requireNonNull(observed);
            normal(acknowledgedSessionStamp(), handle -> { handle.statistics.transaction(
                    () -> handle.statistics.repository().recordLimitIntervention(observed)); return null; });
        }
        public void recordUsage(String packageName, long elapsedMillis, OwnerObservation observed) {
            Objects.requireNonNull(observed);
            normal(acknowledgedSessionStamp(), handle -> { handle.statistics.appUsage().recordUsage(packageName, elapsedMillis, observed); return null; });
        }
        public java.util.Map<String, com.subhub.app.appmode.AppTimerManager.UsageSnapshot> usageSnapshots(
                java.util.Set<String> packages, OwnerObservation observed) {
            Objects.requireNonNull(observed); java.util.Set<String> captured = java.util.Set.copyOf(packages);
            return normal(acknowledgedSessionStamp(), handle -> handle.statistics.appUsage().snapshots(captured, observed));
        }
        public com.subhub.app.appmode.AppTimerManager.UsageSnapshot usageSnapshot(String packageName, OwnerObservation observed) {
            String key = packageName == null ? "" : packageName;
            return usageSnapshots(java.util.Collections.singleton(key), observed).get(key);
        }
        public void checkpointHistory(OwnerObservation observed) {
            Objects.requireNonNull(observed);
            normal(acknowledgedSessionStamp(), handle -> { handle.statistics.checkpoint(observed.at); return null; });
        }
        public CompletableFuture<Void> finishAndRelease(long now, Runnable afterDrained) {
            return binding.stop(handle -> {
                if (ended || started == null) throw new IllegalStateException("Own session start was not acknowledged");
                handle.observeAck(() -> { handle.statistics.finishSession(binding.identity().session, now); return null; });
                ended = true; return null;
            }, afterDrained);
        }
    }
    public static final class SessionObservation {
        public final RuntimeLease.Identity identity;
        public final long startedAt;
        public final String zone;
        public final StatsRepository.SessionStamp stamp;
        private SessionObservation(RuntimeLease.Identity identity, StatsRepository.NativeSession session, StatsRepository.SessionStamp stamp) {
            this.identity = identity; startedAt = session.startedAt; zone = session.zone; this.stamp = stamp;
        }
    }
    /** Host teardown only. Never call on ordinary session stop, hide or generation rotation. */
    public CompletableFuture<Void> closeAfterDrain() { return broker.closeAfterDrain(); }
    private InternalSubRecord current(String generation) {
        InternalSubRecord record = host.acknowledged();
        if (!InternalStorageScope.TARGET_ID.equals(record.targetId) || !record.visible
                || generation == null || !generation.equals(record.generation))
            throw new SecurityException("Internal owner-data target changed");
        return record;
    }
    private InternalSubRecord currentOnWorker(String generation) {
        InternalRuntimePreferences.requireWorker();
        InternalSubRecord durable = host.configuration.read();
        InternalSubRecord record = current(generation);
        if (!durable.equals(record)) throw new IllegalStateException("Owner profile acknowledgement changed");
        return record;
    }
    private InternalSubRecord currentTask(String generation) {
        InternalSubRecord record = currentOnWorker(generation);
        if (record.tasksResumeAfterMillis <= 0 || !taskPolicy.test(record)) throw new SecurityException("Internal Tasks unavailable");
        return record;
    }
}
