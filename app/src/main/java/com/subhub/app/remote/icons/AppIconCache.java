package com.subhub.app.remote.icons;

import android.graphics.Bitmap;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reconstructible PNGs only. The caller's verified current references are the sole authority. */
public final class AppIconCache implements RemoteAppIcons.Provider {
    public static final int MAX_DISK_BYTES = 16_777_216, MAX_ENTRIES = 256;
    private static final int MAX_PARTIALS = 8;
    private final File root;
    private final AppIconIndex index;
    private final Validator validator;
    private final boolean preparePixels;
    private final Object disk = new Object();
    private final Object rehydration = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<RemoteAppIcon, byte[]> partials = new LinkedHashMap<>();
    private final Set<String> liveOffers = new HashSet<>();
    private long restoredRevision = -1;
    private volatile Snapshot snapshot = new Snapshot(Collections.emptyMap(), 0);
    // Full process cache of compact previews. The renderer checks exact authority again on use.
    private volatile Map<RemoteAppIcon, Bitmap> previews = Collections.emptyMap();

    interface Validator { void validate(RemoteAppIcon icon, byte[] bytes); }
    interface Guard { void check() throws IOException; }

    /** Pass the application's private cache directory; never RemoteAssets' root. One owner per root. */
    public AppIconCache(File privateCacheDirectory) throws IOException {
        this(privateCacheDirectory, IconPng::validate, true);
    }

    AppIconCache(File privateCacheDirectory, Validator validator) throws IOException {
        this(privateCacheDirectory, validator, false);
    }

    private AppIconCache(File privateCacheDirectory, Validator validator, boolean preparePixels) throws IOException {
        this.validator = Objects.requireNonNull(validator);
        this.preparePixels = preparePixels;
        index = new AppIconIndex(privateCacheDirectory);
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
            liveOffers.clear();
            partials.keySet().removeIf(icon -> !sameDescriptor(snapshot, icon));
            retainPreviews();
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
        boolean updated;
        synchronized (this) {
            if (!containsReference(token, icon)) return false;
            String key = key(icon);
            boolean firstOffer = liveOffers.add(key);
            updated = !icon.equals(snapshot.refs.get(key));
            if (!firstOffer && !updated) return true;
            if (updated) {
                Map<String, RemoteAppIcon> refs = new LinkedHashMap<>(snapshot.refs);
                refs.put(key, icon);
                // Descriptor changes retain the authority epoch; a backend republish always advances it.
                snapshot = new Snapshot(refs, snapshot.revision);
                partials.keySet().removeIf(old -> key(old).equals(key) && !old.equals(icon));
                retainPreviews();
            }
        }
        rememberOffer(icon);
        if (updated) changed();
        return containsReference(token, icon);
    }

    @Override public RemoteAppIcons.Authority currentAuthority() { return snapshot.authority; }
    @Override public Bitmap preview(RemoteAppIcon icon) { return previews.get(icon); }
    @Override public void addInvalidationListener(Runnable listener) { listeners.addIfAbsent(Objects.requireNonNull(listener)); }
    @Override public void removeInvalidationListener(Runnable listener) { listeners.remove(listener); }

    @Override public byte[] load(RemoteAppIcon icon) throws IOException {
        Snapshot token = snapshot;
        if (!sameDescriptor(token, icon)) return null;
        File path = path(icon);
        byte[] bytes = validatedBytes(icon, path);
        if (bytes == null) return null;
        if (!containsReference(token, icon) || !sameDescriptor(snapshot, icon)) return null;
        synchronized (disk) {
            safeChild(path);
            // Advisory LRU metadata only. A failed timestamp update never grants or invalidates authority.
            if (regular(path)) path.setLastModified(System.currentTimeMillis());
        }
        return containsReference(token, icon) && sameDescriptor(snapshot, icon) ? bytes : null;
    }

