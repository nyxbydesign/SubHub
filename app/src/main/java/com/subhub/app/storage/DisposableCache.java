package com.subhub.app.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Budgets apply to disposable idle files. Active imports and editor files are kept. */
public final class DisposableCache {
    public static final long IMPORT_BUDGET = 32L * 1024 * 1024;
    public static final long EDITOR_BUDGET = 32L * 1024 * 1024;
    public static final long MAX_IDLE_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final Object LOCK = new Object();
    private static final Map<String, Integer> FILES = new HashMap<>();
    private static final Set<String> TEMPORARIES = new HashSet<>();
    private static final Set<String> PENDING_FILES = new HashSet<>();
    private static final Map<String, EditorLease> EDITORS = new HashMap<>();
    private static final Set<File> PENDING_EDITORS = new HashSet<>();
    private static final String IMPORT = "(?:artwork-|peer-import-|peer-artwork-|task-import-)-?[0-9]+\\.part";

    private DisposableCache() { }

    public static File temporary(File cache, String prefix) throws IOException {
        if (!java.util.Arrays.asList("artwork-", "peer-import-", "peer-artwork-", "task-import-").contains(prefix))
            throw new IOException("Unknown temporary file owner");
        synchronized (LOCK) {
            File root = directory(cache);
            trimFiles(root, IMPORT, IMPORT_BUDGET, System.currentTimeMillis(), 0);
            File file = File.createTempFile(prefix, ".part", root);
            String key = file.getCanonicalPath();
            retain(key); TEMPORARIES.add(key);
            return file;
        }
    }

    public static void releaseTemporary(File file) {
        if (file == null) return;
        synchronized (LOCK) {
            try {
                String path = file.getCanonicalPath();
                if (!TEMPORARIES.remove(path)) return;
                PENDING_FILES.add(path); release(path);
            } catch (IOException ignored) { /* An inaccessible disposable file can be retried later. */ }
        }
    }

    /** Only cache/editor UUID directories are managed; persistent assets and draft documents are not. */
    public static EditorLease editor(File cache, String token) throws IOException {
        if (token == null || !uuid(token))
            throw new IOException("Invalid editor identity");
        synchronized (LOCK) {
            File parent = directory(new File(directory(cache), "remote-editor"));
            File root = directory(new File(parent, token));
            String key = root.getCanonicalPath();
            EditorLease existing = EDITORS.get(key);
            if (existing != null) throw new IOException("Editor cache is already owned");
            File marker = new File(root, ".storage-lease");
            if (Files.isSymbolicLink(marker.toPath())) throw new IOException("Invalid editor lease");
            RandomAccessFile handle = new RandomAccessFile(marker, "rw");
            try {
                FileLock lease = handle.getChannel().tryLock();
                if (lease == null) throw new IOException("Editor cache is in use");
                EditorLease result = new EditorLease(root, handle, lease);
                EDITORS.put(key, result);
                return result;
            } catch (IOException | RuntimeException failure) { handle.close(); throw failure; }
        }
    }

    /** Dead-process editor bytes are derived from saved documents/assets, so they can be rebuilt. */
    public static void startup(File cache, long now) throws IOException {
        synchronized (LOCK) {
            File root = directory(cache);
            File editors = new File(root, "remote-editor");
            if (safeDirectory(editors)) {
                File[] children = editors.listFiles();
                if (children != null) for (File child : children)
                    if (uuid(child.getName()) && safeDirectory(child)) discardEditor(child);
            }
            prune(root, now);
        }
    }

    /** Run before editor restoration on startup, and after a closed editor releases its cache. */
    public static void prune(File cache, long now) throws IOException {
        synchronized (LOCK) {
            File root = directory(cache);
            trimFiles(root, IMPORT, IMPORT_BUDGET, now, 0);
            File editors = new File(root, "remote-editor");
            if (safeDirectory(editors)) {
                List<File> idle = new ArrayList<>();
                File[] children = editors.listFiles();
                if (children != null) for (File child : children) {
                    if (!uuid(child.getName()) || !safeDirectory(child) || editorInUse(child)) continue;
                    idle.add(child);
                }
                idle.sort(Comparator.comparingLong(DisposableCache::modified));
                long retained = 0;
                for (File child : idle) retained += size(child);
                for (File child : idle) {
                    long bytes = size(child);
                    if ((retained > EDITOR_BUDGET || expired(now, modified(child), MAX_IDLE_AGE))
                            && discardEditor(child)) retained -= bytes;
                }
            }
        }
    }

    public static Pin pin(File file) throws IOException {
        synchronized (LOCK) {
            if (!safeFile(file)) throw new IOException("Cache file is unavailable");
            String key = file.getCanonicalPath();
            retain(key);
            return new Pin(key);
        }
    }

