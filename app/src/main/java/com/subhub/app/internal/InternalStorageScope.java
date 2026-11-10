package com.subhub.app.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Stable owned data namespace. Visibility generations never change the storage location. */
public final class InternalStorageScope {
    public static final String TARGET_ID = "dummy_sub_preview";
    public enum Area { FILES, ASSETS, TASKS, HISTORY, CACHE }
    private final Path root;

    InternalStorageScope(Path root) throws IOException {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        requireDirectory(this.root);
    }
    public String targetId() { return TARGET_ID; }

    /** For scoped backends, not global TaskRuntime/StatsRepository/DailyStatsStore singletons. */
    public File directory(Area area) throws IOException {
        requireDirectory(root);
        Path child = root.resolve(Objects.requireNonNull(area).name().toLowerCase(java.util.Locale.ROOT));
        try { Files.createDirectory(child); }
        catch (FileAlreadyExistsException exists) {
            // Another caller may have created the same directory. Verification below still rejects
            // a symlink, non-directory or an unsafe ancestor; existence alone is not success.
        }
        requireDirectory(child);
        return child.toFile();
    }
    public File file(Area area, String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,120}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Invalid internal filename");
        Path file = directory(area).toPath().resolve(name);
        if (Files.isSymbolicLink(file) || (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Invalid internal file");
        return file.toFile();
    }
    static void requireDirectory(Path directory) throws IOException {
        // Called only with paths below the previously canonicalized trusted physical parent.
        // Never canonicalize an owned root/descendant: an owned symlink must fail, not alias data.
        Path absolute = directory.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent()) {
            if (!Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Internal directory unavailable");
        }
    }
    static Path canonicalTrustedParent(File parent) throws IOException {
        // The caller must obtain/validate this boundary independently (for example Android's
        // physical private directory), never from an owned asset/root path or external input.
        // Android may expose /data/user/0 through a legitimate /data/data alias.
        Path canonical = Objects.requireNonNull(parent).toPath().toRealPath();
        requireDirectory(canonical);
        return canonical;
    }
    Path root() { return root; }
}