    private byte[] validatedBytes(RemoteAppIcon icon, File path) throws IOException {
        byte[] bytes = readIcon(icon, path);
        if (bytes == null) return null;
        try { validator.validate(icon, bytes); return bytes; }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private byte[] readIcon(RemoteAppIcon icon, File path) throws IOException {
        synchronized (disk) {
            if (!regular(path) || path.length() != icon.descriptor.byteLength) return null;
            return read(path, icon.descriptor.byteLength);
        }
    }

    private void rememberOffer(RemoteAppIcon icon) {
        synchronized (disk) {
            if (!icon.equals(snapshot.refs.get(key(icon)))) return;
            try {
                String scope = scope(icon), identity = digest(key(icon));
                Map<String, RemoteAppIcon.Descriptor> entries = index.read(scope);
                if (entries == null) {
                    File directory = new File(root, scope);
                    if (directory.isDirectory()) trim(directory, 0, icon.descriptor == null ? null : path(icon));
                    entries = new LinkedHashMap<>();
                }
                if (entries.containsKey(identity) && Objects.equals(entries.get(identity), icon.descriptor)) return;
                entries.remove(identity);
                if (entries.size() == AppIconIndex.MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
                entries.put(identity, icon.descriptor);
                index.write(scope, entries);
            } catch (IOException unavailable) { /* A cache write cannot prevent the current display or transfer. */ }
        }
    }

    /** Worker-only hydration for already verified reference membership. The index grants no access. */
    public void restoreCached(Snapshot token) {
        synchronized (rehydration) {
            if (snapshot.revision != token.revision || restoredRevision == token.revision) return;
            restoredRevision = token.revision;
            restoreReferences(token);
        }
    }

    private void restoreReferences(Snapshot token) {
        Map<String, List<RemoteAppIcon>> byScope = new LinkedHashMap<>();
        for (RemoteAppIcon icon : token.refs.values()) {
            if (icon.descriptor == null) byScope.computeIfAbsent(scope(icon), unused -> new ArrayList<>()).add(icon);
        }
        Map<RemoteAppIcon, RemoteAppIcon> restored = new LinkedHashMap<>();
        Map<RemoteAppIcon, Bitmap> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, List<RemoteAppIcon>> group : byScope.entrySet()) {
            if (snapshot.revision != token.revision) return;
            try {
                Map<String, RemoteAppIcon.Descriptor> stored;
                synchronized (disk) { stored = index.read(group.getKey()); }
                if (stored == null) stored = recoverLegacy(group.getKey(), group.getValue());
                for (RemoteAppIcon bare : group.getValue()) {
                    RemoteAppIcon.Descriptor descriptor = stored.get(digest(key(bare)));
                    if (descriptor == null) continue;
                    RemoteAppIcon icon = new RemoteAppIcon(bare.peer, bare.generation, bare.packageName, descriptor);
                    byte[] png = readIcon(icon, path(icon));
                    if (png == null) continue;
                    try {
                        if (preparePixels && decoded.size() < MAX_ENTRIES) decoded.put(icon, IconPng.preview(icon, png));
                        else validator.validate(icon, png);
                        restored.put(bare, icon);
                    } catch (IllegalArgumentException invalid) { /* Leave an invalid cached file unavailable. */ }
                }
            } catch (IOException unavailable) { /* One damaged or missing scope cannot hide other cached icons. */ }
        }
        boolean updated = false;
        synchronized (this) {
            if (snapshot.revision != token.revision) return;
            Map<String, RemoteAppIcon> refs = new LinkedHashMap<>(snapshot.refs);
            for (Map.Entry<RemoteAppIcon, RemoteAppIcon> entry : restored.entrySet()) {
                String key = key(entry.getKey());
                if (liveOffers.contains(key) || !entry.getKey().equals(refs.get(key))) continue;
                refs.put(key, entry.getValue());
                updated = true;
            }
            if (updated) {
                // Publish pixels first so a newly projected descriptor draws immediately.
                Map<RemoteAppIcon, Bitmap> ready = new LinkedHashMap<>(previews);
                decoded.forEach((icon, image) -> { if (icon.equals(refs.get(key(icon)))) ready.put(icon, image); });
                publishPreviews(ready);
                snapshot = new Snapshot(refs, snapshot.revision);
            }
        }
        if (updated) changed();
    }

    private Map<String, RemoteAppIcon.Descriptor> recoverLegacy(String scope, List<RemoteAppIcon> refs) throws IOException {
        Map<String, RemoteAppIcon.Descriptor> recovered = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Map<String, String> referenceKeys = new LinkedHashMap<>();
        for (RemoteAppIcon bare : refs) referenceKeys.put(key(bare), digest(key(bare)));
        synchronized (disk) {
            File directory = new File(root, scope);
            safeChild(directory);
            File[] files = directory.listFiles();
            if (files == null || files.length > MAX_ENTRIES + MAX_PARTIALS) return recovered;
            long scanned = 0;
            for (File file : files) {
                String name = file.getName();
                if (!name.matches("[0-9a-f]{64}\\.png") || !regular(file)) continue;
                long length = file.length();
                if (length < 57 || length > RemoteAppIcon.MAX_BYTES || (scanned += length) > MAX_DISK_BYTES) continue;
                byte[] png = read(file, (int) length);
                if (png == null) continue;
                RemoteAppIcon.Descriptor descriptor;
                try { descriptor = IconPng.descriptor(png); }
                catch (IllegalArgumentException invalid) { continue; }
                for (Map.Entry<String, String> reference : referenceKeys.entrySet()) {
                    if (!name.equals(filename(reference.getKey(), descriptor))) continue;
                    String identity = reference.getValue();
                    if (recovered.put(identity, descriptor) != null) ambiguous.add(identity);
                    break;
                }
            }
            ambiguous.forEach(recovered::remove);
            // Never overwrite metadata from a newer offer. This scan only recognises legacy filenames.
            Map<String, RemoteAppIcon.Descriptor> current = index.read(scope);
            if (current != null) return current;
            if (!recovered.isEmpty()) try {
                trim(directory, 0, null);
                index.write(scope, recovered);
            } catch (IOException ignored) { }
        }
        return recovered;
    }

    private void retainPreviews() {
        Map<RemoteAppIcon, Bitmap> ready = new LinkedHashMap<>(previews);
        if (ready.keySet().removeIf(icon -> !sameDescriptor(snapshot, icon))) publishPreviews(ready);
    }

    private void publishPreviews(Map<RemoteAppIcon, Bitmap> ready) {
        while (ready.size() > MAX_ENTRIES) ready.remove(ready.keySet().iterator().next());
        previews = Collections.unmodifiableMap(ready);
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
        Bitmap prepared = null;
        if (preparePixels && !previews.containsKey(icon)) prepared = IconPng.preview(icon, bytes);
        else validator.validate(icon, bytes);
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
        rememberOffer(icon);
        if (prepared != null) {
            synchronized (this) {
                if (currentDescriptor(token, icon)) {
                    Map<RemoteAppIcon, Bitmap> ready = new LinkedHashMap<>(previews);
                    ready.put(icon, prepared);
                    publishPreviews(ready);
                }
            }
        }
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
        File directory = new File(root, scope(icon));
        safeChild(directory);
        File path = new File(directory, filename(key(icon), icon.descriptor));
        safeChild(path);
        return path;
    }

    private static String key(RemoteAppIcon icon) { return icon.peer + "\n" + icon.generation + "\n" + icon.packageName; }
    private static String scope(RemoteAppIcon icon) { return digest(icon.peer + "\n" + icon.generation); }
    private static String filename(String reference, RemoteAppIcon.Descriptor descriptor) {
        return digest(reference + "\n" + descriptor.sha256 + "\n"
                + descriptor.byteLength + ":" + descriptor.width + ":" + descriptor.height) + ".png";
    }
    private static String digest(String value) { return RemoteProtocol.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static boolean sameDescriptor(Snapshot current, RemoteAppIcon icon) {
        return icon.descriptor != null && icon.equals(current.refs.get(key(icon)));
    }
    private static boolean regular(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath());
    }
    static byte[] read(File file, int length) throws IOException {
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
            if (count < MAX_ENTRIES && bytes <= MAX_DISK_BYTES - AppIconIndex.WRITE_RESERVE - incoming) break;
            if (file.equals(target)) continue;
            long length = file.length(); Files.delete(file.toPath()); bytes -= length; count--;
        }
        if (count >= MAX_ENTRIES || bytes > MAX_DISK_BYTES - AppIconIndex.WRITE_RESERVE - incoming)
            throw new IOException("Icon cache quota exceeded");
    }
}
