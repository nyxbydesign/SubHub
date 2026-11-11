package com.subhub.app.runtime;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.diagnostics.CensorLabRecorder;
import com.subhub.app.diagnostics.CensorLabRecordingService;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.InternalVisibilityBarrier;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.RuntimeCaptureHost;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.StatsRepository;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete physical journal, owner-data and service-delivery assembly. INACTIVE: no application
 * caller installs this factory, the facade, the visibility provider or INTERNAL Start yet.
 *
 * The final installer must first complete the DEVICE/remote/boot/cleanup admission inventory and
 * actual service resource observers. prepareAfterLegacyQuiescence is not an invitation to infer
 * those obligations from false service flags: the existing bootstrap tickets must cover every
 * preinstallation prompt, mutation, service handoff and resource lifetime. Legacy active sessions
 * are refused, never adopted/rewritten. Non-IDLE old-process journals remain recovery-required.
 */
final class AndroidRuntimeFactory implements RuntimeCaptureHost.Gateway, InternalVisibilityBarrier.Provider {
    private final Application physical;
    private final InternalSubData data;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SQLiteRuntimeOwnerBackend journal;
    private final RuntimeInstallationBarrier.Installation installation;
    private final RuntimeAdmissionAuthority authority;
    private final ActiveRuntimeCoordinator coordinator;
    private final RuntimeStartGateway starts;
    private final InternalRuntimeAuthorization internalAuthority;
    private final Map<RuntimeCaptureHost.Connection, RuntimeCaptureHost.Selection> selections = new IdentityHashMap<>();
    private final Set<RuntimeCaptureHost.Selection> rejectedBeforeDelivery =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Pending> projection = new java.util.HashMap<>();
    private final Map<RuntimeLease, Intent> approvedProjection = new IdentityHashMap<>();
    private Pending accessibility;
    /** One actual OS-bound but resource-neutral logical service, never a capture-ready component. */
    private NeutralAccessibility neutralAccessibility;
    private static final class NeutralAccessibility {
        final RuntimeCaptureHost.Connection connection;final RuntimeCaptureHost.Completion completion;
        NeutralAccessibility(RuntimeCaptureHost.Connection connection,RuntimeCaptureHost.Completion completion) {
            this.connection=connection;this.completion=completion;
        }
    }
    private volatile Binding current;
    private volatile Visibility visibility;
    private volatile boolean recoveryRequired;
    private volatile AdmittedCapability admittedCapability;
    private static final class AdmittedCapability {
        final RuntimeLease lease; final OwnedResourceRegistry resources;
        AdmittedCapability(RuntimeLease lease,OwnedResourceRegistry resources){this.lease=lease;this.resources=resources;}
    }
    private volatile RuntimeStartAttempt pendingAttempt;
    private final String devicePlanGeneration = UUID.randomUUID().toString();
    private long devicePlanRevision;
    private final AppRuntimeGateway.ServiceAccess services;

