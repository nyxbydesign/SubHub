package com.subhub.app.runtime;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public final class RuntimeJournalMigrationTest {
    private static final UUID GENERATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final class Handle implements AutoCloseable {
        boolean closed;
        @Override public void close() { closed = true; }
    }
    private static final class Fixture implements RuntimeJournalMigration.Evidence, RuntimeJournalMigration.Journal<Handle> {
        String state="ready", receipt=RuntimeJournalMigration.firstCreationReceipt(GENERATION.toString());
        final List<String> order=new ArrayList<>(); final Handle handle=new Handle();
        boolean commit=true, readyCommit=true, createThrows, openThrows, invalid;
        String birth=GENERATION.toString(); Runnable inCreate=()->{};
        final RuntimeMutex mutex=new RuntimeMutex(new Object());
        final RuntimeInstallationBarrier barrier=new RuntimeInstallationBarrier(mutex);
        final RuntimeInstallationBarrier.Installation proof=mutex.runtime(barrier::prepareInstallation);
        int create,open;
        public String profileState(){return state;} public String receipt(){return receipt;}
        public String originalGeneration(){return birth;}
        public boolean compareAndSetReceipt(String expectedState,String expectedBirth,String expected,String replacement){if(!state.equals(expectedState)||!birth.equals(expectedBirth)||!receipt.equals(expected))return false;order.add(replacement.split(":")[1]);receipt=replacement;return commit && (!replacement.contains(":READY:")||readyCommit);}
        public Handle openExisting() throws IOException {open++;order.add("open");if(openThrows)throw new IOException();return handle;}
        public Handle provisionNew() throws IOException {create++;order.add("create");if(createThrows)throw new IOException();inCreate.run();return handle;}
        public void validateExisting(Handle h) throws IOException{order.add("validate");if(invalid)throw new IOException();}
        public void validateFirstCreation(Handle h) throws IOException{order.add("idle-zero");if(invalid)throw new IOException();}
        RuntimeJournalMigration attempt(){return new RuntimeJournalMigration(mutex,proof);}
        Handle run() {return run(attempt(),()->order.add("quiescent"));}
        Handle run(RuntimeJournalMigration attempt,RuntimeJournalMigration.CreationAdmission admission) {
            return mutex.runtime(()->{try{return attempt.open(this,this,admission);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}});
        }
    }
    @Test public void pendingRequiresQuiescentThenClaimBeforeCreatingIdle() throws Exception {
        Fixture f=new Fixture();assertSame(f.handle,f.run());assertEquals(List.of("quiescent","STARTED","create","idle-zero","READY"),f.order);assertTrue(f.receipt.endsWith(GENERATION.toString()));
    }
    @Test public void readyOnlyOpensExistingWithoutNewAdmissionOrIdleReset() throws Exception {
        Fixture f=new Fixture();f.receipt=f.receipt.replace("PENDING","READY");f.run();assertEquals(List.of("open","validate"),f.order);assertEquals(0,f.create);
    }
    @Test public void missingReceiptCannotProvisionNewFormatProfile() {
        Fixture f=new Fixture();f.receipt="";assertThrows(Exception.class,f::run);assertEquals(0,f.create);assertEquals(0,f.open);
    }
    @Test public void partialProfileNeverSuppliesReceipt() {
        Fixture f=new Fixture();f.state="started";assertThrows(Exception.class,f::run);assertTrue(f.order.isEmpty());
    }
    @Test public void interruptedClaimCannotRetryOrReopen() {
        Fixture f=new Fixture();f.receipt=f.receipt.replace("PENDING","STARTED");assertThrows(Exception.class,f::run);assertTrue(f.order.isEmpty());
    }
    @Test public void failedClaimDoesNotInvokeJournalEvenIfMemoryChanged() {
        Fixture f=new Fixture();f.commit=false;assertThrows(Exception.class,f::run);assertEquals(0,f.create);assertTrue(f.receipt.contains(":STARTED:"));
    }
    @Test public void failedCreateKeepsStartedNoFallback() {
        Fixture f=new Fixture();f.createThrows=true;assertThrows(Exception.class,f::run);assertTrue(f.receipt.contains(":STARTED:"));assertEquals(0,f.open);
    }
    @Test public void invalidFirstJournalIsClosedAndNeverMarkedReady() {
        Fixture f=new Fixture();f.invalid=true;assertThrows(Exception.class,f::run);assertTrue(f.handle.closed);assertTrue(f.receipt.contains(":STARTED:"));
    }
    @Test public void readyWriteFailureClosesHandleAndSameAttemptCannotRetry() {
        Fixture f=new Fixture();f.readyCommit=false;RuntimeJournalMigration attempt=f.attempt();
        assertThrows(Exception.class,()->f.run(attempt,()->{}));assertTrue(f.handle.closed);
        assertThrows(SecurityException.class,()->f.run(attempt,()->{}));assertEquals(1,f.create);
    }
    @Test public void missingReadyDatabaseNeverCreatesReplacement() {
        Fixture f=new Fixture();f.receipt=f.receipt.replace("PENDING","READY");f.openThrows=true;assertThrows(Exception.class,f::run);assertEquals(0,f.create);
    }
    @Test public void corruptExistingJournalIsClosedWithoutCoercion() {
        Fixture f=new Fixture();f.receipt=f.receipt.replace("PENDING","READY");f.invalid=true;assertThrows(Exception.class,f::run);assertTrue(f.handle.closed);assertEquals(0,f.create);
    }
    @Test public void failedQuiescenceDoesNotClaimOrCreate() {
        Fixture f=new Fixture();assertThrows(SecurityException.class,()->f.run(f.attempt(),()->{throw new SecurityException();}));assertTrue(f.order.isEmpty());assertTrue(f.receipt.contains(":PENDING:"));
    }
    @Test public void malformedAndUnknownReceiptsNeverGrantCreation() {
        for(String receipt:new String[]{"ready","v2:PENDING:"+GENERATION,"v1:pending:"+GENERATION,"v1:PENDING:0-0-0-0-2","v1:READY:"+GENERATION+":x"}){
            Fixture f=new Fixture();f.receipt=receipt;assertThrows(Exception.class,f::run);assertEquals(0,f.create);
        }
    }
    @Test public void freshHelperCannotOpenMemoryReadyAfterFailedCommit() {
        Fixture f=new Fixture();f.readyCommit=false;assertThrows(Exception.class,f::run);
        assertTrue(f.receipt.contains(":READY:"));assertThrows(SecurityException.class,f::run);
        assertEquals(0,f.open);assertEquals(1,f.create);
    }
    @Test public void reentrantHelperCannotConsumeSameInstallationProof() {
        Fixture f=new Fixture();f.inCreate=()->assertThrows(SecurityException.class,f::run);
        f.run();assertEquals(1,f.create);assertEquals(0,f.open);
    }
    @Test public void changedReceiptCannotBeOverwrittenAtReady() {
        Fixture f=new Fixture();f.inCreate=()->f.receipt="externally-changed";
        assertThrows(Exception.class,f::run);assertEquals("externally-changed",f.receipt);assertTrue(f.handle.closed);
    }
    @Test public void changedReceiptBeforeClaimNeverCreates() {
        Fixture f=new Fixture();assertThrows(Exception.class,()->f.run(f.attempt(),()->f.receipt="changed"));
        assertEquals(0,f.create);assertEquals("changed",f.receipt);
    }
    @Test public void wrongBirthCannotReuseValidReceipt() {
        Fixture f=new Fixture();f.birth=UUID.randomUUID().toString();assertThrows(Exception.class,f::run);assertTrue(f.order.isEmpty());
    }
    @Test public void orphanRegistryEvidenceIsNotFresh() {
        for(java.util.Map<String,?> registry:List.of(java.util.Map.of(RuntimeJournalMigration.RECEIPT,"existing"),
                java.util.Map.of("provisioning",""),java.util.Map.of("unexpected",true))) {
            assertThrows(IllegalStateException.class,()->RuntimeJournalMigration.requirePristineRegistries(registry,java.util.Map.of()));
        }
        assertThrows(IllegalStateException.class,()->RuntimeJournalMigration.requirePristineRegistries(java.util.Map.of(),java.util.Map.of("runtime_v1","ready")));
        RuntimeJournalMigration.requirePristineRegistries(java.util.Map.of(),java.util.Map.of());
    }
    @Test public void openRequiresSharedMutexNotOnlyAnInstallationObject() {
        Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.attempt().open(f,f,()->{}));assertTrue(f.order.isEmpty());
    }
    @Test public void changedBirthDuringAdmissionCannotClaim() {
        Fixture f=new Fixture();assertThrows(Exception.class,()->f.run(f.attempt(),()->f.birth=UUID.randomUUID().toString()));assertEquals(0,f.create);
    }
    @Test public void changedProfileStateDuringAdmissionCannotClaim() {
        Fixture f=new Fixture();assertThrows(Exception.class,()->f.run(f.attempt(),()->f.state="started"));assertEquals(0,f.create);
    }
    @Test public void changedBirthDuringCreationCannotPublishReady() {
        Fixture f=new Fixture();f.inCreate=()->f.birth=UUID.randomUUID().toString();assertThrows(Exception.class,f::run);assertTrue(f.handle.closed);assertTrue(f.receipt.contains(":STARTED:"));
    }
}
