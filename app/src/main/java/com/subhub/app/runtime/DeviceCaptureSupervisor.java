package com.subhub.app.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stable DEVICE capture obligation. The factory retains this control and connection capabilities;
 * services receive only a Child. Retiring capture never ends the parent pact/statistics session.
 * Replacement uses parent NORMAL, not reopened STARTUP. All observers only enqueue exact owner
 * work; independently accepted queue bodies need their own actual-return registration.
 */
public final class DeviceCaptureSupervisor {
    private final Object lock = new Object();
    private final DeviceRuntimeEffects effects;
    private final DeviceRuntimeEffects.DeviceComponent component;
    private final BooleanSupplier parentActiveAcknowledged;
    private final Consumer<Child> childRetiring;
    private final Consumer<RetiredReceipt> childRetired;
    private Child current;
    private long generation;
    private boolean sealed, failed, parentDrainAcknowledged;
    private int notifications;
    private DeviceRuntimeEffects.DeviceComponent.Cleanup parentCleanup;

    /** Memory-only, nonblocking revocation observation; never read policy/preferences here. */
    static final class Preparation {
        final long configurationRevision, sourceRevision;
        final BooleanSupplier stillCurrent;
        Preparation(long configurationRevision, long sourceRevision, BooleanSupplier stillCurrent) {
            if (configurationRevision < 0 || sourceRevision < 0) throw new IllegalArgumentException("Invalid revision");
            this.configurationRevision = configurationRevision; this.sourceRevision = sourceRevision;
            this.stillCurrent = Objects.requireNonNull(stillCurrent);
        }
    }
    static final class Admission {
        final Child child;
        final SelectionRegistration selection;
        private Admission(Child child, SelectionRegistration selection) { this.child = child; this.selection = selection; }
    }
    static final class RetiredReceipt {
        private final Child child;
        private Runnable wakeup;
        private boolean wakeRegistered, wakeStarted;
        private RetiredReceipt(Child child) { this.child = child; }
    }
    /** This proves resources returned, NOT that connection/gateway delivery returned. */
    public static final class ChildDrainReceipt {
        private final Child child;
        private ChildDrainReceipt(Child child) { this.child = child; }
        public RuntimeLease.Identity owner() { return child.owner(); }
        public long generation() { return child.generation(); }
    }

    DeviceCaptureSupervisor(DeviceRuntimeEffects effects, String name, BooleanSupplier parentActiveAcknowledged,
            Consumer<Child> childRetiring, Consumer<RetiredReceipt> childRetired) {
        this.effects = Objects.requireNonNull(effects);
        this.parentActiveAcknowledged = Objects.requireNonNull(parentActiveAcknowledged);
        this.childRetiring = Objects.requireNonNull(childRetiring); this.childRetired = Objects.requireNonNull(childRetired);
        component = effects.component(name); component.usesNoConfiguration();
        component.installShutdown(cleanup -> {
            Child child;
            synchronized (lock) { sealed = true; parentCleanup = cleanup; child = current; }
            if (child != null) child.retire();
            drainParent();
        });
    }
    private boolean valid(Preparation preparation) {
        return effects.appliedConfigurationRevision() == preparation.configurationRevision && preparation.stillCurrent.getAsBoolean();
    }
    Admission initial(Preparation preparation) { return admit(null, preparation, true); }
    Admission replacement(RetiredReceipt prior, Preparation preparation) { return admit(Objects.requireNonNull(prior), preparation, false); }
    private Admission admit(RetiredReceipt prior, Preparation preparation, boolean initial) {
        Objects.requireNonNull(preparation); AtomicReference<Admission> result = new AtomicReference<>();
        Runnable action = () -> {
            if (!valid(preparation)) return;
            synchronized (lock) {
                if (sealed || failed || (initial ? current != null : current == null || prior.child != current || current.receipt != prior)) return;
                if (generation == Long.MAX_VALUE) throw new IllegalStateException("Capture generation exhausted");
                Child child = new Child(++generation, preparation, initial); current = child;
                result.set(new Admission(child, new SelectionRegistration(child)));
            }
        };
        if (initial) component.startup(action); else component.normal(action);
        return result.get();
    }
    RetiredReceipt retired(Child child) {
        synchronized (lock) {
            if (child == null || child.supervisor() != this) throw new SecurityException("Different child");
            return child.receipt;
        }
    }
    boolean captureAvailable() {
        if (!parentActiveAcknowledged.getAsBoolean()) return false;
        Child child;
        synchronized (lock) {
            child = current;
            if (!availableLocked(child)) return false;
        }
        if (!valid(child.preparation)) return false;
        synchronized (lock) { return current == child && availableLocked(child); }
    }
    private boolean availableLocked(Child child) {
        return !sealed && !failed && child != null && child.ready && !child.retiring && !child.failed
                && child.connectionAssigned && !child.connectionReturned;
    }
    /** One wake after original observer return; its dispatch-call return is counted across children. */
    void afterReturned(RetiredReceipt proof, Runnable wakeup) {
        Objects.requireNonNull(wakeup); boolean dispatch = false;
        synchronized (lock) {
            if (proof == null || proof.child.supervisor() != this || failed || parentDrainAcknowledged
                    || (proof.child.pendingReceipt != proof && proof.child.receipt != proof) || proof.wakeRegistered)
                throw new SecurityException("Retirement wake no longer admits registration");
            proof.wakeRegistered = true; proof.wakeup = wakeup;
            if (proof.child.receipt == proof) { proof.wakeStarted = true; notifications++; dispatch = true; }
        }
        if (dispatch) wake(proof);
    }
    private void wake(RetiredReceipt proof) {
        try { proof.wakeup.run(); }
        catch (RuntimeException | Error uncertain) { proof.child.fail(); throw uncertain; }
        finally { synchronized (lock) { notifications--; } drainParent(); }
    }
    private void drainParent() {
        DeviceRuntimeEffects.DeviceComponent.Cleanup cleanup = null;
        synchronized (lock) {
            if (sealed && !failed && !parentDrainAcknowledged && parentCleanup != null && notifications == 0
                    && (current == null || current.receipt != null)) {
                parentDrainAcknowledged = true; cleanup = parentCleanup;
            }
        }
        if (cleanup != null) {
            try { cleanup.drained(); }
            catch (RuntimeException | Error uncertain) {
                synchronized (lock) { failed = true; } component.cleanupFailed(); throw uncertain;
            }
        }
    }