    /**
     * Owner-data serial worker ONLY. No hook calls this until all old route/resource counting is
     * integrated. The barrier is sealed before checking raw occupancy. A failed precheck aborts
     * without changing legacy behavior; after the first durable step failure is permanently fenced.
     */
    static AndroidRuntimeFactory prepareAfterLegacyQuiescence(Application app, InternalSubData data) {
        Objects.requireNonNull(app); Objects.requireNonNull(data);
        if (!data.isOwnerThread() || Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Prepare the physical owner on its serial data worker");
        if (!data.ownerDataReady()) throw new IllegalStateException("Owner data not acknowledged");
        return AppRuntimeGateway.PHYSICAL_MUTEX.runtime(() -> {
            RuntimeInstallationBarrier.Installation proof = AppRuntimeGateway.prepareInstallation(app);
            if (proof == null) throw new IllegalStateException("Legacy work is still owned");
            if (observeLegacyOccupancy(app) != ActiveRuntimeCoordinator.Occupancy.CLEAR) {
                proof.abortBeforeProvisioning();
                throw new IllegalStateException("Existing DEVICE state must finish normally before internal runtime installation");
            }
            SQLiteRuntimeOwnerBackend opened = null;
            try {
                // Even a READY reopen can fail with a prior INTERNAL owner. Do not fall back to
                // legacy entrypoints after learning that durable ownership needs reconciliation.
                SharedPreferences registry = app.getSharedPreferences(RuntimeJournalMigration.REGISTRY, Context.MODE_PRIVATE);
                opened = new RuntimeJournalMigration(AppRuntimeGateway.PHYSICAL_MUTEX, proof).open(new RuntimeJournalMigration.Evidence() {
                    @Override public String profileState() { return registry.getString(RuntimeJournalMigration.PROFILE_STATE, ""); }
                    @Override public String receipt() { return registry.getString(RuntimeJournalMigration.RECEIPT, ""); }
                    @Override public String originalGeneration() { return registry.getString(RuntimeJournalMigration.ORIGINAL_GENERATION, ""); }
                    @Override public boolean compareAndSetReceipt(String expectedState, String expectedGeneration, String expected, String replacement) {
                        AppRuntimeGateway.PHYSICAL_MUTEX.requireHeld();
                        if (!expectedState.equals(registry.getString(RuntimeJournalMigration.PROFILE_STATE, ""))
                                || !expectedGeneration.equals(registry.getString(RuntimeJournalMigration.ORIGINAL_GENERATION, ""))
                                || !expected.equals(registry.getString(RuntimeJournalMigration.RECEIPT, ""))) return false;
                        return registry.edit().putString(RuntimeJournalMigration.RECEIPT, replacement).commit();
                    }
                }, new RuntimeJournalMigration.Journal<SQLiteRuntimeOwnerBackend>() {
                    @Override public SQLiteRuntimeOwnerBackend openExisting() { return new SQLiteRuntimeOwnerBackend(app); }
                    @Override public SQLiteRuntimeOwnerBackend provisionNew() { return SQLiteRuntimeOwnerBackend.provisionNewJournal(app); }
                    @Override public void validateExisting(SQLiteRuntimeOwnerBackend backend) { backend.read(); }
                    @Override public void validateFirstCreation(SQLiteRuntimeOwnerBackend backend) {
                        if (!RuntimeOwnerStore.Record.idle(0).equals(backend.read()))
                            throw new IllegalStateException("First owner journal was not exactly IDLE revision zero");
                    }
                }, () -> {
                    AppRuntimeGateway.PHYSICAL_MUTEX.requireHeld();
                    if (observeLegacyOccupancy(app) != ActiveRuntimeCoordinator.Occupancy.CLEAR)
                        throw new SecurityException("Physical occupancy changed before first journal claim");
                });
                AndroidRuntimeFactory factory = new AndroidRuntimeFactory(app, data, opened, proof);
                if (factory.coordinator.reconcileStartup() != ActiveRuntimeCoordinator.Result.OK)
                    throw new IllegalStateException("Existing owner requires explicit recovery; no lease was recreated");
                return factory;
            } catch (Exception | Error unavailable) {
                proof.failed();
                if (opened != null) opened.close();
                throw new IllegalStateException("Physical runtime installation needs recovery", unavailable);
            }
        });
    }
    private AndroidRuntimeFactory(Application physical, InternalSubData data, SQLiteRuntimeOwnerBackend journal,
            RuntimeInstallationBarrier.Installation installation) {
        this.physical=physical; this.data=data; this.journal=journal; this.installation=installation;
        authority=new RuntimeAdmissionAuthority(AppRuntimeGateway.PHYSICAL_MUTEX);
        coordinator=new ActiveRuntimeCoordinator(new RuntimeOwnerStore(AppRuntimeGateway.PHYSICAL_MUTEX,journal),
                authority,()->selections.isEmpty()?observeLegacyOccupancy(physical):ActiveRuntimeCoordinator.Occupancy.OCCUPIED,UUID.randomUUID().toString());
        // No extra queued gap between an observed profile/controller and reserve: this executor
        // runs inline only on the SAME data worker that serializes production editor CAS calls.
        starts=new RuntimeStartGateway(coordinator,command->{
            Runnable observed=()->{try{command.run();}finally{retireTerminalRouting();}};
            if(data.isOwnerThread())observed.run();else data.execute(observed);
        },this::requireWorker);
        internalAuthority=new InternalRuntimeAuthorization(physical,authority,this::requireWorker);
        services=new AppRuntimeGateway.ServiceAccess(physical,this,this::executeLifecycle,this::serviceIngressFailed);
    }
    private void requireWorker() {
        if (!data.isOwnerThread() || Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException("Physical ownership requires its serial owner worker");
    }
    private void requireUsable() {
        requireWorker(); if(recoveryRequired||starts.needsRecovery())throw new IllegalStateException("Physical runtime needs recovery");
    }

    /** Exact physical producer, never an externally injected gateway or arbitrary service executor. */
    AppRuntimeGateway.ServiceAccess serviceAccess() { return services; }
    private void serviceIngressFailed(Throwable failure) {
        recoveryRequired=true;
        AdmittedCapability admitted=admittedCapability;
        if(admitted!=null)admitted.resources.fence(admitted.lease); // Exact cached capability, no RuntimeMutex or journal IO.
        RuntimeStartAttempt pending=pendingAttempt;
        if(pending!=null)starts.cancel(pending);
        Binding selected=current;
        if(selected!=null)selected.runtime.requestStop();
    }
    /** Facade command queue only. Every operation must still use exact start/effect/cleanup APIs. */
    <T> CompletableFuture<T> submitDevice(java.util.function.Supplier<T> action) {
        return dispatchDeviceCommand(this::requireUsable,action);
    }
    /** Recovery is allowed to close exact existing resources, never to mint START/NORMAL authority. */
    <T> CompletableFuture<T> submitDeviceCleanup(DeviceAssembly assembly,java.util.function.Supplier<T> action) {
        Objects.requireNonNull(assembly);
        return dispatchDeviceCommand(()->{requireWorker();requireDevice(assembly);},action);
    }
    private <T> CompletableFuture<T> dispatchDeviceCommand(Runnable admission,java.util.function.Supplier<T> action) {
        DeviceCommand<T> command=new DeviceCommand<>(admission,Objects.requireNonNull(action));
        Throwable failed=null;
        try {executeLifecycle(command::run);}
        catch(RuntimeException|Error uncertain){failed=uncertain;serviceIngressFailed(uncertain);}
        command.dispatched(failed);return command.result;
    }
    private final class DeviceCommand<T> {
        final Runnable admission;final java.util.function.Supplier<T> action;final CompletableFuture<T> result=new CompletableFuture<>();
        final AtomicBoolean entered=new AtomicBoolean();
        boolean returned,dispatched,completed;T value;Throwable actionFailure,dispatchFailure;
        DeviceCommand(Runnable admission,java.util.function.Supplier<T> action){this.admission=admission;this.action=action;result.whenComplete((v,f)->{
            if(result.isCancelled())serviceIngressFailed(new java.util.concurrent.CancellationException("DEVICE command withdrawn"));
        });}
        void run(){
            if(!entered.compareAndSet(false,true))return;
            T observed=null;Throwable failure=null;
            try {admission.run();observed=action.get();}catch(RuntimeException|Error failed){failure=failed;}
            synchronized(this){value=observed;actionFailure=failure;returned=true;}finish();
        }
        void dispatched(Throwable failure){synchronized(this){dispatchFailure=failure;dispatched=true;}finish();}
        void finish(){T answer;Throwable failure;
            synchronized(this){
                if(completed||!dispatched||!returned)return;
                completed=true;answer=value;failure=dispatchFailure!=null?dispatchFailure:actionFailure;
            }
            boolean delivered=failure==null?result.complete(answer):result.completeExceptionally(failure);
            if(!delivered)serviceIngressFailed(new java.util.concurrent.CancellationException("DEVICE command result withdrawn"));
        }
    }
    static final class DeviceAdmission {
        final RuntimeStartAttempt attempt;
        final DeviceConfiguration configuration;
        final com.subhub.app.security.DeviceRolePreferences.DeviceStartWitness role;
        // Owner-worker-only handoff, recorded before the STARTING result can escape.
        private RuntimeStartGateway.Starting begun;
        private boolean assemblyTransferred, assemblySettled;
        private DeviceAdmission(RuntimeStartAttempt attempt,DeviceConfiguration configuration,
                com.subhub.app.security.DeviceRolePreferences.DeviceStartWitness role) {
            this.attempt=attempt;this.configuration=configuration;this.role=role;
        }
    }
    static final class DeviceAssembly {
        final RuntimeLease lease;
        final RuntimeExecution<DeviceConfiguration> runtime;
        final DeviceRuntimeEffects effects;
        final DeviceAdmission admission;
        final CompletableFuture<AppRuntimeGateway.StartResult> activation=new CompletableFuture<>();
        volatile com.subhub.app.appmode.RuntimeSessionIdentity.ComponentObservation componentObservation;
        private volatile AndroidDeviceStatisticsSource statistics;
        // Set at the actual durable ACTIVE acknowledgement, before any fallible main/UI delivery.
        // False is not a rollback receipt: uncertain journal/write outcomes still require recovery.
        volatile boolean activeAcknowledged;
        // Aggregate activation errors may follow a committed ACTIVE CAS (including failed Stop).
        // Only definite pre-write NOT_READY may restore this attempt's prior value.
        private volatile boolean activationMayHaveCommitted;
        DeviceAssembly(RuntimeLease lease,RuntimeExecution<DeviceConfiguration> runtime,DeviceRuntimeEffects effects,DeviceAdmission admission) {
            this.lease=lease;this.runtime=runtime;this.effects=effects;this.admission=admission;
            activation.whenComplete((v,f)->{if(activation.isCancelled())runtime.requestStop();});
        }
        AndroidDeviceStatisticsSource statisticsSource() {
            AndroidDeviceStatisticsSource source=statistics;
            if(source==null)throw new SecurityException("DEVICE statistics startup has not been acknowledged");
            return source;
        }
    }
    private <T> CompletableFuture<T> deviceResult(CompletableFuture<CompletableFuture<T>> dispatched) {
        CompletableFuture<T> result=dispatched.thenCompose(value->value);
        result.whenComplete((value,failure)->{
            if(result.isCancelled())serviceIngressFailed(new java.util.concurrent.CancellationException("DEVICE result withdrawn"));
        });
        return result;
    }
    CompletableFuture<DeviceAdmission> reserveDevice(AppRuntimeGateway.DeviceStartSpec specification) {
        return deviceResult(submitDevice(()->{
            retireTerminalRouting();
            ActiveRuntimeCoordinator.Observation existing=coordinator.observe();
            if(existing.persisted==null||existing.quarantined||!existing.startupReconciled)
                throw new IllegalStateException("DEVICE ownership needs reconciliation");
            if(existing.effectivePhase()!=RuntimeOwnerStore.Phase.IDLE)
                throw new AppRuntimeGateway.DeviceAdmissionException("DEVICE admission refused: OCCUPIED");
            // Refusing a competing start must not republish and retire its permission witness.
            DeviceObservation observed=observeDevice(specification);
            RuntimeAdmissionAuthority.AvailabilityPublication available=authority.beginAvailability(RuntimeTarget.DEVICE);
            if(!authority.completeAvailability(available,devicePlanGeneration,observed.configuration.observationRevision,true))
                throw new SecurityException("DEVICE plan publication superseded");
            if(!authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE,RuntimeOrigin.LOCAL_UI),
                    observed.role.revision,true,observed.role.revocation))throw new SecurityException("DEVICE role changed");
            Set<String> required=new java.util.LinkedHashSet<>();required.add("device_start");
            if(observed.configuration.plan.requiresAccessibility)required.add("device_accessibility");
            if(observed.configuration.plan.capture==com.subhub.app.settings.CaptureMethod.SCREEN_RECORDING)required.add("device_projection");
            return starts.reserve(authority.capture(RuntimeTarget.DEVICE,RuntimeOrigin.LOCAL_UI),required).thenApply(admitted->{
                requireWorker();
                if(admitted.result!=ActiveRuntimeCoordinator.Result.OK||admitted.attempt==null)
                    throw new AppRuntimeGateway.DeviceAdmissionException("DEVICE admission refused: "+admitted.result);
                pendingAttempt=admitted.attempt;admittedCapability=new AdmittedCapability(admitted.attempt.lease(),coordinator.resources(admitted.attempt.lease()));
                if(recoveryRequired){starts.cancel(admitted.attempt);throw new SecurityException("DEVICE dispatch authority retired");}
                return new DeviceAdmission(admitted.attempt,observed.configuration,observed.role);
            });
        }));
    }
    /** Exact late OS callback retirement remains legal while new admission is recovery-fenced. */
    CompletableFuture<Boolean> devicePermissionResult(DeviceAdmission admission,RuntimeStartAttempt.Prompt prompt,
            boolean granted,int resultCode,Intent projectionData) {
        Objects.requireNonNull(admission);Objects.requireNonNull(prompt);
        return deviceResult(dispatchDeviceCommand(this::requireWorker,
                ()->permissionResult(admission.attempt,prompt,granted,resultCode,projectionData)));
    }
    /** Backend failure before assembly has no RuntimeExecution yet, but still owns this handoff. */
    CompletableFuture<Void> rejectDeviceStarting(DeviceAdmission admission,RuntimeStartGateway.Starting started) {
        Objects.requireNonNull(admission);Objects.requireNonNull(started);
        return dispatchDeviceCommand(this::requireWorker,()->{
            if(admission.begun!=started||started.lease!=admission.attempt.lease())
                throw new SecurityException("Different unassembled DEVICE handoff");
            if(!admission.assemblySettled)settleUnassembledDevice(admission,started);
            return null;
        });
    }
    CompletableFuture<RuntimeStartGateway.Starting> beginDevice(DeviceAdmission admission,
            boolean accessibilityReady,boolean overlayReady) {
        Objects.requireNonNull(admission);
        return deviceResult(submitDevice(()->{
            try {
                if(!devicePlanCurrent(admission))throw new SecurityException("DEVICE start plan changed during permissions");
            } catch(RuntimeException|Error stale) { starts.cancel(admission.attempt); throw stale; }
            DeviceConfiguration plan=admission.configuration;
            boolean ready=(!plan.plan.requiresAccessibility||accessibilityReady)&&(!plan.plan.requiresOverlay||overlayReady)
                    &&(plan.plan.capture!=com.subhub.app.settings.CaptureMethod.SCREEN_RECORDING
                        ||approvedProjection.containsKey(admission.attempt.lease()));
            // Pure captured OS outcome only. No policy/preferences lookup while coordinator owns RuntimeMutex.
            return begin(admission.attempt,ignored->ready).thenApply(started->{
                requireWorker();admission.begun=started;return started;
            });
        }));
    }
    /** Fresh value comparison does not republish/revive the original reservation witness. */
    boolean devicePlanCurrent(DeviceAdmission expected) {
        requireUsable();Objects.requireNonNull(expected);
        if(expected.role.revocation.isRevoked())return false;
        DeviceObservation latest=observeDevice(expected.configuration.specification);
        return expected.role.revocation==latest.role.revocation
                &&expected.configuration.sameStartPlan(latest.configuration);
    }
    private static final class DeviceObservation {
        final DeviceConfiguration configuration;
        final com.subhub.app.security.DeviceRolePreferences.DeviceStartWitness role;
        DeviceObservation(DeviceConfiguration c,com.subhub.app.security.DeviceRolePreferences.DeviceStartWitness r){configuration=c;role=r;}
    }
    private DeviceObservation observeDevice(AppRuntimeGateway.DeviceStartSpec specification) {
        requireUsable();Objects.requireNonNull(specification);
        if(!com.subhub.app.configuration.LegacyPackMigration.isReady(physical))throw new SecurityException("DEVICE migration is not ready");
        synchronized(com.subhub.app.security.SubFeaturePolicy.LOCK) {
            // Read one main-map image, extract only these named nonsecret plan fields, then drop it.
            // Consent is stable under its existing outer lock; role takes the established inner Runtime lock.
            Map<String,?> values=physical.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll();
            boolean censor=com.subhub.app.security.SubFeaturePolicy.allowed(physical,"censor")&&flag(values,com.subhub.app.settings.FeatureModuleManager.KEY_CENSOR_ENABLED,true);
            com.subhub.app.settings.CaptureMethod capture=censor?com.subhub.app.settings.CaptureMethod.fromPreference(
                    text(values,SettingsRepository.KEY_CAPTURE_METHOD,com.subhub.app.settings.CaptureMethod.APP_MODE.preferenceValue())):com.subhub.app.settings.CaptureMethod.APP_MODE;
            if(capture!=specification.capture)throw new SecurityException("Selected DEVICE capture changed");
            boolean limits=com.subhub.app.security.SubFeaturePolicy.allowed(physical,"limits")&&(flag(values,com.subhub.app.appmode.AppTimerManager.KEY_PER_APP_ENABLED,false)||flag(values,com.subhub.app.appmode.AppTimerManager.KEY_TOTAL_ENABLED,false));
            boolean whispers=com.subhub.app.security.SubFeaturePolicy.allowed(physical,"subliminal")&&flag(values,com.subhub.app.settings.FeatureModuleManager.KEY_SUBLIMINAL_ENABLED,false);
            boolean popups=com.subhub.app.security.SubFeaturePolicy.allowed(physical,"popup")&&flag(values,com.subhub.app.popup.PopupStormSettings.K_ENABLED,false);
            var role=com.subhub.app.security.DeviceRolePreferences.captureDeviceStartWitness(physical);
            // Wallet is a separate read-only availability observation. It changes no capture or
            // permission requirement and is not represented as a cross-store durable config ACK.
            boolean wallet=PenanceManager.observedEnabled(physical);
            long revision=Math.incrementExact(devicePlanRevision);devicePlanRevision=revision;
            return new DeviceObservation(new DeviceConfiguration(revision,specification,android.os.Build.VERSION.SDK_INT,
                    censor,limits,whispers,popups,wallet),role);
        }
    }
    private static boolean flag(Map<String,?> values,String key,boolean fallback) {
        Object value=values.get(key);if(value==null)return fallback;if(!(value instanceof Boolean))throw new SecurityException("Malformed DEVICE plan flag");return (Boolean)value;
    }
    private static String text(Map<String,?> values,String key,String fallback) {
        Object value=values.get(key);if(value==null)return fallback;if(!(value instanceof String))throw new SecurityException("Malformed DEVICE capture selection");return (String)value;
    }
    DeviceAssembly assembleDevice(RuntimeStartGateway.Starting started,DeviceAdmission admission) {
        requireUsable();Objects.requireNonNull(started);Objects.requireNonNull(admission);
        if(started.lease!=admission.attempt.lease()||started.lease.identity.target.kind!=RuntimeTarget.Kind.DEVICE
                ||admission.begun!=started||admission.assemblyTransferred)
            throw new SecurityException("Different or consumed DEVICE admission");
        coordinator.resources(started.lease);
        if(current!=null&&current.lease==started.lease)throw new SecurityException("DEVICE assembly already exists");
        try {
            if(!devicePlanCurrent(admission))throw new SecurityException("Changed DEVICE admission");
            if(!selections.isEmpty())throw new SecurityException("Prior physical selection has not drained");
            RuntimeExecution<DeviceConfiguration> runtime=new RuntimeExecution<>(coordinator,started.lease,admission.configuration,this::executeLifecycle);
            java.util.concurrent.atomic.AtomicReference<DeviceAssembly> captured=new java.util.concurrent.atomic.AtomicReference<>();
            Runnable changed=()->{DeviceAssembly exact=captured.get();if(exact!=null)queueDeviceActivation(exact);};
            DeviceRuntimeEffects effects=new DeviceRuntimeEffects(coordinator,runtime,started.lease,changed,result->{
                if(result==ActiveRuntimeCoordinator.Result.OK)changed.run();else runtime.requestStop();
            });
            DeviceAssembly assembled=new DeviceAssembly(started.lease,runtime,effects,admission);captured.set(assembled);
            Binding binding=new Binding(started.lease,runtime,null);binding.device=assembled;current=binding;
            admission.assemblyTransferred=true;
            return assembled;
        } catch(RuntimeException|Error failed) {
            try { settleUnassembledDevice(admission,started); }
            catch(RuntimeException|Error unsettled){recoveryRequired=true;failed.addSuppressed(unsettled);}
            throw failed;
        }
    }
    /** No physical construction is inferred closed: the actual registry must issue its drain. */
    private void settleUnassembledDevice(DeviceAdmission admission,RuntimeStartGateway.Starting started) {
        requireWorker();
        if(admission.begun!=started||admission.assemblyTransferred
                ||(current!=null&&current.lease==started.lease))
            throw new SecurityException("DEVICE assembly handoff already transferred");
        OwnedResourceRegistry resources=coordinator.resources(started.lease);
        resources.fence(started.lease);
        ActiveRuntimeCoordinator.Result stopped=coordinator.requestStop(started.lease);
        if(stopped!=ActiveRuntimeCoordinator.Result.OK){recoveryRequired=true;return;}
        OwnedResourceRegistry.DrainReceipt proof=resources.drained(started.lease);
        if(proof==null){recoveryRequired=true;return;} // Actual retained work is never guessed absent.
        if(coordinator.completeStop(started.lease,proof)!=ActiveRuntimeCoordinator.Result.OK) {
            recoveryRequired=true;return;
        }
        admission.assemblyTransferred=true;admission.assemblySettled=true;
        retireTerminalRouting();
    }
    /** Actual synchronous start mutation has returned; this captures evidence, not cleanup authority. */
    void deviceStartObserved(DeviceAssembly assembly) {
        requireUsable();requireDevice(assembly);
        assembly.componentObservation=com.subhub.app.appmode.RuntimeSessionIdentity.componentObservation(physical);
        queueDeviceActivation(assembly);
    }
    /** Bind immutable evidence only after the exact physical startup has returned its ACK. */
    void deviceStatisticsSource(DeviceAssembly assembly,AndroidDeviceStatisticsSource source) {
        requireUsable();requireDevice(assembly);Objects.requireNonNull(source);
        if(!assembly.runtime.runStartupEffect(()->{
            requireDevice(assembly);
            if(assembly.componentObservation==null||assembly.statistics!=null
                    ||!assembly.lease.identity.equals(source.owner()))
                throw new SecurityException("Different or repeated DEVICE statistics startup source");
            assembly.statistics=source;
        }))throw new SecurityException("DEVICE statistics startup admission was revoked");
    }
    /**
     * Narrow classification for an exact newly owned start, not CLEANUP or write authority.
     * The lifecycle must also hold its real Cleanup and prove each physical mutation receipt.
     * Any uncertain dispatcher/journal outcome or acknowledged ACTIVE state forbids rollback.
     */
    boolean deviceStartupRollbackEligible(DeviceAssembly assembly) {
        requireWorker();requireDevice(assembly);
        if(assembly.activeAcknowledged||assembly.activationMayHaveCommitted||recoveryRequired||starts.needsRecovery())return false;
        ActiveRuntimeCoordinator.Observation state=coordinator.observe();
        return state.startupReconciled&&!state.quarantined&&state.persisted!=null
                &&assembly.lease.identity.equals(state.persisted.owner)
                &&(state.persisted.phase==RuntimeOwnerStore.Phase.STARTING
                    ||state.persisted.phase==RuntimeOwnerStore.Phase.STOPPING);
    }
    private void requireDevice(DeviceAssembly assembly) {
        Binding binding=current;if(binding==null||binding.device!=assembly||binding.runtime!=assembly.runtime)
            throw new SecurityException("Different DEVICE assembly");
    }
    private void queueDeviceActivation(DeviceAssembly assembly) {
        try {executeLifecycle(()->{
            Binding selected=current;
            if(selected==null||selected.device!=assembly||assembly.activation.isDone())return;
            // Actual start-transition completion supplies this evidence. Component placeholders
            // or service infrastructure alone cannot publish a completed physical DEVICE start.
            if(assembly.componentObservation==null||assembly.statistics==null)return;
            try {
                ActiveRuntimeCoordinator.Result result=activate(assembly.runtime);
                if(result==ActiveRuntimeCoordinator.Result.OK)assembly.activation.complete(new AppRuntimeGateway.StartResult(AppRuntimeGateway.StartState.ACTIVE,assembly.runtime.identity()));
                else if(result!=ActiveRuntimeCoordinator.Result.NOT_READY){assembly.runtime.requestStop();assembly.activation.complete(new AppRuntimeGateway.StartResult(AppRuntimeGateway.StartState.NOT_READY,null));}
            }catch(RuntimeException|Error failed){assembly.runtime.requestStop();assembly.activation.completeExceptionally(failed);}
        });}catch(RuntimeException|Error uncertain){serviceIngressFailed(uncertain);assembly.activation.completeExceptionally(uncertain);}
    }
    AppRuntimeGateway.DeviceMutationPermit admitManagedDeviceMutation(RuntimeLease.Identity expected,
            AppRuntimeGateway.Mutation mutation,AppRuntimeGateway.ManagedDevicePhase phase) {
        Objects.requireNonNull(expected);Objects.requireNonNull(mutation);Objects.requireNonNull(phase);
        Binding selected=current;
        if(selected==null||selected.device==null||!expected.equals(selected.lease.identity))
            throw new AppRuntimeGateway.DeviceAdmissionException("Different managed DEVICE lifecycle owner");
        DeviceContinuationScope.Mutation kind=switch(mutation){case ARM,SAVE_ARMED,PROJECTION_STARTED->DeviceContinuationScope.Mutation.START_OR_REARM;case DISARM,SAVE_DISARMED,PROJECTION_STOPPED->DeviceContinuationScope.Mutation.END;};
        DeviceContinuationScope.Borrow held=selected.device.effects.borrowManagedMutation(expected,kind,
                phase==AppRuntimeGateway.ManagedDevicePhase.STARTUP);
        return held::close;
    }
    AppRuntimeGateway.DeviceMutationPermit admitDeviceMutation(AppRuntimeGateway.Mutation mutation) {
        Binding selected=current;if(selected==null||selected.device==null)throw new AppRuntimeGateway.DeviceAdmissionException("No exact DEVICE continuation");
        DeviceContinuationScope.Mutation kind=switch(mutation){case ARM,SAVE_ARMED,PROJECTION_STARTED->DeviceContinuationScope.Mutation.START_OR_REARM;case DISARM,SAVE_DISARMED,PROJECTION_STOPPED->DeviceContinuationScope.Mutation.END;};
        DeviceContinuationScope.Borrow held=selected.device.effects.borrowMutation(kind);return held::close;
    }

