package com.subhub.app.internal;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;

/**
 * Independent durable data only, never the global physical owner journal. Explicit create/open
 * prevents missing established files from silently becoming defaults. A failed first creation
 * leaves its directory in place and requires explicit recovery rather than automatic reseeding.
 */
public final class FileInternalSubBackend implements InternalSubStore.Backend {
    private static final String RECORD = "profile.configuration", LOCK = "storage.lock";
    // FileChannel locks coordinate processes; this gate avoids overlapping locks in one process.
    private static final Map<Path, Object> GATES = new java.util.concurrent.ConcurrentHashMap<>();
    private final InternalStorageScope scope;
    private final Object gate;

    private FileInternalSubBackend(Path root) throws IOException {
        scope = new InternalStorageScope(root); gate = GATES.computeIfAbsent(scope.root(), unused -> new Object());
    }
    public InternalStorageScope scope() { return scope; }

    /**
     * Parent is an independently trusted physical-directory boundary. Its platform aliases may be
     * canonicalized; the owned target root and everything below it are always checked without following links.
     */
    public static FileInternalSubBackend createNew(File parent, InternalProfile profile,
            Map<String, ? extends Map<String, ?>> configuration) throws IOException {
        InternalSubRecord initial = InternalSubStore.initial(profile, configuration);
        byte[] bytes = InternalRecordCodec.encode(initial);
        Path folder = InternalStorageScope.canonicalTrustedParent(parent);
        Path root = folder.resolve(InternalStorageScope.TARGET_ID);
        Files.createDirectory(root); // Never replace an established namespace, even when empty/corrupt.
        FileInternalSubBackend backend = new FileInternalSubBackend(root);
        Files.createFile(root.resolve(LOCK));
        backend.writeRecord(bytes);
        syncDirectory(folder);
        return backend;
    }

    public static FileInternalSubBackend openExisting(File parent) throws IOException {
        FileInternalSubBackend backend = new FileInternalSubBackend(
                InternalStorageScope.canonicalTrustedParent(parent).resolve(InternalStorageScope.TARGET_ID));
        backend.read();
        return backend;
    }

    @Override public InternalSubRecord read() throws IOException {
        synchronized (gate) {
            try (FileChannel channel = lockChannel(); FileLock lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("Internal lock unavailable");
                return readRecord();
            }
        }
    }

    @Override public InternalSubStore.Outcome compareAndSet(InternalSubRecord expected, InternalSubRecord next) {
        InternalSubRecord.requireSuccessor(expected, next);
        byte[] bytes = InternalRecordCodec.encode(next);
        synchronized (gate) {
            try (FileChannel channel = lockChannel(); FileLock lock = channel.lock()) {
                if (!lock.isValid()) return InternalSubStore.Outcome.UNCERTAIN;
                if (!expected.equals(readRecord())) return InternalSubStore.Outcome.CONFLICT;
                writeRecord(bytes);
                return InternalSubStore.Outcome.COMMITTED;
            } catch (IOException | RuntimeException uncertain) { return InternalSubStore.Outcome.UNCERTAIN; }
        }
    }

    private FileChannel lockChannel() throws IOException {
        InternalStorageScope.requireDirectory(scope.root());
        Path path = scope.root().resolve(LOCK); requireFile(path);
        return FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private InternalSubRecord readRecord() throws IOException {
        Path path = scope.root().resolve(RECORD); requireFile(path);
        byte[] bytes;
        // Bound allocation before consuming a possibly corrupt record; never follow file links.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > InternalRecordCodec.MAX_BYTES) throw new IOException("Invalid internal record size");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated internal record");
            if (channel.size() != size) throw new IOException("Changed internal record");
            bytes = buffer.array();
            channel.force(true);
        }
        syncDirectory(scope.root());
        try { return InternalRecordCodec.decode(bytes); }
        catch (IllegalArgumentException corrupt) { throw new IOException("Corrupt internal storage", corrupt); }
    }

    private void writeRecord(byte[] bytes) throws IOException {
        InternalStorageScope.requireDirectory(scope.root());
        Path temporary = Files.createTempFile(scope.root(), "pending-", ".record");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No fallback to non-atomic replace. Failed move/fsync means no acknowledgement.
            Files.move(temporary, scope.root().resolve(RECORD), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(scope.root());
            if (!Arrays.equals(bytes, InternalRecordCodec.encode(readRecord())))
                throw new IOException("Internal write readback mismatch");
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing internal storage file");
    }
    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel directory = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            directory.force(true);
        }
    }
}