    /** Factory-only; explicit never-selected proof is not cancellation of an escaped delivery. */
    final class SelectionRegistration {
        private final Child child;
        private boolean terminal, promptIssued;
        private SelectionRegistration(Child child) { this.child = child; }
        ConnectionRelease bind(Object exactConnection) {
            Objects.requireNonNull(exactConnection); AtomicReference<ConnectionRelease> result = new AtomicReference<>();
            Runnable action = () -> {
                if (!valid(child.preparation)) return;
                synchronized (lock) {
                    if (terminal || failed || sealed || current != child || child.retiring) throw new SecurityException("Selection retired");
                    terminal = true; child.connectionAssigned = true; result.set(new ConnectionRelease(child, exactConnection));
                }
            };
            if (child.initial) component.startup(action); else component.normal(action);
            if (result.get() == null) throw new SecurityException("Connection admission unavailable");
            return result.get();
        }
        void neverSelected() {
            synchronized (lock) {
                if (terminal) throw new SecurityException("Selection already bound or retired");
                terminal = true; child.connectionReturned = true;
            }
            child.progress();
        }
        PromptTicket promptTicket() {
            if (child.initial) throw new SecurityException("Initial consent belongs to reserved start attempt");
            AtomicReference<PromptTicket> result = new AtomicReference<>();
            component.normal(() -> {
                if (!valid(child.preparation)) return;
                synchronized (lock) {
                    if (terminal || promptIssued || failed || sealed || current != child || child.retiring) return;
                    promptIssued = true; child.work++; result.set(new PromptTicket(this));
                }
            });
            return result.get();
        }
    }
    /** Factory keeps this until actual selected attachment, slot and gateway/ingress returns. */
    final class ConnectionRelease {
        private final Child child;
        private final Object connection;
        private boolean returned;
        private ConnectionRelease(Child child, Object connection) { this.child = child; this.connection = connection; }
        void returned(Object exactConnection) {
            synchronized (lock) {
                if (connection != exactConnection || returned) throw new SecurityException("Different/repeated connection return");
                returned = true; child.connectionReturned = true;
            }
            child.progress();
        }
    }
    /** Factory-only replacement consent lifetime; no native construction or old-grant reuse. */
    final class PromptTicket {
        private final SelectionRegistration selection;
        private final Child child;
        private boolean started, launchReturned, resultReturned, closed;
        private PromptTicket(SelectionRegistration selection) { this.selection = selection; child = selection.child; }
        boolean launch(Runnable action) {
            Objects.requireNonNull(action); boolean[] entered = {false};
            component.normal(() -> {
                if (!valid(child.preparation)) return;
                synchronized (lock) {
                    if (closed || started || selection.terminal || failed || sealed || current != child || child.retiring) return;
                    started = true; entered[0] = true;
                }
                try { action.run(); }
                catch (RuntimeException | Error uncertain) { child.fail(); throw uncertain; }
                finally { synchronized (lock) { launchReturned = true; } releaseIfReturned(); }
            });
            return entered[0];
        }
        void returned() {
            synchronized (lock) {
                if (!started || resultReturned || closed) throw new SecurityException("Different/repeated prompt return");
                resultReturned = true;
            }
            releaseIfReturned();
        }
        /** Only with actual known non-dispatch; a throwing launch/queue call is not this proof. */
        void neverLaunched() {
            synchronized (lock) { if (started || closed) throw new SecurityException("Prompt may have launched"); closed = true; child.work--; }
            child.progress();
        }
        private void releaseIfReturned() {
            synchronized (lock) { if (closed || !launchReturned || !resultReturned) return; closed = true; child.work--; }
            child.progress();
        }
    }

