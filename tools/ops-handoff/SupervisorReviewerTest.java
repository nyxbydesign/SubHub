package com.subhub.app.runtime;
import static org.junit.Assert.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.Test;

public final class SupervisorReviewerTest {
    @Test public void replacementReadinessCallbackMustReturnBeforeParentIdle() {
        F f = new F(); f.activate(); f.retire();
        var next = f.control.replacement(f.control.retired(f.child), f.plan());
        Object connection = new Object(); var release = next.selection.bind(connection);
        next.child.installRetirement(c -> c.afterWorkReturned(c::resourcesDrained));
        assertTrue(next.child.ready(result -> {
            f.runtime.requestStop(); release.returned(connection);
            assertTrue("Parent became IDLE while replacement readiness callback is still executing", f.coordinator.anyOccupied());
        }));
        assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void captureLossKeepsSameParentUntilActualConnectionReturn() {
        F f=new F();f.activate();var owner=f.lease.identity;f.child.retire();assertNull(f.control.retired(f.child));
        assertTrue(f.runtime.runEffect(()->{}));f.release.returned(f.connection);var proof=f.control.retired(f.child);assertNotNull(proof);
        var next=f.control.replacement(proof,f.plan());assertNotNull(next);assertEquals(owner,next.child.owner());assertEquals(2,next.child.generation());
        assertFalse(f.child.normal(()->fail("old child")));next.selection.neverSelected();next.child.retire();f.runtime.requestStop();assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void nativeAndQueueReturnAreIndependent() {
        F f=new F();f.activate();var queue=f.child.ticket(false);var nativeWork=f.child.nativeWork(false,()->"facts");
        f.child.retire();f.release.returned(f.connection);queue.close();queue.close();assertNull(f.control.retired(f.child));
        assertEquals("facts",nativeWork.facts());nativeWork.close();assertNotNull(f.control.retired(f.child));assertTrue(f.runtime.runEffect(()->{}));
    }
    @Test public void endWaitsNativeAndDeniesReplacement() {
        F f=new F();f.activate();var work=f.child.nativeWork(false,()->"frame");f.runtime.requestStop();f.release.returned(f.connection);
        assertTrue(f.coordinator.anyOccupied());assertFalse(f.child.normal(()->fail()));work.close();assertFalse(f.coordinator.anyOccupied());
        assertNull(f.control.replacement(f.control.retired(f.child),f.plan()));
    }
    @Test public void alreadyRetiredChildDoesNotStrandEnd() {F f=new F();f.activate();f.retire();f.runtime.requestStop();assertFalse(f.coordinator.anyOccupied());}
    @Test public void replacementPreparationIsNormalNotStartup() {
        F f=new F();f.activate();f.retire();var next=f.control.replacement(f.control.retired(f.child),f.plan());next.selection.bind(new Object());
        next.child.installRetirement(c->c.afterWorkReturned(c::resourcesDrained));
        assertTrue(next.child.startup(()->assertFalse(f.runtime.runStartupEffect(()->fail()))));assertTrue(next.child.ready(r->assertEquals(ActiveRuntimeCoordinator.Result.OK,r)));
        assertTrue(next.child.normal(()->{}));f.current.set(false);assertFalse(next.child.normal(()->fail()));
    }
    @Test public void staleRevisionAndSourceRefuseWithoutMutation() {
        F f=new F();f.activate();f.retire();var proof=f.control.retired(f.child);
        assertNull(f.control.replacement(proof,new DeviceCaptureSupervisor.Preparation(2,1,()->true)));
        f.current.set(false);assertNull(f.control.replacement(proof,f.plan()));assertTrue(f.runtime.runEffect(()->{}));
    }
    @Test public void initialLossBeforeActiveAbortsStickyReady() {
        F f=new F();assertTrue(f.child.ready(r->{}));f.child.retire();assertFalse(f.control.captureAvailable());
        assertFalse(f.coordinator.activate(f.lease)==ActiveRuntimeCoordinator.Result.OK);f.release.returned(f.connection);assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void neverSelectedIsExplicitAndCannotConstructResources() {
        F f=new F(false,false);assertFalse(f.child.startup(()->fail()));f.child.retire();assertNull(f.control.retired(f.child));
        f.admission.selection.neverSelected();assertNotNull(f.control.retired(f.child));assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void connectionProofAndReceiptAreExact() {
        F f=new F();f.activate();f.child.retire();assertThrows(SecurityException.class,()->f.release.returned(new Object()));f.release.returned(f.connection);
        assertThrows(SecurityException.class,()->f.release.returned(f.connection));F g=new F();g.activate();g.retire();assertNull(f.control.replacement(g.control.retired(g.child),f.plan()));
    }
    @Test public void resourceProofPrecedesConnectionAndWaitsObserverReturn() {
        F f=new F(false,true);AtomicReference<DeviceCaptureSupervisor.ChildDrainReceipt> proof=new AtomicReference<>();
        f.child.installRetirement(c->{c.afterResourcesDrained(p->{proof.set(p);f.child.requireResourcesDrained(p);f.release.returned(f.connection);assertNull(f.control.retired(f.child));f.runtime.requestStop();assertTrue(f.coordinator.anyOccupied());});c.afterWorkReturned(c::resourcesDrained);});
        f.activate();f.child.retire();assertNotNull(proof.get());assertFalse(f.coordinator.anyOccupied());F g=new F();assertThrows(SecurityException.class,()->g.child.requireResourcesDrained(proof.get()));
    }
    @Test public void asynchronousDisposalReturnMustBeCounted() {
        F f=new F(false,true);AtomicReference<DeviceCaptureSupervisor.Child.Retirement.DisposalTicket> pending=new AtomicReference<>();
        f.child.installRetirement(c->c.afterWorkReturned(()->{pending.set(c.reserve());c.resourcesDrained();}));f.activate();f.retire();assertNull(f.control.retired(f.child));
        pending.get().close();assertNotNull(f.control.retired(f.child));
    }
    @Test public void durationCannotEscapeChildFence() {
        F f=new F();AtomicInteger n=new AtomicInteger();f.effects.bindDurationObserver((c,w,e)->n.incrementAndGet());f.activate();assertTrue(f.child.durationChanged(1,1));f.retire();
        assertFalse(f.child.durationChanged(1,1));assertEquals(1,n.get());assertTrue(f.runtime.runEffect(()->{}));
    }
    @Test public void unknownObserverDispatchRetainsRecovery() {
        F f=new F();f.activate();f.retiring=c->{throw new IllegalStateException("unknown");};assertThrows(IllegalStateException.class,f.child::retire);f.release.returned(f.connection);assertTrue(f.coordinator.anyOccupied());
        F g=new F();g.activate();g.child.retire();g.retired=p->{throw new IllegalStateException("unknown");};assertThrows(IllegalStateException.class,()->g.release.returned(g.connection));assertTrue(g.coordinator.anyOccupied());assertNull(g.control.retired(g.child));
    }
    @Test public void retiringObserverActualReturnBlocksParentIdle() throws Exception {observerLatch(false);}
    @Test public void retiredObserverActualReturnBlocksParentIdle() throws Exception {observerLatch(true);}
    private static void observerLatch(boolean finalObserver)throws Exception {
        F f=new F();f.activate();CountDownLatch entered=new CountDownLatch(1),leave=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        if(finalObserver)f.retired=p->{entered.countDown();await(leave);};else f.retiring=c->{entered.countDown();await(leave);};
        Thread worker=new Thread(()->{try{f.child.retire();if(finalObserver)f.release.returned(f.connection);}catch(Throwable t){error.set(t);}});worker.start();
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));if(!finalObserver)f.release.returned(f.connection);f.runtime.requestStop();assertTrue(f.coordinator.anyOccupied());assertNull(f.control.retired(f.child));}
        finally{leave.countDown();worker.join(3000);}assertFalse(worker.isAlive());assertNull(error.get());assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void provisionalReceiptOnlyWakesAfterReturn() {
        F f=new F();f.activate();AtomicInteger n=new AtomicInteger();f.retired=p->{assertNull(f.control.retired(f.child));assertNull(f.control.replacement(p,f.plan()));f.control.afterReturned(p,()->{assertNotNull(f.control.retired(f.child));n.incrementAndGet();});assertEquals(0,n.get());};
        f.retire();assertEquals(1,n.get());assertThrows(SecurityException.class,()->f.control.afterReturned(f.control.retired(f.child),()->fail()));
    }
    @Test public void wakeCountsAcrossReplacementAndEnd() throws Exception {
        F f=new F();f.activate();AtomicBoolean first=new AtomicBoolean(true);AtomicReference<DeviceCaptureSupervisor.Admission> next=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),leave=new CountDownLatch(1);
        f.retired=p->{if(first.compareAndSet(true,false))f.control.afterReturned(p,()->{next.set(f.control.replacement(p,f.plan()));entered.countDown();await(leave);});};f.child.retire();
        Thread worker=new Thread(()->{try{f.release.returned(f.connection);}catch(Throwable t){error.set(t);}});worker.start();
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));assertNotNull(next.get());f.runtime.requestStop();next.get().selection.neverSelected();next.get().child.retire();assertTrue(f.coordinator.anyOccupied());}
        finally{leave.countDown();worker.join(3000);}assertFalse(worker.isAlive());assertNull(error.get());assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void enqueueThenThrowCannotEscapeViaDrainedSuccessor() {
        F f=new F();f.activate();AtomicBoolean first=new AtomicBoolean(true);f.retired=p->{if(first.compareAndSet(true,false))f.control.afterReturned(p,()->{var next=f.control.replacement(p,f.plan());next.selection.neverSelected();next.child.retire();f.runtime.requestStop();assertTrue(f.coordinator.anyOccupied());throw new IllegalStateException("accepted then threw");});};
        f.child.retire();assertThrows(IllegalStateException.class,()->f.release.returned(f.connection));assertTrue(f.coordinator.anyOccupied());
    }
    @Test public void earlyConsumerRegistersWakeWithoutPolling()throws Exception {
        F f=new F();f.activate();AtomicReference<DeviceCaptureSupervisor.RetiredReceipt> proof=new AtomicReference<>();AtomicInteger n=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),leave=new CountDownLatch(1);
        f.retired=p->{proof.set(p);entered.countDown();await(leave);};f.child.retire();Thread worker=new Thread(()->{try{f.release.returned(f.connection);}catch(Throwable t){error.set(t);}});worker.start();
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));f.control.afterReturned(proof.get(),n::incrementAndGet);assertEquals(0,n.get());}finally{leave.countDown();worker.join(3000);}assertNull(error.get());assertEquals(1,n.get());
    }
    @Test public void promptWaitsBothLaunchAndResultReturn() {
        F f=new F();f.activate();f.retire();var next=f.control.replacement(f.control.retired(f.child),f.plan());var prompt=next.selection.promptTicket();assertNotNull(prompt);assertNull(next.selection.promptTicket());
        assertTrue(prompt.launch(()->{prompt.returned();next.child.retire();next.selection.neverSelected();assertNull(f.control.retired(next.child));}));assertNotNull(f.control.retired(next.child));
    }
    @Test public void promptCannotGrantNativePreconnectionAuthority() {
        F f=new F();f.activate();f.retire();var next=f.control.replacement(f.control.retired(f.child),f.plan());var prompt=next.selection.promptTicket();next.child.installRetirement(c->c.afterWorkReturned(c::resourcesDrained));
        assertNull(next.child.nativeWork(true,()->{fail();return "bad";}));assertTrue(prompt.launch(()->{}));next.child.retire();next.selection.neverSelected();f.runtime.requestStop();assertTrue(f.coordinator.anyOccupied());prompt.returned();assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void promptRechecksSourceAndEndAtActualLaunch() {
        F f=new F();assertThrows(SecurityException.class,f.admission.selection::promptTicket);f.activate();f.retire();var next=f.control.replacement(f.control.retired(f.child),f.plan());var prompt=next.selection.promptTicket();f.current.set(false);assertFalse(prompt.launch(()->fail()));f.current.set(true);f.runtime.requestStop();assertFalse(prompt.launch(()->fail()));next.selection.neverSelected();prompt.neverLaunched();assertFalse(f.coordinator.anyOccupied());
    }
    @Test public void inlineResultThenThrowingLaunchRetainsUncertainty() {
        F f=new F();f.activate();f.retire();var next=f.control.replacement(f.control.retired(f.child),f.plan());var prompt=next.selection.promptTicket();
        assertThrows(IllegalStateException.class,()->prompt.launch(()->{prompt.returned();throw new IllegalStateException("launch unknown");}));next.selection.neverSelected();assertTrue(f.coordinator.anyOccupied());assertThrows(SecurityException.class,prompt::neverLaunched);
    }
    @Test public void replacementEndRaceNeverRevivesParent()throws Exception {
        for(int i=0;i<200;i++){F f=new F();f.activate();f.retire();var proof=f.control.retired(f.child);AtomicReference<DeviceCaptureSupervisor.Admission> next=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();CountDownLatch go=new CountDownLatch(1);
            Thread a=new Thread(()->{try{await(go);next.set(f.control.replacement(proof,f.plan()));}catch(Throwable t){error.set(t);}}),b=new Thread(()->{try{await(go);f.runtime.requestStop();}catch(Throwable t){error.set(t);}});a.start();b.start();go.countDown();a.join(3000);b.join(3000);assertFalse(a.isAlive());assertFalse(b.isAlive());assertNull(error.get());if(next.get()!=null){next.get().selection.neverSelected();next.get().child.retire();}assertFalse(f.runtime.runEffect(()->fail()));assertFalse(f.coordinator.anyOccupied());}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static final class F {
        final RuntimeMutex mutex=new RuntimeMutex(new Object());final RuntimeAdmissionAuthority authority=new RuntimeAdmissionAuthority(mutex);
        final RuntimeOwnerStore.Backend backend=new RuntimeOwnerStore.Backend(){RuntimeOwnerStore.Record value=RuntimeOwnerStore.Record.idle(0);public RuntimeOwnerStore.Record read(){return value;}public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected,RuntimeOwnerStore.Record next){if(!value.equals(expected))return RuntimeOwnerStore.Outcome.CONFLICT;value=next;return RuntimeOwnerStore.Outcome.COMMITTED;}};
        final ActiveRuntimeCoordinator coordinator=new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex,backend),authority,()->ActiveRuntimeCoordinator.Occupancy.CLEAR,UUID.randomUUID().toString());
        final RuntimeLease lease;final RuntimeExecution<String> runtime;final DeviceRuntimeEffects effects;final DeviceCaptureSupervisor control;final DeviceCaptureSupervisor.Admission admission;final DeviceCaptureSupervisor.Child child;
        final Object connection=new Object();final DeviceCaptureSupervisor.ConnectionRelease release;final AtomicBoolean active=new AtomicBoolean(),current=new AtomicBoolean(true);
        Consumer<DeviceCaptureSupervisor.Child> retiring=c->{};Consumer<DeviceCaptureSupervisor.RetiredReceipt> retired=p->{};
        F(){this(true,true);}F(boolean closer,boolean bind){authority.completeAvailability(authority.beginAvailability(RuntimeTarget.DEVICE),"generation",1,true);authority.completeAuthorization(authority.beginAuthorization(RuntimeTarget.DEVICE,RuntimeOrigin.LOCAL_UI),1,true);assertEquals(ActiveRuntimeCoordinator.Result.OK,coordinator.reconcileStartup());lease=coordinator.reserve(authority.capture(RuntimeTarget.DEVICE,RuntimeOrigin.LOCAL_UI),Set.of("capture")).lease;assertEquals(ActiveRuntimeCoordinator.Result.OK,coordinator.beginStarting(lease,o->true));runtime=new RuntimeExecution<>(coordinator,lease,"plan");effects=new DeviceRuntimeEffects(coordinator,runtime,lease);control=new DeviceCaptureSupervisor(effects,"capture",active::get,c->retiring.accept(c),p->retired.accept(p));admission=control.initial(plan());assertNotNull(admission);child=admission.child;if(closer)child.installRetirement(c->c.afterWorkReturned(c::resourcesDrained));release=bind?admission.selection.bind(connection):null;}
        DeviceCaptureSupervisor.Preparation plan(){return new DeviceCaptureSupervisor.Preparation(1,1,current::get);}
        void activate(){assertTrue(child.ready(r->assertEquals(ActiveRuntimeCoordinator.Result.OK,r)));assertEquals(ActiveRuntimeCoordinator.Result.OK,coordinator.activate(lease));active.set(true);}
        void retire(){child.retire();release.returned(connection);}
    }
}