    /** Same serial broker lane as native saves; missing clock/capture components cannot activate. */
    CompletableFuture<RuntimeStartGateway.Admission> reserveInternal(InternalSubRecord expected, Set<String> components) {
        requireUsable(); retireTerminalRouting();
        if(visibility!=null)throw new SecurityException("Visibility mutation owns admission");
        Set<String> required=Set.copyOf(components);
        if(!required.containsAll(Set.of(InternalRuntimeOwner.ACCOUNTING_COMPONENT,"internal_session_clock",
                "internal_accessibility_modules","internal_foreground","internal_whispers","internal_popups")))
            throw new IllegalArgumentException("Incomplete INTERNAL component plan");
        return starts.reserve(internalAuthority.observe(data,expected),required).thenApply(admitted->{
            if(admitted.attempt!=null){pendingAttempt=admitted.attempt;admittedCapability=new AdmittedCapability(admitted.attempt.lease(),coordinator.resources(admitted.attempt.lease()));
                if(recoveryRequired)starts.cancel(admitted.attempt);}
            return admitted;
        });
    }
    CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt attempt, RuntimeStartAttempt.Permission kind) {
        requireUsable(); return starts.permission(attempt, kind);
    }
    CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt attempt, RuntimeStartAttempt.Prompt prompt,
            boolean actuallyGranted, int resultCode, Intent actualResult) {
        requireWorker();
        boolean projectionResult = prompt.permission == RuntimeStartAttempt.Permission.PROJECTION;
        boolean requiredReady = prompt.permission == RuntimeStartAttempt.Permission.NOTIFICATIONS || actuallyGranted;
        if (projectionResult) requiredReady &= resultCode == android.app.Activity.RESULT_OK && actualResult != null;
        Intent captured = projectionResult && requiredReady ? new Intent(actualResult) : null;
        return starts.permissionResult(attempt, prompt, requiredReady).thenApply(current -> {
            requireWorker();
            if (current && captured != null) approvedProjection.put(attempt.lease(), captured);
            else if (!current) approvedProjection.remove(attempt.lease());
            return current;
        });
    }
    CompletableFuture<RuntimeStartGateway.Starting> begin(RuntimeStartAttempt attempt, ActiveRuntimeCoordinator.StartReadiness readiness) {
        requireUsable(); return starts.begin(attempt, readiness).thenApply(started->{
            if(pendingAttempt==attempt)pendingAttempt=null;return started;
        });
    }
    boolean cancel(RuntimeStartAttempt attempt) { return starts.cancel(attempt); }
    InternalRuntimeOwner assembleInternal(RuntimeStartGateway.Starting started, InternalSubRecord expected, OwnerObservation observed) {
        requireUsable(); Objects.requireNonNull(started); Objects.requireNonNull(observed);
        if(!expected.equals(data.acknowledged())||!expected.visible)
            throw new SecurityException("Internal profile changed before assembly");
        // begin() supplied the current-process STARTING capability. A prior completed binding
        // can still be cached here; target-only observation must not mistake this NEW lease for it.
        coordinator.resources(started.lease);
        if(current!=null && current.lease==started.lease)
            throw new SecurityException("Runtime assembly is single-use");
        selections.entrySet().removeIf(entry->!entry.getKey().occupied()&&entry.getValue().attachment.managedResourcesDrained());
        if(!selections.isEmpty())throw new SecurityException("Old physical attachments have not drained");
        InternalRuntimeOwner owner=new InternalRuntimeOwner(coordinator,started.lease,data.configuration,expected,
                data.ownerData(),this::executeLifecycle,this::requireWorker,observed);
        current=new Binding(started.lease,owner.runtime,owner);
        return owner;
    }
    /** After concrete clock/modules/capture registration, not an API to substitute fake readiness. */
    ActiveRuntimeCoordinator.Result activate(RuntimeExecution<?> expected) {
        requireUsable(); Binding binding=requireBinding(expected);
        boolean previouslyPossible=binding.device!=null&&binding.device.activationMayHaveCommitted;
        if(binding.device!=null)binding.device.activationMayHaveCommitted=true;
        ActiveRuntimeCoordinator.Result result=coordinator.activate(binding.lease);
        // NOT_READY is returned before the ACTIVE write. Other aggregate errors can be post-write.
        if(binding.device!=null&&result==ActiveRuntimeCoordinator.Result.NOT_READY)
            binding.device.activationMayHaveCommitted=previouslyPossible;
        if(result==ActiveRuntimeCoordinator.Result.OK) {
            if(binding.device!=null)binding.device.activeAcknowledged=true;
            for(RuntimeCaptureHost.Selection selection:selections.values()) {
                if(selection.request.execution()==expected)
                    deliver(binding,()->selection.attachment.activationAcknowledged(),false);
            }
        }
        return result;
    }
    /** One-use opaque transport lookup only. The permission Intent remains process-local. */
    ProjectionTransport awaitProjection(RuntimeExecution<?> expected, RuntimeCaptureHost.CapturePlan plan,
            java.util.function.Function<RuntimeCaptureHost.Connection,RuntimeCaptureHost.Request> request) {
        requireUsable(); Binding binding=requireBinding(expected);
        if(plan.kind()!=RuntimeCaptureHost.Kind.PROJECTION)
            throw new SecurityException("Not the selected projection route");
        Intent approved = approvedProjection.remove(binding.lease);
        if (approved == null) throw new SecurityException("No exact returned projection permission for this lease");
        String token=UUID.randomUUID().toString();
        OwnedResourceRegistry.Registration counted=coordinator.registerWork(binding.lease,"service_start");
        OwnedResourceRegistry.Registration delivery;
        try {delivery=coordinator.registerWork(binding.lease,"service_delivery");}
        catch(RuntimeException refused){counted.close();binding.runtime.requestStop();throw refused;}
        ProjectionTransport transport=new ProjectionTransport(binding,token,counted,delivery);
        Pending pending=new Pending(binding,plan,request,android.app.Activity.RESULT_OK,approved);
        pending.transport=transport;pending.reservedDelivery=delivery;projection.put(token,pending);
        return transport;
    }
    void awaitAccessibility(RuntimeExecution<?> expected, RuntimeCaptureHost.CapturePlan plan,
            java.util.function.Function<RuntimeCaptureHost.Connection,RuntimeCaptureHost.Request> request) {
        requireUsable(); Binding binding=requireBinding(expected);
        if(plan.kind()!=RuntimeCaptureHost.Kind.ACCESSIBILITY||accessibility!=null)
            throw new SecurityException("Accessibility selection already owned or wrong route");
        accessibility=new Pending(binding,plan,request,0,null);
        selectNeutralAccessibilityIfPending();
    }
    @Override public void accessibilityConnected(RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Completion completion) {
        requireWorker();Objects.requireNonNull(connection);Objects.requireNonNull(completion);
        // Invalid/repeated registration is not the return of the pending owner's requested route.
        // In particular, never consume pending demand based only on this method's name.
        if(connection.kind!=RuntimeCaptureHost.Kind.ACCESSIBILITY||!connection.isConnected()
                ||connection.occupied()||selections.containsKey(connection)) {
            rejectUnowned(completion,RuntimeCaptureHost.Rejection.STALE_CONNECTION);return;
        }
        if(recoveryRequired) {rejectUnowned(completion,RuntimeCaptureHost.Rejection.CLEANUP_UNCERTAIN);return;}
        if(neutralAccessibility!=null) {
            rejectUnowned(completion,RuntimeCaptureHost.Rejection.CONFLICT);return;
        }
        // Idle registration is intentionally pending, not NO_RESERVATION rejection/reconnect.
        // The shell owns this exact completion ticket; no settings, windows or capture are created.
        neutralAccessibility=new NeutralAccessibility(connection,completion);
        selectNeutralAccessibilityIfPending();
    }
    private void selectNeutralAccessibilityIfPending() {
        requireWorker();NeutralAccessibility neutral=neutralAccessibility;
        if(neutral==null)return;
        if(!neutral.connection.isConnected()) {
            neutralAccessibility=null;rejectUnowned(neutral.completion,RuntimeCaptureHost.Rejection.STALE_CONNECTION);return;
        }
        if(accessibility==null)return;
        Pending pending=accessibility;
        accessibility=null;neutralAccessibility=null;
        select(neutral.connection,neutral.completion,pending);
    }
    @Override public void claimProjection(RuntimeCaptureHost.Connection connection,String token,RuntimeCaptureHost.Completion completion) {
        requireWorker(); Objects.requireNonNull(connection); Objects.requireNonNull(completion);
        // The process-local token is for the actual projection service route, not an arbitrary
        // connection carrying the string. A foreign route has not returned that launch.
        if(connection.kind!=RuntimeCaptureHost.Kind.PROJECTION||!connection.isConnected()) {
            rejectUnowned(completion,RuntimeCaptureHost.Rejection.STALE_CONNECTION);return;
        }
        Pending pending=projection.get(token);
        if(pending==null||pending.transport==null||!pending.transport.wasLaunched()) {
            rejectUnowned(completion,RuntimeCaptureHost.Rejection.NO_RESERVATION);return;
        }
        projection.remove(token);
        try {select(connection,completion,pending);}
        finally {pending.transport.serviceReturned();}
    }
    private void select(RuntimeCaptureHost.Connection connection,RuntimeCaptureHost.Completion completion,Pending pending) {
        Objects.requireNonNull(connection);Objects.requireNonNull(completion);
        if(pending==null) { rejectUnowned(completion,RuntimeCaptureHost.Rejection.NO_RESERVATION);return; }
        Binding binding=pending.binding;
        // Register BEFORE preparing any component or signalling Stop. A failed preparation may
        // seal registration; its exact rejection callback still needs the pre-owned obligation.
        OwnedResourceRegistry.Registration counted;
        try { counted=pending.reservedDelivery!=null?pending.reservedDelivery:coordinator.registerWork(binding.lease,"service_delivery"); }
        catch(RuntimeException stale) { rejectUnowned(completion,RuntimeCaptureHost.Rejection.NO_RESERVATION);return; }
        Runnable cancellation=()->completion.rejected(RuntimeCaptureHost.Rejection.NO_RESERVATION);
        if(recoveryRequired||current!=binding||!connection.isConnected()||connection.kind!=pending.plan.kind()||connection.occupied()) {
            binding.runtime.requestStop();
            new Delivery(binding,counted,()->completion.rejected(RuntimeCaptureHost.Rejection.STALE_CONNECTION),false,cancellation).post();return;
        }
        RuntimeCaptureHost.Selection selected;
        try {
            RuntimeCaptureHost.Request request=Objects.requireNonNull(pending.request.apply(connection));
            if(request.connection!=connection||request.execution()!=binding.runtime||request.plan!=pending.plan)
                throw new SecurityException("Different capture request/plan");
            if(binding.device!=null&&(!(request instanceof RuntimeCaptureHost.DeviceRequest)
                    ||((RuntimeCaptureHost.DeviceRequest)request).requireStatisticsSource()!=binding.device.statisticsSource()))
                throw new SecurityException("DEVICE attachment lacks its exact acknowledged statistics source");
            RuntimeCaptureHost.Attachment attachment=request.prepare();
            RuntimeCaptureHost.ProjectionGrant grant=pending.result==null?null:
                    new RuntimeCaptureHost.ProjectionGrant(request,pending.resultCode,pending.result);
            selected=new RuntimeCaptureHost.Selection(request,attachment,grant);
            if(selections.putIfAbsent(connection,selected)!=null)throw new SecurityException("Capture connection already selected");
        } catch(RuntimeException|Error failure) {
            binding.runtime.requestStop();
            new Delivery(binding,counted,()->completion.rejected(RuntimeCaptureHost.Rejection.ATTACHMENT_FAILED),false,cancellation).post();return;
        }
        // A post exception is not caught as a preparation failure: it can have accepted this
        // exact selected callback, so never dispatch a second/replacement result.
        Runnable neverDelivered = () -> {
            // Only this branch proves selected() never ran. A throwing rejection is not a
            // successful neutral-shell return and must retain its exact selection for recovery.
            completion.rejected(RuntimeCaptureHost.Rejection.NO_RESERVATION);
            dispatchOwner(()->rejectedBeforeDelivery.add(selected),binding);
        };
        new Delivery(binding,counted,()->completion.selected(selected),true,neverDelivered).post();
    }
    /** Register before any main post. Stop/recovery cannot bypass an actual-return obligation. */
    private void deliver(Binding binding,Runnable callback,boolean startup) {
        requireWorker(); OwnedResourceRegistry.Registration counted;
        try { counted=coordinator.registerWork(binding.lease,"service_delivery"); }
        catch(RuntimeException refused) { binding.runtime.requestStop(); throw refused; }
        Delivery delivery=new Delivery(binding,counted,callback,startup,()->{}); delivery.post();
    }
    private final class Delivery {
        final Binding binding;final OwnedResourceRegistry.Registration counted;final Runnable callback,cancellation;final boolean startup;
        final AtomicBoolean returned=new AtomicBoolean(),queued=new AtomicBoolean();
        Delivery(Binding b,OwnedResourceRegistry.Registration c,Runnable r,boolean startup,Runnable cancellation){binding=b;counted=c;callback=r;this.startup=startup;this.cancellation=cancellation;}
        void post() {
            if(!queued.compareAndSet(false,true))throw new IllegalStateException("Delivery already queued");
            try {
                if(!main.post(this::run)) {
                    queued.set(false);recoveryRequired=true;binding.runtime.requestStop();
                    // This callback was not accepted, but the real neutral service may already
                    // exist. No main callback means no exact shell rejection/close proof. Retain
                    // the obligation for explicit recovery rather than infer physical closure.
                }
            } catch(RuntimeException|Error uncertain) {
                // Handler throwing is not proof that it did not accept/run. Keep the registration
                // until that exact callback actually returns. No elapsed-time cancellation.
                recoveryRequired=true;binding.runtime.requestStop();throw uncertain;
            }
        }
        void run() {
            if(!queued.compareAndSet(true,false)||returned.get())return;
            boolean complete=true;
            try {
                if(startup) {
                    if(!binding.runtime.runStartupEffect(callback)) {
                        complete=false;
                        dispatchOwner(()->{
                            ActiveRuntimeCoordinator.Observation state=coordinator.stateFor(binding.lease.identity.target);
                            if(current==binding&&state!=null&&binding.lease.identity.equals(state.persisted.owner)
                                    &&!state.effectsSealed&&state.effectivePhase()==RuntimeOwnerStore.Phase.STARTING)post();
                            else {
                                // The original selected callback never entered STARTUP. Deliver
                                // only its exact neutral rejection, still under the same ticket.
                                new Delivery(binding,counted,cancellation,false,()->{}).post();
                                returned.set(true); // Registration ownership transferred, not closed.
                            }
                        },binding);
                    }
                } else callback.run();
            } catch(RuntimeException|Error failure) {binding.runtime.requestStop();throw failure;}
            finally {if(complete)finish();}
        }
        void finish() {
            if(!returned.compareAndSet(false,true))return;
            dispatchOwner(()->{
                counted.close();
                if(binding.device!=null)queueDeviceActivation(binding.device);
                ActiveRuntimeCoordinator.Observation state=coordinator.stateFor(binding.lease.identity.target);
                if(state!=null&&binding.lease.identity.equals(state.persisted.owner)&&state.effectsSealed)binding.runtime.requestStop();
            },binding);
        }
    }
    private void executeLifecycle(Runnable action) {
        data.execute(()->{try{action.run();}finally{retireTerminalRouting();}});
    }
    /** Only durable terminal IDLE, never a Stop request or a guessed cancelled callback. */
    private void retireTerminalRouting() {
        requireWorker(); ActiveRuntimeCoordinator.Observation state=coordinator.observe();
        if(state.startupReconciled&&!state.quarantined&&state.persisted!=null
                &&state.persisted.phase==RuntimeOwnerStore.Phase.IDLE) {
            accessibility=null; approvedProjection.clear();pendingAttempt=null;
            // The service never received these selections, so it cannot report them drained.
            // Require both its exact neutral rejection return and the actual attachment proof,
            // after durable core IDLE. A delivered-but-unclaimed selection remains service-owned.
            selections.entrySet().removeIf(entry->{
                RuntimeCaptureHost.Selection selected=entry.getValue();
                if(!rejectedBeforeDelivery.contains(selected)||entry.getKey().occupied()
                        ||!selected.attachment.managedResourcesDrained())return false;
                rejectedBeforeDelivery.remove(selected);return true;
            });
            Binding terminal=current;
            if(terminal!=null&&terminal.device!=null&&!terminal.device.activation.isDone())terminal.device.activation.complete(
                    new AppRuntimeGateway.StartResult(AppRuntimeGateway.StartState.CANCELLED,null));
            // Every launched projection has registry obligations. IDLE with an unresolved token
            // would contradict those receipts; keep it visible and require recovery, never erase it.
            if(!projection.isEmpty())recoveryRequired=true;
        }
    }
    private void dispatchOwner(Runnable action,Binding binding) {
        try {executeLifecycle(action);}catch(RuntimeException|Error uncertain){recoveryRequired=true;binding.runtime.requestStop();throw uncertain;}
    }
    private void rejectUnowned(RuntimeCaptureHost.Completion completion,RuntimeCaptureHost.Rejection reason) {
        // There is no owner/attachment authority to create. Only the neutral shell rejection is
        // delivered; no fallback DEVICE dependencies or grant may be constructed by this route.
        if(!main.post(()->completion.rejected(reason)))throw new IllegalStateException("Main rejection queue unavailable");
    }
    @Override public void disconnected(RuntimeCaptureHost.Connection connection) {
        requireWorker();Objects.requireNonNull(connection);
        NeutralAccessibility neutral=neutralAccessibility;
        if(neutral!=null&&neutral.connection==connection) {
            neutralAccessibility=null;
            rejectUnowned(neutral.completion,RuntimeCaptureHost.Rejection.STALE_CONNECTION);
        }
        RuntimeCaptureHost.Selection selected=selections.get(connection);
        if(selected!=null)selected.request.execution().requestStop();
    }
    @Override public void rejected(RuntimeCaptureHost.Connection connection,RuntimeCaptureHost.Request request,RuntimeCaptureHost.Rejection reason) {
        requireWorker();RuntimeCaptureHost.Selection selected=selections.get(connection);
        if(selected==null||selected.request!=request)throw new SecurityException("Different rejected attachment");
        selected.request.execution().requestStop();
    }
    @Override public void attachmentDrained(RuntimeCaptureHost.Connection connection,RuntimeCaptureHost.Selection selection) {
        requireWorker();
        if(selections.get(connection)!=selection||selection.request.connection!=connection
                ||!selection.attachment.managedResourcesDrained()||connection.occupied())
            throw new SecurityException("No exact service-released attachment drain receipt");
        selections.remove(connection);rejectedBeforeDelivery.remove(selection);
        Binding binding=current;
        if(binding!=null&&selection.request.execution()==binding.runtime)binding.runtime.requestStop();
    }
    private Binding requireBinding(RuntimeExecution<?> runtime) {
        Binding binding=current;if(binding==null||binding.runtime!=runtime)throw new SecurityException("Different physical runtime");return binding;
    }
    private static final class Binding {
        final RuntimeLease lease;final RuntimeExecution<?> runtime;final InternalRuntimeOwner internal;DeviceAssembly device;
        Binding(RuntimeLease lease,RuntimeExecution<?> runtime,InternalRuntimeOwner internal){this.lease=lease;this.runtime=runtime;this.internal=internal;}
    }
    private static final class Pending {
        final Binding binding;final RuntimeCaptureHost.CapturePlan plan;
        final java.util.function.Function<RuntimeCaptureHost.Connection,RuntimeCaptureHost.Request> request;
        final int resultCode;final Intent result;ProjectionTransport transport;OwnedResourceRegistry.Registration reservedDelivery;
        Pending(Binding b,RuntimeCaptureHost.CapturePlan p,java.util.function.Function<RuntimeCaptureHost.Connection,RuntimeCaptureHost.Request> r,int code,Intent result){binding=b;plan=p;request=Objects.requireNonNull(r);resultCode=code;this.result=result;}
    }

    /**
     * Counted exact service-start handoff. The final DEVICE/internal entry adapter uses the sole
     * ScreenCaptureService.ownerStartIntent builder inside launch. A launcher exception is unknown
     * acceptance, not permission to discard this ticket. No raw projection result leaves the map.
     */
    final class ProjectionTransport {
        private final Binding binding;private final String token;
        private final OwnedResourceRegistry.Registration counted,delivery;
        private boolean entered,launchReturned,serviceReturned,closed,launchTrying;
        ProjectionTransport(Binding binding,String token,OwnedResourceRegistry.Registration counted,OwnedResourceRegistry.Registration delivery){this.binding=binding;this.token=token;this.counted=counted;this.delivery=delivery;}
        /** The only concrete service Intent path; minSdk26 already supports foreground service start. */
        boolean launch() {
            if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Service launch requires main");
            return launch(token->physical.startForegroundService(ScreenCaptureService.ownerStartIntent(physical,token)));
        }
        // Package-local controlled dispatch seam. Production uses launch() above, never raw grants.
        boolean launch(java.util.function.Consumer<String> startService) {
            Objects.requireNonNull(startService);
            synchronized(this){if(closed||entered||launchTrying)return false;launchTrying=true;}
            try {
                return binding.runtime.runStartupEffect(()->{
                    synchronized(this){if(closed)throw new SecurityException("Service start cancelled");entered=true;}
                    try {startService.accept(token);}
                    finally {synchronized(this){launchReturned=true;}releaseIfReturned();}
                });
            } finally {synchronized(this){launchTrying=false;}}
        }
        boolean cancelBeforeLaunch() {
            synchronized(this){if(closed||entered||launchTrying)return false;closed=true;}
            dispatchOwner(()->{projection.remove(token);delivery.close();counted.close();binding.runtime.requestStop();},binding);
            return true;
        }
        synchronized boolean wasLaunched(){return entered&&!closed;}
        void serviceReturned(){synchronized(this){if(!entered||serviceReturned)throw new SecurityException("Different/repeated service return");serviceReturned=true;}releaseIfReturned();}
        private void releaseIfReturned() {
            synchronized(this){if(closed||!launchReturned||!serviceReturned)return;closed=true;}
            dispatchOwner(()->{
                counted.close();ActiveRuntimeCoordinator.Observation observed=coordinator.stateFor(binding.lease.identity.target);
                if(observed!=null&&binding.lease.identity.equals(observed.persisted.owner)&&observed.effectsSealed)binding.runtime.requestStop();
            },binding);
        }
    }

    @Override public CompletableFuture<InternalVisibilityBarrier.Permit> prepare(Context context,InternalVisibilityBarrier.Request request) {
        CompletableFuture<InternalVisibilityBarrier.Permit> response=new CompletableFuture<>();
        if(context!=physical){response.completeExceptionally(new SecurityException("Different physical application"));return response;}
        try {data.execute(()->{
            try {
                requireUsable();if(visibility!=null)throw new SecurityException("Visibility already pending");
                RuntimeTarget target=RuntimeTarget.internal(request.expected.targetId);
                authority.beginAvailability(target); // Revokes launch before any proof can escape.
                ActiveRuntimeCoordinator.Observation observed=coordinator.observe();
                if(!observed.startupReconciled||observed.quarantined||observed.persisted==null)
                    throw new InternalVisibilityBarrier.UnavailableException();
                RuntimeLease.Identity occupied=observed.persisted.owner;
                if(occupied!=null&&occupied.target.kind!=RuntimeTarget.Kind.DEVICE)
                    throw new InternalVisibilityBarrier.UnavailableException();
                // An acknowledged current DEVICE owner is unrelated to the internal profile and
                // is never stopped. Idle-with-unknown physical resources is still not a safe proof.
                if(occupied==null&&coordinator.anyOccupied())throw new InternalVisibilityBarrier.UnavailableException();
                if(!request.expected.equals(data.acknowledged()))throw new SecurityException("Profile changed");
                ControllerPinManager.InternalStartWitness controller=ControllerPinManager.captureInternalStartWitness(physical);
                if(controller.revision!=request.controllerRevision)throw new SecurityException("Controller changed");
                Visibility permit=new Visibility(request,controller);visibility=permit;
                if(!response.complete(permit))permit.completedWithoutAdmission();
            }catch(RuntimeException|Error failure){response.completeExceptionally(failure);}
        });}catch(RuntimeException|Error uncertain){recoveryRequired=true;response.completeExceptionally(uncertain);}
        return response;
    }
    private final class Visibility implements InternalVisibilityBarrier.Permit {
        final InternalVisibilityBarrier.Request request;final ControllerPinManager.InternalStartWitness controller;
        final AtomicBoolean completed=new AtomicBoolean();
        Visibility(InternalVisibilityBarrier.Request request,ControllerPinManager.InternalStartWitness controller){this.request=request;this.controller=controller;}
        @Override public void requireCurrent() {
            // Broker calls immediately before CAS on the same serial data worker. No Runtime lock,
            // IO, provider callback or new authority publication occurs at this mutation boundary.
            requireWorker();if(recoveryRequired||visibility!=this||completed.get()||controller.revocation.isRevoked()
                    ||!request.expected.equals(data.acknowledged()))throw new SecurityException("Visibility authority retired");
        }
        void completedWithoutAdmission(){completed.set(true);if(visibility==this)visibility=null;}
        @Override public CompletableFuture<Void> complete(InternalVisibilityBarrier.Completion result) {
            CompletableFuture<Void> response=new CompletableFuture<>();
            if(!completed.compareAndSet(false,true)){response.completeExceptionally(new SecurityException("Visibility already completed"));return response;}
            try {data.execute(()->{
                try {
                    if(visibility!=this)throw new SecurityException("Different pending visibility");
                    if(result.outcome==InternalVisibilityBarrier.Outcome.UNCERTAIN) {
                        recoveryRequired=true;throw new IllegalStateException("Visibility outcome requires recovery");
                    }
                    if(result.outcome==InternalVisibilityBarrier.Outcome.ACKNOWLEDGED
                            &&(result.acknowledged==null||!result.acknowledged.equals(data.acknowledged())))
                        throw new SecurityException("Visibility receipt changed");
                    // Known non-admission/conflict may follow a legitimate intervening editor CAS.
                    // That newer ACK is not corruption. Keep start availability revoked, release
                    // only this mutation slot, and force the next start to observe it afresh.
                    visibility=null; // A future start must freshly observe controller+record. No grant here.
                    response.complete(null);
                }catch(RuntimeException|Error failure){recoveryRequired=true;response.completeExceptionally(failure);}
            });}catch(RuntimeException|Error uncertain){recoveryRequired=true;response.completeExceptionally(uncertain);}
            return response;
        }
    }

    /** Raw observations only: never instantiate expiry/paid-pause/role/profile-effect managers. */
    private static ActiveRuntimeCoordinator.Occupancy observeLegacyOccupancy(Application app) {
        try {
            SharedPreferences main=app.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
            SharedPreferences penance=app.getSharedPreferences(PenanceManager.PREFS_NAME,0);
            SharedPreferences stats=app.getSharedPreferences(StatsRepository.PREFS_NAME,0);
            if(main.getBoolean(AppModeManager.KEY_ARMED,false)||!main.getString("managed_device_session_v1","").isEmpty()
                    ||main.getBoolean("media_projection_session_desired",false)||main.getLong("commitment_ends_at",0)!=0
                    ||!main.getString("commitment_session_identity","").isEmpty()
                    ||penance.getLong("paid_pause_active_until",0)!=0||!penance.getString("paid_pause_session_identity","").isEmpty()
                    ||!stats.getString("active_session_identity","").isEmpty()
                    ||ScreenCaptureService.isRunning()||ScreenshotAccessibilityService.isRecognitionActive()
                    ||MediaProjectionLeaseRegistry.owner()!=null||CensorLabRecorder.isActive()||CensorLabCensorSession.isActive()
                    ||CensorLabRecordingService.isActive()||PopupStormManager.physicalResourcesOccupied())
                return ActiveRuntimeCoordinator.Occupancy.OCCUPIED;
            return ActiveRuntimeCoordinator.Occupancy.CLEAR;
        }catch(RuntimeException unavailable){return ActiveRuntimeCoordinator.Occupancy.UNKNOWN;}
    }
}