    public static final class Pin implements AutoCloseable {
        private String key;
        private Pin(String key) { this.key = key; }
        @Override public void close() {
            synchronized (LOCK) {
                if (key != null) {
                    try { release(key); } catch (IOException ignored) { /* Retry deferred cache cleanup. */ }
                    key = null;
                }
            }
        }
    }

    public static final class EditorLease implements AutoCloseable {
        public final File directory;
        private final RandomAccessFile handle;
        private final FileLock lease;
        private boolean closed;
        private EditorLease(File directory, RandomAccessFile handle, FileLock lease) {
            this.directory = directory; this.handle = handle; this.lease = lease;
        }
        @Override public void close() throws IOException {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                EDITORS.remove(directory.getCanonicalPath());
                try { lease.release(); } finally { handle.close(); }
                if (!discardEditor(directory)) PENDING_EDITORS.add(directory);
            }
        }
    }

    private static void trimFiles(File root, String names, long budget, long now, long grace) throws IOException {
        File[] listed = root.listFiles();
        if (listed == null) return;
        List<File> idle = new ArrayList<>();
        long retained = 0;
        for (File file : listed) {
            if (!file.getName().matches(names) || !safeFile(file) || FILES.containsKey(file.getCanonicalPath())) continue;
            if (grace > 0 && !expired(now, file.lastModified(), grace)) continue;
            retained += file.length(); idle.add(file);
        }
        idle.sort(Comparator.comparingLong(File::lastModified));
        for (File file : idle) {
            long bytes = file.length();
            if ((retained > budget || expired(now, file.lastModified(), MAX_IDLE_AGE)) && file.delete()) retained -= bytes;
        }
    }

    private static boolean editorInUse(File root) throws IOException {
        String key = root.getCanonicalPath();
        if (EDITORS.containsKey(key)) return true;
        for (String path : FILES.keySet()) if (path.startsWith(key + File.separator)) return true;
        File marker = new File(root, ".storage-lease");
        if (!marker.exists()) return false;
        if (Files.isSymbolicLink(marker.toPath())) return true;
        try (RandomAccessFile handle = new RandomAccessFile(marker, "rw")) {
            try (FileLock lease = handle.getChannel().tryLock()) { return lease == null; }
            catch (OverlappingFileLockException busy) { return true; }
        }
    }

    private static boolean discardEditor(File root) throws IOException {
        if (!root.exists()) return true;
        if (!uuid(root.getName()) || !safeDirectory(root) || editorInUse(root)) return false;
        List<File> entries = new ArrayList<>();
        if (!collect(root, entries, 0)) return false;
        for (File entry : entries) if (!entry.delete() && entry.exists()) return false;
        return true;
    }

    private static boolean collect(File root, List<File> out, int depth) {
        if (depth > 12 || Files.isSymbolicLink(root.toPath())) return false;
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) return false;
            for (File child : children) if (!collect(child, out, depth + 1)) return false;
        } else if (!root.isFile()) return false;
        out.add(root); return true;
    }

    private static File directory(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Cache directory is a link");
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cache directory unavailable");
        return file.getCanonicalFile();
    }
    private static void retain(String key) { FILES.put(key, FILES.getOrDefault(key, 0) + 1); }
    private static void release(String key) throws IOException {
        int references = FILES.getOrDefault(key, 0);
        if (references > 1) FILES.put(key, references - 1); else FILES.remove(key);
        if (!FILES.containsKey(key) && PENDING_FILES.remove(key)) {
            File file = new File(key);
            if (safeFile(file)) file.delete();
        }
        for (File root : new ArrayList<>(PENDING_EDITORS))
            if (discardEditor(root)) PENDING_EDITORS.remove(root);
    }
    private static boolean safeDirectory(File file) { return !Files.isSymbolicLink(file.toPath()) && file.isDirectory(); }
    private static boolean safeFile(File file) { return !Files.isSymbolicLink(file.toPath()) && file.isFile(); }
    private static boolean uuid(String value) { try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException invalid) { return false; } }
    private static boolean expired(long now, long then, long age) { return then > 0 && now >= then && now - then >= age; }
    private static long modified(File root) { return modified(root, 0); }
    private static long modified(File root, int depth) {
        if (depth > 12) return Long.MAX_VALUE;
        long result = root.lastModified(); File[] children = root.listFiles();
        if (children != null) for (File child : children) if (!Files.isSymbolicLink(child.toPath())) result = Math.max(result, child.isDirectory() ? modified(child, depth + 1) : child.lastModified());
        return result;
    }
    private static long size(File root) { return size(root, 0); }
    private static long size(File root, int depth) {
        if (depth > 12 || Files.isSymbolicLink(root.toPath())) return 0;
        if (root.isFile()) return root.length();
        long bytes = 0; File[] children = root.listFiles();
        if (children != null) for (File child : children) bytes += size(child, depth + 1);
        return bytes;
    }
}
