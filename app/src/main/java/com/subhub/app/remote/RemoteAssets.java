package com.subhub.app.remote;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/** Content-addressed, resumable asset staging bounded independently of compression. */
public final class RemoteAssets {
    public static final long MAX_ASSET_BYTES = 25L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    public static final int MAX_ENTRIES = 512;
    public static final int CHUNK_BYTES = 64 * 1024;
    private static final ConcurrentHashMap<String,Object> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final Object lock;

    public RemoteAssets(File directory) throws IOException {
        Path supplied = directory.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(supplied)) throw new IOException("Invalid asset directory");
        Files.createDirectories(supplied);
        root = supplied.toRealPath();
        lock = LOCKS.computeIfAbsent(root.toString(), ignored -> new Object());
    }

    public void reserve(String hash, long length) throws IOException {
        validate(hash);
        if (length <= 0 || length > MAX_ASSET_BYTES) throw new IOException("Asset size unsupported");
        synchronized (lock) {
            Path metadata = file(hash,"size");
            if (Files.exists(metadata)) {
                if (expected(hash) != length) throw new IOException("Conflicting asset length");
                return;
            }
            long total = length;
            int entries = 1;
            try (DirectoryStream<Path> sizes = Files.newDirectoryStream(root,"*.size")) {
                for (Path size : sizes) {
                    String name = size.getFileName().toString();
                    total += expected(name.substring(0,name.length()-5));
                    entries++;
                    if (total > MAX_TOTAL_BYTES || entries > MAX_ENTRIES) throw new IOException("Asset quota exceeded");
                }
            }
            Files.write(metadata,Long.toString(length).getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC);
        }
    }

    public long offset(String hash) throws IOException {
        synchronized (lock) {
            long expected = expected(hash);
            Path complete = file(hash,"blob");
            if (Files.exists(complete)) {
                if (Files.size(complete) != expected) throw new IOException("Invalid stored asset length");
                return expected;
            }
            Path part = file(hash,"part");
            long actual = Files.exists(part) ? Files.size(part) : 0;
            if (actual > expected) throw new IOException("Invalid partial asset length");
            return actual;
        }
    }

    public long append(String hash,long offset,byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > CHUNK_BYTES || offset < 0) {
            throw new IOException("Invalid asset chunk");
        }
        synchronized (lock) {
            long length = expected(hash);
            if (offset > length - bytes.length) throw new IOException("Asset chunk exceeds declared size");
            Path complete = file(hash,"blob");
            Path target = Files.exists(complete) ? complete : file(hash,"part");
            try (RandomAccessFile output = new RandomAccessFile(target.toFile(),"rw")) {
                long current = output.length();
                if (current > length || offset > current) throw new IOException("Asset chunk offset mismatch");
                if (offset < current) {
                    if (bytes.length > current-offset) throw new IOException("Overlapping asset chunk");
                    output.seek(offset);
                    byte[] previous = new byte[bytes.length]; output.readFully(previous);
                    if (!MessageDigest.isEqual(previous,bytes)) throw new IOException("Conflicting duplicate asset chunk");
                    return current;
                }
                if (Files.exists(complete)) throw new IOException("Asset already complete");
                output.seek(offset); output.write(bytes); output.getFD().sync();
                return output.length();
            }
        }
    }

    public boolean isComplete(String hash) throws IOException {
        synchronized (lock) {
            Path complete = file(hash,"blob");
            return Files.exists(complete) && Files.size(complete) == expected(hash);
        }
    }

    public File finish(String hash) throws IOException {
        synchronized (lock) {
            long length = expected(hash);
            Path complete = file(hash,"blob");
            Path part = Files.exists(complete) ? complete : file(hash,"part");
            if (!Files.exists(part) || Files.size(part) != length) throw new IOException("Asset incomplete");
            if (!hash.equals(digest(part.toFile()))) throw new IOException("Asset checksum mismatch");
            if (!part.equals(complete)) Files.move(part,complete,StandardCopyOption.ATOMIC_MOVE);
            return complete.toFile();
        }
    }

    public InputStream open(String hash) throws IOException {
        synchronized (lock) {
            if (!isComplete(hash)) throw new IOException("Asset incomplete");
            return new FileInputStream(file(hash,"blob").toFile());
        }
    }

    public byte[] chunk(String hash,long offset) throws IOException {
        synchronized (lock) {
            if (!isComplete(hash) || offset < 0 || offset >= expected(hash)) throw new IOException("Invalid asset offset");
            try (RandomAccessFile input = new RandomAccessFile(file(hash,"blob").toFile(),"r")) {
                byte[] result = new byte[(int)Math.min(CHUNK_BYTES,input.length()-offset)];
                input.seek(offset); input.readFully(result); return result;
            }
        }
    }

    public void restartPartial(String hash) throws IOException {
        synchronized (lock) {
            expected(hash);
            if (Files.exists(file(hash,"blob"))) throw new IOException("Cannot reset a completed asset");
            Files.deleteIfExists(file(hash,"part"));
        }
    }

    public static String digest(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer,0,count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private long expected(String hash) throws IOException {
        Path metadata = file(hash,"size");
        if (!Files.isRegularFile(metadata) || Files.size(metadata)>16) throw new IOException("Asset reservation missing");
        try {
            long value = Long.parseLong(new String(Files.readAllBytes(metadata),StandardCharsets.US_ASCII));
            if (value <= 0 || value > MAX_ASSET_BYTES) throw new IOException("Invalid asset reservation");
            return value;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid asset reservation"); }
    }
    private Path file(String hash,String suffix) throws IOException {
        validate(hash);
        Path result = root.resolve(hash + "." + suffix);
        if (Files.isSymbolicLink(result)) throw new IOException("Invalid asset path");
        return result;
    }
    private static void validate(String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid asset hash");
    }
}