    public final class Child {
        private final long generation;
        private final Preparation preparation;
        private final boolean initial;
        private boolean ready, readyRequested, retiring, failed, closerDispatched, closerReturned;
        private boolean connectionAssigned, connectionReturned, resourcesReturned, retirementNotifying;
        private int effectsRunning, work, disposal, observers;
        private Consumer<Retirement> closer;
        private final Retirement retirement = new Retirement();
        private ChildDrainReceipt resourcesReceipt;
        private RetiredReceipt receipt, pendingReceipt;
        private Child(long generation, Preparation preparation, boolean initial) { this.generation = generation; this.preparation = preparation; this.initial = initial; }
        private DeviceCaptureSupervisor supervisor() { return DeviceCaptureSupervisor.this; }
        public RuntimeLease.Identity owner() { return component.owner(); }
        public long generation() { return generation; }
        public long sourceRevision() { return preparation.sourceRevision; }
        public boolean retiring() { synchronized (lock) { return retiring || sealed; } }
        public void installRetirement(Consumer<Retirement> closer) {
            synchronized (lock) {
                if (this.closer != null || retiring || sealed || connectionReturned) throw new SecurityException("Closer retired");
                this.closer = Objects.requireNonNull(closer);
            }
        }
        public boolean startup(Runnable action) { return effect(true, action); }
        public boolean normal(Runnable action) { return effect(false, action); }
        public boolean durationChanged(long wall, long elapsed) {
            boolean[] observed = {false}; boolean entered = normal(() -> observed[0] = component.durationChanged(wall, elapsed));
            return entered && observed[0];
        }
        private boolean effect(boolean preparing, Runnable action) {
            Objects.requireNonNull(action); boolean[] entered = {false};
            Runnable admitted = () -> {
                if (!valid(preparation)) return;
                synchronized (lock) {
                    if (sealed || DeviceCaptureSupervisor.this.failed || current != this || retiring || failed || closer == null
                            || !connectionAssigned || connectionReturned || (preparing ? readyRequested : !ready)) return;
                    effectsRunning++; entered[0] = true;
                }
                try { action.run(); }
                finally { synchronized (lock) { effectsRunning--; } progress(); }
            };
            if (preparing && initial) component.startup(admitted); else component.normal(admitted);
            return entered[0];
        }
        public Ticket ticket(boolean preparing) {
            AtomicReference<Ticket> result = new AtomicReference<>();
            effect(preparing, () -> { synchronized (lock) { work++; result.set(new Ticket()); } }); return result.get();
        }
        public <T> NativeWork<T> nativeWork(boolean preparing, Supplier<T> capture) {
            Objects.requireNonNull(capture); AtomicReference<NativeWork<T>> result = new AtomicReference<>();
            effect(preparing, () -> {
                synchronized (lock) { work++; } boolean transferred = false;
                try {
                    DeviceRuntimeEffects.DeviceComponent.NativeWork<T> value = preparing && initial ? component.startupWork(capture) : component.normalWork(capture);
                    if (value != null) { result.set(new NativeWork<>(value)); transferred = true; }
                } finally { if (!transferred) { synchronized (lock) { work--; } progress(); } }
            }); return result.get();
        }
        public final class Ticket implements AutoCloseable {
            private boolean returned;
            private Ticket() { }
            @Override public void close() { synchronized (lock) { if (returned) return; returned = true; work--; } progress(); }
        }
        public final class NativeWork<T> implements AutoCloseable {
            private final DeviceRuntimeEffects.DeviceComponent.NativeWork<T> value;
            private boolean returned;
            private NativeWork(DeviceRuntimeEffects.DeviceComponent.NativeWork<T> value) { this.value = value; }
            public T facts() { return value.facts(); }
            @Override public void close() {
                synchronized (this) { if (returned) return; returned = true; }
                try { value.close(); } finally { synchronized (lock) { work--; } progress(); }
            }
        }
        public boolean ready(Consumer<ActiveRuntimeCoordinator.Result> completion) {
            Objects.requireNonNull(completion);
            ReadinessDelivery exact = new ReadinessDelivery(completion);
            AtomicReference<ReadinessDelivery> delivery = new AtomicReference<>();
            try {
                effect(true, () -> {
                    synchronized (lock) {
                        if (readyRequested || retiring || sealed || DeviceCaptureSupervisor.this.failed
                                || current != this || connectionReturned) return;
                        // Reserve BOTH returns before publication or leaving the admitted effect.
                        observers += 2; notifications += 2;
                        delivery.set(exact);
                        readyRequested = true;
                    }
                });
            } catch (RuntimeException | Error uncertain) {
                fail();
                if (delivery.get() != null) exact.notDispatched();
                throw uncertain;
            }
            if (delivery.get() == null) return false;
            exact.dispatch();
            return true;
        }
        /** A callback result is not its actual return, nor the scheduling call's actual return. */
        private final class ReadinessDelivery {
            private final Consumer<ActiveRuntimeCoordinator.Result> completion;
            private boolean callbackEntered, callbackReturned, dispatchReturned;
            private ReadinessDelivery(Consumer<ActiveRuntimeCoordinator.Result> completion) { this.completion = completion; }
            private void dispatch() {
                try {
                    if (initial) component.readyAsync(this::invoke, this::observerReturned);
                    else {
                        boolean success = false;
                        try { invoke(ActiveRuntimeCoordinator.Result.OK); success = true; }
                        finally { observerReturned(success); }
                    }
                } catch (RuntimeException | Error uncertain) {
                    // A throwing dispatcher may have accepted work. Do not retire its callback.
                    fail(); throw uncertain;
                } finally { returned(false); }
            }
            private void invoke(ActiveRuntimeCoordinator.Result result) {
                boolean stillCurrent = result == ActiveRuntimeCoordinator.Result.OK && valid(preparation);
                synchronized (lock) {
                    if (callbackEntered || callbackReturned) throw new SecurityException("Repeated readiness delivery");
                    callbackEntered = true;
                    if (result == ActiveRuntimeCoordinator.Result.OK) {
                        if (retiring || sealed || DeviceCaptureSupervisor.this.failed || current != Child.this
                                || !connectionAssigned || connectionReturned) result = ActiveRuntimeCoordinator.Result.STALE_LEASE;
                        else if (!stillCurrent) result = ActiveRuntimeCoordinator.Result.CONFIGURATION_CHANGED;
                        else ready = true;
                    }
                }
                completion.accept(result);
            }
            private void observerReturned(boolean success) {
                try { if (!success) fail(); }
                finally { returned(true); }
            }
            private void notDispatched() {
                // Called only when the admission itself threw, before dispatch() was invoked.
                returned(true); returned(false);
            }
            private void returned(boolean callback) {
                synchronized (lock) {
                    if (callback ? callbackReturned : dispatchReturned) return;
                    if (callback) callbackReturned = true; else dispatchReturned = true;
                    observers--; notifications--;
                }
                progress(); drainParent();
            }
        }
        public void retire() {
            boolean first;
            synchronized (lock) { first = !retiring; retiring = true; if (first) { observers++; notifications++; } }
            if (first) {
                try { if (initial && !parentActiveAcknowledged.getAsBoolean()) component.failed(); childRetiring.accept(this); }
                catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
                finally { synchronized (lock) { observers--; notifications--; } progress(); drainParent(); }
            } else progress();
        }
        public void uncertainFailure() { fail(); }
        private void fail() { synchronized (lock) { failed = true; DeviceCaptureSupervisor.this.failed = true; retiring = true; } component.cleanupFailed(); }
        public void requireResourcesDrained(ChildDrainReceipt proof) {
            synchronized (lock) { if (proof == null || proof.child != this || resourcesReceipt != proof) throw new SecurityException("Different resource drain"); }
        }
        private void progress() {
            Consumer<Retirement> close = null; Runnable afterWork = null;
            Consumer<ChildDrainReceipt> resourcesObserver = null; ChildDrainReceipt resourceProof = null;
            RetiredReceipt published = null;
            synchronized (lock) {
                if (!retiring || failed || receipt != null) return;
                if (effectsRunning == 0 && !closerDispatched) {
                    if (closer != null) { closerDispatched = true; close = closer; }
                    else if (connectionReturned && !connectionAssigned) { closerDispatched = true; closerReturned = true; resourcesReturned = true; }
                }
                if (work == 0 && retirement.afterWork != null && !retirement.afterWorkDispatched) {
                    retirement.afterWorkDispatched = true; disposal++; afterWork = retirement.afterWork;
                }
                if (effectsRunning == 0 && work == 0 && disposal == 0 && closerReturned && resourcesReturned) {
                    if (resourcesReceipt == null) resourcesReceipt = new ChildDrainReceipt(this);
                    if (retirement.resourcesObserver != null && !retirement.resourcesObserverDispatched) {
                        retirement.resourcesObserverDispatched = true; disposal++; resourcesObserver = retirement.resourcesObserver; resourceProof = resourcesReceipt;
                    }
                }
                if (effectsRunning == 0 && work == 0 && disposal == 0 && observers == 0 && !retirementNotifying
                        && closerReturned && resourcesReturned && connectionReturned) {
                    retirementNotifying = true; observers++; notifications++; pendingReceipt = new RetiredReceipt(this); published = pendingReceipt;
                }
            }
            if (close != null) {
                try {
                    close.accept(retirement);
                } catch (RuntimeException | Error uncertain) {
                    fail();
                    throw uncertain;
                } finally {
                    synchronized (lock) { closerReturned = true; }
                    progress();
                }
            }
            if (afterWork != null) {
                try {
                    afterWork.run();
                } catch (RuntimeException | Error uncertain) {
                    fail();
                    throw uncertain;
                } finally {
                    synchronized (lock) { disposal--; }
                    progress();
                }
            }
            if (resourcesObserver != null) {
                try {
                    resourcesObserver.accept(resourceProof);
                } catch (RuntimeException | Error uncertain) {
                    fail();
                    throw uncertain;
                } finally {
                    synchronized (lock) { disposal--; }
                    progress();
                }
            }
            if (published != null) {
                boolean returned = false, dispatch = false;
                try { childRetired.accept(published); returned = true; }
                catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
                finally {
                    synchronized (lock) {
                        observers--; notifications--; retirementNotifying = false;
                        if (returned && !failed) {
                            receipt = published;
                            if (published.wakeRegistered && !published.wakeStarted) { published.wakeStarted = true; notifications++; dispatch = true; }
                        }
                        pendingReceipt = null;
                    }
                }
                if (dispatch) {
                    wake(published);
                }
            }
            drainParent();
        }
        public final class Retirement {
            private Runnable afterWork;
            private boolean afterWorkDispatched, resourcesObserverDispatched;
            private Consumer<ChildDrainReceipt> resourcesObserver;
            private Retirement() { }
            public void afterWorkReturned(Runnable observer) {
                synchronized (lock) {
                    requireOpen();
                    if (afterWork != null) {
                        throw new SecurityException("Observer already installed");
                    }
                    afterWork = Objects.requireNonNull(observer);
                }
                progress();
            }
            public void afterResourcesDrained(Consumer<ChildDrainReceipt> observer) {
                synchronized (lock) {
                    requireOpen();
                    if (resourcesObserver != null) {
                        throw new SecurityException("Observer already installed");
                    }
                    resourcesObserver = Objects.requireNonNull(observer);
                }
                progress();
            }
            /** Disposal only, never physical mutation authority. Async dispatch/body need separate tickets. */
            public void run(Runnable action) {
                Objects.requireNonNull(action);
                try (DisposalTicket ticket = reserve()) { Objects.requireNonNull(ticket); action.run(); }
                catch (RuntimeException | Error uncertain) { fail(); throw uncertain; }
            }
            public DisposalTicket reserve() { synchronized (lock) { requireOpen(); disposal++; return new DisposalTicket(); } }
            public void resourcesDrained() { synchronized (lock) { requireOpen(); resourcesReturned = true; } progress(); }
            private void requireOpen() { if (!retiring || failed || receipt != null || resourcesReturned) throw new SecurityException("Child retirement closed"); }
            public final class DisposalTicket implements AutoCloseable {
                private boolean returned;
                private DisposalTicket() { }
                @Override public void close() {
                    synchronized (lock) {
                        if (returned) {
                            return;
                        }
                        returned = true;
                        disposal--;
                    }
                    progress();
                }
            }
        }
    }
}
