package com.subhub.app.internal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class InternalTaskBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final InternalProfile PROFILE = new InternalProfile("Internal", "", "", "", 0);
    private static final class Memory implements InternalSubStore.Backend {
        InternalSubRecord record = InternalSubStore.initial(PROFILE, Map.of());
        InternalSubStore.Outcome outcome = InternalSubStore.Outcome.COMMITTED;
        public InternalSubRecord read() { return record; }
        public InternalSubStore.Outcome compareAndSet(InternalSubRecord expected, InternalSubRecord next) {
            if (!expected.equals(record)) return InternalSubStore.Outcome.CONFLICT;
            record = next; return outcome;
        }
    }
    @Test public void boundaryAndVisibilityAreAtomicAndOrdinaryEditsPreserveIt() {
        Memory memory = new Memory(); InternalSubStore store = new InternalSubStore(memory);
        InternalSubRecord initial = store.read(); assertEquals(0, initial.tasksResumeAfterMillis);
        InternalSubRecord shown = store.setVisible(initial, true, 1000);
        assertTrue(shown.visible); assertEquals(1000, shown.tasksResumeAfterMillis);
        assertNotEquals(initial.generation, shown.generation); assertEquals(0, shown.configurationRevision);
        InternalSubRecord edited = store.saveProfile(shown, new InternalProfile("Edited", "", "", "", 0));
        edited = store.saveConfiguration(edited, Map.of("fixture", Map.of("enabled", true)));
        assertEquals(1000, edited.tasksResumeAfterMillis); assertEquals(shown.generation, edited.generation);
        InternalSubRecord hidden = store.setVisible(edited, false, 1500);
        assertEquals(1000, hidden.tasksResumeAfterMillis);
        InternalSubRecord resumed = store.setVisible(hidden, true, 2000);
        assertEquals(2000, resumed.tasksResumeAfterMillis); assertNotEquals(hidden.generation, resumed.generation);
        assertSame(resumed, store.setVisible(resumed, true, 9000));
    }
    @Test public void legacyVisibleFormatIsReadableWithoutInventingBoundaryOrRewriting() throws Exception {
        File parent = temporary.newFolder();
        FileInternalSubBackend backend = FileInternalSubBackend.createNew(parent, PROFILE, Map.of());
        InternalSubRecord source = backend.read();
        byte[] legacy = legacy(source, true);
        File recordFile = new File(new File(parent, InternalStorageScope.TARGET_ID), "profile.configuration");
        Files.write(recordFile.toPath(), legacy);
        InternalSubStore store = new InternalSubStore(FileInternalSubBackend.openExisting(parent));
        InternalSubRecord read = store.read(); assertTrue(read.visible); assertEquals(0, read.tasksResumeAfterMillis);
        assertArrayEquals(legacy, Files.readAllBytes(recordFile.toPath()));
        InternalSubRecord edited = store.saveProfile(read, new InternalProfile("Still unknown", "", "", "", 0));
        assertEquals(0, edited.tasksResumeAfterMillis); // A profile edit is not a visibility transition.
        InternalSubRecord hidden = store.setVisible(edited, false, 2000);
        InternalSubRecord shown = store.setVisible(hidden, true, 3000);
        assertEquals(3000, shown.tasksResumeAfterMillis);
        assertEquals(shown, FileInternalSubBackend.openExisting(parent).read());
    }
    @Test public void versionTwoRoundTripRetainsExactBoundaryAndRejectsUnknownSchema() {
        Memory memory = new Memory(); InternalSubStore store = new InternalSubStore(memory);
        InternalSubRecord shown = store.setVisible(store.read(), true, 123456789L);
        byte[] encoded = InternalRecordCodec.encode(shown);
        assertEquals(2, java.nio.ByteBuffer.wrap(encoded).getInt(4));
        assertEquals(shown, InternalRecordCodec.decode(encoded));
        byte[] corrupt = encoded.clone(); java.nio.ByteBuffer.wrap(corrupt).putInt(4, 3);
        resign(corrupt); assertThrows(IllegalArgumentException.class, () -> InternalRecordCodec.decode(corrupt));
    }
    @Test public void onlyExplicitEnableCanReplaceBoundaryAndInvalidClocksNeverWrite() {
        Memory memory = new Memory(); InternalSubStore store = new InternalSubStore(memory);
        InternalSubRecord initial = store.read();
        assertThrows(IllegalArgumentException.class, () -> store.setVisible(initial, true, 0));
        InternalSubRecord badEnable = new InternalSubRecord(initial.targetId, UUID.randomUUID().toString(), 1, 0,
                true, 0, PROFILE, Map.of());
        assertThrows(IllegalArgumentException.class, () -> InternalSubRecord.requireSuccessor(initial, badEnable));
        InternalSubRecord shown = store.setVisible(initial, true, 1000);
        InternalSubRecord forged = new InternalSubRecord(shown.targetId, shown.generation, 2, 0, true, 2000,
                new InternalProfile("Forged", "", "", "", 0), Map.of());
        assertThrows(IllegalArgumentException.class, () -> InternalSubRecord.requireSuccessor(shown, forged));
        assertEquals(shown, store.read());
    }
    @Test public void uncertainVisibilityDoesNotPublishEitherBoundaryOrGeneration() {
        Memory memory = new Memory(); InternalSubStore store = new InternalSubStore(memory);
        InternalSubRecord initial = store.read(); memory.outcome = InternalSubStore.Outcome.UNCERTAIN;
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        store.addListener((a, b) -> publications.incrementAndGet());
        assertThrows(InternalSubStore.PersistenceException.class, () -> store.setVisible(initial, true, 1000));
        assertEquals(0, publications.get());
        assertThrows(InternalSubStore.PersistenceException.class, store::read);
    }
    /** Independent literal v1 encoder for an empty configuration, not the production v2 serializer. */
    private static byte[] legacy(InternalSubRecord source, boolean visible) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(0x53484953); out.writeInt(1); text(out, source.targetId); text(out, source.generation);
        out.writeLong(source.revision); out.writeLong(source.configurationRevision); out.writeBoolean(visible);
        text(out, PROFILE.name); text(out, PROFILE.about); text(out, PROFILE.avatar); text(out, PROFILE.gender);
        out.writeInt(PROFILE.age); out.writeInt(0); out.flush();
        out.write(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())); out.flush(); return buffer.toByteArray();
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void resign(byte[] bytes) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
            System.arraycopy(digest, 0, bytes, bytes.length - 32, 32); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
