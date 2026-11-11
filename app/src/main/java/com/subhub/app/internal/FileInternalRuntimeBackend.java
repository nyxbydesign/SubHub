package com.subhub.app.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Independent, data-only owner storage. Explicit provisioning claims a retained marker outside
 * the data directory, then acknowledges its initial record. Ordinary opens never create anything.
 * A partial claim, lost established directory, bad checksum or swapped identity requires explicit
 * recovery. No missing-file path can silently publish empty counters. Call only on an owner worker.
 */
public final class FileInternalRuntimeBackend implements InternalRuntimePreferences.Backend {
    static final String DIRECTORY = "runtime-data", MARKER = "runtime-data.marker", RECORD = "data.record";
    private static final String MAGIC = "SUBHUB_INTERNAL_RUNTIME_V1";
    private static final Map<Path, Object> GATES = new java.util.concurrent.ConcurrentHashMap<>();
    private final InternalStorageScope scope;
    private final Object gate;
    private final Path directory, marker, record;
    private final Runnable workerCheck;
    private String identity;
    private Object markerKey, directoryKey;
    private boolean closed;

    private FileInternalRuntimeBackend(InternalStorageScope scope, Runnable workerCheck) throws IOException {
        this.workerCheck = Objects.requireNonNull(workerCheck); workerCheck.run();
        this.scope = Objects.requireNonNull(scope); InternalStorageScope.requireDirectory(scope.root());
        directory = scope.root().resolve(DIRECTORY); marker = scope.root().resolve(MARKER); record = directory.resolve(RECORD);
        gate = GATES.computeIfAbsent(marker, unused -> new Object());
    }
    /**
     * Only an independently authorized initial creation/migration may call this method. If all
     * provisioning evidence was erased, this method cannot infer that history from an empty path.
     * The provider must never choose provisioning merely because openExisting failed.
     */
    public static FileInternalRuntimeBackend provisionNew(InternalStorageScope scope) throws IOException {
        return provisionNew(scope, InternalRuntimePreferences::requireWorker);
    }
    static FileInternalRuntimeBackend provisionNew(InternalStorageScope scope, Runnable workerCheck) throws IOException {
        FileInternalRuntimeBackend backend = new FileInternalRuntimeBackend(scope, workerCheck);
        synchronized (backend.gate) {
            if (Files.exists(backend.directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Runtime data exists without an initial-provision decision");
            backend.identity = UUID.randomUUID().toString();
            try (FileChannel channel = FileChannel.open(backend.marker, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("Runtime-data provision lock unavailable");
                backend.markerKey = requireFile(backend.marker).fileKey();
                backend.writeMarker(channel, "STARTED"); syncDirectory(scope.root());
                Files.createDirectory(backend.directory);
                backend.directoryKey = requireDirectory(backend.directory).fileKey();
                backend.writeRecord(InternalRuntimeRecord.encode(new InternalRuntimeRecord(backend.identity, 0, Collections.emptyMap())));
                backend.writeMarker(channel, "READY"); syncDirectory(scope.root());
                backend.validateMarker(channel); backend.readRecord();
                return backend;
            }
        }
    }
    public static FileInternalRuntimeBackend openExisting(InternalStorageScope scope) throws IOException {
        return openExisting(scope, InternalRuntimePreferences::requireWorker);
    }
    static FileInternalRuntimeBackend openExisting(InternalStorageScope scope, Runnable workerCheck) throws IOException {
        FileInternalRuntimeBackend backend = new FileInternalRuntimeBackend(scope, workerCheck);
        synchronized (backend.gate) {
            backend.markerKey = requireFile(backend.marker).fileKey();
            backend.directoryKey = requireDirectory(backend.directory).fileKey();
            backend.read(); return backend;
        }
    }
    @Override public InternalRuntimeRecord read() throws IOException {
        workerCheck.run();
        synchronized (gate) {
            requireOpen();
            try (FileChannel channel = lockChannel(); FileLock lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("Runtime-data lock unavailable");
                validateMarker(channel); return readRecord();
            }
        }
    }
    @Override public InternalRuntimePreferences.Outcome compareAndSet(InternalRuntimeRecord expected, InternalRuntimeRecord next) {
        workerCheck.run();
        if (!Objects.requireNonNull(expected).identity.equals(Objects.requireNonNull(next).identity)
                || next.revision != Math.addExact(expected.revision, 1))
            throw new IllegalArgumentException("Runtime-data successor required");
        byte[] bytes = InternalRuntimeRecord.encode(next);
        synchronized (gate) {
            try {
                requireOpen();
                try (FileChannel channel = lockChannel(); FileLock lock = channel.lock()) {
                    if (!lock.isValid()) return InternalRuntimePreferences.Outcome.UNCERTAIN;
                    validateMarker(channel);
                    if (!expected.equals(readRecord())) return InternalRuntimePreferences.Outcome.CONFLICT;
                    writeRecord(bytes); validateMarker(channel);
                    return InternalRuntimePreferences.Outcome.COMMITTED;
                }
            } catch (IOException | RuntimeException uncertain) { return InternalRuntimePreferences.Outcome.UNCERTAIN; }
        }
    }
    @Override public void close() { synchronized (gate) { closed = true; } }
    private void requireOpen() throws IOException { if (closed) throw new IOException("Runtime-data backend closed"); }
    private FileChannel lockChannel() throws IOException {
        validateBoundary();
        return FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private void validateBoundary() throws IOException {
        InternalStorageScope.requireDirectory(scope.root());
        requireSame(markerKey, requireFile(marker).fileKey());
        requireSame(directoryKey, requireDirectory(directory).fileKey());
    }
    private void validateMarker(FileChannel channel) throws IOException {
        validateBoundary();
        long size = channel.size();
        if (size <= 0 || size > 256) throw new IOException("Runtime-data provision evidence missing or corrupt");
        ByteBuffer buffer = ByteBuffer.allocate((int) size); channel.position(0);
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Runtime-data provision evidence truncated");
        String text = new String(buffer.array(), StandardCharsets.US_ASCII);
        String prefix = MAGIC + "\nREADY\n";
        if (!text.startsWith(prefix) || !text.endsWith("\n")) throw new IOException("Runtime-data provisioning not acknowledged");
        String parsed = text.substring(prefix.length(), text.length() - 1);
        try {
            if (!UUID.fromString(parsed).toString().equals(parsed)) throw new IllegalArgumentException("Noncanonical identity");
        } catch (IllegalArgumentException invalid) { throw new IOException("Runtime-data provision identity corrupt", invalid); }
        if (identity != null && !identity.equals(parsed)) throw new IOException("Runtime-data provision identity changed");
        identity = parsed; channel.force(true); syncDirectory(scope.root()); validateBoundary();
    }
    private void writeMarker(FileChannel channel, String state) throws IOException {
        byte[] bytes = (MAGIC + "\n" + state + "\n" + identity + "\n").getBytes(StandardCharsets.US_ASCII);
        channel.position(0); ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.truncate(bytes.length); channel.force(true);
    }
    private InternalRuntimeRecord readRecord() throws IOException {
        validateBoundary(); requireFile(record); byte[] bytes;
        try (FileChannel channel = FileChannel.open(record, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > InternalRuntimeRecord.MAX_BYTES) throw new IOException("Invalid runtime-data record size");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated runtime-data record");
            if (channel.size() != size) throw new IOException("Changed runtime-data record");
            bytes = buffer.array(); channel.force(true);
        }
        syncDirectory(directory); validateBoundary();
        try {
            InternalRuntimeRecord result = InternalRuntimeRecord.decode(bytes);
            if (!result.identity.equals(identity)) throw new IOException("Runtime data does not match provisioning evidence");
            return result;
        } catch (IllegalArgumentException corrupt) { throw new IOException("Corrupt internal runtime data", corrupt); }
    }
    private void writeRecord(byte[] bytes) throws IOException {
        validateBoundary();
        if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) requireFile(record);
        Path temporary = Files.createTempFile(directory, "pending-", ".record");
        // Do not erase pending evidence on failure. A completed atomic move consumes its temp.
        // Stale temp files are inert and never selected as a read/recovery fallback.
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        validateBoundary();
        if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) requireFile(record);
        Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(directory);
        if (!Arrays.equals(bytes, InternalRuntimeRecord.encode(readRecord())))
            throw new IOException("Runtime-data write readback mismatch");
    }
    private static BasicFileAttributes requireFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Runtime-data file missing or invalid");
        return attributes;
    }
    private static BasicFileAttributes requireDirectory(Path path) throws IOException {
        InternalStorageScope.requireDirectory(path);
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    private static void requireSame(Object expected, Object actual) throws IOException {
        if (expected != null && !expected.equals(actual)) throw new IOException("Runtime-data storage boundary was replaced");
    }
    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
}
