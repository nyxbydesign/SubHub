package com.subhub.app.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeOwnerDirectorySyncTest {
    // Synthetic flags exercise the protocol without initializing Android's native OsConstants.
    private static final int EXISTING = 0x40, CLOSE_ON_EXEC = 0x04;
    private static final File DIRECTORY = new File("owned-directory");

    @Test public void preservesFlagsAndAcknowledgesBeforeCheckingAndFlushingDescriptor() throws Exception {
        Fake descriptor = new Fake();
        sync(descriptor);
        assertEquals(EXISTING | CLOSE_ON_EXEC, descriptor.flags);
        assertEquals(List.of("open", "get", "set", "get", "directory", "sync", "close"), descriptor.calls);
    }

    @Test public void openFailureDoesNotPretendToFlushOrCloseAnUnownedDescriptor() {
        IOException failed = new IOException("open");
        IOException actual = assertThrows(IOException.class, () -> RuntimeOwnerDirectorySync.sync(DIRECTORY,
                directory -> { assertEquals(DIRECTORY, directory); throw failed; }, CLOSE_ON_EXEC));
        assertSame(failed, actual);
    }

    @Test public void everyDescriptorOperationFailureClosesOnceWithoutProceeding() {
        List<String> operations = List.of("get", "set", "get", "directory", "sync");
        for (int failingOperation = 0; failingOperation < operations.size(); failingOperation++) {
            Fake descriptor = new Fake(); descriptor.failOperation = failingOperation;
            assertSame(descriptor.failure, assertThrows(IOException.class, () -> sync(descriptor)));
            List<String> expected = new ArrayList<>(); expected.add("open");
            expected.addAll(operations.subList(0, failingOperation + 1)); expected.add("close");
            assertEquals(expected, descriptor.calls);
        }
    }

    @Test public void unacknowledgedCloseOnExecNeverReachesStatOrFsync() {
        Fake descriptor = new Fake(); descriptor.acknowledgeFlags = false;
        IOException failed = assertThrows(IOException.class, () -> sync(descriptor));
        assertTrue(failed.getMessage().contains("close-on-exec"));
        assertEquals(List.of("open", "get", "set", "get", "close"), descriptor.calls);
    }

    @Test public void regularFileIsRejectedWithoutFsyncAndStillClosed() {
        Fake descriptor = new Fake(); descriptor.directory = false;
        IOException failed = assertThrows(IOException.class, () -> sync(descriptor));
        assertTrue(failed.getMessage().contains("not a directory"));
        assertEquals(List.of("open", "get", "set", "get", "directory", "close"), descriptor.calls);
    }

    @Test public void closeFailureNeverReportsAcknowledgedFlush() {
        Fake descriptor = new Fake(); descriptor.failClose = true;
        assertSame(descriptor.closeFailure, assertThrows(IOException.class, () -> sync(descriptor)));
        assertEquals(List.of("open", "get", "set", "get", "directory", "sync", "close"), descriptor.calls);
    }

    @Test public void closeFailurePreservesEveryPrimaryOperationFailure() {
        for (int failingOperation = 0; failingOperation < 5; failingOperation++) {
            Fake descriptor = new Fake(); descriptor.failOperation = failingOperation; descriptor.failClose = true;
            IOException failed = assertThrows(IOException.class, () -> sync(descriptor));
            assertSame(descriptor.failure, failed);
            assertArrayEquals(new Throwable[]{descriptor.closeFailure}, failed.getSuppressed());
            assertEquals(1, descriptor.calls.stream().filter("close"::equals).count());
        }
    }

    @Test public void closeFailureAlsoPreservesUnacknowledgedFlagAndWrongTypeRejections() {
        for (boolean flagsAcknowledged : new boolean[]{false, true}) {
            Fake descriptor = new Fake(); descriptor.acknowledgeFlags = flagsAcknowledged;
            descriptor.directory = false; descriptor.failClose = true;
            IOException failed = assertThrows(IOException.class, () -> sync(descriptor));
            assertTrue(failed.getMessage().contains(flagsAcknowledged ? "not a directory" : "close-on-exec"));
            assertArrayEquals(new Throwable[]{descriptor.closeFailure}, failed.getSuppressed());
            assertFalse(descriptor.calls.contains("sync"));
        }
    }

    private static void sync(Fake descriptor) throws IOException {
        RuntimeOwnerDirectorySync.sync(DIRECTORY, directory -> {
            assertEquals(DIRECTORY, directory); descriptor.calls.add("open"); return descriptor;
        }, CLOSE_ON_EXEC);
    }

    private static final class Fake implements RuntimeOwnerDirectorySync.Descriptor {
        final List<String> calls = new ArrayList<>();
        final IOException failure = new IOException("operation failed"), closeFailure = new IOException("close failed");
        int flags = EXISTING, failOperation = -1, operation;
        boolean acknowledgeFlags = true, directory = true, failClose;
        private void record(String name) throws IOException {
            calls.add(name);
            if (operation++ == failOperation) throw failure;
        }
        @Override public int flags() throws IOException { record("get"); return flags; }
        @Override public void flags(int value) throws IOException { record("set"); if (acknowledgeFlags) flags = value; }
        @Override public boolean isDirectory() throws IOException { record("directory"); return directory; }
        @Override public void sync() throws IOException { record("sync"); }
        @Override public void close() throws IOException { calls.add("close"); if (failClose) throw closeFailure; }
    }
}
