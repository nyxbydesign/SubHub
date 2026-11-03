package com.subhub.app.remote.icons;

import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.util.RemoteAppIcon;
import com.subhub.app.util.RemoteAppIcons;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reconstructible PNGs only. The caller's verified current references are the sole authority. */
public final class AppIconCache implements RemoteAppIcons.Provider {
    public static final int MAX_DISK_BYTES = 16_777_216, MAX_ENTRIES = 256;
    private static final int MAX_PARTIALS = 8;
    private final File root;
    private final Validator validator;
    private final Object disk = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<RemoteAppIcon, byte[]> partials = new LinkedHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(Collections.emptyMap(), 0);

    interface Validator { void validate(RemoteAppIcon icon, byte[] bytes); }
    interface Guard { void check() throws IOException; }

    /** Pass the application's private cache directory; never RemoteAssets' root. One owner per root. */
    public AppIconCache(File privateCacheDirectory) throws IOException { this(privateCacheDirectory, IconPng::validate); }

    AppIconCache(File privateCacheDirectory, Validator validator) throws IOException {
        this.validator = Objects.requireNonNull(validator);
        root = new File(privateCacheDirectory.getCanonicalFile(), "remote-app-icons-v1");
        directory(root);
    }

    /** Immutable, memory-only projection. Retain this token across one request to fence revoke/regrant. */
    public static final class Snapshot {
        private final Map<String, RemoteAppIcon> refs;
        private final RemoteAppIcons.Authority authority;
        private final long revision;
        private Snapshot(Map<String, RemoteAppIcon> refs, long revision) {
            this.refs = Collections.unmodifiableMap(new LinkedHashMap<>(refs));
            this.authority = new RemoteAppIcons.Authority(this.refs.values());
            this.revision = revision;
        }
        public RemoteAppIcon identity(String peer, String generation, String packageName) {
            return refs.get(key(new RemoteAppIcon(peer, generation, packageName, null)));
        }
    }

    public Snapshot snapshot() { return snapshot; }

    /**
     * Backend worker publishes verified current mirror refs/descriptors, including offline refs.
     * Publish removals/revocations synchronously with backend invalidation. Cache history is never a grant.
     * This replaces the projection; a bare ref explicitly removes its previous descriptor.
     * Descriptor-only UI refreshes read snapshot(), rather than republishing backend references.
     */
    public void publishReferences(Collection<RemoteAppIcon> current) {
        Map<String, RemoteAppIcon> refs = new LinkedHashMap<>();
        for (RemoteAppIcon icon : current) {
            if (refs.put(key(Objects.requireNonNull(icon)), icon) != null)
                throw new IllegalArgumentException("Duplicate icon reference");
        }
        synchronized (this) {
            if (snapshot.refs.equals(refs)) return;
            snapshot = new Snapshot(refs, snapshot.revision + 1);
            partials.keySet().removeIf(icon -> !sameDescriptor(snapshot, icon));
        }
        changed();
    }

    public boolean containsReference(Snapshot token, RemoteAppIcon icon) {
        Snapshot current = snapshot;
        return current.revision == token.revision && current.refs.containsKey(key(icon));
    }

    boolean currentDescriptor(Snapshot token, RemoteAppIcon icon) {
        return containsReference(token, icon) && sameDescriptor(snapshot, icon);
    }

    /** Descriptor update after a currently authorized offer; no disk or backend calls under this monitor. */
    boolean offered(Snapshot token, RemoteAppIcon icon) {
        synchronized (this) {
            if (!containsReference(token, icon)) return false;
            if (icon.equals(snapshot.refs.get(key(icon)))) return true;
            Map<String, RemoteAppIcon> refs = new LinkedHashMap<>(snapshot.refs);
            refs.put(key(icon), icon);
            // Descriptor changes retain the authority epoch; a backend republish always advances it.
            snapshot = new Snapshot(refs, snapshot.revision);
            partials.keySet().removeIf(old -> key(old).equals(key(icon)) && !old.equals(icon));
        }
        changed();
        return containsReference(token, icon);
    }

    @Override public RemoteAppIcons.Authority currentAuthority() { return snapshot.authority; }
    @Override public void addInvalidationListener(Runnable listener) { listeners.addIfAbsent(Objects.requireNonNull(listener)); }
    @Override public void removeInvalidationListener(Runnable listener) { listeners.remove(listener); }

