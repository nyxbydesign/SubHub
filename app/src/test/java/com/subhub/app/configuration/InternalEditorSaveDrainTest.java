package com.subhub.app.configuration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InternalEditorSaveDrainTest {
    @Test public void heldAckRotationSerializesPreparationAcrossReplacementObservers() {
        Fixture f=new Fixture();List<InternalEditorSaveDrain.State> old=new ArrayList<>(),replacement=new ArrayList<>();
        InternalEditorSaveDrain.Observation binding=f.drain.observe(old::add);
        f.edit(1);f.drain.request();assertEquals(1,f.prepared);
        f.edit(24);f.drain.request();binding.close();f.drain.observe(replacement::add);f.drain.request();
        assertEquals(1,f.prepared);assertEquals(1,f.writes.size());
        f.ack();assertEquals(1,f.prepared);f.deliver();
        assertEquals(2,f.prepared);assertEquals(1,f.writes.size());assertEquals(1,f.acknowledged);
        f.ack();f.deliver();
        assertEquals(24,f.acknowledged);assertFalse(f.drain.pending());assertNull(f.drain.failure());
        assertEquals(InternalEditorSaveDrain.State.SAVED,f.drain.state());
        assertEquals(InternalEditorSaveDrain.State.SAVED,replacement.get(replacement.size()-1));
        assertEquals(2,old.size());
    }
    @Test public void multipleHeldEditsCoalesceAndALaterEditStillPersists() {
        Fixture f=new Fixture();f.edit(1);f.drain.request();
        for(int value:new int[]{2,3,24}){f.edit(value);f.drain.request();}
        assertEquals(1,f.prepared);f.ack();f.deliver();f.ack();f.deliver();
        assertEquals(24,f.acknowledged);assertEquals(2,f.prepared);
        f.edit(2);f.drain.request();f.ack();f.deliver();
        assertEquals(2,f.acknowledged);assertEquals(3,f.prepared);assertFalse(f.driver.hasChanges());
    }
    @Test public void closingEveryViewStillDrainsOnlyAlreadyStagedChanges() {
        Fixture f=new Fixture();List<InternalEditorSaveDrain.State> view=new ArrayList<>();
        InternalEditorSaveDrain.Observation binding=f.drain.observe(view::add);
        f.edit(1);f.drain.request();f.edit(24);f.drain.request();binding.close();
        f.ack();f.deliver();f.ack();f.deliver();
        assertEquals(24,f.acknowledged);assertEquals(2,view.size());assertEquals(InternalEditorSaveDrain.State.SAVED,f.drain.state());
    }
    @Test public void revocationAfterAnAdmittedAckCannotPrepareTheRemainingStagedImage() {
        Fixture f=new Fixture();f.edit(1);f.drain.request();f.edit(24);f.current=false;
        f.ack();f.deliver();
        assertEquals(1,f.acknowledged);assertEquals(1,f.prepared);assertTrue(f.driver.hasChanges());
        assertEquals(InternalEditorSaveDrain.State.REVOKED,f.drain.state());
        f.current=true;f.drain.request();assertEquals(1,f.prepared);
    }
    @Test public void queuedAdmissionFailureReachesReplacementAndDoesNotRebase() {
        Fixture f=new Fixture();f.edit(1);f.drain.request();List<InternalEditorSaveDrain.State> replacement=new ArrayList<>();
        f.drain.observe(replacement::add);f.edit(24);
        SecurityException denied=new SecurityException("Revoked before broker admission");f.writes.remove().ack.completeExceptionally(denied);f.deliver();
        assertSame(denied,f.drain.failure());assertEquals(InternalEditorSaveDrain.State.FAILED,f.drain.state());
        assertEquals(0,f.revision);assertEquals(0,f.acknowledged);assertEquals(1,f.prepared);
        f.drain.request();assertEquals(1,f.prepared);
        assertEquals(InternalEditorSaveDrain.State.FAILED,replacement.get(replacement.size()-1));
    }
    @Test public void acknowledgementDeliveryMustSettleBeforeAnotherImageIsPrepared() {
        Fixture f=new Fixture();f.edit(1);f.drain.request();f.ack();f.edit(24);f.drain.request();
        assertEquals(1,f.prepared);assertTrue(f.drain.pending());
        f.deliver();assertEquals(2,f.prepared);f.ack();f.deliver();assertEquals(24,f.acknowledged);
    }
    @Test public void observerFailureCannotInterruptOrDuplicatePersistence() {
        Fixture f=new Fixture();f.drain.observe(state->{throw new IllegalStateException("Detached view");});
        f.edit(1);f.drain.request();f.edit(24);f.ack();f.deliver();f.ack();f.deliver();
        assertEquals(24,f.acknowledged);assertEquals(2,f.prepared);assertNull(f.drain.failure());
    }
    private static final class Write {
        final int expected,value;final CompletableFuture<Void> ack=new CompletableFuture<>();
        Write(int expected,int value){this.expected=expected;this.value=value;}
    }
    private static final class Fixture {
        final ArrayDeque<Write> writes=new ArrayDeque<>();final ArrayDeque<Runnable> deliveries=new ArrayDeque<>();
        int staged,acknowledged,revision,prepared;boolean current=true;
        final InternalEditorSaveDrain.Driver driver=new InternalEditorSaveDrain.Driver(){
            @Override public boolean current(){return current;}
            @Override public boolean hasChanges(){return staged!=acknowledged;}
            @Override public CompletableFuture<Void> prepareAndSubmit(){prepared++;Write write=new Write(revision,staged);writes.add(write);return write.ack;}
        };
        final InternalEditorSaveDrain drain=new InternalEditorSaveDrain(driver,deliveries::add);
        void edit(int value){staged=value;}
        void ack(){Write write=writes.remove();assertEquals("A prepared image may not rebase",revision,write.expected);revision++;acknowledged=write.value;write.ack.complete(null);}
        void deliver(){while(!deliveries.isEmpty())deliveries.remove().run();}
    }
}
