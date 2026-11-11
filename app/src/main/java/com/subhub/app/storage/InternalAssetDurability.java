package com.subhub.app.storage;

import com.subhub.app.remote.RemoteAssets;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Owned immutable assets are sealed before a durable internal manifest/task can reference them. */
public final class InternalAssetDurability {
    private InternalAssetDurability() { }
    public static File seal(File ownedDirectory, RemoteAssets assets, String hash, long length) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || length <= 0 || length > RemoteAssets.MAX_ASSET_BYTES)
            throw new IOException("Invalid owned asset descriptor");
        Path root = ownedDirectory.toPath().toAbsolutePath().normalize();
        for (Path part = root; part != null; part = part.getParent())
            if (!Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Owned asset directory unavailable");
        Path blob = root.resolve(hash + ".blob"), metadata = root.resolve(hash + ".size");
        requireFile(metadata);
        requireFile(Files.exists(blob, LinkOption.NOFOLLOW_LINKS) ? blob : root.resolve(hash + ".part"));
        File finished = assets.finish(hash);
        if (!finished.toPath().toAbsolutePath().normalize().equals(blob)) throw new IOException("Different owned asset namespace");
        requireFile(blob); requireFile(metadata);
        if (Files.size(blob) != length || Files.size(metadata) > 16
                || !Long.toString(length).equals(new String(Files.readAllBytes(metadata), StandardCharsets.US_ASCII)))
            throw new IOException("Owned asset length differs");
        try (FileChannel bytes = FileChannel.open(blob, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { bytes.force(true); }
        try (FileChannel size = FileChannel.open(metadata, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { size.force(true); }
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { directory.force(true); }
        // The owned directory can itself be newly created. Seal its parent entry too.
        try (FileChannel parent = FileChannel.open(root.getParent(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { parent.force(true); }
        // Read back the sealed bytes/metadata. Missing/corrupt published assets never become defaults.
        if (assets.length(hash) != length || !hash.equals(RemoteAssets.digest(assets.finish(hash))))
            throw new IOException("Owned asset readback differs");
        return finished;
    }
    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Owned asset file unavailable");
    }
}