    @Override public byte[] load(RemoteAppIcon icon) throws IOException {
        Snapshot token = snapshot;
        if (!sameDescriptor(token, icon)) return null;
        byte[] bytes;
        File path = path(icon);
        synchronized (disk) {
            if (!regular(path) || path.length() != icon.descriptor.byteLength) return null;
            bytes = read(path, icon.descriptor.byteLength);
            if (bytes == null) return null;
        }
        try { validator.validate(icon, bytes); }
        catch (IllegalArgumentException invalid) { return null; }
        if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) return null;
        synchronized (disk) {
            safeChild(path);
            // Advisory LRU metadata only. A failed timestamp update never grants or invalidates authority.
            if (regular(path)) path.setLastModified(System.currentTimeMillis());
        }
        return containsReference(token, icon) && sameDescriptor(snapshot, icon) ? bytes : null;
    }

    byte[] resume(Snapshot token, RemoteAppIcon icon) throws IOException {
        if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) return new byte[0];
        byte[] complete = load(icon);
        if (complete != null) return complete;
        synchronized (this) {
            byte[] bytes = partials.get(icon);
            return containsReference(token, icon) && bytes != null ? bytes.clone() : new byte[0];
        }
    }

    void partial(Snapshot token, RemoteAppIcon icon, byte[] bytes) {
        synchronized (this) {
            if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) throw new SecurityException("Icon reference changed");
            if (bytes.length > icon.descriptor.byteLength) throw new IllegalArgumentException("Oversized partial");
            partials.remove(icon);
            while (partials.size() >= MAX_PARTIALS) partials.remove(partials.keySet().iterator().next());
            partials.put(icon, bytes.clone());
        }
    }

    void discard(RemoteAppIcon icon) { synchronized (this) { partials.remove(icon); } }

    void complete(Snapshot token, RemoteAppIcon icon, byte[] bytes, Guard currentGuard) throws IOException {
        validator.validate(icon, bytes);
        currentGuard.check();
        if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) throw new SecurityException("Icon reference changed");
        synchronized (disk) {
            currentGuard.check();
            if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) throw new SecurityException("Icon reference changed");
            File target = path(icon);
            directory(target.getParentFile());
            if (!regular(target) || target.length() != bytes.length || !Arrays.equals(bytes, read(target, bytes.length))) {
                // Reserve the temporary's full size too. Existing complete files change only by atomic replace.
                trim(target.getParentFile(), bytes.length, target);
                File temporary = new File(target.getParentFile(), target.getName().replace(".png", ".part"));
                safeChild(temporary);
                try {
                    Files.write(temporary.toPath(), bytes);
                    currentGuard.check();
                    if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) throw new SecurityException("Icon reference changed");
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary.toPath()); }
            }
        }
        currentGuard.check();
        if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) throw new SecurityException("Icon reference changed");
        discard(icon);
        changed();
    }

    private void changed() {
        // Listeners may schedule projections; one failed observer cannot prevent revocation publication.
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Observer owns its failure. */ }
        }
    }

    private File path(RemoteAppIcon icon) throws IOException {
        if (icon.descriptor == null) throw new IllegalArgumentException("Descriptor required");
        String scope = digest(icon.peer + "\n" + icon.generation);
        File directory = new File(root, scope);
        safeChild(directory);
        File path = new File(directory, digest(key(icon) + "\n" + icon.descriptor.sha256 + "\n"
                + icon.descriptor.byteLength + ":" + icon.descriptor.width + ":" + icon.descriptor.height) + ".png");
        safeChild(path);
        return path;
    }

    private static String key(RemoteAppIcon icon) { return icon.peer + "\n" + icon.generation + "\n" + icon.packageName; }
    private static String digest(String value) { return RemoteProtocol.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static boolean sameDescriptor(Snapshot current, RemoteAppIcon icon) {
        return icon.descriptor != null && icon.equals(current.refs.get(key(icon)));
    }
    private static boolean regular(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath());
    }
    private static byte[] read(File file, int length) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(bytes, offset, length - offset);
                if (count < 0) return null;
                offset += count;
            }
            return input.read() == -1 ? bytes : null;
        }
    }
    private static void directory(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Unsafe icon directory");
        Files.createDirectories(file.toPath());
        if (!Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid icon directory");
    }
    private void safeChild(File file) throws IOException {
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(file.toPath())
                || Files.isSymbolicLink(file.getParentFile().toPath())
                || !file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()))
            throw new IOException("Unsafe icon cache path");
    }
    private void trim(File directory, int incoming, File target) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect icon cache");
        List<File> entries = new ArrayList<>();
        long bytes = 0;
        for (File file : files) {
            safeChild(file);
            if (!file.getName().matches("[0-9a-f]{64}\\.(png|part)") || !regular(file))
                throw new IOException("Unexpected icon cache entry");
            if (file.getName().endsWith(".part")) { Files.delete(file.toPath()); continue; }
            entries.add(file); bytes += file.length();
        }
        entries.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int count = entries.size();
        for (File file : entries) {
            if (count < MAX_ENTRIES && bytes <= MAX_DISK_BYTES - incoming) break;
            if (file.equals(target)) continue;
            long length = file.length(); Files.delete(file.toPath()); bytes -= length; count--;
        }
        if (count >= MAX_ENTRIES || bytes > MAX_DISK_BYTES - incoming) throw new IOException("Icon cache quota exceeded");
    }
}
