package com.subhub.app.internal;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public final class OwnerDataProvisioningTest {
    @Test public void firstCreateAcknowledgesIntentBeforeOwnedBytesThenReady() throws Exception {
        Fixture f = new Fixture(); Handle h = f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE);
        assertEquals(List.of("read:", "commit:started", "create", "validate", "commit:ready"), f.events);
        assertTrue(f.bytes); assertEquals("ready", f.state); assertFalse(h.closed);
    }
    @Test public void onlyExplicitKnownSchemaUpgradeMayCreateMissingOwnerData() throws Exception {
        Fixture absent = new Fixture(); assertThrows(IOException.class, () -> absent.open(OwnerDataProvisioning.Decision.OPEN_EXISTING));
        assertEquals(List.of("read:"), absent.events); assertFalse(absent.bytes);
        Fixture upgrade = new Fixture(); upgrade.open(OwnerDataProvisioning.Decision.PROFILE_ONLY_V1_UPGRADE);
        assertEquals("ready", upgrade.state); assertTrue(upgrade.bytes);
    }
    @Test public void establishedOpenFailureNeverFallsBackToProvisioning() {
        Fixture f = new Fixture(); f.state = "ready"; f.failOpen = true;
        assertThrows(IOException.class, () -> f.open(OwnerDataProvisioning.Decision.OPEN_EXISTING));
        assertEquals(List.of("read:ready", "open"), f.events); assertFalse(f.bytes); assertEquals("ready", f.state);
    }
    @Test public void startedAndUnknownEvidencePreserveFailureWithoutRecreating() {
        for (String state : List.of("started", "corrupt", "READY")) {
            Fixture f = new Fixture(); f.state = state; f.bytes = true;
            assertThrows(IOException.class, () -> f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE));
            assertEquals(List.of("read:" + state), f.events); assertTrue(f.bytes); assertEquals(state, f.state);
        }
    }
    @Test public void failedIntentAckNeverCreatesOwnedBytesEvenIfMemoryChanged() {
        Fixture f = new Fixture(); f.failCommit = "started";
        assertThrows(IOException.class, () -> f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE));
        assertEquals("started", f.state); assertFalse(f.bytes);
        assertEquals(List.of("read:", "commit:started"), f.events);
    }
    @Test public void failedReadyOrValidationRetainsOwnedBytesAndEvidenceAndClosesOnlyHandle() {
        for (boolean validation : List.of(false, true)) {
            Fixture f = new Fixture(); if (validation) f.failValidate = true; else f.failCommit = "ready";
            assertThrows(IOException.class, () -> f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE));
            assertTrue(f.bytes); assertTrue(f.handle.closed);
            assertEquals(validation ? "started" : "ready", f.state);
            assertEquals("close", f.events.get(f.events.size() - 1));
        }
    }
    @Test public void consumedDecisionCannotBeReusedAfterFailure() {
        Fixture f = new Fixture(); f.failCommit = "started";
        assertThrows(IOException.class, () -> f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE));
        f.state = ""; f.failCommit = "";
        assertThrows(IllegalStateException.class, () -> f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE));
        assertFalse(f.bytes);
    }
    @Test public void readyAlwaysOpensOneExistingHandleEvenWithCreationDecision() throws Exception {
        Fixture f = new Fixture(); f.state = "ready"; f.bytes = true;
        f.open(OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE);
        assertEquals(List.of("read:ready", "open"), f.events);
    }
    @Test public void realFilesRemainAfterProvisionFailureAndOutsideEvidenceBlocksRetry() throws Exception {
        Path parent = Files.createTempDirectory("owner-data-provision-test-");
        Path evidence = parent.resolve("independent-registry"), directory = parent.resolve("runtime-data");
        try {
            OwnerDataProvisioning.Evidence registry = new OwnerDataProvisioning.Evidence() {
                @Override public String read() throws IOException { return Files.exists(evidence) ? Files.readString(evidence) : ""; }
                @Override public boolean commit(String state) throws IOException { Files.writeString(evidence, state); return true; }
            };
            OwnerDataProvisioning.Factory<AutoCloseable> factory = new OwnerDataProvisioning.Factory<>() {
                @Override public AutoCloseable openExisting() { throw new AssertionError("No ready data"); }
                @Override public AutoCloseable provisionNew() throws IOException {
                    Files.createDirectory(directory); Files.writeString(directory.resolve("partial"), "retained");
                    throw new IOException("Uncertain initial data write");
                }
                @Override public void validate(AutoCloseable ignored) { fail("Not created"); }
            };
            assertThrows(IOException.class, () -> new OwnerDataProvisioning().open(
                    OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE, registry, factory));
            assertEquals("started", Files.readString(evidence));
            assertEquals("retained", Files.readString(directory.resolve("partial")));
            assertThrows(IOException.class, () -> new OwnerDataProvisioning().open(
                    OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE, registry, factory));
            assertEquals("retained", Files.readString(directory.resolve("partial")));
        } finally {
            Files.deleteIfExists(directory.resolve("partial")); Files.deleteIfExists(directory);
            Files.deleteIfExists(evidence); Files.deleteIfExists(parent);
        }
    }
    private static final class Handle implements AutoCloseable {
        final List<String> events; boolean closed;
        Handle(List<String> events) { this.events = events; }
        @Override public void close() { closed = true; events.add("close"); }
    }
    private static final class Fixture implements OwnerDataProvisioning.Evidence, OwnerDataProvisioning.Factory<Handle> {
        final OwnerDataProvisioning provision = new OwnerDataProvisioning(); final List<String> events = new ArrayList<>();
        String state = "", failCommit = ""; boolean bytes, failOpen, failValidate; Handle handle;
        Handle open(OwnerDataProvisioning.Decision decision) throws Exception { return provision.open(decision, this, this); }
        @Override public String read() { events.add("read:" + state); return state; }
        @Override public boolean commit(String value) { events.add("commit:" + value); state = value; return !value.equals(failCommit); }
        @Override public Handle openExisting() throws IOException { events.add("open"); if (failOpen) throw new IOException("Missing established data"); return handle = new Handle(events); }
        @Override public Handle provisionNew() { events.add("create"); assertFalse(bytes); bytes = true; return handle = new Handle(events); }
        @Override public void validate(Handle value) throws IOException { events.add("validate"); assertSame(handle, value); if (failValidate) throw new IOException("Unacknowledged initial bytes"); }
    }
}
